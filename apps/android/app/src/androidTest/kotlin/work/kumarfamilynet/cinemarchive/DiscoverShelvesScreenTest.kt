package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.DiscoverLibrary
import work.kumarfamilynet.cinemarchive.data.DiscoverLibraryTitle
import work.kumarfamilynet.cinemarchive.feature.discover.*

@RunWith(AndroidJUnit4::class)
class DiscoverShelvesScreenTest {
    @get:Rule val compose = createComposeRule()
    private val movie = TrendingTitle(42, "Owned movie", 2020, MediaType.MOVIE, null, null)
    private val tv = TrendingTitle(42, "Unowned series", 2021, MediaType.TV, null, null)
    private val library = MutableStateFlow(DiscoverLibrary(
        (1..9).map { DiscoverLibraryTitle("seed-$it", if (it == 1) 42 else it, "Seed $it", MediaType.MOVIE) },
        (1..9).map { LibraryPerson(it, "Actor $it") },
    ))
    private val opened = mutableListOf<TrendingTitle>()
    private val added = mutableListOf<TrendingTitle>()
    private val seeds = mutableListOf<Pair<Int, MediaType>>()
    private val people = mutableListOf<Int>()

    private fun show(recommendations: suspend (Int, MediaType) -> List<TrendingTitle> = { _, _ -> listOf(movie, tv) },
                     personTitles: suspend (Int) -> List<TrendingTitle> = { listOf(tv.copy(tmdbId = 90 + it, title = "Filmography $it")) }) {
        compose.setContent { CinemArchiveTheme {
            val store = remember { ViewModelStore() }
            val model = remember { DiscoverShelvesViewModel(library,
                { id, type -> seeds += id to type; recommendations(id, type) },
                { id -> people += id; personTitles(id) }).also { store.put("shelves", it) } }
            DisposableEffect(store) { onDispose { store.clear() } }
            val state by model.state.collectAsState()
            var search by remember { mutableStateOf("") }
            var type by remember { mutableStateOf(TypeFilter.ALL) }
            var mode by remember { mutableStateOf(DiscoverMode.TITLES) }
            var genre by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(search, mode, genre) { model.setVisible(search.isBlank() && mode == DiscoverMode.TITLES && genre == null) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                Box(Modifier.width(360.dp).fillMaxSize()) {
                    DiscoverScreen(search, { search = it }, type, { type = it }, emptyList(), false, false, null,
                        {}, {}, state.library.ownedKeys, opened::add, added::add, 2, {},
                        genreId = genre, onGenreChange = { genre = it }, mode = mode, onModeChange = { mode = it },
                        shelves = state, onRecommendationTitle = model::selectTitle, onStarringPerson = model::selectPerson,
                        onRetryRecommendations = model::retryRecommendations, onRetryStarring = model::retryStarring)
                }
            }
        } }
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun shelf(key: String) { compose.onNodeWithTag("discover-grid").performScrollToKey(key) }
    private fun top() { compose.onNodeWithTag("discover-grid").performScrollToIndex(0); compose.waitForIdle() }

    @Test fun recommendationsPreserveTypeIdentityAndUseExistingPreviewAndAddActions() {
        show()
        waitFor("Because You Watched")
        shelf("recommendations")
        compose.onNodeWithTag("recommendations-MOVIE:42").assertDoesNotExist()
        val poster = hasText("Unowned series") and hasClickAction()
        compose.onNodeWithTag("discover-grid").performScrollToNode(poster)
        val card = compose.onNode(poster).assertIsDisplayed()
        val cardBounds = card.getUnclippedBoundsInRoot()
        val visibleCardBounds = card.getBoundsInRoot()
        assertEquals("Poster top must be visible", cardBounds.top.value, visibleCardBounds.top.value, 0.5f)
        assertTrue("Visible artwork must have a touch target", visibleCardBounds.bottom.value - visibleCardBounds.top.value >= 48f)
        val visibleArtworkHeight = card.fetchSemanticsNode().boundsInRoot.height
        card.performTouchInput { click(Offset(center.x, visibleArtworkHeight * 0.25f)) }
        compose.runOnIdle { assertEquals("Physical poster tap opens preview; bounds=$cardBounds", listOf(tv), opened) }
        val add = hasText("+ Add") and hasAnyAncestor(hasTestTag("recommendations-TV:42"))
        // Reveal the lower action with the same physical vertical scroll a phone user makes.
        compose.onNodeWithTag("discover-grid").performTouchInput {
            swipe(Offset(center.x, height * 0.8f), Offset(center.x, height * 0.45f), durationMillis = 500)
        }
        val button = compose.onNode(add).assertIsDisplayed()
        val buttonBounds = button.getUnclippedBoundsInRoot()
        val visibleButtonBounds = button.getBoundsInRoot()
        assertEquals("Add top must be visible", buttonBounds.top.value, visibleButtonBounds.top.value, 0.5f)
        assertEquals("Add bottom must be visible", buttonBounds.bottom.value, visibleButtonBounds.bottom.value, 0.5f)
        button.performClick()
        compose.runOnIdle { assertEquals("Physical Add tap starts add flow; bounds=$buttonBounds", listOf(tv), added) }
        top()
        compose.onNodeWithText("Movies", useUnmergedTree = true).performClick()
        shelf("recommendations")
        compose.onNodeWithText("No recommendations found for this title — try picking another.").assertExists()
        compose.runOnIdle { assertEquals(listOf(42 to MediaType.MOVIE), seeds) }
        top()
        compose.onNodeWithText("TV", useUnmergedTree = true).performClick()
        shelf("recommendations")
        compose.onNodeWithTag("recommendations-TV:42").assertExists()
        compose.runOnIdle { library.value = library.value.copy(titles = library.value.titles + DiscoverLibraryTitle("owned-tv", 42, "Owned TV", MediaType.TV)) }
        compose.waitUntil { compose.onAllNodesWithTag("recommendations-TV:42").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun bothSearchablePickersChangeOnlyTheirOwnShelfAtLargeTextSize() {
        show()
        waitFor("Because You Watched")
        shelf("recommendations")
        compose.onNodeWithContentDescription("Choose a title to base recommendations on").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Seed 9")
        compose.onNodeWithTag("recommendations-choice-seed-9").performClick()
        compose.waitUntil { seeds.size == 2 }
        compose.runOnIdle { assertEquals(9 to MediaType.MOVIE, seeds.last()); assertEquals(listOf(1), people) }
        shelf("starring")
        compose.onNodeWithContentDescription("Choose an actor to see more of their titles").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Actor 9")
        compose.onNodeWithTag("starring-choice-9").performClick()
        waitFor("Filmography 9")
        compose.runOnIdle { assertEquals(listOf(1, 9), people); assertEquals(2, seeds.size) }
        compose.onNodeWithText("Filmography 9").performClick()
        compose.runOnIdle { assertEquals(99, opened.single().tmdbId) }
    }

    @Test fun retryEmptyAndSearchGenreModeVisibilityRemainIndependent() {
        var attempts = 0
        show(recommendations = { _, _ -> if (++attempts == 1) error("Offline"); emptyList() })
        waitFor("Retry recommendations")
        shelf("recommendations")
        compose.onNodeWithText("Retry recommendations").performClick()
        waitFor("No recommendations found for this title — try picking another.")
        shelf("starring")
        compose.onNodeWithText("Filmography 1").assertExists()
        top()
        compose.onNode(hasSetTextAction()).performTextInput("Search")
        compose.onNodeWithTag("discover-shelf-recommendations").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextClearance()
        waitFor("Because You Watched")
        compose.onNodeWithText("Genre: All genres").performClick()
        compose.onNodeWithText("Drama").performClick()
        compose.onNodeWithTag("discover-shelf-recommendations").assertDoesNotExist()
        compose.onNodeWithText("Genre: Drama").performClick()
        compose.onNodeWithText("All genres").performClick()
        waitFor("Because You Watched")
        compose.onNodeWithText("People", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("discover-shelf-recommendations").assertDoesNotExist()
        compose.onNodeWithTag("discover-shelf-starring").assertDoesNotExist()
    }
}
