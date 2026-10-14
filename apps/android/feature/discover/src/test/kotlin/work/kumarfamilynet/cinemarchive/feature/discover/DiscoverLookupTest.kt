package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.lifecycle.ViewModelStore
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle
import work.kumarfamilynet.cinemarchive.data.CatalogLookup

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverLookupTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val person = CatalogLookup(1, "A Person", "A Movie", null)
    private fun title(kind: MediaType) = TrendingTitle(1, kind.name, 2026, kind, null, null)
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { store.clear(); Dispatchers.resetMain() }

    @Test fun peopleSearchSelectionAndTypeFilterUseOneCompleteCreditResponse() = runTest(dispatcher) {
        var calls = 0
        val model = DiscoverViewModel({ _, _, _ -> emptyList() }, { emptyList() },
            searchPeople = { assertEquals("Person", it); listOf(person) },
            personTitles = { calls++; listOf(title(MediaType.MOVIE), title(MediaType.TV)) }).also { store.put("model", it) }
        runCurrent(); model.onModeChange(DiscoverMode.PEOPLE); runCurrent()
        assertTrue(model.uiState.value.titles.isEmpty()); assertFalse(model.uiState.value.isLoading)
        model.onQueryChange("Person"); advanceTimeBy(300); runCurrent()
        assertEquals(listOf(person), model.uiState.value.lookups)
        model.onQueryChange(" Person "); runCurrent()
        assertEquals(listOf(person), model.uiState.value.lookups)
        model.onLookupSelect(person); runCurrent()
        assertEquals(2, model.uiState.value.titles.size); assertFalse(model.uiState.value.hasMore)
        model.onTypeChange(TypeFilter.TV); runCurrent()
        assertEquals(listOf(title(MediaType.TV)), model.uiState.value.titles); assertEquals(1, calls)
        model.onTypeChange(TypeFilter.ALL); runCurrent(); assertEquals(2, model.uiState.value.titles.size)
        model.clearLookup(); runCurrent(); assertTrue(model.uiState.value.titles.isEmpty()); assertNull(model.uiState.value.selectedLookup)
    }

    @Test fun changedModeIgnoresUncancellableOlderPersonCredits() = runTest(dispatcher) {
        lateinit var delayed: Continuation<List<TrendingTitle>>
        val studio = CatalogLookup(2, "Studio", "", null)
        val model = DiscoverViewModel({ _, _, _ -> emptyList() }, { emptyList() }, searchPeople = { listOf(person) },
            searchStudios = { listOf(studio) }, personTitles = { suspendCoroutine { delayed = it } },
            studioTitles = { _, _ -> listOf(title(MediaType.TV)) }).also { store.put("model", it) }
        runCurrent(); model.onModeChange(DiscoverMode.PEOPLE); model.onQueryChange("Person"); advanceTimeBy(300); runCurrent()
        model.onLookupSelect(person); runCurrent()
        model.onModeChange(DiscoverMode.STUDIOS); model.onQueryChange("Studio"); advanceTimeBy(300); runCurrent()
        model.onLookupSelect(studio); runCurrent(); delayed.resume(listOf(title(MediaType.MOVIE))); runCurrent()
        assertEquals(studio, model.uiState.value.selectedLookup); assertEquals(listOf(title(MediaType.TV)), model.uiState.value.titles)
        assertFalse(model.uiState.value.isLoading)
    }

    @Test fun studioTypeRetryRetainsSelectionAndTypingReturnsToPicker() = runTest(dispatcher) {
        var fail = true
        val kinds = mutableListOf<MediaType?>()
        val model = DiscoverViewModel({ _, _, _ -> emptyList() }, { emptyList() }, searchStudios = { listOf(person) },
            studioTitles = { _, kind -> kinds += kind; if (fail) error("Offline") else listOf(title(kind ?: MediaType.MOVIE)) })
            .also { store.put("model", it) }
        runCurrent(); model.onModeChange(DiscoverMode.STUDIOS); model.onQueryChange("Person"); advanceTimeBy(300); runCurrent()
        model.onLookupSelect(person); runCurrent(); assertEquals("Offline", model.uiState.value.error)
        fail = false; model.retry(); runCurrent(); assertNull(model.uiState.value.error)
        model.onTypeChange(TypeFilter.TV); runCurrent()
        assertEquals(person, model.uiState.value.selectedLookup); assertEquals(MediaType.TV, kinds.last())
        model.loadMore(); runCurrent(); assertEquals(3, kinds.size)
        model.onQueryChange(person.name + " "); advanceTimeBy(300); runCurrent()
        assertNull(model.uiState.value.selectedLookup); assertTrue(model.uiState.value.titles.isEmpty())
        assertEquals(listOf(person), model.uiState.value.lookups)
    }
}
