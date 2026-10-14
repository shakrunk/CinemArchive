package work.kumarfamilynet.cinemarchive

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.*
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen
import work.kumarfamilynet.cinemarchive.feature.upnext.UpNextScreen

@RunWith(AndroidJUnit4::class)
class PostShowLifecycleScreenTest {
    @get:Rule val compose = createComposeRule()
    private val opening = PostShowOpening(ViewingDraft("viewing", "2026-10-08", null, "Original note", "Cinema",
        listOf("Friend, Jr."), "captured viewing and title guards"), "captured reversal and receipt")
    private fun detail() = TitleDetail("title", MediaType.MOVIE, "Film", 2026, null, null, null, null, null, 90,
        LibraryStatus.WATCHED, null, null, emptyList(), emptyList(),
        listOf(Viewing("viewing", "2026-10-08", null, null, "Cinema", outingId = "outing")), tmdbId = 42)
    private fun waitFor(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun tap(text: String) = compose.onNodeWithText(text, substring = false).performScrollTo().performClick()
    private fun openTitle() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("How was it?"))
        compose.onNodeWithText("How was it?").performClick()
        waitFor("Film just let out")
    }
    private fun systemBack() {
        val result = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
        ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
        compose.waitForIdle()
    }

    @Test fun gesturesStageOnlyAndPendingSaveBlocksDismissUntilExactRetrySucceeds() {
        val active = mutableStateOf(true)
        val blocker = CompletableDeferred<Unit>()
        val saves = mutableListOf<Triple<PostShowOpening, Double?, String>>()
        compose.setContent { CinemArchiveTheme {
            if (active.value) PostShowSheet("Film", opening, { captured, rating, notes ->
                saves += Triple(captured, rating, notes)
                if (saves.size == 1) { blocker.await(); error("local result uncertain") }
            }, { error("Must not reverse a save attempt") }, { active.value = false })
        } }
        compose.onNodeWithTag("post-show-rating").performClick()
        val hint = compose.onNodeWithText("Drag anywhere above — half-star precision").fetchSemanticsNode().boundsInRoot
        val dialog = compose.onNode(isDialog() and hasAnyDescendant(hasText("Rate this title")))
        val dialogBounds = dialog.fetchSemanticsNode().boundsInRoot
        dialog.performTouchInput {
            val trackY = hint.top - dialogBounds.top - 24.dp.toPx()
            swipe(Offset(width * 0.35f, trackY), Offset(width * 0.75f, trackY), durationMillis = 300)
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Quick note").performTextReplacement("Edited note")
        compose.runOnIdle { assertTrue("Rating gestures and typing must not enqueue", saves.isEmpty()) }
        tap("Save")
        waitFor("Saving…")
        compose.onNodeWithText("Close").assertIsNotEnabled()
        systemBack(); systemBack()
        compose.onNodeWithText("Film just let out").assertExists()
        compose.runOnIdle { assertEquals(1, saves.size); assertNotNull(saves.single().second); blocker.complete(Unit) }
        waitFor("Retry save")
        compose.onNodeWithText("Quick note").assertIsNotEnabled()
        compose.onNodeWithText("Didn't make it").assertDoesNotExist()
        tap("Retry save")
        compose.waitUntil { !active.value }
        compose.runOnIdle { assertEquals(2, saves.size); assertEquals(saves[0], saves[1]); assertEquals(opening, saves[0].first) }
    }

    @Test fun titleHostRestoresCapturedOpeningAndFrozenAttemptWithoutRecapture() {
        val restoration = StateRestorationTester(compose)
        var prepareCalls = 0
        val saves = mutableListOf<Triple<PostShowOpening, Double?, String>>()
        restoration.setContent { CinemArchiveTheme {
            TitleDetailScreen(detail(), {}, viewingOwnerId = "owner", onPreparePostShow = {
                prepareCalls++; opening
            }, onSavePostShow = { captured, rating, notes ->
                saves += Triple(captured, rating, notes)
                if (saves.size == 1) error("Uncertain local result")
            })
        } }
        openTitle()
        compose.onNodeWithText("Quick note").performTextReplacement("Keep exact retry")
        tap("Save"); waitFor("Retry save")
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Retry save")
        compose.onNodeWithText("Quick note").assertIsNotEnabled()
        tap("Retry save")
        compose.waitUntil { compose.onAllNodesWithText("Film just let out").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            assertEquals(1, prepareCalls)
            assertEquals(listOf(Triple(opening, null, "Keep exact retry"), Triple(opening, null, "Keep exact retry")), saves)
        }
    }

    @Test fun upNextUsesStoredViewingAndReversalRetryNeverEnqueuesDismissOrSave() {
        val outing = CinemaOuting("outing", "title", "2026-10-08T17:00:00Z", 0, 90, "2026-10-08T18:30:00Z", "Old venue", emptyList(),
            null, null, null, null, null, emptyList(), null, null, null, null, null, OutingStatus.COMPLETED,
            LibraryStatus.WATCHLIST, "viewing", null, "2026-10-08T16:00:00Z")
        var prepareCalls = 0
        var dismissed = 0
        val reversals = mutableListOf<PostShowOpening>()
        compose.setContent { CinemArchiveTheme {
            UpNextScreen(UpNextBoard(emptyList(), emptyList(), freshFromTheLobby = listOf(UpNextOuting(outing, "Film", null))),
                {}, {}, {}, {}, { dismissed++ }, postShowOwnerId = "owner", onPreparePostShow = { titleId, viewingId ->
                    assertEquals("title", titleId); assertEquals("viewing", viewingId); prepareCalls++; opening
                }, onSavePostShow = { _, _, _ -> error("Must not save a reversal attempt") }, onRevertPostShow = {
                    reversals += it
                    if (reversals.size == 1) error("Retry local admission")
                })
        } }
        compose.onNodeWithText("How was it?").performClick()
        waitFor("Film just let out")
        compose.onNodeWithText("Original note").assertExists()
        compose.onNodeWithText("Cinema · with Friend, Jr.").assertExists()
        tap("Didn't make it"); waitFor("Retry reversal")
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Quick note").assertIsNotEnabled()
        tap("Retry reversal")
        compose.waitUntil { compose.onAllNodesWithText("Film just let out").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(listOf(opening, opening), reversals); assertEquals(1, prepareCalls); assertEquals(0, dismissed) }
    }

    @Test fun manualOrAlreadyRatedViewingCannotReverseAndClearRatingWaitsForSave() {
        val captured = opening.copy(viewing = opening.viewing.copy(rating = 3.5), reversalContext = "even a stale reversal is hidden")
        val active = mutableStateOf(true)
        val savedRatings = mutableListOf<Double?>()
        compose.setContent { CinemArchiveTheme {
            if (active.value) PostShowSheet("Film", captured, { _, rating, _ -> savedRatings += rating },
                { error("Rated viewing cannot reverse") }, { active.value = false })
        } }
        compose.onNodeWithText("Didn't make it").assertDoesNotExist()
        tap("Clear rating")
        compose.runOnIdle { assertTrue(savedRatings.isEmpty()) }
        tap("Save")
        compose.runOnIdle { assertEquals(listOf<Double?>(null), savedRatings) }
    }

    @Test fun accountSwitchRejectsLateOpeningAndSavedContextValidatesOwnerAndTitle() {
        val owner = mutableStateOf("A")
        lateinit var pending: Continuation<PostShowOpening>
        compose.setContent { CinemArchiveTheme { key(owner.value) {
            TitleDetailScreen(detail(), {}, viewingOwnerId = owner.value,
                onPreparePostShow = { suspendCoroutine { pending = it } })
        } } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("How was it?"))
        compose.onNodeWithText("How was it?").performClick()
        waitFor("Opening viewing…")
        compose.runOnIdle { owner.value = "B" }
        compose.runOnIdle { pending.resume(opening) }
        compose.onNodeWithText("Film just let out").assertDoesNotExist()
        val saved = savePostShow("A", "title", "Film", opening, "outing")
        assertEquals(opening, restorePostShow(saved, "A", "title")!!.opening)
        assertEquals("outing", restorePostShow(saved, "A")!!.followUpOutingId)
        assertNull(restorePostShow(saved, "B"))
        assertNull(restorePostShow(saved, "A", "other-title"))
        assertNull(restorePostShow("broken", "A"))
    }

    @Test fun openingFailureIsVisibleAndRetryCapturesOnlyAfterExplicitReopen() {
        var calls = 0
        compose.setContent { CinemArchiveTheme {
            TitleDetailScreen(detail(), {}, viewingOwnerId = "owner", onPreparePostShow = {
                if (++calls == 1) error("Unreadable local evidence")
                opening.copy(reversalContext = null)
            })
        } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("How was it?"))
        compose.onNodeWithText("How was it?").performClick()
        waitFor("Couldn't open this viewing. Try again.")
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("How was it?").performClick()
        waitFor("Film just let out")
        compose.onNodeWithText("Didn't make it").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, calls) }
    }
}
