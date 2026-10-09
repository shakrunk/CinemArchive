package work.kumarfamilynet.cinemarchive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import work.kumarfamilynet.cinemarchive.core.designsystem.CinemArchiveTheme
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.feature.discover.*

@RunWith(AndroidJUnit4::class)
class DiscoverBrowseScreenTest {
    @get:Rule val compose = createComposeRule()
    private val movie = TrendingTitle(42, "Movie 42", 2020, MediaType.MOVIE, null, null)
    private val tv = TrendingTitle(42, "TV 42", 2021, MediaType.TV, null, null)

    private fun show(
        browse: suspend (MediaType?, Int?, Int) -> List<TrendingTitle>,
        onOpen: (TrendingTitle) -> Unit = {},
        onAdd: (TrendingTitle) -> Unit = {},
    ) {
        compose.setContent { CinemArchiveTheme {
            val store = remember { ViewModelStore() }
            val model = remember { DiscoverViewModel(browse, { listOf(MediaSearchResult(84, "Search hit", 2024, MediaType.TV, null, null)) }).also { store.put("discover", it) } }
            DisposableEffect(store) { onDispose { store.clear() } }
            val state by model.uiState.collectAsState()
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                Box(Modifier.width(360.dp).fillMaxSize()) {
                    DiscoverScreen(
                        search = state.query, onSearchChange = model::onQueryChange,
                        typeFilter = state.typeFilter, onTypeFilterChange = model::onTypeChange,
                        titles = state.titles, isLoading = state.isLoading, isRefreshing = state.isRefreshing,
                        error = state.error, onRetry = model::retry, onRefresh = model::refresh,
                        addedIds = setOf(42 to MediaType.MOVIE), onOpenTitle = onOpen, onAdd = onAdd,
                        gridColumns = 2, onGridColumnsChange = {}, genreId = state.genreId,
                        onGenreChange = model::onGenreChange, hasMore = state.hasMore,
                        isLoadingMore = state.isLoadingMore, moreError = state.moreError, onLoadMore = model::loadMore,
                    )
                }
            }
        } }
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun genreTypeAndPaginationRetryUseTheActualControllerWithoutLosingCards() {
        val requests = mutableListOf<Triple<MediaType?, Int?, Int>>()
        var pageTwoAttempts = 0
        show({ type, genre, page ->
            requests += Triple(type, genre, page)
            if (page == 2 && ++pageTwoAttempts == 1) error("Connection lost")
            when (page) { 1 -> listOf(movie, tv).filter { type == null || it.type == type }; 2 -> listOf(movie.copy(tmdbId = 84, title = "Next movie")); else -> emptyList() }
        })
        waitFor("View more")
        compose.onNodeWithText("Genre: All genres").performClick()
        compose.onNodeWithText("Drama").performClick()
        waitFor("Genre: Drama")
        compose.onNodeWithText("View more").performClick()
        waitFor("Retry more")
        compose.onNodeWithText("Connection lost").assertIsDisplayed()
        compose.onNodeWithText("Movie 42").assertExists()
        compose.onNodeWithText("Retry more").performClick()
        waitFor("3 titles")
        compose.onNodeWithText("View more").performClick()
        compose.waitUntil { compose.onAllNodesWithText("View more").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("TV", useUnmergedTree = true).performClick()
        waitFor("1 titles")
        compose.onNodeWithText("Genre: All genres").assertIsDisplayed()
        compose.onNodeWithText("Genre: All genres").performClick()
        compose.onNodeWithText("Action & Adventure").assertExists()
        compose.onNodeWithText("Action & Adventure").performClick()
        compose.runOnIdle {
            assertEquals(2, requests.count { it == Triple(null, 18, 2) })
            assertEquals(Triple(MediaType.TV, 10759, 1), requests.last())
        }
    }

    @Test fun ownershipAndAddKeepSameNumberMovieAndTvDistinctAndSearchHidesPagination() {
        val opened = mutableListOf<TrendingTitle>()
        val added = mutableListOf<TrendingTitle>()
        show({ _, _, _ -> listOf(movie, tv) }, opened::add, added::add)
        waitFor("View more")
        compose.onNodeWithText("Movie 42").performClick()
        compose.onNodeWithText("+ Add").performClick()
        compose.runOnIdle { assertEquals(listOf(movie), opened); assertEquals(listOf(tv), added) }
        compose.onNode(hasSetTextAction()).performTextInput("Series")
        waitFor("Search hit")
        compose.onAllNodesWithText("View more").assertCountEquals(0)
        compose.onNode(hasSetTextAction()).performTextClearance()
        waitFor("View more")
        compose.onNodeWithText("Movie 42").assertExists()
    }
}
