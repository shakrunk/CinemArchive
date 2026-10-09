package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle
import work.kumarfamilynet.cinemarchive.data.DiscoverRepository

/** A blank query loads trending; a nonblank query searches the remote catalog. */
class DiscoverViewModel(
    private val fetchBrowse: suspend (MediaType?, Int?, Int) -> List<TrendingTitle>,
    private val searchMedia: suspend (String) -> List<MediaSearchResult>,
) : ViewModel() {
    constructor(repository: DiscoverRepository) : this(repository::fetchBrowse, repository::searchMedia)

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()
    private var request: Job? = null
    private var requestVersion = 0L

    init { fetch(showFullScreenLoading = true) }

    fun onQueryChange(query: String) {
        val previous = _uiState.value.query
        if (previous == query) return
        _uiState.update { it.copy(query = query) }
        if (previous.trim() != query.trim()) {
            _uiState.update { it.copy(titles = emptyList()) }
            fetch(showFullScreenLoading = true, debounce = query.isNotBlank())
        }
    }

    fun retry() = fetch(showFullScreenLoading = true)
    fun refresh() = fetch(showFullScreenLoading = false)

    fun onTypeChange(type: TypeFilter) {
        if (type == _uiState.value.typeFilter) return
        _uiState.update { it.copy(typeFilter = type, genreId = null, query = "", titles = emptyList()) }
        fetch(showFullScreenLoading = true)
    }

    fun onGenreChange(genreId: Int?) {
        if (genreId != null && discoverGenres(_uiState.value.typeFilter).none { it.id == genreId }) return
        if (genreId == _uiState.value.genreId && _uiState.value.query.isBlank()) return
        _uiState.update { it.copy(genreId = genreId, query = "", titles = emptyList()) }
        fetch(showFullScreenLoading = true)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.query.isNotBlank() || state.isLoading || state.isRefreshing || state.isLoadingMore || !state.hasMore) return
        val version = requestVersion
        _uiState.update { it.copy(isLoadingMore = true, moreError = null) }
        request = viewModelScope.launch {
            try {
                val nextPage = state.page + 1
                val titles = fetchBrowse(state.typeFilter.mediaType, state.genreId, nextPage)
                if (isActive && version == requestVersion) _uiState.update {
                    it.copy(titles = (it.titles + titles).distinctBy(TrendingTitle::mediaIdentity), page = nextPage,
                        hasMore = titles.isNotEmpty() && nextPage < 500, isLoadingMore = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isActive && version == requestVersion) _uiState.update {
                    it.copy(isLoadingMore = false, moreError = error.message ?: "Couldn't load more titles")
                }
            }
        }
    }

    private fun fetch(showFullScreenLoading: Boolean, debounce: Boolean = false) {
        val state = _uiState.value
        val query = state.query.trim()
        val version = ++requestVersion
        request?.cancel()
        _uiState.update { it.copy(isLoading = showFullScreenLoading, isRefreshing = !showFullScreenLoading,
            error = null, page = 1, hasMore = false, isLoadingMore = false, moreError = null) }
        request = viewModelScope.launch {
            try {
                if (debounce) delay(300)
                val titles = if (query.isEmpty()) fetchBrowse(state.typeFilter.mediaType, state.genreId, 1) else searchMedia(query).map {
                    TrendingTitle(it.tmdbId, it.title, it.year, it.type, it.posterUrl, it.synopsis)
                }.let { filterDiscoverTitles(it, state.typeFilter) }
                // Blocking clients may finish after cancellation. Never publish an older query.
                if (isActive && version == requestVersion) {
                    _uiState.update { it.copy(titles = titles.distinctBy(TrendingTitle::mediaIdentity), isLoading = false,
                        isRefreshing = false, hasMore = query.isEmpty() && titles.isNotEmpty()) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isActive && version == requestVersion) {
                    _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = error.message ?: "Couldn't load titles") }
                }
            }
        }
    }
}
