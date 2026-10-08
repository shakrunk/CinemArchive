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
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle
import work.kumarfamilynet.cinemarchive.data.DiscoverRepository

/** A blank query loads trending; a nonblank query searches the remote catalog. */
class DiscoverViewModel internal constructor(
    private val fetchTrending: suspend () -> List<TrendingTitle>,
    private val searchMedia: suspend (String) -> List<MediaSearchResult>,
) : ViewModel() {
    constructor(repository: DiscoverRepository) : this(repository::fetchTrending, repository::searchMedia)

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

    private fun fetch(showFullScreenLoading: Boolean, debounce: Boolean = false) {
        val query = _uiState.value.query.trim()
        val version = ++requestVersion
        request?.cancel()
        _uiState.update { it.copy(isLoading = showFullScreenLoading, isRefreshing = !showFullScreenLoading, error = null) }
        request = viewModelScope.launch {
            try {
                if (debounce) delay(300)
                val titles = if (query.isEmpty()) fetchTrending() else searchMedia(query).map {
                    TrendingTitle(it.tmdbId, it.title, it.year, it.type, it.posterUrl, it.synopsis)
                }
                // Blocking clients may finish after cancellation. Never publish an older query.
                if (isActive && version == requestVersion) {
                    _uiState.update { it.copy(titles = titles.distinctBy(TrendingTitle::mediaIdentity), isLoading = false, isRefreshing = false) }
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
