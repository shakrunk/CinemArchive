package work.kumarfamilynet.cinemarchive.data

import org.json.JSONArray
import org.json.JSONObject
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/** Result of `find_user_by_email` — web `FoundProfile`. */
data class FoundProfile(
    val userId: String,
    val username: String?,
    val displayName: String?,
)

/** `friendships.status` (schema.sql check constraint); [UNKNOWN] absorbs a value a newer
 *  server adds so an old build never drops the whole list. */
enum class FriendshipStatus(val wire: String) {
    PENDING("pending"),
    ACCEPTED("accepted"),
    BLOCKED("blocked"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String): FriendshipStatus = entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/** How a [Friendship] row reads from the signed-in user's side — the branches of web
 *  `Friends.tsx`'s status line and action buttons. */
enum class FriendshipRelation(val label: String) {
    /** pending, requested_by === me -> "Request sent" (can cancel). */
    REQUEST_SENT("Request sent"),
    /** pending, requested_by !== me -> "Wants to be friends" (can accept / decline). */
    REQUEST_RECEIVED("Wants to be friends"),
    /** accepted -> "Friends" (can view library, comment, block). */
    FRIENDS("Friends"),
    /** blocked, blocked_by === me -> "Blocked" (can unblock). */
    BLOCKED_BY_ME("Blocked"),
    /** blocked by the other side -> "Unavailable" (no actions). */
    UNAVAILABLE("Unavailable"),
    UNKNOWN(""),
}

/** One row of `list_friendships` — web `FriendshipView`. */
data class Friendship(
    val friendUserId: String,
    val status: FriendshipStatus,
    val requestedBy: String,
    val blockedBy: String?,
    val createdAt: String,
    val updatedAt: String,
    val displayName: String?,
    val username: String?,
) {
    /** Web list row: `display_name || username || 'Unknown user'`. */
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: username?.takeIf { it.isNotBlank() } ?: "Unknown user"

    /** Web `loadFriendLibrary(..., f.display_name || f.username || 'Friend')`. */
    val libraryLabel: String get() = displayName?.takeIf { it.isNotBlank() } ?: username?.takeIf { it.isNotBlank() } ?: "Friend"

    fun relationFor(viewerUserId: String): FriendshipRelation = when (status) {
        FriendshipStatus.PENDING ->
            if (requestedBy == viewerUserId) FriendshipRelation.REQUEST_SENT else FriendshipRelation.REQUEST_RECEIVED
        FriendshipStatus.ACCEPTED -> FriendshipRelation.FRIENDS
        FriendshipStatus.BLOCKED ->
            if (blockedBy == viewerUserId) FriendshipRelation.BLOCKED_BY_ME else FriendshipRelation.UNAVAILABLE
        FriendshipStatus.UNKNOWN -> FriendshipRelation.UNKNOWN
    }
}

enum class InviteConnectionKind(val wire: String) {
    INVITED_BY_YOU("invited_by_you"),
    INVITED_YOU("invited_you"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String): InviteConnectionKind = entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/** Suggested friend from `list_invite_connections` — web `InviteConnection`. */
data class InviteConnection(
    val userId: String,
    val username: String?,
    val displayName: String?,
    val connection: InviteConnectionKind,
)

enum class RecommendationStatus(val wire: String) {
    UNREAD("unread"),
    READ("read"),
    DISMISSED("dismissed"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String): RecommendationStatus = entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/** One inbox row from `list_recommendations` — web `Recommendation`. [type] is null for a
 *  media type this build doesn't know. */
data class Recommendation(
    val id: String,
    val senderUserId: String,
    val senderDisplayName: String?,
    val senderUsername: String?,
    val tmdbId: Int,
    val type: MediaType?,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val note: String?,
    val watchUrl: String?,
    val status: RecommendationStatus,
    val createdAt: String,
) {
    val isUnread: Boolean get() = status == RecommendationStatus.UNREAD
    val senderName: String get() = senderDisplayName?.takeIf { it.isNotBlank() } ?: senderUsername?.takeIf { it.isNotBlank() } ?: "Someone"
}

/** The denormalized title snapshot `send_recommendation` stores (web passes a `Title`). */
data class RecommendationDraft(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
)

/** A comment from `list_title_comments` / `add_title_comment` — web `TitleComment`. The
 *  add RPC returns only the bare row, so the author names are null for a just-posted comment. */
data class TitleComment(
    val id: String,
    val authorId: String,
    val authorDisplayName: String?,
    val authorUsername: String?,
    val body: String,
    val createdAt: String,
) {
    val authorName: String get() = authorDisplayName?.takeIf { it.isNotBlank() } ?: authorUsername?.takeIf { it.isNotBlank() } ?: "Someone"
}

/** One row of `list_title_reactions` — web `TitleReaction`. [emoji] is the raw wire string. */
data class TitleReaction(
    val authorId: String,
    val authorDisplayName: String?,
    val authorUsername: String?,
    val emoji: String,
) {
    val authorName: String get() = authorDisplayName?.takeIf { it.isNotBlank() } ?: authorUsername?.takeIf { it.isNotBlank() } ?: "Someone"
}

/** Per-emoji aggregate (web `reactionStats`): one entry per [FriendsRules.REACTION_EMOJIS]
 *  in order, with zero counts included; emoji outside the set are ignored. */
data class ReactionSummary(val emoji: String, val count: Int, val names: List<String>)

enum class ActivityKind(val wire: String) {
    TITLE_ADDED("title_added"),
    VIEWING_LOGGED("viewing_logged"),
    COMMENT_ADDED("comment_added"),
    REACTION_ADDED("reaction_added"),
    UNKNOWN("");

