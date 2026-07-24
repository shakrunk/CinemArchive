package work.kumarfamilynet.cinemarchive.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingDao
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeDao
import work.kumarfamilynet.cinemarchive.core.database.EpisodeEntity
import work.kumarfamilynet.cinemarchive.core.database.EpisodeWatchCount
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
import work.kumarfamilynet.cinemarchive.core.database.TitleListRow
import work.kumarfamilynet.cinemarchive.core.database.ViewingDao
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity
import work.kumarfamilynet.cinemarchive.core.model.LedgerCategoryCount
import work.kumarfamilynet.cinemarchive.core.model.LedgerPremiereRevivalBucket
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetConfig
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetId
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetSettings
import work.kumarfamilynet.cinemarchive.core.model.LedgerWidgetWidth

// --- Minimal in-memory fakes, one per DAO LedgerRepository depends on. Only the observeAllX()
// methods LedgerRepository actually calls are backed by real state; everything else throws,
// matching FakeOutboxDao's "only implement what's exercised" precedent in MutationOutboxTest.

private class FakeTitleDao(titles: List<TitleEntity>) : TitleDao {
    private val flow = MutableStateFlow(titles)
    override fun observeLibrary(): Flow<List<TitleListRow>> = throw UnsupportedOperationException()
    override fun observeTitle(titleId: String): Flow<TitleEntity?> = throw UnsupportedOperationException()
    override fun observeAllTitles(): Flow<List<TitleEntity>> = flow
    override suspend fun upsertAll(titles: List<TitleEntity>) = throw UnsupportedOperationException()
    override suspend fun updateStatus(titleId: String, status: String, updatedAt: String) = throw UnsupportedOperationException()
    override suspend fun updateRating(titleId: String, rating: Double, updatedAt: String) = throw UnsupportedOperationException()
    override suspend fun count(): Int = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private class FakeViewingDao(viewings: List<ViewingEntity>) : ViewingDao {
    private val flow = MutableStateFlow(viewings)
    override fun observeViewings(titleId: String): Flow<List<ViewingEntity>> = throw UnsupportedOperationException()
    override fun observeTotalViewingCount(): Flow<Int> = throw UnsupportedOperationException()
    override fun observeAllViewings(): Flow<List<ViewingEntity>> = flow
    override suspend fun getById(id: String): ViewingEntity? = throw UnsupportedOperationException()
    override suspend fun getByOutingId(outingId: String): ViewingEntity? = throw UnsupportedOperationException()
    override suspend fun upsertAll(viewings: List<ViewingEntity>) = throw UnsupportedOperationException()
    override suspend fun upsert(viewing: ViewingEntity) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private class FakeTitleCastDao(cast: List<TitleCastEntity>) : TitleCastDao {
    private val flow = MutableStateFlow(cast)
    override fun observeAllCast(): Flow<List<TitleCastEntity>> = flow
    override suspend fun upsertAll(rows: List<TitleCastEntity>) = throw UnsupportedOperationException()
}

private class FakeTitleCrewDao(crew: List<TitleCrewEntity>) : TitleCrewDao {
    private val flow = MutableStateFlow(crew)
    override fun observeAllCrew(): Flow<List<TitleCrewEntity>> = flow
    override suspend fun upsertAll(rows: List<TitleCrewEntity>) = throw UnsupportedOperationException()
}

private class FakeCinemaOutingDao(outings: List<CinemaOutingEntity>) : CinemaOutingDao {
    private val flow = MutableStateFlow(outings)
    override fun observeAllOutings(): Flow<List<CinemaOutingEntity>> = flow
    override fun observeOutingsForTitle(titleId: String): Flow<List<CinemaOutingEntity>> = throw UnsupportedOperationException()
    override suspend fun getById(id: String): CinemaOutingEntity? = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
    override suspend fun upsertAll(rows: List<CinemaOutingEntity>) = throw UnsupportedOperationException()
    override suspend fun upsert(row: CinemaOutingEntity) = throw UnsupportedOperationException()
    override suspend fun getScheduledOutings(): List<CinemaOutingEntity> = throw UnsupportedOperationException()
}

private class FakeEpisodeWatchEventDao(events: List<EpisodeWatchEventEntity>) : EpisodeWatchEventDao {
    private val flow = MutableStateFlow(events)
    override fun observeWatchCounts(titleId: String): Flow<List<EpisodeWatchCount>> = throw UnsupportedOperationException()
    override fun observeAllWatchEvents(): Flow<List<EpisodeWatchEventEntity>> = flow
    override suspend fun upsertAll(events: List<EpisodeWatchEventEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private class FakeSeasonDao(seasons: List<SeasonEntity>) : SeasonDao {
    private val flow = MutableStateFlow(seasons)
    override fun observeSeasons(titleId: String): Flow<List<SeasonEntity>> = throw UnsupportedOperationException()
    override fun observeAllSeasons(): Flow<List<SeasonEntity>> = flow
    override suspend fun upsertAll(seasons: List<SeasonEntity>) = throw UnsupportedOperationException()
    override suspend fun findSeasonId(titleId: String, seasonNumber: Int): String? = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

private class FakeEpisodeDao(episodes: List<EpisodeEntity>) : EpisodeDao {
    private val flow = MutableStateFlow(episodes)
    override fun observeEpisodes(titleId: String): Flow<List<EpisodeEntity>> = throw UnsupportedOperationException()
    override fun observeAllEpisodes(): Flow<List<EpisodeEntity>> = flow
    override suspend fun upsertAll(episodes: List<EpisodeEntity>) = throw UnsupportedOperationException()
    override suspend fun deleteById(id: String) = throw UnsupportedOperationException()
}

/**
 * The exact fixture data the now-retired `DevFixtureSeed` used to seed the emulator with
 * (see git history — removed in ec05214 once real Supabase sync replaced it), reproduced here
 * as plain in-memory data rather than a Room seed. This is deliberately the *same* data
 * `docs/android-implementation-status.md`'s Phase 3 Ledger section hand-verified against on a
 * real device (e.g. "Second Opinions 'Us 9.0 vs IMDb 4.4 (Δ4.6)' for Inception", "Still Rolling
 * 'Breaking Bad 1/7 episodes'") — this test turns those one-off manual checks into a
 * regression suite over [LedgerRepository.observeLedgerBoard]'s pure aggregation.
 */
private object LedgerFixture {
    const val INCEPTION_ID = "3f2b6b1a-8b3e-4a0c-9c1a-1a2b3c4d5e6f"
    const val BREAKING_BAD_ID = "7c1d2e3f-4a5b-6c7d-8e9f-0a1b2c3d4e5f"
    const val FIGHT_CLUB_ID = "9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d"
    private const val SEASON_ID = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
    private const val PILOT_EPISODE_ID = "b2c3d4e5-f6a7-8b9c-0d1e-2f3a4b5c6d7e"
    private const val SECOND_EPISODE_ID = "a9b8c7d6-e5f4-3a2b-1c0d-9e8f7a6b5c4d"
    const val CINEMA_OUTING_ID = "d1e2f3a4-b5c6-7d8e-9f0a-1b2c3d4e5f60"
    private const val SCHEDULED_OUTING_ID = "a4b5c6d7-e8f9-0a1b-2c3d-4e5f60718293"

    val titles = listOf(
        TitleEntity(
            id = INCEPTION_ID, tmdbId = 27205, type = "MOVIE", title = "Inception", year = 2010,
            director = "Christopher Nolan", genres = listOf("Action", "Science Fiction", "Thriller"),
            posterUrl = null, backdropUrl = null, synopsis = null, runtime = 148, network = null,
            status = "WATCHED", rating = 4.5, notes = null, addedAt = "2026-01-15",
            updatedAt = "2026-01-15T09:12:00.000Z", imdbRating = 4.4, originalLanguage = "en",
        ),
        TitleEntity(
            id = BREAKING_BAD_ID, tmdbId = 1396, type = "TV", title = "Breaking Bad", year = 2008,
            director = null, genres = listOf("Drama", "Crime"), posterUrl = null, backdropUrl = null,
            synopsis = null, runtime = null, network = "AMC", status = "WATCHING", rating = null,
            notes = null, addedAt = "2026-03-02", updatedAt = "2026-06-20T14:30:00.000Z",
            imdbRating = 4.65, originalLanguage = "en",
        ),
        TitleEntity(
            id = FIGHT_CLUB_ID, tmdbId = 550, type = "MOVIE", title = "Fight Club", year = 1999,
            director = "David Fincher", genres = listOf("Drama"), posterUrl = null, backdropUrl = null,
            synopsis = null, runtime = 139, network = null, status = "WATCHLIST", rating = null,
            notes = null, addedAt = "2026-07-01", updatedAt = "2026-07-01T20:05:00.000Z",
            imdbRating = 4.35, originalLanguage = "en",
        ),
    )

    val cast = listOf(
        TitleCastEntity("cast-1", INCEPTION_ID, 6193, "Leonardo DiCaprio", "Cobb", 0),
        TitleCastEntity("cast-2", INCEPTION_ID, 24045, "Joseph Gordon-Levitt", "Arthur", 1),
        TitleCastEntity("cast-3", INCEPTION_ID, 2524, "Tom Hardy", "Eames", 2),
        TitleCastEntity("cast-4", BREAKING_BAD_ID, 17419, "Bryan Cranston", "Walter White", 0),
        TitleCastEntity("cast-5", BREAKING_BAD_ID, 84497, "Aaron Paul", "Jesse Pinkman", 1),
        TitleCastEntity("cast-6", FIGHT_CLUB_ID, 287, "Brad Pitt", "Tyler Durden", 0),
        TitleCastEntity("cast-7", FIGHT_CLUB_ID, 819, "Edward Norton", "The Narrator", 1),
    )

    val crew = listOf(
        TitleCrewEntity("crew-1", INCEPTION_ID, 525, "Christopher Nolan", "Director", "Directing"),
        TitleCrewEntity("crew-2", BREAKING_BAD_ID, 66633, "Vince Gilligan", "Creator", "Writing"),
        TitleCrewEntity("crew-3", FIGHT_CLUB_ID, 7467, "David Fincher", "Director", "Directing"),
    )

    val seasons = listOf(SeasonEntity(SEASON_ID, BREAKING_BAD_ID, seasonNumber = 1, episodeCount = 7, episodesWatched = 1, airYear = 2008))

    val episodes = listOf(
        EpisodeEntity(PILOT_EPISODE_ID, BREAKING_BAD_ID, SEASON_ID, 1, "Pilot", "2008-01-20", 58),
        EpisodeEntity(SECOND_EPISODE_ID, BREAKING_BAD_ID, SEASON_ID, 2, "Cat's in the Bag...", "2008-01-27", 48),
    )

    val watchEvents = listOf(
        EpisodeWatchEventEntity("watch-1", PILOT_EPISODE_ID, "2026-03-02"),
        EpisodeWatchEventEntity("watch-2", PILOT_EPISODE_ID, "2026-06-10"),
    )

    val outings = listOf(
        CinemaOutingEntity(
            id = CINEMA_OUTING_ID, titleId = INCEPTION_ID, showtime = "2026-01-15T19:00:00Z",
            runtimeMinutes = 148, endsAt = "2026-01-15T22:08:00Z", venue = "AMC Lincoln Square",
            companions = listOf("Sam", "Jordan"), format = "IMAX", ticketPrice = 24.50, status = "COMPLETED",
            previousStatus = "WATCHLIST", completedViewingId = "viewing-inception",
            createdAt = "2026-01-14T10:00:00Z", updatedAt = "2026-01-15T22:08:00Z",
        ),
        CinemaOutingEntity(
            id = SCHEDULED_OUTING_ID, titleId = FIGHT_CLUB_ID, showtime = "2026-08-01T19:00:00Z",
            runtimeMinutes = 139, endsAt = "2026-08-01T21:39:00Z", venue = "Alamo Drafthouse",
            companions = listOf("Alex"), format = "SEVENTY_MM", ticketPrice = 18.00, seat = "H12",
            status = "SCHEDULED", createdAt = "2026-07-20T00:00:00Z", updatedAt = "2026-07-20T00:00:00Z",
        ),
    )

    val viewings = listOf(
        ViewingEntity(id = "viewing-breaking-bad", titleId = BREAKING_BAD_ID, date = "2026-03-02", rating = null, notes = null, venue = null),
        ViewingEntity(
            id = "viewing-inception", titleId = INCEPTION_ID, date = "2026-01-15", rating = 4.5, notes = null,
            venue = "AMC Lincoln Square", companions = listOf("Sam", "Jordan"), outingId = CINEMA_OUTING_ID,
        ),
    )

    fun repository() = LedgerRepository(
        titleDao = FakeTitleDao(titles),
        viewingDao = FakeViewingDao(viewings),
        titleCastDao = FakeTitleCastDao(cast),
        titleCrewDao = FakeTitleCrewDao(crew),
        cinemaOutingDao = FakeCinemaOutingDao(outings),
        watchEventDao = FakeEpisodeWatchEventDao(watchEvents),
        seasonDao = FakeSeasonDao(seasons),
        episodeDao = FakeEpisodeDao(episodes),
    )
}

/** Regression coverage for [LedgerRepository.observeLedgerBoard]'s 20-widget aggregation
 *  against [LedgerFixture] — the same data the on-device hand verification in
 *  `docs/android-implementation-status.md`'s Phase 3 Ledger section used. Widgets whose
 *  bucketing is relative to the real "now" ([LedgerRepository.weeklyActivity]'s 52-week
 *  window, [LedgerRepository.monthlyRun]'s 12-month window) are covered separately below with
 *  synthetic dates computed relative to [LocalDate.now] at test-run time, since pinning them
 *  to [LedgerFixture]'s fixed 2026 dates would silently stop asserting anything once those
 *  dates age out of the trailing window. */
class LedgerRepositoryTest {

    @Test
    fun `Feature Lengths buckets both movies into 120-150 min`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(2, board.runtimeBuckets.single { it.label == "120–150 min" }.count)
    }

    @Test
    fun `On the Air tallies AMC once`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(listOf(LedgerCategoryCount("AMC", 1)), board.networks)
    }

    @Test
    fun `By the Era buckets one title per decade`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(
            listOf(
                LedgerCategoryCount("1990s", 1),
                LedgerCategoryCount("2000s", 1),
                LedgerCategoryCount("2010s", 1),
            ),
            board.decades,
        )
    }

    @Test
    fun `Coming Attractions lists Fight Club owing 139 movie-minutes`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(1, board.watchlist.size)
        assertEquals("Fight Club", board.watchlist.single().title)
        assertEquals(139, board.watchlistMovieMinutesOwed)
    }

    @Test
    fun `Encore Performances is empty since no title has 2 plus viewings`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertTrue(board.encores.isEmpty())
    }

