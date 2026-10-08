package work.kumarfamilynet.cinemarchive.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class OutingRecoveryStateTest {
    @Test fun reviewStartsWithNoChosenFieldsAndRequiresAnExplicitSelection() = runTest {
        val source = FakeSource()
        val controller = OutingRecoveryController(source, this)
        controller.open("one"); advanceUntilIdle()
        assertTrue(controller.state.value.selected.isEmpty())
        controller.apply(); advanceUntilIdle()
        assertEquals(0, source.calls)
        controller.select("venue", true); controller.apply(); advanceUntilIdle()
        assertEquals(setOf("venue"), source.selected)
        assertEquals(1, source.calls)
    }

    @Test fun failedReviewKeepsOtherCardsAndAllowsOriginalExport() = runTest {
        val source = FakeSource()
        val controller = OutingRecoveryController(source, this)
        controller.refresh(); advanceUntilIdle()
        source.failReview = true
        controller.open("one"); advanceUntilIdle()
        assertEquals(2, controller.state.value.cards.size)
        assertEquals("Offline", controller.state.value.error)
        var exported: String? = null
        controller.export("one") { exported = it }; advanceUntilIdle()
        assertEquals("Original one", exported)
    }

    @Test fun unknownOutcomeLocksFieldChoicesAndRetainsSameAttemptForRetry() = runTest {
        val source = FakeSource()
        val controller = OutingRecoveryController(source, this)
        controller.open("one"); advanceUntilIdle()
        controller.select("venue", true)
        source.failApply = true
        controller.apply(); advanceUntilIdle()
        assertTrue(controller.state.value.review!!.pendingAttempt)
        controller.select("notes", true)
        assertEquals(setOf("venue"), controller.state.value.selected)
        source.failApply = false
        controller.apply(); advanceUntilIdle()
        assertEquals(2, source.calls)
    }

    @Test fun originalCommandConfirmationNeedsNoReplacementSelectionAndReportsConfirmation() = runTest {
        val source = FakeSource().apply { pending = true; outcome = OutingRecoveryOutcome.CONFIRMED }
        val controller = OutingRecoveryController(source, this)
        controller.open("one"); advanceUntilIdle()
        controller.select("venue", true)
        assertTrue(controller.state.value.selected.isEmpty())
        controller.apply(); advanceUntilIdle()
        assertEquals(1, source.calls)
        assertTrue(source.selected.isEmpty())
        assertTrue(controller.state.value.review!!.resolved)
        assertTrue(controller.state.value.message!!.startsWith("Original outing change confirmed"))
    }

    @Test fun changedRemoteClearsSelectionsAndRequiresANewReview() = runTest {
        val source = FakeSource().apply { outcome = OutingRecoveryOutcome.CHANGED }
        val controller = OutingRecoveryController(source, this)
        controller.open("one"); advanceUntilIdle()
        controller.select("venue", true); controller.apply(); advanceUntilIdle()
        assertTrue(controller.state.value.selected.isEmpty())
        assertTrue(controller.state.value.message!!.contains("Review its latest values"))
    }

    @Test fun lateResultAfterAccountChangeCannotRestorePrivateReviewOrExport() = runTest {
        val source = FakeSource()
        val deferred = CompletableDeferred<Unit>()
        source.wait = deferred
        val controller = OutingRecoveryController(source, this)
        controller.open("one"); advanceUntilIdle()
        source.active = false
        deferred.complete(Unit); advanceUntilIdle()
        assertNull(controller.state.value.review)
        assertTrue(controller.state.value.cards.isEmpty())
        var exported = false
        controller.export("one") { exported = true }; advanceUntilIdle()
        assertFalse(exported)
    }

    private class FakeSource : OutingRecoverySource {
        override val changes = flowOf(Unit)
        var active = true
        var failReview = false
        var failApply = false
        var pending = false
        var calls = 0
        var selected = emptySet<String>()
        var wait: CompletableDeferred<Unit>? = null
        var outcome = OutingRecoveryOutcome.APPLIED
        override fun isActive() = active
        override suspend fun items() = listOf(OutingRecoveryCard("one", "First", false), OutingRecoveryCard("two", "Second", false))
        override suspend fun review(id: String): OutingRecoveryReview {
            wait?.await()
            check(!failReview) { "Offline" }
            return OutingRecoveryReview(id, "Film", listOf(
                OutingRecoveryField("venue", "Theater", "Saved", "Current", true),
                OutingRecoveryField("notes", "Notes", "Saved notes", "Current notes", true),
            ), "version", true, pending, false)
        }
        override suspend fun pendingAttempt(id: String) = pending
        override suspend fun apply(id: String, expectedVersion: String?, selected: Set<String>): OutingRecoveryOutcome {
            calls++; this.selected = selected
            pending = failApply
            check(!failApply) { "Unconfirmed" }
            return outcome
        }
        override suspend fun discard(id: String) = Unit
        override suspend fun exportOriginal(id: String) = "Original $id"
    }
}
