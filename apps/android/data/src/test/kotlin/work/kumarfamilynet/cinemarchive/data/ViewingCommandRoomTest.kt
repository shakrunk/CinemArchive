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
class ViewingCommandRoomTest {
    private lateinit var db: LibraryDatabase
    private lateinit var tx: RoomTransactor
    private lateinit var applier: ViewingCommandApplier
    private val entry get() = ViewingCommandFixture.entry()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
        tx = RoomTransactor(db)
        applier = ViewingCommandApplier(db, ViewingCommandFixture.owner)
        db.titleDao().upsertAll(listOf(TitleEntity(ViewingCommandFixture.title, 42, "MOVIE", "Film", 2026, null,
            emptyList(), null, null, null, 90, null, "WATCHED", null, null,
            ViewingCommandFixture.baseline, ViewingCommandFixture.baseline)))
        db.viewingDao().upsert(ViewingCommandFixture.applied(entry).toCompletionViewing())
        db.outboxDao().enqueue(entry)
    }
    @After fun close() { db.close() }
    private suspend fun ack(envelope: JSONObject = ViewingCommandFixture.envelope(entry)) = tx.run {
        applier.apply(entry, envelope)
        db.outboxDao().remove(entry.id)
    }

    @Test fun historicalReceiptCannotReplaceLaterRemoteNotesOrRating() = runBlocking {
        val current = ViewingCommandFixture.applied(entry).put("notes", "Later remote note").put("rating", 4.5)
            .put("updated_at", "2026-10-08T12:00:00Z")
        ack(ViewingCommandFixture.envelope(entry, current))
        val local = db.viewingDao().getById(entry.entityId)!!
        assertEquals("Later remote note", local.notes)
        assertEquals(4.5, local.rating!!, 0.0)
        assertEquals("2026-10-08T12:00:00Z", local.updatedAt)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    @Test fun laterNarrowIntentOverlaysCurrentFieldsWithoutChangingItsCausalCommand() = runBlocking {
        val later = ViewingCommandFixture.entry(fields = JSONObject().put("notes", "Offline follow-up"),
            predecessor = entry.id, id = ViewingCommandFixture.nextOperation).copy(createdAt = 0)
        db.outboxDao().enqueue(later)
        db.viewingDao().upsert(db.viewingDao().getById(entry.entityId)!!.copy(notes = "Offline follow-up"))
        val current = ViewingCommandFixture.applied(entry).put("venue", "New remote venue").put("rating", 4)
        ack(ViewingCommandFixture.envelope(entry, current))
        val local = db.viewingDao().getById(entry.entityId)!!
        assertEquals("Offline follow-up", local.notes)
        assertEquals("New remote venue", local.venue)
        assertEquals(4.0, local.rating!!, 0.0)
        assertEquals(later, db.outboxDao().getPending().single())
    }

    @Test fun deletedRemoteEventRemainsAbsentButOriginalPendingDraftSurvives() = runBlocking {
        val later = ViewingCommandFixture.entry(fields = JSONObject().put("notes", "Preserved draft"),
            predecessor = entry.id, id = ViewingCommandFixture.nextOperation)
        db.outboxDao().enqueue(later)
        ack(ViewingCommandFixture.envelope(entry, null))
        assertNull(db.viewingDao().getById(entry.entityId))
        assertEquals(later, db.outboxDao().getPending().single())
    }

    @Test fun confirmedMissingEventClearsOnlyItsExactOutingPointer() = runBlocking {
        val linked = viewingLinkedOuting("linked", entry.entityId)
        val laterLink = viewingLinkedOuting("later", ViewingCommandFixture.nextOperation)
        db.cinemaOutingDao().upsertAll(listOf(linked, laterLink))
        ack(ViewingCommandFixture.envelope(entry, null))
        assertEquals(linked.copy(completedViewingId = null), db.cinemaOutingDao().getById(linked.id))
        assertEquals(laterLink, db.cinemaOutingDao().getById(laterLink.id))
    }

    @Test fun laterLocalDeleteNeverResurrectsAndLeavesOtherRewatchesAlone() = runBlocking {
        val independent = db.viewingDao().getById(entry.entityId)!!.copy(id = ViewingCommandFixture.nextOperation, notes = "Independent history")
        db.viewingDao().upsert(independent)
        val deletion = ViewingCommandFixture.entry("delete", JSONObject(), predecessor = entry.id, id = ViewingCommandFixture.nextOperation)
        db.outboxDao().enqueue(deletion)
        db.viewingDao().deleteById(entry.entityId)
        ack()
        assertNull(db.viewingDao().getById(entry.entityId))
        assertEquals(independent, db.viewingDao().getById(independent.id))
        assertEquals(deletion, db.outboxDao().getPending().single())
    }

    @Test fun malformedLaterIntentDoesNotEraseItsOptimisticDraftOrQueue() = runBlocking {
        val local = db.viewingDao().getById(entry.entityId)!!.copy(notes = "Uninterpreted later draft")
        db.viewingDao().upsert(local)
        val later = entry.copy(id = ViewingCommandFixture.nextOperation, operation = "review", payloadJson = "{unreadable")
        db.outboxDao().enqueue(later)
        ack()
        assertEquals(local, db.viewingDao().getById(entry.entityId))
        assertEquals(later, db.outboxDao().getPending().single())
    }

    @Test fun missingParentCannotBeRecreatedFromAReceipt() = runBlocking {
        db.titleDao().deleteById(ViewingCommandFixture.title)
        ack()
        assertNull(db.titleDao().getById(ViewingCommandFixture.title))
        assertNull(db.viewingDao().getById(entry.entityId))
    }

    @Test fun wrongOwnerOrChangedQueueHeadRollsBackWithoutAcknowledgment() = runBlocking {
        val original = db.viewingDao().getById(entry.entityId)
        val wrongOwner = ViewingCommandFixture.envelope(entry, ViewingCommandFixture.applied(entry).put("user_id", ViewingCommandFixture.title))
        assertTrue(runCatching { ack(wrongOwner) }.isFailure)
        assertEquals(original, db.viewingDao().getById(entry.entityId))
        assertEquals(entry, db.outboxDao().getPending().single())
        val changed = entry.copy(payloadJson = JSONObject(entry.payloadJson).put("unexpected", true).toString())
        db.outboxDao().enqueue(changed)
        assertTrue(runCatching { ack() }.isFailure)
        assertEquals(changed, db.outboxDao().getPending().single())
    }

    @Test fun acknowledgmentFailureRollsBackProjectionAndRetriesOriginalOperation() = runBlocking {
        var crash = true
        val pushed = mutableListOf<OutboxEntity>()
        val current = ViewingCommandFixture.applied(entry).put("notes", "Current server note")
        val outbox = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
            override suspend fun push(entry: OutboxEntity): PushResult {
                pushed += entry
                return PushResult.Applied(ViewingCommandFixture.envelope(entry, current))
            }
        }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), tx,
            appliedHandler = AppliedMutationHandler { saved, response ->
                applier.apply(saved, response)
                if (crash) error("Simulated failure before queue removal")
            })
        outbox.flush()
        assertEquals("Saved note", db.viewingDao().getById(entry.entityId)!!.notes)
        assertEquals(entry.payloadJson, db.outboxDao().getPending().single().payloadJson)
        crash = false
        outbox.flush()
        assertEquals(listOf(entry.id, entry.id), pushed.map { it.id })
        assertEquals(listOf(entry.payloadJson, entry.payloadJson), pushed.map { it.payloadJson })
        assertEquals("Current server note", db.viewingDao().getById(entry.entityId)!!.notes)
        assertTrue(db.outboxDao().getPending().isEmpty())
    }

    private suspend fun seedLinkedDelete(): OutboxEntity {
        val deletion = ViewingCommandFixture.linkedDelete()
        db.outboxDao().remove(entry.id); db.outboxDao().enqueue(deletion)
        db.cinemaOutingDao().upsert(ViewingCommandFixture.linkedOuting().put("completed_viewing_id", deletion.entityId).toRecoveryOuting())
        return deletion
    }
    private fun deletionEnvelope(deletion: OutboxEntity, outing: JSONObject?) = ViewingCommandFixture.envelope(deletion, null)
        .put("currentOutings", JSONObject().put(OutingCommandFixture.outing, outing ?: JSONObject.NULL))

    @Test fun deleteAckUsesCurrentLinkAndRevisionWithoutTouchingUnrelatedRewatchOrOuting() = runBlocking {
        val deletion = seedLinkedDelete()
        val other = viewingLinkedOuting("unrelated", ViewingCommandFixture.nextOperation)
        db.cinemaOutingDao().upsert(other)
        val rewatch = db.viewingDao().getById(entry.entityId)!!.copy(id = ViewingCommandFixture.nextOperation)
        db.viewingDao().upsert(rewatch)
        val current = ViewingCommandFixture.linkedOuting().put("completed_viewing_id", rewatch.id)
        tx.run { applier.apply(deletion, deletionEnvelope(deletion, current)); db.outboxDao().remove(deletion.id) }
        assertEquals(current.toRecoveryOuting(), db.cinemaOutingDao().getById(OutingCommandFixture.outing))
        assertEquals(other, db.cinemaOutingDao().getById(other.id))
        assertEquals(rewatch, db.viewingDao().getById(rewatch.id))
        assertNull(db.viewingDao().getById(deletion.entityId))
    }

    @Test fun deleteAckKeepsLaterNarrowOutingAndTicketIntentWhileTakingServerRevision() = runBlocking {
        val deletion = seedLinkedDelete()
        val later = OutingCommandFixture.patch(notes = "Later private note")
        db.outboxDao().enqueue(later)
        val ticket = OutboxEntity(ViewingCommandFixture.nextOperation, TICKET_COMMAND_ENTITY, OutingCommandFixture.outing, "ticket_v1", "{}", 2)
        db.outboxDao().enqueue(ticket)
        db.cinemaOutingDao().upsert(db.cinemaOutingDao().getById(OutingCommandFixture.outing)!!.copy(ticketImagePath = "new-local-ticket"))
        val current = ViewingCommandFixture.linkedOuting().put("venue", "New server venue")
        tx.run { applier.apply(deletion, deletionEnvelope(deletion, current)); db.outboxDao().remove(deletion.id) }
        val result = db.cinemaOutingDao().getById(OutingCommandFixture.outing)!!
        assertEquals("Later private note", result.notes); assertEquals("New server venue", result.venue)
        assertEquals("new-local-ticket", result.ticketImagePath); assertEquals("2026-10-08T15:00:00Z", result.updatedAt)
        assertNull(result.completedViewingId)
        assertEquals(listOf(later, ticket), db.outboxDao().getPending())
    }

    @Test fun missingCurrentOutingStaysAbsentButLaterIntentSurvives() = runBlocking {
        val deletion = seedLinkedDelete()
        val later = OutingCommandFixture.patch(notes = "Review after remote removal")
        db.outboxDao().enqueue(later)
        tx.run { applier.apply(deletion, deletionEnvelope(deletion, null)); db.outboxDao().remove(deletion.id) }
        assertNull(db.cinemaOutingDao().getById(OutingCommandFixture.outing))
        assertEquals(later, db.outboxDao().getPending().single())
    }

    @Test fun accountChangeDuringAckRollsBackViewingAndLinkedOutingTogether() = runBlocking {
        val deletion = seedLinkedDelete()
        val original = db.cinemaOutingDao().getById(OutingCommandFixture.outing)
        var checks = 0
        val fenced = ViewingCommandApplier(db, ViewingCommandFixture.owner) { ++checks == 1 }
        assertTrue(runCatching { tx.run {
            fenced.apply(deletion, deletionEnvelope(deletion, ViewingCommandFixture.linkedOuting()))
            db.outboxDao().remove(deletion.id)
        } }.isFailure)
        assertEquals(original, db.cinemaOutingDao().getById(OutingCommandFixture.outing))
        assertNotNull(db.viewingDao().getById(deletion.entityId))
        assertEquals(deletion, db.outboxDao().getPending().single())
    }
}

internal fun viewingLinkedOuting(id: String, viewingId: String) = CinemaOutingEntity(id, ViewingCommandFixture.title,
    "2026-10-01T19:00:00Z", runtimeMinutes = 90, endsAt = "2026-10-01T21:00:00Z", venue = "Preserved venue",
    companions = listOf("Friend"), format = null, ticketPrice = 12.0, notes = "Private note",
    ticketImagePath = "private/ticket", completedViewingId = viewingId, status = "COMPLETED",
    createdAt = ViewingCommandFixture.baseline, updatedAt = ViewingCommandFixture.baseline)
