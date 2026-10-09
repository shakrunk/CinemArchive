package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.library.PrePlatformWatchControl
import work.kumarfamilynet.cinemarchive.feature.library.EpisodeBulkRecoveryPanel

class PrePlatformWatchScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun episode(id: String, watched: Boolean = false) = EpisodeDetail(id, 1, id, null, 30, if (watched) 1 else 0, null,
        watchEvents = if (watched) listOf(EpisodeWatch("existing", "2026-01-01")) else emptyList())
    private fun detail() = TitleDetail("title", MediaType.TV, "Series", 2020, null, null, null, null, null, 30,
        LibraryStatus.WATCHING, null, null, emptyList(), listOf(
            SeasonDetail("main", 1, 2, 1, 2020, listOf(episode("one", true), episode("two"))),
            SeasonDetail("specials", 0, 1, 0, 2020, listOf(episode("special")))), emptyList())
    private fun opening() = EpisodeBulkOpening(JSONObject().put("operationId", "same-request")
        .put("watches", JSONArray().put(JSONObject().put("id", "same-watch"))).put("seasons", JSONArray()).toString())

    @Test fun seasonAndSeriesRequireExplicitConfirmationAndCancelWritesNothing() {
        val scopes = mutableListOf<Int?>(); var saves = 0
        compose.setContent { CinemArchiveTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            PrePlatformWatchControl(detail(), 0, "owner", emptyList(), { scopes += it; opening() }, { saves++ }, {}, { error("unused") }, {}, {})
        } } }
        compose.onNodeWithText("Watched specials before joining").performClick()
        compose.onNodeWithText("Mark 1 episodes as watched (no date)?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(listOf(0), scopes); assertEquals(0, saves) }
        compose.onNodeWithText("Watched entire series before joining").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.runOnIdle { assertEquals(listOf(0, null), scopes); assertEquals(1, saves) }
    }

    @Test fun failureRetainsExactOpeningForRetryAndPendingSaveDisablesDismiss() {
        var attempts = 0; val seen = mutableListOf<String>(); val gate = CompletableDeferred<Unit>()
        compose.setContent { CinemArchiveTheme {
            PrePlatformWatchControl(detail(), 1, "owner", emptyList(), { opening() }, {
                seen += it.json; attempts++
                if (attempts == 1) { gate.await(); error("Disk full") }
            }, {}, { error("unused") }, {}, {})
        } }
        compose.onNodeWithText("Watched season 1 before joining").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil { compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(2, seen.size); assertEquals(seen[0], seen[1]) }
        compose.onAllNodesWithText("Confirm").assertCountEquals(0)
    }

    @Test fun removedTitleRecoveryKeepsOriginalExportAvailableAfterExplicitDiscard() {
        val requests = mutableStateOf(listOf(EpisodeBulkPending("removed", 2, true, "Changed remotely", "Removed series")))
        var discarded: String? = null
        var exports = 0
        compose.setContent { CinemArchiveTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            EpisodeBulkRecoveryPanel(requests.value, {}, {
                discarded = it; requests.value = emptyList()
            }, retainedCount = 1, exportOriginals = { exports++ })
        } } }
        compose.onNodeWithText("Removed series: 2 undated watches").assertIsDisplayed()
        compose.onNodeWithText("Export original requests").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, exports) }
        compose.onNodeWithText("Discard saved request").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(discarded) }
        compose.onNodeWithText("Discard saved request").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { assertEquals("removed", discarded) }
        compose.onNodeWithText("Export original requests").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, exports) }
        compose.onNodeWithText("1 original requests retained, including discarded and synced requests.").assertIsDisplayed()
        compose.onAllNodesWithText("Discard saved request").assertCountEquals(0)
    }

    @Test fun retainedReviewIsVisibleEvenWhenOptimisticWatchesHideNewActions() {
        val watched = detail().copy(seasons = detail().seasons.map { s -> s.copy(episodes = s.episodes.map { e -> e.copy(watchEvents = listOf(EpisodeWatch("pending", null))) }) })
        var discarded: String? = null
        compose.setContent { CinemArchiveTheme {
            PrePlatformWatchControl(watched, 1, "owner", listOf(EpisodeBulkPending("request", 1, true, "Changed remotely")),
                { error("unused") }, {}, {}, { error("unused") }, {}, { discarded = it })
        } }
        compose.onNodeWithText("Changed remotely").assertIsDisplayed()
        compose.onNodeWithText("Compare saved watches").assertIsDisplayed()
        compose.onNodeWithText("Discard request").performClick()
        compose.runOnIdle { assertNull(discarded) }
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { assertEquals("request", discarded) }
    }
}
