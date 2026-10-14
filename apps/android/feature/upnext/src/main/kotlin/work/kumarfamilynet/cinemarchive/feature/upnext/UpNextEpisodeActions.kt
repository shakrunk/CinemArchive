package work.kumarfamilynet.cinemarchive.feature.upnext

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.core.model.EpisodeWatchReceipt
import work.kumarfamilynet.cinemarchive.core.model.UpNextWatching

data class EpisodeActionState(
    val snapshot: UpNextWatching,
    val receipt: EpisodeWatchReceipt? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Keeps undo alive independently of Room's live card list. Scope is owned by the ViewModel. */
class UpNextEpisodeActions(
    private val scope: CoroutineScope,
    private val advance: suspend (UpNextWatching) -> EpisodeWatchReceipt?,
    private val deleteWatch: suspend (EpisodeWatchReceipt) -> Unit,
    private val markSeriesWatched: suspend (String) -> Unit,
    private val undoWindowMillis: Long = 6_000,
) {
    private val mutable = MutableStateFlow<Map<String, EpisodeActionState>>(emptyMap())
    val state = mutable.asStateFlow()

    fun mark(snapshot: UpNextWatching) {
        val current = mutable.value[snapshot.id]
        if (current?.busy == true || current?.receipt != null) return
        put(snapshot.id, EpisodeActionState(snapshot, busy = true))
        scope.launch {
            try {
                val receipt = advance(snapshot)
                if (receipt == null) {
                    put(snapshot.id, EpisodeActionState(snapshot, error = "The next episode changed or hasn't aired. Refresh to continue."))
                    return@launch
                }
                put(snapshot.id, EpisodeActionState(snapshot, receipt))
                scope.launch {
                    delay(undoWindowMillis)
                    mutable.update { states ->
                        val latest = states[snapshot.id]
                        if (latest?.receipt?.watchEventId == receipt.watchEventId && !latest.busy && latest.error == null) states - snapshot.id else states
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { put(snapshot.id, EpisodeActionState(snapshot, error = e.message ?: "Couldn't log episode. Try again.")) }
        }
    }

    fun undo(titleId: String) = actOnReceipt(titleId) { deleteWatch(it) }

    fun finishSeries(titleId: String) = actOnReceipt(titleId, requireCaughtUp = true) { markSeriesWatched(it.titleId) }

    private fun actOnReceipt(titleId: String, requireCaughtUp: Boolean = false, action: suspend (EpisodeWatchReceipt) -> Unit) {
        val current = mutable.value[titleId] ?: return
        val receipt = current.receipt ?: return
        if (current.busy || (requireCaughtUp && !receipt.caughtUp)) return
        // Reserve synchronously so a second tap and the expiry timer cannot overtake this action.
        put(titleId, current.copy(busy = true, error = null))
        scope.launch {
            try {
                action(receipt)
                mutable.update { it - titleId }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Keep the exact receipt for retry, including after the ordinary six-second window.
                put(titleId, current.copy(error = e.message ?: "Couldn't save this change. Try again."))
            }
        }
    }

    private fun put(titleId: String, state: EpisodeActionState) { mutable.update { it + (titleId to state) } }
}
