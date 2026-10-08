package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.LocalTransactor

/** Minimal row source over either a raw or a Room-managed database. */
internal fun interface Rows {
    fun query(sql: String, args: Array<String>): List<Map<String, Any?>>
}

private fun SQLiteDatabase.asRows(): Rows = Rows { sql, args ->
    rawQuery(sql, args).use { it.readAll() }
}

private fun SupportSQLiteDatabase.asRows(): Rows = Rows { sql, args ->
    query(sql, args).use { it.readAll() }
}

private fun Cursor.readAll(): List<Map<String, Any?>> {
    val out = ArrayList<Map<String, Any?>>()
    while (moveToNext()) out.add(rowMap())
    return out
}

private fun Cursor.rowMap(): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>()
    for (i in 0 until columnCount) {
        m[getColumnName(i)] = when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            Cursor.FIELD_TYPE_BLOB -> getBlob(i)
            else -> getString(i)
        }
    }
    return m
}

/**
 * What [LegacyArchive.inspect] may reveal about the legacy global database: whether it exists,
 * how many unsynced changes / local-only items it holds, whether recovery is finished (or held by
 * another account), and why it could not be read. Deliberately NO content (titles, notes, ids,
 * ...) — the file's owner is unknown, so nothing about it may be shown before the signed-in user
 * explicitly confirms recovery.
 *
 *  - [pendingChanges]: legacy outbox entries not yet handed to the inspecting account.
 *  - [localOnlyItems]: legacy rows with no outbox entry that this account lacks (venue notes,
 *    theater interest, and titles/viewings/outings/episode events/ratings/reviews/lists/list items).
 *  - [skippedRows]: entries handed over by an earlier restore whose rows could not be restored yet.
 *  - [claimed]: nothing left to offer — finished for this account, or held by [claimedByOther].
 */
data class LegacyArchiveStatus(
    val present: Boolean,
    val pendingChanges: Int,
    val claimed: Boolean,
    val error: String?,
    val localOnlyItems: Int = 0,
    val skippedRows: Int = 0,
    val claimedByOther: Boolean = false,
)

sealed interface LegacyRestoreResult {
    /** [restoredOutboxEntries] = outbox entries newly added to the target; [restoredRows] =
     *  local rows written/patched/deleted; [skippedRows] = items that could not be restored (a
     *  missing source row or a missing FK parent) — the archive then stays available for a retry. */
    data class Success(
        val restoredOutboxEntries: Int,
        val restoredRows: Int,
        val skippedRows: Int,
    ) : LegacyRestoreResult

    data class Failure(val message: String) : LegacyRestoreResult
}

/**
 * Recovery of unsynced work from the pre-isolation global Room file `cinemarchive.db`.
 *
 * Privacy / recovery contract:
 *  - The legacy file's owner is unknown. It is NEVER opened as an active runtime database and
 *    NEVER modified or deleted by this class. Every read happens on a disposable copy, deleted
 *    afterwards.
 *  - [inspect] has no side effects beyond a transient copy (and best-effort repair of the claim
 *    file) and reveals only counts/flags.
 *  - [restoreInto] runs only on an explicit user confirmation, into the CURRENT account's
 *    database. Outbox-backed work: the legacy `mutation_outbox` entries and the local rows they
 *    refer to are copied and the entries re-enqueued (so the edit shows up and is pushed). With
 *    `includeLocalOnly`, legacy rows that have no outbox entry and are absent from this account
 *    (plus venue notes, theater interest, ticket fields) are copied LOCALLY ONLY — never enqueued,
 *    because whether they reached the server is unknowable.
 *  - Idempotence: the merge commits, in the SAME target transaction, a `legacy_restore_receipt`
 *    per restored outbox entry (and an `archive:<id>` receipt when nothing was skipped). An entry
 *    with a receipt is never re-enqueued, even after it was pushed and removed from the outbox.
 *    The receipt is the source of truth; the global claim file (`legacy-archive-claim`, holding
 *    the owner key and `pending|done`) is written and fsync'd BEFORE the transaction, and is only
 *    a guard that stops a second account from adopting the same archive. A missing/pending claim
 *    next to a committed complete receipt is repaired.
 *  - A restore with skipped rows never finalizes the claim: the archive stays available to the
 *    same owner for a retry.
 */
