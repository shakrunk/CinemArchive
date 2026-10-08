package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
class UpNextAdvanceTest {
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
        // Deliberately reverse the UUID order and include an unwatched special.
        db.seasonDao().upsertAll(listOf(SeasonEntity("z-first", "title", 1, 1, 0, 2020), SeasonEntity("a-second", "title", 2, 1, 0, 2020), SeasonEntity("special", "title", 0, 1, 0, 2020)))
        db.episodeDao().upsertAll(listOf(
            EpisodeEntity("ep1", "title", "z-first", 1, "First", "2020-01-01", 30),
            EpisodeEntity("ep2", "title", "a-second", 1, "Finale", "2020-01-02", 30),
            EpisodeEntity("special", "title", "special", 1, "Special", "2020-01-01", 30),
        ))
        repo = repository()
    }
    @After fun tearDown() { db.close() }
    private suspend fun advance(expected: String = "ep1") = repo.advanceNextEpisode("title", "2026-10-08", expected, "2026-10-08")

    @Test fun boardAndReceiptUseNumericMainSeasonOrderAndOriginalDate() = runBlocking {
        assertEquals("ep1", repo.observeUpNext().first().watching.single().nextEpisodeId)
        val receipt = advance()!!
        assertEquals("ep1", receipt.episodeId)
        assertEquals(1, receipt.seasonNumber)
        assertFalse(receipt.caughtUp)
        val row = db.episodeWatchEventDao().observeAllWatchEvents().first().single()
        assertEquals(receipt.watchEventId, row.id)
        assertEquals("2026-10-08", row.watchedAt)
        val queued = db.outboxDao().getPending().single()
        assertEquals(row.id, queued.entityId)
        assertEquals("2026-10-08", JSONObject(queued.payloadJson).getString("watchedAt"))
        assertEquals("ep2", repo.observeUpNext().first().watching.single().nextEpisodeId)
    }

    @Test fun staleAndConcurrentCardsCannotAdvanceDifferentEpisode() = runBlocking {
        val results = listOf(async { advance() }, async { advance() }).awaitAll()
        assertEquals(1, results.count { it != null })
        assertNull(advance())
        assertEquals(1, db.episodeWatchEventDao().observeAllWatchEvents().first().size)
        assertEquals(1, db.outboxDao().getPending().size)
    }

    @Test fun finaleDisappearsButStatusChangesOnlyWhenExplicitlyRequested() = runBlocking {
        advance()
        val finale = advance("ep2")!!
        assertTrue(finale.caughtUp)
        assertEquals(2, finale.seasonNumber)
        assertTrue(repo.observeUpNext().first().watching.isEmpty())
        assertEquals("WATCHING", db.titleDao().getById("title")!!.status)
        assertTrue(db.episodeWatchEventDao().observeAllWatchEvents().first().none { it.episodeId == "special" })
        repo.markSeriesWatched("title")
        assertEquals("WATCHED", db.titleDao().getById("title")!!.status)
        assertEquals("WATCHED", JSONObject(db.outboxDao().getPending().last().payloadJson).getString("status"))
    }

    @Test fun exactUndoPreservesConcurrentRewatchAndIndependentRatingReview() = runBlocking {
        val receipt = advance()!!
        repo.saveEpisodeLog("ep1", EpisodeLogDraft("later", "rating", "review", "2026-10-08T12:00:00Z", true, null, "Rewatch", 4.5, "Great"))
        repo.deleteEpisodeWatchEvent(receipt.episodeId, receipt.watchEventId)
        val ep = repo.observeTitleDetail("title").first()!!.seasons.first { it.seasonNumber == 1 }.episodes.single()
        assertEquals(listOf("later"), ep.watchEvents.map { it.id })
        assertEquals(1, ep.ratings.size); assertEquals(1, ep.reviews.size)
        assertEquals(receipt.watchEventId, db.outboxDao().getPending().last().entityId)
        assertEquals("delete", db.outboxDao().getPending().last().operation)
        assertEquals("WATCHING", db.titleDao().getById("title")!!.status)
    }

    @Test fun futureEpisodeAndInactiveTitleCannotBeAdvanced() = runBlocking {
        assertNull(repo.advanceNextEpisode("title", "2019-01-01", "ep1", "2019-01-01"))
        db.titleDao().upsertAll(listOf(db.titleDao().getById("title")!!.copy(status = "WATCHED")))
        assertNull(advance())
        assertTrue(db.episodeWatchEventDao().observeAllWatchEvents().first().isEmpty())
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun failedQueueInsertRollsBackWatchAndLeavesCardAvailable() = runBlocking {
        val failing = repository(object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) { error("disk full") }
        })
        try { failing.advanceNextEpisode("title", "2026-10-08", "ep1"); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertTrue(db.episodeWatchEventDao().observeAllWatchEvents().first().isEmpty())
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertEquals("ep1", repo.observeUpNext().first().watching.single().nextEpisodeId)
    }
}
