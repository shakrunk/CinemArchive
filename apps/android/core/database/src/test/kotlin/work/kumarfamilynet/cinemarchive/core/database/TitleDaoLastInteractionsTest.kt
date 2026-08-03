package work.kumarfamilynet.cinemarchive.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [TitleDao.observeLastInteractions] against a real in-memory Room database rather
 * than by reading the `@Query` string — see issue #224. Covers the cases its kdoc claims: the
 * `addedAt` fallback, viewing/episode-event recency comparisons either side of `addedAt`, the
 * null-`watchedAt` "watched before joining" case, ratings/reviews counting alongside watch
 * events, and that the flow re-emits when a contributing table changes.
 */
@RunWith(RobolectricTestRunner::class)
class TitleDaoLastInteractionsTest {
    private lateinit var db: LibraryDatabase
    private lateinit var titleDao: TitleDao
    private lateinit var seasonDao: SeasonDao
    private lateinit var episodeDao: EpisodeDao
    private lateinit var viewingDao: ViewingDao
    private lateinit var watchEventDao: EpisodeWatchEventDao
    private lateinit var ratingDao: EpisodeRatingDao
    private lateinit var reviewDao: EpisodeReviewDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        titleDao = db.titleDao()
        seasonDao = db.seasonDao()
        episodeDao = db.episodeDao()
        viewingDao = db.viewingDao()
        watchEventDao = db.episodeWatchEventDao()
        ratingDao = db.episodeRatingDao()
        reviewDao = db.episodeReviewDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `falls back to addedAt with no viewings or episode events`() = runTest {
        titleDao.upsertAll(listOf(title("t1", addedAt = "2026-01-01T00:00:00Z")))

        val result = titleDao.observeLastInteractions().first()

        assertEquals(listOf(TitleLastInteraction("t1", "2026-01-01T00:00:00Z")), result)
    }

    @Test
    fun `a viewing newer than addedAt wins`() = runTest {
        titleDao.upsertAll(listOf(title("t1", addedAt = "2026-01-01T00:00:00Z")))
        viewingDao.upsert(viewing("v1", titleId = "t1", date = "2026-03-01"))

        val result = titleDao.observeLastInteractions().first()

        assertEquals("2026-03-01", result.single { it.titleId == "t1" }.lastInteractionAt)
    }

    @Test
    fun `a viewing older than addedAt does not win`() = runTest {
        titleDao.upsertAll(listOf(title("t1", addedAt = "2026-06-01T00:00:00Z")))
        viewingDao.upsert(viewing("v1", titleId = "t1", date = "2026-01-01"))

        val result = titleDao.observeLastInteractions().first()

        assertEquals("2026-06-01T00:00:00Z", result.single { it.titleId == "t1" }.lastInteractionAt)
    }

    @Test
    fun `a null watchedAt contributes nothing rather than nulling the rollup`() = runTest {
        titleDao.upsertAll(listOf(title("t1", addedAt = "2026-01-01T00:00:00Z")))
        seasonDao.upsertAll(listOf(season("s1", titleId = "t1")))
        episodeDao.upsertAll(listOf(episode("e1", titleId = "t1", seasonId = "s1")))
        // "Watched before joining the platform" — a real, permanent row with a null timestamp.
        watchEventDao.upsertAll(listOf(EpisodeWatchEventEntity(id = "w1", episodeId = "e1", watchedAt = null)))

        val result = titleDao.observeLastInteractions().first()

        assertEquals("2026-01-01T00:00:00Z", result.single { it.titleId == "t1" }.lastInteractionAt)
    }

    @Test
    fun `episode ratings and reviews count alongside watch events`() = runTest {
        titleDao.upsertAll(listOf(title("t1", addedAt = "2026-01-01T00:00:00Z")))
        seasonDao.upsertAll(listOf(season("s1", titleId = "t1")))
        episodeDao.upsertAll(listOf(episode("e1", titleId = "t1", seasonId = "s1")))
        ratingDao.upsertAll(listOf(EpisodeRatingEntity(id = "r1", episodeId = "e1", rating = 4.0, ratedAt = "2026-02-01T00:00:00Z")))

        assertEquals("2026-02-01T00:00:00Z", titleDao.observeLastInteractions().first().single { it.titleId == "t1" }.lastInteractionAt)

        reviewDao.upsertAll(listOf(EpisodeReviewEntity(id = "rv1", episodeId = "e1", reviewText = "Great", reviewedAt = "2026-03-01T00:00:00Z")))

        assertEquals("2026-03-01T00:00:00Z", titleDao.observeLastInteractions().first().single { it.titleId == "t1" }.lastInteractionAt)
    }

    @Test
    fun `flow re-emits when a contributing table changes`() = runTest {
        titleDao.upsertAll(listOf(title("t1", addedAt = "2026-01-01T00:00:00Z")))

        val before = titleDao.observeLastInteractions().first()
        assertEquals("2026-01-01T00:00:00Z", before.single { it.titleId == "t1" }.lastInteractionAt)

        viewingDao.upsert(viewing("v1", titleId = "t1", date = "2026-05-01"))

        val after = titleDao.observeLastInteractions().first()
        assertEquals("2026-05-01", after.single { it.titleId == "t1" }.lastInteractionAt)
    }

    private fun title(id: String, addedAt: String) = TitleEntity(
        id = id,
        tmdbId = id.hashCode(),
        type = "MOVIE",
        title = id,
        year = 2026,
        director = null,
        genres = emptyList(),
        posterUrl = null,
        backdropUrl = null,
        synopsis = null,
        runtime = null,
        network = null,
        status = "WATCHLIST",
        rating = null,
        notes = null,
        addedAt = addedAt,
        updatedAt = addedAt,
    )

    private fun season(id: String, titleId: String) = SeasonEntity(
        id = id,
        titleId = titleId,
        seasonNumber = 1,
        episodeCount = 1,
        episodesWatched = 0,
        airYear = 2026,
    )

    private fun episode(id: String, titleId: String, seasonId: String) = EpisodeEntity(
        id = id,
        titleId = titleId,
        seasonId = seasonId,
        episodeNumber = 1,
        episodeName = null,
        airDate = null,
        runtime = null,
    )

    private fun viewing(id: String, titleId: String, date: String) = ViewingEntity(
        id = id,
        titleId = titleId,
        date = date,
        rating = null,
        notes = null,
        venue = null,
    )
}
