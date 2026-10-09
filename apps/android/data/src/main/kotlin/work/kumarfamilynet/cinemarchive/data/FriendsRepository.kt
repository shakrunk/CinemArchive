package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.MediaType
import java.io.IOException
import java.net.URLEncoder

private const val FRIEND_TITLE_COLUMNS = "id,tmdb_id,type,title,year,poster_url,status,rating,genres"

/**
 * The friends / social data layer. Ports `apps/web/src/lib/auth.ts` (friend lookup,
 * friendships, invite connections) and `apps/web/src/lib/db.ts` (friend library,
 * recommendations, title comments & reactions, friend activity feed) over the same Supabase
 * RPCs, tables and RLS — no backend change.
 *
 * Stateless by design (account isolation): every call reads the session at call time inside
 * `withContext(Dispatchers.IO)` (the provider may refresh the token with a blocking call),
 * sends that session's token, and keeps nothing between calls, so one account's friends,
 * recommendations or comments can never surface under another's session. Signed out fails
 * with `IllegalStateException("Not signed in.")` before any request is made.
 *
 * Failures surface as [IllegalStateException] whose message follows web's `getErrorMessage`
 * (the server's own `message`, e.g. "Cannot send a friend request to this user", else the
 * web fallback); comment/reaction writes use web's fixed "check your connection" texts.
 * The original exception is kept as the cause.
 */
