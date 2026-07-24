package work.kumarfamilynet.cinemarchive.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingDao
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchEventEntity
import work.kumarfamilynet.cinemarchive.core.database.SeasonDao
import work.kumarfamilynet.cinemarchive.core.database.SeasonEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCastDao
import work.kumarfamilynet.cinemarchive.core.database.TitleCastEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewDao
import work.kumarfamilynet.cinemarchive.core.database.TitleCrewEntity
import work.kumarfamilynet.cinemarchive.core.database.TitleDao
import work.kumarfamilynet.cinemarchive.core.database.TitleEntity
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.model.LedgerBoard
import work.kumarfamilynet.cinemarchive.core.model.LedgerCategoryCount
import work.kumarfamilynet.cinemarchive.core.model.LedgerEncoreEntry
import work.kumarfamilynet.cinemarchive.core.model.LedgerMonthlyCount
import work.kumarfamilynet.cinemarchive.core.model.LedgerMoviegoingStats
import work.kumarfamilynet.cinemarchive.core.model.LedgerPremiereRevivalBucket
import work.kumarfamilynet.cinemarchive.core.model.LedgerProgressEntry
import work.kumarfamilynet.cinemarchive.core.model.LedgerQuarterRating
import work.kumarfamilynet.cinemarchive.core.model.LedgerSettingKey
import work.kumarfamilynet.cinemarchive.core.model.LedgerStats
import work.kumarfamilynet.cinemarchive.core.model.LedgerStreaks
import work.kumarfamilynet.cinemarchive.core.model.LedgerVerdictEntry
import work.kumarfamilynet.cinemarchive.core.model.LedgerWatchlistEntry
import work.kumarfamilynet.cinemarchive.core.model.LedgerWeekdayCount
import work.kumarfamilynet.cinemarchive.core.model.LedgerWeeklyActivity
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetConfig
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.effectiveLedgerSettings
import work.kumarfamilynet.cinemarchive.core.model.honorsLedgerSetting

private val ISO_LANGUAGE_NAMES = mapOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
    "it" to "Italian", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese",
    "hi" to "Hindi", "pt" to "Portuguese", "ru" to "Russian", "sv" to "Swedish",
    "da" to "Danish", "no" to "Norwegian", "fi" to "Finnish", "nl" to "Dutch",
)

/** All bundle of a title's DAO reads the widgets below need together — kept private since
 *  it's purely an intermediate combine() shape, not part of the repository's public API. */
private data class LedgerSources(
    val titles: List<TitleEntity>,
    val viewings: List<ViewingEntity>,
    val cast: List<TitleCastEntity>,
    val crew: List<TitleCrewEntity>,
    val outings: List<CinemaOutingEntity>,
    val watchedAtDates: List<String?>,
    val seasons: List<SeasonEntity>,
    val episodes: List<EpisodeEntity>,
    val watchEvents: List<EpisodeWatchEventEntity>,
)

private fun parseLocalDate(date: String?): LocalDate? =
    date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

/**
 * Read-only Ledger rollup — see [LedgerStats]/[LedgerBoard] and
 * docs/android-contracts/ledger.md. Pure client-side aggregation over already-synced Room
 * data, same as the web app's ledgerStats.ts/ledgerDerive.ts/ledgerPanels.ts; no network
 * call, no write path. Covers all 20 widgets as a fixed-order board — the customizable
 * layout is a separate concern (see [LedgerBoard]'s kdoc).
 */
