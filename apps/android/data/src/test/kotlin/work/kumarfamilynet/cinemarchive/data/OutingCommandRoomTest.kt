package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
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
class OutingCommandRoomTest {
    private lateinit var db: LibraryDatabase
    private lateinit var outbox: MutationOutbox
    private val pushed = mutableListOf<OutboxEntity>()
    private var deliver: suspend (OutboxEntity) -> PushResult = { PushResult.Retry("Offline") }
    private var rejectAck = false
    private fun configureOutbox() {
        outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity): PushResult { pushed += entry; return deliver(entry) }
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db),
            AppliedMutationHandler { entry, receipt ->
                OutingCommandApplier(db, OutingCommandFixture.owner).apply(entry, receipt)
                check(!rejectAck) { "Simulated local ACK failure" }
            })
    }
    private suspend fun seed() {
        db.titleDao().upsertAll(listOf(TitleEntity(OutingCommandFixture.title, 42, "MOVIE", "Film", 2026, null,
            emptyList(), null, null, null, 90, null, "WATCHLIST", null, null,
            OutingCommandFixture.baseline, OutingCommandFixture.baseline)))
        db.cinemaOutingDao().upsert(OutingCommandFixture.entity())
    }
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        configureOutbox(); seed()
    }
    @After fun tearDown() { db.close() }

    @Test fun acknowledgmentUsesFreshRemoteFieldsAndKeepsLaterLocalIntentAndHistory() = runBlocking {
        val first = OutingCommandFixture.patch()
        val later = OutingCommandFixture.patch("40000000-0000-4000-8000-000000000002", first.id, "Later local note")
        db.outboxDao().enqueue(first); db.outboxDao().enqueue(later)
        db.viewingDao().upsert(ViewingEntity("history", OutingCommandFixture.title, "2026-10-08", 4.5, "Keep", "Cinema"))
        deliver = { if (it.id == first.id) PushResult.Applied(OutingCommandFixture.envelope(first,
            OutingCommandFixture.applied(first).put("venue", "Current remote cinema").put("notes", "Current remote note")))
            else PushResult.Retry("Offline later") }
        outbox.flush()
        val row = db.cinemaOutingDao().getById(OutingCommandFixture.outing)!!
        assertEquals("Current remote cinema", row.venue)
        assertEquals("Later local note", row.notes)
        assertEquals(later.payloadJson, db.outboxDao().getPending().single().payloadJson)
        assertEquals(first.id, outingCommandOperations(db.outboxDao().getPending().single()).getJSONObject(0).getString("expectedOperationId"))
        assertEquals("Keep", db.viewingDao().getById("history")!!.notes)
    }

    @Test fun deletedRemotePlanStaysAbsentAndIndependentViewingSurvives() = runBlocking {
        val entry = OutingCommandFixture.patch(); db.outboxDao().enqueue(entry)
        db.viewingDao().upsert(ViewingEntity("history", OutingCommandFixture.title, "2026-10-08", 4.5, "Keep", "Cinema", outingId = OutingCommandFixture.outing))
        deliver = { PushResult.Applied(OutingCommandFixture.envelope(entry, null)) }
        outbox.flush()
        assertNull(db.cinemaOutingDao().getById(OutingCommandFixture.outing))
        assertNotNull(db.viewingDao().getById("history"))
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun laterLocalDeletionCannotBeResurrectedByAnOldReceiptOrCurrentRemoteRow() = runBlocking {
        val entry = OutingCommandFixture.patch(); db.outboxDao().enqueue(entry)
        db.cinemaOutingDao().deleteById(OutingCommandFixture.outing)
        deliver = { PushResult.Applied(OutingCommandFixture.envelope(entry)) }
        outbox.flush()
        assertNull(db.cinemaOutingDao().getById(OutingCommandFixture.outing))
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun localAckFailureRollsBackProjectionAndKeepsExactCommandForRetry() = runBlocking {
        val entry = OutingCommandFixture.patch(); db.outboxDao().enqueue(entry)
        deliver = { PushResult.Applied(OutingCommandFixture.envelope(entry)) }
        rejectAck = true; outbox.flush()
        assertEquals("Cinema", db.cinemaOutingDao().getById(OutingCommandFixture.outing)!!.venue)
        assertEquals(entry.payloadJson, db.outboxDao().getPending().single().payloadJson)
        rejectAck = false; configureOutbox(); outbox.flush()
        assertEquals("Saved cinema", db.cinemaOutingDao().getById(OutingCommandFixture.outing)!!.venue)
        assertEquals(listOf(entry.id, entry.id), pushed.map { it.id })
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun definiteConflictPausesOriginalAndDependentCommandsWithoutOverwritingRemote() = runBlocking {
        val first = OutingCommandFixture.patch()
        val later = OutingCommandFixture.patch("40000000-0000-4000-8000-000000000002", first.id, "Later note")
        db.outboxDao().enqueue(first); db.outboxDao().enqueue(later)
        deliver = { PushResult.Review("Compare current plan") }
        outbox.flush()
        assertEquals(listOf(first.id), pushed.map { it.id })
        val pending = db.outboxDao().getPending()
        assertEquals("review", pending.first().operation)
        assertEquals(first.payloadJson, pending.first().payloadJson)
        assertEquals(later.payloadJson, pending.last().payloadJson)
    }

    @Test fun serializedCausalRequestSurvivesRealDatabaseCloseAndReopen() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, "oc.db")
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        db = open(); configureOutbox(); seed()
        val repo = OutingsRepository(db.cinemaOutingDao(), db.viewingDao(), db.titleDao(), outbox, db.venueNoteDao())
        repo.cancelOuting(OutingCommandFixture.outing)
        repo.dismissFollowUp(OutingCommandFixture.outing)
        val before = db.outboxDao().getPending()
        assertEquals(before.first().id, outingCommandOperations(before.last()).getJSONObject(0).getString("expectedOperationId"))
        db.close(); db = open(); configureOutbox()
        assertEquals(before, db.outboxDao().getPending())
        outbox.flush()
        assertEquals(before.first().id, pushed.single().id)
        assertEquals(before.first().payloadJson, pushed.single().payloadJson)
    }

    @Test fun unknownCommandRequiresOriginalConfirmationBeforeReplacementOrDiscard() = runBlocking {
        val entry = OutingCommandFixture.patch(); db.outboxDao().enqueue(entry); outbox.flush()
        val archive = Archive()
        var confirmed: OutboxEntity? = null
        val remote = object : OutingRecoveryRemote {
            override suspend fun fetch(session: SupabaseSession, outingId: String) = OutingCommandFixture.row()
            override suspend fun apply(session: SupabaseSession, outingId: String, attempt: JSONObject): JSONObject = error("Must confirm original")
            override suspend fun confirmCommand(session: SupabaseSession, entry: OutboxEntity): PushResult {
                confirmed = entry
                return PushResult.Applied(OutingCommandFixture.envelope(entry))
            }
        }
        fun repo() = OutingRecoveryRepository(OutingCommandFixture.owner,
            { SupabaseSession("token", OutingCommandFixture.owner) }, db.outboxDao(), db.cinemaOutingDao(), db.titleDao(), outbox, archive, remote)
        assertTrue(repo().review(entry.id).pendingAttempt)
        try { repo().discard(entry.id); fail("Unknown delivery must be confirmed") } catch (_: IllegalStateException) {}
        assertEquals(OutingRecoveryOutcome.CONFIRMED, repo().apply(entry.id, "different", setOf("notes")))
        assertEquals(entry.id, confirmed!!.id)
        assertEquals(entry.payloadJson, confirmed!!.payloadJson)
        assertTrue(db.outboxDao().getPending().isEmpty())
        assertTrue(repo().items().single().resolved)
    }

    @Test fun definitiveOriginalConflictUnlocksExplicitFieldReviewWithoutLosingOriginal() = runBlocking {
        val entry = OutingCommandFixture.patch(); db.outboxDao().enqueue(entry); outbox.flush()
        val archive = Archive()
        val remote = object : OutingRecoveryRemote {
            override suspend fun fetch(session: SupabaseSession, outingId: String) = OutingCommandFixture.row()
            override suspend fun apply(session: SupabaseSession, outingId: String, attempt: JSONObject): JSONObject = error("No replacement selected")
            override suspend fun confirmCommand(session: SupabaseSession, entry: OutboxEntity) = PushResult.Review("Changed remotely")
        }
        val repo = OutingRecoveryRepository(OutingCommandFixture.owner,
            { SupabaseSession("token", OutingCommandFixture.owner) }, db.outboxDao(), db.cinemaOutingDao(), db.titleDao(), outbox, archive, remote)
        assertTrue(repo.review(entry.id).pendingAttempt)
        assertEquals(OutingRecoveryOutcome.CHANGED, repo.apply(entry.id, null, emptySet()))
        assertFalse(repo.review(entry.id).pendingAttempt)
        assertEquals("review", db.outboxDao().getPending().single().operation)
        assertEquals(entry.payloadJson, JSONObject(repo.exportOriginal(entry.id)).getJSONObject("original").getString("payloadJson"))
    }

    @Test fun confirmingUnknownCommandCannotRestoreAnOutingDeletedLocallyWhileOffline() = runBlocking {
        val entry = OutingCommandFixture.patch(); db.outboxDao().enqueue(entry); outbox.flush()
        val archive = Archive()
        val remote = object : OutingRecoveryRemote {
            override suspend fun fetch(session: SupabaseSession, outingId: String) = OutingCommandFixture.row()
            override suspend fun apply(session: SupabaseSession, outingId: String, attempt: JSONObject): JSONObject = error("Must confirm original")
            override suspend fun confirmCommand(session: SupabaseSession, entry: OutboxEntity) = PushResult.Applied(OutingCommandFixture.envelope(entry))
        }
        val repo = OutingRecoveryRepository(OutingCommandFixture.owner,
            { SupabaseSession("token", OutingCommandFixture.owner) }, db.outboxDao(), db.cinemaOutingDao(), db.titleDao(), outbox, archive, remote)
        repo.review(entry.id)
        db.cinemaOutingDao().deleteById(OutingCommandFixture.outing)
        assertEquals(OutingRecoveryOutcome.CONFIRMED, repo.apply(entry.id, null, emptySet()))
        assertNull(db.cinemaOutingDao().getById(OutingCommandFixture.outing))
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun missingRemoteWithLaterIntentKeepsLocalDraftWithoutIssuingACreate() = runBlocking {
        val first = OutingCommandFixture.patch()
        val later = OutingCommandFixture.patch("40000000-0000-4000-8000-000000000002", first.id, "Keep local draft")
        db.outboxDao().enqueue(first); db.outboxDao().enqueue(later)
        db.cinemaOutingDao().upsert(OutingCommandFixture.entity().copy(notes = "Keep local draft"))
        deliver = { if (it.id == first.id) PushResult.Applied(OutingCommandFixture.envelope(first, null))
            else PushResult.Review("The current outing was deleted") }
        outbox.flush()
        assertEquals("Keep local draft", db.cinemaOutingDao().getById(OutingCommandFixture.outing)!!.notes)
        assertEquals("review", db.outboxDao().getPending().single().operation)
        assertEquals(later.payloadJson, db.outboxDao().getPending().single().payloadJson)
        assertTrue(pushed.all { outingCommandOperations(it).getJSONObject(0).getString("action") == "update" })
    }

    private class Archive : OutingRecoveryArchive {
        override val records = MutableStateFlow<Map<String, String>>(emptyMap())
        override suspend fun put(id: String, record: String) { records.value = records.value + (id to record) }
    }
}
