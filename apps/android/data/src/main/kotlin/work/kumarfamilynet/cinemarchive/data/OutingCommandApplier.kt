package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

/** Runs inside the outbox ACK transaction; performs no network IO and never replays receipt rows. */
class OutingCommandApplier(private val database: LibraryDatabase, private val ownerId: String) {
    suspend fun apply(entry: OutboxEntity, envelope: JSONObject) {
        val current = currentOutingCommandRow(entry, envelope, ownerId)
        val outings = database.cinemaOutingDao()
        val local = outings.getById(entry.entityId) ?: return // a later local deletion remains absent
        if (database.titleDao().getById(local.titleId) == null) return
        val queue = database.outboxDao().getPending()
        val index = queue.indexOfFirst { it.id == entry.id }
        require(index >= 0) { "Outing command is no longer pending." }
        val later = queue.drop(index + 1).filter { it.entityType == "cinema_outing" && it.entityId == entry.entityId }
        if (current == null) {
            if (later.isEmpty()) outings.deleteById(entry.entityId)
            // Later intent stays visible and queued for explicit conflict review; no remote create.
            return
        }
        if (database.titleDao().getById(current.getString("title_id")) == null) return
        val projection = runCatching {
            val row = JSONObject(current.toString())
            later.forEach { pending ->
                val patch = outingWireBody(JSONObject(pending.payloadJson), ownerId, false)
                patch.keys().forEach { key -> row.put(key, patch.get(key)) }
            }
            row.toRecoveryOuting()
        }.getOrNull()
        if (projection != null) outings.upsert(projection)
        else check(later.isNotEmpty()) // preserve malformed later intent and its existing local projection
    }
}