class LedgerRepository(
    private val titleDao: TitleDao,
    private val viewingDao: ViewingDao,
    private val titleCastDao: TitleCastDao,
    private val titleCrewDao: TitleCrewDao,
    private val cinemaOutingDao: CinemaOutingDao,
    private val watchEventDao: EpisodeWatchEventDao,
    private val seasonDao: SeasonDao,
    private val episodeDao: EpisodeDao,
) {
    fun observeLedgerStats(): Flow<LedgerStats> = combine(
        titleDao.observeAllTitles(),
        viewingDao.observeTotalViewingCount(),
    ) { titles, viewingCount ->
        val ratings = titles.mapNotNull { it.rating }
        val watchedMovieMinutes = titles
            .filter { it.type == MediaType.MOVIE.name && it.status == LibraryStatus.WATCHED.name }
            .sumOf { it.runtime ?: 0 }

        LedgerStats(
            totalMovies = titles.count { it.type == MediaType.MOVIE.name },
            totalSeries = titles.count { it.type == MediaType.TV.name },
            totalViewings = viewingCount,
            averageRating = ratings.takeIf { it.isNotEmpty() }?.average(),
            totalWatchedMovieMinutes = watchedMovieMinutes,
        )
    }

    /** Split into two combine() groups since kotlinx.coroutines only has typed `combine`
     *  overloads up to 5 flows. */
    private fun observeLedgerSources(): Flow<LedgerSources> {
        val titleGroup = combine(
            titleDao.observeAllTitles(),
            viewingDao.observeAllViewings(),
            titleCastDao.observeAllCast(),
            titleCrewDao.observeAllCrew(),
            ::TitleGroup,
        )
        val historyGroup = combine(
            cinemaOutingDao.observeAllOutings(),
            watchEventDao.observeAllWatchEvents(),
            seasonDao.observeAllSeasons(),
            episodeDao.observeAllEpisodes(),
            ::HistoryGroup,
        )
        return combine(titleGroup, historyGroup) { titles, history ->
            LedgerSources(
                titles.titles,
                titles.viewings,
                titles.cast,
                titles.crew,
                history.outings,
                history.watchEvents.map { it.watchedAt },
                history.seasons,
                history.episodes,
                history.watchEvents,
            )
        }
    }

    /** All 20 widgets, unfiltered (every field's default `scope`/`timeRange` — i.e. "all") —
     *  see [LedgerBoard] field kdocs for which widget each field backs. [observeLedgerBoards]
     *  is the settings-aware counterpart a real board render should use. */
    fun observeLedgerBoard(): Flow<LedgerBoard> = observeLedgerSources().map { s -> buildBoard(s) }

    /** One [LedgerBoard] per widget instance in [layoutFlow], keyed by [LedgerWidgetConfig.id]
     *  — each built from [LedgerSources] filtered by that *specific* widget's own effective
     *  `scope`/`timeRange` (docs/superpowers/plans/2026-07-23-android-ledger-parity.md Phase D,
     *  mirroring ledgerPanels.ts's `scopedTitles` filter-then-aggregate pattern). A panel that
     *  doesn't honor `scope`/`timeRange` per [PANEL_SETTING_KEYS] always resolves to
     *  "all"/unfiltered regardless of what a widget instance's stored settings say, matching
     *  ledger.md §1's "silently ignore an inapplicable key" rule. Widgets that resolve to the
     *  same effective (scope, timeRange) pair — the common case, since most widgets carry no
     *  override — share one [buildBoard] call rather than recomputing per widget. */
    fun observeLedgerBoards(layoutFlow: Flow<List<LedgerWidgetConfig>>): Flow<Map<String, LedgerBoard>> =
        combine(observeLedgerSources(), layoutFlow) { sources, layout -> buildBoardsForLayout(sources, layout) }

    private fun buildBoardsForLayout(sources: LedgerSources, layout: List<LedgerWidgetConfig>): Map<String, LedgerBoard> {
        val cache = mutableMapOf<Pair<String, LocalDate?>, LedgerBoard>()
        return layout.associate { widget ->
            val effective = effectiveLedgerSettings(widget.panel, widget.settings)
            val scope = if (widget.panel.honorsLedgerSetting(LedgerSettingKey.SCOPE)) effective.scope else "all"
            val rangeStart = if (widget.panel.honorsLedgerSetting(LedgerSettingKey.TIME_RANGE)) {
                timeRangeStart(effective.timeRange)
            } else {
                null
            }
            val board = cache.getOrPut(scope to rangeStart) { buildBoard(sources.scopedTo(scope, rangeStart)) }
            widget.id to board
        }
    }

    /** Inclusive lower bound for a time range, or null for "all"/unrestricted — mirrors
     *  `timeRangeStart()` in ledgerDerive.ts. */
    private fun timeRangeStart(timeRange: String): LocalDate? = when (timeRange) {
        "ytd" -> LocalDate.now().withDayOfYear(1)
        "12mo" -> LocalDate.now().minusYears(1)
        "5y" -> LocalDate.now().minusYears(5)
        else -> null // "all"
    }

    /** Filters to titles of the given [scope] (cascading to every joined collection by
     *  `titleId`/`episodeId` membership) and, independently, to date-bearing events
     *  ([ViewingEntity.date]/[EpisodeWatchEventEntity.watchedAt]) on/after [rangeStart] — an
     *  undated event only passes when [rangeStart] is null, mirroring `dateInRange()`'s "an
     *  event with no true date can't be shown to be in range" rule. Scope never excludes a
     *  title by date (only by type); time range never excludes a title (only its dated
     *  events) — the two axes are independent, matching `scopeTitles`/`dateInRange` staying
     *  separate helpers on the web side. */
    private fun LedgerSources.scopedTo(scope: String, rangeStart: LocalDate?): LedgerSources {
        if (scope == "all" && rangeStart == null) return this
        val scopedTitleList = when (scope) {
            "movies" -> titles.filter { it.type == MediaType.MOVIE.name }
            "tv" -> titles.filter { it.type == MediaType.TV.name }
            else -> titles
        }
        val scopedIds = scopedTitleList.map { it.id }.toSet()
        fun inRange(date: String?): Boolean {
            if (rangeStart == null) return true
            val parsed = parseLocalDate(date) ?: return false
            return !parsed.isBefore(rangeStart)
        }
        val scopedEpisodes = episodes.filter { it.titleId in scopedIds }
        val scopedEpisodeIds = scopedEpisodes.map { it.id }.toSet()
        val scopedWatchEvents = watchEvents.filter { it.episodeId in scopedEpisodeIds && inRange(it.watchedAt) }
        return LedgerSources(
            titles = scopedTitleList,
            viewings = viewings.filter { it.titleId in scopedIds && inRange(it.date) },
            cast = cast.filter { it.titleId in scopedIds },
            crew = crew.filter { it.titleId in scopedIds },
            outings = outings.filter { it.titleId in scopedIds },
            watchedAtDates = scopedWatchEvents.map { it.watchedAt },
            seasons = seasons.filter { it.titleId in scopedIds },
            episodes = scopedEpisodes,
            watchEvents = scopedWatchEvents,
        )
    }

    private data class TitleGroup(
        val titles: List<TitleEntity>,
        val viewings: List<ViewingEntity>,
        val cast: List<TitleCastEntity>,
        val crew: List<TitleCrewEntity>,
    )

    private data class HistoryGroup(
        val outings: List<CinemaOutingEntity>,
        val watchEvents: List<EpisodeWatchEventEntity>,
        val seasons: List<SeasonEntity>,
        val episodes: List<EpisodeEntity>,
    )

    private fun buildBoard(s: LedgerSources): LedgerBoard {
        val titles = s.titles
        val viewings = s.viewings
        val titleById = titles.associateBy { it.id }
        val movies = titles.filter { it.type == MediaType.MOVIE.name }
        val series = titles.filter { it.type == MediaType.TV.name }

        val runtimeBuckets = listOf(
            "< 90 min" to movies.count { (it.runtime ?: 0) < 90 },
            "90–120 min" to movies.count { (it.runtime ?: 0) in 90..119 },
            "120–150 min" to movies.count { (it.runtime ?: 0) in 120..149 },
            "150+ min" to movies.count { (it.runtime ?: 0) >= 150 },
        ).map { (label, count) -> LedgerCategoryCount(label, count) }

        val networks = tally(series.mapNotNull { it.network })
        val decades = titles.mapNotNull { it.year }.map { year -> "${(year / 10) * 10}s" }
            .groupingBy { it }.eachCount().entries.sortedBy { it.key }
            .map { LedgerCategoryCount(it.key, it.value) }

        val watchlisted = titles.filter { it.status == LibraryStatus.WATCHLIST.name }
        val watchlist = watchlisted.map {
            LedgerWatchlistEntry(titleId = it.id, title = it.title, year = it.year, runtimeMinutes = it.runtime)
        }
        val watchlistMovieMinutesOwed = watchlisted
            .filter { it.type == MediaType.MOVIE.name }
            .sumOf { it.runtime ?: 0 }

        return LedgerBoard(
            runtimeBuckets = runtimeBuckets,
            networks = networks,
            decades = decades,
            watchlist = watchlist,
            watchlistMovieMinutesOwed = watchlistMovieMinutesOwed,
            weeklyActivity = weeklyActivity(viewings),
            encores = encores(viewings, titleById),
            monthlyRun = monthlyRun(viewings),
            ratingBuckets = ratingBuckets(titles),
            genres = tally(titles.flatMap { it.genres }),
            auteurs = tally(s.crew.filter { it.job == "Director" }.map { it.name }),
            ensemble = tally(s.cast.filter { it.castOrder < 5 }.map { it.name }),
            verdicts = verdicts(titles),
            languages = tally(titles.mapNotNull { it.originalLanguage?.let { code -> displayLanguage(code) } }),
            weekdays = weekdays(viewings),
            streaks = streaks(viewings, s.watchedAtDates),
            trajectory = trajectory(titles, viewings),
            revivals = revivals(viewings),
            timewarp = timewarp(viewings, titleById),
            stillRolling = stillRolling(titles, s.seasons, s.episodes, s.watchEvents),
            moviegoing = moviegoing(viewings, s.outings),
        )
    }

    private fun tally(values: List<String>): List<LedgerCategoryCount> =
        values.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }
            .map { LedgerCategoryCount(it.key, it.value) }

    /** Time in the Dark: week-granularity heatmap, 52 buckets ending this week (oldest
     *  first) — see [work.kumarfamilynet.cinemarchive.core.designsystem.HeatmapRow]'s kdoc
     *  for why Android buckets by week rather than the web app's per-day grid. */
    private fun weeklyActivity(viewings: List<ViewingEntity>): List<LedgerWeeklyActivity> {
        val today = LocalDate.now()
        val dates = viewings.mapNotNull { parseLocalDate(it.date) }
        return (51 downTo 0).map { weeksAgo ->
            val weekStart = today.minusWeeks(weeksAgo.toLong()).with(DayOfWeek.MONDAY)
            val weekEnd = weekStart.plusDays(6)
            val count = dates.count { it in weekStart..weekEnd }
            LedgerWeeklyActivity(weekStart.format(DateTimeFormatter.ofPattern("MMM d")), count)
        }
    }

    /** Encore Performances: titles with >=2 viewings (ledger.md §2). */
    private fun encores(viewings: List<ViewingEntity>, titleById: Map<String, TitleEntity>): List<LedgerEncoreEntry> =
        viewings.groupingBy { it.titleId }.eachCount().entries
            .filter { it.value >= 2 }
            .mapNotNull { (titleId, count) ->
                titleById[titleId]?.let { LedgerEncoreEntry(titleId, it.title, it.year, count) }
            }
            .sortedByDescending { it.viewingCount }

    /** The Run: monthly trend, gap-filled, default window 12mo (ledger.md §2). */
    private fun monthlyRun(viewings: List<ViewingEntity>): List<LedgerMonthlyCount> {
        val today = LocalDate.now()
        val dates = viewings.mapNotNull { parseLocalDate(it.date) }
        return (11 downTo 0).map { monthsAgo ->
            val month = today.minusMonths(monthsAgo.toLong())
            val count = dates.count { it.year == month.year && it.monthValue == month.monthValue }
            LedgerMonthlyCount(month.format(DateTimeFormatter.ofPattern("MMM yyyy")), count)
        }
    }

    /** Critical Record: 0.5-star buckets, 5.0 descending to 0.5 (ledger.md §2). */
    private fun ratingBuckets(titles: List<TitleEntity>): List<LedgerCategoryCount> {
        val steps = generateSequence(5.0) { (it - 0.5).takeIf { next -> next >= 0.5 } }.toList()
        return steps.map { value ->
            val count = titles.count { title -> title.rating?.let { Math.abs(it - value) < 0.01 } ?: false }
            LedgerCategoryCount("★%.1f".format(value), count)
        }
    }

    /** Second Opinions: our rating (x2, 0-5 -> 0-10) vs IMDb, sorted by |delta| descending,
     *  only titles with both fields present (ledger.md §2). */
    private fun verdicts(titles: List<TitleEntity>): List<LedgerVerdictEntry> =
        titles.mapNotNull { title ->
            val rating = title.rating ?: return@mapNotNull null
            val imdbRating = title.imdbRating ?: return@mapNotNull null
            val ours = rating * 2
            LedgerVerdictEntry(title.id, title.title, ours, imdbRating, ours - imdbRating)
        }.sortedByDescending { Math.abs(it.delta) }

    private fun displayLanguage(code: String): String = ISO_LANGUAGE_NAMES[code] ?: code

    /** Screening Nights: local day-of-week from local date components (ledger.md §2). */
    private fun weekdays(viewings: List<ViewingEntity>): List<LedgerWeekdayCount> {
        val counts = viewings.mapNotNull { parseLocalDate(it.date) }
            .groupingBy { it.dayOfWeek }.eachCount()
        return DayOfWeek.entries.map { day ->
            LedgerWeekdayCount(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()), counts[day] ?: 0)
        }
    }

    /** The Marathon: streak detection folds viewings AND episode watch events together
     *  (ledger.md §1) — the one date-bucketing panel that does. */
    private fun streaks(viewings: List<ViewingEntity>, watchedAtDates: List<String?>): LedgerStreaks {
        val allDates = (viewings.map { it.date } + watchedAtDates)
            .mapNotNull { parseLocalDate(it) }
            .toSortedSet()
        if (allDates.isEmpty()) return LedgerStreaks(0, 0, emptyList())

        var longest = 1
        var run = 1
        val ordered = allDates.toList()
        for (i in 1 until ordered.size) {
            run = if (ChronoUnit.DAYS.between(ordered[i - 1], ordered[i]) == 1L) run + 1 else 1
            longest = maxOf(longest, run)
        }

        val today = LocalDate.now()
        var current = 0
        var cursor = if (allDates.contains(today)) today else today.minusDays(1)
        while (allDates.contains(cursor)) {
            current++
            cursor = cursor.minusDays(1)
        }

        return LedgerStreaks(
            currentStreakDays = current,
            longestStreakDays = longest,
            recentActiveDates = ordered.takeLast(10).map { it.toString() },
        )
    }

    /** Shifting Standards: a title lands in the quarter of its first *dated* viewing,
     *  falling back to addedAt (ledger.md §2). */
    private fun trajectory(titles: List<TitleEntity>, viewings: List<ViewingEntity>): List<LedgerQuarterRating> {
        val firstDatedViewingByTitle = viewings
            .mapNotNull { v -> parseLocalDate(v.date)?.let { v.titleId to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, dates) -> dates.min() }

        val quarterByRatedTitle = titles.mapNotNull { title ->
            val rating = title.rating ?: return@mapNotNull null
            val landingDate = firstDatedViewingByTitle[title.id] ?: parseLocalDate(title.addedAt)
            landingDate?.let { Triple(title.id, rating, it) }
        }

        return quarterByRatedTitle
            .groupBy { (_, _, date) -> "${date.year} Q${(date.monthValue - 1) / 3 + 1}" }
            .entries
            .sortedBy { it.key }
            .map { (label, entries) ->
                val ratings = entries.map { (_, rating, _) -> rating }
                LedgerQuarterRating(label, ratings.average(), ratings.size)
            }
    }

    /** Premieres & Revivals: per title, the earliest viewing (undated sorts first, per
     *  ledger.md §2) is the premiere; every later viewing is a revival. Only dated viewings
     *  render into a month bucket. */
    private fun revivals(viewings: List<ViewingEntity>): List<LedgerPremiereRevivalBucket> {
        val premiereMonths = mutableListOf<String>()
        val revivalMonths = mutableListOf<String>()

        viewings.groupBy { it.titleId }.forEach { (_, titleViewings) ->
            val ordered = titleViewings.sortedWith(
                compareBy(nullsFirst()) { parseLocalDate(it.date) },
            )
            ordered.forEachIndexed { index, viewing ->
                val date = parseLocalDate(viewing.date) ?: return@forEachIndexed
                val monthLabel = date.format(DateTimeFormatter.ofPattern("MMM yyyy"))
                if (index == 0) premiereMonths += monthLabel else revivalMonths += monthLabel
            }
        }

        val months = (premiereMonths + revivalMonths).distinct().sortedBy {
            java.time.YearMonth.parse(it, DateTimeFormatter.ofPattern("MMM yyyy"))
        }
        return months.map { month ->
            LedgerPremiereRevivalBucket(
                monthLabel = month,
                premieres = premiereMonths.count { it == month },
                revivals = revivalMonths.count { it == month },
            )
        }
    }

    /** The Revival House: age = viewing year - release year, floored at 0, 5 fixed buckets
     *  (ledger.md §2). Exact bucket cutoffs aren't pinned by the contract doc — this uses a
     *  reasonable Android-chosen quintent scheme. */
    private fun timewarp(viewings: List<ViewingEntity>, titleById: Map<String, TitleEntity>): List<LedgerCategoryCount> {
        val ages = viewings.mapNotNull { v ->
            val year = parseLocalDate(v.date)?.year ?: return@mapNotNull null
            val releaseYear = titleById[v.titleId]?.year ?: return@mapNotNull null
            (year - releaseYear).coerceAtLeast(0)
        }
        val buckets = listOf(
            "Same year" to (0..0),
            "1–2 yrs" to (1..2),
            "3–5 yrs" to (3..5),
            "6–15 yrs" to (6..15),
            "16+ yrs" to (16..Int.MAX_VALUE),
        )
        return buckets.map { (label, range) -> LedgerCategoryCount(label, ages.count { it in range }) }
    }

    /** Still Rolling: TV titles that are `WATCHING`, or have partial progress even if
     *  status says otherwise (ledger.md §2). Watched counts come from real per-episode watch
     *  events, not `seasons.episodesWatched` — see LibraryRepository.observeUpNext's kdoc for
     *  why that synced column can't be trusted once a title is tracked episode-by-episode. */
    private fun stillRolling(
        titles: List<TitleEntity>,
        seasons: List<SeasonEntity>,
        episodes: List<EpisodeEntity>,
        watchEvents: List<EpisodeWatchEventEntity>,
    ): List<LedgerProgressEntry> {
        val seasonsByTitle = seasons.groupBy { it.titleId }
        val episodesByTitle = episodes.groupBy { it.titleId }
        val watchedEpisodeIds = watchEvents.map { it.episodeId }.toSet()
        return titles.filter { it.type == MediaType.TV.name }.mapNotNull { title ->
            val titleSeasons = seasonsByTitle[title.id] ?: emptyList()
            val titleEpisodes = episodesByTitle[title.id].orEmpty()
            val watched = if (titleEpisodes.isNotEmpty()) {
                titleEpisodes.count { it.id in watchedEpisodeIds }
            } else {
                titleSeasons.sumOf { it.episodesWatched }
            }
            val total = titleSeasons.sumOf { it.episodeCount }
            val isPartial = watched > 0 && watched < total
            if (title.status != LibraryStatus.WATCHING.name && !isPartial) return@mapNotNull null
            LedgerProgressEntry(title.id, title.title, watched, total)
        }
    }

    /** At the Movies: viewings with a non-null venue are cinema trips (venue is filled for
     *  a trip whether logged manually or via a completed outing — see schema.sql's
     *  `viewings.venue`/`companions`/`outing_id` comment). [totalSpend]/formats are the
     *  owner-private half, joined via `outingId` (ledger.md §3). */
    private fun moviegoing(viewings: List<ViewingEntity>, outings: List<CinemaOutingEntity>): LedgerMoviegoingStats {
        val trips = viewings.filter { it.venue != null }
        val outingById = outings.associateBy { it.id }
        val joinedOutings = trips.mapNotNull { it.outingId?.let { id -> outingById[id] } }

        return LedgerMoviegoingStats(
            tripCount = trips.size,
            byYear = tally(trips.mapNotNull { parseLocalDate(it.date)?.year?.toString() }),
            venues = tally(trips.mapNotNull { it.venue }),
            companions = tally(trips.flatMap { it.companions }),
            formats = tally(joinedOutings.mapNotNull { it.format }),
            totalSpend = joinedOutings.mapNotNull { it.ticketPrice }.takeIf { it.isNotEmpty() }?.sum(),
        )
    }
}
