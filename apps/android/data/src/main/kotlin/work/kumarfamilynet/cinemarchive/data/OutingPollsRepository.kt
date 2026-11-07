package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.OutingPoll
import work.kumarfamilynet.cinemarchive.core.model.PollCandidate
import work.kumarfamilynet.cinemarchive.core.model.PollMember
import work.kumarfamilynet.cinemarchive.core.model.PollOption
import work.kumarfamilynet.cinemarchive.core.model.PollRules
import work.kumarfamilynet.cinemarchive.core.model.PollStatus

/**
 * Outing polls (#203, docs/android-contracts/outing-polls.md): put a few movie and time candidates
 * to accepted friends, vote, and close on a winner. Online-only: the polls are server records shared
 * between people, with no local copy, and every call is fenced to the signed-in owner this
 * repository was built for. Creating the outing from a winner reuses the normal schedule flow.
 */
class OutingPollsRepository(
    private val client: SupabaseRestClient,
    private val session: () -> SupabaseSession?,
    private val ownerId: String,
    private val isCurrent: () -> Boolean,
    private val listFriends: suspend () -> List<Friendship>,
    private val now: () -> Instant = Instant::now,
) {
    /** Polls the user owns or was asked into, from the last 30 days, newest first. */
    suspend fun list(): List<OutingPoll> = withContext(Dispatchers.IO) {
        val token = token()
        parseOutingPolls(client.rpc("get_outing_polls", "{}", token)).also { token() }
    }

    /** Accepted friends a poll can be put to. */
    suspend fun friends(): List<Friendship> {
        token()
        val rows = listFriends()
        token()
        return rows.filter { it.relationFor(ownerId) == FriendshipRelation.FRIENDS && it.friendUserId != ownerId }
    }

    /** Starts a poll and returns its id. The server re-checks everything, including that the friends are accepted. */
    suspend fun create(question: String, candidates: List<PollCandidate>, friendIds: List<String>): String = withContext(Dispatchers.IO) {
        PollRules.problem(question, candidates, friendIds.distinct().size, now())?.let { throw IllegalArgumentException(it) }
        val token = token()
        val options = JSONArray(candidates.map { candidate ->
            JSONObject().put("title_id", checkedUuid(candidate.titleId)).put("showtime", candidate.showtime.toString())
                .put("venue", candidate.venue?.trim()?.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
        })
        val body = JSONObject().put("p_question", question.trim()).put("p_options", options)
            .put("p_friend_ids", JSONArray(friendIds.distinct().map(::checkedUuid))).toString()
        val reply = try { client.rpc("create_outing_poll", body, token) }
        catch (e: SupabaseHttpException) { throw IllegalStateException(FriendsRules.errorMessage(e, "Could not start the poll."), e) }
        token()
        JSONObject(reply).getString("poll_id").also(::checkedUuid)
    }

    /** Replaces the caller's ticks on [pollId] with exactly [optionIds] (none clears them). */
    suspend fun vote(pollId: String, optionIds: List<String>) = withContext(Dispatchers.IO) {
        val token = token()
        val body = JSONObject().put("p_poll_id", checkedUuid(pollId)).put("p_option_ids", JSONArray(optionIds.distinct().map(::checkedUuid))).toString()
        try { client.rpc("cast_poll_votes", body, token) }
        catch (e: SupabaseHttpException) { throw IllegalStateException(FriendsRules.errorMessage(e, "Could not save your votes."), e) }
        token()
        Unit
    }

    /** Owner only. Closes on [optionId], or cancels the poll when it is null. */
    suspend fun close(pollId: String, optionId: String?) = withContext(Dispatchers.IO) {
        val token = token()
        val body = JSONObject().put("p_poll_id", checkedUuid(pollId)).put("p_option_id", optionId?.let(::checkedUuid) ?: JSONObject.NULL).toString()
        try { client.rpc("close_outing_poll", body, token) }
        catch (e: SupabaseHttpException) { throw IllegalStateException(FriendsRules.errorMessage(e, "Could not close the poll."), e) }
        token()
        Unit
    }

    private fun token(): String {
        check(isCurrent()) { "This sign-in has ended." }
        val current = session()
        check(current != null && current.userId == ownerId) { "Sign in to poll your friends." }
        return current.accessToken
    }
}

private val UUID_SHAPE = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

private fun checkedUuid(value: String): String {
    require(UUID_SHAPE.matches(value)) { "Invalid id" }
    return value
}

private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/** Polls or options with an unexpected shape are skipped, never shown. */
internal fun parseOutingPolls(body: String): List<OutingPoll> {
    val rows = JSONArray(body)
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val status = when (row.optString("status")) {
            "open" -> PollStatus.OPEN
            "closed" -> PollStatus.CLOSED
            "cancelled" -> PollStatus.CANCELLED
            else -> return@mapNotNull null
        }
        val optionRows = row.optJSONArray("options") ?: return@mapNotNull null
        val options = (0 until optionRows.length()).mapNotNull { parsePollOption(optionRows.optJSONObject(it)) }
        if (options.size != optionRows.length() || options.isEmpty()) return@mapNotNull null
        val memberRows = row.optJSONArray("members") ?: JSONArray()
        OutingPoll(
            id = row.optString("id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null,
            question = row.stringOrNull("question") ?: return@mapNotNull null,
            status = status,
            isOwner = row.optBoolean("is_owner"),
            ownerName = row.stringOrNull("owner_name") ?: "A friend",
            createdAt = row.optString("created_at"),
            winnerOptionId = row.stringOrNull("winner_option_id")?.takeIf(UUID_SHAPE::matches),
            members = (0 until memberRows.length()).mapNotNull { i ->
                val member = memberRows.optJSONObject(i) ?: return@mapNotNull null
                PollMember(member.optString("user_id").takeIf(UUID_SHAPE::matches) ?: return@mapNotNull null, member.stringOrNull("name") ?: "A friend")
            },
            options = options,
        )
    }
}

private fun parsePollOption(row: JSONObject?): PollOption? {
    row ?: return null
    val showtime = row.optString("showtime").takeIf { runCatching { Instant.parse(it) }.isSuccess } ?: return null
    val voters = row.optJSONArray("voters")?.let { names -> (0 until names.length()).mapNotNull { names.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
    return PollOption(
        id = row.optString("id").takeIf(UUID_SHAPE::matches) ?: return null,
        position = row.optInt("position"),
        tmdbId = row.optInt("tmdb_id"),
        title = row.stringOrNull("title") ?: return null,
        year = if (row.isNull("year")) null else row.optInt("year").takeIf { it > 0 },
        posterUrl = row.stringOrNull("poster_url"),
        showtime = showtime,
        venue = row.stringOrNull("venue"),
        votes = row.optInt("votes"),
        mine = row.optBoolean("mine"),
        voters = voters,
    )
}
