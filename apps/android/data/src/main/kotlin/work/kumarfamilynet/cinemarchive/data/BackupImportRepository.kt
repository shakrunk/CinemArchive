package work.kumarfamilynet.cinemarchive.data

import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope

class PreparedLibraryImport internal constructor(
    val titleCount: Int, val outingCount: Int, val skippedTitles: Int, val skippedOutings: Int,
    val issues: List<String>, internal val entries: List<OutboxEntity>, internal val token: Any,
)
data class LibraryImportResult(val admitted: Int, val skipped: Int, val remaining: Int, val error: String?)
data class SavedLibraryImport(val id: String, val title: String, val review: Boolean, val message: String?, val dependentChanges: Int, val comparisonToken: String)

interface LibraryRestoreSource {
    fun isCurrent(): Boolean
    fun savedImports(): Flow<List<SavedLibraryImport>>
    suspend fun prepareImport(open: () -> InputStream): PreparedLibraryImport
    suspend fun admit(prepared: PreparedLibraryImport): LibraryImportResult
    suspend fun retry(id: String)
    suspend fun discardRejected(expected: SavedLibraryImport)
    suspend fun exportSaved(id: String, open: () -> OutputStream)
    suspend fun synchronize()
}

class BackupImportRepository(
    private val database: LibraryDatabase,
    private val outbox: MutationOutbox,
    private val owner: TicketOwnerScope,
    private val current: () -> Boolean,
    private val sync: suspend () -> Unit,
    private val replay: suspend (suspend () -> Unit) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
) : LibraryRestoreSource {
    private val token = Any()
    override fun isCurrent() = current()
    private fun fence() = check(current()) { "The account changed. Open this file again for the current account." }
    override suspend fun synchronize() { fence(); sync(); fence() }
    override fun savedImports(): Flow<List<SavedLibraryImport>> = database.outboxDao().observePending().map { queue ->
        if (!current()) emptyList() else queue.filter(::isBackupImport).map { entry ->
            val command = checkedImportCommand(entry, owner)
            SavedLibraryImport(entry.id, command.title.getString("title"), entry.operation == "review", entry.lastError,
                dependentChain(queue, entry, command).size - 1, importChainToken(dependentChain(queue, entry, command)))
        }
    }

    override suspend fun prepareImport(open: () -> InputStream): PreparedLibraryImport = withContext(Dispatchers.IO) {
        fence()
        val parsed = open().use { LibraryBackupCodec.parseStream(it) }
        fence()
        val document = when (parsed) {
            is LibraryBackupCodec.ParseResult.Success -> parsed.document
            is LibraryBackupCodec.ParseResult.Failure -> error(parsed.message)
        }
        val existing = RoomTransactor(database).run {
            fence()
            val rows = database.titleDao().observeAllTitles().first()
            val keys = rows.associate { (it.tmdbId to it.type.lowercase()) to it.id }.toMutableMap()
            database.outboxDao().getPending().filter(::isBackupImport).forEach {
                val command = checkedImportCommand(it, owner)
                keys[command.title.getInt("tmdbId") to command.title.getString("type")] = it.entityId
            }
            keys
        }
        val validation = LibraryBackupCodec.validate(document)
        val selected = mutableSetOf<String>(); val seen = existing.keys.toMutableSet()
        val selectedIndexes = mutableMapOf<Pair<Int, String>, Int>()
        document.titles.forEachIndexed { index, title ->
            if (validation.none { it.fatal && (it.path == "$" || it.path == "titles[$index]" || it.path.startsWith("titles[$index].")) }) {
                val key = title.getInt("tmdbId") to title.getString("type")
                if (seen.add(key)) {
                    selectedIndexes[key] = index
                    (title.opt("id") as? String)?.let(selected::add)
                }
            }
        }
        // Installed web import skips the whole duplicate graph, including scheduled outings.
        val outings = document.outings.filter { it.optString("titleId") in selected }
        val filtered = LibraryBackupCodec.BackupDocument(document.version, document.format, document.exportedAt,
            document.client, document.titles, outings, emptyList(), document.localOnly, document.extra)
        val plan = LibraryBackupCodec.planCopy(filtered, LibraryBackupCodec.ExistingLibrary(existing))
        val issues = (plan.report.rejections.map { "${it.path}: ${it.message}" } +
            plan.report.warnings.filterNot { it.message.contains("re-pointed") || it.message.startsWith("Title ") }.map { "${it.path}: ${it.message}" }).toMutableList()
        if (document.lists.isNotEmpty() || document.localOnly != null || document.extra.length() > 0)
            issues += "Lists, account settings, local-only data and unknown envelope fields stay in the original file; they are not restored."
        val at = Instant.now(clock).toString()
        val entries = plan.titles.mapNotNull { title ->
            try {
                // A dropped history link is not silently converted into a different graph.
                val originalIndex = checkNotNull(selectedIndexes[title.getInt("tmdbId") to title.getString("type")])
                require(plan.report.warnings.none { it.path.startsWith("titles[$originalIndex].") && it.message.contains("reference") }) {
                    "A history reference cannot be restored. Keep the original file and correct that title first."
                }
                val linked = plan.outings.filter { it.getString("titleId") == title.getString("id") }
                val originalId = document.titles[originalIndex].optString("id")
                val originalOutingIndexes = outings.indices.filter { outings[it].optString("titleId") == originalId }
                require(plan.report.warnings.none { warning ->
                    originalOutingIndexes.any { warning.path.startsWith("outings[$it].") } && warning.message.contains("reference")
                }) { "An outing history reference cannot be restored. Keep the original file and correct that title first." }
                require(linked.size == outings.count { it.optString("titleId") == originalId }) { "An outing was rejected; this entire title graph is retained in the original file." }
                val originalViewings = document.titles[originalIndex].importArray("viewings").importObjects()
                val copiedViewings = title.importArray("viewings").importObjects()
                require(originalViewings.size == copiedViewings.size) { "Some history could not be restored." }
                copiedViewings.zip(originalViewings).forEach { (copy, original) ->
                    copy.put("companions", restoredCompanions(original.opt("companions")))
                }
                linked.zip(outings.filter { it.optString("titleId") == originalId }).forEach { (copy, original) ->
                    copy.put("companions", restoredCompanions(original.opt("companions")))
                }
                val payload = importPayload(owner, title, linked, at)
                OutboxEntity(UUID.randomUUID().toString(), "title", title.getString("id"), BACKUP_IMPORT_COMMAND, payload, clock.millis())
                    .also { checkedImportCommand(it, owner) }
            } catch (error: Exception) {
                issues += "${title.optString("title", "Title")}: ${error.message ?: "This graph cannot be restored without losing data."}"
                null
            }
        }
        fence()
        PreparedLibraryImport(entries.size, entries.sumOf { checkedImportCommand(it, owner).outings.size },
            plan.report.titlesSkippedExisting, document.outings.size - outings.size, issues, entries, token)
    }

    override suspend fun admit(prepared: PreparedLibraryImport): LibraryImportResult = withContext(Dispatchers.IO) {
        fence(); check(prepared.token === token) { "This preview belongs to another account session." }
        var admitted = 0; var skipped = 0
        for ((index, entry) in prepared.entries.withIndex()) {
            try {
                val command = checkedImportCommand(entry, owner)
                val inserted = outbox.atomically {
                    fence()
                    val title = command.mapping.graph.titles.single()
                    val duplicate = database.titleDao().findIdByTmdbKey(title.tmdbId, title.type) != null ||
                        database.outboxDao().getPending().filter(::isBackupImport).any {
                            val previous = checkedImportCommand(it, owner).mapping.graph.titles.single()
                            previous.tmdbId == title.tmdbId && previous.type == title.type
                        }
                    if (duplicate) false else {
                        writeImportGraph(database, command.mapping.graph)
                        outbox.enqueueCaptured(entry.id, entry.entityType, entry.entityId, entry.operation, entry.payloadJson)
                        fence(); true
                    }
                }
                if (inserted) admitted++ else skipped++
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                return@withContext LibraryImportResult(admitted, skipped, prepared.entries.size - index,
                    error.message ?: "Could not save this title. Earlier titles remain safely saved.")
            }
        }
        LibraryImportResult(admitted, skipped, 0, null)
    }

    override suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        fence()
        outbox.withFlushPaused {
            outbox.atomically {
                fence()
                val entry = database.outboxDao().getPending().single { it.id == id }
                checkedImportCommand(entry, owner)
                if (entry.operation == "review") {
                    check(database.outboxDao().replaceReviewedTitle(entry.id, entry.payloadJson, entry.id, BACKUP_IMPORT_COMMAND, entry.payloadJson) == 1)
                }
                fence()
            }
        }
        synchronize()
    }

    override suspend fun discardRejected(expected: SavedLibraryImport) = withContext(Dispatchers.IO) {
        fence()
        replay {
            outbox.withFlushPaused {
                outbox.atomically {
                    fence()
                    val queue = database.outboxDao().getPending()
                    val entry = queue.single { it.id == expected.id }
                    require(entry.operation == "review") { "An unconfirmed import can only be retried or exported." }
                    val command = checkedImportCommand(entry, owner)
                    val chain = dependentChain(queue, entry, command)
                    require(importChainToken(chain) == expected.comparisonToken) { "Saved changes changed. Compare them again before removing anything." }
                    require(chain.drop(1).all { it.attemptCount == 0 }) { "A later change may already have reached the server. Retry confirmation before removing this import." }
                    require(chain.drop(1).all { runCatching { exactMetadataObject(it.payloadJson) }.isSuccess }) {
                        "A later saved change could not be identified. Preserve it and resolve that change before removing this import."
                    }
                    database.titleDao().deleteById(entry.entityId)
                    database.theaterInterestDao().deleteByTitleId(entry.entityId)
                    chain.forEach { database.outboxDao().remove(it.id) }
                    fence()
                }
            }
        }
    }

    override suspend fun exportSaved(id: String, open: () -> OutputStream) = withContext(Dispatchers.IO) {
        fence()
        val entry = database.outboxDao().getPending().single { it.id == id }
        val command = checkedImportCommand(entry, owner)
        val bytes = (metadataJson(JSONObject().put("version", 1).put("titles", JSONArray().put(command.title))
            .put("outings", JSONArray(command.outings))) + "\n").toByteArray(Charsets.UTF_8)
        fence(); open().use { output ->
            var offset = 0
            while (offset < bytes.size) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive(); fence()
                val count = minOf(64 * 1024, bytes.size - offset)
                output.write(bytes, offset, count); offset += count
            }
            output.flush()
        }; fence()
    }
}

