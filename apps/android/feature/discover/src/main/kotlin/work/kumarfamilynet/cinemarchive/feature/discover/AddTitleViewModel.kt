package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.AddTitleRequest
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaDetails
import work.kumarfamilynet.cinemarchive.core.model.MediaSearchResult
import work.kumarfamilynet.cinemarchive.data.DiscoverRepository
import work.kumarfamilynet.cinemarchive.data.LibraryRepository

/** Which half of the two-step add flow is on screen. */
enum class AddTitleStep { SEARCH, LOG }

/** The log form's own state, kept apart from the async/network state around it so a details
 *  re-fetch (or a search the user backs out to and returns from) can't quietly reset the
 *  status/rating/notes they already chose. */
data class AddTitleLogForm(
    val status: LibraryStatus = LibraryStatus.WATCHLIST,
    val rating: Double = 0.0,
    val notes: String = "",
)

data class AddTitleUiState(
    val step: AddTitleStep = AddTitleStep.SEARCH,
    val query: String = "",
    val results: List<MediaSearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val selected: MediaSearchResult? = null,
    val details: MediaDetails? = null,
    val isLoadingDetails: Boolean = false,
    val detailsError: String? = null,
    val form: AddTitleLogForm = AddTitleLogForm(),
    val isSaving: Boolean = false,
    val saveError: String? = null,
    /** Local row id of the title the user already owns, when the selected result turns out to
     *  be a duplicate — the UI offers to open it instead of adding it twice. */
    val alreadyOwnedTitleId: String? = null,
) {
    /** True once a search has run and come back with nothing — distinct from the initial
     *  never-searched state, which should prompt rather than report "no results". */
    val isEmptyResult: Boolean
        get() = query.isNotBlank() && !isSearching && searchError == null && results.isEmpty()
}

/**
 * Drives the Add-title overlay: debounced TMDB search, a details fetch for whatever the user
 * picks, and the real library write.
 *
 * Search is debounced at 400ms and `flatMapLatest`-ed, matching the web app's
 * `useDebouncedSearch`: every keystroke would otherwise be a round trip through `media-proxy`
 * to TMDB (two, in fact — movie and TV are separate queries), and a slow earlier response could
 * land after a faster later one and repopulate the list with results for a prefix the user has
 * already typed past.
 */
class AddTitleViewModel(
    private val discoverRepository: DiscoverRepository,
    private val libraryRepository: LibraryRepository,
    preselected: MediaSearchResult?,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddTitleUiState())
    val uiState: StateFlow<AddTitleUiState> = _uiState

    private val queryFlow = MutableStateFlow("")
    private var detailsJob: Job? = null

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val searches = queryFlow
        .debounce(SEARCH_DEBOUNCE_MS)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            flow {
                if (query.isBlank()) {
                    emit(Result.success(emptyList()))
                } else {
                    emit(runCatching { discoverRepository.searchMedia(query) })
                }
            }
        }

    init {
        searches
            .onEach { result ->
                result
                    .onSuccess { hits -> _uiState.update { it.copy(results = hits, isSearching = false, searchError = null) } }
                    .onFailure { e ->
                        _uiState.update { it.copy(results = emptyList(), isSearching = false, searchError = e.message ?: "Search failed") }
                    }
            }
            .launchIn(viewModelScope)

        // Arriving from a Discover tap: the result is already chosen, so skip straight to the
        // log step and start hydrating it — same shortcut the web app's `preselectedResult`
        // path takes into step 2.
        preselected?.let(::select)
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query, isSearching = query.isNotBlank(), searchError = null) }
        queryFlow.value = query
    }

    fun retrySearch() {
        val query = _uiState.value.query
        if (query.isBlank()) return
        _uiState.update { it.copy(isSearching = true, searchError = null) }
        viewModelScope.launch {
            runCatching { discoverRepository.searchMedia(query) }
                .onSuccess { hits -> _uiState.update { it.copy(results = hits, isSearching = false, searchError = null) } }
                .onFailure { e -> _uiState.update { it.copy(isSearching = false, searchError = e.message ?: "Search failed") } }
        }
    }

    fun select(result: MediaSearchResult) {
        detailsJob?.cancel()
        _uiState.update {
            it.copy(
                step = AddTitleStep.LOG,
                selected = result,
                details = null,
                isLoadingDetails = true,
                detailsError = null,
                saveError = null,
                alreadyOwnedTitleId = null,
                form = AddTitleLogForm(),
            )
        }
        detailsJob = viewModelScope.launch {
            val owned = libraryRepository.findLibraryTitleId(result.tmdbId, result.type)
            runCatching { discoverRepository.fetchDetails(result) }
                .onSuccess { details ->
                    _uiState.update { it.copy(details = details, isLoadingDetails = false, alreadyOwnedTitleId = owned) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoadingDetails = false,
                            detailsError = e.message ?: "Couldn't load this title",
                            alreadyOwnedTitleId = owned,
                        )
                    }
                }
        }
    }

    fun retryDetails() {
        _uiState.value.selected?.let(::select)
    }

    fun backToSearch() {
        detailsJob?.cancel()
        _uiState.update { it.copy(step = AddTitleStep.SEARCH, selected = null, details = null, detailsError = null, saveError = null) }
    }

    fun onStatusChange(status: LibraryStatus) = _uiState.update { it.copy(form = it.form.copy(status = status)) }

    fun onRatingChange(rating: Double) = _uiState.update { it.copy(form = it.form.copy(rating = rating)) }

    fun onNotesChange(notes: String) = _uiState.update { it.copy(form = it.form.copy(notes = notes)) }

    /** Writes the title and reports its new local id to [onAdded]. The write itself is
     *  local-only and effectively instant — the Supabase push is the outbox's problem, and
     *  deliberately not awaited here, so adding a title works offline. */
    fun save(onAdded: (String) -> Unit) {
        val state = _uiState.value
        val details = state.details ?: return
        if (state.isSaving) return
        _uiState.update { it.copy(isSaving = true, saveError = null) }
        viewModelScope.launch {
            val request = AddTitleRequest(
                details = details,
                status = state.form.status,
                rating = state.form.rating.takeIf { it > 0.0 },
                notes = state.form.notes.trim().takeIf { it.isNotEmpty() },
                watchedOn = LocalDate.now().toString(),
            )
            runCatching { libraryRepository.addTitle(request) }
                .onSuccess { id ->
                    _uiState.update { it.copy(isSaving = false) }
                    onAdded(id)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isSaving = false, saveError = e.message ?: "Couldn't add this title") }
                }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
    }
}

class AddTitleViewModelFactory(
    private val discoverRepository: DiscoverRepository,
    private val libraryRepository: LibraryRepository,
    private val preselected: MediaSearchResult?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AddTitleViewModel(discoverRepository, libraryRepository, preselected) as T
}
