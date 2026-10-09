package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

class OutingReversalApplier(private val database: LibraryDatabase, private val ownerId: String) {
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        check(database.inTransaction())
        val command = checkedReversalCurrent(entry, envelope, ownerId)
        val queue = database.outboxDao().getPending()
        require(queue.firstOrNull()?.let { it.id == entry.id && it.payloadJson == entry.payloadJson && it.operation == entry.operation } == true)
        val later = queue.drop(1)
        if (database.titleDao().getById(command.titleId) == null) return
        applyViewingLinkedOutings(database, command.titleId,
            checkedViewingLinkedOutings(listOf(command.outingId), command.titleId, ownerId, envelope.getJSONObject("currentOutings")), later)
        command.viewingId?.let { id ->
            if (envelope.isNull("currentViewing")) {
                database.viewingDao().deleteById(id)
                database.completionQueueDao().clearViewingLink(id)
            } else if (database.viewingDao().getById(id) != null && later.none { it.entityType == "viewing" && it.entityId == id }) {
                database.viewingDao().upsert(envelope.getJSONObject("currentViewing").toCompletionViewing())
            }
        }
        applyCurrentViewingTitle(database, command.titleId,
            if (envelope.isNull("currentTitle")) null else envelope.getJSONObject("currentTitle"), later, ownerId)
    }
}
