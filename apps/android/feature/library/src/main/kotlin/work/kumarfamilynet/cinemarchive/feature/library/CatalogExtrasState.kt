package work.kumarfamilynet.cinemarchive.feature.library

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import work.kumarfamilynet.cinemarchive.core.model.*
import work.kumarfamilynet.cinemarchive.data.CatalogExtrasSource

sealed interface CatalogLoad<out T> {
    data object Loading : CatalogLoad<Nothing>
    data class Ready<T>(val value: T) : CatalogLoad<T>
    data object Failed : CatalogLoad<Nothing>
}

data class CatalogExtrasState(
    val key: CatalogExtrasKey? = null,
    val videos: CatalogLoad<List<CatalogVideo>> = CatalogLoad.Loading,
    val providers: CatalogLoad<CatalogProviders?> = CatalogLoad.Loading,
)

/** Screen-lifetime state. Late network replies cannot populate a different title or sign-in. */
class CatalogExtrasController(private val source: CatalogExtrasSource, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(CatalogExtrasState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var videosJob: Job? = null
    private var providersJob: Job? = null

    fun select(key: CatalogExtrasKey?) {
        if (mutable.value.key == key) return
        generation++
        videosJob?.cancel()
        providersJob?.cancel()
        mutable.value = CatalogExtrasState(key)
        if (key != null) { retryVideos(); retryProviders() }
    }

    fun retryVideos() {
        val key = mutable.value.key ?: return
        val expected = generation
        videosJob?.cancel()
        mutable.update { it.copy(videos = CatalogLoad.Loading) }
        videosJob = scope.launch {
            val result = load { source.videos(key) }
            ensureActive()
            if (generation == expected) mutable.update { it.copy(videos = result) }
        }
    }

    fun retryProviders() {
        val key = mutable.value.key ?: return
        val expected = generation
        providersJob?.cancel()
        mutable.update { it.copy(providers = CatalogLoad.Loading) }
        providersJob = scope.launch {
            val result = load { source.providers(key) }
            ensureActive()
            if (generation == expected) mutable.update { it.copy(providers = result) }
        }
    }

    private suspend fun <T> load(block: suspend () -> T): CatalogLoad<T> = try {
        CatalogLoad.Ready(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CatalogLoad.Failed
    }
}