internal fun dependentChain(queue: List<OutboxEntity>, entry: OutboxEntity, command: BackupImportCommand): List<OutboxEntity> {
    val selected = mutableListOf(entry)
    val ids = importRowKeys(command).mapTo(mutableSetOf()) { it.substringAfter(':') }.also { it.add(entry.id) }
    queue.dropWhile { it.id != entry.id }.drop(1).forEach {
        if (importIntentReferences(it, ids)) { selected += it; ids += it.id; ids += it.entityId }
    }
    return selected
}

internal suspend fun writeImportGraph(database: LibraryDatabase, graph: LibraryExportGraph) {
    check(database.inTransaction())
    database.titleDao().upsertAll(graph.titles)
    database.seasonDao().upsertAll(graph.seasons); database.episodeDao().upsertAll(graph.episodes)
    database.episodeWatchEventDao().upsertAll(graph.watches); database.episodeRatingDao().upsertAll(graph.ratings)
    database.episodeReviewDao().upsertAll(graph.reviews); database.cinemaOutingDao().upsertAll(graph.outings)
    database.viewingDao().upsertAll(graph.viewings)
    database.titleCastDao().upsertAll(graph.cast); database.titleCrewDao().upsertAll(graph.crew)
    database.personCreditsDao().upsertSeasonCast(graph.seasonCast); database.personCreditsDao().upsertEpisodeCrew(graph.episodeCrew)
}

