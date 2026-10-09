package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import org.json.JSONArray
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingDao
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeCrewEntity
import work.kumarfamilynet.cinemarchive.core.database.SeasonCastEntity
import work.kumarfamilynet.cinemarchive.core.database.PersonCreditsDao
import work.kumarfamilynet.cinemarchive.core.model.LibraryPerson
import work.kumarfamilynet.cinemarchive.core.model.PersonCredit
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeRatingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeReviewDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeReviewEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventEntity
import work.kumarfamilynet.cinemarchive.core.database.SeasonDao
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity
import work.kumarfamilynet.cinemarchive.core.database.TheaterInterestDao
import work.kumarfamilynet.cinemarchive.core.database.TheaterInterestEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCastDao
import work.kumarfamilynet.cinemarchive.core.database.TitleCastEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewDao
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.model.AddTitleRequest
import work.kumarfamilynet.cinemarchive.core.model.CinemaOutingRules
import work.kumarfamilynet.cinemarchive.core.model.EpisodeCast
import work.kumarfamilynet.cinemarchive.core.model.EpisodeDetail
import work.kumarfamilynet.cinemarchive.core.model.EpisodeLogDraft
import work.kumarfamilynet.cinemarchive.core.model.EpisodeWatch
import work.kumarfamilynet.cinemarchive.core.model.EpisodeWatchReceipt
import work.kumarfamilynet.cinemarchive.core.model.EpisodeRating
import work.kumarfamilynet.cinemarchive.core.model.EpisodeReview
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.LibraryTitle
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.OutingStatus
import work.kumarfamilynet.cinemarchive.core.model.SeasonDetail
import work.kumarfamilynet.cinemarchive.core.model.TitleDetail
import work.kumarfamilynet.cinemarchive.core.model.UpNextBoard
import work.kumarfamilynet.cinemarchive.core.model.UpNextOnThisDay
import work.kumarfamilynet.cinemarchive.core.model.UpNextOuting
import work.kumarfamilynet.cinemarchive.core.model.UpNextWatching
import work.kumarfamilynet.cinemarchive.core.model.Viewing
import work.kumarfamilynet.cinemarchive.core.model.ViewingDraft
import work.kumarfamilynet.cinemarchive.core.database.ViewingCompletionAliasDao
import work.kumarfamilynet.cinemarchive.core.model.isSpecialsSeason

private data class TitleCreditAggregate(
    val cast: List<TitleCastEntity>, val crew: List<TitleCrewEntity>,
    val seasonCast: List<SeasonCastEntity>, val episodeCrew: List<EpisodeCrewEntity>,
)

private data class EpisodeAggregate(
    val seasons: List<SeasonEntity>,
    val episodes: List<EpisodeEntity>,
    val watchEvents: List<EpisodeWatchEventEntity>,
    val ratings: List<EpisodeRatingEntity>,
    val reviews: List<EpisodeReviewEntity>,
)

private data class UpNextCoreSources(
    val titles: List<TitleEntity>,
    val seasons: List<SeasonEntity>,
    val outingRows: List<CinemaOutingEntity>,
    val viewingRows: List<ViewingEntity>,
)

