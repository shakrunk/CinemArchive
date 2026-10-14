package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*
import work.kumarfamilynet.cinemarchive.core.model.*

@RunWith(RobolectricTestRunner::class)
class OutingScheduleCommandTest {
    private lateinit var db: LibraryDatabase
    private lateinit var outbox: MutationOutbox
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owner = OutingCommandFixture.owner
    private val title = OutingCommandFixture.title
    private val scope = TicketOwnerScope("project", owner)
    private var current = true
    private fun open() {
        db = Room.databaseBuilder(context, LibraryDatabase::class.java, "schedule.db")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity) = PushResult.Retry("Offline")
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db), outingOwnerScope = scope)
    }
    private fun commands() = OutingScheduleCommands(db, outbox, scope) { current }
    @Before fun setup() = runBlocking {
        context.deleteDatabase("schedule.db"); open()
        db.titleDao().upsertAll(listOf(TitleEntity(title, 42, "MOVIE", "Film", 2026, null, emptyList(),
            null, null, null, 90, null, "WATCHLIST", null, null, OutingCommandFixture.baseline, OutingCommandFixture.baseline)))
    }
    @After fun close() { db.close(); context.deleteDatabase("schedule.db") }
    private suspend fun save(opening: String, venue: String = "Cinema", notes: String? = null): String =
        commands().save(opening, Instant.parse("2099-10-08T19:00:00Z"), 20, 90,
            venue, emptyList(), CinemaFormat.IMAX, 12.5, SeatAssignment(null, null, emptyList()), null, notes)

    @Test fun capturedCreateSurvivesReopenAndAcknowledgedThenDeletedRetryNeverResurrects() = runBlocking {
        val opening = commands().prepare(title, null)
        val id = save(opening)
        val original = db.outboxDao().getPending().single()
        db.close(); open()
        assertEquals(id, save(opening))
        assertEquals(original, db.outboxDao().getPending().single())
        db.outboxDao().remove(original.id)
        db.cinemaOutingDao().deleteById(id)
        db.close(); open()
        assertEquals(id, save(opening))
        assertNull(db.cinemaOutingDao().getById(id))
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertNotNull(db.outingScheduleAdmissionDao().payload(original.id))
    }

    @Test fun conflictingReuseFailsWithoutReplacingAnyRowQueueOrAdmission() = runBlocking {
        val opening = commands().prepare(title, null)
        val id = save(opening)
        val row = db.cinemaOutingDao().getById(id)
        val entry = db.outboxDao().getPending().single()
        try { save(opening, "Different"); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(row, db.cinemaOutingDao().getById(id))
        assertEquals(entry, db.outboxDao().getPending().single())
    }

    @Test fun editUsesOriginalGuardButOverlaysOnlyUserChangedFieldsOnCurrentRow() = runBlocking {
        val original = OutingCommandFixture.entity().copy(companions = emptyList(), companionsJson = "[]")
        db.cinemaOutingDao().upsert(original)
        val opening = commands().prepare(title, original.id)
        val later = original.copy(notes = "Other device note", ticketImagePath = "private-ticket", updatedAt = "2026-10-08T18:00:00Z")
        db.cinemaOutingDao().upsert(later)
        save(opening, "New cinema", original.notes)
        val actual = db.cinemaOutingDao().getById(original.id)!!
        assertEquals("Other device note", actual.notes)
        assertEquals("private-ticket", actual.ticketImagePath)
        assertEquals(later.updatedAt, actual.updatedAt)
        val op = outingCommandOperations(db.outboxDao().getPending().single()).getJSONObject(0)
        assertEquals(original.updatedAt, op.getString("expectedUpdatedAt"))
        assertEquals(setOf("venue"), op.getJSONObject("values").keys().asSequence().toSet())
    }

    @Test fun editOfDeletedRowQueuesExactCasWithoutRecreatingAndUnknownGuardGoesToReview() = runBlocking {
        val original = OutingCommandFixture.entity().copy(companions = emptyList(), companionsJson = "[]")
        db.cinemaOutingDao().upsert(original)
        val opening = commands().prepare(title, original.id)
        db.cinemaOutingDao().deleteById(original.id)
        save(opening, "New cinema", original.notes)
        assertNull(db.cinemaOutingDao().getById(original.id))
        assertEquals(OUTING_COMMAND, db.outboxDao().getPending().single().operation)
        for (revision in listOf("unknown", "")) {
            db.outboxDao().getPending().forEach { db.outboxDao().remove(it.id) }
            db.cinemaOutingDao().upsert(original.copy(updatedAt = revision))
            val reviewOpening = commands().prepare(title, original.id)
            val captured = JSONObject(reviewOpening)
            assertEquals(revision, captured.getJSONObject("original").getString("updated_at"))
            assertTrue(captured.getBoolean("review"))
            assertTrue(captured.isNull("expectedUpdatedAt") && captured.isNull("expectedOperationId"))
            assertEquals(original.id, capturedScheduleInitial(reviewOpening, owner, title)!!.id)
            save(reviewOpening, "Reviewed cinema", original.notes)
            val pending = db.outboxDao().getPending().single()
            assertEquals("review", pending.operation)
            assertEquals(revision, db.cinemaOutingDao().getById(original.id)!!.updatedAt)
            save(reviewOpening, "Reviewed cinema", original.notes)
            assertEquals(pending, db.outboxDao().getPending().single())
        }
    }

    @Test fun ownerLossAndAdmissionFailureRollbackLocalRowAndQueue() = runBlocking {
        val opening = commands().prepare(title, null)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_admission BEFORE INSERT ON outing_schedule_admissions BEGIN SELECT RAISE(ABORT, 'test'); END")
        try { save(opening); fail() } catch (_: Exception) {}
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertNull(db.cinemaOutingDao().getById(JSONObject(opening).getString("outingId")))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_admission")
        current = false
        try { save(opening); fail() } catch (_: IllegalStateException) {}
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun alarmFailureAfterAdmissionRetriesWithoutDuplicatingThePlanOrChangingCommand() = runBlocking {
        var alarms = 0
        val repo = OutingsRepository(db.cinemaOutingDao(), db.viewingDao(), db.titleDao(), outbox, db.venueNoteDao(),
            alarmScheduler = object : OutingAlarmScheduler {
                override fun scheduleNext(nextTransitionAt: Instant?) { if (++alarms == 1) error("Alarm unavailable") }
            }, scheduleCommands = commands())
        val opening = repo.prepareOutingSchedule(title, null)
        suspend fun attempt() = repo.saveOutingSchedule(opening, Instant.parse("2099-10-08T19:00:00Z"), 20, 90,
            "Cinema", emptyList(), CinemaFormat.IMAX, 12.5, SeatAssignment(null, null, emptyList()), null, null)
        try { attempt(); fail() } catch (_: IllegalStateException) {}
        val original = db.outboxDao().getPending().single()
        val row = db.cinemaOutingDao().getById(original.entityId)
        attempt()
        assertEquals(2, alarms)
        assertEquals(listOf(original), db.outboxDao().getPending())
        assertEquals(row, db.cinemaOutingDao().getById(original.entityId))
    }

    @Test fun causalPredecessorCapturedBeforeAcknowledgeIsNeverRebased() = runBlocking {
        val original = OutingCommandFixture.entity().copy(companions = emptyList(), companionsJson = "[]")
        db.cinemaOutingDao().upsert(original)
        val prior = OutingCommandFixture.patch()
        db.outboxDao().enqueue(prior)
        val opening = commands().prepare(title, original.id)
        db.outboxDao().remove(prior.id)
        db.cinemaOutingDao().upsert(original.copy(updatedAt = "2026-10-08T20:00:00Z"))
        save(opening, "Next cinema", original.notes)
        assertEquals(prior.id, outingCommandOperations(db.outboxDao().getPending().single())
            .getJSONObject(0).getString("expectedOperationId"))
    }

    @Test fun schemaBoundariesRejectUnrepresentablePricesAndPreserveLongRuntime() = runBlocking {
        val opening = commands().prepare(title, null)
        suspend fun submit(previews: Int, runtime: Int, price: Double?) = commands().save(opening,
            Instant.parse("2099-10-08T19:00:00Z"), previews, runtime, "Cinema", emptyList(), null,
            price, SeatAssignment(null, null, emptyList()), null, null)
        for ((previews, runtime, price) in listOf(Triple(121, 90, 1.0), Triple(0, 0, 1.0),
            Triple(0, 90, 10000.0), Triple(0, 90, 1.001), Triple(0, 90, Double.NaN))) {
            try { submit(previews, runtime, price); fail() } catch (_: IllegalArgumentException) {}
            assertTrue(db.outboxDao().getPending().isEmpty())
        }
        val id = submit(120, Int.MAX_VALUE, 9999.99)
        assertEquals(Instant.parse("2099-10-08T19:00:00Z").plusSeconds((120L + Int.MAX_VALUE) * 60L).toString(),
            db.cinemaOutingDao().getById(id)!!.endsAt)
    }

    @Test fun pendingImportedOutingRetainsItsOperationGuardDespiteEmptyOriginalRevision() = runBlocking {
        val original = OutingCommandFixture.entity().copy(companions = emptyList(), companionsJson = "[]")
        val document = LibraryExportGraph(listOf(db.titleDao().getById(title)!!), outings = listOf(original))
            .exportDocument("2026-10-09")
        val prior = OutboxEntity(java.util.UUID.randomUUID().toString(), "title", title, BACKUP_IMPORT_COMMAND,
            importPayload(scope, document.getJSONArray("titles").getJSONObject(0),
                document.getJSONArray("outings").importObjects(), OutingCommandFixture.baseline), 1)
        val imported = checkedImportCommand(prior, scope).mapping.graph.outings.single()
        assertEquals("", imported.updatedAt)
        db.cinemaOutingDao().upsert(imported)
        db.outboxDao().enqueue(prior)
        val opening = commands().prepare(title, imported.id)
        assertEquals("", JSONObject(opening).getJSONObject("original").getString("updated_at"))
        assertEquals(imported.id, capturedScheduleInitial(opening, owner, title)!!.id)
        db.outboxDao().remove(prior.id)
        db.cinemaOutingDao().upsert(imported.copy(updatedAt = "2026-10-08T20:00:00Z"))
        save(opening, "New cinema", original.notes)
        val operation = outingCommandOperations(db.outboxDao().getPending().single()).getJSONObject(0)
        assertEquals(prior.id, operation.getString("expectedOperationId"))
        assertFalse(operation.has("expectedUpdatedAt"))
        assertEquals("2026-10-08T20:00:00Z", db.cinemaOutingDao().getById(imported.id)!!.updatedAt)
    }
}
