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
    ) = DiscoverViewModel({ _, _, _ -> trendingSource() }, search).also { store.put("discover", it) }

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

    private fun browseModel(
        browse: suspend (MediaType?, Int?, Int) -> List<TrendingTitle>,
        search: suspend (String) -> List<MediaSearchResult> = { emptyList() },
    ) = DiscoverViewModel(browse, search).also { store.put("discover", it) }

    @Test fun `genre type and search transitions reset pages and preserve the selected browsing context`() = runTest(dispatcher) {
        val requests = mutableListOf<Triple<MediaType?, Int?, Int>>()
        val model = browseModel({ type, genre, page -> requests += Triple(type, genre, page); listOf(trending) }) { listOf(result(42), result(42, MediaType.TV)) }
        runCurrent()
        model.onGenreChange(18); runCurrent()
        model.loadMore(); runCurrent()
        assertEquals(Triple(null, 18, 2), requests.last())
        model.onQueryChange("A title"); advanceTimeBy(300); runCurrent()
        assertFalse(model.uiState.value.hasMore)
        model.loadMore(); runCurrent()
        assertEquals(3, requests.size)
        model.onQueryChange(""); runCurrent()
        assertEquals(Triple(null, 18, 1), requests.last())
        model.onTypeChange(TypeFilter.TV); runCurrent()
        assertEquals(Triple(MediaType.TV, null, 1), requests.last())
        assertNull(model.uiState.value.genreId)
        model.onGenreChange(28); runCurrent() // Movie-only genre is not valid under TV.
        assertNull(model.uiState.value.genreId)
        model.onQueryChange("A title"); advanceTimeBy(300); runCurrent()
        assertEquals(MediaType.TV, model.uiState.value.titles.single().type)
    }

    @Test fun `append deduplicates across pages without collapsing movie and TV and empty page ends browsing`() = runTest(dispatcher) {
        val movie = trending.copy(tmdbId = 42)
        val tv = movie.copy(type = MediaType.TV)
        val model = browseModel({ _, _, page -> when (page) { 1 -> listOf(movie); 2 -> listOf(movie, tv); 3 -> listOf(tv); else -> emptyList() } })
        runCurrent(); model.loadMore(); runCurrent()
        assertEquals(listOf(movie, tv), model.uiState.value.titles)
        model.loadMore(); runCurrent()
        assertTrue(model.uiState.value.hasMore) // A duplicate-only page is not the end of the remote feed.
        assertEquals(3, model.uiState.value.page)
        model.loadMore(); runCurrent()
        assertFalse(model.uiState.value.hasMore)
        assertEquals(listOf(movie, tv), model.uiState.value.titles)
    }

    @Test fun `failed append retains cards and retry uses the same page`() = runTest(dispatcher) {
        var fail = true
        val pages = mutableListOf<Int>()
        val model = browseModel({ _, _, page -> pages += page; if (page == 2 && fail) error("Offline"); listOf(trending.copy(tmdbId = page)) })
        runCurrent(); model.loadMore(); runCurrent()
        assertEquals(1, model.uiState.value.page)
        assertEquals(1, model.uiState.value.titles.size)
        assertEquals("Offline", model.uiState.value.moreError)
        assertNull(model.uiState.value.error)
        fail = false; model.loadMore(); runCurrent()
        assertEquals(listOf(1, 2, 2), pages)
        assertEquals(2, model.uiState.value.titles.size)
        assertNull(model.uiState.value.moreError)
    }

    @Test fun `double append taps issue one request and stale page cannot enter a changed filter`() = runTest(dispatcher) {
        lateinit var old: Continuation<List<TrendingTitle>>
        var pageCalls = 0
        val model = browseModel({ type, _, page -> if (page == 2) { pageCalls++; suspendCoroutine { old = it } } else listOf(trending.copy(type = type ?: MediaType.MOVIE)) })
        runCurrent(); model.loadMore(); model.loadMore(); runCurrent()
        assertEquals(1, pageCalls)
        model.onTypeChange(TypeFilter.TV); runCurrent()
        old.resume(listOf(trending.copy(tmdbId = 999))); runCurrent()
        assertEquals(listOf(MediaType.TV), model.uiState.value.titles.map { it.type })
        assertEquals(1, model.uiState.value.page)
        assertFalse(model.uiState.value.isLoadingMore)
    }

    @Test fun `refresh replaces accumulated pages and cancelled append error cannot replace refreshed state`() = runTest(dispatcher) {
        lateinit var old: Continuation<List<TrendingTitle>>
        val model = browseModel({ _, _, page -> if (page == 2) suspendCoroutine { old = it } else listOf(trending) })
        runCurrent(); model.onGenreChange(18); runCurrent(); model.loadMore(); runCurrent()
        model.refresh(); runCurrent()
        old.resumeWithException(IllegalStateException("Old page failure")); runCurrent()
        assertEquals(listOf(trending), model.uiState.value.titles)
        assertEquals(18, model.uiState.value.genreId)
        assertEquals(1, model.uiState.value.page)
        assertNull(model.uiState.value.moreError)
        assertFalse(model.uiState.value.isRefreshing)
    }
}
