package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The nine `notifications.type` values (schema.sql's check constraint); [UNKNOWN] absorbs
 *  any type a newer server adds so an old build never drops the whole page. */
enum class NotificationType(val wire: String) {
    FRIEND_REQUEST_RECEIVED("friend_request_received"),
    FRIEND_REQUEST_ACCEPTED("friend_request_accepted"),
    SHARE_LINK_USED("share_link_used"),
    RECOMMENDATION_RECEIVED("recommendation_received"),
    COMMENT_RECEIVED("comment_received"),
    REACTION_RECEIVED("reaction_received"),
    INVITE_REDEEMED("invite_redeemed"),
    OUTING_COMPLETED("outing_completed"),
    OUTING_PLANS_SHARED("outing_plans_shared"),
    UNKNOWN("");

    companion object {
        fun fromWire(value: String): NotificationType = entries.firstOrNull { it.wire == value && it != UNKNOWN } ?: UNKNOWN
    }
}

/** One inbox row from the `list_notifications` RPC — mirrors web `AppNotificationItem`. */
data class AppNotification(
    val id: String,
    val type: NotificationType,
    val actorId: String?,
    val actorDisplayName: String?,
    val actorUsername: String?,
    val titleId: String?,
    val tmdbId: Int?,
    val mediaType: String?,
    val title: String?,
    val posterUrl: String?,
    val payload: JSONObject,
    val createdAt: String,
    val readAt: String?,
) {
    val isUnread: Boolean get() = readAt == null

    /** Web `actorName`: display name, then username, then "Someone". */
    val actorName: String get() = actorDisplayName?.takeIf { it.isNotBlank() } ?: actorUsername?.takeIf { it.isNotBlank() } ?: "Someone"
}

/** Where tapping a notification should land — web `NotificationCenter.handleItemClick`. */
sealed interface NotificationRoute {
    data class TitleDetail(val titleId: String) : NotificationRoute
    data object Profile : NotificationRoute
    /** Friends isn't on Android yet; the screen falls back to showing the row in place. */
    data object Friends : NotificationRoute
    data object None : NotificationRoute
}

object NotificationRules {
    const val PAGE_SIZE = 30
    /** `list_notifications` clamps `p_limit` to 50 server-side. */
    const val SERVER_MAX_PAGE = 50
    const val POLL_INTERVAL_MS = 45_000L

    fun route(n: AppNotification): NotificationRoute = when (n.type) {
        NotificationType.COMMENT_RECEIVED, NotificationType.REACTION_RECEIVED ->
            n.titleId?.let { NotificationRoute.TitleDetail(it) } ?: NotificationRoute.None
        NotificationType.OUTING_COMPLETED ->
            n.titleId?.let { NotificationRoute.TitleDetail(it) } ?: NotificationRoute.None
        NotificationType.FRIEND_REQUEST_RECEIVED, NotificationType.FRIEND_REQUEST_ACCEPTED,
        NotificationType.RECOMMENDATION_RECEIVED -> NotificationRoute.Friends
        NotificationType.INVITE_REDEEMED -> NotificationRoute.Profile
        else -> NotificationRoute.None
    }

    /** The row's sentence, matching the verbs in web `TYPE_META`. */
    fun describe(n: AppNotification): String {
        val title = n.title ?: "a title"
        return when (n.type) {
            NotificationType.FRIEND_REQUEST_RECEIVED -> "${n.actorName} sent you a friend request"
            NotificationType.FRIEND_REQUEST_ACCEPTED -> "${n.actorName} accepted your friend request"
            NotificationType.SHARE_LINK_USED -> {
                val label = n.payload.optString("label")
                "${n.actorName} viewed your shared link" + if (label.isNotEmpty()) " \"$label\"" else ""
            }
            NotificationType.RECOMMENDATION_RECEIVED -> "${n.actorName} sent you \"${n.payload.optString("title").ifEmpty { "a title" }}\""
            NotificationType.COMMENT_RECEIVED -> "${n.actorName} commented on \"$title\""
            NotificationType.REACTION_RECEIVED -> "${n.actorName} reacted ${n.payload.optString("emoji")} to \"$title\""
            NotificationType.INVITE_REDEEMED -> {
                val email = n.payload.optString("email")
                "${n.actorName} redeemed your invite code" + if (email.isNotEmpty()) " ($email)" else ""
            }
            NotificationType.OUTING_COMPLETED -> "${n.title ?: "Your movie"} just let out — how was it?"
            NotificationType.OUTING_PLANS_SHARED -> "${n.actorName} has tickets to \"${n.title ?: "a movie"}\""
            NotificationType.UNKNOWN -> "New activity"
        }
    }
}

internal fun parseNotifications(json: String): List<AppNotification> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val row = array.getJSONObject(i)
        AppNotification(
            id = row.getString("id"),
            type = NotificationType.fromWire(row.optString("type")),
            actorId = row.optStringOrNull("actor_id"),
            actorDisplayName = row.optStringOrNull("actor_display_name"),
            actorUsername = row.optStringOrNull("actor_username"),
            titleId = row.optStringOrNull("title_id"),
            tmdbId = if (row.isNull("tmdb_id")) null else row.optInt("tmdb_id"),
            mediaType = row.optStringOrNull("media_type"),
            title = row.optStringOrNull("title"),
            posterUrl = row.optStringOrNull("poster_url"),
            payload = row.optJSONObject("payload") ?: JSONObject(),
            createdAt = row.optString("created_at"),
            readAt = row.optStringOrNull("read_at"),
        )
    }
}

data class NotificationInbox(
    val items: List<AppNotification> = emptyList(),
    val unreadCount: Int = 0,
    /** False once a page came back shorter than requested — no more to load. */
    val hasMore: Boolean = true,
    val loaded: Boolean = false,
    val error: String? = null,
)

