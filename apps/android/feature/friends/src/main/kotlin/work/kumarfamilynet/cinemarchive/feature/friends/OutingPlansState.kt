package work.kumarfamilynet.cinemarchive.feature.friends

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import work.kumarfamilynet.cinemarchive.data.Friendship
import work.kumarfamilynet.cinemarchive.data.OutingPlansSource
import work.kumarfamilynet.cinemarchive.data.PublicOutingPlan
import work.kumarfamilynet.cinemarchive.data.OutingPlanUnavailableException

data class OutingPlansState(
    val plan: PublicOutingPlan? = null,
    val friends: List<Friendship> = emptyList(),
    val loading: Boolean = true,
    val sending: Set<String> = emptySet(),
    val sent: Set<String> = emptySet(),
    val delivered: Map<String, PublicOutingPlan> = emptyMap(),
    val failures: Map<String, String> = emptyMap(),
    val preparing: Boolean = false,
    val error: String? = null,
)

class OutingPlansController(private val source: OutingPlansSource, private val outingId: String, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(OutingPlansState())
    val state = mutable.asStateFlow()
    private val jobs = mutableListOf<Job>()
    private var closed = false
    // Retry identity lasts for this dialog/controller. Reopening starts an explicit new attempt;
    // these direct social sends are not a restart-durable outbox.
    private val operations = mutableMapOf<String, String>()
    private fun active() = !closed && source.isActive()
    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (!active()) throw CancellationException("Outing sharing closed")
    }
    fun load() {
        if (!active() || jobs.any { it.isActive }) return
        mutable.value = state.value.copy(loading = true, error = null, plan = null)
        jobs += scope.launch {
            try {
                val plan = source.load(outingId)
                checkActive()
                mutable.value = state.value.copy(plan = plan)
                val friends = source.friends()
                checkActive()
                mutable.value = state.value.copy(friends = friends)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active()) mutable.value = state.value.copy(error = e.message ?: "Could not load plans.") }
            finally { if (active()) mutable.value = state.value.copy(loading = false) else clear() }
        }
    }
    fun send(recipientId: String) {
        if (!active() || state.value.plan == null || state.value.loading || recipientId in state.value.sending || state.value.friends.none { it.friendUserId == recipientId }) return
        mutable.value = state.value.copy(sending = state.value.sending + recipientId, failures = state.value.failures - recipientId)
        val operationId = operations.getOrPut(recipientId) { java.util.UUID.randomUUID().toString() }
        jobs += scope.launch {
            try {
                val plan = source.send(outingId, recipientId, operationId)
                checkActive()
                operations.remove(recipientId)
                mutable.value = state.value.copy(sent = state.value.sent + recipientId, delivered = state.value.delivered + (recipientId to plan))
                // Acknowledgment is final even if refreshing today's plan fails afterward.
                try {
                    val current = source.load(outingId)
                    checkActive()
                    mutable.value = state.value.copy(plan = current, error = null)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (active()) mutable.value = state.value.copy(plan = null, error = e.message ?: "Could not refresh current plans.")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (active()) mutable.value = state.value.copy(
                    plan = if (e is OutingPlanUnavailableException) null else state.value.plan,
                    error = if (e is OutingPlanUnavailableException) e.message else state.value.error,
                    failures = state.value.failures + (recipientId to (e.message ?: "Could not send plans.")),
                )
            }
            finally { if (active()) mutable.value = state.value.copy(sending = state.value.sending - recipientId) else clear() }
        }
    }
    /** Every external share/calendar action revalidates fresh state, just like an in-app send. */
    fun prepareExternal(onReady: (PublicOutingPlan) -> Unit) {
        if (!active() || state.value.preparing || state.value.loading) return
        mutable.value = state.value.copy(preparing = true, error = null)
        jobs += scope.launch {
            try {
                val plan = source.load(outingId)
                checkActive()
                mutable.value = state.value.copy(plan = plan)
                onReady(plan)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active()) mutable.value = state.value.copy(plan = null, error = e.message ?: "Could not open sharing.") }
            finally { if (active()) mutable.value = state.value.copy(preparing = false) else clear() }
        }
    }
    private fun clear() { operations.clear(); mutable.value = OutingPlansState(loading = false) }
    fun close() { closed = true; jobs.forEach { it.cancel() }; jobs.clear(); clear() }
}