    /** Web `activityVerb`. */
    val verb: String
        get() = when (this) {
            TITLE_ADDED -> "added"
            VIEWING_LOGGED -> "watched"
            COMMENT_ADDED -> "commented on"
            REACTION_ADDED -> "reacted to"
            UNKNOWN -> "interacted with"
        }

    companion object {
        fun fromWire(value: String): ActivityKind = entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/** One row of `friend_activity_feed` — web `ActivityEvent`. [eventAt] is the keyset cursor. */
data class ActivityEvent(
    val kind: ActivityKind,
    val eventAt: String,
    val friendUserId: String,
    val friendDisplayName: String?,
    val friendUsername: String?,
    val titleId: String,
    val tmdbId: Int,
    val mediaType: MediaType?,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val rating: Double?,
) {
    /** Web `handleOpen`: `friendDisplayName || friendUsername || 'Friend'`. */
    val friendName: String get() = friendDisplayName?.takeIf { it.isNotBlank() } ?: friendUsername?.takeIf { it.isNotBlank() } ?: "Friend"
}

/** Lightweight read-only view of a friend's `titles` row (RLS "friend read"). Never persisted. */
data class FriendTitle(
    val id: String,
    val tmdbId: Int,
    val type: MediaType?,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val status: LibraryStatus?,
    val rating: Double?,
    val genres: List<String>,
)

/** Pure rules mirroring the web social layer (`auth.ts`, `db.ts`, `Friends.tsx`,
 *  `TitleCommentsPanel.tsx`). */
object FriendsRules {
    /** Web `REACTION_EMOJIS`. */
    val REACTION_EMOJIS: List<String> = listOf("👍", "❤️", "😂", "😮")

    /** `title_comments.body` check constraint and web `BODY_MAX_LEN`. */
    const val COMMENT_MAX_LENGTH = 1000

    /** Web `ACTIVITY_PAGE_SIZE` / `fetchFriendActivityFeed` default limit. */
    const val ACTIVITY_PAGE_SIZE = 30

    /** `friend_activity_feed` caps `p_limit` at 50 server-side. */
    const val ACTIVITY_SERVER_MAX_PAGE = 50

    /** Web `Friends.tsx` `confirm(...)` text before `block_user`. */
    const val BLOCK_CONFIRM_MESSAGE =
        "Block this user? They will no longer be able to send you friend requests or view your library."

    const val NO_USER_FOUND_MESSAGE = "No user found with that email."
    const val REQUEST_SENT_MESSAGE = "Friend request sent."
    const val REQUEST_CANCELLED_MESSAGE = "Friend request cancelled."
    const val SEND_REQUEST_FAILED = "Failed to send friend request."
    const val CANCEL_REQUEST_FAILED = "Failed to cancel friend request."
    const val POST_COMMENT_FAILED = "Couldn't post that comment — check your connection."
    const val DELETE_COMMENT_FAILED = "Couldn't delete that comment — check your connection."
    const val SET_REACTION_FAILED = "Couldn't save that reaction — check your connection."

    /** Web `normalizeEmail`: trim + lowercase. */
    fun normalizeEmail(email: String): String = email.trim().lowercase()

    /** Comment text as web sends it: trimmed (and capped at [COMMENT_MAX_LENGTH] like the
     *  textarea's `slice`), or null when blank (web does not post blank comments). */
    fun prepareComment(raw: String): String? = raw.take(COMMENT_MAX_LENGTH).trim().takeIf { it.isNotEmpty() }

    /** Web `sendRecommendation` note/URL handling: `value?.trim() || null`. */
    fun blankToNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** Clamp a requested page size to what the server honours. */
    fun clampPageSize(limit: Int): Int = limit.coerceIn(1, ACTIVITY_SERVER_MAX_PAGE)

    /** Web `setHasMore(more.length === ACTIVITY_PAGE_SIZE)`: a full page means there may be more. */
    fun hasMore(pageSize: Int, limit: Int = ACTIVITY_PAGE_SIZE): Boolean = pageSize >= clampPageSize(limit)

    /** Keyset cursor for the next activity page: the last row's `eventAt` (web `last.eventAt`). */
    fun nextCursor(page: List<ActivityEvent>): String? = page.lastOrNull()?.eventAt

    /** Web `reactionStats`: counts and names for each of [REACTION_EMOJIS]; zero counts included. */
    fun summarize(reactions: List<TitleReaction>): List<ReactionSummary> = REACTION_EMOJIS.map { emoji ->
        val matching = reactions.filter { it.emoji == emoji }
        ReactionSummary(emoji, matching.size, matching.map { it.authorName })
    }

    /** Web `myReaction`: the viewer's emoji, if any. */
    fun myReaction(reactions: List<TitleReaction>, viewerUserId: String): String? =
        reactions.firstOrNull { it.authorId == viewerUserId }?.emoji

    /** Web toggle: tapping your current emoji clears it (null), otherwise sets it. */
    fun nextReaction(current: String?, tapped: String): String? = if (current == tapped) null else tapped

    /** Web `getErrorMessage(err, fallback)` for a PostgREST failure: the server's `message`
     *  (e.g. "Cannot send a friend request to this user"), else [fallback]. The raw
     *  [SupabaseHttpException] message embeds the URL, so it is never surfaced. */
    fun errorMessage(e: Throwable, fallback: String): String {
        if (e is SupabaseHttpException) {
            val serverMessage = runCatching { JSONObject(e.responseBody).optString("message") }.getOrNull()
            return serverMessage?.takeIf { it.isNotBlank() } ?: fallback
        }
        return e.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)

/** Web `row.rating ? parseFloat(row.rating) : null` — numeric(3,1) may arrive as number or string. */
private fun JSONObject.optRating(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return if (value.isNaN() || value == 0.0) null else value
}

private fun parseMediaType(wire: String?): MediaType? = when (wire) {
    "movie" -> MediaType.MOVIE
    "tv" -> MediaType.TV
    else -> null
}

internal fun mediaTypeWire(type: MediaType): String = when (type) {
    MediaType.MOVIE -> "movie"
    MediaType.TV -> "tv"
}

private fun parseLibraryStatus(wire: String?): LibraryStatus? = when (wire) {
    "watchlist" -> LibraryStatus.WATCHLIST
    "watching" -> LibraryStatus.WATCHING
    "watched" -> LibraryStatus.WATCHED
    "dropped" -> LibraryStatus.DROPPED
    else -> null
}

/** `find_user_by_email` returns a (possibly empty) table; empty -> null (web `data?.[0] ?? null`). */
internal fun parseFoundProfile(json: String): FoundProfile? {
    val array = JSONArray(json)
    if (array.length() == 0) return null
    val row = array.getJSONObject(0)
    return FoundProfile(
        userId = row.getString("user_id"),
        username = row.optStringOrNull("username"),
        displayName = row.optStringOrNull("display_name"),
    )
}

internal fun parseFriendships(json: String): List<Friendship> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val row = array.getJSONObject(i)
        Friendship(
            friendUserId = row.getString("friend_user_id"),
            status = FriendshipStatus.fromWire(row.optString("status")),
            requestedBy = row.optString("requested_by"),
            blockedBy = row.optStringOrNull("blocked_by"),
            createdAt = row.optString("created_at"),
            updatedAt = row.optString("updated_at"),
            displayName = row.optStringOrNull("display_name"),
            username = row.optStringOrNull("username"),
        )
    }
}

internal fun parseInviteConnections(json: String): List<InviteConnection> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val row = array.getJSONObject(i)
        InviteConnection(
            userId = row.getString("user_id"),
            username = row.optStringOrNull("username"),
            displayName = row.optStringOrNull("display_name"),
            connection = InviteConnectionKind.fromWire(row.optString("connection")),
        )
    }
}

internal fun parseRecommendations(json: String): List<Recommendation> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val row = array.getJSONObject(i)
        Recommendation(
            id = row.getString("id"),
            senderUserId = row.getString("sender_user_id"),
            senderDisplayName = row.optStringOrNull("sender_display_name"),
            senderUsername = row.optStringOrNull("sender_username"),
            tmdbId = row.getInt("tmdb_id"),
            type = parseMediaType(row.optStringOrNull("type")),
            title = row.optString("title"),
            year = row.optIntOrNull("year"),
            posterUrl = row.optStringOrNull("poster_url"),
            note = row.optStringOrNull("note"),
            watchUrl = row.optStringOrNull("watch_url"),
            status = RecommendationStatus.fromWire(row.optString("status")),
            createdAt = row.optString("created_at"),
        )
    }
}