/**
 * The persistent notification inbox. Ports the store actions in `apps/web/src/store/useAppStore.ts`
 * (`loadNotificationInbox`/`markOneNotificationRead`/`markAllNotificationsSeen`/
 * `deleteNotificationItem`/`refreshUnreadNotificationCount`) over the same RPCs and RLS:
 * `list_notifications`, `unread_notification_count`, `mark_notification_read`,
 * `mark_all_notifications_read` and a direct recipient-only delete. Mutations are optimistic
 * and roll back (then recount) on failure, exactly like web.
 *
 * Account isolation: [onSessionChanged] clears the inbox when the signed-in user changes or
 * signs out, and in-flight results for a previous user are discarded.
 */
class NotificationsRepository(
    private val client: SupabaseRestClient,
    private val sessionProvider: () -> SupabaseSession?,
) {
    private val _inbox = MutableStateFlow(NotificationInbox())
    val inbox: StateFlow<NotificationInbox> = _inbox
    private var ownerId: String? = null
    private val mutex = Mutex()

    fun onSessionChanged(userId: String?) {
        if (userId != ownerId) {
            ownerId = userId
            _inbox.value = NotificationInbox()
        }
    }

    /** Keeps [inbox] scoped to [session] and returns it, or null when signed out. */
    private suspend fun activeSession(): SupabaseSession? {
        // currentSession() may refresh the token with a blocking call — never on the caller thread.
        val session = withContext(Dispatchers.IO) { sessionProvider() }
        onSessionChanged(session?.userId)
        return session
    }

    /** Applies [block] only if [userId] is still the signed-in user (drops stale results). */
    private fun updateIfCurrent(userId: String, block: (NotificationInbox) -> NotificationInbox) {
        if (ownerId == userId) _inbox.update(block)
    }

    suspend fun refreshUnreadCount() {
        val session = activeSession() ?: return
        runCatching {
            val count = withContext(Dispatchers.IO) { parseCount(client.rpc("unread_notification_count", "{}", session.accessToken)) }
            updateIfCurrent(session.userId) { it.copy(unreadCount = count) }
        }
    }

    /** Loads the first page (replacing the list) or, with [before] = the oldest loaded
     *  `created_at`, the next page (appended). Also refreshes the unread count. */
    suspend fun load(before: String? = null) = mutex.withLock {
        val session = activeSession() ?: return@withLock
        try {
            val params = JSONObject()
                .put("p_before", before ?: JSONObject.NULL)
                .put("p_limit", NotificationRules.PAGE_SIZE)
                .toString()
            val page = withContext(Dispatchers.IO) { parseNotifications(client.rpc("list_notifications", params, session.accessToken)) }
            updateIfCurrent(session.userId) { state ->
                state.copy(
                    items = if (before != null) state.items + page.filter { p -> state.items.none { it.id == p.id } } else page,
                    hasMore = page.size >= NotificationRules.PAGE_SIZE,
                    loaded = true,
                    error = null,
                )
            }
            refreshUnreadCount()
        } catch (e: Exception) {
            updateIfCurrent(session.userId) { it.copy(loaded = true, error = e.message ?: "Couldn't load notifications.") }
        }
    }

    suspend fun loadMore() {
        val last = _inbox.value.items.lastOrNull()?.createdAt ?: return
        if (_inbox.value.hasMore) load(before = last)
    }

    suspend fun markRead(id: String) {
        val session = activeSession() ?: return
        val before = _inbox.value
        val target = before.items.firstOrNull { it.id == id } ?: return
        if (!target.isUnread) return
        updateIfCurrent(session.userId) { s ->
            s.copy(
                items = s.items.map { if (it.id == id) it.copy(readAt = nowIso()) else it },
                unreadCount = (s.unreadCount - 1).coerceAtLeast(0),
            )
        }
        try {
            withContext(Dispatchers.IO) { client.rpc("mark_notification_read", JSONObject().put("p_id", id).toString(), session.accessToken) }
        } catch (e: Exception) {
            updateIfCurrent(session.userId) { it.copy(items = before.items) }
            refreshUnreadCount()
        }
    }

    suspend fun markAllRead() {
        val session = activeSession() ?: return
        val before = _inbox.value
        val now = nowIso()
        updateIfCurrent(session.userId) { s -> s.copy(items = s.items.map { if (it.isUnread) it.copy(readAt = now) else it }, unreadCount = 0) }
        try {
            withContext(Dispatchers.IO) { client.rpc("mark_all_notifications_read", "{}", session.accessToken) }
        } catch (e: Exception) {
            updateIfCurrent(session.userId) { it.copy(items = before.items) }
            refreshUnreadCount()
        }
    }

    suspend fun delete(id: String) {
        val session = activeSession() ?: return
        val before = _inbox.value
        val removed = before.items.firstOrNull { it.id == id } ?: return
        updateIfCurrent(session.userId) { s ->
            s.copy(
                items = s.items.filterNot { it.id == id },
                unreadCount = if (removed.isUnread) (s.unreadCount - 1).coerceAtLeast(0) else s.unreadCount,
            )
        }
        try {
            withContext(Dispatchers.IO) { client.delete("notifications", "id=eq.$id&recipient_id=eq.${session.userId}", session.accessToken) }
        } catch (e: Exception) {
            updateIfCurrent(session.userId) { it.copy(items = before.items) }
            refreshUnreadCount()
        }
    }

    private fun nowIso(): String = java.time.Instant.now().toString()

    private fun parseCount(body: String): Int = body.trim().toIntOrNull() ?: 0
}
