package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder
import java.util.UUID

/** Authenticated, memory-only archive. No owner database or write API is exposed. */
interface FriendLibrarySource {
    suspend fun loadFriendLibrary(viewerUserId: String, friendUserId: String): SharedLibrary
}

// Same graph as web TITLE_SELECT. RLS applies the owner's friend scope to titles
// and every child. Do not substitute an owner sync RPC or fetch private outings.
private const val FRIEND_GRAPH = "*,title_cast(*),title_crew(*),seasons(*,season_cast(*)),viewings(*),episodes(*,episode_crew(*),episode_watch_events(*),episode_ratings(*),episode_reviews(*))"

internal suspend fun readFriendLibrary(
    client: SupabaseRestClient,
    sessionProvider: () -> SupabaseSession?,
    viewerUserId: String,
    friendUserId: String,
): SharedLibrary = withContext(Dispatchers.IO) {
    require(viewerUserId.isNotBlank() && friendUserId != viewerUserId)
    require(UUID.fromString(friendUserId).toString() == friendUserId) { "Invalid friend identity." }
    val context = currentCoroutineContext()
    val session = sessionProvider() ?: error("Not signed in.")
    fun checkAccount() {
        context.ensureActive()
        check(session.userId == viewerUserId && sessionProvider()?.userId == viewerUserId) {
            "Account changed. Reopen this archive."
        }
    }
    fun <T> request(block: () -> T): T {
        checkAccount()
        val result = block()
        checkAccount()
        return result
    }
    fun checkFriendship() {
        val friends = request { parseFriendships(client.rpc("list_friendships", "{}", session.accessToken)) }
        check(friends.any { it.friendUserId == friendUserId && it.status == FriendshipStatus.ACCEPTED }) {
            "This friend archive is no longer available."
        }
    }
    checkFriendship()
    val titles = mutableListOf<SharedLibraryTitle>()
    var cursor: String? = null
    while (true) {
        val query = "select=${URLEncoder.encode(FRIEND_GRAPH, "UTF-8")}&user_id=eq.$friendUserId&order=id.asc&limit=200" +
            (cursor?.let { "&id=gt.$it" } ?: "")
        val rows = request { parseSharedTitles(client.get("titles", query, session.accessToken), friendUserId) }
        if (rows.isEmpty()) break // Also handles a server cap below our requested limit.
        rows.forEach { row ->
            check(UUID.fromString(row.id).toString() == row.id && (cursor == null || row.id > cursor!!)) {
                "The friend archive returned an invalid continuation."
            }
            cursor = row.id
            titles += row
        }
    }
    // Web fetchLedgerLayout requests only this deliberately shareable column.
    // Never select arbitrary preferences, even though friend RLS permits this row.
    val prefs = request { JSONArray(client.get("user_prefs",
        "select=ledger_layout&user_id=eq.$friendUserId&limit=1", session.accessToken)) }
    check(prefs.length() <= 1) { "Invalid friend Ledger response." }
    val row = prefs.optJSONObject(0)
    check(prefs.length() == 0 || row != null) { "Invalid friend Ledger response." }
    check(row == null || (row.has("ledger_layout") &&
        (row.isNull("ledger_layout") || row.optJSONArray("ledger_layout") != null))) { "Invalid friend Ledger layout." }
    checkFriendship() // A block/revocation during loading never publishes a partial archive.
    SharedLibrary(friendUserId, titles, row?.optJSONArray("ledger_layout")?.toString())
}
