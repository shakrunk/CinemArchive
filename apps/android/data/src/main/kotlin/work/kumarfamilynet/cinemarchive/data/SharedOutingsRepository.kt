package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.MemberStatus
import work.kumarfamilynet.cinemarchive.core.model.OutingMember
import work.kumarfamilynet.cinemarchive.core.model.SharedOuting
import work.kumarfamilynet.cinemarchive.core.model.SharedOutingStatus

/**
 * Shared outings (#206, docs/android-contracts/shared-outings.md): inviting accepted friends to an
 * outing, answering an invitation, and listing the outings friends invited this user to. Online-only:
 * these are account-wide records the server owns, so there is no local copy, and every call is fenced
 * to the signed-in owner this repository was built for.
 */
class SharedOutingsRepository(
    private val client: SupabaseRestClient,
    private val session: () -> SupabaseSession?,
    private val ownerId: String,
    private val isCurrent: () -> Boolean,
) {
    /** Outings this user is invited to or going to, with recently ended and cancelled ones. */
    suspend fun list(): List<SharedOuting> = withContext(Dispatchers.IO) {
        val token = token()
        parseSharedOutings(client.rpc("get_shared_outings", "{}", token)).also { token() }
    }

    /** The owner's view of who is invited to [outingId]. */
    suspend fun members(outingId: String): List<OutingMember> = withContext(Dispatchers.IO) {
        val token = token()
        parseOutingMembers(client.rpc("get_outing_members", JSONObject().put("p_outing_id", checkedUuid(outingId)).toString(), token))
            .also { token() }
    }

    /** Invites [friendIds] (accepted friends only; the server refuses anyone else). Returns the members. */
    suspend fun invite(outingId: String, friendIds: List<String>): List<OutingMember> = withContext(Dispatchers.IO) {
        require(friendIds.isNotEmpty()) { "Choose at least one friend." }
        val token = token()
        val body = JSONObject().put("p_outing_id", checkedUuid(outingId))
            .put("p_friend_ids", JSONArray(friendIds.map(::checkedUuid))).toString()
        try { client.rpc("invite_friends_to_outing", body, token) }
        catch (e: SupabaseHttpException) { throw IllegalStateException(FriendsRules.errorMessage(e, "Could not send the invitation."), e) }
        token()
        // The invite result carries ids and statuses only; names come from the owner's member list.
        members(outingId)
    }

    suspend fun respond(sharedOutingId: String, accept: Boolean) = withContext(Dispatchers.IO) {
        val token = token()
        val body = JSONObject().put("p_shared_outing_id", checkedUuid(sharedOutingId)).put("p_accept", accept).toString()
        try { client.rpc("respond_to_outing_invite", body, token) }
        catch (e: SupabaseHttpException) { throw IllegalStateException(FriendsRules.errorMessage(e, "Could not send your answer."), e) }
        token()
        Unit
    }

    suspend fun removeMember(sharedOutingId: String, userId: String) = withContext(Dispatchers.IO) {
        val token = token()
        val body = JSONObject().put("p_shared_outing_id", checkedUuid(sharedOutingId)).put("p_user_id", checkedUuid(userId)).toString()
        client.rpc("remove_outing_member", body, token)
        token()
        Unit
    }

    private fun token(): String {
        check(isCurrent()) { "This sign-in has ended." }
        val current = session()
        check(current != null && current.userId == ownerId) { "Sign in to share outings with friends." }
        return current.accessToken
    }
}

private val UUID_SHAPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun checkedUuid(value: String): String {
    require(UUID_SHAPE.matches(value)) { "Invalid id" }
    return value
}

private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun memberStatus(wire: String?): MemberStatus? = when (wire) {
    "invited" -> MemberStatus.INVITED
    "accepted" -> MemberStatus.ACCEPTED
    "declined" -> MemberStatus.DECLINED
    else -> null
}

/** Rows with an unexpected shape are skipped, never shown. */
internal fun parseSharedOutings(body: String): List<SharedOuting> {
    val rows = JSONArray(body)
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val snapshot = row.optJSONObject("snapshot") ?: return@mapNotNull null
        val id = row.optString("id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null
        val ownerId = row.optString("owner_id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null
        val status = when (row.optString("status")) {
            "scheduled" -> SharedOutingStatus.SCHEDULED
            "completed" -> SharedOutingStatus.COMPLETED
            "cancelled" -> SharedOutingStatus.CANCELLED
            else -> return@mapNotNull null
        }
        val myStatus = memberStatus(row.optString("my_status"))?.takeIf { it != MemberStatus.DECLINED } ?: return@mapNotNull null
        val showtime = row.optString("showtime").takeIf { runCatching { Instant.parse(it) }.isSuccess } ?: return@mapNotNull null
        val endsAt = row.optString("ends_at").takeIf { runCatching { Instant.parse(it) }.isSuccess } ?: return@mapNotNull null
        val title = snapshot.stringOrNull("title") ?: return@mapNotNull null
        val companions = snapshot.optJSONArray("companions")?.let { names ->
            (0 until names.length()).mapNotNull { names.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        SharedOuting(
            id = id, status = status, myStatus = myStatus, ownerId = ownerId,
            ownerName = row.stringOrNull("owner_name") ?: "A friend",
            showtime = showtime, endsAt = endsAt, title = title,
            year = if (snapshot.isNull("year")) null else snapshot.optInt("year").takeIf { it > 0 },
            posterUrl = snapshot.stringOrNull("poster_url"),
            venue = snapshot.stringOrNull("venue"),
            format = snapshot.stringOrNull("format")?.let { CinemaFormat.fromWire(it) ?: CinemaFormat.OTHER },
            companions = companions,
        )
    }
}

internal fun parseOutingMembers(body: String): List<OutingMember> {
    val rows = JSONArray(body)
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        OutingMember(
            userId = row.optString("user_id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null,
            name = row.stringOrNull("name") ?: "A friend",
            status = memberStatus(row.optString("status")) ?: return@mapNotNull null,
            sharedOutingId = row.optString("shared_outing_id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null,
        )
    }
}
