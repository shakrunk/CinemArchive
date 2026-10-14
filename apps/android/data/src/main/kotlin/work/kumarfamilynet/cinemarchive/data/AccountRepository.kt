package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val PROFILE_COLUMNS = "user_id,email,username,display_name,created_at,is_owner"

/**
 * The signed-in user's own account data: identity (`profiles`) and invite codes
 * (`invite_codes`). Ports `getMyProfile`/`updateMyProfile`/`createInviteCode`/
 * `listMyInviteCodes`/`deleteInviteCode` from `apps/web/src/lib/auth.ts`, reusing the same
 * REST tables and owner-only RLS — no new backend policy.
 *
 * Account isolation: every call is made with the *current* session's token and additionally
 * filtered by that session's user id, and the cached [profile] remembers whose it is —
 * [onSessionChanged] drops it when the signed-in user changes or signs out, so one account's
 * identity can never render under another's session.
 */
class AccountRepository(
    private val client: SupabaseRestClient,
    private val sessionProvider: () -> SupabaseSession?,
) {
    private val _profile = MutableStateFlow<MyProfile?>(null)

    /** The cached own profile; null until [refreshProfile] succeeds or after sign-out. */
    val profile: StateFlow<MyProfile?> = _profile

    /** Call with the current session's user id whenever the session changes. */
    fun onSessionChanged(userId: String?) {
        val cached = _profile.value
        if (cached != null && cached.userId != userId) _profile.value = null
    }

    private fun requireSession(): SupabaseSession = sessionProvider() ?: error("Not signed in.")

    suspend fun refreshProfile(): MyProfile? = withContext(Dispatchers.IO) {
        val session = requireSession()
        val rows = JSONArray(client.get("profiles", "select=$PROFILE_COLUMNS&user_id=eq.${session.userId}", session.accessToken))
        val result = if (rows.length() == 0) null else parseProfile(rows.getJSONObject(0))
        if (sessionProvider()?.userId == session.userId) _profile.value = result
        result
    }

    /** Applies [edit] (already validated by [ProfileRules.validate]). A taken username maps to
     *  the web app's exact message. */
    suspend fun updateProfile(edit: ProfileEdit.Valid): MyProfile = withContext(Dispatchers.IO) {
        val session = requireSession()
        val body = JSONObject()
            .put("display_name", edit.displayName ?: JSONObject.NULL)
            .put("username", edit.username ?: JSONObject.NULL)
            .toString()
        val rows = try {
            JSONArray(client.patchWithFilter("profiles", "user_id=eq.${session.userId}", session.accessToken, body))
        } catch (e: SupabaseHttpException) {
            if (e.postgresCode == "23505") throw IllegalStateException("That username is already taken.", e)
            throw e
        }
        check(rows.length() > 0) { "Profile not found." }
        val updated = parseProfile(rows.getJSONObject(0))
        if (sessionProvider()?.userId == session.userId) _profile.value = updated
        updated
    }

    suspend fun listInviteCodes(): List<InviteCode> = withContext(Dispatchers.IO) {
        val session = requireSession()
        parseInviteCodes(client.get("invite_codes", "select=*&created_by=eq.${session.userId}&order=created_at.desc", session.accessToken))
    }

    /** Inserts a fresh code. RLS enforces the cap server-side; the friendly messages mirror web. */
    suspend fun createInviteCode(): InviteCode = withContext(Dispatchers.IO) {
        val session = requireSession()
        val body = JSONObject().put("code", InviteRules.newCode()).put("created_by", session.userId).toString()
        try {
            // PostgREST returns the inserted row as a one-element array.
            parseInviteCodes(client.insert("invite_codes", session.accessToken, body)).first()
        } catch (e: SupabaseHttpException) {
            val friendly = InviteRules.insertErrorMessage(e)
            if (friendly != null) throw IllegalStateException(friendly, e)
            throw e
        }
    }

    /** Deletes an *unredeemed* code (RLS also forbids deleting redeemed ones). */
    suspend fun deleteInviteCode(id: String) {
        withContext(Dispatchers.IO) {
            val session = requireSession()
            client.delete("invite_codes", "id=eq.$id&created_by=eq.${session.userId}&redeemed_by=is.null", session.accessToken)
        }
    }
}