class LegacyArchive(private val context: Context) {

    /** Test hook: runs inside the transaction after verification, just before commit. */
    internal var beforeCommitHook: (() -> Unit)? = null

    /** Test hook: runs after the commit, before the claim is marked done (simulates a crash). */
    internal var afterCommitHook: (() -> Unit)? = null

    private val legacyFile: File get() = context.getDatabasePath(LibraryDatabase.LEGACY_DATABASE_NAME)
    private val claimFile: File get() = File(context.filesDir, CLAIM_FILE)
    private val oldMarkerFile: File get() = File(context.filesDir, OLD_MARKER)

    // ---- claim -------------------------------------------------------------------------------

    /** [owner] null = claimed by an unknown owner (old marker file or unreadable claim). */
    private data class Claim(val owner: String?, val state: String)

    private enum class Availability { AVAILABLE, COMPLETE, OTHER }

    private fun readClaim(): Claim? {
        val f = claimFile
        if (f.isFile) {
            return try {
                val lines = f.readLines().associate { l -> l.substringBefore('=') to l.substringAfter('=', "") }
                val owner = lines["owner"].orEmpty()
                val state = lines["state"].orEmpty()
                if (owner.isEmpty() || state !in setOf(PENDING, DONE)) Claim(null, DONE) else Claim(owner, state)
            } catch (_: Exception) {
                Claim(null, DONE)
            }
        }
        if (oldMarkerFile.exists()) return Claim(null, DONE)
        return null
    }

    /** Atomic + fsync'd; throws on any failure. */
    private fun writeClaim(owner: String, state: String) {
        val f = claimFile
        if (f.exists() && !f.isFile) throw IOException("claim path is not a file")
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write("owner=$owner\nstate=$state\n".toByteArray())
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun receiptKeys(target: LibraryDatabase, archiveId: String): Set<String> =
        target.openHelper.readableDatabase
            .query("SELECT `key` FROM legacy_restore_receipt WHERE archiveId = ?", arrayOf<Any?>(archiveId))
            .use { c ->
                val out = HashSet<String>()
                while (c.moveToNext()) out.add(c.getString(0))
                out
            }

    private fun skippedCount(target: LibraryDatabase, archiveId: String): Int =
        target.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM legacy_restore_receipt r WHERE r.archiveId = ? AND r.kind = 'skipped' " +
                "AND EXISTS (SELECT 1 FROM mutation_outbox o WHERE 'skip:' || o.id = r.key)",
            arrayOf<Any?>(archiveId),
        ).use {
            it.moveToFirst()
            it.getInt(0)
        }

    /** The reconciliation rule: the CURRENT owner's complete receipt wins over the claim file
     *  (and repairs it); otherwise a claim held by someone else (or unknown) blocks. */
    private fun evaluate(keys: Set<String>, archiveId: String?, ownerKey: String?): Availability {
        val claim = readClaim()
        if (archiveId != null && "archive:$archiveId" in keys) {
            if (ownerKey != null && (claim == null || (claim.owner == ownerKey && claim.state != DONE))) {
                try {
                    writeClaim(ownerKey, DONE)
                } catch (_: Exception) {
                }
            }
            return Availability.COMPLETE
        }
        if (claim == null) return Availability.AVAILABLE
        if (claim.owner == null) return Availability.OTHER
        if (ownerKey != null && claim.owner != ownerKey) return Availability.OTHER
        return if (claim.state == DONE) Availability.COMPLETE else Availability.AVAILABLE
    }

    // ---- inspect -----------------------------------------------------------------------------

