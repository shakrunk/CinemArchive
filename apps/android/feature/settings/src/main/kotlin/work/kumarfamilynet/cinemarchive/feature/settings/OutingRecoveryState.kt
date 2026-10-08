package work.kumarfamilynet.cinemarchive.feature.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.data.*

data class OutingRecoveryState(
    val cards: List<OutingRecoveryCard> = emptyList(),
    val focusedId: String? = null,
    val review: OutingRecoveryReview? = null,
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class OutingRecoveryController(private val source: OutingRecoverySource, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(OutingRecoveryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var closed = false
    private fun active() = !closed && source.isActive()
    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (!active()) throw CancellationException("Recovery account changed")
    }
    private fun run(action: suspend () -> Unit) {
        if (!active() || state.value.busy) return
        mutable.value = state.value.copy(busy = true, error = null, message = null)
        job = scope.launch {
            try { action(); checkActive() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (active()) mutable.value = state.value.copy(error = e.message ?: "Could not load saved changes.")
            } finally {
                if (active()) mutable.value = state.value.copy(busy = false)
                else mutable.value = OutingRecoveryState()
            }
        }
    }
    fun refresh() = run {
        val cards = source.items()
        checkActive()
        mutable.value = state.value.copy(cards = cards)
    }
    fun open(id: String) = run {
        mutable.value = state.value.copy(focusedId = id, review = null, selected = emptySet())
        val review = source.review(id)
        checkActive()
        mutable.value = state.value.copy(review = review)
    }
    fun select(key: String, selected: Boolean) {
        val value = state.value
        if (!active() || value.busy || value.review?.pendingAttempt == true || value.review?.resolved == true ||
            value.review?.fields?.none { it.key == key && it.selectable } != false) return
        mutable.value = value.copy(selected = if (selected) value.selected + key else value.selected - key)
    }
    fun apply() {
        val value = state.value
        val review = value.review ?: return
        if (!review.pendingAttempt && value.selected.isEmpty()) return
        run {
            val result = try { source.apply(review.id, review.remoteVersion, value.selected) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                val pending = source.pendingAttempt(review.id)
                checkActive()
                mutable.value = state.value.copy(review = review.copy(pendingAttempt = pending))
                throw error
            }
            checkActive()
            mutable.value = state.value.copy(selected = emptySet(),
                review = review.copy(pendingAttempt = false, remoteVersion = null, remoteExists = false))
            val updated = if (result == OutingRecoveryOutcome.APPLIED)
                review.copy(resolved = true, pendingAttempt = false, fields = emptyList(), message = null)
            else source.review(review.id)
            val cards = source.items()
            checkActive()
            mutable.value = state.value.copy(review = updated, cards = cards, message = when (result) {
                OutingRecoveryOutcome.APPLIED -> "Selected fields applied. The original remains available to export."
                OutingRecoveryOutcome.CHANGED -> "The current plan changed. Review its latest values and select fields again."
                OutingRecoveryOutcome.MISSING -> "The plan is no longer available. No replacement was created."
            })
        }
    }
    fun discard() {
        val id = state.value.focusedId ?: return
        run {
            source.discard(id)
            checkActive()
            val cards = source.items()
            checkActive()
            mutable.value = state.value.copy(cards = cards, focusedId = null, review = null, selected = emptySet(),
                message = "Saved change discarded. Its original remains available to export.")
        }
    }
    fun export(id: String, ready: (String) -> Unit) = run {
        val original = source.exportOriginal(id)
        checkActive()
        ready(original)
    }
    fun dismiss() {
        job?.cancel()
        mutable.value = state.value.copy(focusedId = null, review = null, selected = emptySet(), error = null, busy = false)
    }
    fun close() { closed = true; job?.cancel(); mutable.value = OutingRecoveryState() }
}

