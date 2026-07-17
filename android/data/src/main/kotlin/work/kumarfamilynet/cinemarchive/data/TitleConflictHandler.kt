package work.kumarfamilynet.cinemarchive.data

import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.TitleDao

/**
 * The only entity type Android currently writes with in-place "update" semantics is
 * `title` ([LibraryRepository.updateTitleStatus]) — every other outbox entry
 * (episode_watch_event/episode_rating/episode_review/viewing) is an append-only insert with
 * a client-generated id, so retries upsert cleanly and a real conflict can't arise for them
 * (docs/android-sync-contract.md §4.1). This handler applies the server's winning `title`
 * row locally; other entity types are a no-op since [RemoteMutationWriter] can't produce a
 * [PushResult.Conflict] for them today.
 */
class TitleConflictHandler(private val titleDao: TitleDao) : ConflictHandler {
    override suspend fun applyRemote(entityType: String, entityId: String, serverPayload: JSONObject) {
        if (entityType != "title") return
        titleDao.updateStatus(
            titleId = entityId,
            status = serverPayload.getString("status"),
            updatedAt = serverPayload.getString("updatedAt"),
        )
    }
}