    /**
     * Counts-only, side-effect-free (apart from a transient copy) look at the legacy file. With
     * [target] + [ownerKey] the counts are relative to that account (already-delivered entries and
     * rows it already has are excluded); with `target == null` only legacy-side counts are given
     * and `localOnlyItems` = venue notes + theater interest.
     */
    fun inspect(target: LibraryDatabase? = null, ownerKey: String? = null): LegacyArchiveStatus {
        val file = legacyFile
        if (!file.exists()) {
            val a = evaluate(emptySet(), null, ownerKey)
            return LegacyArchiveStatus(
                present = false, pendingChanges = 0, claimed = a != Availability.AVAILABLE,
                error = null, claimedByOther = a == Availability.OTHER,
            )
        }
        val workDir = newWorkDir("li-")
        return try {
            val archiveId = sha256(file)
            val keys = if (target != null) receiptKeys(target, archiveId) else emptySet()
            val a = evaluate(keys, archiveId, ownerKey)
            if (a != Availability.AVAILABLE) {
                return LegacyArchiveStatus(
                    present = true, pendingChanges = 0, claimed = true, error = null,
                    claimedByOther = a == Availability.OTHER,
                )
            }
            val copy = copyWithSidecars(file, File(workDir, file.name))
            val counts = withRaw(copy) { db ->
                val src = db.asRows()
                val entries = src.query("SELECT id, entityType, entityId, operation FROM mutation_outbox", emptyArray())
                val pending = entries.count { "entry:${it["id"]}" !in keys }
                val dst = target?.openHelper?.readableDatabase?.asRows()
                val localOnly = LocalOnly.candidates(src, dst, LocalOnly.covered(src, entries)).size
                pending to localOnly
            }
            LegacyArchiveStatus(
                present = true,
                pendingChanges = counts.first,
                claimed = false,
                error = null,
                localOnlyItems = counts.second,
                skippedRows = if (target != null) skippedCount(target, archiveId) else 0,
            )
        } catch (t: Throwable) {
            LegacyArchiveStatus(
                present = true,
                pendingChanges = 0,
                claimed = false,
                error = t.message ?: t.javaClass.simpleName,
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Opens the (disposable) copy read-only, retrying read-write: a read-only open can refuse a
     *  WAL-mode file (header says WAL) whose -shm/-wal sidecars are absent. Never used on the original. */
    private fun <T> withRaw(copy: File, block: (SQLiteDatabase) -> T): T {
        fun attempt(flags: Int): T {
            val db = SQLiteDatabase.openDatabase(copy.absolutePath, null, flags)
            try {
                return block(db)
            } finally {
                db.close()
            }
        }
        return try {
            attempt(SQLiteDatabase.OPEN_READONLY)
        } catch (e: SQLiteException) {
            attempt(SQLiteDatabase.OPEN_READWRITE)
        }
    }

    private fun countOutbox(copy: File): Int = withRaw(copy) { db ->
        db.rawQuery("SELECT COUNT(*) FROM mutation_outbox", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
    }

    // ---- restore -----------------------------------------------------------------------------

    /**
     * See the class kdoc. [ownerKey] = `OwnerNamespace.key(userId)` of the account owning
     * [target]. [includeLocalOnly] additionally copies the local-only rows (never enqueued).
     * Never throws except for cancellation.
     */
    suspend fun restoreInto(
        target: LibraryDatabase,
        transactor: LocalTransactor,
        ownerKey: String,
        includeLocalOnly: Boolean,
    ): LegacyRestoreResult =
        restoreMutex.withLock {
            withContext(Dispatchers.IO) { doRestore(target, transactor, ownerKey, includeLocalOnly) }
        }

    private suspend fun doRestore(
        target: LibraryDatabase,
        transactor: LocalTransactor,
        ownerKey: String,
        includeLocalOnly: Boolean,
    ): LegacyRestoreResult {
        val file = legacyFile
        if (!file.exists()) return LegacyRestoreResult.Failure("No legacy archive found.")
        // The working copy lives in the databases directory under a fixed disposable name (one restore
        // at a time, guarded by restoreMutex); the original is only ever read.
        context.deleteDatabase(WORK_DB_NAME)
        var legacyDb: LibraryDatabase? = null
        try {
            val archiveId = sha256(file)
            when (evaluate(receiptKeys(target, archiveId), archiveId, ownerKey)) {
                Availability.COMPLETE -> return LegacyRestoreResult.Failure("The legacy archive was already restored.")
                Availability.OTHER -> return LegacyRestoreResult.Failure(
                    "Recovery of this archive was already started or completed by another account on this phone.",
                )
                Availability.AVAILABLE -> Unit
            }
            val workFile = copyWithSidecars(file, context.getDatabasePath(WORK_DB_NAME))
            // Room's open helper silently REPLACES a corrupt file with a fresh empty one, which would
            // look like a successful empty restore. Prove the copy is a readable database with an
            // outbox table first, with a raw open that fails loudly instead.
            try {
                countOutbox(workFile)
            } catch (t: Exception) {
                return LegacyRestoreResult.Failure("The legacy archive could not be read: ${t.message ?: t.javaClass.simpleName}")
            }
            val opened = LibraryDatabase.createForRecovery(context, WORK_DB_NAME)
            legacyDb = opened
            val source = try {
                opened.openHelper.readableDatabase
            } catch (t: Exception) {
                return LegacyRestoreResult.Failure("The legacy archive could not be opened: ${t.message ?: t.javaClass.simpleName}")
            }
            // Durable claim BEFORE the target transaction. If it cannot be recorded, nothing changes.
            val hadClaim = readClaim() != null
            try {
                writeClaim(ownerKey, PENDING)
            } catch (t: Exception) {
                return LegacyRestoreResult.Failure(
                    "Couldn't record the recovery claim, nothing was restored: ${t.message ?: t.javaClass.simpleName}",
                )
            }
            val hook = beforeCommitHook
            val stats = try {
                transactor.run {
                    val merger = Merger(source, target.openHelper.writableDatabase, archiveId, includeLocalOnly)
                    merger.merge()
                    merger.verify()
                    hook?.invoke()
                    merger
                }
            } catch (t: Throwable) {
                // Rolled back: nothing was delivered, so a claim we freshly created is released.
                if (!hadClaim) try {
                    claimFile.delete()
                } catch (_: Exception) {
                }
                throw t
            }
            // Committed. The receipts are durable; the claim update is best-effort and repaired later.
            afterCommitHook?.invoke()
            if (stats.completeWritten) {
                try {
                    writeClaim(ownerKey, DONE)
                } catch (_: Exception) {
                }
            }
            return LegacyRestoreResult.Success(stats.insertedOutbox, stats.restoredRows, stats.skippedRows)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Exception) {
            return LegacyRestoreResult.Failure(t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                legacyDb?.close()
            } catch (_: Exception) {
            }
            context.deleteDatabase(WORK_DB_NAME)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun newWorkDir(prefix: String): File =
        File(context.cacheDir, prefix + UUID.randomUUID().toString().take(8)).also { it.mkdirs() }

    private fun copyWithSidecars(src: File, dst: File): File {
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        for (suffix in SIDECARS) {
            val side = File(src.path + suffix)
            if (side.exists()) side.copyTo(File(dst.path + suffix), overwrite = true)
        }
        return dst
    }

    private class TableSpec(
        val name: String,
        val pk: String,
        /** (child column, parent table) pairs backed by a Room foreign key. */
        val parents: List<Pair<String, String>> = emptyList(),
    )

    /** Which legacy rows count as "local-only": no outbox entry covers them and the account lacks them. */
    private object LocalOnly {
        /** FK-safe order (parents first). */
        val TABLES = listOf(
            "titles", "lists", "viewings", "cinema_outings", "episode_watch_events",
            "episode_ratings", "episode_reviews", "list_items", "venue_notes", "theater_interest",
        )

        fun isLocalTable(table: String) = table == "venue_notes" || table == "theater_interest"

        /** "table|id" keys that a legacy outbox entry already accounts for. */
        fun covered(src: Rows, entries: List<Map<String, Any?>>): Set<String> {
            val out = HashSet<String>()
            for (e in entries) {
                val spec = ENTITY_SPECS[e["entityType"] as? String ?: continue] ?: continue
                val id = e["entityId"] as? String ?: continue
                out.add(spec.name + "|" + id)
                if (spec.name == "titles" && e["operation"] == "insert") {
                    for ((table, sql) in GRAPH_QUERIES) {
                        val pk = SPECS.getValue(table).pk
                        val rows = try {
                            src.query(sql, arrayOf(id))
                        } catch (_: SQLiteException) {
                            emptyList()
                        }
                        for (r in rows) (r[pk] as? String)?.let { out.add("$table|$it") }
                    }
                }
            }
            return out
        }

        private fun ids(rows: Rows, spec: TableSpec): Set<String> = try {
            rows.query("SELECT `${spec.pk}` FROM `${spec.name}`", emptyArray()).mapNotNull { it[spec.pk] as? String }.toSet()
        } catch (_: SQLiteException) {
            emptySet() // table absent in an old legacy schema
        }

        /** [dst] null = no target given: only the local-only tables, all of their rows. */
        fun candidates(src: Rows, dst: Rows?, covered: Set<String>): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            for (table in TABLES) {
                val local = isLocalTable(table)
                if (dst == null && !local) continue
                val spec = SPECS.getValue(table)
                val have = if (dst == null) emptySet() else ids(dst, spec)
                for (id in ids(src, spec)) {
                    if (id in have) continue
                    if (!local && "$table|$id" in covered) continue
                    out.add(table to id)
                }
            }
            return out
        }
    }

    private class Merger(
        private val src: SupportSQLiteDatabase,
        private val dst: SupportSQLiteDatabase,
        private val archiveId: String,
        private val includeLocalOnly: Boolean,
    ) {
        var insertedOutbox = 0
        var restoredRows = 0
        var skippedRows = 0
        var completeWritten = false
        private val dstColumns = HashMap<String, List<String>>()
        private val done = HashSet<String>()
        private var newOutboxIds = ArrayList<String>()
        private var deletedKeys = emptySet<String>()
        private val now = Instant.now().toString()

        fun merge() {
            val entries = src.rows("SELECT * FROM mutation_outbox ORDER BY createdAt, id")
            deletedKeys = entries
                .filter { it["operation"] == "delete" }
                .mapNotNull { e -> ENTITY_SPECS[e["entityType"] as String]?.let { it.name + "|" + e["entityId"] } }
                .toSet()
            val receipts = dst.rows(
                "SELECT `key` FROM legacy_restore_receipt WHERE archiveId = ?", arrayOf(archiveId),
            ).map { it["key"] as String }.toSet()

            // (a) outbox-backed work, oldest first: enqueue + restore the rows (unless a receipt
            // says the entry was already delivered to this account).
            for (e in entries) handleEntry(e, receipts)

            val srcRows = src.asRows()
            val covered = LocalOnly.covered(srcRows, entries)
            val candidates = LocalOnly.candidates(srcRows, dst.asRows(), covered)

            // (b) local-only data: copied locally, never enqueued.
            if (includeLocalOnly) {
                for ((table, id) in candidates) restoreLocalOnly(table, id)
                mergeTicketFields()
            }

            // (c) finished only if nothing was skipped and nothing local-only was left behind.
            if (skippedRows == 0 && (includeLocalOnly || candidates.isEmpty())) {
                writeReceipt("archive:$archiveId", "complete")
                completeWritten = true
            }
        }

        private fun handleEntry(e: Map<String, Any?>, receipts: Set<String>) {
            val id = e["id"] as String
            val entryKey = "entry:$id"
            val skipKey = "skip:$id"
            if (entryKey !in receipts) {
                if (insertIgnore("mutation_outbox", e)) insertedOutbox++
                newOutboxIds.add(id)
                val before = skippedRows
                applyEntry(e)
                writeReceipt(entryKey, "entry")
                if (skippedRows > before) writeReceipt(skipKey, "skipped")
            } else if (skipKey in receipts) {
                if (exists(dst, OUTBOX_SPEC, id)) {
                    // Delivered earlier but its row couldn't be restored: try again (still protected by
                    // the pending entry). Never re-enqueued.
                    val before = skippedRows
                    applyEntry(e)
                    if (skippedRows == before) removeReceipt(skipKey)
                } else {
                    removeReceipt(skipKey) // already pushed and removed: nothing left to retry
                }
            }
        }

        private fun writeReceipt(key: String, kind: String) {
            dst.execSQL(
                "INSERT OR IGNORE INTO legacy_restore_receipt (`key`, archiveId, kind, restoredAt) VALUES (?, ?, ?, ?)",
                arrayOf(key, archiveId, kind, now),
            )
        }

        private fun removeReceipt(key: String) {
            dst.execSQL("DELETE FROM legacy_restore_receipt WHERE `key` = ?", arrayOf(key))
        }

        private fun restoreLocalOnly(table: String, id: String) {
            val spec = SPECS.getValue(table)
            if (exists(dst, spec, id)) return // already brought in as an FK parent of an earlier row
            val row = readRow(src, spec, id) ?: return
            if (LocalOnly.isLocalTable(table)) {
                if (insertIgnore(table, row)) restoredRows++
            } else if (!putRow(spec, row)) {
                skippedRows++
            }
        }

        private fun applyEntry(e: Map<String, Any?>) {
            val spec = ENTITY_SPECS[e["entityType"] as String]
            val id = e["entityId"] as String
            if (spec == null) {
                skippedRows++
                return
            }
            when (e["operation"] as String) {
                "delete" -> {
                    done.remove("graph|${spec.name}|$id")
                    done.remove("row|${spec.name}|$id")
                    if (deleteRow(spec, id) > 0) restoredRows++
                }
                "insert" -> if (spec.name == "titles") copyTitleGraph(spec, id) else copyRow(spec, id)
                "update" -> patchOrCopy(spec, id, payloadKeys(e["payloadJson"] as? String))
                else -> copyRow(spec, id)
            }
        }

        private fun missingSource(spec: TableSpec, id: String) {
            if ((spec.name + "|" + id) !in deletedKeys) skippedRows++
        }

        private fun copyRow(spec: TableSpec, id: String) {
            if (!done.add("row|${spec.name}|$id")) return
            val row = readRow(src, spec, id)
            if (row == null) {
                missingSource(spec, id)
                return
            }
            if (!putRow(spec, row)) skippedRows++
        }

        private fun patchOrCopy(spec: TableSpec, id: String, keys: Set<String>) {
            if (!exists(dst, spec, id)) {
                copyRow(spec, id)
                return
            }
            val row = readRow(src, spec, id)
            if (row == null) {
                missingSource(spec, id)
                return
            }
            val cols = keys.filter { it != spec.pk && it in columnsOf(dst, spec.name) && it in row.keys }
            if (cols.isEmpty()) {
                if (!putRow(spec, row)) skippedRows++
                return
            }
            val sql = "UPDATE `${spec.name}` SET " + cols.joinToString(", ") { "`$it` = ?" } + " WHERE `${spec.pk}` = ?"
            dst.execSQL(sql, (cols.map { row[it] } + id).toTypedArray())
            restoredRows++
        }

        private fun copyTitleGraph(spec: TableSpec, id: String) {
            if (!done.add("graph|${spec.name}|$id")) return
            val title = readRow(src, spec, id)
            if (title == null) {
                missingSource(spec, id)
                return
            }
            if (!putRow(spec, title)) {
                skippedRows++
                return
            }
            for ((table, sql) in GRAPH_QUERIES) {
                val childSpec = SPECS.getValue(table)
                for (child in src.rows(sql, arrayOf(id))) {
                    if (!putRow(childSpec, child)) skippedRows++
                }
            }
        }

        /** Writes [row] with upsert semantics (NOT `INSERT OR REPLACE`, whose delete-then-insert
         *  would cascade-delete the target row's children). False when a required FK parent
         *  exists in neither database. */
        private fun putRow(spec: TableSpec, row: Map<String, Any?>): Boolean {
            for ((col, parent) in spec.parents) {
                val pid = row[col] as? String ?: continue
                if (!ensure(SPECS.getValue(parent), pid)) return false
            }
            val id = row[spec.pk] as String
            if (spec.name == "list_items") {
                // (listId, titleId) is unique; a different id for the same pair would abort the insert.
                dst.execSQL(
                    "DELETE FROM `list_items` WHERE `listId` = ? AND `titleId` = ? AND `id` <> ?",
                    arrayOf(row["listId"], row["titleId"], id),
                )
            }
            val cols = row.keys.filter { it in columnsOf(dst, spec.name) }
            val updates = cols.filter { it != spec.pk }
            // Portable upsert (UPDATE then INSERT OR IGNORE) instead of `ON CONFLICT DO UPDATE`, which
            // needs SQLite 3.24+ and isn't available to every test runtime; and never INSERT OR REPLACE,
            // whose delete-then-insert would cascade-delete the target row's children.
            if (updates.isNotEmpty()) {
                dst.execSQL(
                    "UPDATE `${spec.name}` SET " + updates.joinToString(",") { "`$it` = ?" } + " WHERE `${spec.pk}` = ?",
                    (updates.map { row[it] } + id).toTypedArray(),
                )
            }
            dst.execSQL(
                "INSERT OR IGNORE INTO `${spec.name}` (" + cols.joinToString(",") { "`$it`" } +
                    ") VALUES (" + cols.joinToString(",") { "?" } + ")",
                cols.map { row[it] }.toTypedArray(),
            )
            restoredRows++
            return true
        }

        /** A parent only needs to exist: copy it from the source when the target lacks it. */
        private fun ensure(spec: TableSpec, id: String): Boolean {
            if (exists(dst, spec, id)) return true
            val row = readRow(src, spec, id) ?: return false
            return putRow(spec, row)
        }

        private fun deleteRow(spec: TableSpec, id: String): Int =
            dst.compileStatement("DELETE FROM `${spec.name}` WHERE `${spec.pk}` = ?").use {
                it.bindString(1, id)
                it.executeUpdateDelete()
            }

        /** Ticket capture fields are local-only; fill them in for outings present in both. */
        private fun mergeTicketFields() {
            val fields = listOf("ticketImagePath", "ticketBarcodePayload", "ticketBarcodeFormat")
            val select = "SELECT id, ${fields.joinToString(",")} FROM cinema_outings WHERE " +
                fields.joinToString(" OR ") { "$it IS NOT NULL" }
            for (legacy in src.rows(select)) {
                val id = legacy["id"] as String
                val current = dst.rows("SELECT ${fields.joinToString(",")} FROM cinema_outings WHERE id = ?", arrayOf(id))
                    .firstOrNull() ?: continue
                val merged = fields.map { current[it] ?: legacy[it] }
                if (merged == fields.map { current[it] }) continue
                dst.execSQL(
                    "UPDATE cinema_outings SET " + fields.joinToString(", ") { "`$it` = ?" } + " WHERE id = ?",
                    (merged + id).toTypedArray(),
                )
                restoredRows++
            }
        }

        /** Throws (rolling the transaction back) if anything copied is not actually there. */
        fun verify() {
            var present = 0
            for (id in newOutboxIds) {
                if (exists(dst, OUTBOX_SPEC, id)) present++
            }
            check(present == newOutboxIds.size) {
                "Outbox verification failed: $present of ${newOutboxIds.size} entries present"
            }
            val receiptSpec = TableSpec("legacy_restore_receipt", "key")
            for (id in newOutboxIds) {
                check(exists(dst, receiptSpec, "entry:$id")) { "restore receipt missing after restore" }
            }
            if (includeLocalOnly) {
                for (r in src.rows("SELECT venue FROM venue_notes")) {
                    check(exists(dst, SPECS.getValue("venue_notes"), r["venue"] as String)) { "venue note missing after restore" }
                }
                for (r in src.rows("SELECT titleId FROM theater_interest")) {
                    check(exists(dst, SPECS.getValue("theater_interest"), r["titleId"] as String)) { "theater interest missing after restore" }
                }
            }
        }

        private fun insertIgnore(table: String, row: Map<String, Any?>): Boolean {
            val cols = row.keys.filter { it in columnsOf(dst, table) }
            val sql = "INSERT OR IGNORE INTO `$table` (" + cols.joinToString(",") { "`$it`" } +
                ") VALUES (" + cols.joinToString(",") { "?" } + ")"
            return dst.compileStatement(sql).use { st ->
                cols.forEachIndexed { i, c -> bind(st, i + 1, row[c]) }
                st.executeInsert() != -1L
            }
        }

        private fun bind(st: androidx.sqlite.db.SupportSQLiteStatement, index: Int, v: Any?) {
            when (v) {
                null -> st.bindNull(index)
                is Long -> st.bindLong(index, v)
                is Int -> st.bindLong(index, v.toLong())
                is Double -> st.bindDouble(index, v)
                is ByteArray -> st.bindBlob(index, v)
                else -> st.bindString(index, v.toString())
            }
        }

        private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<String> =
            dstColumns.getOrPut(table) { db.rows("PRAGMA table_info(`$table`)").map { it["name"] as String } }

        private fun exists(db: SupportSQLiteDatabase, spec: TableSpec, id: String): Boolean =
            db.query("SELECT 1 FROM `${spec.name}` WHERE `${spec.pk}` = ? LIMIT 1", arrayOf<Any?>(id)).use { it.moveToFirst() }

        private fun readRow(db: SupportSQLiteDatabase, spec: TableSpec, id: String): Map<String, Any?>? =
            db.rows("SELECT * FROM `${spec.name}` WHERE `${spec.pk}` = ?", arrayOf(id)).firstOrNull()

        private fun payloadKeys(json: String?): Set<String> = try {
            val o = JSONObject(json ?: "{}")
            val out = HashSet<String>()
            val iter = o.keys()
            while (iter.hasNext()) out.add(iter.next())
            out
        } catch (_: Exception) {
            emptySet()
        }

        private fun SupportSQLiteDatabase.rows(sql: String, args: Array<Any?> = emptyArray()): List<Map<String, Any?>> =
            query(sql, args).use { c ->
                val out = ArrayList<Map<String, Any?>>()
                while (c.moveToNext()) out.add(c.rowMap())
                out
            }
    }

    private companion object {
        const val CLAIM_FILE = "legacy-archive-claim"
        const val OLD_MARKER = "legacy-archive-claimed"
        const val PENDING = "pending"
        const val DONE = "done"
        // -shm is a rebuildable shared-memory index (never copied: a stale one can make SQLite refuse the file).
        const val WORK_DB_NAME = "lw.db"
        val SIDECARS = listOf("-wal", "-journal")
        val restoreMutex = Mutex()

        val OUTBOX_SPEC = TableSpec("mutation_outbox", "id")

        val SPECS: Map<String, TableSpec> = listOf(
            TableSpec("titles", "id"),
            TableSpec("seasons", "id", listOf("titleId" to "titles")),
            TableSpec("episodes", "id", listOf("titleId" to "titles", "seasonId" to "seasons")),
            TableSpec("episode_watch_events", "id", listOf("episodeId" to "episodes")),
            TableSpec("episode_ratings", "id", listOf("episodeId" to "episodes")),
            TableSpec("episode_reviews", "id", listOf("episodeId" to "episodes")),
            TableSpec("viewings", "id", listOf("titleId" to "titles")),
            TableSpec("title_cast", "id", listOf("titleId" to "titles")),
            TableSpec("title_crew", "id", listOf("titleId" to "titles")),
            TableSpec("cinema_outings", "id", listOf("titleId" to "titles")),
            TableSpec("lists", "id"),
            TableSpec("list_items", "id", listOf("listId" to "lists", "titleId" to "titles")),
            TableSpec("venue_notes", "venue"),
            TableSpec("theater_interest", "titleId"),
        ).associateBy { it.name }

        /** Outbox entityType -> the Room table holding that entity's local projection. */
        val ENTITY_SPECS: Map<String, TableSpec> = mapOf(
            "title" to SPECS.getValue("titles"),
            "viewing" to SPECS.getValue("viewings"),
            "cinema_outing" to SPECS.getValue("cinema_outings"),
            "episode_watch_event" to SPECS.getValue("episode_watch_events"),
            "episode_rating" to SPECS.getValue("episode_ratings"),
            "episode_review" to SPECS.getValue("episode_reviews"),
            "episode_metadata" to SPECS.getValue("episodes"),
            "episode" to SPECS.getValue("episodes"),
            "list" to SPECS.getValue("lists"),
            "list_item" to SPECS.getValue("list_items"),
        )

        /** Children of a title, in FK-safe order, selected from the source by title id. */
        val GRAPH_QUERIES: List<Pair<String, String>> = listOf(
            "seasons" to "SELECT * FROM seasons WHERE titleId = ?",
            "episodes" to "SELECT * FROM episodes WHERE titleId = ?",
            "episode_watch_events" to "SELECT * FROM episode_watch_events WHERE episodeId IN (SELECT id FROM episodes WHERE titleId = ?)",
            "episode_ratings" to "SELECT * FROM episode_ratings WHERE episodeId IN (SELECT id FROM episodes WHERE titleId = ?)",
            "episode_reviews" to "SELECT * FROM episode_reviews WHERE episodeId IN (SELECT id FROM episodes WHERE titleId = ?)",
            "title_cast" to "SELECT * FROM title_cast WHERE titleId = ?",
            "title_crew" to "SELECT * FROM title_crew WHERE titleId = ?",
            "viewings" to "SELECT * FROM viewings WHERE titleId = ?",
            "cinema_outings" to "SELECT * FROM cinema_outings WHERE titleId = ?",
            "list_items" to "SELECT * FROM list_items WHERE titleId = ?",
        )
    }
}