class FriendsRepository(
    private val client: SupabaseRestClient,
    private val sessionProvider: () -> SupabaseSession?,
) : FriendLibrarySource {
    override suspend fun loadFriendLibrary(viewerUserId: String, friendUserId: String): SharedLibrary =
        readFriendLibrary(client, sessionProvider, viewerUserId, friendUserId)

    /** Runs [block] on IO with the call-time session, mapping transport/HTTP failures to a
     *  friendly [IllegalStateException]. [fixedMessage] forces [fallback] regardless of cause. */
    private suspend fun <T> call(fallback: String, fixedMessage: Boolean = false, block: (SupabaseSession) -> T): T =
        withContext(Dispatchers.IO) {
            val session = sessionProvider() ?: error("Not signed in.")
            try {
                block(session)
            } catch (e: SupabaseHttpException) {
                throw IllegalStateException(if (fixedMessage) fallback else FriendsRules.errorMessage(e, fallback), e)
            } catch (e: IOException) {
                throw IllegalStateException(if (fixedMessage) fallback else FriendsRules.errorMessage(e, fallback), e)
            }
        }

    private fun rpcParams(vararg pairs: Pair<String, Any?>): String {
        val json = JSONObject()
        pairs.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        return json.toString()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ─── Friend lookup & friendships (auth.ts) ───────────────────────────────

    /** `find_user_by_email(lookup_email)` with the email normalized (trim + lowercase). Null
     *  when no account matches (the RPC also excludes yourself). A blank email returns null
     *  without a request (web's form never submits one). */
    suspend fun findUserByEmail(email: String): FoundProfile? {
        val normalized = FriendsRules.normalizeEmail(email)
        if (normalized.isEmpty()) {
            // Still honour "signed out fails" semantics.
            call("Failed to send friend request.") { }
            return null
        }
        return call(FriendsRules.SEND_REQUEST_FAILED) { s ->
            parseFoundProfile(client.rpc("find_user_by_email", rpcParams("lookup_email" to normalized), s.accessToken))
        }
    }

    /** Web `handleSendRequest`: look up by email, then send. Returns null when no user matches
     *  (web's "No user found with that email."), otherwise the profile the request went to. */
    suspend fun sendFriendRequestByEmail(email: String): FoundProfile? {
        val found = findUserByEmail(email) ?: return null
        sendFriendRequest(found.userId)
        return found
    }

    /** `send_friend_request(target_user_id)`. Auto-accepts if they already requested you. */
    suspend fun sendFriendRequest(targetUserId: String) = call(FriendsRules.SEND_REQUEST_FAILED) { s ->
        client.rpc("send_friend_request", rpcParams("target_user_id" to targetUserId), s.accessToken)
        Unit
    }

    /** `accept_friend_request(requester_user_id)`. */
    suspend fun acceptFriendRequest(requesterUserId: String) = call("Failed to accept friend request.") { s ->
        client.rpc("accept_friend_request", rpcParams("requester_user_id" to requesterUserId), s.accessToken)
        Unit
    }

    /** `decline_friend_request(requester_user_id)` — deletes their pending request. */
    suspend fun declineFriendRequest(requesterUserId: String) = call("Failed to decline friend request.") { s ->
        client.rpc("decline_friend_request", rpcParams("requester_user_id" to requesterUserId), s.accessToken)
        Unit
    }

    /** `cancel_friend_request(recipient_user_id)` — cancels your own pending request. */
    suspend fun cancelFriendRequest(recipientUserId: String) = call(FriendsRules.CANCEL_REQUEST_FAILED) { s ->
        client.rpc("cancel_friend_request", rpcParams("recipient_user_id" to recipientUserId), s.accessToken)
        Unit
    }

    /** `block_user(target_user_id)`. Callers show [FriendsRules.BLOCK_CONFIRM_MESSAGE] first. */
    suspend fun blockFriend(targetUserId: String) = call("Failed to block user.") { s ->
        client.rpc("block_user", rpcParams("target_user_id" to targetUserId), s.accessToken)
        Unit
    }

    /** `unblock_user(target_user_id)` — drops the relationship; re-friending needs a new request. */
    suspend fun unblockFriend(targetUserId: String) = call("Failed to unblock user.") { s ->
        client.rpc("unblock_user", rpcParams("target_user_id" to targetUserId), s.accessToken)
        Unit
    }

    /** `list_friendships()` — every pending/accepted/blocked relationship involving the caller. */
    suspend fun listFriendships(): List<Friendship> = call("Failed to load friends.") { s ->
        parseFriendships(client.rpc("list_friendships", "{}", s.accessToken))
    }

    /** `list_invite_connections()` — suggested friends by invite lineage. */
    suspend fun listInviteConnections(): List<InviteConnection> = call("Failed to load suggested friends.") { s ->
        parseInviteConnections(client.rpc("list_invite_connections", "{}", s.accessToken))
    }

    // ─── Friend library (db.ts fetchFriendLibrary) ───────────────────────────

    /** Reads an accepted friend's titles through the "friend read" RLS policies
     *  (`is_friend(auth.uid(), user_id)`), no bearer-token setup call. Read-only and
     *  lightweight; never written into Room. RLS yields an empty list for a non-friend. */
    suspend fun fetchFriendTitles(friendUserId: String): List<FriendTitle> = call("Failed to load friend library.") { s ->
        parseFriendTitles(client.get("titles", "select=$FRIEND_TITLE_COLUMNS&user_id=eq.${enc(friendUserId)}", s.accessToken))
    }

    // ─── Recommendations ─────────────────────────────────────────────────────

    /** `send_recommendation(recipient_id, p_tmdb_id, p_type, p_title, p_year, p_poster_url,
     *  p_note, p_watch_url)`; note/URL are trimmed and blank becomes null like web. Requires an
     *  accepted friendship (enforced server-side). */
    suspend fun sendRecommendation(
        recipientUserId: String,
        draft: RecommendationDraft,
        note: String? = null,
        watchUrl: String? = null,
    ) = call("Failed to send recommendation.") { s ->
        val params = rpcParams(
            "recipient_id" to recipientUserId,
            "p_tmdb_id" to draft.tmdbId,
            "p_type" to mediaTypeWire(draft.type),
            "p_title" to draft.title,
            "p_year" to draft.year,
            "p_poster_url" to draft.posterUrl,
            "p_note" to FriendsRules.blankToNull(note),
            "p_watch_url" to FriendsRules.blankToNull(watchUrl),
        )
        client.rpc("send_recommendation", params, s.accessToken)
        Unit
    }

    /** Recipient id -> status for recommendations the caller already sent of this exact title.
     *  Web filters only by tmdb_id/type and relies on RLS; because "recipient can read" also
     *  matches, this adds `sender_user_id=eq.<me>` so received rows never leak into the map. */
    suspend fun fetchSentRecommendationStatus(tmdbId: Int, type: MediaType): Map<String, RecommendationStatus> =
        call("Failed to load sent recommendations.") { s ->
            val filter = "select=recipient_user_id,status&tmdb_id=eq.$tmdbId&type=eq.${mediaTypeWire(type)}&sender_user_id=eq.${s.userId}"
            parseSentRecommendationStatus(client.get("recommendations", filter, s.accessToken))
        }

    /** `list_recommendations()` — the caller's inbox, newest first (includes dismissed rows,
     *  as web does; the UI decides what to show). */
    suspend fun fetchRecommendations(): List<Recommendation> = call("Failed to load recommendations.") { s ->
        parseRecommendations(client.rpc("list_recommendations", "{}", s.accessToken))
    }

    /** `mark_recommendation_read(rec_id)`. */
    suspend fun markRecommendationRead(id: String) = call("Failed to mark recommendation read.") { s ->
        client.rpc("mark_recommendation_read", rpcParams("rec_id" to id), s.accessToken)
        Unit
    }

    /** `dismiss_recommendation(rec_id)`. */
    suspend fun dismissRecommendation(id: String) = call("Failed to dismiss recommendation.") { s ->
        client.rpc("dismiss_recommendation", rpcParams("rec_id" to id), s.accessToken)
        Unit
    }

    // ─── Title comments & reactions ──────────────────────────────────────────

    /** `list_title_comments(p_title_id)`, oldest first. Empty for a title you neither own nor
     *  share a friendship with. */
    suspend fun fetchTitleComments(titleId: String): List<TitleComment> = call("Failed to load comments.") { s ->
        parseTitleComments(client.rpc("list_title_comments", rpcParams("p_title_id" to titleId), s.accessToken))
    }

    /** `add_title_comment(p_title_id, p_body)`. The body is trimmed and capped at
     *  [FriendsRules.COMMENT_MAX_LENGTH]; a blank body throws [IllegalArgumentException]
     *  without a request (web never posts one). */
    suspend fun addTitleComment(titleId: String, body: String): TitleComment {
        val prepared = requireNotNull(FriendsRules.prepareComment(body)) { "Comment can't be empty." }
        return call(FriendsRules.POST_COMMENT_FAILED, fixedMessage = true) { s ->
            parseAddedComment(client.rpc("add_title_comment", rpcParams("p_title_id" to titleId, "p_body" to prepared), s.accessToken))
        }
    }

    /** `delete_title_comment(p_comment_id)` — author, or the title's owner, may delete. */
    suspend fun deleteTitleComment(commentId: String) = call(FriendsRules.DELETE_COMMENT_FAILED, fixedMessage = true) { s ->
        client.rpc("delete_title_comment", rpcParams("p_comment_id" to commentId), s.accessToken)
        Unit
    }

    /** `list_title_reactions(p_title_id)`. */
    suspend fun fetchTitleReactions(titleId: String): List<TitleReaction> = call("Failed to load reactions.") { s ->
        parseTitleReactions(client.rpc("list_title_reactions", rpcParams("p_title_id" to titleId), s.accessToken))
    }

    /** `set_title_reaction(p_title_id, p_emoji)`; a null [emoji] clears the caller's reaction.
     *  Anything outside [FriendsRules.REACTION_EMOJIS] throws [IllegalArgumentException]
     *  without a request. */
    suspend fun setTitleReaction(titleId: String, emoji: String?) {
        require(emoji == null || emoji in FriendsRules.REACTION_EMOJIS) { "Unsupported reaction." }
        call(FriendsRules.SET_REACTION_FAILED, fixedMessage = true) { s ->
            client.rpc("set_title_reaction", rpcParams("p_title_id" to titleId, "p_emoji" to emoji), s.accessToken)
            Unit
        }
    }

    // ─── Friend activity feed ────────────────────────────────────────────────

    /** `friend_activity_feed(p_before, p_limit)`, newest first, keyset-paginated: pass the last
     *  row's `eventAt` ([FriendsRules.nextCursor]) as [before] for the next page. [limit] is
     *  clamped to 1..50 (the server's cap). Use [FriendsRules.hasMore] on the page size. */
    suspend fun fetchFriendActivityFeed(
        before: String? = null,
        limit: Int = FriendsRules.ACTIVITY_PAGE_SIZE,
    ): List<ActivityEvent> = call("Failed to load friend activity.") { s ->
        val params = rpcParams("p_before" to before, "p_limit" to FriendsRules.clampPageSize(limit))
        parseActivityEvents(client.rpc("friend_activity_feed", params, s.accessToken))
    }
}
