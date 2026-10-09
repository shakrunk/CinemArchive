package work.kumarfamilynet.cinemarchive.data

import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

data class SavedProviderMerge(val id: String, val title: String, val review: Boolean, val message: String?)
data class ProviderMergeComparison internal constructor(val id: String, val title: String, val saved: String,
    val current: String, val dependentChanges: Int, internal val token: String)
interface ProviderMergeSource {
    fun isCurrent(): Boolean
    val changes: Flow<List<SavedProviderMerge>>
    suspend fun retry(id: String)
    suspend fun compare(id: String): ProviderMergeComparison
    suspend fun discard(comparison: ProviderMergeComparison)
    suspend fun export(id: String, open: () -> OutputStream)
}

class ProviderMergeRepository(private val db: LibraryDatabase, private val outbox: MutationOutbox,
    private val owner: TicketOwnerScope, private val active: () -> Boolean, private val remote: ProviderMergeRemote,
    private val sync: suspend () -> Unit, private val replay: suspend (suspend () -> Unit) -> Unit,
    private val clock: Clock = Clock.systemUTC()) : ProviderMergeSource {
    override fun isCurrent() = active()
    private fun fence() { check(active()) { "This sign-in has ended." } }
    override val changes = db.outboxDao().observePending().map { rows ->
        if (!active()) emptyList() else rows.filter { it.entityType == PROVIDER_MERGE }.map {
            val command = checkedProviderMerge(it, owner)
            SavedProviderMerge(it.id, command.title, it.operation == "review", it.lastError)
        }
    }

    /** True means visible tracking changed; even a no-change import saves its provider identity. */
    suspend fun merge(titleId: String, item: SyncItem): Boolean = withContext(Dispatchers.IO) {
        fence()
        outbox.atomically {
            fence()
            val title = checkNotNull(db.titleDao().getById(titleId)) { "This title was removed. Match it again." }
            require(title.type == item.type.name)
            val queue = db.outboxDao().getPending()
            val link = ProviderTitleLink(item.provider, item.externalId)
            requirePendingProviderTarget(pendingProviderLinks(queue, owner), link, titleId)
            val dates = db.viewingDao().observeAllViewings().first().filter { it.titleId == titleId }.mapNotNull { it.date }.toSet()
            val merge = planMerge(TitleSyncSnapshot(LibraryStatus.valueOf(title.status), title.rating, dates), item.type, item)
            val patch = JSONObject().apply { merge?.status?.let { put("status", it.name.lowercase()) }; merge?.rating?.let { put("rating", it) } }
            val guard = if (patch.length() == 0) ViewingGuard() else captureViewingTitleGuard(title, queue, owner.ownerId).also {
                check(it.known) { "Sync or review earlier title changes before importing into this title." }
            }
            val viewings = merge?.newViewingDates.orEmpty().map { ViewingEntity(UUID.randomUUID().toString(), titleId, it,
                null, null, null, companionsJson = "[]") }
            val at = Instant.now(clock).toString()
            val entry = OutboxEntity(UUID.randomUUID().toString(), PROVIDER_MERGE, titleId, PROVIDER_MERGE_COMMAND,
                providerMergePayload(owner, titleId, title.title, patch, viewings, link, at, guard), clock.millis())
            checkedProviderMerge(entry, owner)
            if (patch.length() > 0) db.titleDao().upsertAll(listOf(title.withTitleMetadata(patch)))
            db.viewingDao().upsertAll(viewings)
            outbox.enqueueCaptured(entry.id, entry.entityType, titleId, entry.operation, entry.payloadJson)
            fence(); merge != null
        }
    }

    override suspend fun retry(id: String) = withContext(Dispatchers.IO) {
        fence(); outbox.withFlushPaused { outbox.atomically {
            fence(); val entry = entry(id); checkedProviderMerge(entry, owner)
            if (entry.operation == "review") check(db.completionQueueDao().replaceReviewedLifecycle(id, PROVIDER_MERGE,
                entry.payloadJson, id, PROVIDER_MERGE_COMMAND, entry.payloadJson) == 1)
            fence()
        } }
        sync(); fence()
    }

    override suspend fun compare(id: String): ProviderMergeComparison = withContext(Dispatchers.IO) { outbox.withFlushPaused {
        fence(); val queue = db.outboxDao().getPending(); val entry = queue.single { it.id == id }
        require(entry.operation == "review") { "Confirm this import by retrying before reviewing it." }
        val command = checkedProviderMerge(entry, owner)
        val current = remote.current(entry.entityId); fence()
        val chain = providerMergeChain(queue, entry, command)
        val saved = buildList {
            if (command.patch.has("status")) add("Status: ${command.patch.getString("status")}")
            if (command.patch.has("rating")) add("Rating: ${command.patch.get("rating")}")
            add("${command.viewings.size} added viewing dates")
        }.joinToString("; ")
        ProviderMergeComparison(id, command.title, saved,
            if (current.isNull("currentTitle")) "This title is no longer on the server."
            else current.getJSONObject("currentTitle").let { "Status: ${it.getString("status")}; rating: ${if (it.isNull("rating")) "none" else it.get("rating")}" },
            chain.size - 1, mergeQueueToken(queue))
    } }

    override suspend fun discard(comparison: ProviderMergeComparison) = withContext(Dispatchers.IO) {
        fence(); replay { outbox.withFlushPaused {
            val original = entry(comparison.id); require(original.operation == "review")
            val command = checkedProviderMerge(original, owner)
            val current = remote.current(original.entityId); fence()
            outbox.atomically {
                fence(); val queue = db.outboxDao().getPending()
                require(mergeQueueToken(queue) == comparison.token) { "Saved changes changed. Compare again before removing anything." }
                val chain = providerMergeChain(queue, original, command)
                require(chain.drop(1).all { it.attemptCount == 0 }) { "A later change may already have reached the server. Confirm it before removing this import." }
                chain.drop(1).forEach {
                    // Keep exact original IDs/bytes and visible drafts; their own domain recovery
                    // can explicitly compare/reapply after this rejected prerequisite is removed.
                    db.outboxDao().markForReview(it.id, "An earlier provider import was rejected and removed. Compare this retained draft before applying it.")
                }
                db.outboxDao().remove(original.id)
                val remaining = db.outboxDao().getPending()
                val local = db.titleDao().getById(original.entityId)
                if (local != null && current.isNull("currentTitle")) {
                    db.titleDao().deleteById(original.entityId)
                    db.theaterInterestDao().deleteByTitleId(original.entityId)
                } else if (local != null) {
                    val canonical = checkedCurrentTitle(current.getJSONObject("currentTitle"), original.entityId, owner.ownerId)
                    applyCurrentViewingTitle(db, original.entityId, canonical, remaining, owner.ownerId)
                }
                val actual = current.getJSONArray("currentViewings").importObjects().associateBy { it.getString("id") }
                command.viewings.forEach { row ->
                    if (remaining.none { importIntentReferences(it, setOf(row.id)) }) {
                        val server = actual[row.id]
                        if (server == null) db.viewingDao().deleteById(row.id) else db.viewingDao().upsert(server.toCompletionViewing())
                    }
                }
                fence()
            }
        } }
    }

    override suspend fun export(id: String, open: () -> OutputStream) = withContext(Dispatchers.IO) {
        fence(); val entry = entry(id); checkedProviderMerge(entry, owner)
        val bytes = metadataJson(JSONObject().put("operationId", entry.id).put("request", exactMetadataObject(entry.payloadJson))).toByteArray(Charsets.UTF_8)
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
    private suspend fun entry(id: String) = db.outboxDao().getPending().single { it.id == id && it.entityType == PROVIDER_MERGE }
}

private fun mergeQueueToken(queue: List<OutboxEntity>) = importChainToken(queue) + ":" + queue.joinToString(",") { "${it.id}:${it.attemptCount}" }
internal fun providerMergeChain(queue: List<OutboxEntity>, first: OutboxEntity, command: ProviderMergeCommand): List<OutboxEntity> {
    val chain = mutableListOf(first)
    val ids = command.viewings.mapTo(mutableSetOf()) { it.id }.also { it.add(first.id) }
    queue.dropWhile { it.id != first.id }.drop(1).forEach { next ->
        if (importIntentReferences(next, ids)) { chain += next; ids += next.id }
    }
    return chain
}

class ProviderMergeApplier(private val db: LibraryDatabase, private val owner: TicketOwnerScope) {
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        check(db.inTransaction()); val queue = db.outboxDao().getPending(); require(queue.firstOrNull() == entry)
        val command = checkedProviderMerge(entry, owner)
        checkedLibraryCommandReceipt(entry.id, command.operations, envelope.getJSONObject("receipt"), owner.ownerId)
        require(envelope.has("currentTitle")); if (db.titleDao().getById(entry.entityId) == null) return
        if (envelope.isNull("currentTitle")) { db.titleDao().deleteById(entry.entityId); db.theaterInterestDao().deleteByTitleId(entry.entityId); return }
        val current = checkedCurrentTitle(envelope.getJSONObject("currentTitle"), entry.entityId, owner.ownerId)
        applyCurrentViewingTitle(db, entry.entityId, current, queue.drop(1), owner.ownerId)
        val rows = envelope.getJSONArray("currentViewings").importObjects()
        require(rows.all { it.getString("title_id") == entry.entityId && it.getString("user_id") == owner.ownerId })
        val map = rows.associateBy { it.getString("id") }; require(map.size == rows.size)
        command.viewings.forEach { row ->
            if (db.viewingDao().getById(row.id) != null && queue.drop(1).none { importIntentReferences(it, setOf(row.id)) }) {
                val server = map[row.id]
                if (server == null) db.viewingDao().deleteById(row.id) else db.viewingDao().upsert(server.toCompletionViewing())
            }
        }
    }
}