/** Rows of `recommendations?select=recipient_user_id,status` -> recipient id to status.
 *  Rows without a recipient are skipped; a later duplicate wins (web's object assignment). */
internal fun parseSentRecommendationStatus(json: String): Map<String, RecommendationStatus> {
    val array = JSONArray(json)
    val result = LinkedHashMap<String, RecommendationStatus>()
    for (i in 0 until array.length()) {
        val row = array.getJSONObject(i)
        val recipient = row.optStringOrNull("recipient_user_id") ?: continue
        result[recipient] = RecommendationStatus.fromWire(row.optString("status"))
    }
    return result
}

internal fun parseTitleComments(json: String): List<TitleComment> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val row = array.getJSONObject(i)
        TitleComment(
            id = row.getString("id"),
            authorId = row.getString("author_id"),
            authorDisplayName = row.optStringOrNull("display_name"),
            authorUsername = row.optStringOrNull("username"),
            body = row.optString("body"),
            createdAt = row.optString("created_at"),
        )
    }
}

/** `add_title_comment` returns the bare `title_comments` row (an object; tolerate a one-element
 *  array too). Author names aren't included — web sets them null as well. */
internal fun parseAddedComment(json: String): TitleComment {
    val trimmed = json.trim()
    val row = if (trimmed.startsWith("[")) JSONArray(trimmed).getJSONObject(0) else JSONObject(trimmed)
    return TitleComment(
        id = row.getString("id"),
        authorId = row.getString("author_id"),
        authorDisplayName = null,
        authorUsername = null,
        body = row.optString("body"),
        createdAt = row.optString("created_at"),
    )
}