private data class UpNextEpisodeSources(
    val episodes: List<EpisodeEntity>,
    val watchEvents: List<EpisodeWatchEventEntity>,
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
    private val titleCastDao: TitleCastDao,
    private val titleCrewDao: TitleCrewDao,
    private val theaterInterestDao: TheaterInterestDao,
    private val outbox: MutationOutbox,
    private val episodeMetadataFetcher: EpisodeMetadataFetcher,
    private val personCreditsDao: PersonCreditsDao,
    private val mutationOwnerId: String? = null,
    private val viewingAliases: ViewingCompletionAliasDao? = null,
    private val isCurrentOwner: () -> Boolean = { mutationOwnerId != null },
    private val moviegoingPreferences: MoviegoingPreferencesRepository? = null,
) {
    val viewingOwnerId: String? get() = mutationOwnerId
    /**
     * Adds a catalog result to the library: an optimistic Room write of everything the title
     * brings with it (the title row, its seasons and episodes, top-billed cast and key crew,
     * and a seed viewing when it's being logged as already watched), plus **one** outbox entry
     * carrying all of it.
     *
     * One entry, not one per table, on purpose. Every child row here is foreign-keyed to the
     * title server-side, and [MutationOutbox.flush] pushes entries independently in
     * `createdAt` order — separate entries enqueued in the same millisecond could push a
     * season before its title existed. Bundling them lets the writer order the inserts itself
     * and makes a partial failure retry as a whole (every insert is an id-keyed upsert, so
     * replaying one that already landed is a no-op).
     *
     * Returns the new title's local id — or the existing one, without writing anything, if
     * this TMDB id is already in the library. That guard mirrors the server's
     * `unique_user_tmdb` constraint, so the duplicate case is refused here rather than
     * surfacing later as a push that can never succeed.
     */
    suspend fun addTitle(request: AddTitleRequest): String {
        val details = request.details
        titleDao.findIdByTmdbKey(details.tmdbId, details.type.name)?.let { return it }

        val titleId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val seasons = details.seasons.map { season ->
            SeasonEntity(
                id = UUID.randomUUID().toString(),
                titleId = titleId,
                seasonNumber = season.seasonNumber,
                episodeCount = season.episodeCount,
                episodesWatched = 0,
                airYear = season.airYear,
            ) to season.episodes
        }
        val episodes = seasons.flatMap { (seasonEntity, seasonEpisodes) ->
            seasonEpisodes.map { episode ->
                EpisodeEntity(
                    id = UUID.randomUUID().toString(),
                    titleId = titleId,
                    seasonId = seasonEntity.id,
                    episodeNumber = episode.episodeNumber,
                    episodeName = episode.name,
                    airDate = episode.airDate,
                    runtime = episode.runtime,
                    synopsis = episode.synopsis,
                    stillUrl = episode.stillUrl,
                )
            }
        }
        val cast = details.cast.distinctBy { it.tmdbPersonId }.map { credit ->
            TitleCastEntity(
                id = UUID.randomUUID().toString(),
                titleId = titleId,
                tmdbPersonId = credit.tmdbPersonId,
                name = credit.name,
                characterName = credit.characterName,
                castOrder = credit.order,
            )
        }
        val crew = details.crew.map { credit ->
            TitleCrewEntity(
                id = UUID.randomUUID().toString(),
                titleId = titleId,
                tmdbPersonId = credit.tmdbPersonId,
                name = credit.name,
                job = credit.job,
                department = credit.department,
            )
        }
        val seasonIds = seasons.associate { it.first.seasonNumber to it.first.id }
        val episodeIds = episodes.associate { (it.seasonId to it.episodeNumber) to it.id }
        val seasonCast = details.seasons.flatMap { season ->
            val seasonId = seasonIds.getValue(season.seasonNumber)
            season.cast.distinctBy { it.tmdbPersonId }.map { credit ->
                SeasonCastEntity(UUID.randomUUID().toString(), titleId, seasonId, credit.tmdbPersonId,
                    credit.name, credit.characterName, credit.order)
            }
        }
        val episodeCrew = details.seasons.flatMap { season ->
            season.episodes.flatMap { episode ->
                val episodeId = episodeIds.getValue(seasonIds.getValue(season.seasonNumber) to episode.episodeNumber)
                episode.crew.distinctBy { it.tmdbPersonId to it.job }.map { credit ->
                    EpisodeCrewEntity(UUID.randomUUID().toString(), titleId, episodeId, credit.tmdbPersonId, credit.name, credit.job)
                }
            }
        }
        // A title logged as already watched gets its first viewing here, so it lands on the
        // Ledger's date-bucketed widgets immediately instead of only counting once the user
        // logs a re-watch. Matches the web Add workflow, which seeds a viewing on the same
        // condition. Any other status has nothing to date yet.
        val viewing = if (request.status == LibraryStatus.WATCHED) {
            ViewingEntity(
                id = UUID.randomUUID().toString(),
                titleId = titleId,
                date = request.watchedOn,
                rating = request.rating,
                notes = request.notes,
                venue = null,
            )
        } else {
            null
        }
        val title = TitleEntity(
            id = titleId,
            tmdbId = details.tmdbId,
            type = details.type.name,
            title = details.title,
            year = details.year,
            director = details.director,
            genres = details.genres,
            posterUrl = details.posterUrl,
            backdropUrl = details.backdropUrl,
            synopsis = details.synopsis,
            runtime = details.runtime,
            network = details.network,
            status = request.status.name,
            rating = request.rating,
            notes = request.notes,
            addedAt = now,
            updatedAt = now,
            imdbRating = details.imdbRating,
            originalLanguage = details.originalLanguage,
            releaseDate = details.releaseDate,
            studios = details.studios,
            collectionId = details.collectionId,
            collectionName = details.collectionName,
            contentRating = details.contentRating,
            imdbId = details.imdbId,
            rtScore = details.rtScore,
            metacriticScore = details.metacriticScore,
        )

        outbox.atomically {
            titleDao.upsertAll(listOf(title))
            seasonDao.upsertAll(seasons.map { it.first })
            episodeDao.upsertAll(episodes)
            if (cast.isNotEmpty()) titleCastDao.upsertAll(cast)
            if (crew.isNotEmpty()) titleCrewDao.upsertAll(crew)
            personCreditsDao.upsertSeasonCast(seasonCast)
            personCreditsDao.upsertEpisodeCrew(episodeCrew)
            viewing?.let { viewingDao.upsertAll(listOf(it)) }

            outbox.enqueue(
                entityType = "title",
                entityId = titleId,
                operation = "insert",
                payload = buildAddTitlePayload(title, details, seasons.map { it.first }, episodes, cast, crew, viewing, seasonCast, episodeCrew),
            )
        }
        return titleId
    }

    /**
     * Removes a title from the library for good — the Title detail screen's "Remove from
     * library" action. Room's `on delete cascade` (see [TitleEntity]'s children) takes every
     * locally mirrored season/episode/viewing/cast/crew/outing row with it in the same
     * statement; the outbox's `delete` entry (pushed by
     * [SupabaseRemoteMutationWriter.deleteTitle]) does the same server-side via schema.sql's
     * own cascades. Mirrors the web app's `removeTitle` (`useAppStore.ts`), which is likewise
     * a plain hard delete with no undo.
     */
    suspend fun removeTitle(titleId: String) {
        outbox.atomically {
            titleDao.deleteById(titleId)
            outbox.enqueue(
                entityType = "title",
                entityId = titleId,
                operation = "delete",
                payload = JSONObject().put("id", titleId),
            )
        }
    }

    /** What sync's merge planner needs to know about an existing title — see [planMerge]. */
    suspend fun syncSnapshot(titleId: String): TitleSyncSnapshot? {
        val title = titleDao.getById(titleId) ?: return null
        val dates = viewingDao.observeViewings(titleId).first().mapNotNull { it.date }.toSet()
        return TitleSyncSnapshot(
            status = runCatching { LibraryStatus.valueOf(title.status) }.getOrDefault(LibraryStatus.WATCHLIST),
            rating = title.rating,
            viewingDates = dates,
        )
    }

    /** Local row id for an already-owned TMDB title, if any — lets the Add flow show
     *  "In library" and route a tap to the real title-detail screen instead of re-adding. */
    suspend fun findLibraryTitleId(tmdbId: Int, type: MediaType): String? =
        titleDao.findIdByTmdbKey(tmdbId, type.name)

    fun observeLibrary(): Flow<List<LibraryTitle>> = combine(
        titleDao.observeLibrary(),
        cinemaOutingDao.observeAllOutings(),
        titleDao.observeLastInteractions(),
        theaterInterestDao.observeAll(),
        combine(titleCastDao.observeAllCast(), personCreditsDao.observeLibraryPeople()) { cast, people -> cast to people },
    ) { rows, outings, interactions, theaterInterest, credits ->
        val scheduledTitleIds = CinemaOutingRules.titleIdsWithScheduledOuting(outings.map { it.toDomain() })
        val lastInteractionByTitle = interactions.associate { it.titleId to it.lastInteractionAt }
        val interestedTitleIds = theaterInterest.map { it.titleId }.toSet()
        val castByTitle = credits.first.groupBy { it.titleId }
        val peopleByTitle = credits.second.groupBy { it.titleId }
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
                releaseDate = row.releaseDate,
                genres = row.genres,
                lastInteractionAt = lastInteractionByTitle[row.id],
                interestedInTheaters = row.id in interestedTitleIds,
                addedAt = row.addedAt,
                originalLanguage = row.originalLanguage,
                tags = row.tags,
                studios = row.studios,
                collectionId = row.collectionId,
                collectionName = row.collectionName,
                castNames = castByTitle[row.id].orEmpty().map { it.name },
                people = peopleByTitle[row.id].orEmpty().distinctBy { it.tmdbPersonId }.map { LibraryPerson(it.tmdbPersonId, it.name) },
            )
        }
    }

    /** TMDB ids already in the library, for Discover to mark its results with. */
    fun observeLibraryTmdbIds(): Flow<Set<Int>> =
        titleDao.observeLibraryTmdbIds().map { it.toSet() }

    /** Real local row id for every already-owned title, keyed by (tmdbId, type) — lets Discover
     *  open the shared title-detail screen for a trending result that's already in the library
     *  instead of its own bare preview (#119/KP-049). */
    fun observeLibraryTitleIdsByTmdbKey(): Flow<Map<Pair<Int, MediaType>, String>> =
        titleDao.observeLibraryTitleIdsByTmdbKey().map { rows ->
            rows.associate { (it.tmdbId to MediaType.valueOf(it.type)) to it.id }
        }

    /** Continue-watching + watchlist + marquee board for the Up Next screen. Episode totals
     *  come from [SeasonDao.observeAllSeasons]'s already-aggregated per-season counts (same
     *  rollup the Ledger board uses) rather than a new query — a WATCHING title with zero
     *  season rows (i.e. a movie) simply can't produce a progress card and is skipped.
     *  *Watched* counts, though, are rolled from [EpisodeDao.observeAllEpisodes] +
     *  [EpisodeWatchEventDao.observeAllWatchEvents] rather than trusting
     *  `seasons.episodesWatched` itself: that column is only ever set when a season row is
     *  first synced down (schema.sql's default/bulk-add value) and nothing server-side updates
     *  it when an episode gets watched afterward (episode_watch_events is the only write the
     *  web app makes) — so it goes stale at 0 (or whatever it started at) for any title tracked
     *  episode-by-episode. Falls back independently for each season without locally synced
     *  episode rows. Watchlist titles with a scheduled outing move to the
     *  marquee instead of the plain watchlist list
     *  (docs/superpowers/plans/2026-07-21-android-cinema-outings.md §7). */
    fun observeUpNext(): Flow<UpNextBoard> = combine(
        combine(
            titleDao.observeAllTitles(),
            seasonDao.observeAllSeasons(),
            cinemaOutingDao.observeAllOutings(),
            viewingDao.observeAllViewings(),
            ::UpNextCoreSources,
        ),
        combine(episodeDao.observeAllEpisodes(), watchEventDao.observeAllWatchEvents(), ::UpNextEpisodeSources),
        theaterInterestDao.observeAll(),
    ) { core, episodeSources, theaterInterest ->
        val (titles, seasons, outingRows, viewingRows) = core
        val (episodes, watchEvents) = episodeSources
        val interestedTitleIds = theaterInterest.map { it.titleId }.toSet()
        val now = Instant.now()
        val today = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        val outings = outingRows.map { it.toDomain() }
        val titlesById = titles.associateBy { it.id }
        val viewingsById = viewingRows.associate { it.id to Viewing(it.id, it.date, it.rating, it.notes, it.venue, it.companions, it.outingId) }
        val scheduledTitleIds = CinemaOutingRules.titleIdsWithScheduledOuting(outings)

        // Specials (season 0) never count toward progress or the next episode — see
        // Specials.kt — so both the season totals and the episode rows are main-season only.
        val mainSeasons = seasons.filterNot { isSpecialsSeason(it.seasonNumber) }
        val seasonById = mainSeasons.associateBy { it.id }
        val watchedEpisodeIds = watchEvents.map { it.episodeId }.toSet()
        val episodesByTitle = episodes.filter { it.seasonId in seasonById }.groupBy { it.titleId }
        val seasonsByTitle = mainSeasons.groupBy { it.titleId }
        val titleIdByEpisode = episodes.associate { it.id to it.titleId }
        val lastWatchedByTitle = watchEvents.filter { it.watchedAt != null }
            .groupBy { titleIdByEpisode[it.episodeId] }
            .mapValues { (_, events) -> events.maxOf { it.watchedAt!! } }
        val watching = titles
            .filter { it.type == MediaType.TV.name && LibraryStatus.valueOf(it.status) == LibraryStatus.WATCHING }
            .sortedByDescending { lastWatchedByTitle[it.id] ?: it.addedAt }
            .mapNotNull { row ->
                val titleSeasons = seasonsByTitle[row.id].orEmpty()
                val total = titleSeasons.sumOf { it.episodeCount }
                val titleEpisodes = episodesByTitle[row.id].orEmpty()
                val watched = watchedEpisodeCount(titleSeasons, titleEpisodes, watchedEpisodeIds)
                val next = titleEpisodes
                    .sortedWith(compareBy({ seasonById[it.seasonId]?.seasonNumber ?: 0 }, { it.episodeNumber }))
                    .firstOrNull { it.id !in watchedEpisodeIds } ?: return@mapNotNull null
                UpNextWatching(
                    id = row.id,
                    name = row.title,
                    posterUrl = row.posterUrl,
                    episodesWatched = watched,
                    episodesTotal = total,
                    nextSeasonNumber = seasonById[next.seasonId]?.seasonNumber,
                    nextEpisodeNumber = next.episodeNumber,
                    nextEpisodeName = next.episodeName,
                    nextEpisodeAirDate = next.airDate,
                    nextEpisodeId = next.id,
                )
            }
        val watchlist = titles
            .filter { LibraryStatus.valueOf(it.status) == LibraryStatus.WATCHLIST && it.id !in scheduledTitleIds }
            .sortedWith(compareBy<TitleEntity> { it.releaseDate?.let { date -> date > today } == true }
                .thenComparator { a, b ->
                    val releaseDate = a.releaseDate
                    if (releaseDate != null && releaseDate > today) {
                        releaseDate.compareTo(b.releaseDate ?: "")
                    } else {
                        b.addedAt.compareTo(a.addedAt)
                    }
                })
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
                    releaseDate = row.releaseDate,
                    interestedInTheaters = row.id in interestedTitleIds,
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
        val nowYear = now.atZone(java.time.ZoneId.systemDefault()).year
        val onThisDay = CinemaOutingRules.onThisDay(outings, now).mapNotNull { outing ->
            val title = titlesById[outing.titleId] ?: return@mapNotNull null
            val outingYear = runCatching { Instant.parse(outing.showtime).atZone(java.time.ZoneId.systemDefault()).year }.getOrNull() ?: return@mapNotNull null
            val viewing = outing.completedViewingId?.let(viewingsById::get)
            UpNextOnThisDay(
                outing = outing,
                titleName = title.title,
                posterUrl = title.posterUrl,
                yearsAgo = nowYear - outingYear,
                rating = viewing?.rating,
                notes = viewing?.notes,
            )
        }
        UpNextBoard(watching, watchlist, onTheMarquee, freshFromTheLobby, onThisDay)
    }

    /** Marks the next unwatched episode of [titleId] as watched (season/episode order, main
     *  seasons only — the same episode [observeUpNext] shows as next) — the Up Next screen's
     *  "Mark episode watched" action. Deliberately doesn't flip the
     *  title's status: no other episode action in the app does (status is a manual, separate
     *  choice via the status chips), and the locally cached episode rows aren't guaranteed to
     *  match the season's full episodeCount, so "no more unwatched rows" isn't a safe proxy
     *  for "season complete". */
    suspend fun advanceNextEpisode(
        titleId: String,
        watchedAt: String?,
        expectedEpisodeId: String? = null,
        today: String = java.time.LocalDate.now().toString(),
    ): EpisodeWatchReceipt? = outbox.atomically {
        val title = titleDao.getById(titleId) ?: return@atomically null
        if (title.type != MediaType.TV.name || title.status != LibraryStatus.WATCHING.name) return@atomically null
        val seasonNumberById = seasonDao.observeSeasons(titleId).first()
            .filterNot { isSpecialsSeason(it.seasonNumber) }
            .associate { it.id to it.seasonNumber }
        // EpisodeDao orders by seasonId (a UUID), so re-sort by the season's number.
        val episodes = episodeDao.observeEpisodes(titleId).first()
            .filter { it.seasonId in seasonNumberById }
            .sortedWith(compareBy({ seasonNumberById.getValue(it.seasonId) }, { it.episodeNumber }))
        val watchCounts = watchEventDao.observeWatchCounts(titleId).first().associate { it.episodeId to it.watchCount }
        val unwatched = episodes.filter { (watchCounts[it.id] ?: 0) <= 0 }
        val next = unwatched.firstOrNull() ?: return@atomically null
        if (expectedEpisodeId != null && next.id != expectedEpisodeId) return@atomically null
        if (next.airDate?.let { it > today } == true) return@atomically null
        val eventId = logEpisodeWatched(next.id, watchedAt)
        EpisodeWatchReceipt(titleId, next.id, eventId, seasonNumberById.getValue(next.seasonId), next.episodeNumber, unwatched.size == 1)
    }

    /** Explicit caught-up-card action. Logging a finale alone never changes series status. */
    suspend fun markSeriesWatched(titleId: String) = outbox.atomically {
        val title = checkNotNull(titleDao.getById(titleId)) { "This title is no longer in your library" }
        require(title.type == MediaType.TV.name) { "This title is not a series" }
        if (title.status != LibraryStatus.WATCHED.name) updateTitleStatus(titleId, LibraryStatus.WATCHED, Instant.now().toString())
    }

    fun observeTitleDetail(titleId: String): Flow<TitleDetail?> {
        val episodeAggregate = combine(
            seasonDao.observeSeasons(titleId),
            episodeDao.observeEpisodes(titleId),
            watchEventDao.observeAllWatchEvents(),
            ratingDao.observeRatings(titleId),
            reviewDao.observeReviews(titleId),
        ) { seasons, episodes, watches, ratings, reviews ->
            EpisodeAggregate(seasons, episodes, watches, ratings, reviews)
        }

        val creditAggregate = combine(titleCastDao.observeAllCast(), titleCrewDao.observeAllCrew(),
            personCreditsDao.observeSeasonCast(), personCreditsDao.observeEpisodeCrew()) { cast, crew, seasonCast, episodeCrew ->
            TitleCreditAggregate(cast.filter { it.titleId == titleId }, crew.filter { it.titleId == titleId },
                seasonCast.filter { it.titleId == titleId }, episodeCrew.filter { it.titleId == titleId })
        }
        return combine(
            titleDao.observeTitle(titleId),
            combine(episodeAggregate, creditAggregate) { episodes, credits -> episodes to credits },
            viewingDao.observeViewings(titleId),
            cinemaOutingDao.observeOutingsForTitle(titleId),
            theaterInterestDao.observeIsInterested(titleId),
        ) { title, detailSources, viewings, outingRows, isInterested ->
            val (aggregate, credits) = detailSources
            if (title == null) return@combine null

            val watchesByEpisode = aggregate.watchEvents.groupBy { it.episodeId }
            val watchCountByEpisode = watchesByEpisode.mapValues { it.value.size }
            val reviewsByEpisode = aggregate.reviews.groupBy { it.episodeId }
            // Episode cards summarize every rating, matching web avgEpisodeRating.
            val ratingsByEpisode = aggregate.ratings.groupBy { it.episodeId }
            val averageRatingByEpisode = ratingsByEpisode
                .mapValues { (_, ratings) -> ratings.map { it.rating }.average() }

            val episodesBySeason = aggregate.episodes.groupBy { it.seasonId }

            TitleDetail(
                id = title.id,
                tmdbId = title.tmdbId,
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
                tags = title.tags,
                originalLanguage = title.originalLanguage,
                releaseDate = title.releaseDate,
                studios = title.studios,
                collectionName = title.collectionName,
                addedAt = title.addedAt,
                imdbRating = title.imdbRating,
                contentRating = title.contentRating,
                imdbId = title.imdbId,
                rtUrl = title.rtUrl,
                rtScore = title.rtScore,
                metacriticScore = title.metacriticScore,
                customWatchUrl = title.customWatchUrl,
                inHomeCollection = title.inHomeCollection,
                physicalMedia = physicalMediaItems(title.physicalMediaJson),
                awardsCount = title.awardsCount,
                bechdelOutcome = title.bechdelOutcome,
                bechdelScore = title.bechdelScore,
                cast = credits.cast.sortedBy { it.castOrder }.map { PersonCredit(it.tmdbPersonId, it.name, it.characterName) },
                crew = credits.crew.map { PersonCredit(it.tmdbPersonId, it.name, it.job) },
                seasons = aggregate.seasons.map { season ->
                    val seasonEpisodes = episodesBySeason[season.id].orEmpty()
                    // season.episodesWatched (the synced column) is never updated after a
                    // season's initial sync — see observeUpNext's kdoc — so it's only trusted
                    // as a fallback for a season with no locally synced episode rows.
                    val episodesWatched = if (seasonEpisodes.isNotEmpty()) {
                        seasonEpisodes.count { (watchCountByEpisode[it.id] ?: 0) > 0 }
                    } else {
                        season.episodesWatched
                    }
                    SeasonDetail(
                        id = season.id,
                        seasonNumber = season.seasonNumber,
                        episodeCount = season.episodeCount,
                        episodesWatched = episodesWatched,
                        airYear = season.airYear,
                        cast = credits.seasonCast.filter { it.seasonId == season.id }.sortedBy { it.castOrder }
                            .map { PersonCredit(it.tmdbPersonId, it.name, it.characterName) },
                        episodes = seasonEpisodes.map { episode ->
                            EpisodeDetail(
                                id = episode.id,
                                episodeNumber = episode.episodeNumber,
                                episodeName = episode.episodeName,
                                airDate = episode.airDate,
                                runtime = episode.runtime,
                                watchCount = watchCountByEpisode[episode.id] ?: 0,
                                latestRating = ratingsByEpisode[episode.id]?.firstOrNull()?.rating,
                                averageRating = averageRatingByEpisode[episode.id],
                                crew = credits.episodeCrew.filter { it.episodeId == episode.id }
                                    .map { PersonCredit(it.tmdbPersonId, it.name, it.job) },
                                synopsis = episode.synopsis,
                                stillUrl = episode.stillUrl,
                                watchEvents = watchesByEpisode[episode.id].orEmpty()
                                    .sortedWith(compareByDescending<EpisodeWatchEventEntity> { it.watchedAt }.thenBy { it.id })
                                    .map { EpisodeWatch(it.id, it.watchedAt, it.notes) },
                                ratings = ratingsByEpisode[episode.id].orEmpty().map { EpisodeRating(it.id, it.rating, it.ratedAt) },
                                reviews = reviewsByEpisode[episode.id].orEmpty().map { EpisodeReview(it.id, it.reviewText, it.reviewedAt) },
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
                        companions = savedCompanionNames(viewing.companionsJson, viewing.companions),
                        outingId = viewing.outingId,
                    )
                },
                scheduledOuting = outingRows.map { it.toDomain() }
                    .filter { it.status == OutingStatus.SCHEDULED }
                    .minByOrNull { it.showtime },
                interestedInTheaters = isInterested,
            )
        }
    }

    /** Toggles "I want to see this in theaters" (issue #205) for [titleId]. */
    suspend fun setTheaterInterest(titleId: String, interested: Boolean) {
        checkNotNull(moviegoingPreferences) { "Shared preferences are unavailable for this session." }.setInterest(titleId, interested)
    }

    /**
     * On-demand TMDB backfill for a TV title's missing episode synopsis/still image —
     * Android's own equivalent of the web app's `TitleDetailDrawer` backfill effect. Relying
     * solely on data the web app has already fetched and synced down would leave every title
     * *added on Android* permanently thumbnail-less if the user never opens the web app, so
     * this repeats the same on-open fetch-and-persist here instead of assuming web did it
     * first. Symmetric with why the fetched fields are also pushed to the outbox below: neither
     * client should end up depending on the other having visited a title first.
     *
     * Only ever touches seasons that already have local episode rows (matched to the fetched
     * TMDB rows by episode number) and only ever fills a currently-null [EpisodeEntity.synopsis]
     * /[EpisodeEntity.stillUrl] — never overwrites a value either client already wrote. A season
     * with no local episode rows at all (the add-time TMDB season call failed) is left for a
     * later sync to populate first; there is nothing here to attach metadata to yet.
     */
    suspend fun backfillEpisodeMetadata(titleId: String) {
        val title = titleDao.getById(titleId) ?: return
        if (MediaType.valueOf(title.type) != MediaType.TV) return

        val episodesBySeason = episodeDao.observeEpisodes(titleId).first().groupBy { it.seasonId }
        val staleSeasons = seasonDao.observeSeasons(titleId).first().filter { season ->
            episodesBySeason[season.id].orEmpty().let { episodes -> episodes.isNotEmpty() && episodes.any { it.synopsis == null } }
        }
        if (staleSeasons.isEmpty()) return

        val updated = mutableListOf<EpisodeEntity>()
        for (season in staleSeasons) {
            val fetchedByNumber = episodeMetadataFetcher.fetchSeasonEpisodes(title.tmdbId, season.seasonNumber)
                .associateBy { it.episodeNumber }
            if (fetchedByNumber.isEmpty()) continue
            for (episode in episodesBySeason[season.id].orEmpty()) {
                if (episode.synopsis != null && episode.stillUrl != null) continue
                val fetched = fetchedByNumber[episode.episodeNumber] ?: continue
                updated += episode.copy(
                    synopsis = episode.synopsis ?: fetched.synopsis,
                    stillUrl = episode.stillUrl ?: fetched.stillUrl,
                )
            }
        }
        if (updated.isEmpty()) return

        // The TMDB fetches above stay outside the transaction; only the DB writes + enqueues are atomic.
        outbox.atomically {
            episodeDao.upsertAll(updated)
            for (episode in updated) {
                outbox.enqueue(
                    entityType = "episode_metadata",
                    entityId = episode.id,
                    operation = "update",
                    payload = JSONObject().apply {
                        put("id", episode.id)
                        put("synopsis", episode.synopsis ?: JSONObject.NULL)
                        put("stillUrl", episode.stillUrl ?: JSONObject.NULL)
                    },
                )
            }
        }
    }

    /** One episode's cast, looked up by the local title's TMDB id. Display-only and never
     *  persisted; empty for a missing or non-TV title, or on any fetch failure. */
    suspend fun fetchEpisodeCast(titleId: String, seasonNumber: Int, episodeNumber: Int): EpisodeCast {
        val title = titleDao.getById(titleId) ?: return EpisodeCast.EMPTY
        if (MediaType.valueOf(title.type) != MediaType.TV) return EpisodeCast.EMPTY
        return episodeMetadataFetcher.fetchEpisodeCast(title.tmdbId, seasonNumber, episodeNumber)
    }

    /** Rates the outing's auto-logged viewing (the post-show sheet's ★ control) and, matching
     *  [updateTitleRating]'s semantics, bumps the title's own rating too — the web plan's §4.4
     *  "writes viewing.rating and updates title.rating (same semantics as logViewing)". Notes
     *  are a separate action ([updateViewingNotes]): the sheet's "Done" button always fires
     *  regardless of whether the user actually touched the star control, and coupling it to
     *  rating would silently stamp a fake 0★ rating on a still-unrated viewing. */
    suspend fun rateViewing(viewingId: String, titleId: String, rating: Double) {
        outbox.atomically {
            val existing = viewingDao.getById(viewingId) ?: return@atomically
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
    }

    suspend fun updateViewingNotes(viewingId: String, notes: String) {
        outbox.atomically {
            val existing = viewingDao.getById(viewingId) ?: return@atomically
            viewingDao.upsert(existing.copy(notes = notes))
            outbox.enqueue(
                entityType = "viewing",
                entityId = viewingId,
                operation = "update",
                payload = JSONObject().apply { put("id", viewingId); put("notes", notes) },
            )
        }
    }

    /** One form submission may create independent watch, rating and review rows. */
    suspend fun saveEpisodeLog(episodeId: String, draft: EpisodeLogDraft) {
        outbox.atomically {
            requireNotNull(episodeDao.getById(episodeId)) { "Episode is no longer in your library" }
            if (draft.includeWatch) {
                val existing = watchEventDao.observeAllWatchEvents().first().find { it.id == draft.watchEventId }
                require(existing == null || existing.episodeId == episodeId) { "Watch belongs to another episode" }
                watchEventDao.upsertAll(listOf(EpisodeWatchEventEntity(draft.watchEventId, episodeId, draft.watchedAt, draft.watchNotes)))
                outbox.enqueue("episode_watch_event", draft.watchEventId, "upsert", JSONObject().apply {
                    put("id", draft.watchEventId); put("episodeId", episodeId)
                    put("watchedAt", draft.watchedAt ?: JSONObject.NULL)
                    put("notes", draft.watchNotes ?: JSONObject.NULL)
                })
            }
            draft.rating?.let { rating ->
                ratingDao.upsertAll(listOf(EpisodeRatingEntity(draft.ratingId, episodeId, rating, draft.recordedAt)))
                outbox.enqueue("episode_rating", draft.ratingId, "upsert", JSONObject().apply {
                    put("id", draft.ratingId); put("episodeId", episodeId)
                    put("rating", rating); put("ratedAt", draft.recordedAt)
                })
            }
            draft.reviewText?.takeIf { it.isNotBlank() }?.let { review ->
                reviewDao.upsertAll(listOf(EpisodeReviewEntity(draft.reviewId, episodeId, review, draft.recordedAt)))
                outbox.enqueue("episode_review", draft.reviewId, "upsert", JSONObject().apply {
                    put("id", draft.reviewId); put("episodeId", episodeId)
                    put("reviewText", review); put("reviewedAt", draft.recordedAt)
                })
            }
        }
    }

    suspend fun deleteEpisodeWatchEvent(episodeId: String, eventId: String) {
        outbox.atomically {
            val event = watchEventDao.observeAllWatchEvents().first().find { it.id == eventId } ?: return@atomically
            require(event.episodeId == episodeId) { "Watch belongs to another episode" }
            watchEventDao.deleteById(eventId)
            outbox.enqueue("episode_watch_event", eventId, "delete", JSONObject().put("id", eventId))
        }
    }

    /** Logs a watch for [episodeId] — optimistic local write + a queued remote push, per
     *  the idempotency contract in docs/android-sync-contract.md §4.2: the id is generated
     *  here (not left to the server) so a retried push upserts instead of duplicating. */
    suspend fun logEpisodeWatched(episodeId: String, watchedAt: String?): String {
        val id = UUID.randomUUID().toString()
        outbox.atomically {
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
        return id
    }

    /** Records a rating for [episodeId] — same client-generated-id contract as
     *  [logEpisodeWatched]; ratings are an independent log, not tied to a watch event. */
    suspend fun logEpisodeRating(episodeId: String, rating: Double, ratedAt: String) {
        val id = UUID.randomUUID().toString()
        outbox.atomically {
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
    }

    /** Records a review for [episodeId] — same client-generated-id contract as
     *  [logEpisodeWatched]; reviews are an independent log, not tied to a watch event or rating. */
    suspend fun logEpisodeReview(episodeId: String, reviewText: String, reviewedAt: String) {
        val id = UUID.randomUUID().toString()
        outbox.atomically {
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
    }

    /** Import convenience: each admitted event is transactional and has one durable command ID. */
    suspend fun logViewing(titleId: String, date: String?) {
        outbox.atomically {
            val draft = prepareViewingEdit(titleId, null).copy(date = date)
            saveCapturedViewing(titleId, draft, isNew = true, updateTitle = false)
        }
    }

    /** The opening snapshot, including its causal guards, is retained by the saved editor. */
    suspend fun prepareViewingEdit(titleId: String, requestedId: String?): ViewingDraft = outbox.atomically {
        val owner = activeViewingOwner()
        val title = checkNotNull(titleDao.getById(titleId)) { "Title is no longer in your library." }
        val alias = requestedId?.let { checkNotNull(viewingAliases) { "Viewing identity lookup is unavailable." }.byProvisionalId(it) }
        require(alias == null || alias.titleId == titleId) { "Viewing belongs to another title." }
        val existing = requestedId?.let { viewingDao.getById(alias?.canonicalViewingId ?: it) }
        check(requestedId == null || existing != null) { "Viewing was removed. Reopen the history to continue." }
        require(existing == null || existing.titleId == titleId) { "Viewing belongs to another title." }
        val draft = existing?.let { ViewingDraft(it.id, it.date?.take(10), it.rating, it.notes, it.venue, savedCompanionNames(it.companionsJson, it.companions)) }
            ?: ViewingDraft(UUID.randomUUID().toString(), java.time.LocalDate.now().toString(), null, null, null)
        val pending = outbox.pendingEntries()
        val linked = cinemaOutingDao.observeOutingsForTitle(titleId).first().filter { it.completedViewingId == draft.id }.map { it.id }
        val context = viewingOpening(owner, titleId, requestedId, draft,
            existing?.let { captureViewingGuard(it, alias, pending) } ?: ViewingGuard(),
            captureViewingTitleGuard(title, pending, owner), linked, existing?.companionsJson)
        activeViewingOwner()
        draft.copy(openingContext = context)
    }

    /** Every field diff is against the opening snapshot, never rebased from a refreshed Room row. */
    suspend fun saveViewing(titleId: String, draft: ViewingDraft, isNew: Boolean) =
        saveCapturedViewing(titleId, draft, isNew, updateTitle = true)

    private suspend fun saveCapturedViewing(titleId: String, draft: ViewingDraft, isNew: Boolean, updateTitle: Boolean) {
        val owner = activeViewingOwner()
        val captured = checkedViewingOpening(draft, owner, titleId)
        require(captured.isNew == isNew)
        val fields = captured.fields(draft)
        if (fields.length() == 0) return
        val titlePatch = JSONObject().apply {
            if (updateTitle && isNew) put("status", "watched")
            if (updateTitle && fields.has("rating") && draft.rating != null) put("rating", draft.rating)
        }.takeIf { it.length() > 0 }
        val (operation, payload) = captured.payload(if (isNew) "insert" else "update", fields, titlePatch)
        val operationId = capturedViewingOperationId(owner, captured.token, "viewing", payload)
        outbox.atomically {
            activeViewingOwner()
            val title = checkNotNull(titleDao.getById(titleId)) { "Title is no longer in your library." }
            val existing = viewingDao.getById(draft.id)
            require(existing == null || existing.titleId == titleId) { "Viewing belongs to another title." }
            check(isNew || existing != null) { "Viewing was removed. Reopen the history to continue." }
            val admitted = outbox.enqueueCaptured(operationId, "viewing", draft.id, operation, payload)
            if (admitted && !(isNew && existing != null)) {
                val base = existing ?: ViewingEntity(draft.id, titleId, null, null, null, null)
                viewingDao.upsert(base.copy(
                    date = if (fields.has("date")) draft.date else base.date,
                    rating = if (fields.has("rating")) draft.rating else base.rating,
                    notes = if (fields.has("notes")) draft.notes else base.notes,
                    venue = if (fields.has("venue")) draft.venue else base.venue,
                    companions = if (fields.has("companions")) draft.companions else base.companions,
                    companionsJson = if (fields.has("companions")) fields.getJSONArray("companions").toString() else base.companionsJson,
                ))
            }
            if (admitted && titlePatch != null) titleDao.upsertAll(listOf(title.withTitleMetadata(titlePatch)))
            activeViewingOwner()
        }
    }

    /** Server FK removal and the exact viewing delete share one guarded transaction. */
    suspend fun deleteViewing(titleId: String, draft: ViewingDraft) {
        val owner = activeViewingOwner()
        val captured = checkedViewingOpening(draft, owner, titleId)
        require(!captured.isNew)
        val (operation, payload) = captured.payload("delete", JSONObject())
        val id = capturedViewingOperationId(owner, captured.token, "delete", payload)
        outbox.atomically {
            activeViewingOwner()
            val existing = viewingDao.getById(draft.id)
            require(existing == null || existing.titleId == titleId) { "Viewing belongs to another title." }
            if (outbox.enqueueCaptured(id, "viewing", draft.id, operation, payload)) {
                viewingDao.deleteById(draft.id)
                cinemaOutingDao.observeOutingsForTitle(titleId).first().filter { it.completedViewingId == draft.id && it.id in captured.linkedOutings }.forEach {
                    cinemaOutingDao.upsert(it.copy(completedViewingId = null))
                }
            }
            activeViewingOwner()
        }
    }

    private fun activeViewingOwner(): String {
        check(isCurrentOwner()) { "This sign-in has ended. Reopen the history in your current account." }
        return checkNotNull(mutationOwnerId) { "Viewing edits require an account runtime." }
    }

    /** Future title edits use exact server revisions/receipt predecessors; legacy timestamps are not CAS inputs. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun updateTitleStatus(titleId: String, status: LibraryStatus, updatedAt: String) =
        updateTitleMetadata(titleId, JSONObject().put("status", status.name.lowercase()))

    /** Sets [titleId]'s own rating (distinct from per-episode ratings) — same in-place
     *  update contract as [updateTitleStatus]. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun updateTitleRating(titleId: String, rating: Double, updatedAt: String) =
        updateTitleMetadata(titleId, JSONObject().put("rating", rating))

    suspend fun prepareTitleSources(titleId: String): String = titleSourcesEditor().capture(titleId)

    suspend fun saveTitleSources(titleId: String, opening: String, desired: TitleSourcesValues) =
        titleSourcesEditor().save(titleId, opening, desired)

    private fun titleSourcesEditor() = TitleSourcesEditing(titleDao, outbox,
        checkNotNull(mutationOwnerId) { "Title edits require an account runtime." }, isCurrentOwner)

    suspend fun updateTitleTags(titleId: String, tags: List<String>) =
        updateTitleMetadata(titleId, JSONObject().put("tags", org.json.JSONArray(tags)))

    private suspend fun updateTitleMetadata(titleId: String, patch: JSONObject) {
        val ownerId = checkNotNull(mutationOwnerId) { "Title edits require an account runtime." }
        outbox.atomically {
            val previous = checkNotNull(titleDao.getById(titleId)) { "This title was removed." }
            val next = previous.withTitleMetadata(patch)
            if (next == previous) return@atomically
            titleDao.upsertAll(listOf(next))
            outbox.enqueueTitleMetadata(previous, patch, ownerId)
        }
    }
}

/** Match web episodesWatchedInSeason: coarse progress applies independently to each
 * season without episode rows; repeats count once and Specials never enter series totals. */
internal fun watchedEpisodeCount(
    seasons: List<SeasonEntity>,
    episodes: List<EpisodeEntity>,
    watchedEpisodeIds: Set<String>,
): Int {
    val bySeason = episodes.groupBy { it.seasonId }
    return seasons.filterNot { isSpecialsSeason(it.seasonNumber) }.sumOf { season ->
        val rows = bySeason[season.id].orEmpty()
        if (rows.isEmpty()) season.episodesWatched else rows.count { it.id in watchedEpisodeIds }
    }
}
