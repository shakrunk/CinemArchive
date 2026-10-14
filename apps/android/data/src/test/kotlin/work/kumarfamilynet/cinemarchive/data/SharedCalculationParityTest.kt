package work.kumarfamilynet.cinemarchive.data

import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

/** Reads the exact same graph and expectations as web calculationParity.test.ts. */
@RunWith(RobolectricTestRunner::class)
class SharedCalculationParityTest {
    private val today = LocalDate.now()
    private val fixture = JSONObject(File("../../../docs/android-contracts/fixtures/calculation-parity.json").readText()
        .replace("@beforeYearStart", today.withDayOfYear(1).minusDays(1).toString())
        .replace("@yearStart", today.withDayOfYear(1).toString()).replace("@today", today.toString()))
    private val rows = fixture.getJSONArray("titles").objects()
    private val expected = fixture.getJSONObject("expected")
    private val titles = rows.map { t -> TitleEntity(
        id = t.getString("id"), tmdbId = t.getInt("tmdbId"), type = t.getString("type").uppercase(),
        title = t.getString("title"), year = t.getInt("year"), director = null,
        genres = t.getJSONArray("genres").strings(), posterUrl = null, backdropUrl = null, synopsis = null,
        runtime = t.integer("runtime"), network = null, status = t.getString("status").uppercase(),
        rating = t.number("rating"), notes = null, addedAt = t.getString("addedAt"), updatedAt = t.getString("addedAt")) }
    private val seasons = rows.flatMap { t -> t.objects("seasons").map { s -> SeasonEntity(
        s.getString("id"), t.getString("id"), s.getInt("seasonNumber"), s.getInt("episodeCount"),
        s.getInt("episodesWatched"), null) } }
    private val episodeRows = rows.flatMap { t -> t.objects("seasons").flatMap { s ->
        s.objects("episodes").map { e -> Triple(t.getString("id"), s.getString("id"), e) } } }
    private val episodes = episodeRows.map { (title, season, e) -> EpisodeEntity(
        e.getString("id"), title, season, e.getInt("episodeNumber"), null, null, e.integer("runtime")) }
    private val watches = episodeRows.flatMap { (_, _, e) -> e.objects("watchEvents").map { w ->
        EpisodeWatchEventEntity(w.getString("id"), e.getString("id"), w.text("watchedAt")) } }
    private val ratings = episodeRows.flatMap { (_, _, e) -> e.objects("ratings").map { r ->
        EpisodeRatingEntity(r.getString("id"), e.getString("id"), r.getDouble("rating"), r.getString("ratedAt")) } }
    private val viewings = rows.flatMap { t -> t.objects("viewings").map { v -> ViewingEntity(
        v.getString("id"), t.getString("id"), v.text("date"), v.number("rating"), null, null) } }
    private val cast = rows.flatMap { t -> t.objects("cast").map { c -> TitleCastEntity(
        t.getString("id") + "-" + c.getInt("tmdbPersonId"), t.getString("id"), c.getInt("tmdbPersonId"),
        c.getString("name"), c.text("character"), c.getInt("order")) } }
    private val titleDao = dao<TitleDao> { name, args -> when (name) {
        "observeAllTitles" -> titles
        "observeTitle" -> titles.singleOrNull { it.id == args[0] }
        else -> error(name)
    } }
    private val seasonDao = dao<SeasonDao> { _, _ -> seasons }
    private val episodeDao = dao<EpisodeDao> { _, _ -> episodes }
    private val watchDao = dao<EpisodeWatchEventDao> { name, _ -> when (name) {
        "observeAllWatchEvents" -> watches
        "observeWatchCounts" -> watches.groupingBy { it.episodeId }.eachCount().map { EpisodeWatchCount(it.key, it.value) }
        else -> error(name)
    } }
    private val viewingDao = dao<ViewingDao> { name, args -> when (name) {
        "observeAllViewings" -> viewings
        "observeTotalViewingCount" -> viewings.size
        "observeViewings" -> viewings.filter { it.titleId == args[0] }
        else -> error(name)
    } }
    private val castDao = dao<TitleCastDao> { _, _ -> cast }
    private val crewDao = dao<TitleCrewDao> { _, _ -> emptyList<TitleCrewEntity>() }
    private val outingDao = dao<CinemaOutingDao> { _, _ -> emptyList<CinemaOutingEntity>() }
    private fun ledger() = LedgerRepository(titleDao, viewingDao, castDao, crewDao, outingDao, watchDao, seasonDao, episodeDao)
    private fun library() = LibraryRepository(titleDao, seasonDao, episodeDao, watchDao,
        dao<EpisodeRatingDao> { _, _ -> ratings }, dao<EpisodeReviewDao> { _, _ -> emptyList<EpisodeReviewEntity>() },
        viewingDao, outingDao, castDao, crewDao,
        dao<TheaterInterestDao> { name, _ -> if (name == "observeIsInterested") false else emptyList<TheaterInterestEntity>() },
        MutationOutbox(dao { _, _ -> error("No writes") }, dao { _, _ -> error("No writes") }, dao { _, _ -> error("No writes") }),
        dao { _, _ -> error("No venue reads") }, personCreditsDao = FakePersonCreditsDao())

