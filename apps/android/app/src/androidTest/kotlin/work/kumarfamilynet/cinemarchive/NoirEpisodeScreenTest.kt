package work.kumarfamilynet.cinemarchive

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen

/** Synthetic owner archive only; no device account or network. */
class NoirEpisodeScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun detail(tmdb: Int = 220102) = TitleDetail("noir", MediaType.TV, "Synthetic Noir", 2026,
        null, null, null, null, null, 45, LibraryStatus.WATCHING, null, null, emptyList(),
        listOf(SeasonDetail("season", 1, 1, 0, 2026, listOf(EpisodeDetail("episode", 1, "The case", null, 45, 0, null)))),
        emptyList(), tmdbId = tmdb)
    private fun show(save: suspend (EpisodeLogDraft) -> Unit) {
        compose.setContent { CinemArchiveTheme { TitleDetailScreen(detail(), {}, onSaveEpisodeLog = { _, draft -> save(draft) }) } }
        openLog()
    }
    private fun openLog() {
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("Log watch, rating, or review"))
        compose.onNodeWithText("Log watch, rating, or review").performClick()
    }
    private fun save() = compose.onNodeWithText("Save").performScrollTo().performClick()

    @Test fun blackAndWhiteWatchAndReviewKeepExactModeAndValuesOnFailedSaveRetry() {
        val saved = mutableListOf<EpisodeLogDraft>()
        val blocked = CompletableDeferred<Unit>()
        show { draft -> saved += draft; if (saved.size == 1) { blocked.await(); error("Disk full") } }
        compose.onNodeWithText("Review (optional, logged independently)").performScrollTo().performTextInput("Noir review")
        save()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        compose.onNodeWithText("Authentic Black & White").performClick()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle { blocked.complete(Unit) }
        compose.waitUntil { compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Review (optional, logged independently)").assertIsNotEnabled()
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.waitUntil { saved.size == 2 }
        compose.runOnIdle {
            assertEquals(saved[0], saved[1]); assertEquals("bw", saved[1].colorMode)
            assertEquals("Noir review", saved[1].reviewText); assertTrue(saved[1].includeWatch)
        }
    }

    @Test fun reviewWithoutWatchOffersFullColorAndPreservesIndependentLog() {
        val saved = mutableListOf<EpisodeLogDraft>()
        show { saved += it }
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNodeWithText("Review (optional, logged independently)").performScrollTo().performTextInput("Color review")
        save()
        compose.onNodeWithText("True-Hue Full Color").performClick()
        compose.waitUntil { saved.size == 1 }
        compose.runOnIdle { assertFalse(saved.single().includeWatch); assertEquals("color", saved.single().colorMode) }
    }

    @Test fun skipSavesAnUntaggedWatch() {
        val saved = mutableListOf<EpisodeLogDraft>()
        show { saved += it }
        save()
        compose.onNodeWithText("Not now").performClick()
        compose.waitUntil { saved.size == 1 }
        compose.runOnIdle { assertNull(saved.single().colorMode); assertTrue(saved.single().includeWatch) }
    }

    @Test fun ratingOnlyAndOrdinaryTitleDoNotPromptForNoirMode() {
        val saved = mutableListOf<EpisodeLogDraft>()
        val current = mutableStateOf(detail())
        compose.setContent { CinemArchiveTheme { TitleDetailScreen(current.value, {}, onSaveEpisodeLog = { _, draft -> saved += draft }) } }
        openLog()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNode(hasContentDescription("Change rating") and hasAnyAncestor(isDialog()))
            .performScrollTo().performClick()
        val hint = compose.onNodeWithText("Drag anywhere above — half-star precision").fetchSemanticsNode().boundsInRoot
        val picker = compose.onNode(isDialog() and hasAnyDescendant(hasText("Rate this title")))
        val bounds = picker.fetchSemanticsNode().boundsInRoot
        picker.performTouchInput {
            val y = hint.top - bounds.top - 24.dp.toPx()
            swipe(Offset(width * 0.35f, y), Offset(width * 0.75f, y), durationMillis = 300)
        }
        compose.onNodeWithText("Done").performClick()
        save()
        compose.waitUntil { saved.size == 1 }
        compose.runOnIdle {
            assertFalse(saved.single().includeWatch); assertNotNull(saved.single().rating); assertNull(saved.single().colorMode)
            current.value = detail(42)
        }
        compose.onAllNodesWithText("How did you experience this?").assertCountEquals(0)
        openLog(); save()
        compose.waitUntil { saved.size == 2 }
        compose.runOnIdle { assertTrue(saved[1].includeWatch); assertNull(saved[1].colorMode) }
        compose.onAllNodesWithText("How did you experience this?").assertCountEquals(0)
    }
}
