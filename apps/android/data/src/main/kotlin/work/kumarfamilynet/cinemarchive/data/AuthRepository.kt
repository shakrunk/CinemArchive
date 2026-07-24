package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val AUTH_CALLBACK_SCHEME = "cinemarchive"
private const val AUTH_CALLBACK_HOST = "auth-callback"
private const val AUTH_CALLBACK_REDIRECT = "$AUTH_CALLBACK_SCHEME://$AUTH_CALLBACK_HOST"

/**
 * Owns the app's one Supabase auth session — sign-in, persistence, sign-out, and the
 * [SupabaseRemoteMutationWriter] seam (`currentSession()` is what its `sessionProvider`
 * lambda calls). Kept separate from [PreferencesRepository]: a refresh token is a
 * long-lived full-account credential, not a UI preference, so it lives in
 * [EncryptedSharedPreferences] rather than plaintext DataStore.
 */
class AuthRepository(context: Context, private val client: SupabaseRestClient) {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "cinemarchive_auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _session = MutableStateFlow(readStoredSession())
    fun observeSession(): StateFlow<SupabaseSession?> = _session

    /** Refreshes an about-to-expire access token before handing the session back — the
     *  synchronous [SupabaseRemoteMutationWriter] seam has no other point to do this. Falls
     *  back to the (stale) current session if the refresh call itself fails, so a transient
     *  network hiccup surfaces as a retryable push failure rather than a forced sign-out. */
    fun currentSession(): SupabaseSession? {
        val current = _session.value ?: return null
        val expiresAt = current.expiresAt
        val refreshToken = current.refreshToken
        if (expiresAt == null || refreshToken == null || System.currentTimeMillis() / 1000 < expiresAt - 60) {
            return current
        }
        return runCatching { client.refreshSession(refreshToken) }.onSuccess { persist(it) }.getOrElse { current }
    }

    /** Sends the magic-link email. Throws on failure (e.g. unknown email — sign-up is
     *  invite-only, matching src/lib/auth.ts's `shouldCreateUser: false`) so the login
     *  screen can surface the message directly. */
    fun sendMagicLink(email: String) {
        client.signInWithOtp(email, AUTH_CALLBACK_REDIRECT)
    }

    /** True if [uri] is this app's auth-callback deep link — MainActivity checks this
     *  before handing the launch intent's data off to [completeMagicLinkCallback]. */
    fun isAuthCallback(uri: Uri): Boolean = uri.scheme == AUTH_CALLBACK_SCHEME && uri.host == AUTH_CALLBACK_HOST

    /** Parses the `#access_token=...&refresh_token=...` fragment GoTrue's magic link
     *  redirects with (implicit grant — the fragment survives the custom-scheme intent
     *  hand-off, unlike an https App Link), resolves the user behind it, and persists the
     *  resulting session. */
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
        persist(
            SupabaseSession(
                accessToken = accessToken,
                userId = userId,
                refreshToken = params["refresh_token"],
                expiresAt = params["expires_in"]?.toLongOrNull()?.let { System.currentTimeMillis() / 1000 + it },
                email = email,
            ),
        )
    }

    fun signOut() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private fun persist(session: SupabaseSession) {
        val editor = prefs.edit()
            .putString("access_token", session.accessToken)
            .putString("user_id", session.userId)
            .putString("refresh_token", session.refreshToken)
            .putString("email", session.email)
        if (session.expiresAt != null) editor.putLong("expires_at", session.expiresAt) else editor.remove("expires_at")
        editor.apply()
        _session.value = session
    }

    private fun readStoredSession(): SupabaseSession? {
        val accessToken = prefs.getString("access_token", null) ?: return null
        val userId = prefs.getString("user_id", null) ?: return null
        return SupabaseSession(
            accessToken = accessToken,
            userId = userId,
            refreshToken = prefs.getString("refresh_token", null),
            expiresAt = if (prefs.contains("expires_at")) prefs.getLong("expires_at", 0) else null,
            email = prefs.getString("email", null),
        )
    }
}
