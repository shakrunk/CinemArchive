package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** Network-free ACK, invoked in the same transaction that removes the original queue entry. */
class ViewingCommandApplier(private val database: LibraryDatabase, private val ownerId: String,
    private val isCurrentOwner: () -> Boolean = { true }) {
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        check(isCurrentOwner()) { "Viewing account changed." }
        applyOwned(entry, envelope)
        check(isCurrentOwner()) { "Viewing account changed." }
    }

    private suspend fun applyOwned(entry: OutboxEntity, envelope: JSONObject) {
        check(database.inTransaction()) { "Viewing acknowledgment requires one Room transaction." }
        val current = currentViewingCommandRow(entry, envelope, ownerId)
        val currentOutings = currentViewingLinkedOutings(entry, envelope, ownerId)
        val currentTitle = currentViewingTitle(entry, envelope, ownerId)
        val titleId = JSONObject(entry.payloadJson).getString("titleId")
        val queue = database.outboxDao().getPending()
        require(queue.firstOrNull()?.let { it.id == entry.id && it.operation == entry.operation && it.payloadJson == entry.payloadJson } == true)
        val later = queue.drop(1).filter { it.entityType == "viewing" && it.entityId == entry.entityId }
        val local = database.viewingDao().getById(entry.entityId)
        require(local == null || local.titleId == titleId)
        if (database.titleDao().getById(titleId) == null) return
        if (viewingTitleEntry(entry, ownerId) != null) applyCurrentViewingTitle(database, titleId, currentTitle, queue.drop(1), ownerId)
        if (current == null) database.completionQueueDao().clearViewingLink(entry.entityId)
        applyViewingLinkedOutings(database, titleId, currentOutings, queue.drop(1))
        if (current == null) {
            // A removed remote event cannot be recreated by an old receipt. Keep any unsent
            // draft in its original queue payload for explicit recovery, not as live history.
            database.viewingDao().deleteById(entry.entityId)
            return
        }
        if (local == null) return // a later local delete remains deleted
        val projection = runCatching {
            val row = JSONObject(current.toString())
            var deleted = false
            later.forEach { pending ->
                require(JSONObject(pending.payloadJson).getString("titleId") == titleId)
                val op = viewingCommandOperations(pending).getJSONObject(0)
                require(op.getString("action") != "insert" && !deleted)
                if (op.getString("action") == "delete") deleted = true
                else op.getJSONObject("values").let { fields -> fields.keys().forEach { row.put(it, fields.get(it)) } }
            }
            if (deleted) null else row.toCompletionViewing()
        }
        // Unknown later delivery stays untouched and reviewable. Its optimistic draft is not
        // replaced with a historical receipt or a best-effort interpretation of malformed data.
        if (projection.isSuccess) {
            val row = projection.getOrNull()
            if (row == null) database.viewingDao().deleteById(entry.entityId) else database.viewingDao().upsert(row)
        }
    }
}

/** Current owner rows only; shared by FIFO acknowledgment and explicit recovery. */
internal suspend fun applyViewingLinkedOutings(database: LibraryDatabase, titleId: String,
    currentOutings: Map<String, JSONObject?>, later: List<OutboxEntity>) {
    check(database.inTransaction())
    for ((id, row) in currentOutings) {
        val outing = database.cinemaOutingDao().getById(id) ?: continue
        require(outing.titleId == titleId)
        if (row == null) {
            database.cinemaOutingDao().deleteById(id)
            continue // later intent remains queued for review, never a remote resurrection
        }
        val pending = later.filter { it.entityId == id && it.entityType in setOf("cinema_outing", "outing_completion", "outing_reversal", TICKET_COMMAND_ENTITY) }
        val projected = runCatching {
            val result = JSONObject(row.toString())
            pending.filter { it.entityType != TICKET_COMMAND_ENTITY }.forEach {
                require(it.entityType == "cinema_outing" && it.operation == OUTING_COMMAND)
                val operation = outingCommandOperations(it).getJSONObject(0)
                require(operation.getString("action") == "update")
                val fields = operation.getJSONObject("values")
                fields.keys().forEach { key -> result.put(key, fields.get(key)) }
            }
            result.toRecoveryOuting().let {
                if (pending.any { saved -> saved.entityType == TICKET_COMMAND_ENTITY }) it.copy(ticketImagePath = outing.ticketImagePath) else it
            }
        }
        projected.getOrNull()?.let { database.cinemaOutingDao().upsert(it) }
    }
}
