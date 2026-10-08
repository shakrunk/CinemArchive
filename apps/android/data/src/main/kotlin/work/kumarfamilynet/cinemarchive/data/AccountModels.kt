package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject

/** The signed-in user's own `profiles` row (owner-only RLS) — mirrors `MyProfile` in
 *  `apps/web/src/lib/auth.ts`. The row has no avatar column on either client. */
data class MyProfile(
    val userId: String,
    val email: String,
    val username: String?,
    val displayName: String?,
    val createdAt: String,
    val isOwner: Boolean,
)

/** An `invite_codes` row created by the signed-in user — mirrors web `InviteCode`. */
data class InviteCode(
    val id: String,
    val code: String,
    val createdAt: String,
    val redeemedBy: String?,
    val redeemedAt: String?,
) {
    val isRedeemed: Boolean get() = redeemedBy != null
}

/** Result of validating the identity form. Mirrors the web Identity section's rules. */
sealed interface ProfileEdit {
    data class Valid(val displayName: String?, val username: String?) : ProfileEdit
    data class Invalid(val message: String) : ProfileEdit
}

object ProfileRules {
    const val DISPLAY_NAME_MAX = 60
    const val USERNAME_MAX = 24
    const val USERNAME_ERROR =
        "Usernames are 3–24 characters: lowercase letters, numbers, hyphens or underscores, " +
            "starting and ending with a letter or number."

    /** Same pattern as `USERNAME_RE` in `apps/web/src/views/Profile.tsx`. */
    private val USERNAME_RE = Regex("^[a-z0-9](?:[a-z0-9_-]{1,22}[a-z0-9])?$")

    /** Trims the display name, trims+lowercases the username, maps blanks to null, and
     *  rejects a malformed username — the web form's exact normalization. */
    fun validate(displayName: String, username: String): ProfileEdit {
        val name = displayName.trim().take(DISPLAY_NAME_MAX).ifEmpty { null }
        val handle = username.trim().lowercase().ifEmpty { null }
        if (handle != null && !USERNAME_RE.matches(handle)) return ProfileEdit.Invalid(USERNAME_ERROR)
        return ProfileEdit.Valid(name, handle)
    }

    /** Web `Profile.tsx` heading precedence: display name, then the email's local part. */
    fun headingName(profile: MyProfile?, email: String?): String? =
        profile?.displayName?.takeIf { it.isNotBlank() } ?: profile?.username?.takeIf { it.isNotBlank() }
            ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
}

/** Invite capacity rules, mirroring web `InvitesSection` + the `invite_codes: capped insert`
 *  RLS policy: non-owners may hold at most [CAP] codes (redeemed ones still count). */
object InviteRules {
    const val CAP = 2

    fun atCap(isOwner: Boolean, codes: List<InviteCode>): Boolean = !isOwner && codes.size >= CAP

    fun unredeemedCount(codes: List<InviteCode>): Int = codes.count { !it.isRedeemed }

    /** 8 uppercase hex characters, like web's `crypto.randomUUID()` slice. */
    fun newCode(random: () -> String = { java.util.UUID.randomUUID().toString() }): String =
        random().replace("-", "").take(8).uppercase()

    /** Friendly message for a failed insert; null means "not a known case — surface raw". */
    fun insertErrorMessage(error: Throwable): String? = when ((error as? SupabaseHttpException)?.postgresCode) {
        "23505" -> "That code collided — please try again."
        "42501" -> "You've used both of your invites."
        else -> null
    }
}

internal fun parseProfile(row: JSONObject): MyProfile = MyProfile(
    userId = row.getString("user_id"),
    email = row.optString("email"),
    username = row.optStringOrNull("username"),
    displayName = row.optStringOrNull("display_name"),
    createdAt = row.optString("created_at"),
    isOwner = row.optBoolean("is_owner", false),
)

internal fun parseInviteCodes(json: String): List<InviteCode> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val row = array.getJSONObject(i)
        InviteCode(
            id = row.getString("id"),
            code = row.getString("code"),
            createdAt = row.optString("created_at"),
            redeemedBy = row.optStringOrNull("redeemed_by"),
            redeemedAt = row.optStringOrNull("redeemed_at"),
        )
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