internal fun parseTitleReactions(json: String): List<TitleReaction> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val row = array.getJSONObject(i)
        TitleReaction(
            authorId = row.getString("author_id"),
            authorDisplayName = row.optStringOrNull("display_name"),
            authorUsername = row.optStringOrNull("username"),
            emoji = row.optString("emoji"),
        )
    }
}

internal fun parseActivityEvents(json: String): List<ActivityEvent> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val row = array.getJSONObject(i)
        ActivityEvent(
            kind = ActivityKind.fromWire(row.optString("event_type")),
            eventAt = row.optString("event_at"),
            friendUserId = row.getString("friend_user_id"),
            friendDisplayName = row.optStringOrNull("friend_display_name"),
            friendUsername = row.optStringOrNull("friend_username"),
            titleId = row.getString("title_id"),
            tmdbId = row.getInt("tmdb_id"),
            mediaType = parseMediaType(row.optStringOrNull("type")),
            title = row.optString("title"),
            year = row.optIntOrNull("year"),
            posterUrl = row.optStringOrNull("poster_url"),
            rating = row.optRating("rating"),
        )
    }
}

internal fun parseFriendTitles(json: String): List<FriendTitle> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val row = array.getJSONObject(i)
        val genres = row.optJSONArray("genres")
        FriendTitle(
            id = row.getString("id"),
            tmdbId = row.getInt("tmdb_id"),
            type = parseMediaType(row.optStringOrNull("type")),
            title = row.optString("title"),
            year = row.optIntOrNull("year"),
            posterUrl = row.optStringOrNull("poster_url"),
            status = parseLibraryStatus(row.optStringOrNull("status")),
            rating = row.optRating("rating"),
            genres = if (genres == null) emptyList() else (0 until genres.length()).mapNotNull { g -> genres.optString(g).takeIf { it.isNotEmpty() } },
        )
    }
}
