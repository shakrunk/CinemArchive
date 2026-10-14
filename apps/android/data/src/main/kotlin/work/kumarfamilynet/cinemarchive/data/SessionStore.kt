package work.kumarfamilynet.cinemarchive.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistence seam for the app's one Supabase session. Split out of [AuthRepository] so the
 * refresh / sign-out / account-switch logic is unit-testable on the JVM
 * (`EncryptedSharedPreferences` needs a real Android keystore).
 */
interface SessionStore {
    fun read(): SupabaseSession?
    fun write(session: SupabaseSession)
    fun clear()
}

/** Refresh tokens are long-lived full-account credentials, so they live in
 *  [EncryptedSharedPreferences] rather than plaintext DataStore. */
class EncryptedSessionStore(context: Context) : SessionStore {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "cinemarchive_auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun read(): SupabaseSession? {
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

    override fun write(session: SupabaseSession) {
        val editor = prefs.edit()
            .putString("access_token", session.accessToken)
            .putString("user_id", session.userId)
            .putString("refresh_token", session.refreshToken)
            .putString("email", session.email)
        if (session.expiresAt != null) editor.putLong("expires_at", session.expiresAt) else editor.remove("expires_at")
        editor.apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
