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
import work.kumarfamilynet.cinemarchive.data.CatalogLookup
import work.kumarfamilynet.cinemarchive.feature.discover.*

@RunWith(AndroidJUnit4::class)
class DiscoverLookupScreenTest {
    @get:Rule val compose = createComposeRule()
    private val movie = TrendingTitle(42, "Shared Movie", 2020, MediaType.MOVIE, null, null)
    private val tv = TrendingTitle(42, "Shared TV", 2021, MediaType.TV, null, null)
    private val person = CatalogLookup(1, "A Person", "Known title", null)
    private val studio = CatalogLookup(2, "A Studio", "US", null)

    private fun show(studios: suspend (Int, MediaType?) -> List<TrendingTitle> = { _, _ -> listOf(movie, tv) },
        onAdd: (TrendingTitle) -> Unit = {}) {
        compose.setContent { CinemArchiveTheme {
            val store = remember { ViewModelStore() }
            val model = remember { DiscoverViewModel({ _, _, _ -> emptyList() }, { emptyList() },
                searchPeople = { listOf(person) }, searchStudios = { listOf(studio) },
                personTitles = { listOf(movie, tv) }, studioTitles = studios).also { store.put("discover", it) } }
            DisposableEffect(store) { onDispose { store.clear() } }
            val state by model.uiState.collectAsState()
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                Box(Modifier.width(360.dp).fillMaxSize()) {
                    DiscoverScreen(state.query, model::onQueryChange, state.typeFilter, model::onTypeChange,
                        state.titles, state.isLoading, state.isRefreshing, state.error, model::retry, model::refresh,
                        setOf(42 to MediaType.MOVIE), {}, onAdd, 2, {}, mode = state.mode, onModeChange = model::onModeChange,
                        lookups = state.lookups, selectedLookup = state.selectedLookup, onLookupSelect = model::onLookupSelect,
                        onLookupBack = model::clearLookup)
                }
            }
        } }
    }
    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test fun peopleCreditsKeepMediaIdentityAndCanReturnToSearchAtLargeFont() {
        val added = mutableListOf<TrendingTitle>()
        show(onAdd = added::add)
        compose.onNodeWithText("People", useUnmergedTree = true).performClick()
        waitText("Search for a person")
        compose.onNode(hasSetTextAction()).performTextInput("Person")
        waitText("Known title"); compose.onNodeWithText("A Person").performClick()
        waitText("Shared TV")
        compose.onNodeWithText("+ Add").performClick()
        compose.runOnIdle { assertEquals(listOf(tv), added) }
        compose.onNodeWithText("TV", useUnmergedTree = true).performClick()
        waitText("1 titles"); compose.onAllNodesWithText("Shared Movie").assertCountEquals(0)
        compose.onNodeWithText("Back to people").performClick()
        waitText("Search for a person"); compose.onAllNodesWithText("Shared TV").assertCountEquals(0)
    }

    @Test fun studioSelectionRetriesAndTypeChangeRequestsTelevisionCatalog() {
        var calls = 0
        val kinds = mutableListOf<MediaType?>()
        show(studios = { _, type -> kinds += type; if (++calls == 1) error("Offline studio") else listOf(tv) })
        compose.onNodeWithText("Studios", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Studio")
        waitText("US"); compose.onNodeWithText("A Studio").performClick()
        waitText("Offline studio"); compose.onNodeWithText("Retry").performClick()
        waitText("Shared TV"); compose.onNodeWithText("TV", useUnmergedTree = true).performClick()
        compose.waitUntil { kinds.lastOrNull() == MediaType.TV }
        compose.onNodeWithText("Back to studios").performClick(); waitText("Search for a studio")
    }
}
