package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import android.net.Uri
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val AUTH_CALLBACK_SCHEME = "cinemarchive"
private const val AUTH_CALLBACK_HOST = "auth-callback"
private const val AUTH_CALLBACK_REDIRECT = "$AUTH_CALLBACK_SCHEME://$AUTH_CALLBACK_HOST"

/**
 * Who is signed in *and which sign-in it is*. [generation] increments on every sign-in and
 * sign-out but NOT on a token refresh, so A→B→A yields three distinct identities and a result
 * computed for the first A can never be applied under the second.
 */
data class AuthIdentity(val userId: String, val generation: Long)

/**
 * Owns the app's one Supabase auth session — sign-in, persistence, sign-out, and the
 * [SupabaseRemoteMutationWriter] seam. A refresh token is a long-lived full-account credential,
 * so persistence goes through a [SessionStore] (encrypted on device).
 *
 * Concurrency model (the writer seam is synchronous and called from many threads):
 *  - [refreshLock] makes token refresh single-flight (Supabase rotates refresh tokens, so two
 *    parallel refreshes with the same token risk reuse detection revoking the session). It IS
 *    held across the network call, but nothing else waits on it…
 *  - …[identityLock] is only ever held for a few in-memory/store operations, and is what
 *    [signOut] / sign-in take. So sign-out never waits behind a refresh in flight.
 *  - A refresh result is persisted only if, under [identityLock], the generation and the
 *    stored refresh token are both unchanged (compare-and-set). A refresh that finishes after
 *    sign-out (or after another account signed in) is discarded, never resurrecting a session.
 */
