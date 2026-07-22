package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingDao
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
import work.kumarfamilynet.cinemarchive.core.model.CinemaOutingRules
import work.kumarfamilynet.cinemarchive.core.model.EpisodeDetail
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.OutingStatus
import work.kumarfamilynet.cinemarchive.core.model.SeasonDetail
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.core.model.UpNextBoard
import work.kumarfamilynet.cinemarchive.core.model.UpNextOuting
import work.kumarfamilynet.cinemarchive.core.model.UpNextWatching
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
    private val cinemaOutingDao: CinemaOutingDao,
    private val outbox: MutationOutbox,
) {
    fun observeLibrary(): Flow<List<LibraryTitle>> = combine(
        titleDao.observeLibrary(),
        cinemaOutingDao.observeAllOutings(),
    ) { rows, outings ->
        val scheduledTitleIds = CinemaOutingRules.titleIdsWithScheduledOuting(outings.map { it.toDomain() })
        rows.map { row ->
            LibraryTitle(
                id = row.id,
                name = row.title,
                year = row.year,
                posterUrl = row.posterUrl,
                status = LibraryStatus.valueOf(row.status),
                type = MediaType.valueOf(row.type),
                director = row.director,
                network = row.network,
                rating = row.rating,
                hasScheduledOuting = row.id in scheduledTitleIds,
            )
        }
    }

    /** Continue-watching + watchlist + marquee board for the Up Next screen. Episode totals
     *  come from [SeasonDao.observeAllSeasons]'s already-aggregated per-season counts (same
     *  rollup the Ledger board uses) rather than a new query — a WATCHING title with zero
     *  season rows (i.e. a movie) simply can't produce a progress card and is skipped.
     *  Watchlist titles with a scheduled outing move to the marquee instead of the plain
     *  watchlist list (docs/superpowers/plans/2026-07-21-android-cinema-outings.md §7). */
    fun observeUpNext(): Flow<UpNextBoard> = combine(
        titleDao.observeLibrary(),
        seasonDao.observeAllSeasons(),
        cinemaOutingDao.observeAllOutings(),
        viewingDao.observeAllViewings(),
    ) { titles, seasons, outingRows, viewingRows ->
        val now = Instant.now()
        val outings = outingRows.map { it.toDomain() }
        val titlesById = titles.associateBy { it.id }
        val viewingsById = viewingRows.associate { it.id to Viewing(it.id, it.date, it.rating, it.notes, it.venue, it.companions, it.outingId) }
        val scheduledTitleIds = CinemaOutingRules.titleIdsWithScheduledOuting(outings)

        val totalsByTitle = seasons.groupBy { it.titleId }.mapValues { (_, rows) ->
            rows.sumOf { it.episodeCount } to rows.sumOf { it.episodesWatched }
        }
        val watching = titles
            .filter { LibraryStatus.valueOf(it.status) == LibraryStatus.WATCHING }
            .mapNotNull { row ->
                val (total, watched) = totalsByTitle[row.id] ?: return@mapNotNull null
                if (total <= 0) return@mapNotNull null
                UpNextWatching(row.id, row.title, row.posterUrl, watched, total)
            }
        val watchlist = titles
            .filter { LibraryStatus.valueOf(it.status) == LibraryStatus.WATCHLIST && it.id !in scheduledTitleIds }
            .map { row ->
                LibraryTitle(
                    id = row.id,
                    name = row.title,
                    year = row.year,
                    posterUrl = row.posterUrl,
                    status = LibraryStatus.WATCHLIST,
                    type = MediaType.valueOf(row.type),
                    director = row.director,
                    network = row.network,
                    rating = row.rating,
                )
            }
        val onTheMarquee = CinemaOutingRules.marqueeEntries(outings, now).mapNotNull { outing ->
            val title = titlesById[outing.titleId] ?: return@mapNotNull null
            UpNextOuting(outing, title.title, title.posterUrl)
        }
        val freshFromTheLobby = CinemaOutingRules.pendingFollowUp(outings, viewingsById, now).mapNotNull { outing ->
            val title = titlesById[outing.titleId] ?: return@mapNotNull null
            UpNextOuting(outing, title.title, title.posterUrl)
        }
        UpNextBoard(watching, watchlist, onTheMarquee, freshFromTheLobby)
    }

    /** Marks the next unwatched episode of [titleId] as watched (season/episode order) —
     *  the Up Next screen's "Mark episode watched" action. Deliberately doesn't flip the
     *  title's status: no other episode action in the app does (status is a manual, separate
     *  choice via the status chips), and the locally cached episode rows aren't guaranteed to
     *  match the season's full episodeCount, so "no more unwatched rows" isn't a safe proxy
     *  for "season complete". */
    suspend fun advanceNextEpisode(titleId: String, watchedAt: String?) {
        val episodes = episodeDao.observeEpisodes(titleId).first()
        val watchCounts = watchEventDao.observeWatchCounts(titleId).first().associate { it.episodeId to it.watchCount }
        val next = episodes.firstOrNull { (watchCounts[it.id] ?: 0) <= 0 } ?: return
        logEpisodeWatched(next.id, watchedAt)
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
            cinemaOutingDao.observeOutingsForTitle(titleId),
        ) { title, aggregate, viewings, outingRows ->
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
                        companions = viewing.companions,
                        outingId = viewing.outingId,
                    )
                },
                scheduledOuting = outingRows.map { it.toDomain() }
                    .filter { it.status == OutingStatus.SCHEDULED }
                    .minByOrNull { it.showtime },
            )
        }
    }

    /** Rates the outing's auto-logged viewing (the post-show sheet's ★ control) and, matching
     *  [updateTitleRating]'s semantics, bumps the title's own rating too — the web plan's §4.4
     *  "writes viewing.rating and updates title.rating (same semantics as logViewing)". Notes
     *  are a separate action ([updateViewingNotes]): the sheet's "Done" button always fires
     *  regardless of whether the user actually touched the star control, and coupling it to
     *  rating would silently stamp a fake 0★ rating on a still-unrated viewing. */
    suspend fun rateViewing(viewingId: String, titleId: String, rating: Double) {
        val existing = viewingDao.getById(viewingId) ?: return
        val updated = existing.copy(rating = rating)
        viewingDao.upsert(updated)
        outbox.enqueue(
            entityType = "viewing",
            entityId = viewingId,
            operation = "update",
            payload = JSONObject().apply { put("id", viewingId); put("rating", rating) },
        )
        updateTitleRating(titleId, rating, Instant.now().toString())
    }

    suspend fun updateViewingNotes(viewingId: String, notes: String) {
        val existing = viewingDao.getById(viewingId) ?: return
        viewingDao.upsert(existing.copy(notes = notes))
        outbox.enqueue(
            entityType = "viewing",
            entityId = viewingId,
            operation = "update",
            payload = JSONObject().apply { put("id", viewingId); put("notes", notes) },
        )
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

    /** Sets [titleId]'s own rating (distinct from per-episode ratings) — same in-place
     *  update contract as [updateTitleStatus]. */
    suspend fun updateTitleRating(titleId: String, rating: Double, updatedAt: String) {
        titleDao.updateRating(titleId, rating, updatedAt)
        outbox.enqueue(
            entityType = "title",
            entityId = titleId,
            operation = "update",
            payload = JSONObject().apply {
                put("id", titleId)
                put("rating", rating)
                put("updatedAt", updatedAt)
            },
        )
    }
}
