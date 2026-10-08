package work.kumarfamilynet.cinemarchive.data

import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Everything owned by one account's runtime that must be torn down before another account's
 *  runtime exists. [close] must be idempotent. */
interface RuntimeHandle {
    val identity: AuthIdentity
    suspend fun close()
}

/** Deterministic, filesystem-safe, non-reversible per-account namespace key. */
object OwnerNamespace {
    fun key(userId: String): String =
        MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun databaseName(userId: String): String = "cinemarchive-${key(userId)}.db"
    fun dataStoreName(base: String, userId: String): String = "${base}_${key(userId)}"
}

/**
 * Owns the single live per-account runtime. The runtime is a pure function of the sign-in
 * ([AuthIdentity] = user id + generation), so:
 *  - a sign-out, a switch to another account, and even A→B→A each tear the old runtime down
 *    COMPLETELY (scope cancelled and joined, databases closed, alarms/notifications cleared)
 *    BEFORE the next one is built — two accounts' repositories never coexist;
 *  - [runtime] is cleared first, so the UI stops reading from the old runtime's database before
 *    it is closed;
 *  - building a runtime is local-only (open Room, construct repositories); network sync is
 *    launched inside the runtime's own scope afterwards, so a slow or offline network can never
 *    delay a usable, cached, same-owner runtime.
 */
class AccountRuntimeManager<R : RuntimeHandle>(
    private val identity: StateFlow<AuthIdentity?>,
    private val scope: CoroutineScope,
    private val factory: (AuthIdentity) -> R,
) {
    private val _runtime = MutableStateFlow<R?>(null)
    val runtime: StateFlow<R?> = _runtime
    private val mutex = Mutex()

    fun start() {
        scope.launch {
            identity.collect { reconcile() }
        }
    }

    /** Brings [runtime] in line with the current identity; idempotent. */
    suspend fun reconcile() = mutex.withLock {
        val target = identity.value
        val live = _runtime.value
        if (live != null && live.identity == target) return@withLock
        _runtime.value = null
        live?.close()
        if (target == null) return@withLock
        val created = withContext(Dispatchers.IO) { factory(target) }
        if (identity.value == target) {
            _runtime.value = created
        } else {
            // Signed out / switched while building — never publish a runtime for a dead sign-in.
            created.close()
        }
    }
}

/**
 * The boundary the UI must cross: a runtime is shown only if it belongs to EXACTLY the sign-in
 * that is current right now (user id AND generation). The manager reconciles asynchronously, so
 * for a moment after auth adopts account B the published runtime can still be A's — or, after
 * A→B→A, the previous A's. Gating on `runtime.identity == currentIdentity` closes that window.
 */
fun <R : RuntimeHandle> visibleRuntime(current: AuthIdentity?, runtime: R?): R? =
    runtime?.takeIf { current != null && it.identity == current }

/**
 * Counts how many UI compositions are currently attached to a runtime, so teardown can wait for
 * the CURRENT set to detach (not just the first one ever to leave). A rotation detaches and
 * re-attaches; a close after the re-attach must wait for that second attachment too.
 */
class UiAttachmentGate {
    private val attached = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun attach() { attached.value += 1 }
    fun detach() { attached.value = (attached.value - 1).coerceAtLeast(0) }
    val attachedCount: Int get() = attached.value

    /** Suspends until nothing is attached (immediately if already so); false on [timeoutMs]. */
    suspend fun awaitDetached(timeoutMs: Long): Boolean =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { attached.first { it == 0 } } != null
}
