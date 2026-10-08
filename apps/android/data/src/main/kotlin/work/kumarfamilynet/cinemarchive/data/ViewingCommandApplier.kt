package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** Network-free ACK, invoked in the same transaction that removes the original queue entry. */
class ViewingCommandApplier(private val database: LibraryDatabase, private val ownerId: String) {
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        check(database.inTransaction()) { "Viewing acknowledgment requires one Room transaction." }
        val current = currentViewingCommandRow(entry, envelope, ownerId)
        val titleId = JSONObject(entry.payloadJson).getString("titleId")
        val queue = database.outboxDao().getPending()
        require(queue.firstOrNull()?.let { it.id == entry.id && it.operation == entry.operation && it.payloadJson == entry.payloadJson } == true)
        val later = queue.drop(1).filter { it.entityType == "viewing" && it.entityId == entry.entityId }
        val local = database.viewingDao().getById(entry.entityId)
        require(local == null || local.titleId == titleId)
        if (database.titleDao().getById(titleId) == null) return
        if (current == null) {
            // A removed remote event cannot be recreated by an old receipt. Keep any unsent
            // draft in its original queue payload for explicit recovery, not as live history.
            database.viewingDao().deleteById(entry.entityId)
            database.completionQueueDao().clearViewingLink(entry.entityId)
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
