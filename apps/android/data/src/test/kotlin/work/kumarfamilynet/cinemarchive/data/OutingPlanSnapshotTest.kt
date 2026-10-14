package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*

@RunWith(RobolectricTestRunner::class)
class OutingPlanSnapshotTest {
    private lateinit var db: LibraryDatabase
    private lateinit var outbox: MutationOutbox
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter { override suspend fun push(entry: OutboxEntity) = PushResult.Success },
            TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
        db.titleDao().upsertAll(listOf(TitleEntity("title", 42, "MOVIE", "Film", 2026, null, emptyList(), null, null, null, 90, null,
            "WATCHLIST", null, "private title notes", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")))
        db.cinemaOutingDao().upsert(CinemaOutingEntity(
            id = "outing", titleId = "title", showtime = "2099-10-08T19:00:00Z", runtimeMinutes = 90, endsAt = "2099-10-08T21:00:00Z",
            venue = "Cinema", companions = listOf("Friend"), format = "IMAX", ticketPrice = 999.99, seatRow = "F", seats = listOf("12"),
            bookingRef = "secret-booking", ticketImagePath = "private/ticket.png", ticketBarcodePayload = "secret-barcode", notes = "secret-note",
            createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
        ))
    }
    @After fun tearDown() { db.close() }
    private suspend fun snapshot() = readOutingPlanSnapshot("outing", db.cinemaOutingDao(), db.titleDao(), outbox)!!

    @Test fun projectionExcludesPrivateFieldsAndKeepsStructuredPublicSeat() = runBlocking {
        val result = snapshot()
        assertEquals("F12", result.plan.seat)
        assertEquals(listOf("Friend"), result.plan.companions)
        assertTrue(result.scheduled)
        assertFalse(result.pending)
        val encoded = result.plan.toString()
        listOf("secret-booking", "private/ticket.png", "secret-barcode", "secret-note", "private title notes", "999.99").forEach {
            assertFalse(encoded.contains(it))
        }
    }
    @Test fun onlyRelatedTitleOrOutingPendingWritesBlockSnapshot() = runBlocking {
        outbox.enqueue("viewing", "other", "upsert", JSONObject())
        assertFalse(snapshot().pending)
        outbox.enqueue("cinema_outing", "outing", "upsert", JSONObject())
        assertTrue(snapshot().pending)
        outbox.flush()
        outbox.enqueue("title", "title", "upsert", JSONObject())
        assertTrue(snapshot().pending)
        db.cinemaOutingDao().deleteById("outing")
        assertNull(readOutingPlanSnapshot("outing", db.cinemaOutingDao(), db.titleDao(), outbox))
    }
}
