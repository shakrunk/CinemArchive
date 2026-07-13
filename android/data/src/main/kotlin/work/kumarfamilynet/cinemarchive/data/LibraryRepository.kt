package work.kumarfamilynet.cinemarchive.data

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.EpisodeDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeReviewDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeReviewEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchCount
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventEntity
import work.kumarfamilynet.cinemarchive.core.database.SeasonDao
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.model.EpisodeDetail
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.SeasonDetail
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.core.model.Viewing

private data class EpisodeAggregate(
    val seasons: List<SeasonEntity>,
    val episodes: List<EpisodeEntity>,
    val watchCounts: List<EpisodeWatchCount>,
    val ratings: List<EpisodeRatingEntity>,
)

/**
 * The app reads the Library and Title detail, and queues tracking mutations, through this
 * Room-backed repository. Writes land in Room immediately (optimistic) and are queued in
 * [outbox] for a remote push once network sync is wired up (docs/android-sync-contract.md).
 */
class LibraryRepository(
    private val titleDao: TitleDao,
    private val seasonDao: SeasonDao,
    private val episodeDao: EpisodeDao,
    private val watchEventDao: EpisodeWatchEventDao,
    private val ratingDao: EpisodeRatingDao,
    private val reviewDao: EpisodeReviewDao,
    private val viewingDao: ViewingDao,
    private val outbox: MutationOutbox,
) {
    fun observeLibrary(): Flow<List<LibraryTitle>> = titleDao.observeLibrary().map { rows ->
        rows.map { row ->
            LibraryTitle(
                id = row.id,
                name = row.title,
                year = row.year,
                posterUrl = row.posterUrl,
                status = LibraryStatus.valueOf(row.status),
            )
        }
    }

    fun observeTitleDetail(titleId: String): Flow<TitleDetail?> {
        val episodeAggregate = combine(
            seasonDao.observeSeasons(titleId),
            episodeDao.observeEpisodes(titleId),
            watchEventDao.observeWatchCounts(titleId),
            ratingDao.observeRatings(titleId),
        ) { seasons, episodes, watchCounts, ratings ->
            EpisodeAggregate(seasons, episodes, watchCounts, ratings)
        }

        return combine(
            titleDao.observeTitle(titleId),
            episodeAggregate,
            viewingDao.observeViewings(titleId),
        ) { title, aggregate, viewings ->
            if (title == null) return@combine null

            val watchCountByEpisode = aggregate.watchCounts.associate { it.episodeId to it.watchCount }
            // aggregate.ratings is newest-first (see EpisodeRatingDao), so the first match per
            // episode is the latest rating.
            val latestRatingByEpisode = mutableMapOf<String, Double>()
            for (rating in aggregate.ratings) {
                latestRatingByEpisode.getOrPut(rating.episodeId) { rating.rating }
            }

            val episodesBySeason = aggregate.episodes.groupBy { it.seasonId }

            TitleDetail(
                id = title.id,
                type = MediaType.valueOf(title.type),
                title = title.title,
                year = title.year,
                posterUrl = title.posterUrl,
                backdropUrl = title.backdropUrl,
                synopsis = title.synopsis,
                director = title.director,
                network = title.network,
                runtime = title.runtime,
                status = LibraryStatus.valueOf(title.status),
                rating = title.rating,
                notes = title.notes,
                genres = title.genres,
                seasons = aggregate.seasons.map { season ->
                    SeasonDetail(
                        id = season.id,
                        seasonNumber = season.seasonNumber,
                        episodeCount = season.episodeCount,
                        episodesWatched = season.episodesWatched,
                        airYear = season.airYear,
                        episodes = (episodesBySeason[season.id] ?: emptyList()).map { episode ->
                            EpisodeDetail(
                                id = episode.id,
                                episodeNumber = episode.episodeNumber,
                                episodeName = episode.episodeName,
                                airDate = episode.airDate,
                                runtime = episode.runtime,
                                watchCount = watchCountByEpisode[episode.id] ?: 0,
                                latestRating = latestRatingByEpisode[episode.id],
                            )
                        },
                    )
                },
                viewings = viewings.map { viewing ->
                    Viewing(
                        id = viewing.id,
                        date = viewing.date,
                        rating = viewing.rating,
                        notes = viewing.notes,
                        venue = viewing.venue,
                    )
                },
            )
        }
    }

    /** Logs a watch for [episodeId] — optimistic local write + a queued remote push, per
     *  the idempotency contract in docs/android-sync-contract.md §4.2: the id is generated
     *  here (not left to the server) so a retried push upserts instead of duplicating. */
    suspend fun logEpisodeWatched(episodeId: String, watchedAt: String?) {
        val id = UUID.randomUUID().toString()
        watchEventDao.upsertAll(listOf(EpisodeWatchEventEntity(id = id, episodeId = episodeId, watchedAt = watchedAt)))
        outbox.enqueue(
            entityType = "episode_watch_event",
            entityId = id,
            operation = "upsert",
            payload = JSONObject().apply {
                put("id", id)
                put("episodeId", episodeId)
                put("watchedAt", watchedAt ?: JSONObject.NULL)
            },
        )
    }

    /** Records a rating for [episodeId] — same client-generated-id contract as
     *  [logEpisodeWatched]; ratings are an independent log, not tied to a watch event. */
    suspend fun logEpisodeRating(episodeId: String, rating: Double, ratedAt: String) {
        val id = UUID.randomUUID().toString()
        ratingDao.upsertAll(listOf(EpisodeRatingEntity(id = id, episodeId = episodeId, rating = rating, ratedAt = ratedAt)))
        outbox.enqueue(
            entityType = "episode_rating",
            entityId = id,
            operation = "upsert",
            payload = JSONObject().apply {
                put("id", id)
                put("episodeId", episodeId)
                put("rating", rating)
                put("ratedAt", ratedAt)
            },
        )
    }

    /** Records a review for [episodeId] — same client-generated-id contract as
     *  [logEpisodeWatched]; reviews are an independent log, not tied to a watch event or rating. */
    suspend fun logEpisodeReview(episodeId: String, reviewText: String, reviewedAt: String) {
        val id = UUID.randomUUID().toString()
        reviewDao.upsertAll(listOf(EpisodeReviewEntity(id = id, episodeId = episodeId, reviewText = reviewText, reviewedAt = reviewedAt)))
        outbox.enqueue(
            entityType = "episode_review",
            entityId = id,
            operation = "upsert",
            payload = JSONObject().apply {
                put("id", id)
                put("episodeId", episodeId)
                put("reviewText", reviewText)
                put("reviewedAt", reviewedAt)
            },
        )
    }

    /** Logs a re-watch timeline entry for [titleId] — same client-generated-id contract as
     *  [logEpisodeWatched]. */
    suspend fun logViewing(titleId: String, date: String?) {
        val id = UUID.randomUUID().toString()
        viewingDao.upsertAll(listOf(ViewingEntity(id = id, titleId = titleId, date = date, rating = null, notes = null, venue = null)))
        outbox.enqueue(
            entityType = "viewing",
            entityId = id,
            operation = "upsert",
            payload = JSONObject().apply {
                put("id", id)
                put("titleId", titleId)
                put("date", date ?: JSONObject.NULL)
            },
        )
    }

    /** Changes [titleId]'s status — an in-place update, not an append-only log, so the
     *  outbox operation is "update" rather than "upsert". [updatedAt] feeds the
     *  last-write-wins conflict resolution designed in docs/android-sync-contract.md §4.2,
     *  so it must reflect when this change was made, not be left stale. */
    suspend fun updateTitleStatus(titleId: String, status: LibraryStatus, updatedAt: String) {
        titleDao.updateStatus(titleId, status.name, updatedAt)
        outbox.enqueue(
            entityType = "title",
            entityId = titleId,
            operation = "update",
            payload = JSONObject().apply {
                put("id", titleId)
                put("status", status.name)
                put("updatedAt", updatedAt)
            },
        )
    }
}
