package work.kumarfamilynet.cinemarchive

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen

@RunWith(AndroidJUnit4::class)
class OutingScheduleSaveScreenTest {
    @get:Rule val compose = createComposeRule()
    private val owner = "10000000-0000-4000-8000-000000000001"
    private val titleId = "20000000-0000-4000-8000-000000000001"
    private val opening = JSONObject().put("version", 1).put("ownerId", owner).put("projectId", "project")
        .put("titleId", titleId).put("outingId", "30000000-0000-4000-8000-000000000001")
        .put("operationId", "40000000-0000-4000-8000-000000000001").put("capturedAt", "2026-10-09T00:00:00Z")
        .put("original", JSONObject.NULL).put("expectedUpdatedAt", JSONObject.NULL).put("expectedOperationId", JSONObject.NULL)
        .put("review", false).put("companionsKnown", true).toString()
    private fun detail() = TitleDetail(titleId, MediaType.MOVIE, "Movie night", 2026, null, null, null, null, null, 90,
        LibraryStatus.WATCHLIST, null, null, emptyList(), emptyList(), emptyList())
    private fun waitFor(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun systemBack() {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
        compose.waitForIdle()
    }

    @Test fun ticketsPaletteOpensCapturedEditorAndPendingRetrySurvivesRecreationWithoutRecapture() {
        val restoration = StateRestorationTester(compose)
        val blocked = CompletableDeferred<Unit>()
        var captures = 0
        val attempts = mutableListOf<List<Any?>>()
        restoration.setContent { CinemArchiveTheme {
            var selected by rememberSaveable { mutableStateOf(false) }
            var initial by rememberSaveable { mutableStateOf(true) }
            if (!selected) OwnerGlobalSearch(owner,
                listOf(LibraryTitle(titleId, "Movie night", 2026, null, LibraryStatus.WATCHLIST, MediaType.MOVIE, null, null, null)),
                {}, {}, {}, { selected = true })
            else TitleDetailScreen(detail(), {}, viewingOwnerId = owner, initialSchedule = initial,
                onInitialScheduleConsumed = { initial = false }, onPrepareSchedule = { captures++; opening },
                onSaveSchedule = { captured, time, previews, runtime, venue, companions, format, price, seats, booking, notes ->
                    attempts += listOf(captured, time, previews, runtime, venue, companions, format, price, seats, booking, notes)
                    if (attempts.size == 1) { blocked.await(); error("Alarm unavailable; retry the same tickets.") }
                })
        } }
        compose.onNode(hasSetTextAction()).performTextInput("tickets")
        compose.onNodeWithText("I've got tickets…").performClick()
        compose.onNodeWithText("Movie night").performClick()
        waitFor("Theater")
        compose.onNodeWithText("Theater").performTextReplacement("Local cinema")
        compose.onNodeWithText("Save tickets").performScrollTo().performClick()
        waitFor("Saving tickets…")
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        systemBack(); systemBack()
        compose.onNodeWithText("Saving tickets…").assertExists()
        compose.runOnIdle { assertEquals(1, attempts.size); blocked.complete(Unit) }
        waitFor("Retry tickets")
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Retry tickets")
        compose.onAllNodesWithText("Theater").assertCountEquals(0)
        compose.onNodeWithText("Retry tickets").performScrollTo().performClick()
        compose.waitUntil { compose.onAllNodesWithText("Retry tickets").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(1, captures); assertEquals(2, attempts.size); assertEquals(attempts[0], attempts[1]); assertEquals(opening, attempts[0][0]) }
    }

    @Test fun failedCaptureIsVisibleAndRetryDoesNotScheduleWithoutOpening() {
        var captures = 0
        var saves = 0
        compose.setContent { CinemArchiveTheme {
            TitleDetailScreen(detail(), {}, viewingOwnerId = owner,
                onPrepareSchedule = { if (++captures == 1) error("Offline opening failed"); opening },
                onSaveSchedule = { _, _, _, _, _, _, _, _, _, _, _ -> saves++ })
        } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("I've got tickets"))
        compose.onNodeWithText("I've got tickets").performClick()
        waitFor("Couldn't open tickets")
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("Retry").performClick()
        waitFor("Theater")
        compose.runOnIdle { assertEquals(2, captures); assertEquals(0, saves) }
    }
}
