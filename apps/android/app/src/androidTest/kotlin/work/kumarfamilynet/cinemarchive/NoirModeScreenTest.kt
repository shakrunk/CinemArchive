package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.designsystem.*
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.*
import work.kumarfamilynet.cinemarchive.feature.library.*

class NoirModeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val base = Color(0xFF9B6347)
    private fun detail() = TitleDetail("noir", MediaType.TV, "Noir", 2026, null, null, null, null, null, 30,
        LibraryStatus.WATCHING, null, null, emptyList(), listOf(SeasonDetail("main", 1, 1, 1, 2026, listOf(
            EpisodeDetail("episode", 1, "One", null, 30, 1, null,
                watchEvents = listOf(EpisodeWatch("watch", null, colorMode = "bw")),
                reviews = listOf(EpisodeReview("review", "Color review", "date", "color")))))), emptyList(), tmdbId = SPIDER_NOIR_TMDB_ID)
    private class Source : TitlePinsSource {
        override val state = MutableStateFlow(TitlePinsState(loaded = true))
        var save: suspend (String?) -> Unit = { mode -> state.value = state.value.copy(pins = if (mode == null) emptyMap() else mapOf("noir" to mode), pending = setOf("noir")) }
        var retries = 0
        override suspend fun set(titleId: String, mode: String?) { save(mode) }
        override fun retry() { retries++ }
    }
    private fun pixel(): Color {
        val image = compose.onNodeWithTag("artwork").captureToImage().toPixelMap()
        return image[image.width / 2, image.height / 2]
    }
    private fun gray() { val color = pixel(); assertEquals(color.red, color.green, .015f); assertEquals(color.red, color.blue, .015f) }

    @Test fun modesOnlyUnlockFromWatchesAndPinRequiresAllMainEpisodes() {
        val selected = mutableStateOf<String?>(null)
        compose.setContent { CinemArchiveTheme {
            NoirModeSelector(NoirProgress(setOf("bw", "color"), setOf("bw"), null), selected.value, null,
                onSelect = { selected.value = it }, onPin = {})
        } }
        compose.onNodeWithText("Normal").assertIsSelected()
        compose.onNodeWithText("Color").performClick()
        compose.onAllNodesWithText("Pin Color mode").assertCountEquals(0)
        compose.onNodeWithText("B&W").performClick()
        compose.onNodeWithText("Pin B&W mode").assertIsDisplayed()
    }
    @Test fun actualArtworkFilterPinsAcrossCloseAndUnpinRestoresNormal() {
        val open = mutableStateOf(true); val mode = mutableStateOf<String?>(null); val pins = mutableStateOf<Map<String, String>>(emptyMap())
        compose.setContent { CinemArchiveTheme { Column {
            val effect = effectiveNoirMode(if (open.value) "noir" else null, NoirPreview("noir", mode.value), pins.value)
            Box(Modifier.size(90.dp).testTag("artwork").noirVisualEffect(effect).background(base))
            if (open.value) NoirModeSelector(NoirProgress(setOf("bw", "color"), setOf("bw", "color"), null), mode.value, pins.value["noir"],
                onSelect = { mode.value = it }, onPin = { pins.value = if (it == null) emptyMap() else mapOf("noir" to it) })
            TextButton(onClick = { open.value = !open.value }) { Text(if (open.value) "Close detail" else "Open detail") }
        } } }
        val original = pixel()
        compose.onNodeWithText("B&W").performClick(); gray()
        compose.onNodeWithText("Pin B&W mode").performClick()
        compose.onNodeWithText("Close detail").performClick(); gray()
        compose.onNodeWithText("Open detail").performClick()
        compose.onNodeWithText("Unpin B&W mode").performClick()
        compose.onNodeWithText("Color").performClick()
        val colored = pixel(); assertTrue(kotlin.math.abs(colored.red - original.red) > .03f || kotlin.math.abs(colored.blue - original.blue) > .03f)
        compose.onNodeWithText("Close detail").performClick()
        val restored = pixel(); assertEquals(original.red, restored.red, .01f); assertEquals(original.green, restored.green, .01f); assertEquals(original.blue, restored.blue, .01f)
    }
    @Test fun pinnedModeSeedsControlsAndFailedAdmissionKeepsActionableError() {
        val source = Source(); source.state.value = TitlePinsState(loaded = true, pins = mapOf("noir" to "bw"))
        val blocker = CompletableDeferred<Unit>(); source.save = { blocker.await(); error("Disk full") }
        compose.setContent { CinemArchiveTheme { NoirDetailControls(detail(), source, null, {}) } }
        compose.waitUntil { compose.onAllNodesWithText("Unpin B&W mode").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("B&W").assertIsSelected()
        compose.onNodeWithText("Unpin B&W mode").performClick()
        compose.onNodeWithText("Normal").assertIsNotEnabled()
        compose.runOnIdle { blocker.complete(Unit) }
        compose.waitUntil { compose.onAllNodesWithText("Disk full").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unpin B&W mode").assertIsEnabled()
        compose.onNodeWithText("Retry filter sync").performClick()
        compose.runOnIdle { assertEquals(1, source.retries) }
    }
    @Test fun successfulLogSwitchesModeAndNewOwnerCannotInheritPreviewOrPins() {
        val owner = mutableStateOf("A"); val logged = mutableStateOf<String?>(null)
        val sourceA = Source(); sourceA.state.value = TitlePinsState(loaded = true, pins = mapOf("noir" to "bw"))
        val sourceB = Source()
        compose.setContent { CinemArchiveTheme { key(owner.value) {
            var preview by remember { mutableStateOf<String?>(null) }
            Column {
                Box(Modifier.size(90.dp).testTag("artwork").noirVisualEffect(preview).background(base))
                if (owner.value == "A") NoirDetailControls(detail(), sourceA, logged.value) { preview = it }
                else NoirDetailControls(detail().copy(tmdbId = 42), sourceB, null) { preview = it }
            }
        } } }
        compose.waitUntil { compose.onAllNodesWithText("Unpin B&W mode").fetchSemanticsNodes().isNotEmpty() }; gray()
        compose.runOnIdle { logged.value = "new-review|color" }
        compose.waitForIdle(); val color = pixel(); assertTrue(kotlin.math.abs(color.red - color.green) > .05f)
        compose.runOnIdle { owner.value = "B" }
        compose.waitForIdle(); val normal = pixel(); assertEquals(base.red, normal.red, .01f); assertEquals(base.green, normal.green, .01f)
        compose.onAllNodesWithText("Filter stays on when you leave").assertCountEquals(0)
    }
}
