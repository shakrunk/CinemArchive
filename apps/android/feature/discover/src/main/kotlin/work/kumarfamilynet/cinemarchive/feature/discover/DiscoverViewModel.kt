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
import work.kumarfamilynet.cinemarchive.data.CatalogLookup

/** A blank query loads trending; a nonblank query searches the remote catalog. */
class DiscoverViewModel(
    private val fetchBrowse: suspend (MediaType?, Int?, Int) -> List<TrendingTitle>,
    private val searchMedia: suspend (String) -> List<MediaSearchResult>,
    private val searchPeople: suspend (String) -> List<CatalogLookup> = { emptyList() },
    private val searchStudios: suspend (String) -> List<CatalogLookup> = { emptyList() },
    private val personTitles: suspend (Int) -> List<TrendingTitle> = { emptyList() },
    private val studioTitles: suspend (Int, MediaType?) -> List<TrendingTitle> = { _, _ -> emptyList() },
) : ViewModel() {
    constructor(repository: DiscoverRepository) : this(repository::fetchBrowse, repository::searchMedia,
        repository::searchPeople, repository::searchStudios, repository::fetchPersonTitles, repository::fetchStudioTitles)

    private val _uiState = MutableStateFlow(DiscoverUiState())
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()
    private var request: Job? = null
    private var requestVersion = 0L

    init { fetch(showFullScreenLoading = true) }

    fun onQueryChange(query: String) {
        val previous = _uiState.value.query
        if (previous == query) return
        val hadSelection = _uiState.value.selectedLookup != null
        val changedSearch = previous.trim() != query.trim() || hadSelection
        _uiState.update { if (changedSearch) it.copy(query = query, selectedLookup = null, lookups = emptyList(), personTitles = emptyList()) else it.copy(query = query) }
        if (changedSearch) {
            _uiState.update { it.copy(titles = emptyList()) }
            fetch(showFullScreenLoading = true, debounce = query.isNotBlank())
        }
    }

    fun retry() = fetch(showFullScreenLoading = true)
    fun refresh() = fetch(showFullScreenLoading = false)

    fun onModeChange(mode: DiscoverMode) {
        if (mode == _uiState.value.mode) return
        _uiState.update { it.copy(mode = mode, query = "", genreId = null, selectedLookup = null,
            lookups = emptyList(), personTitles = emptyList(), titles = emptyList()) }
        fetch(showFullScreenLoading = true)
    }

    fun onLookupSelect(lookup: CatalogLookup) {
        if (_uiState.value.mode == DiscoverMode.TITLES || lookup !in _uiState.value.lookups) return
        _uiState.update { it.copy(query = lookup.name, selectedLookup = lookup, lookups = emptyList(), titles = emptyList(), personTitles = emptyList()) }
        fetch(showFullScreenLoading = true)
    }

    fun clearLookup() {
        _uiState.update { it.copy(query = "", selectedLookup = null, titles = emptyList(), personTitles = emptyList(), lookups = emptyList()) }
        fetch(showFullScreenLoading = true)
    }

    fun onTypeChange(type: TypeFilter) {
        if (type == _uiState.value.typeFilter) return
        val previous = _uiState.value
        if (previous.mode != DiscoverMode.TITLES) {
            _uiState.update { it.copy(typeFilter = type) }
            if (previous.selectedLookup != null && previous.mode == DiscoverMode.PEOPLE && !previous.isLoading && !previous.isRefreshing && previous.error == null) {
                _uiState.update { it.copy(titles = filterDiscoverTitles(it.personTitles, type)) }
            } else fetch(showFullScreenLoading = true)
            return
        }
        _uiState.update { it.copy(typeFilter = type, genreId = null, query = "", titles = emptyList()) }
        fetch(showFullScreenLoading = true)
    }

    fun onGenreChange(genreId: Int?) {
        if (_uiState.value.mode != DiscoverMode.TITLES) return
        if (genreId != null && discoverGenres(_uiState.value.typeFilter).none { it.id == genreId }) return
        if (genreId == _uiState.value.genreId && _uiState.value.query.isBlank()) return
        _uiState.update { it.copy(genreId = genreId, query = "", titles = emptyList()) }
        fetch(showFullScreenLoading = true)
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.mode != DiscoverMode.TITLES || state.query.isNotBlank() || state.isLoading || state.isRefreshing || state.isLoadingMore || !state.hasMore) return
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
                if (state.mode != DiscoverMode.TITLES) {
                    val selected = state.selectedLookup
                    val lookups = if (selected == null && query.isNotBlank()) {
                        if (state.mode == DiscoverMode.PEOPLE) searchPeople(query) else searchStudios(query)
                    } else emptyList()
                    val titles = when {
                        selected == null -> emptyList()
                        state.mode == DiscoverMode.PEOPLE -> personTitles(selected.id)
                        else -> studioTitles(selected.id, state.typeFilter.mediaType)
                    }.distinctBy(TrendingTitle::mediaIdentity)
                    if (isActive && version == requestVersion) _uiState.update {
                        it.copy(lookups = lookups, titles = filterDiscoverTitles(titles, it.typeFilter),
                            personTitles = if (state.mode == DiscoverMode.PEOPLE) titles else emptyList(),
                            isLoading = false, isRefreshing = false)
                    }
                    return@launch
                }
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
