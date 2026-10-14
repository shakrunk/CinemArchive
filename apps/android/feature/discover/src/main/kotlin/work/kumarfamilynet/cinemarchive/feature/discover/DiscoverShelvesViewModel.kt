package work.kumarfamilynet.cinemarchive.feature.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import work.kumarfamilynet.cinemarchive.core.model.TrendingTitle
import work.kumarfamilynet.cinemarchive.data.DiscoverLibrary
import work.kumarfamilynet.cinemarchive.data.DiscoverLibraryTitle

data class DiscoverShelfResult(
    val titles: List<TrendingTitle> = emptyList(), val loading: Boolean = false, val error: String? = null,
)

data class DiscoverShelvesState(
    val library: DiscoverLibrary = DiscoverLibrary(),
    val selectedTitleId: String? = null,
    val selectedPersonId: Int? = null,
    val recommendations: DiscoverShelfResult = DiscoverShelfResult(),
    val starring: DiscoverShelfResult = DiscoverShelfResult(),
) {
    fun visible(result: DiscoverShelfResult, type: TypeFilter): List<TrendingTitle> {
        val owned = library.ownedKeys
        return filterDiscoverTitles(result.titles, type).filterNot { it.mediaIdentity in owned }
    }
}

/** Owned by the account ViewModelStore, so sign-out cancels both independent request streams. */
class DiscoverShelvesViewModel(
    library: Flow<DiscoverLibrary>,
    private val recommendations: suspend (Int, MediaType) -> List<TrendingTitle>,
    private val personTitles: suspend (Int) -> List<TrendingTitle>,
) : ViewModel() {
    private val mutable = MutableStateFlow(DiscoverShelvesState())
    val state = mutable.asStateFlow()
    private var recommendationJob: Job? = null
    private var starringJob: Job? = null
    private var recommendationVersion = 0L
    private var starringVersion = 0L
    private var seed: DiscoverLibraryTitle? = null
    private var person: Int? = null
    private var visible = false

    init {
        viewModelScope.launch {
            library.collect { value ->
                val old = state.value
                mutable.value = old.copy(library = value,
                    selectedTitleId = old.selectedTitleId?.takeIf { id -> value.titles.any { it.id == id } } ?: value.titles.firstOrNull()?.id,
                    selectedPersonId = old.selectedPersonId?.takeIf { id -> value.cast.any { it.tmdbPersonId == id } } ?: value.cast.firstOrNull()?.tmdbPersonId)
                reconcile()
            }
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) {
            recommendationVersion++; starringVersion++
            recommendationJob?.cancel(); starringJob?.cancel()
            seed = null; person = null
            mutable.update { it.copy(recommendations = DiscoverShelfResult(), starring = DiscoverShelfResult()) }
        } else reconcile()
    }

    fun selectTitle(id: String) {
        if (id == state.value.selectedTitleId || state.value.library.titles.none { it.id == id }) return
        mutable.update { it.copy(selectedTitleId = id) }
        reconcile()
    }
    fun selectPerson(id: Int) {
        if (id == state.value.selectedPersonId || state.value.library.cast.none { it.tmdbPersonId == id }) return
        mutable.update { it.copy(selectedPersonId = id) }
        reconcile()
    }
    fun retryRecommendations() { if (visible) loadRecommendations() }
    fun retryStarring() { if (visible) loadStarring() }

    private fun reconcile() {
        if (!visible) return
        val next = state.value.library.titles.firstOrNull { it.id == state.value.selectedTitleId }
        if (next != seed) { seed = next; loadRecommendations() }
        val nextPerson = state.value.selectedPersonId
        if (nextPerson != person) { person = nextPerson; loadStarring() }
    }

    private fun loadRecommendations() {
        val version = ++recommendationVersion
        recommendationJob?.cancel()
        val current = seed?.takeIf { it.tmdbId > 0 }
        mutable.update { it.copy(recommendations = DiscoverShelfResult(loading = current != null)) }
        if (current == null) return
        recommendationJob = viewModelScope.launch {
            try {
                val titles = recommendations(current.tmdbId, current.type)
                if (isActive && visible && version == recommendationVersion) mutable.update {
                    it.copy(recommendations = DiscoverShelfResult(titles.distinctBy(TrendingTitle::mediaIdentity)))
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (isActive && visible && version == recommendationVersion) mutable.update {
                    it.copy(recommendations = DiscoverShelfResult(error = "Recommendations could not be loaded. Retry when connected."))
                }
            }
        }
    }

    private fun loadStarring() {
        val version = ++starringVersion
        starringJob?.cancel()
        val current = person
        mutable.update { it.copy(starring = DiscoverShelfResult(loading = current != null)) }
        if (current == null) return
        starringJob = viewModelScope.launch {
            try {
                val titles = personTitles(current)
                if (isActive && visible && version == starringVersion) mutable.update {
                    it.copy(starring = DiscoverShelfResult(titles.distinctBy(TrendingTitle::mediaIdentity)))
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (isActive && visible && version == starringVersion) mutable.update {
                    it.copy(starring = DiscoverShelfResult(error = "Filmography could not be loaded. Retry when connected."))
                }
            }
        }
    }
}
