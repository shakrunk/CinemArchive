package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.*

@RunWith(AndroidJUnit4::class)
class GlobalSearchScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun movie(id: String, name: String, type: MediaType = MediaType.MOVIE) =
        LibraryTitle(id, name, 2026, null, LibraryStatus.WATCHLIST, type, "Director", null, null, genres = listOf("Drama"))

    @Test fun keyboardEntryRankingArrowsEnterEscapeAndClearWorkWithoutMovingFocus() {
        var selected: String? = null
        compose.setContent { CinemArchiveTheme {
            var open by remember { mutableStateOf(false) }
            val shellFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { shellFocus.requestFocus() }
            Column(Modifier.statusBarsPadding().onPreviewKeyEvent {
                if (it.opensGlobalSearch()) { open = true; true } else false
            }.focusRequester(shellFocus).focusable().testTag("command-shell")) {
                TextButton(onClick = { open = true }) { Text("Search") }
                if (open) GlobalSearchDialog("owner", listOf(AppCommand("a", "Alien"), AppCommand("b", "Aliens")),
                    { open = false }, { selected = it.id; open = false })
            }
        } }
        compose.onNodeWithText("Search").performClick()
        val field = compose.onNode(hasSetTextAction())
        field.assertIsFocused().performTextInput("ali")
        field.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals("b", selected) }
        compose.onNodeWithTag("command-shell").assertIsFocused()
        compose.onNodeWithText("Search").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.K); keyUp(Key.CtrlLeft) }
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("absent")
        compose.onNodeWithText("No results").assertIsDisplayed()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithText("Alien").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
        compose.onAllNodesWithText("Search and actions").assertCountEquals(0)
    }

    @Test fun ticketsActionUsesOnlyCurrentOwnerMoviesAndDispatchesExactTitle() {
        var scheduled: String? = null
        var opened: String? = null
        var command: String? = null
        compose.setContent { CinemArchiveTheme {
            OwnerGlobalSearch("owner", listOf(movie("film", "Movie night"), movie("tv", "Series", MediaType.TV)),
                {}, { command = it }, { opened = it }, { scheduled = it })
        } }
        compose.onNode(hasSetTextAction()).performTextInput("tickets")
        compose.onNodeWithText("I've got tickets…").performClick()
        compose.onNodeWithText("Which movie are your tickets for?").assertIsDisplayed()
        compose.onNodeWithText("Movie night").performClick()
        compose.runOnIdle { assertEquals("film", scheduled); assertNull(opened); assertNull(command) }
        compose.onNode(hasSetTextAction()).performTextInput("Series")
        compose.onNodeWithText("No results").assertIsDisplayed()
    }

    @Test fun accountScopeChangeDropsOldQuerySelectionAndCachedTitles() {
        val owner = mutableStateOf("one")
        var selected: String? = null
        compose.setContent { CinemArchiveTheme {
            GlobalSearchDialog(owner.value,
                if (owner.value == "one") listOf(AppCommand("old", "Private old film"))
                else listOf(AppCommand("new", "New account film")), {}, { selected = it.id })
        } }
        compose.onNode(hasSetTextAction()).performTextInput("Private")
        compose.runOnIdle { owner.value = "two" }
        compose.onAllNodesWithText("Private old film").assertCountEquals(0)
        compose.onNodeWithText("New account film").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals("new", selected) }
    }

    @Test fun anonymousArchiveSearchOffersOnlyScopedTitlesAndReadonlyDestinations() {
        val repo = SharingRepository(SupabaseRestClient("https://unused.invalid", "public"), { null },
            object : SharedLibraryTransport {
                override fun rpc(name: String, paramsJson: String) = """{"ownerUserId":"owner","hasMore":false,"ledgerLayout":null,
                  "titles":[{"id":"shared","user_id":"owner","tmdb_id":1,"type":"movie","title":"Scoped film","year":2026,
                  "status":"watched","genres":["Drama"],"notes":"Public notes","added_at":"2026-01-01T00:00:00Z"}]}"""
            })
        compose.setContent { CinemArchiveTheme { SharedLibraryRoute("token", repo, {}) } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Scoped film").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search archive").performClick()
        compose.onNodeWithText("Go to the Ledger").assertIsDisplayed()
        listOf("Add a title", "I've got tickets…", "Go to Friends", "Go to Profile & Settings").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Scoped")
        compose.onNode(hasText("Scoped film") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Public notes").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Save tickets").assertCountEquals(0)
    }
}
