package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.PostShowOpening
import work.kumarfamilynet.cinemarchive.core.model.TicketOwnerScope
import work.kumarfamilynet.cinemarchive.core.model.ViewingDraft

@RunWith(RobolectricTestRunner::class)
class OutingLifecycleAdmissionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LibraryDatabase
    private val owner = OutingCommandFixture.owner
    private val titleId = OutingCommandFixture.title
    private val outingId = OutingCommandFixture.outing
    private val ownerScope = TicketOwnerScope("https://project.example", owner)
    private val now = Instant.parse("2026-10-09T03:01:00Z")
    private val historicalId = "50000000-0000-4000-8000-000000000099"
    private val linkedId = "50000000-0000-4000-8000-000000000001"
    private fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, "life.db")
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
    private fun outbox(scope: TicketOwnerScope = ownerScope) = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
        override suspend fun push(entry: OutboxEntity): PushResult = error("Admission must not send network requests")
    }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db), outingOwnerScope = scope)
    private fun repository(active: () -> Boolean = { true }) = OutingLifecycleRepository(db, outbox(), owner, active)
    @Before fun setUp() = runBlocking {
        context.deleteDatabase("life.db")
        db = open()
        db.titleDao().upsertAll(listOf(TitleEntity(titleId, 42, "MOVIE", "Film", 2026, null, emptyList(), null, null, null, 90,
            null, "WATCHLIST", null, null, OutingCommandFixture.baseline, OutingCommandFixture.baseline)))
        db.cinemaOutingDao().upsert(OutingCommandFixture.entity().copy(showtime = "2026-10-09T01:30:00Z", endsAt = "2026-10-09T03:00:00Z",
            companionsJson = "[{\"name\":\"Sam\",\"friendUserId\":\"friend\",\"extra\":false}]"))
    }
    @After fun tearDown() { db.close(); context.deleteDatabase("life.db") }

    @Test fun completionIsOneDurableAdmissionAcrossReopenAndTimezoneChange() = runBlocking {
        val transitions = repository().completeDue(now, ZoneId.of("America/Denver"))
        val transition = transitions.single()
        val command = db.outboxDao().getPending().single()
        val payload = JSONObject(command.payloadJson)
        assertEquals(OUTING_COMPLETION, command.operation)
        assertEquals(owner, payload.getString("ownerId"))
        assertEquals("America/Denver", completionCommand(command).timezone)
        assertEquals("2026-10-08", db.viewingDao().getById(transition.viewingId)!!.date)
        assertEquals(db.cinemaOutingDao().getById(outingId)!!.companionsJson, db.viewingDao().getById(transition.viewingId)!!.companionsJson)
        assertEquals("WATCHED", db.titleDao().getById(titleId)!!.status)
        assertEquals(OutingCommandFixture.baseline, db.titleDao().getById(titleId)!!.updatedAt)
        assertTrue(outingLifecycleProtectionKeys(listOf(command), owner).containsAll(setOf("title:$titleId", "cinema_outing:$outingId", "viewing:${transition.viewingId}")))
        db.close(); db = open()
        assertTrue(repository().completeDue(now.plusSeconds(600), ZoneId.of("UTC")).isEmpty())
        assertEquals(command, db.outboxDao().getPending().single())
        assertEquals(transition.viewingId, db.cinemaOutingDao().getById(outingId)!!.completedViewingId)
        assertEquals("2026-10-08", db.viewingDao().getById(transition.viewingId)!!.date)
    }

    @Test fun futureAndAlreadyCancelledPlansProduceNoHistoryOrCommands() = runBlocking {
        assertTrue(repository().completeDue(Instant.parse("2026-10-09T02:59:59Z"), ZoneId.of("UTC")).isEmpty())
        val outing = db.cinemaOutingDao().getById(outingId)!!
        db.cinemaOutingDao().upsert(outing.copy(status = "CANCELLED"))
        assertTrue(repository().completeDue(now, ZoneId.of("UTC")).isEmpty())
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertNull(db.viewingDao().getByOutingId(outingId))
        assertEquals("WATCHLIST", db.titleDao().getById(titleId)!!.status)
    }

    @Test fun independentLinkedHistoryForcesReviewAndNeverBecomesCompletionProof() = runBlocking {
        val independent = ViewingEntity(historicalId, titleId, "2020-01-01", 4.5, "Independent historical viewing", "Original venue", outingId = outingId)
        db.viewingDao().upsert(independent)
        val transition = repository().completeDue(now, ZoneId.of("UTC")).single()
        val pending = db.outboxDao().getPending().single()
        assertEquals("review", pending.operation)
        assertEquals("outing_completion", pending.entityType)
        assertTrue(JSONObject(pending.payloadJson).getString("reviewReason").contains("independent history"))
        assertEquals(independent, db.viewingDao().getById(historicalId))
        assertNotEquals(historicalId, transition.viewingId)
        val outing = db.cinemaOutingDao().getById(outingId)!!
        val provisional = db.viewingDao().getById(transition.viewingId)!!
        val captured = opening(outing, provisional)
        repository().revert(captured)
        assertEquals("review", db.outboxDao().getPending().last().operation)
        assertEquals(independent, db.viewingDao().getById(historicalId))
        assertNull(db.viewingDao().getById(transition.viewingId))
        assertEquals("WATCHED", db.titleDao().getById(titleId)!!.status)
    }

    @Test fun ownerChangeAtFinalFenceRollsBackWholeCompletionAndWrongOwnerCannotAdmit() = runBlocking {
        var checks = 0
        val result = runCatching { repository { ++checks < 3 }.completeDue(now, ZoneId.of("UTC")) }
        assertTrue(result.isFailure)
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertEquals("SCHEDULED", db.cinemaOutingDao().getById(outingId)!!.status)
        assertNull(db.viewingDao().getByOutingId(outingId))
        assertEquals("WATCHLIST", db.titleDao().getById(titleId)!!.status)
        val wrong = OutingLifecycleRepository(db, outbox(ownerScope.copy(ownerId = "10000000-0000-4000-8000-000000000002")), owner) { true }
        assertTrue(runCatching { wrong.completeDue(now) }.isFailure)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun capturedReversalRetriesExactCommandAndNeverInventsTitleUndoOrDeletesOtherRewatch() = runBlocking {
        val outing = db.cinemaOutingDao().getById(outingId)!!.copy(status = "COMPLETED", completedViewingId = linkedId, previousStatus = "WATCHLIST")
        val viewing = ViewingEntity(linkedId, titleId, "2026-10-08", null, "Linked", "Cinema", outingId = outingId, updatedAt = OutingCommandFixture.baseline)
        val independent = ViewingEntity(historicalId, titleId, "2020-01-01", 4.0, "Keep rewatch", null)
        db.cinemaOutingDao().upsert(outing); db.viewingDao().upsert(viewing); db.viewingDao().upsert(independent)
        val captured = opening(outing, viewing)
        val title = db.titleDao().getById(titleId)!!.copy(status = "WATCHING", updatedAt = "2026-10-09T04:00:00Z")
        db.titleDao().upsertAll(listOf(title))
        repository().revert(captured)
        val first = db.outboxDao().getPending().single()
        assertEquals(OUTING_REVERSAL, first.operation)
        repository().revert(captured)
        assertEquals(first, db.outboxDao().getPending().single())
        assertEquals(title, db.titleDao().getById(titleId))
        assertEquals(independent, db.viewingDao().getById(historicalId))
        assertNull(db.viewingDao().getById(linkedId))
        assertEquals("MISSED", db.cinemaOutingDao().getById(outingId)!!.status)
        db.outboxDao().remove(first.id)
        repository().revert(captured)
        val retryAfterAck = db.outboxDao().getPending().single()
        assertEquals(first.id, retryAfterAck.id); assertEquals(first.payloadJson, retryAfterAck.payloadJson)
        assertEquals(title, db.titleDao().getById(titleId))
    }

    @Test fun ratedViewingAndForeignCapturedOwnerCannotBeReversed() = runBlocking {
        val outing = db.cinemaOutingDao().getById(outingId)!!.copy(status = "COMPLETED", completedViewingId = linkedId)
        val viewing = ViewingEntity(linkedId, titleId, "2026-10-08", null, null, null, outingId = outingId, updatedAt = OutingCommandFixture.baseline)
        db.cinemaOutingDao().upsert(outing); db.viewingDao().upsert(viewing)
        val captured = opening(outing, viewing)
        db.viewingDao().upsert(viewing.copy(rating = 4.5))
        assertTrue(runCatching { repository().revert(captured) }.isFailure)
        db.viewingDao().upsert(viewing)
        val foreign = captured.copy(reversalContext = JSONObject(captured.reversalContext!!).put("ownerId", "10000000-0000-4000-8000-000000000002").toString())
        assertTrue(runCatching { repository().revert(foreign) }.isFailure)
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertEquals(viewing, db.viewingDao().getById(linkedId))
        assertEquals("COMPLETED", db.cinemaOutingDao().getById(outingId)!!.status)
    }

    private suspend fun opening(outing: CinemaOutingEntity, viewing: ViewingEntity) = PostShowOpening(
        ViewingDraft(viewing.id, viewing.date, viewing.rating, viewing.notes, viewing.venue, viewing.companions),
        checkNotNull(captureReversalContext(outing, viewing, db.outboxDao().getPending(), ownerScope)))
}
