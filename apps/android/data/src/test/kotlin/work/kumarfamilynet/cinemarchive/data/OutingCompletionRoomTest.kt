package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import work.kumarfamilynet.cinemarchive.core.database.*

@RunWith(RobolectricTestRunner::class)
class OutingCompletionRoomTest {
    private lateinit var db: LibraryDatabase
    private val owner = OutingCommandFixture.owner
    private val title = OutingCommandFixture.title
    private val outing = OutingCommandFixture.outing
    private val provisional = "50000000-0000-4000-8000-000000000001"
    private val canonical = "50000000-0000-4000-8000-000000000002"
    private val revision = "2026-10-08T12:01:00Z"
    private val command = OutingCompletionCommand(outing, title, provisional, OutingCommandFixture.baseline, null, "America/Denver")
    private val completion get() = OutboxEntity(OutingCommandFixture.operation, "outing_completion", outing,
        OUTING_COMPLETION, JSONObject().put(COMPLETION_COMMAND_DATA, command.persisted()).toString(), 10)
    private fun id(n: Int) = "60000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
    private fun viewing() = JSONObject().put("id", canonical).put("user_id", owner).put("title_id", title)
        .put("outing_id", outing).put("updated_at", revision).put("viewed_at", "2026-10-08")
        .put("notes", "Current server note").put("rating", JSONObject.NULL).put("venue", "Current cinema").put("companions", JSONArray())
    private fun outingRow() = OutingCommandFixture.row().put("status", "completed").put("completed_viewing_id", canonical)
        .put("venue", "Current cinema").put("notes", "Current outing note").put("updated_at", revision)
    private fun envelope(currentViewing: JSONObject? = viewing(), currentOuting: JSONObject? = outingRow(),
        viewingProof: Boolean = true, outingProof: Boolean = true): JSONObject {
        val effects = JSONArray().put(JSONObject().put("table", "cinema_outings").put("key", JSONObject().put("id", outing))
            .put("row", JSONObject().put("id", outing).put("user_id", owner).put("title_id", title)
                .put("updated_at", if (outingProof) revision else JSONObject.NULL)))
        if (viewingProof) effects.put(JSONObject().put("table", "viewings").put("key", JSONObject().put("id", canonical))
            .put("row", JSONObject().put("id", canonical).put("user_id", owner).put("title_id", title).put("outing_id", outing).put("updated_at", revision)))
        val receipt = JSONObject().put("operationId", completion.id).put("outingId", outing).put("canonicalViewingId", canonical)
            .put("request", command.request()).put("status", "already_completed").put("completionOutingVersion", if (outingProof) revision else JSONObject.NULL)
            .put("rows", effects).put("outing", currentOuting ?: JSONObject.NULL)
            .put("viewing", if (currentOuting != null && currentViewing?.optString("outing_id") == outing) currentViewing else JSONObject.NULL)
            .put("title", if (currentOuting == null) JSONObject.NULL else JSONObject().put("id", title).put("status", "watched").put("updated_at", revision))
        return JSONObject().put("receipt", receipt).put("currentViewing", currentViewing ?: JSONObject.NULL)
    }
    private fun awaiting(n: Int, type: String = "viewing", action: String = "update", fields: JSONObject = JSONObject().put("notes", "Saved note")) =
        OutboxEntity(id(n), type, if (type == "viewing") provisional else outing, AWAITING_COMPLETION,
            awaitingCompletionPayload(completion, type, action, fields).toString(), 100L - n)
    private suspend fun seed() {
        db.titleDao().upsertAll(listOf(TitleEntity(title, 42, "MOVIE", "Film", 2026, null,
            emptyList(), null, null, null, 90, null, "WATCHED", null, null,
            OutingCommandFixture.baseline, OutingCommandFixture.baseline)))
        db.cinemaOutingDao().upsert(OutingCommandFixture.entity().copy(status = "COMPLETED", completedViewingId = provisional))
        db.viewingDao().upsert(ViewingEntity(provisional, title, "2026-10-07", null, "Offline draft", "Old cinema", outingId = outing))
        db.outboxDao().enqueue(completion)
    }
    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        seed()
    }
    @After fun tearDown() { db.close() }
    private suspend fun apply(response: JSONObject = envelope()) = db.withTransaction {
        OutingCompletionApplier(db, owner).apply(completion, response)
        db.outboxDao().remove(completion.id)
    }

    @Test fun aliasConvertsOnlyBoundDraftAndPreservesIndependentHistory() = runBlocking {
        db.viewingDao().upsert(ViewingEntity(id(99), title, "2020-01-01", 4.5, "Independent rewatch", null, outingId = outing))
        apply()
        assertNull(db.viewingDao().getById(provisional))
        assertEquals("Current server note", db.viewingDao().getById(canonical)!!.notes)
        assertEquals("Independent rewatch", db.viewingDao().getById(id(99))!!.notes)
        val alias = db.viewingCompletionAliasDao().byProvisionalId(provisional)!!
        assertEquals(canonical, alias.canonicalViewingId)
        assertEquals(revision, alias.canonicalViewingVersion)
        assertEquals(canonical, db.cinemaOutingDao().getById(outing)!!.completedViewingId)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun conversionsRetainFifoIdsOriginalIntentAndCausalChains() = runBlocking {
        val first = awaiting(1)
        val unrelated = OutboxEntity(id(2), "list", id(90), "update", "{}", -100)
        val second = awaiting(3, fields = JSONObject().put("rating", 4.5))
        val plan = awaiting(4, "cinema_outing", fields = JSONObject().put("notes", "Saved plan note"))
        listOf(first, unrelated, second, plan).forEach { db.outboxDao().enqueue(it) }
        apply()
        val pending = db.outboxDao().getPending()
        assertEquals(listOf(first.id, unrelated.id, second.id, plan.id), pending.map { it.id })
        assertEquals(completion.id, viewingCommandOperations(pending[0]).getJSONObject(0).getString("expectedOperationId"))
        assertEquals(first.id, viewingCommandOperations(pending[2]).getJSONObject(0).getString("expectedOperationId"))
        assertEquals(completion.id, outingCommandOperations(pending[3]).getJSONObject(0).getString("expectedOperationId"))
        assertEquals(canonical, pending[0].entityId)
        assertTrue(sameCommandJson(JSONObject(first.payloadJson), JSONObject(pending[0].payloadJson).getJSONObject("completionSource")))
        assertEquals("Saved note", db.viewingDao().getById(canonical)!!.notes)
        assertEquals(4.5, db.viewingDao().getById(canonical)!!.rating)
        assertEquals("Current cinema", db.viewingDao().getById(canonical)!!.venue)
        assertEquals("Saved plan note", db.cinemaOutingDao().getById(outing)!!.notes)
    }

    @Test fun exactPendingDeleteBecomesCanonicalDeleteWithoutDeletingOtherEvents() = runBlocking {
        val deletion = awaiting(1, action = "delete", fields = JSONObject())
        db.outboxDao().enqueue(deletion)
        db.viewingDao().deleteById(provisional)
        db.viewingDao().upsert(ViewingEntity(id(99), title, "2020-01-01", null, "Keep", null))
        apply()
        val pending = db.outboxDao().getPending().single()
        val operation = viewingCommandOperations(pending).getJSONObject(0)
        assertEquals("delete", operation.getString("action"))
        assertEquals(canonical, operation.getJSONObject("key").getString("id"))
        assertFalse(operation.has("values"))
        assertNull(db.viewingDao().getById(canonical))
        assertEquals("Keep", db.viewingDao().getById(id(99))!!.notes)
    }

    @Test fun missingCanonicalRetainsReviewIntentAndNeverRecreatesIt() = runBlocking {
        val pending = awaiting(1)
        db.outboxDao().enqueue(pending)
        apply(envelope(currentViewing = null))
        val retained = db.outboxDao().getPending().single()
        assertEquals("review", retained.operation)
        assertEquals(pending.payloadJson, retained.payloadJson)
        assertEquals(provisional, retained.entityId)
        assertNull(db.viewingDao().getById(canonical))
        assertNotNull(db.viewingDao().getById(provisional))
        assertEquals(canonical, db.viewingCompletionAliasDao().byProvisionalId(provisional)!!.canonicalViewingId)
    }

    @Test fun unknownOriginalVersionsDoNotAdoptCurrentRevisions() = runBlocking {
        val view = awaiting(1)
        val plan = awaiting(2, "cinema_outing", fields = JSONObject().put("venue", "Saved cinema"))
        db.outboxDao().enqueue(view); db.outboxDao().enqueue(plan)
        apply(envelope(viewingProof = false, outingProof = false))
        assertTrue(db.outboxDao().getPending().all { it.operation == "review" })
        assertEquals(listOf(view.payloadJson, plan.payloadJson), db.outboxDao().getPending().map { it.payloadJson })
        assertNull(db.viewingCompletionAliasDao().byProvisionalId(provisional)!!.canonicalViewingVersion)
        assertEquals("Offline draft", db.viewingDao().getById(provisional)!!.notes)
        assertEquals("Cinema", db.cinemaOutingDao().getById(outing)!!.venue)
    }

    @Test fun removedOutingPreservesUnlinkedCanonicalHistory() = runBlocking {
        apply(envelope(currentOuting = null, currentViewing = viewing().put("outing_id", JSONObject.NULL)))
        assertNull(db.cinemaOutingDao().getById(outing))
        assertNull(db.viewingDao().getById(provisional))
        assertEquals("Current server note", db.viewingDao().getById(canonical)!!.notes)
        assertNull(db.viewingDao().getById(canonical)!!.outingId)
    }

    @Test fun localTitleDeletionCannotBeResurrectedByReceipt() = runBlocking {
        db.titleDao().deleteById(title)
        apply()
        assertNull(db.titleDao().getById(title))
        assertNull(db.viewingDao().getById(canonical))
        assertNotNull(db.viewingCompletionAliasDao().byProvisionalId(provisional))
    }

    @Test fun potentiallyDispatchedCommandRemainsByteForByteUnchanged() = runBlocking {
        val pending = OutboxEntity(id(1), "viewing", provisional, VIEWING_COMMAND,
            viewingCommandPayload(provisional, title, "update", JSONObject().put("notes", "Unknown delivery"), revision, null).toString(), 1)
        db.outboxDao().enqueue(pending)
        assertTrue(runCatching { apply() }.isFailure)
        assertEquals(pending, db.outboxDao().getPending().last())
        assertNull(db.viewingCompletionAliasDao().byProvisionalId(provisional))
        assertNotNull(db.viewingDao().getById(provisional))
    }

    @Test fun attemptedAwaitingIntentAndChangedPayloadCannotBeConverted() = runBlocking {
        val pending = awaiting(1).copy(attemptCount = 1)
        db.outboxDao().enqueue(pending)
        assertTrue(runCatching { apply() }.isFailure)
        assertEquals(0, db.completionQueueDao().resolveAwaiting(pending.id, pending.payloadJson, canonical, VIEWING_COMMAND, "{}", null))
        db.outboxDao().remove(pending.id)
        val clean = awaiting(2); db.outboxDao().enqueue(clean)
        assertEquals(0, db.completionQueueDao().resolveAwaiting(clean.id, "changed", canonical, VIEWING_COMMAND, "{}", null))
        assertEquals(clean, db.outboxDao().getPending().last())
    }

    @Test fun wrongOwnerOrRebindingCannotPartiallyApply() = runBlocking {
        val wrong = envelope()
        wrong.getJSONObject("currentViewing").put("user_id", id(90))
        assertTrue(runCatching { apply(wrong) }.isFailure)
        assertNull(db.viewingCompletionAliasDao().byProvisionalId(provisional))
        db.viewingCompletionAliasDao().insert(ViewingCompletionAliasEntity(provisional, id(90), title, outing, completion.id, revision))
        assertTrue(runCatching { apply() }.isFailure)
        assertNotNull(db.viewingDao().getById(provisional))
        assertNull(db.viewingDao().getById(canonical))
        assertEquals(completion, db.outboxDao().getPending().single())
    }

    @Test fun ackFailureRollsBackAliasQueueAndProjectionThenRetryUsesSameCommand() = runBlocking {
        val pending = awaiting(1); db.outboxDao().enqueue(pending)
        var reject = true
        val pushed = mutableListOf<OutboxEntity>()
        val outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity): PushResult {
                pushed += entry
                return if (entry.id == completion.id) PushResult.Applied(envelope()) else PushResult.Retry("Offline")
            }
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db), AppliedMutationHandler { entry, response ->
            OutingCompletionApplier(db, owner).apply(entry, response)
            check(!reject) { "Simulated ACK failure" }
        })
        outbox.flush()
        assertNull(db.viewingCompletionAliasDao().byProvisionalId(provisional))
        assertEquals(pending, db.outboxDao().getPending().last())
        assertNotNull(db.viewingDao().getById(provisional))
        assertNull(db.viewingDao().getById(canonical))
        reject = false; outbox.flush()
        assertEquals(listOf(completion.id, completion.id, pending.id), pushed.map { it.id })
        assertEquals(canonical, pushed.last().entityId)
        assertEquals(VIEWING_COMMAND, pushed.last().operation)
        assertNotNull(db.viewingCompletionAliasDao().byProvisionalId(provisional))
    }

    @Test fun acknowledgedIdentityAndConvertedIntentSurviveReopen() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun open() = Room.databaseBuilder(context, LibraryDatabase::class.java, "ca.db")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        db = open(); seed()
        db.outboxDao().enqueue(awaiting(1)); apply()
        val before = db.outboxDao().getPending().single()
        db.close(); db = open()
        assertEquals(before, db.outboxDao().getPending().single())
        assertEquals(canonical, db.viewingCompletionAliasDao().byProvisionalId(provisional)!!.canonicalViewingId)
        assertEquals(completion.id, viewingCommandOperations(before).getJSONObject(0).getString("expectedOperationId"))
    }
}
