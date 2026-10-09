package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** Recovery is explicit and serialized with pushes. Network calls never run inside a Room transaction. */
class TitleMetadataRepository(
    private val database: LibraryDatabase,
    private val outbox: MutationOutbox,
    private val ownerId: String,
    private val session: SessionSource,
    private val remote: TitleMetadataRemote,
    private val synchronize: suspend () -> Unit,
    private val replayBoundary: suspend (suspend () -> Unit) -> Unit,
) : TitleMetadataRecoverySource {
    override val changes = database.outboxDao().observePending().map { queue ->
        queue.filter(::isMetadata).groupBy { it.entityId }.map { (id, entries) ->
            SavedTitleChange(id, database.titleDao().getById(id)?.title ?: "Removed title", entries.size,
                entries.first().operation == "review", entries.first().lastError)
        }
    }

    private suspend fun active() {
        currentCoroutineContext().ensureActive()
        check(session.currentSession()?.userId == ownerId) { "This sign-in has ended" }
    }

    override suspend fun retrySync() { active(); synchronize(); active() }

    override suspend fun compare(titleId: String): TitleMetadataComparison = outbox.withFlushPaused {
        active()
        val queue = database.outboxDao().getPending()
        val entries = reviewEntries(queue, titleId)
        check(entries.isNotEmpty()) { "This title has no saved edits." }
        check(entries.first().operation == "review") { "Retry sync to confirm the original change before reviewing it." }
        val firstIndex = queue.indexOfFirst { it.id == entries.first().id }
        check(pendingTitleIntents(queue.take(firstIndex), titleId).isEmpty()) {
            "Sync the older title changes first, then compare these saved edits."
        }
        val patch = mergeTitlePatches(entries.map { titleMetadataPatch(it, ownerId) })
        val current = remote.current(titleId)?.also { checkedCurrentTitle(it, titleId, ownerId) }
        active()
        val local = database.titleDao().getById(titleId)
        val saved = JSONObject().put("tags", current?.optJSONArray("tags") ?: JSONArray(local?.tags ?: emptyList<String>()))
            .put("status", current?.optString("status") ?: local?.status?.lowercase() ?: "unknown")
            .put("rating", current?.opt("rating") ?: local?.rating ?: JSONObject.NULL)
        patch.keys().forEach { saved.put(it, patch.get(it)) }
        val comparison = TitleMetadataComparison(titleId, local?.title ?: current?.optString("title") ?: "Removed title",
            saved.titleMetadataValues(), current?.titleMetadataValues(), snapshot(entries), patch.toString(), current?.toString())
        verify(comparison)
        comparison
    }

    override suspend fun applySaved(comparison: TitleMetadataComparison) = outbox.withFlushPaused {
        active()
        val current = comparison.currentJson?.let(::JSONObject) ?: error("The title is no longer on the server. Discard these edits or keep them for review.")
        checkedCurrentTitle(current, comparison.titleId, ownerId)
        val patch = checkedTitlePatch(JSONObject(comparison.patchJson))
        outbox.atomically {
            active()
            val entries = verify(comparison)
            val local = database.titleDao().getById(comparison.titleId) ?: error("This title was removed locally. Its saved edits can be discarded.")
            val first = entries.first()
            val replacementId = UUID.randomUUID().toString()
            val payload = titleMetadataPayload(ownerId, comparison.titleId, patch, current.getString("updated_at"), null)
            // Replacing the slot retains FIFO order; no unrelated/later legacy edit overtakes this intent.
            check(database.outboxDao().replaceReviewedTitle(first.id, first.payloadJson, replacementId, TITLE_METADATA_COMMAND, payload.toString()) == 1)
            entries.drop(1).forEach { database.outboxDao().remove(it.id) }
            val remaining = pendingTitleIntents(database.outboxDao().getPending(), comparison.titleId)
            database.titleDao().upsertAll(listOf(overlayTitleIntents(current.toMetadataTitle(local), remaining, ownerId)))
        }
    }

    override suspend fun discard(comparison: TitleMetadataComparison) = replayBoundary { outbox.withFlushPaused {
        active()
        verify(comparison)
        val current = remote.current(comparison.titleId)?.also { checkedCurrentTitle(it, comparison.titleId, ownerId) }
        active()
        outbox.atomically {
            active()
            val entries = verify(comparison)
            entries.forEach { database.outboxDao().remove(it.id) }
            val local = database.titleDao().getById(comparison.titleId)
            if (local != null && current != null) {
                val remaining = pendingTitleIntents(database.outboxDao().getPending(), comparison.titleId)
                database.titleDao().upsertAll(listOf(overlayTitleIntents(current.toMetadataTitle(local), remaining, ownerId)))
            }
        }
    } }

    private suspend fun verify(comparison: TitleMetadataComparison): List<OutboxEntity> {
        active()
        val queue = database.outboxDao().getPending()
        val entries = reviewEntries(queue, comparison.titleId)
        check(entries.isNotEmpty() && entries.first().operation == "review" && snapshot(entries) == comparison.entries) {
            "Saved edits changed. Compare them again before continuing."
        }
        val firstIndex = queue.indexOfFirst { it.id == entries.first().id }
        check(pendingTitleIntents(queue.take(firstIndex), comparison.titleId).isEmpty()) {
            "Sync the older title changes first, then compare these saved edits."
        }
        entries.forEach { titleMetadataPatch(it, ownerId) }
        return entries
    }

    private fun snapshot(entries: List<OutboxEntity>) = entries.map { it.id to it.payloadJson }
    /** Resolve the first title segment; a viewing action is an indivisible boundary. */
    private fun reviewEntries(queue: List<OutboxEntity>, titleId: String): List<OutboxEntity> {
        val first = queue.indexOfFirst { it.entityId == titleId && isMetadata(it) }
        if (first < 0) return emptyList()
        return queue.drop(first).takeWhile { !hasViewingTitleEffect(it, titleId) }
            .filter { it.entityId == titleId && isMetadata(it) }
    }
    private fun isMetadata(entry: OutboxEntity): Boolean = entry.entityType == "title" &&
        runCatching { JSONObject(entry.payloadJson).has(TITLE_METADATA_DATA) }.getOrDefault(false)
}
