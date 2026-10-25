package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import work.kumarfamilynet.cinemarchive.core.model.UpdateCheckResult

/**
 * Single source of truth for "is there an update", shared by every surface that shows one.
 *
 * Before this, the result lived in a `remember` inside Settings → About, so the launch-time
 * check found updates that nothing else could see. Every caller now resolves the same release
 * here and observes it through [state].
 *
 * App-scoped: create one per process.
 */
class AppUpdateController(
    private val fetch: suspend () -> UpdateCheckResult,
    private val clock: () -> Long = System::currentTimeMillis,
    // Outlives whichever caller started a check, so callers sharing it aren't left waiting on
    // a request cancelled because the first of them went away (e.g. About was closed).
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val stateFlow = MutableStateFlow<UpdateCheckResult>(UpdateCheckResult.Idle)

    /** The latest check result. [UpdateCheckResult.Idle] until the first check runs. */
    val state: StateFlow<UpdateCheckResult> = stateFlow.asStateFlow()

    private val checkingFlow = MutableStateFlow(false)

    /**
     * Whether a check is in flight. Re-checking while an update is already available keeps
     * [state] on that update rather than flashing [UpdateCheckResult.Checking] — a banner or a
     * download in progress shouldn't blink away because the daily check ran — so a spinner for
     * a manual re-check reads this instead.
     */
    val isChecking: StateFlow<Boolean> = checkingFlow.asStateFlow()

    private val mutex = Mutex()
    private var inFlight: Deferred<UpdateCheckResult>? = null
    private var lastResolvedAt: Long? = null

    /**
     * Checks for an update and publishes the result to [state].
     *
     * Callers arriving while a check is in flight share it, and those arriving within
     * [RESULT_REUSE_WINDOW_MILLIS] of a successful one reuse its result: opening the app fans
     * out to the launch check and the About screen at once, and each would otherwise be its
     * own GitHub request against a 60-per-hour unauthenticated limit. A failed check is never
     * reused, so pressing Check again after an error always retries.
     *
     * [force] skips the reuse window (an in-flight check is still shared): a manual "Check" or
     * "Check again" tap means the user wants a fresh answer, e.g. once a release's APK finishes
     * uploading.
     */
    suspend fun check(force: Boolean = false): UpdateCheckResult {
        val pending = mutex.withLock {
            val current = stateFlow.value
            val resolvedAt = lastResolvedAt
            if (!force && resolvedAt != null && current !is UpdateCheckResult.Failed &&
                clock() - resolvedAt < RESULT_REUSE_WINDOW_MILLIS
            ) {
                return current
            }
            inFlight ?: startCheck().also { inFlight = it }
        }
        return pending.await()
    }

    // Called with mutex held.
    private fun startCheck(): Deferred<UpdateCheckResult> {
        checkingFlow.value = true
        if (stateFlow.value !is UpdateCheckResult.Available) stateFlow.value = UpdateCheckResult.Checking
        return scope.async {
            var result: UpdateCheckResult = UpdateCheckResult.Failed("Update check was interrupted")
            try {
                result = fetch()
                result
            } finally {
                // NonCancellable: a cancelled check must still clear inFlight, or every later
                // caller would await a Deferred that never completes normally.
                withContext(NonCancellable) {
                    mutex.withLock {
                        stateFlow.value = result
                        lastResolvedAt = clock()
                        inFlight = null
                        checkingFlow.value = false
                    }
                }
            }
        }
    }

    private companion object {
        // Long enough to cover the checks one app launch fans out, short enough that a manual
        // check moments later still reaches the network.
        const val RESULT_REUSE_WINDOW_MILLIS = 15_000L
    }
}