class AuthRepository(
    private val store: SessionStore,
    private val client: SupabaseRestClient,
) : SessionSource {
    constructor(context: Context, client: SupabaseRestClient) : this(EncryptedSessionStore(context), client)

    private val identityLock = Any()
    private val refreshLock = Any()
    private val generation = AtomicLong(0)

    private val _session = MutableStateFlow(store.read())
    private val _identity = MutableStateFlow(_session.value?.let { AuthIdentity(it.userId, generation.incrementAndGet()) })

    fun observeSession(): StateFlow<SupabaseSession?> = _session

    /** Changes on sign-in/sign-out only (not token refresh) — what per-account runtimes key on. */
    fun observeIdentity(): StateFlow<AuthIdentity?> = _identity

    /** Refreshes an about-to-expire access token before handing the session back. Falls back to
     *  the (stale) current session if the refresh call itself fails, so a transient network
     *  hiccup surfaces as a retryable push failure rather than a forced sign-out. Blocking:
     *  call from an IO thread. */
    override fun currentSession(): SupabaseSession? {
        val current = _session.value ?: return null
        if (!needsRefresh(current)) return current
        synchronized(refreshLock) {
            // Another caller may have refreshed (or the user signed out) while we waited.
            val latest = _session.value ?: return null
            if (!needsRefresh(latest)) return latest
            val startGeneration = generation.get()
            val refreshToken = latest.refreshToken ?: return latest
            val refreshed = runCatching { client.refreshSession(refreshToken) }.getOrNull() ?: return latest
            synchronized(identityLock) {
                val stillSame = generation.get() == startGeneration && _session.value?.refreshToken == refreshToken
                if (!stillSame) return _session.value
                persist(refreshed)
                return refreshed
            }
        }
    }

    /** The session, but only if [identity] is still the signed-in sign-in — otherwise null. Checked
     *  before AND after any refresh, so a runtime for account A can never obtain B's token. */
    fun sessionFor(identity: AuthIdentity): SupabaseSession? {
        if (_identity.value != identity) return null
        val session = currentSession() ?: return null
        return session.takeIf { _identity.value == identity && it.userId == identity.userId }
    }

    /** Sends the magic-link email. Throws on failure (e.g. unknown email — sign-up is
     *  invite-only, matching src/lib/auth.ts's `shouldCreateUser: false`) so the login
     *  screen can surface the message directly. */
    fun sendMagicLink(email: String) {
        client.signInWithOtp(email, AUTH_CALLBACK_REDIRECT)
    }

    /** Invite-only sign-up (web `redeemInvite` + `signInWithEmail`): redeems [code] for [email] via the
     *  `redeem-invite` Edge Function — the only place an account can be created — then sends the
     *  magic link that finishes sign-in. The function always answers HTTP 200 and reports refusals
     *  (invalid/used code, rate limit, existing account) as `{ "error": … }`, surfaced here as the
     *  exception message. Blocking: call from an IO thread. */
    fun redeemInviteAndSendLink(email: String, code: String) {
        val body = org.json.JSONObject().put("email", email.trim()).put("code", code.trim()).toString()
        parseRedeemInviteResponse(client.invokeFunctionPost("redeem-invite", body, null))
        sendMagicLink(email.trim())
    }

    /** True if [uri] is this app's auth-callback deep link — MainActivity checks this
     *  before handing the launch intent's data off to [completeMagicLinkCallback]. */
    fun isAuthCallback(uri: Uri): Boolean = uri.scheme == AUTH_CALLBACK_SCHEME && uri.host == AUTH_CALLBACK_HOST

    /** Parses the `#access_token=...&refresh_token=...` fragment GoTrue's magic link
     *  redirects with (implicit grant — the fragment survives the custom-scheme intent
     *  hand-off, unlike an https App Link), resolves the user behind it, and persists the
     *  resulting session as a NEW sign-in (new generation). */
    fun completeMagicLinkCallback(uri: Uri) {
        val params = (uri.fragment ?: uri.encodedQuery ?: return)
            .split("&")
            .mapNotNull { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) parts[0] to Uri.decode(parts[1]) else null
            }
            .toMap()
        val accessToken = params["access_token"] ?: return
        val (userId, email) = client.getUser(accessToken)
        adoptSession(
            SupabaseSession(
                accessToken = accessToken,
                userId = userId,
                refreshToken = params["refresh_token"],
                expiresAt = params["expires_in"]?.toLongOrNull()?.let { System.currentTimeMillis() / 1000 + it },
                email = email,
            ),
        )
    }

    /** Installs [session] as a new sign-in: bumps the generation first so any refresh begun for
     *  the previous sign-in (same user or not) is discarded when it lands. */
    internal fun adoptSession(session: SupabaseSession) {
        synchronized(identityLock) {
            val gen = generation.incrementAndGet()
            persist(session)
            _identity.value = AuthIdentity(session.userId, gen)
        }
    }

    /** Invalidates immediately — never waits for an in-flight refresh. */
    fun signOut() {
        synchronized(identityLock) {
            generation.incrementAndGet()
            store.clear()
            _session.value = null
            _identity.value = null
        }
    }

    private fun needsRefresh(session: SupabaseSession): Boolean {
        val expiresAt = session.expiresAt
        return expiresAt != null && session.refreshToken != null && System.currentTimeMillis() / 1000 >= expiresAt - 60
    }

    /** Callers hold [identityLock]. */
    private fun persist(session: SupabaseSession) {
        store.write(session)
        _session.value = session
    }
}

/** Throws the function's own message when the redemption was refused; returns on success. */
internal fun parseRedeemInviteResponse(responseBody: String) {
    val json = runCatching { org.json.JSONObject(responseBody) }.getOrNull()
        ?: throw IllegalStateException("Unexpected response from the invite service.")
    val error = json.optString("error")
    if (error.isNotEmpty()) throw IllegalStateException(error)
}

/** The one thing most repositories need from auth: "the session to use right now, or null".
 *  Account runtimes hand their repositories a [FencedSessionSource] so a runtime built for
 *  account A can only ever obtain A's session. */
fun interface SessionSource {
    fun currentSession(): SupabaseSession?
}

/** A [SessionSource] pinned to one sign-in ([AuthIdentity]). Returns null once that sign-in is
 *  over — it never falls through to whoever is signed in now. */
class FencedSessionSource(private val auth: AuthRepository, val identity: AuthIdentity) : SessionSource {
    override fun currentSession(): SupabaseSession? = auth.sessionFor(identity)
}