    @Test
    fun `Critical Record buckets Inception at 4point5 stars`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(1, board.ratingBuckets.single { it.label == "★4.5" }.count)
        assertEquals(0, board.ratingBuckets.filter { it.label != "★4.5" }.sumOf { it.count })
    }

    @Test
    fun `By the Genre counts Drama twice`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(2, board.genres.single { it.label == "Drama" }.count)
    }

    @Test
    fun `The Auteurs counts only Director-job crew, not Breaking Bad's Creator`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(
            setOf("Christopher Nolan" to 1, "David Fincher" to 1),
            board.auteurs.map { it.label to it.count }.toSet(),
        )
        assertEquals(2, board.auteurs.size)
    }

    @Test
    fun `The Ensemble tallies all 7 leading cast members once each`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(7, board.ensemble.size)
        assertTrue(board.ensemble.all { it.count == 1 })
    }

    @Test
    fun `Second Opinions shows Inception at 9point0 vs IMDb 4point4`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(1, board.verdicts.size)
        val inception = board.verdicts.single()
        assertEquals("Inception", inception.title)
        assertEquals(9.0, inception.ourRatingOn10, 0.001)
        assertEquals(4.4, inception.imdbRating, 0.001)
        assertEquals(4.6, inception.delta, 0.001)
    }

    @Test
    fun `In Translation tallies English for all 3 titles`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(listOf(LedgerCategoryCount("English", 3)), board.languages)
    }

    @Test
    fun `Screening Nights buckets by the viewings' real weekdays`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        // 2026-01-15 (Inception) is a Thursday, 2026-03-02 (Breaking Bad) is a Monday.
        assertEquals(1, board.weekdays.single { it.weekday.startsWith("Thu") }.count)
        assertEquals(1, board.weekdays.single { it.weekday.startsWith("Mon") }.count)
        assertEquals(2, board.weekdays.sumOf { it.count })
    }

    @Test
    fun `The Marathon folds viewings and watch events into 3 distinct dates`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(listOf("2026-01-15", "2026-03-02", "2026-06-10"), board.streaks.recentActiveDates)
        assertEquals(1, board.streaks.longestStreakDays)
        assertEquals("none of the fixture's 2026 dates can be today's real date", 0, board.streaks.currentStreakDays)
    }

    @Test
    fun `Shifting Standards lands Inception in 2026 Q1 at 4point5`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(1, board.trajectory.size)
        val q1 = board.trajectory.single()
        assertEquals("2026 Q1", q1.quarterLabel)
        assertEquals(4.5, q1.averageRating, 0.001)
        assertEquals(1, q1.titleCount)
    }

    @Test
    fun `Premieres and Revivals has one premiere each in Jan and Mar 2026, no revivals`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(
            listOf(
                LedgerPremiereRevivalBucket("Jan 2026", premieres = 1, revivals = 0),
                LedgerPremiereRevivalBucket("Mar 2026", premieres = 1, revivals = 0),
            ),
            board.revivals,
        )
    }

    @Test
    fun `The Revival House buckets both viewings into 16 plus years`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(2, board.timewarp.single { it.label == "16+ yrs" }.count)
        assertEquals(0, board.timewarp.filter { it.label != "16+ yrs" }.sumOf { it.count })
    }

    @Test
    fun `Still Rolling shows Breaking Bad at 1 of 7 episodes`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        assertEquals(1, board.stillRolling.size)
        val breakingBad = board.stillRolling.single()
        assertEquals("Breaking Bad", breakingBad.title)
        assertEquals(1, breakingBad.episodesWatched)
        assertEquals(7, breakingBad.episodeCount)
    }

    @Test
    fun `At the Movies matches the hand-verified trip, spend, venue, and companions`() = runTest {
        val board = LedgerFixture.repository().observeLedgerBoard().first()
        val moviegoing = board.moviegoing
        assertEquals(1, moviegoing.tripCount)
        assertEquals(24.50, moviegoing.totalSpend!!, 0.001)
        assertEquals(listOf(LedgerCategoryCount("AMC Lincoln Square", 1)), moviegoing.venues)
        assertEquals(
            setOf("Sam" to 1, "Jordan" to 1),
            moviegoing.companions.map { it.label to it.count }.toSet(),
        )
        assertEquals(listOf(LedgerCategoryCount("IMAX", 1)), moviegoing.formats)
    }

    // --- Time-relative widgets: pinned against synthetic dates computed from LocalDate.now()
    // at test-run time, not LedgerFixture's fixed 2026 dates (see class kdoc).

    @Test
    fun `Time in the Dark counts a viewing from 3 weeks ago in the 52-week window`() = runTest {
        val recentDate = LocalDate.now().minusWeeks(3)
        val repository = LedgerRepository(
            titleDao = FakeTitleDao(LedgerFixture.titles),
            viewingDao = FakeViewingDao(
                listOf(ViewingEntity(id = "recent", titleId = LedgerFixture.INCEPTION_ID, date = recentDate.toString(), rating = null, notes = null, venue = null)),
            ),
            titleCastDao = FakeTitleCastDao(emptyList()),
            titleCrewDao = FakeTitleCrewDao(emptyList()),
            cinemaOutingDao = FakeCinemaOutingDao(emptyList()),
            watchEventDao = FakeEpisodeWatchEventDao(emptyList()),
            seasonDao = FakeSeasonDao(emptyList()),
            episodeDao = FakeEpisodeDao(emptyList()),
        )

        val board = repository.observeLedgerBoard().first()

        assertEquals(52, board.weeklyActivity.size)
        assertEquals(1, board.weeklyActivity.sumOf { it.count })
    }

    @Test
    fun `dailyActivity has exactly 364 entries and counts a viewing from 3 weeks ago`() = runTest {
        val recentDate = LocalDate.now().minusWeeks(3)
        val repository = LedgerRepository(
            titleDao = FakeTitleDao(LedgerFixture.titles),
            viewingDao = FakeViewingDao(
                listOf(ViewingEntity(id = "recent", titleId = LedgerFixture.INCEPTION_ID, date = recentDate.toString(), rating = null, notes = null, venue = null)),
            ),
            titleCastDao = FakeTitleCastDao(emptyList()),
            titleCrewDao = FakeTitleCrewDao(emptyList()),
            cinemaOutingDao = FakeCinemaOutingDao(emptyList()),
            watchEventDao = FakeEpisodeWatchEventDao(emptyList()),
            seasonDao = FakeSeasonDao(emptyList()),
            episodeDao = FakeEpisodeDao(emptyList()),
        )

        val board = repository.observeLedgerBoard().first()

        assertEquals(364, board.dailyActivity.size)
        assertEquals(1, board.dailyActivity.sum())
    }

    @Test
    fun `The Marathon's last30Nights has exactly 30 entries and marks a viewing from yesterday`() = runTest {
        val yesterday = LocalDate.now().minusDays(1)
        val repository = LedgerRepository(
            titleDao = FakeTitleDao(LedgerFixture.titles),
            viewingDao = FakeViewingDao(
                listOf(ViewingEntity(id = "yesterday", titleId = LedgerFixture.INCEPTION_ID, date = yesterday.toString(), rating = null, notes = null, venue = null)),
            ),
            titleCastDao = FakeTitleCastDao(emptyList()),
            titleCrewDao = FakeTitleCrewDao(emptyList()),
            cinemaOutingDao = FakeCinemaOutingDao(emptyList()),
            watchEventDao = FakeEpisodeWatchEventDao(emptyList()),
            seasonDao = FakeSeasonDao(emptyList()),
            episodeDao = FakeEpisodeDao(emptyList()),
        )

        val board = repository.observeLedgerBoard().first()

        assertEquals(30, board.streaks.last30Nights.size)
        assertEquals(1, board.streaks.last30Nights.count { it })
        assertTrue("the second-to-last entry (yesterday) must be true", board.streaks.last30Nights[28])
    }

    @Test
    fun `The Run counts a viewing from 2 months ago in the 12-month window`() = runTest {
        val recentDate = LocalDate.now().minusMonths(2)
        val repository = LedgerRepository(
            titleDao = FakeTitleDao(LedgerFixture.titles),
            viewingDao = FakeViewingDao(
                listOf(ViewingEntity(id = "recent", titleId = LedgerFixture.INCEPTION_ID, date = recentDate.toString(), rating = null, notes = null, venue = null)),
            ),
            titleCastDao = FakeTitleCastDao(emptyList()),
            titleCrewDao = FakeTitleCrewDao(emptyList()),
            cinemaOutingDao = FakeCinemaOutingDao(emptyList()),
            watchEventDao = FakeEpisodeWatchEventDao(emptyList()),
            seasonDao = FakeSeasonDao(emptyList()),
            episodeDao = FakeEpisodeDao(emptyList()),
        )

        val board = repository.observeLedgerBoard().first()

        assertEquals(12, board.monthlyRun.size)
        val expectedLabel = recentDate.format(DateTimeFormatter.ofPattern("MMM yyyy"))
        assertEquals(1, board.monthlyRun.single { it.monthLabel == expectedLabel }.count)
        assertEquals(1, board.monthlyRun.sumOf { it.count })
    }

    // --- Phase D: observeLedgerBoards' per-widget scope/timeRange consumption
    // (docs/superpowers/plans/2026-07-23-android-ledger-parity.md).

    @Test
    fun `a movies-scoped Genre widget excludes Breaking Bad's Drama and Crime contribution`() = runTest {
        val layout = listOf(
            LedgerWidgetConfig(id = "w-genres", panel = LedgerWidgetId.GENRES, width = LedgerWidgetWidth.FULL, settings = LedgerWidgetSettings(scope = "movies")),
        )

        val boards = LedgerFixture.repository().observeLedgerBoards(flowOf(layout)).first()

        // Unscoped, Drama is 2 (Breaking Bad + Fight Club) per the earlier full-board test;
        // scoped to movies only, Breaking Bad (TV) drops out and Drama falls to Fight Club's 1.
        assertEquals(1, boards.getValue("w-genres").genres.single { it.label == "Drama" }.count)
    }

    @Test
    fun `a movies-scoped Ensemble widget excludes Breaking Bad's cast`() = runTest {
        val layout = listOf(
            LedgerWidgetConfig(id = "w-ensemble", panel = LedgerWidgetId.ENSEMBLE, width = LedgerWidgetWidth.FULL, settings = LedgerWidgetSettings(scope = "movies")),
        )

        val boards = LedgerFixture.repository().observeLedgerBoards(flowOf(layout)).first()

        val names = boards.getValue("w-ensemble").ensemble.map { it.label }.toSet()
        assertEquals(5, names.size) // 3 Inception + 2 Fight Club, not Breaking Bad's 2
        assertFalse("Bryan Cranston" in names)
        assertFalse("Aaron Paul" in names)
    }

    @Test
    fun `Feature Lengths ignores an unsupported scope setting entirely`() = runTest {
        // runtimes only honors 'title' per PANEL_SETTING_KEYS -- a stray scope value (e.g.
        // from a layout synced down from a future web version) must be silently ignored, not
        // applied, matching ledger.md §1.
        val layout = listOf(
            LedgerWidgetConfig(id = "w-runtimes", panel = LedgerWidgetId.RUNTIMES, width = LedgerWidgetWidth.FULL, settings = LedgerWidgetSettings(scope = "tv")),
        )

        val boards = LedgerFixture.repository().observeLedgerBoards(flowOf(layout)).first()

        assertEquals(2, boards.getValue("w-runtimes").runtimeBuckets.single { it.label == "120–150 min" }.count)
    }

    @Test
    fun `widgets sharing the same effective scope and time range share one computed board`() = runTest {
        // Both panels honor scope and neither has an explicit setting, so both resolve to the
        // same "all" scope, "all" time range pair -- and, being reference-equal LedgerBoard
        // instances from the same buildBoard() call, prove the per-pair memoization ran once.
        val layout = listOf(
            LedgerWidgetConfig(id = "w-genres", panel = LedgerWidgetId.GENRES, width = LedgerWidgetWidth.FULL),
            LedgerWidgetConfig(id = "w-decades", panel = LedgerWidgetId.DECADES, width = LedgerWidgetWidth.FULL),
        )

        val boards = LedgerFixture.repository().observeLedgerBoards(flowOf(layout)).first()

        assertTrue(boards.getValue("w-genres") === boards.getValue("w-decades"))
    }

    @Test
    fun `a this-year-scoped Screening Nights widget excludes a viewing from over a year ago`() = runTest {
        val thisYear = LocalDate.now().withDayOfYear(1).plusDays(10)
        val lastYear = LocalDate.now().minusYears(2)
        val repository = LedgerRepository(
            titleDao = FakeTitleDao(LedgerFixture.titles),
            viewingDao = FakeViewingDao(
                listOf(
                    ViewingEntity(id = "this-year", titleId = LedgerFixture.INCEPTION_ID, date = thisYear.toString(), rating = null, notes = null, venue = null),
                    ViewingEntity(id = "last-year", titleId = LedgerFixture.BREAKING_BAD_ID, date = lastYear.toString(), rating = null, notes = null, venue = null),
                ),
            ),
            titleCastDao = FakeTitleCastDao(emptyList()),
            titleCrewDao = FakeTitleCrewDao(emptyList()),
            cinemaOutingDao = FakeCinemaOutingDao(emptyList()),
            watchEventDao = FakeEpisodeWatchEventDao(emptyList()),
            seasonDao = FakeSeasonDao(emptyList()),
            episodeDao = FakeEpisodeDao(emptyList()),
        )
        val layout = listOf(
            LedgerWidgetConfig(id = "w-weekdays", panel = LedgerWidgetId.WEEKDAYS, width = LedgerWidgetWidth.FULL, settings = LedgerWidgetSettings(timeRange = "ytd")),
        )

        val boards = repository.observeLedgerBoards(flowOf(layout)).first()

        assertEquals(1, boards.getValue("w-weekdays").weekdays.sumOf { it.count })
    }
}