internal fun importChainToken(entries: List<OutboxEntity>): String = LibraryBackupCodec.sha256Hex(entries.joinToString("\n") { it.id + ":" + it.operation + ":" + it.payloadJson }.toByteArray(Charsets.UTF_8))

internal fun restoredCompanions(raw: Any?): JSONArray {
    if (raw == null || raw == JSONObject.NULL) return JSONArray()
    require(raw is JSONArray) { "Companions must be an array." }
    return JSONArray().also { result ->
        (0 until raw.length()).forEach { index ->
            val value = raw.get(index)
            val row = when (value) {
                is String -> JSONObject().put("name", value)
                is JSONObject -> {
                    require(value.keys().asSequence().all { it in setOf("name", "friendUserId") }) { "An unsupported companion field remains in the original file." }
                    exactMetadataObject(metadataJson(value))
                }
                else -> error("A companion cannot be restored without losing data.")
            }
            val name = row.get("name")
            require(name is String && name.isNotBlank() && '\u0000' !in name && Charsets.UTF_8.newEncoder().canEncode(name))
            if (row.has("friendUserId") && !row.isNull("friendUserId")) {
                val id = row.get("friendUserId")
                require(id is String && UUID.fromString(id).toString() == id.lowercase()) { "A companion's friend identity is invalid." }
            }
            result.put(row)
        }
    }
}
