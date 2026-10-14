package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

internal object TitleMetadataFixture {
    const val owner = "10000000-0000-4000-8000-000000000001"
    const val title = "20000000-0000-4000-8000-000000000001"
    const val baseline = "2026-10-08T01:00:00Z"
    const val applied = "2026-10-08T02:00:00.123456Z"
    fun entity() = TitleEntity(title, 42, "MOVIE", "Film", 2026, null, emptyList(), null, null, null, 90,
        null, "WATCHLIST", null, "Keep notes", baseline, baseline, tags = listOf("Original"))
    fun patch(vararg tags: String) = JSONObject().put("tags", JSONArray(tags.toList()))
    fun row(entity: TitleEntity = entity()) = JSONObject()
        .put("id", entity.id).put("user_id", owner).put("tmdb_id", entity.tmdbId).put("type", "movie")
        .put("title", entity.title).put("year", entity.year).put("director", entity.director ?: JSONObject.NULL)
        .put("genres", JSONArray(entity.genres)).put("poster_url", entity.posterUrl ?: JSONObject.NULL)
        .put("backdrop_url", entity.backdropUrl ?: JSONObject.NULL).put("synopsis", entity.synopsis ?: JSONObject.NULL)
        .put("runtime", entity.runtime ?: JSONObject.NULL).put("network", entity.network ?: JSONObject.NULL)
        .put("notes", entity.notes ?: JSONObject.NULL).put("added_at", entity.addedAt).put("updated_at", entity.updatedAt)
        .put("imdb_rating", entity.imdbRating ?: JSONObject.NULL).put("original_language", entity.originalLanguage ?: JSONObject.NULL)
        .put("release_date", entity.releaseDate ?: JSONObject.NULL).put("studios", JSONArray(entity.studios))
        .put("collection_id", entity.collectionId ?: JSONObject.NULL).put("collection_name", entity.collectionName ?: JSONObject.NULL)
        .put("tags", JSONArray(entity.tags)).put("status", entity.status.lowercase()).put("rating", entity.rating ?: JSONObject.NULL)
    fun entry(patch: JSONObject = patch("Saved"), id: String = "30000000-0000-4000-8000-000000000001") =
        OutboxEntity(id, "title", title, TITLE_METADATA_COMMAND, titleMetadataPayload(owner, title, patch, baseline, null).toString(), 1)
    fun effect(entry: OutboxEntity) = row(entity().withTitleMetadata(titleMetadataPatch(entry, owner))).put("updated_at", applied)
    fun receipt(entry: OutboxEntity) = JSONObject().put("operationId", entry.id).put("rows", JSONArray().put(
        JSONObject().put("table", "titles").put("key", JSONObject().put("id", title)).put("row", effect(entry))))
    fun envelope(entry: OutboxEntity, current: JSONObject? = effect(entry)) =
        JSONObject().put("receipt", receipt(entry)).put("current", current ?: JSONObject.NULL)
    fun library(db: LibraryDatabase, outbox: MutationOutbox) = LibraryRepository(
        db.titleDao(), db.seasonDao(), db.episodeDao(), db.episodeWatchEventDao(), db.episodeRatingDao(), db.episodeReviewDao(),
        db.viewingDao(), db.cinemaOutingDao(), db.titleCastDao(), db.titleCrewDao(), db.theaterInterestDao(), outbox,
        object : EpisodeMetadataFetcher {
            override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = emptyList<MediaEpisode>()
            override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int) = EpisodeCast.EMPTY
        }, db.personCreditsDao(), mutationOwnerId = owner,
    )
}