    @Test fun sameGraphDrivesLibraryHistoryUpNextAndLedgerProgress() = runTest {
        val e = expected.getJSONObject("progress")
        val detail = library().observeTitleDetail("show").first()!!
        assertEquals(e.getJSONArray("seasonWatched").ints(), detail.seasons.map { it.episodesWatched })
        val episode = detail.seasons.single { it.seasonNumber == 2 }.episodes.first()
        assertEquals(e.getDouble("episodeAverage"), episode.averageRating!!, 0.0)
        assertEquals(e.getDouble("latestEpisodeRating"), episode.latestRating!!, 0.0)
        assertEquals(e.getInt("episodeWatchCount"), episode.watchCount)
        val next = library().observeUpNext().first().watching.single()
        assertEquals(e.getInt("watched"), next.episodesWatched)
        assertEquals(e.getInt("total"), next.episodesTotal)
        assertEquals(e.getInt("nextSeason"), next.nextSeasonNumber)
        assertEquals(e.getInt("nextEpisode"), next.nextEpisodeNumber)
        val progress = ledger().observeLedgerBoard().first().stillRolling.single()
        assertEquals(e.getInt("watched"), progress.episodesWatched)
        assertEquals(e.getInt("total"), progress.episodeCount)
    }

    @Test fun sameHeroCountsDistinctWatchedEpisodesIncludingSpecialsAndIgnoresUnknownRuntime() = runTest {
        val e = expected.getJSONObject("stats")
        val stats = ledger().observeLedgerStats().first()
        assertEquals(e.getInt("movies"), stats.totalMovies)
        assertEquals(e.getInt("series"), stats.totalSeries)
        assertEquals(e.getInt("viewings"), stats.totalViewings)
        assertEquals(e.getDouble("averageRating"), stats.averageRating!!, 0.0)
        assertEquals(e.getInt("movieMinutes"), stats.totalWatchedMovieMinutes)
        assertEquals(e.getInt("episodeMinutes"), stats.totalWatchedEpisodeMinutes)
        assertEquals(e.getInt("totalMinutes"), stats.totalWatchedMinutes)
        assertEquals(e.getInt("roundedHours"), stats.roundedHours)
        val board = ledger().observeLedgerBoard().first()
        assertEquals(expected.getJSONArray("runtimeBuckets").ints(), board.runtimeBuckets.map { it.count })
        assertEquals(expected.getJSONArray("actorCounts").ints(), board.ensemble.map { it.count })
    }

    @Test fun sameInclusiveDateWindowRetainsPrePlatformAndEarlierPremiereMeaning() = runTest {
        for (range in listOf("all", "ytd")) {
            val e = expected.getJSONObject("ranges").getJSONObject(range)
            val layout = listOf(LedgerWidgetId.REVIVALS, LedgerWidgetId.TRAJECTORY, LedgerWidgetId.RUN).map { panel ->
                LedgerWidgetConfig(panel.name, panel, LedgerWidgetWidth.FULL, LedgerWidgetSettings(timeRange = range)) }
            val boards = ledger().observeLedgerBoards(flowOf(layout)).first()
            val revivals = boards.getValue(LedgerWidgetId.REVIVALS.name).revivals
            assertEquals(range, e.getInt("premieres"), revivals.sumOf { it.premieres })
            assertEquals(range, e.getInt("revivals"), revivals.sumOf { it.revivals })
            assertEquals(range, e.getInt("trajectoryTitles"), boards.getValue(LedgerWidgetId.TRAJECTORY.name).trajectory.sumOf { it.titleCount })
            assertEquals(range, e.getInt("datedViewings"), boards.getValue(LedgerWidgetId.RUN.name).monthlyRun.sumOf { it.count })
        }
    }

    @Test fun sameGraphUsesCurrentTitleRatingAndExactPersonIdentityForLibrarySelection() {
        val library = titles.map { t -> LibraryTitle(t.id, t.title, t.year, null,
            LibraryStatus.valueOf(t.status), MediaType.valueOf(t.type), null, null, t.rating,
            genres = t.genres, people = cast.filter { it.titleId == t.id }.map { LibraryPerson(it.tmdbPersonId, it.name) }) }
        for (case in expected.getJSONArray("filterCases").objects()) {
            val filters = LibraryFilters(person = LibraryPerson(case.getInt("personId"), "Same Name"),
                sortOrder = LibrarySortOrder.TITLE, sortDirection = LibrarySortDirection.ASCENDING)
            assertEquals(case.getString("name"), case.getJSONArray("expected").strings(), filterLibrary(library, filters).map { it.id })
        }
        assertEquals(expected.getJSONArray("ratingDescending").strings(), filterLibrary(library,
            LibraryFilters(sortOrder = LibrarySortOrder.RATING_HIGHEST, sortDirection = LibrarySortDirection.DESCENDING)).map { it.id })
    }

    private inline fun <reified T> dao(crossinline value: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            check(method.name.startsWith("observe")) { "Unexpected DAO call: ${method.name}" }
            flowOf(value(method.name, args ?: emptyArray()))
        } as T
    private fun JSONObject.text(key: String) = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.number(key: String) = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.integer(key: String) = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.objects(key: String) = optJSONArray(key)?.objects().orEmpty()
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun JSONArray.ints() = (0 until length()).map(::getInt)
}
