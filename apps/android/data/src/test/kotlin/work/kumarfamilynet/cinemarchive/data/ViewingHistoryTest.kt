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
class ViewingHistoryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private val writer = object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Success }

    private fun repository(dao: OutboxDao = db.outboxDao()) = LibraryRepository(
        titleDao = db.titleDao(), seasonDao = db.seasonDao(), episodeDao = db.episodeDao(),
        watchEventDao = db.episodeWatchEventDao(), ratingDao = db.episodeRatingDao(), reviewDao = db.episodeReviewDao(),
        viewingDao = db.viewingDao(), cinemaOutingDao = db.cinemaOutingDao(), titleCastDao = db.titleCastDao(),
        titleCrewDao = db.titleCrewDao(), theaterInterestDao = db.theaterInterestDao(),
        outbox = MutationOutbox(dao, writer, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db)),
        episodeMetadataFetcher = object : EpisodeMetadataFetcher {
            override suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int) = emptyList<MediaEpisode>()
            override suspend fun fetchEpisodeCast(tmdbId: Int, seasonNumber: Int, episodeNumber: Int) = EpisodeCast.EMPTY
        },
        personCreditsDao = db.personCreditsDao(),
    )

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        db.titleDao().upsertAll(listOf(TitleEntity(
            id = "title", tmdbId = 42, type = "MOVIE", title = "A film", year = 2020, director = null,
            genres = emptyList(), posterUrl = null, backdropUrl = null, synopsis = null, runtime = null, network = null,
            status = "WATCHLIST", rating = 2.0, notes = null, addedAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
            imdbRating = null, originalLanguage = null, releaseDate = null,
        )))
        repo = repository()
    }
    @After fun tearDown() { db.close() }

    private fun event(id: String, outingId: String? = null) = ViewingEntity(id, "title", "2026-01-03", 3.5, "Old notes", "Old venue", listOf("Alex"), outingId)
    private fun draft(id: String = "watch") = ViewingDraft(id, "2025-12-24", 4.5, "New notes", "New venue", listOf("Sam"))
    private fun outing() = CinemaOutingEntity(
        id = "outing", titleId = "title", showtime = "2026-01-03T12:00:00Z", runtimeMinutes = 100,
        endsAt = "2026-01-03T14:00:00Z", venue = "Old venue", format = null, ticketPrice = null,
        status = "COMPLETED", completedViewingId = "watch", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-03T14:00:00Z",
    )

    @Test fun ordinaryEditKeepsIdAndOtherRewatchAndUpdatesEveryField() = runBlocking {
        db.viewingDao().upsertAll(listOf(event("watch"), event("other")))
        repo.saveViewing("title", draft(), false)
        assertEquals(event("watch").copy(date = "2025-12-24", rating = 4.5, notes = "New notes", venue = "New venue", companions = listOf("Sam")), db.viewingDao().getById("watch"))
        assertEquals(event("other"), db.viewingDao().getById("other"))
        assertEquals(4.5, db.titleDao().getById("title")!!.rating!!, 0.0)
        assertEquals("WATCHLIST", db.titleDao().getById("title")!!.status)
        val queued = db.outboxDao().getPending().single { it.entityType == "viewing" }
        assertEquals("update", queued.operation)
        assertEquals("Sam", JSONObject(queued.payloadJson).getJSONArray("companions").getJSONObject(0).getString("name"))
    }

    @Test fun alreadyRatedOutingCanBeEditedWithoutLosingLinkOrUnchangedFriendIdentity() = runBlocking {
        db.cinemaOutingDao().upsert(outing())
        db.viewingDao().upsert(event("watch", "outing"))
        repo.saveViewing("title", draft().copy(companions = listOf("Alex")), false)
        assertEquals("outing", db.viewingDao().getById("watch")!!.outingId)
        assertEquals(4.5, db.viewingDao().getById("watch")!!.rating!!, 0.0)
        assertFalse(JSONObject(db.outboxDao().getPending().first().payloadJson).has("companions"))
        assertEquals(outing(), db.cinemaOutingDao().getById("outing"))
    }

    @Test fun explicitClearsPersistLocallyAndInQueuedJsonWithoutClearingTitleRating() = runBlocking {
        db.viewingDao().upsert(event("watch"))
        repo.saveViewing("title", draft().copy(date = null, rating = null, notes = null, venue = null, companions = emptyList()), false)
        val saved = db.viewingDao().getById("watch")!!
        assertNull(saved.date); assertNull(saved.rating); assertNull(saved.notes); assertNull(saved.venue)
        val body = JSONObject(db.outboxDao().getPending().single().payloadJson)
        listOf("date", "rating", "notes", "venue").forEach { assertTrue(body.has(it)); assertTrue(body.isNull(it)) }
        assertEquals(0, body.getJSONArray("companions").length())
        assertEquals(2.0, db.titleDao().getById("title")!!.rating!!, 0.0)
    }

    @Test fun creatingAndRetryingSameDraftKeepsOneViewingAndMarksTitleWatched() = runBlocking {
        repo.saveViewing("title", draft(), true)
        val titlePatch = JSONObject(db.outboxDao().getPending().single { it.entityType == "title" }.payloadJson)
        assertEquals("WATCHED", titlePatch.getString("status"))
        assertEquals(4.5, titlePatch.getDouble("rating"), 0.0)
        repo.saveViewing("title", draft(), true)
        assertEquals(listOf("watch"), db.viewingDao().observeViewings("title").first().map { it.id })
        assertEquals("WATCHED", db.titleDao().getById("title")!!.status)
    }

    @Test fun deletionRemovesOnlyChosenViewingAndDismissesItsOutingWithoutRevertingCompletion() = runBlocking {
        db.cinemaOutingDao().upsert(outing())
        db.viewingDao().upsertAll(listOf(event("watch", "outing"), event("other")))
        repo.deleteViewing("title", "watch")
        assertNull(db.viewingDao().getById("watch"))
        assertEquals(event("other"), db.viewingDao().getById("other"))
        val savedOuting = db.cinemaOutingDao().getById("outing")!!
        assertEquals("COMPLETED", savedOuting.status)
        assertNull(savedOuting.completedViewingId)
        assertNotNull(savedOuting.followUpDismissedAt)
        val queue = db.outboxDao().getPending()
        assertEquals("delete", queue.single { it.entityType == "viewing" }.operation)
        assertTrue(JSONObject(queue.single { it.entityType == "cinema_outing" }.payloadJson).isNull("completedViewingId"))
        val unlink = queue.single { it.entityType == "cinema_outing" }
        assertEquals(OUTING_COMMAND, unlink.operation)
        val operation = outingCommandOperations(unlink).getJSONObject(0)
        assertEquals(outing().updatedAt, operation.getString("expectedUpdatedAt"))
        assertEquals(setOf("completed_viewing_id", "follow_up_dismissed_at"), operation.getJSONObject("values").keys().asSequence().toSet())
        assertEquals("WATCHLIST", db.titleDao().getById("title")!!.status)
    }

    @Test fun viewingDeletionUnlinkDependsOnExactEarlierOutingCommandBeforeDeletingTheEvent() = runBlocking {
        db.cinemaOutingDao().upsert(outing())
        db.viewingDao().upsert(event("watch", "outing"))
        val intent = JSONObject().put("id", "outing").put("venue", "Edited venue").put("updatedAt", "2026-01-03T15:00:00Z")
        db.outboxDao().enqueue(OutboxEntity("earlier", "cinema_outing", "outing", OUTING_COMMAND,
            outingCommandPayload(intent, false, outing().updatedAt, null).toString(), 1))
        repo.deleteViewing("title", "watch")
        val queue = db.outboxDao().getPending()
        assertEquals(listOf("cinema_outing", "cinema_outing", "viewing"), queue.map { it.entityType })
        assertEquals("earlier", outingCommandOperations(queue[1]).getJSONObject(0).getString("expectedOperationId"))
        assertFalse(outingCommandOperations(queue[1]).getJSONObject(0).has("expectedUpdatedAt"))
        assertEquals("delete", queue[2].operation)
    }

    @Test fun enqueueFailureRollsBackEditAndDeletionIncludingOutingChanges() = runBlocking {
        db.cinemaOutingDao().upsert(outing())
        db.viewingDao().upsert(event("watch", "outing"))
        val failing = repository(object : OutboxDao by db.outboxDao() {
            override suspend fun enqueue(entry: OutboxEntity) { throw IllegalStateException("disk full") }
        })
        for (delete in listOf(false, true)) {
            try {
                if (delete) failing.deleteViewing("title", "watch") else failing.saveViewing("title", draft(), false)
                fail("Expected failure")
            } catch (_: IllegalStateException) { }
            assertEquals(event("watch", "outing"), db.viewingDao().getById("watch"))
            assertEquals(outing(), db.cinemaOutingDao().getById("outing"))
            assertTrue(db.outboxDao().getPending().isEmpty())
        }
    }

    @Test fun staleEditorCannotResurrectRemovedViewing() = runBlocking {
        try { repo.saveViewing("title", draft(), false); fail("Expected missing viewing") } catch (_: IllegalStateException) { }
        assertNull(db.viewingDao().getById("watch"))
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun failedCreateCannotBeOvertakenByDeleteAndLaterResurrectViewing() = runBlocking {
        // Identical timestamps and reverse-lexical IDs must still preserve enqueue order.
        db.outboxDao().enqueue(OutboxEntity("z-create", "viewing", "watch", "upsert", "{}", 10L))
        db.outboxDao().enqueue(OutboxEntity("a-delete", "viewing", "watch", "delete", "{}", 10L))
        assertEquals(listOf("z-create", "a-delete"), db.outboxDao().getPending().map { it.id })
        val calls = mutableListOf<String>()
        var failing = true
        val remote = mutableSetOf<String>()
        val retryWriter = object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity): PushResult {
                calls += entry.operation
                if (failing) return PushResult.Retry("offline")
                if (entry.operation == "upsert") remote += entry.entityId else remote -= entry.entityId
                return PushResult.Success
            }
        }
        val outbox = MutationOutbox(db.outboxDao(), retryWriter, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
        outbox.flush()
        assertEquals(listOf("upsert"), calls)
        assertEquals(2, db.outboxDao().getPending().size)
        failing = false
        outbox.flush()
        assertEquals(listOf("upsert", "upsert", "delete"), calls)
        assertTrue(remote.isEmpty())
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun conflictRebaseUsesQueueOrderEvenWhenDeviceClockMovesBackwards() = runBlocking {
        db.outboxDao().enqueue(OutboxEntity("first", "title", "title", "update", "{\"rating\":2}", 100L))
        db.outboxDao().enqueue(OutboxEntity("later", "title", "title", "update", "{\"rating\":4}", 50L))
        val rebased = mutableListOf<Double>()
        val handler = object : ConflictHandler {
            override suspend fun applyRemote(entityType: String, entityId: String, serverPayload: JSONObject) = Unit
            override suspend fun rebasePending(entityType: String, entityId: String, laterPending: List<JSONObject>) {
                rebased += laterPending.map { it.getDouble("rating") }
            }
        }
        val remote = object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = if (entry.id == "first") PushResult.Conflict(JSONObject()) else PushResult.Retry("offline")
        }
        MutationOutbox(db.outboxDao(), remote, handler, RoomTransactor(db)).flush()
        assertEquals(listOf(4.0), rebased)
        assertEquals(listOf("later"), db.outboxDao().getPending().map { it.id })
    }
}
