package work.kumarfamilynet.cinemarchive.data

import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

/** Fixtures encode the web episodeUtils/upNext/ledgerDerive rules, including history
 * which is invisible in the selected range but still determines a viewing's meaning. */
class CalculationParityTest {
    private fun title(id: String, type: String = "TV", status: String = "WATCHING", added: String = "2024-01-01") = TitleEntity(
        id = id, tmdbId = id.hashCode(), type = type, title = id, year = 2000,
        director = null, genres = emptyList(), posterUrl = null, backdropUrl = null,
        synopsis = null, runtime = null, network = null, status = status, rating = null,
        notes = null, addedAt = added, updatedAt = added,
    )

    private fun season(id: String, title: String, number: Int, total: Int, watched: Int = 0) =
        SeasonEntity(id, title, number, total, watched, null)
    private fun episode(id: String, title: String, season: String, number: Int) =
        EpisodeEntity(id, title, season, number, null, null, null)
    private fun viewing(id: String, title: String, date: String?) =
        ViewingEntity(id, title, date, null, null, null)

    @Test
    fun `mixed coarse and detailed seasons agree in detail up next and ledger`() = runTest {
        val fixture = CalculationFixture(
            titles = listOf(title("show")),
            seasons = listOf(season("coarse", "show", 1, 8, 8), season("detailed", "show", 2, 10, 9), season("specials", "show", 0, 20, 20)),
            episodes = listOf(episode("seen", "show", "detailed", 1), episode("next", "show", "detailed", 2)),
            events = listOf(EpisodeWatchEventEntity("first", "seen", "2025-01-01"), EpisodeWatchEventEntity("repeat", "seen", "2025-02-01")),
        )
        val card = fixture.library().observeUpNext().first().watching.single()
        assertEquals(9, card.episodesWatched)
        assertEquals(18, card.episodesTotal)
        assertEquals(2, card.nextSeasonNumber)
        assertEquals(2, card.nextEpisodeNumber)
        val progress = fixture.ledger().observeLedgerBoard().first().stillRolling.single()
        assertEquals(9, progress.episodesWatched)
        assertEquals(18, progress.episodeCount)
        val detail = fixture.library().observeTitleDetail("show").first()!!
        assertEquals(listOf(8, 1, 20), detail.seasons.map { it.episodesWatched })
    }

    @Test
    fun `up next excludes completed and coarse only shows and orders by latest watch including specials`() = runTest {
        val fixture = CalculationFixture(
            titles = listOf(title("A"), title("Z"), title("complete"), title("coarse"), title("new", added = "2026-02-01")),
            seasons = listOf(season("a", "A", 1, 1), season("z", "Z", 1, 1), season("sp", "Z", 0, 1), season("c", "complete", 1, 1), season("co", "coarse", 1, 2), season("n", "new", 1, 1)),
            episodes = listOf(episode("a1", "A", "a", 1), episode("z1", "Z", "z", 1), episode("special", "Z", "sp", 1), episode("c1", "complete", "c", 1), episode("n1", "new", "n", 1)),
            events = listOf(EpisodeWatchEventEntity("s", "special", "2026-03-01"), EpisodeWatchEventEntity("c", "c1", "2026-04-01")),
        )
        assertEquals(listOf("Z", "new", "A"), fixture.library().observeUpNext().first().watching.map { it.id })
    }

    @Test
    fun `watchlist places newest available titles before future releases in release order`() = runTest {
        val fixture = CalculationFixture(titles = listOf(
            title("future-late", status = "WATCHLIST").copy(releaseDate = "2999-12-01"),
            title("old", status = "WATCHLIST", added = "2020-01-01").copy(releaseDate = "2020-01-01"),
            title("future-soon", status = "WATCHLIST").copy(releaseDate = "2999-01-01"),
            title("new", status = "WATCHLIST", added = "2025-01-01"),
        ))
        assertEquals(listOf("new", "old", "future-soon", "future-late"), fixture.library().observeUpNext().first().watchlist.map { it.id })
    }

    @Test
    fun `episode average preserves newest rating separately and absent ratings stay null`() = runTest {
        val fixture = CalculationFixture(
            titles = listOf(title("show")), seasons = listOf(season("s", "show", 1, 2)),
            episodes = listOf(episode("rated", "show", "s", 1), episode("unrated", "show", "s", 2)),
            ratings = listOf(EpisodeRatingEntity("new", "rated", 5.0, "2026-02-01"), EpisodeRatingEntity("old", "rated", 1.0, "2026-01-01")),
        )
        val episodes = fixture.library().observeTitleDetail("show").first()!!.seasons.single().episodes
        assertEquals(5.0, episodes[0].latestRating!!, 0.0)
        assertEquals(3.0, episodes[0].averageRating!!, 0.0)
        assertNull(episodes[1].averageRating)
    }

    @Test
    fun `range filtering preserves premiere history and first seen anchors including undated watches`() = runTest {
        val today = LocalDate.now()
        val old = today.minusYears(2).toString()
        val fixture = CalculationFixture(
            titles = listOf(title("dated").copy(rating = 4.0), title("undated").copy(rating = 3.0, addedAt = old)),
            viewings = listOf(viewing("d-first", "dated", old), viewing("d-again", "dated", today.toString()), viewing("u-first", "undated", null), viewing("u-again", "undated", today.toString())),
        )
        val revivals = fixture.panel(LedgerWidgetId.REVIVALS, "ytd").revivals.single()
        assertEquals(0, revivals.premieres)
        assertEquals(2, revivals.revivals)
        val trajectory = fixture.panel(LedgerWidgetId.TRAJECTORY, "ytd").trajectory.single()
        // Undated viewing does not supply a first-seen date; its first dated watch is today.
        assertEquals(1, trajectory.titleCount)
        assertEquals(3.0, trajectory.averageRating, 0.0)
    }

