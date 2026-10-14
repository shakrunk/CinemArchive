package work.kumarfamilynet.cinemarchive.feature.friends

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class OutingPlansStateTest {
    @Test fun recipientRetryDoesNotResendSuccessAndDoubleTapIsBlocked() = runTest {
        val source = FakeSource()
        val controller = OutingPlansController(source, "outing", this)
        controller.load(); advanceUntilIdle()
        controller.send("one"); controller.send("one"); controller.send("two"); advanceUntilIdle()
        assertEquals(setOf("one"), controller.state.value.sent)
        assertEquals(setOf("two"), controller.state.value.failures.keys)
        source.failTwo = false
        controller.send("two"); advanceUntilIdle()
        assertEquals(listOf("one", "two", "two"), source.sent)
        assertEquals(setOf("one", "two"), controller.state.value.sent)
        val retries = source.operations.filter { it.first == "two" }.map { it.second }
        assertEquals(2, retries.size)
        assertEquals(retries[0], retries[1])
        val original = source.operations.first { it.first == "one" }.second
        controller.send("one"); advanceUntilIdle()
        assertNotEquals(original, source.operations.last().second)
    }
    @Test fun externalActionRechecksPendingChangesAndNeverExportsCachedPlan() = runTest {
        val source = FakeSource()
        val controller = OutingPlansController(source, "outing", this)
        controller.load(); advanceUntilIdle()
        var exported: PublicOutingPlan? = null
        source.pending = true
        controller.prepareExternal { exported = it }; advanceUntilIdle()
        assertNull(exported); assertNull(controller.state.value.plan)
        assertEquals("Still syncing", controller.state.value.error)
        source.pending = false
        source.plan = source.plan.copy(venue = "Updated cinema")
        controller.load(); advanceUntilIdle()
        controller.prepareExternal { exported = it }; advanceUntilIdle()
        assertEquals("Updated cinema", exported?.venue)
    }
    @Test fun accountSwitchWhileSendingDropsPrivateStateAndBlocksExports() = runTest {
        val source = FakeSource().apply { waitSend = CompletableDeferred() }
        val controller = OutingPlansController(source, "outing", this)
        controller.load(); advanceUntilIdle()
        controller.send("one"); advanceUntilIdle()
        source.active = false
        source.waitSend!!.complete(Unit); advanceUntilIdle()
        controller.prepareExternal { fail("No export after account exit") }
        controller.send("two"); advanceUntilIdle()
        assertNull(controller.state.value.plan)
        assertTrue(controller.state.value.friends.isEmpty())
        assertEquals(listOf("one"), source.sent)
    }
    @Test fun closingCancelsPendingLoadWithoutPublishingOldPlan() = runTest {
        val source = FakeSource().apply { waitLoad = CompletableDeferred() }
        val controller = OutingPlansController(source, "outing", this)
        controller.load(); advanceUntilIdle()
        controller.close()
        source.waitLoad!!.complete(Unit); advanceUntilIdle()
        assertNull(controller.state.value.plan)
        assertEquals(0, source.friendReads)
        controller.send("one"); advanceUntilIdle()
        assertTrue(source.sent.isEmpty())
    }
    @Test fun eachFriendKeepsConfirmedSnapshotWhileHeaderShowsCurrentPlan() = runTest {
        val source = FakeSource().apply { failTwo = false }
        val oldPlan = source.plan
        val controller = OutingPlansController(source, "outing", this)
        controller.load(); advanceUntilIdle()
        source.plan = oldPlan.copy(venue = "New cinema")
        source.receiptPlan = oldPlan
        controller.send("one"); advanceUntilIdle()
        assertEquals("Cinema", controller.state.value.delivered["one"]?.venue)
        assertEquals("New cinema", controller.state.value.plan?.venue)
        source.receiptPlan = source.plan
        controller.send("two"); advanceUntilIdle()
        assertEquals("Cinema", controller.state.value.delivered["one"]?.venue)
        assertEquals("New cinema", controller.state.value.delivered["two"]?.venue)
    }

    @Test fun failedRefreshAfterAckDoesNotTurnDeliveredAttemptIntoRetry() = runTest {
        val source = FakeSource()
        val controller = OutingPlansController(source, "outing", this)
        controller.load(); advanceUntilIdle()
        source.receiptPlan = source.plan
        source.afterSend = { source.pending = true }
        controller.send("one"); advanceUntilIdle()
        assertEquals(setOf("one"), controller.state.value.sent)
        assertTrue(controller.state.value.failures.isEmpty())
        assertNull(controller.state.value.plan)
        assertEquals("Still syncing", controller.state.value.error)
        source.pending = false; source.afterSend = {}
        controller.load(); advanceUntilIdle()
        controller.send("one"); advanceUntilIdle()
        assertNotEquals(source.operations[0].second, source.operations[1].second)
    }

    private class FakeSource : OutingPlansSource {
        var active = true
        var pending = false
        var failTwo = true
        var friendReads = 0
        var waitLoad: CompletableDeferred<Unit>? = null
        var waitSend: CompletableDeferred<Unit>? = null
        var plan = PublicOutingPlan("outing", "Film", "2099-01-01T19:00:00Z", "2099-01-01T21:00:00Z", "Cinema", null, null, emptyList())
        var receiptPlan: PublicOutingPlan? = null
        var afterSend: () -> Unit = {}
        val sent = mutableListOf<String>()
        val operations = mutableListOf<Pair<String, String>>()
        override fun isActive() = active
        override suspend fun load(outingId: String): PublicOutingPlan {
            waitLoad?.await(); check(!pending) { "Still syncing" }; return plan
        }
        override suspend fun friends(): List<Friendship> {
            friendReads++
            return listOf("one", "two").map { Friendship(it, FriendshipStatus.ACCEPTED, "owner", null, "", "", it, null) }
        }
        override suspend fun send(outingId: String, recipientId: String, operationId: String): PublicOutingPlan {
            operations += recipientId to operationId
            sent += recipientId; waitSend?.await()
            check(!(recipientId == "two" && failTwo)) { "Retry" }
            afterSend()
            return receiptPlan ?: load(outingId)
        }
    }
}
