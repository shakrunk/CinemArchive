package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.TitleReconcileDao

/**
 * The only entity type Android currently writes with in-place "update" semantics is
 * `title` ([LibraryRepository.updateTitleStatus] / [LibraryRepository.updateTitleRating]) —
 * every other outbox entry is an append-only insert with a client-generated id, so retries
 * upsert cleanly and a real conflict can't arise for them (docs/android-sync-contract.md §4.1).
 *
 * A conflict means the server already holds a newer row. The local projection is reconciled to
 * the server's WHOLE editable state (status AND rating — either kind of edit can be the one that
 * lost, and the server row may differ in the other field too), so the UI never shows a value
 * the server doesn't have. The rejected edit isn't silently lost: [MutationOutbox] reports a
 * [ConflictNotice] to the user.
 */
class TitleConflictHandler(
    private val titleDao: TitleDao,
    /** Needed to mirror a server row that has no rating (clears the local value). */
    private val reconcileDao: TitleReconcileDao? = null,
) : ConflictHandler {
    override suspend fun applyRemote(entityType: String, entityId: String, serverPayload: JSONObject) {
        if (entityType != "title") return
        // serverPayload.status comes straight from Postgres's lowercase watch_status enum
        // (SupabaseRemoteMutationWriter's conflict read) — Room stores LibraryStatus.name.
        val updatedAt = serverPayload.getString("updatedAt")
        titleDao.updateStatus(titleId = entityId, status = serverPayload.getString("status").uppercase(), updatedAt = updatedAt)
        // Field PRESENCE matters: a payload without a "rating" key says nothing about the rating;
        // an explicit null means the server row is unrated, so the local rating must be cleared.
        if (serverPayload.has("rating")) {
            if (serverPayload.isNull("rating")) {
                reconcileDao?.setRating(entityId, null, updatedAt)
            } else {
                titleDao.updateRating(titleId = entityId, rating = serverPayload.getDouble("rating"), updatedAt = updatedAt)
            }
        }
    }

    override suspend fun rebasePending(entityType: String, entityId: String, laterPending: List<JSONObject>) {
        if (entityType != "title") return
        for (payload in laterPending) {
            if (payload.optString("operation").isNotEmpty()) continue
            val updatedAt = payload.optString("updatedAt").ifEmpty { continue }
            if (payload.has("status")) titleDao.updateStatus(entityId, payload.getString("status").uppercase(), updatedAt)
            if (payload.has("rating") && !payload.isNull("rating")) titleDao.updateRating(entityId, payload.getDouble("rating"), updatedAt)
        }
    }
}
