package work.kumarfamilynet.cinemarchive

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.feature.library.TitleDetailScreen
import work.kumarfamilynet.cinemarchive.feature.upnext.UpNextEpisodeActions
import work.kumarfamilynet.cinemarchive.feature.upnext.UpNextScreen

/** Synthetic owner UI only: no account, network, or existing device archive is accessed. */
@RunWith(AndroidJUnit4::class)
class OwnerEpisodeHistoryScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(text))
    }

    @Test fun episodeRewatchCancelSaveAndExactHistoryDeletion() {
        val saved = mutableListOf<Pair<String, EpisodeLogDraft>>()
        val deleted = mutableListOf<Pair<String, String>>()
        val episode = EpisodeDetail("episode", 1, "Opening night", "2020-01-01", 30, 1, 4.5,
            watchEvents = listOf(EpisodeWatch("original-watch", "2026-01-02", "Original memory")),
            ratings = listOf(EpisodeRating("original-rating", 4.5, "2026-01-02T12:00:00Z")),
            reviews = listOf(EpisodeReview("original-review", "Original review", "2026-01-02T12:00:00Z")))
        val detail = TitleDetail("show", MediaType.TV, "Synthetic owner show", 2020, null, null, null, null, null, 30,
            LibraryStatus.WATCHING, null, null, emptyList(), listOf(SeasonDetail("season", 1, 1, 1, 2020, listOf(episode))), emptyList())
        compose.setContent { CinemArchiveTheme {
            TitleDetailScreen(detail, {}, onSaveEpisodeLog = { id, draft -> saved += id to draft }, onDeleteEpisodeWatch = { id, event -> deleted += id to event })
        } }
        scrollTo("Add rating, review, or rewatch")
        compose.onNodeWithText("Add rating, review, or rewatch").performClick()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        scrollTo("Add rating, review, or rewatch")
        compose.onNodeWithText("Add rating, review, or rewatch").performClick()
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.onNodeWithText("Watch notes (optional)").performScrollTo().performTextInput("Before joining memory")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil { saved.size == 1 }
        compose.runOnIdle {
            assertEquals("episode", saved.single().first)
            assertNull(saved.single().second.watchedAt)
            assertEquals("Before joining memory", saved.single().second.watchNotes)
            assertNotEquals("original-watch", saved.single().second.watchEventId)
        }
        scrollTo("Delete watch")
        compose.onNodeWithText("Delete watch").performClick()
        compose.onNode(hasText("Delete watch") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil { deleted.size == 1 }
        compose.runOnIdle { assertEquals(listOf("episode" to "original-watch"), deleted) }
        scrollTo("Original review")
        compose.onNodeWithText("Original review").assertIsDisplayed()
    }

    @Test fun finaleKeepsExactUndoThenRequiresExplicitSeriesCompletion() {
        val deleted = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val card = UpNextWatching("show", "Synthetic finale", null, 1, 2, 1, 2, "Finale", "2020-01-01", "episode")
        compose.setContent {
            var board by remember { mutableStateOf(UpNextBoard(listOf(card), emptyList())) }
            val scope = rememberCoroutineScope()
            val actions = remember {
                UpNextEpisodeActions(scope,
                    advance = { board = board.copy(watching = emptyList()); EpisodeWatchReceipt("show", "episode", "exact-event", 1, 2, true) },
                    deleteWatch = { deleted += it.watchEventId; board = board.copy(watching = listOf(card)) },
                    markSeriesWatched = { finished += it }, undoWindowMillis = 60_000)
            }
            val state by actions.state.collectAsState()
            CinemArchiveTheme {
                UpNextScreen(board, {}, {}, actions::mark, {}, { _, _, _ -> }, { _, _ -> }, {}, {},
                    episodeActions = state, onUndoEpisode = actions::undo, onMarkSeriesWatched = actions::finishSeries)
            }
        }
        compose.onNodeWithContentDescription("Mark episode watched").performClick()
        compose.onNodeWithText("All caught up").assertIsDisplayed()
        compose.runOnIdle { assertTrue(finished.isEmpty()); assertTrue(deleted.isEmpty()) }
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithContentDescription("Mark episode watched").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("exact-event"), deleted); assertTrue(finished.isEmpty()) }
        compose.onNodeWithContentDescription("Mark episode watched").performClick()
        compose.onNodeWithText("Mark series watched").performClick()
        compose.runOnIdle { assertEquals(listOf("show"), finished) }
        compose.onAllNodesWithText("All caught up").assertCountEquals(0)
    }
}
