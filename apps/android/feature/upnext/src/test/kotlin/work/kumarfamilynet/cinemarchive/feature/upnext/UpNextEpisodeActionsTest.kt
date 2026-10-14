package work.kumarfamilynet.cinemarchive.feature.upnext

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.EpisodeWatchReceipt
import work.kumarfamilynet.cinemarchive.core.model.UpNextWatching

@OptIn(ExperimentalCoroutinesApi::class)
class UpNextEpisodeActionsTest {
    private val card = UpNextWatching("title", "A show", null, 0, 2, 1, 1, nextEpisodeId = "ep1")
    private fun receipt(id: String = "watch", caughtUp: Boolean = false) = EpisodeWatchReceipt("title", "ep1", id, 1, 1, caughtUp)

    @Test fun undoExpiresAfterSixSecondsAndDuplicateTapsLogOnce() = runTest {
        var advances = 0
        val actions = UpNextEpisodeActions(backgroundScope, { advances++; receipt() }, {}, {})
        actions.mark(card); actions.mark(card)
        runCurrent()
        assertEquals(1, advances)
        advanceTimeBy(5_999); runCurrent()
        assertEquals("watch", actions.state.value["title"]!!.receipt!!.watchEventId)
        advanceTimeBy(1); runCurrent()
        assertTrue(actions.state.value.isEmpty())
    }

    @Test fun undoDeletesExactReceiptOnlyOnce() = runTest {
        val deleted = mutableListOf<EpisodeWatchReceipt>()
        val actions = UpNextEpisodeActions(backgroundScope, { receipt("stable-id") }, { deleted += it }, {})
        actions.mark(card); runCurrent()
        actions.undo("title"); actions.undo("title"); runCurrent()
        assertEquals(listOf(receipt("stable-id")), deleted)
        assertTrue(actions.state.value.isEmpty())
    }

    @Test fun olderTimerCannotRemoveNewerUndo() = runTest {
        var count = 0
        val actions = UpNextEpisodeActions(backgroundScope, { receipt("watch-${++count}") }, {}, {})
        actions.mark(card); runCurrent()
        advanceTimeBy(1_000)
        actions.undo("title"); runCurrent()
        actions.mark(card.copy(nextEpisodeId = "ep2")); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals("watch-2", actions.state.value["title"]!!.receipt!!.watchEventId)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(actions.state.value.isEmpty())
    }

    @Test fun finaleRetainsSnapshotAndChangesStatusOnlyOnExplicitChoice() = runTest {
        val statuses = mutableListOf<String>()
        val actions = UpNextEpisodeActions(backgroundScope, { receipt(caughtUp = true) }, {}, { statuses += it })
        actions.mark(card); runCurrent()
        assertTrue(statuses.isEmpty())
        assertEquals(card, actions.state.value["title"]!!.snapshot)
        assertTrue(actions.state.value["title"]!!.receipt!!.caughtUp)
        actions.finishSeries("title"); runCurrent()
        assertEquals(listOf("title"), statuses)
        assertTrue(actions.state.value.isEmpty())
    }

    @Test fun ordinaryEpisodeCannotFinishSeries() = runTest {
        var changed = false
        val actions = UpNextEpisodeActions(backgroundScope, { receipt() }, {}, { changed = true })
        actions.mark(card); runCurrent()
        actions.finishSeries("title"); runCurrent()
        assertFalse(changed)
        assertNotNull(actions.state.value["title"]!!.receipt)
    }

    @Test fun undoRequestedBeforeExpiryRetainsReceiptForRetryWhenStorageFailsAfterExpiry() = runTest {
        val pending = CompletableDeferred<Unit>()
        val deleted = mutableListOf<String>()
        val actions = UpNextEpisodeActions(backgroundScope, { receipt("exact") }, {
            deleted += it.watchEventId
            if (deleted.size == 1) { pending.await(); error("disk full") }
        }, {})
        actions.mark(card); runCurrent()
        advanceTimeBy(5_999)
        actions.undo("title"); runCurrent()
        advanceTimeBy(2); runCurrent()
        assertTrue(actions.state.value["title"]!!.busy)
        pending.complete(Unit); runCurrent()
        assertEquals("disk full", actions.state.value["title"]!!.error)
        assertFalse(actions.state.value["title"]!!.busy)
        advanceTimeBy(60_000); runCurrent()
        actions.undo("title"); runCurrent()
        assertEquals(listOf("exact", "exact"), deleted)
        assertTrue(actions.state.value.isEmpty())
    }

    @Test fun failedLogCanRetryAndStaleCardReportsNoSuccess() = runTest {
        var count = 0
        val actions = UpNextEpisodeActions(backgroundScope, { if (++count == 1) error("disk full") else null }, {}, {})
        actions.mark(card); runCurrent()
        assertEquals("disk full", actions.state.value["title"]!!.error)
        assertNull(actions.state.value["title"]!!.receipt)
        actions.mark(card); runCurrent()
        assertEquals(2, count)
        assertTrue(actions.state.value["title"]!!.error!!.contains("changed"))
        assertNull(actions.state.value["title"]!!.receipt)
    }
}