    @Test
    fun `monthly windows include the starting month and all time history with a ten year cap`() = runTest {
        val today = LocalDate.now()
        val fixture = CalculationFixture(titles = listOf(title("movie", "MOVIE")), viewings = listOf(
            viewing("ancient", "movie", today.minusYears(20).toString()),
            viewing("old", "movie", today.minusYears(2).toString()),
            viewing("boundary", "movie", today.minusYears(1).toString()),
            viewing("recent", "movie", today.toString()),
        ))
        assertEquals(121, fixture.panel(LedgerWidgetId.RUN, "all").monthlyRun.size)
        assertEquals(61, fixture.panel(LedgerWidgetId.RUN, "5y").monthlyRun.size)
        val year = fixture.panel(LedgerWidgetId.RUN, "12mo").monthlyRun
        assertEquals(13, year.size)
        assertEquals(today.minusYears(1).format(DateTimeFormatter.ofPattern("MMM yyyy")), year.first().monthLabel)
        assertEquals(2, year.sumOf { it.count })
        assertEquals(12, fixture.panel(LedgerWidgetId.RUN, "ytd").monthlyRun.size)
    }

    @Test
    fun `runtime excludes unknowns and actor grouping keeps separate people with identical names`() = runTest {
        val fixture = CalculationFixture(
            titles = listOf(title("unknown", "MOVIE"), title("zero", "MOVIE").copy(runtime = 0), title("short", "MOVIE").copy(runtime = 89)),
            cast = listOf(TitleCastEntity("a", "unknown", 1, "Same Name", null, 0), TitleCastEntity("b", "zero", 2, "Same Name", null, 0), TitleCastEntity("c", "short", 1, "Same Name", null, 0)),
        )
        val board = fixture.ledger().observeLedgerBoard().first()
        assertEquals(1, board.runtimeBuckets.sumOf { it.count })
        assertEquals(listOf(2, 1), board.ensemble.map { it.count })
    }

    @Test
    fun `revival house uses web age boundaries`() = runTest {
        val ages = listOf(0, 1, 2, 5, 6, 20, 21, 50, 51)
        val fixture = CalculationFixture(
            titles = ages.map { title("age-$it", "MOVIE").copy(year = 2026 - it) },
            viewings = ages.map { viewing("v-$it", "age-$it", "2026-10-08") },
        )
        assertEquals(listOf(2, 2, 2, 2, 1), fixture.ledger().observeLedgerBoard().first().timewarp.map { it.count })
    }
}

/** Only declared observation methods are implemented; unexpected repository reads fail. */
private inline fun <reified T> calculationDao(vararg observations: Pair<String, Any?>): T {
    val values = observations.toMap()
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        check(method.name in values) { "Unexpected ${T::class.java.simpleName}.${method.name}" }
        flowOf(values[method.name])
    } as T
}

private data class CalculationFixture(
    val titles: List<TitleEntity>,
    val seasons: List<SeasonEntity> = emptyList(),
    val episodes: List<EpisodeEntity> = emptyList(),
    val events: List<EpisodeWatchEventEntity> = emptyList(),
    val ratings: List<EpisodeRatingEntity> = emptyList(),
    val viewings: List<ViewingEntity> = emptyList(),
    val cast: List<TitleCastEntity> = emptyList(),
) {
    private val titleDao get() = calculationDao<TitleDao>("observeAllTitles" to titles, "observeTitle" to titles.firstOrNull())
    private val seasonDao get() = calculationDao<SeasonDao>("observeAllSeasons" to seasons, "observeSeasons" to seasons)
    private val episodeDao get() = calculationDao<EpisodeDao>("observeAllEpisodes" to episodes, "observeEpisodes" to episodes)
    private val watchDao get() = calculationDao<EpisodeWatchEventDao>("observeAllWatchEvents" to events, "observeWatchCounts" to events.groupingBy { it.episodeId }.eachCount().map { EpisodeWatchCount(it.key, it.value) })
    private val viewingDao get() = calculationDao<ViewingDao>("observeAllViewings" to viewings, "observeViewings" to viewings)
    private val outingDao get() = calculationDao<CinemaOutingDao>("observeAllOutings" to emptyList<CinemaOutingEntity>(), "observeOutingsForTitle" to emptyList<CinemaOutingEntity>())

    fun ledger() = LedgerRepository(titleDao, viewingDao, calculationDao("observeAllCast" to cast), calculationDao("observeAllCrew" to emptyList<TitleCrewEntity>()), outingDao, watchDao, seasonDao, episodeDao)
    fun library() = LibraryRepository(
        titleDao, seasonDao, episodeDao, watchDao,
        calculationDao("observeRatings" to ratings), calculationDao("observeReviews" to emptyList<EpisodeReviewEntity>()), viewingDao, outingDao,
        calculationDao(), calculationDao(), calculationDao("observeAll" to emptyList<TheaterInterestEntity>(), "observeIsInterested" to false),
        MutationOutbox(calculationDao(), calculationDao(), calculationDao()), calculationDao(),
    )
    suspend fun panel(id: LedgerWidgetId, range: String): LedgerBoard = ledger().observeLedgerBoards(flowOf(listOf(
        LedgerWidgetConfig("test", id, LedgerWidgetWidth.FULL, LedgerWidgetSettings(timeRange = range)),
    ))).first().getValue("test")
}
