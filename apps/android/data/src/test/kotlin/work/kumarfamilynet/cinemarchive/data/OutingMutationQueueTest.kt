package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.SeatAssignment

@RunWith(RobolectricTestRunner::class)
class OutingMutationQueueTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repo: OutingsRepository
    private lateinit var outbox: MutationOutbox
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("review required")
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
        repo = OutingsRepository(db.cinemaOutingDao(), db.viewingDao(), db.titleDao(), outbox, db.venueNoteDao())
        db.titleDao().upsertAll(listOf(TitleEntity("title", 42, "MOVIE", "Film", 2026, null, emptyList(), null, null, null, 90, null,
            "WATCHLIST", null, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")))
        db.cinemaOutingDao().upsert(CinemaOutingEntity(
            id = "outing", titleId = "title", showtime = "2099-10-08T19:00:00Z", runtimeMinutes = 90, endsAt = "2099-10-08T20:50:00Z",
            venue = "Cinema", companions = listOf("Smith, Alex"), format = "70mm", ticketPrice = null, notes = "Keep",
            ticketImagePath = "owner/remote-ticket", createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
        ))
    }
    @After fun tearDown() { db.close() }
    private suspend fun edit(format: CinemaFormat? = CinemaFormat.SEVENTY_MM) = repo.updateOuting(
        "outing", Instant.parse("2099-10-08T19:00:00Z"), 20, 90, "New cinema", listOf("Smith, Alex"),
        format, null, SeatAssignment(null, null, emptyList()), null, "Keep",
    )

    @Test fun editingVenueQueuesOnlyItsIntentAndPreservesTicketNamesAndFormat() = runBlocking {
        edit()
        val entry = db.outboxDao().getPending().single()
        assertEquals("update", entry.operation)
        assertEquals(setOf("id", "updatedAt", "venue"), JSONObject(entry.payloadJson).keys().asSequence().toSet())
        val row = db.cinemaOutingDao().getById("outing")!!
        assertEquals(listOf("Smith, Alex"), row.companions)
        assertEquals("owner/remote-ticket", row.ticketImagePath)
        assertEquals("70mm", row.format)
    }

    @Test fun captureCancelAndDismissEachQueueOnlyTheirChangedFields() = runBlocking {
        repo.clearTicketCapture("outing")
        repo.cancelOuting("outing")
        repo.dismissFollowUp("outing")
        val payloads = db.outboxDao().getPending().map { JSONObject(it.payloadJson) }
        assertEquals(setOf("id", "updatedAt", "ticketImagePath"), payloads[0].keys().asSequence().toSet())
        assertTrue(payloads[0].isNull("ticketImagePath"))
        assertEquals(setOf("id", "updatedAt", "status"), payloads[1].keys().asSequence().toSet())
        assertEquals(setOf("id", "updatedAt", "followUpDismissedAt"), payloads[2].keys().asSequence().toSet())
        assertTrue(db.outboxDao().getPending().all { it.operation == "update" })
    }

    @Test fun explicitCreateAndFollowingEditRemainOrderedWhileReviewFailurePreservesBoth() = runBlocking {
        val id = repo.scheduleOuting("title", Instant.parse("2099-11-01T19:00:00Z"), 20, 90, "Cinema",
            listOf("Friend"), CinemaFormat.DOLBY, null, SeatAssignment(null, null, emptyList()), null, null)
        repo.cancelOuting(id)
        val before = db.outboxDao().getPending()
        assertEquals(listOf("insert", "update"), before.map { it.operation })
        assertEquals("Dolby", JSONObject(before.first().payloadJson).getString("format"))
        outbox.flush()
        val after = db.outboxDao().getPending()
        assertEquals(before.map { it.payloadJson }, after.map { it.payloadJson })
        assertEquals("CANCELLED", db.cinemaOutingDao().getById(id)!!.status)
    }

    @Test fun unfamiliarStoredFormatIsRetainedWhenEditorHasNoKnownSelection() = runBlocking {
        val existing = db.cinemaOutingDao().getById("outing")!!
        db.cinemaOutingDao().upsert(existing.copy(format = "Future format"))
        edit(format = null)
        assertEquals("Future format", db.cinemaOutingDao().getById("outing")!!.format)
        assertFalse(JSONObject(db.outboxDao().getPending().single().payloadJson).has("format"))
    }
}

