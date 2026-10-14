package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.LibraryDatabase
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails

/** Update only known catalog columns. Missing provider rows never delete tracked episodes. */
internal suspend fun enqueueKnownEpisodeMetadata(db: LibraryDatabase, outbox: MutationOutbox,
    titleId: String, ownerId: String, fresh: MediaDetails): Boolean {
    val seasons = db.seasonDao().observeSeasons(titleId).first().associateBy { it.id }
    val operations = JSONArray().put(JSONObject().put("table", "titles").put("action", "update")
        .put("key", JSONObject().put("id", titleId)).put("values", JSONObject()))
    val changed = db.episodeDao().observeEpisodes(titleId).first().mapNotNull { old ->
        val number = seasons[old.seasonId]?.seasonNumber ?: return@mapNotNull null
        val remote = fresh.seasons.firstOrNull { it.seasonNumber == number }?.episodes
            ?.firstOrNull { it.episodeNumber == old.episodeNumber } ?: return@mapNotNull null
        val next = old.copy(episodeName = remote.name?.takeIf(String::isNotBlank) ?: old.episodeName,
            airDate = remote.airDate?.takeIf(String::isNotBlank) ?: old.airDate,
            runtime = remote.runtime?.takeIf { it > 0 } ?: old.runtime,
            synopsis = remote.synopsis?.takeIf(String::isNotBlank) ?: old.synopsis,
            stillUrl = remote.stillUrl?.takeIf(String::isNotBlank) ?: old.stillUrl)
        if (next == old) return@mapNotNull null
        operations.put(JSONObject().put("table", "episodes").put("action", "update")
            .put("key", JSONObject().put("id", old.id)).put("values", JSONObject()
                .put("episode_name", next.episodeName ?: JSONObject.NULL).put("air_date", next.airDate ?: JSONObject.NULL)
                .put("runtime", next.runtime ?: JSONObject.NULL).put("synopsis", next.synopsis ?: JSONObject.NULL)
                .put("still_url", next.stillUrl ?: JSONObject.NULL)))
        next
    }
    if (changed.isEmpty()) return false
    assertImportOperations(operations)
    db.episodeDao().upsertAll(changed)
    outbox.enqueue("title_catalog", titleId, "ensure", JSONObject().put("ownerId", ownerId).put("titleId", titleId)
        .put("operations", operations).put("catalog", JSONArray()))
    return true
}
