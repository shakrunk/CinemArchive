package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.lifecycle.ViewModelStore
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val trending = TrendingTitle(1, "This week's hit", 2026, MediaType.MOVIE, null, null)
    private fun result(id: Int, type: MediaType = MediaType.MOVIE) =
        MediaSearchResult(id, "An alternate display title", 1942, type, null, "Synopsis")

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    private fun model(
        trendingSource: suspend () -> List<TrendingTitle> = { listOf(trending) },
        search: suspend (String) -> List<MediaSearchResult>,
    ) = DiscoverViewModel(trendingSource, search).also { store.put("discover", it) }

    @Test fun `nonblank search debounces a remote query and clearing restores trending`() = runTest(dispatcher) {
        val queries = mutableListOf<String>()
        var trendingCalls = 0
        val model = model(trendingSource = { trendingCalls++; listOf(trending) }) { queries += it; listOf(result(2)) }
        runCurrent()
        assertEquals(listOf(trending), model.uiState.value.titles)
        model.onQueryChange("  Casablanca  ")
        assertTrue(model.uiState.value.isLoading)
        assertTrue(model.uiState.value.titles.isEmpty())
        advanceTimeBy(299); runCurrent()
        assertTrue(queries.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("Casablanca"), queries)
        // Remote results can match an alternate title; do not re-filter by the display name.
        assertEquals(2, model.uiState.value.titles.single().tmdbId)
        assertFalse(model.uiState.value.isLoading)
        model.onQueryChange(" ")
        runCurrent()
        assertEquals(2, trendingCalls)
        assertEquals(listOf(trending), model.uiState.value.titles)
    }

    @Test fun `rapid typing starts only the latest search`() = runTest(dispatcher) {
        val queries = mutableListOf<String>()
        val model = model { queries += it; emptyList() }
        runCurrent()
        model.onQueryChange("Cas")
        advanceTimeBy(100)
        model.onQueryChange("Casa")
        advanceTimeBy(100)
        model.onQueryChange("Casablanca")
        advanceTimeBy(300); runCurrent()
        assertEquals(listOf("Casablanca"), queries)
        assertTrue(model.uiState.value.titles.isEmpty())
        assertNull(model.uiState.value.error)
    }

    @Test fun `a cancelled request that completes late cannot replace newer results`() = runTest(dispatcher) {
        lateinit var old: Continuation<List<MediaSearchResult>>
        val model = model { query -> if (query == "old") suspendCoroutine { old = it } else listOf(result(3)) }
        runCurrent()
        model.onQueryChange("old"); advanceTimeBy(300); runCurrent()
        model.onQueryChange("new"); advanceTimeBy(300); runCurrent()
        old.resume(listOf(result(2)))
        runCurrent()
        assertEquals("new", model.uiState.value.query)
        assertEquals(3, model.uiState.value.titles.single().tmdbId)
        assertNull(model.uiState.value.error)
    }

    @Test fun `a cancelled request failure cannot overwrite restored trending`() = runTest(dispatcher) {
        lateinit var old: Continuation<List<MediaSearchResult>>
        val model = model { suspendCoroutine { old = it } }
        runCurrent()
        model.onQueryChange("old"); advanceTimeBy(300); runCurrent()
        model.onQueryChange(""); runCurrent()
        old.resumeWithException(IllegalStateException("Old failure")); runCurrent()
        assertEquals(listOf(trending), model.uiState.value.titles)
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.isLoading)
    }

    @Test fun `retry and pull refresh retain the active remote query`() = runTest(dispatcher) {
        val queries = mutableListOf<String>()
        val model = model { query ->
            queries += query
            if (queries.size == 1) error("Offline")
            listOf(result(2))
        }
        runCurrent()
        model.onQueryChange("Casablanca"); advanceTimeBy(300); runCurrent()
        assertEquals("Offline", model.uiState.value.error)
        model.retry(); runCurrent()
        assertNull(model.uiState.value.error)
        assertEquals(2, model.uiState.value.titles.single().tmdbId)
        model.refresh()
        assertTrue(model.uiState.value.isRefreshing)
        runCurrent()
        assertFalse(model.uiState.value.isRefreshing)
        assertEquals(listOf("Casablanca", "Casablanca", "Casablanca"), queries)
    }

    @Test fun `result deduplication retains movie and TV sharing the same numeric ID`() = runTest(dispatcher) {
        val model = model { listOf(result(42), result(42), result(42, MediaType.TV)) }
        runCurrent()
        model.onQueryChange("same id"); advanceTimeBy(300); runCurrent()
        assertEquals(listOf(42 to MediaType.MOVIE, 42 to MediaType.TV), model.uiState.value.titles.map { it.mediaIdentity })
    }
}
