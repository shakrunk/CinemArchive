package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

@RunWith(RobolectricTestRunner::class)
class EpisodeHistoryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private val writer = object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Success }
    private fun repository(dao: OutboxDao = db.outboxDao()) = LibraryRepository(
        db.titleDao(), db.seasonDao(), db.episodeDao(), db.episodeWatchEventDao(), db.episodeRatingDao(), db.episodeReviewDao(),
        db.viewingDao(), db.cinemaOutingDao(), db.titleCastDao(), db.titleCrewDao(), db.theaterInterestDao(),
        MutationOutbox(dao, writer, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db)),
        object : EpisodeMetadataFetcher {
            override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = emptyList<MediaEpisode>()
            override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int) = EpisodeCast.EMPTY
        },
        personCreditsDao = db.personCreditsDao(),
    )
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        db.titleDao().upsertAll(listOf(TitleEntity(
            id = "title", tmdbId = 42, type = "TV", title = "A show", year = 2020, director = null,
            genres = emptyList(), posterUrl = null, backdropUrl = null, synopsis = null, runtime = null, network = null,
            status = "WATCHING", rating = null, notes = null, addedAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
            imdbRating = null, originalLanguage = null, releaseDate = null,
        )))
        db.seasonDao().upsertAll(listOf(SeasonEntity("season", "title", 1, 2, 0, 2020)))
        db.episodeDao().upsertAll((1..2).map { EpisodeEntity("ep$it", "title", "season", it, "Episode $it", "2020-01-01", 30) })
        repo = repository()
    }
    @After fun tearDown() { db.close() }
    private fun draft(id: String = "one") = EpisodeLogDraft("watch-$id", "rating-$id", "review-$id", "2026-10-08T12:00:00Z", true, "2026-01-02", "With family", 4.5, "A great episode")
    private suspend fun episode() = repo.observeTitleDetail("title").first()!!.seasons.single().episodes.first()

    @Test fun datedAndPrePlatformRewatchesHaveIndependentHistoryAndUniqueProgress() = runBlocking {
        repo.saveEpisodeLog("ep1", draft())
        repo.saveEpisodeLog("ep1", draft("two").copy(watchedAt = null, watchNotes = "Long ago", rating = 3.0, reviewText = null))
        val detail = repo.observeTitleDetail("title").first()!!
        val ep = detail.seasons.single().episodes.first()
        assertEquals(2, ep.watchCount)
        assertEquals(1, detail.seasons.single().episodesWatched)
        assertEquals(listOf("2026-01-02", null), ep.watchEvents.map { it.watchedAt })
        assertEquals(listOf("With family", "Long ago"), ep.watchEvents.map { it.notes })
        assertEquals(3.75, ep.averageRating!!, 0.0)
        assertEquals(2, ep.ratings.size)
        assertEquals("A great episode", ep.reviews.single().reviewText)
    }

    @Test fun repeatedSaveRetainsAllThreeRowIdsAndTimestamps() = runBlocking {
        repeat(2) { repo.saveEpisodeLog("ep1", draft()) }
        val ep = episode()
        assertEquals(listOf("watch-one"), ep.watchEvents.map { it.id })
        assertEquals(listOf("rating-one"), ep.ratings.map { it.id })
        assertEquals(listOf("review-one"), ep.reviews.map { it.id })
        assertEquals(draft().recordedAt, ep.ratings.single().ratedAt)
        assertEquals(draft().recordedAt, ep.reviews.single().reviewedAt)
    }

    @Test fun ratingAndReviewOnlyLogDoesNotCreateWatchOrChangeProgress() = runBlocking {
        repo.saveEpisodeLog("ep1", draft().copy(includeWatch = false))
        val ep = episode()
        assertEquals(0, ep.watchCount)
        assertTrue(ep.watchEvents.isEmpty())
        assertEquals(1, ep.ratings.size)
        assertEquals(1, ep.reviews.size)
        assertEquals(listOf("episode_rating", "episode_review"), db.outboxDao().getPending().map { it.entityType })
    }

    @Test fun deletingSpecificRewatchKeepsOtherWatchRatingsAndReviewsThenLastDeleteClearsProgress() = runBlocking {
        repo.saveEpisodeLog("ep1", draft())
        repo.saveEpisodeLog("ep1", draft("two"))
        repo.deleteEpisodeWatchEvent("ep1", "watch-one")
        assertEquals(listOf("watch-two"), episode().watchEvents.map { it.id })
        assertEquals(2, episode().ratings.size)
        assertEquals(2, episode().reviews.size)
        assertEquals(1, repo.observeTitleDetail("title").first()!!.seasons.single().episodesWatched)
        repo.deleteEpisodeWatchEvent("ep1", "watch-two")
        assertEquals(0, repo.observeTitleDetail("title").first()!!.seasons.single().episodesWatched)
        assertEquals("WATCHING", db.titleDao().getById("title")!!.status)
        assertEquals(listOf("watch-one", "watch-two"), db.outboxDao().getPending().filter { it.operation == "delete" }.map { it.entityId })
    }

    @Test fun failedSecondQueueInsertRollsBackEveryPartOfCombinedLog() = runBlocking {
        var count = 0
        val failing = repository(object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) {
                if (++count == 2) throw IllegalStateException("disk full")
                db.outboxDao().enqueue(entry)
            }
        })
        try { failing.saveEpisodeLog("ep1", draft()); fail("Expected failure") } catch (_: IllegalStateException) { }
        val ep = episode()
        assertTrue(ep.watchEvents.isEmpty()); assertTrue(ep.ratings.isEmpty()); assertTrue(ep.reviews.isEmpty())
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun failedDeleteQueueInsertRollsBackEventRemoval() = runBlocking {
        repo.saveEpisodeLog("ep1", draft())
        val failing = repository(object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) { throw IllegalStateException("disk full") }
        })
        try { failing.deleteEpisodeWatchEvent("ep1", "watch-one"); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertEquals(1, episode().watchCount)
        assertTrue(db.outboxDao().getPending().none { it.operation == "delete" })
    }

    @Test fun deletingEventThroughWrongEpisodeCannotRemoveIt() = runBlocking {
        repo.saveEpisodeLog("ep1", draft())
        try { repo.deleteEpisodeWatchEvent("ep2", "watch-one"); fail("Expected ownership check") } catch (_: IllegalArgumentException) { }
        assertEquals(1, episode().watchCount)
    }

    @Test fun undatedWatchQueuesExplicitNullAndWatchNotes() = runBlocking {
        repo.saveEpisodeLog("ep1", draft().copy(watchedAt = null, rating = null, reviewText = null))
        val payload = JSONObject(db.outboxDao().getPending().single().payloadJson)
        assertTrue(payload.has("watchedAt") && payload.isNull("watchedAt"))
        assertEquals("With family", payload.getString("notes"))
    }
}
