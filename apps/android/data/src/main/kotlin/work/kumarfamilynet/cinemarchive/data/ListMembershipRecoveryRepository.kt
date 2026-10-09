package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.ListItemEntity
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

interface ListMembershipRecoveryRemote {
    suspend fun byId(id: String): JSONObject?
    suspend fun byMembership(listId: String, titleId: String): JSONObject?
}

/** An explicit user decision migrates an old surrogate-ID write to a natural-key command.
 * Originals are archived before any Room change; replacement retains the original FIFO rowid.
 * No remote write is sent from this recovery path: the ordinary immutable command delivers it.
 */
class ListMembershipRecoveryRepository(
    private val database: LibraryDatabase,
    private val ownerId: String,
    private val sessionProvider: () -> SupabaseSession?,
    private val outbox: MutationOutbox,
    private val archive: OutingRecoveryArchive,
    private val remote: ListMembershipRecoveryRemote,
    private val replayBoundary: suspend (suspend () -> Unit) -> Unit,
    private val synchronize: suspend () -> Unit,
) : OutingRecoverySource {
    private val dao get() = database.outboxDao()
    override val changes = combine(dao.observePending(), archive.records) { _, _ -> Unit }
    override fun isActive() = sessionProvider()?.userId == ownerId
    private suspend fun active() {
        currentCoroutineContext().ensureActive()
        check(isActive()) { "Account changed. Reopen Profile." }
    }
    override suspend fun pendingAttempt(id: String) = false // Replacements have their own immutable queue IDs.
    private fun legacy(entry: OutboxEntity) = entry.entityType == "list_item" && entry.operation != MEMBERSHIP_COMMAND

    override suspend fun items(): List<OutingRecoveryCard> {
        active()
        val pending = dao.getPending().filter(::legacy)
        val records = archive.records.first().toMutableMap()
        pending.forEach { records.putIfAbsent(it.id, it.originalRecord().toString()) }
        return records.map { (id, raw) ->
            val entry = runCatching { original(raw, id) }.getOrNull()
            if (entry == null) OutingRecoveryCard(id, "Unreadable saved list change", false,
                "Original data is preserved. Export it for recovery.")
            else OutingRecoveryCard(id, label(entry), pending.none { it.id == id })
        }.also { active() }
    }

    override suspend fun review(id: String): OutingRecoveryReview = outbox.withFlushPaused {
        val entry = retain(id)
        val resolved = dao.getPending().none { it.id == id }
        val intent = intent(entry)
        val row = intent?.let { current(it) }
        val exists = intent != null && database.listDao().getById(intent.listId) != null &&
            database.titleDao().getById(intent.titleId) != null
        active()
        OutingRecoveryReview(id, label(entry), if (intent == null) emptyList() else listOf(
            OutingRecoveryField("membership", "List membership", if (intent.present) "Add to list" else "Remove from list",
                if (row == null) "Not in list" else "In list", exists && !resolved)),
            snapshot(row), exists, false, resolved,
            if (intent == null) "This older change has no recoverable list/title identity. Export the original or discard the queued change; manage the desired membership from Lists."
            else if (!exists) "The list or title is unavailable. No parent will be recreated. You can export or discard the queued change."
            else "Select the saved membership to queue it using the current list and title identity. Discard removes only this queued change; it does not undo changes already delivered.")
    }

    override suspend fun apply(id: String, expectedVersion: String?, selected: Set<String>): OutingRecoveryOutcome {
        val result = queueReviewed(id, expectedVersion, selected)
        if (result == OutingRecoveryOutcome.APPLIED) synchronizeAfterResolution()
        return result
    }

    private suspend fun synchronizeAfterResolution() {
        active()
        try { synchronize() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { active() } // Admission is durable; offline delivery remains queued.
    }

    private suspend fun queueReviewed(id: String, expectedVersion: String?, selected: Set<String>): OutingRecoveryOutcome = outbox.withFlushPaused {
        require(selected == setOf("membership")) { "Select the saved membership first." }
        val entry = retain(id)
        val intent = intent(entry) ?: error("The saved membership identity cannot be recovered.")
        val row = current(intent)
        if (snapshot(row) != expectedVersion) return@withFlushPaused OutingRecoveryOutcome.CHANGED
        val newId = UUID.randomUUID().toString()
        val payload = membershipPayload(entry.entityId, intent.listId, intent.titleId, intent.present, intent.addedAt).toString()
        outbox.atomically {
            active()
            check(dao.getPending().any { it.id == entry.id && it.entityId == entry.entityId && it.operation == entry.operation && it.payloadJson == entry.payloadJson }) { "This saved change was already resolved." }
            if (database.listDao().getById(intent.listId) == null || database.titleDao().getById(intent.titleId) == null)
                return@atomically false
            check(database.completionQueueDao().replaceLegacyMembership(entry.id, entry.operation, entry.payloadJson,
                newId, MEMBERSHIP_COMMAND, payload) == 1)
            val laterMembership = dao.getPending().dropWhile { it.id != newId }.drop(1).any { it.entityType == "list_item" }
            if (!laterMembership) {
                database.listItemDao().deleteByListAndTitle(intent.listId, intent.titleId)
                if (intent.present) database.listItemDao().upsert(ListItemEntity(entry.entityId, intent.listId,
                    intent.titleId, row?.position, requireNotNull(intent.addedAt), row?.updatedAt ?: intent.addedAt))
            }
            active()
            true
        }.let { admitted -> if (!admitted) return@withFlushPaused OutingRecoveryOutcome.MISSING }
        // A crash here leaves an archived original and an independently durable replacement.
        // Resolved status derives from queue membership, never a second archive write.
        OutingRecoveryOutcome.APPLIED
    }

    override suspend fun discard(id: String) {
        discardQueued(id)
        synchronizeAfterResolution()
    }

    private suspend fun discardQueued(id: String) = replayBoundary { outbox.withFlushPaused {
        val entry = retain(id)
        val intent = intent(entry)
        val row = intent?.let { current(it) }
        outbox.atomically {
            active()
            val queue = dao.getPending()
            check(queue.any { it.id == entry.id && it.entityId == entry.entityId && it.operation == entry.operation && it.payloadJson == entry.payloadJson }) { "This saved change was already resolved." }
            // Preserve the visible projection if any other command could own it, including
            // an older ID-only deletion whose natural identity is no longer recoverable.
            val other = queue.any { it.id != id && it.entityType == "list_item" }
            if (!other && intent != null) {
                database.listItemDao().deleteByListAndTitle(intent.listId, intent.titleId)
                if (row != null && database.listDao().getById(intent.listId) != null && database.titleDao().getById(intent.titleId) != null)
                    database.listItemDao().upsert(row)
            }
            dao.remove(id)
            active()
        }
    } }

    override suspend fun exportOriginal(id: String): String = outbox.withFlushPaused {
        active()
        archive.records.first()[id]?.let { return@withFlushPaused it }
        retain(id)
        active()
        archive.records.first().getValue(id)
    }

    private suspend fun retain(id: String): OutboxEntity {
        active()
        archive.records.first()[id]?.let { return original(it, id) }
        val entry = dao.getPending().firstOrNull { it.id == id && legacy(it) } ?: error("Saved change is no longer pending.")
        archive.put(id, entry.originalRecord().toString())
        active()
        return entry
    }

    private fun original(raw: String, id: String): OutboxEntity {
        val record = JSONObject(raw)
        require(record.getInt("version") == 1)
        val value = record.getJSONObject("original")
        require(value.getString("id") == id && value.getString("entityType") == "list_item")
        return OutboxEntity(id, "list_item", value.getString("entityId"), value.getString("operation"),
            value.getString("payloadJson"), value.getLong("createdAt"), value.getInt("attemptCount"),
            if (value.isNull("lastError")) null else value.getString("lastError"))
    }

    private data class Intent(val listId: String, val titleId: String, val present: Boolean, val addedAt: String?)
    private suspend fun intent(entry: OutboxEntity): Intent? {
        val payload = runCatching { JSONObject(entry.payloadJson) }.getOrNull() ?: return null
        if (payload.optString("id") != entry.entityId || entry.operation !in setOf("upsert", "delete")) return null
        UUID.fromString(entry.entityId)
        val present = entry.operation == "upsert"
        val known = if (payload.has("listId") && payload.has("titleId"))
            payload.getString("listId") to payload.getString("titleId")
        else {
            val retained = archive.record(entry.id)?.optJSONObject("membershipIdentity")
            val local = database.listItemDao().getById(entry.entityId)
            if (retained != null) retained.getString("listId") to retained.getString("titleId")
            else if (local != null) local.listId to local.titleId
            else {
                val server = remote.byId(entry.entityId)
                active()
                val row = server?.let { parse(it).also { row -> require(row.id == entry.entityId) } }
                row?.let { it.listId to it.titleId }
            }
        } ?: return null
        UUID.fromString(known.first); UUID.fromString(known.second)
        if (!payload.has("listId")) {
            val record = requireNotNull(archive.record(entry.id))
            if (!record.has("membershipIdentity")) {
                active()
                record.put("membershipIdentity", JSONObject().put("listId", known.first).put("titleId", known.second))
                archive.put(entry.id, record.toString())
                active()
            }
        }
        val addedAt = if (present) payload.optString("addedAt").takeIf { it.isNotBlank() }?.also(Instant::parse) ?: return null else null
        return Intent(known.first, known.second, present, addedAt)
    }

    private suspend fun current(intent: Intent): ListItemEntity? {
        active()
        val row = remote.byMembership(intent.listId, intent.titleId)?.let(::parse)
        active()
        require(row == null || row.listId == intent.listId && row.titleId == intent.titleId)
        return row
    }
    private fun parse(row: JSONObject): ListItemEntity {
        require(row.getString("user_id") == ownerId)
        return ListItemEntity(row.getString("id").also(UUID::fromString), row.getString("list_id").also(UUID::fromString),
            row.getString("title_id").also(UUID::fromString), if (row.isNull("position")) null else row.getInt("position"),
            row.getString("added_at").also(Instant::parse), row.getString("updated_at").also(Instant::parse))
    }
    private fun snapshot(row: ListItemEntity?) = if (row == null) "absent" else "${row.id}:${row.updatedAt}:${row.addedAt}:${row.position}"
    private suspend fun label(entry: OutboxEntity): String {
        val payload = runCatching { JSONObject(entry.payloadJson) }.getOrNull()
        val identity = if (payload?.has("listId") == true && payload.has("titleId")) payload
            else archive.record(entry.id)?.optJSONObject("membershipIdentity")
        val title = identity?.optString("titleId")?.let { database.titleDao().getById(it)?.title }
        val list = identity?.optString("listId")?.let { database.listDao().getById(it)?.name }
        return if (title != null && list != null) "$title in $list" else "Saved list membership"
    }

    companion object {
        fun remote(client: SupabaseRestClient, ownerId: String, sessionProvider: () -> SupabaseSession?) = object : ListMembershipRecoveryRemote {
            private suspend fun read(filter: String): JSONObject? = withContext(Dispatchers.IO) {
                val session = sessionProvider()?.takeIf { it.userId == ownerId } ?: error("Account changed. Reopen Profile.")
                val rows = JSONArray(client.get("list_items", "$filter&user_id=eq.$ownerId&select=*", session.accessToken))
                check(sessionProvider()?.userId == ownerId)
                require(rows.length() <= 1)
                if (rows.length() == 0) null else rows.getJSONObject(0)
            }
            override suspend fun byId(id: String): JSONObject? { UUID.fromString(id); return read("id=eq.$id") }
            override suspend fun byMembership(listId: String, titleId: String): JSONObject? {
                UUID.fromString(listId); UUID.fromString(titleId)
                return read("list_id=eq.$listId&title_id=eq.$titleId")
            }
        }
    }
}
