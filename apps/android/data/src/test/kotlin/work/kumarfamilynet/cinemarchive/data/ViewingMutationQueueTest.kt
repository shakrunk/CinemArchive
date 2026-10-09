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
import work.kumarfamilynet.cinemarchive.core.model.ViewingDraft

@RunWith(RobolectricTestRunner::class)
class ViewingMutationQueueTest {
    private lateinit var db: LibraryDatabase
    private val owner = ViewingCommandFixture.owner
    private val title = ViewingCommandFixture.title
    private val draft get() = ViewingDraft(ViewingCommandFixture.viewing, "2026-10-01", 4.0, "Original", "Cinema", listOf("Sam"))
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }
    private fun outbox() = MutationOutbox(db.outboxDao(), object : RemoteMutationWriter {
        override suspend fun push(entry: OutboxEntity) = PushResult.Success
    }, TitleConflictHandler(db.titleDao(), db.titleReconcileDao()), RoomTransactor(db))
    private fun opened(guard: ViewingGuard = ViewingGuard(revision = ViewingCommandFixture.baseline)): ViewingDraft = draft.copy(
        openingContext = viewingOpening(owner, title, draft.id, draft, guard, ViewingGuard(revision = ViewingCommandFixture.baseline), listOf(OutingCommandFixture.outing)))
    private fun command(saved: ViewingDraft): OutboxEntity {
        val capture = checkedViewingOpening(saved, owner, title)
        val (operation, payload) = capture.payload("update", capture.fields(saved))
        return OutboxEntity(capturedViewingOperationId(owner, capture.token, "update", payload), "viewing", saved.id, operation, payload, 0)
    }
    private suspend fun MutationOutbox.admit(entry: OutboxEntity) = atomically {
        enqueueCaptured(entry.id, entry.entityType, entry.entityId, entry.operation, entry.payloadJson)
    }

    @Test fun recreatedOpeningRetainsExactRequestAndOriginalGuardAfterQueueWasAcknowledged() = runBlocking {
        val form = opened().copy(notes = "Changed")
        val first = command(form)
        assertTrue(outbox().admit(first))
        outbox().flush()
        assertTrue(db.outboxDao().getPending().isEmpty())
        // Simulate process restoration of the form after a lost local Save result.
        val restored = form.copy(openingContext = JSONObject(form.openingContext!!).toString())
        val retried = command(restored)
        assertEquals(first.id, retried.id); assertEquals(first.payloadJson, retried.payloadJson)
        assertTrue(outbox().admit(retried))
        val op = viewingCommandOperations(db.outboxDao().getPending().single()).getJSONObject(0)
        assertEquals(ViewingCommandFixture.baseline, op.getString("expectedUpdatedAt"))
    }

    @Test fun repeatedAdmissionAdoptsExistingReviewAndFailureMetadataWithoutMovingQueue() = runBlocking {
        val saved = command(opened().copy(notes = "Changed"))
        outbox().admit(saved)
        db.outboxDao().recordFailure(saved.id, "Unknown response")
        db.outboxDao().markForReview(saved.id, "Confirmed conflict")
        val original = db.outboxDao().getPending().single()
        assertFalse(outbox().admit(saved))
        assertEquals(original, db.outboxDao().getPending().single())
    }

    @Test fun conflictingIdCannotReplaceIntentAndRollsBackOtherLocalWork() = runBlocking {
        val saved = command(opened().copy(notes = "One")); outbox().admit(saved)
        val different = command(opened().copy(notes = "Two")).copy(id = saved.id)
        val unrelated = ViewingCommandFixture.entry(id = ViewingCommandFixture.nextOperation)
        assertTrue(runCatching { outbox().atomically {
            db.outboxDao().enqueue(unrelated)
            outbox().enqueueCaptured(different.id, different.entityType, different.entityId, different.operation, different.payloadJson)
        } }.isFailure)
        assertEquals(saved.payloadJson, db.outboxDao().getPending().single().payloadJson)
    }

    @Test fun restoredFieldsUseOpeningDiffWithExplicitNullAndNeverAdoptLaterRevision() {
        val form = opened().copy(rating = null, notes = null)
        val entry = command(form)
        val op = viewingCommandOperations(entry).getJSONObject(0)
        assertEquals(setOf("rating", "notes"), op.getJSONObject("values").keys().asSequence().toSet())
        assertTrue(op.getJSONObject("values").isNull("rating")); assertTrue(op.getJSONObject("values").isNull("notes"))
        assertEquals(ViewingCommandFixture.baseline, op.getString("expectedUpdatedAt"))
        assertNotEquals(entry.id, command(form.copy(notes = "New intent")).id)
    }

    @Test fun openingCannotCrossOwnerTitleOrViewingAndMissingBaselineIsNeverDispatchable() {
        val form = opened()
        assertTrue(runCatching { checkedViewingOpening(form, ViewingCommandFixture.nextOperation, title) }.isFailure)
        assertTrue(runCatching { checkedViewingOpening(form, owner, ViewingCommandFixture.nextOperation) }.isFailure)
        assertTrue(runCatching { checkedViewingOpening(form.copy(id = ViewingCommandFixture.nextOperation), owner, title) }.isFailure)
        val unknown = command(opened(ViewingGuard()).copy(notes = "Retained for review"))
        assertEquals("review", unknown.operation)
        assertTrue(JSONObject(unknown.payloadJson).has(VIEWING_REVIEW_INTENT))
        assertTrue(runCatching { viewingCommandOperations(unknown) }.isFailure)
    }

    @Test fun newlyOpenedCanonicalHistoryUsesObservedRevisionRatherThanOldCompletionProof() {
        val canonical = ViewingCommandFixture.row().put("updated_at", "2026-10-08T20:00:00Z").toCompletionViewing()
        val alias = ViewingCompletionAliasEntity(ViewingCommandFixture.nextOperation, canonical.id, title,
            OutingCommandFixture.outing, ViewingCommandFixture.operation, ViewingCommandFixture.baseline)
        assertEquals(ViewingGuard(revision = canonical.updatedAt), captureViewingGuard(canonical, alias, emptyList()))
        assertEquals(ViewingGuard(operationId = alias.completionOperationId), captureViewingGuard(canonical.copy(updatedAt = null), alias, emptyList()))
        assertEquals(ViewingGuard(), captureViewingGuard(canonical.copy(updatedAt = null), alias.copy(canonicalViewingVersion = null), emptyList()))
        assertTrue(runCatching { captureViewingGuard(canonical, alias.copy(titleId = owner), emptyList()) }.isFailure)
    }

    @Test fun pendingLegacyOrUnconvertedProvisionalIntentCannotBecomeFreshBaseline() {
        val canonical = ViewingCommandFixture.row().toCompletionViewing()
        val legacy = ViewingCommandFixture.entry().copy(operation = "update")
        assertEquals(ViewingGuard(), captureViewingGuard(canonical, null, listOf(legacy)))
        val valid = ViewingCommandFixture.entry()
        assertEquals(ViewingGuard(operationId = valid.id), captureViewingGuard(canonical, null, listOf(valid)))
        val wrongTitle = valid.copy(payloadJson = JSONObject(valid.payloadJson).put("titleId", owner).toString())
        assertEquals(ViewingGuard(), captureViewingGuard(canonical, null, listOf(wrongTitle)))
    }

    @Test fun pendingCapturedDeleteProtectsExactOutingsAndSurvivesReviewState() {
        val deletion = ViewingCommandFixture.linkedDelete().copy(operation = "review")
        val keys = viewingHistoryProtectionKeys(listOf(deletion), owner)
        assertEquals(setOf("viewing_history:${deletion.entityId}", "cinema_outing:${OutingCommandFixture.outing}"), keys)
        val tombstone = JSONObject().put("entity_type", "tombstone").put("entity_id", OutingCommandFixture.outing)
            .put("payload", JSONObject().put("entityType", "cinema_outing"))
        assertTrue(isProtectedFromPull(tombstone, keys))
        assertTrue(runCatching { viewingHistoryProtectionKeys(listOf(deletion), ViewingCommandFixture.nextOperation) }.isFailure)
        assertEquals(keys, viewingHistoryProtectionKeys(listOf(deletion, deletion.copy(payloadJson = "{broken")), owner))
    }
}
