package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

private const val FRIEND = "00000000-0000-4000-8000-000000000002"
private const val VIEWER = "00000000-0000-4000-8000-000000000001"
private fun titleId(n: Int) = "10000000-0000-4000-8000-" + n.toString().padStart(12, '0')
private fun accepted(status: String = "accepted") =
    """[{"friend_user_id":"$FRIEND","status":"$status"}]"""
private fun graph(n: Int = 1) = JSONObject().put("id", titleId(n)).put("user_id", FRIEND)
    .put("tmdb_id", n).put("type", "movie").put("title", "Scoped film $n").put("status", "watched")
    .put("genres", JSONArray().put("Drama"))

private class ArchiveHttp {
    data class Reply(val body: String, val status: Int = 200, val after: () -> Unit = {})
    val replies = ArrayDeque<Reply>()
    val requests = mutableListOf<Request>()
    var session: SupabaseSession? = SupabaseSession("captured-token", VIEWER)
    fun reply(body: String, status: Int = 200, after: () -> Unit = {}) { replies += Reply(body, status, after) }
    val repo = FriendsRepository(SupabaseRestClient("https://unused.invalid", "public", OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            val next = replies.removeFirst()
            next.after()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(next.status)
                .message("fixture").body(next.body.toResponseBody()).build()
        }).build())) { session }
    fun successTail() { reply("[]"); reply("""[{"ledger_layout":null}]"""); reply(accepted()) }
}

private suspend fun rejected(block: suspend () -> Unit) {
    try { block(); fail("Expected rejection") } catch (_: IllegalStateException) { }
}

class FriendLibraryReadTest {
    @Test fun completeScopedGraphAndOnlyShareableLedgerColumn() = runTest {
        val http = ArchiveHttp()
        val title = graph()
        val child = JSONObject().put("id", "view").put("title_id", titleId(1)).put("user_id", FRIEND)
            .put("viewed_at", "2026-01-01").put("notes", "Shared history")
        title.put("viewings", JSONArray().put(child))
        val season = JSONObject().put("id", "season").put("title_id", titleId(1)).put("user_id", FRIEND)
            .put("season_number", 0).put("season_cast", JSONArray().put(JSONObject()
                .put("id", "cast").put("title_id", titleId(1)).put("season_id", "season")
                .put("user_id", FRIEND).put("name", "Special guest")))
        title.put("seasons", JSONArray().put(season))
        val episode = JSONObject().put("id", "episode").put("title_id", titleId(1)).put("user_id", FRIEND)
            .put("season_number", 0).put("episode_number", 1).put("episode_watch_events", JSONArray()
                .put(JSONObject().put("id", "watch").put("episode_id", "episode").put("user_id", FRIEND)
                    .put("notes", "Independent watch notes")))
        title.put("episodes", JSONArray().put(episode))
        http.reply(accepted()); http.reply(JSONArray().put(title).toString()); http.reply("[]")
        http.reply("""[{"ledger_layout":[{"id":"custom","panel":"moviegoing","width":"full"}]}]""")
        http.reply(accepted())
        val archive = http.repo.loadFriendLibrary(VIEWER, FRIEND)
        assertEquals(FRIEND, archive.ownerUserId)
        assertEquals("Shared history", archive.titles.single().graph().getJSONArray("viewings").getJSONObject(0).getString("notes"))
        assertEquals("Independent watch notes", archive.titles.single().graph().getJSONArray("episodes")
            .getJSONObject(0).getJSONArray("episode_watch_events").getJSONObject(0).getString("notes"))
        assertTrue(archive.ledgerLayoutJson!!.contains("custom"))
        val titlesRequest = http.requests.first { it.url.encodedPath.endsWith("/titles") }
        val select = titlesRequest.url.queryParameter("select")!!
        listOf("season_cast", "episode_watch_events", "episode_ratings", "episode_reviews", "episode_crew").forEach {
            assertTrue(select.contains(it))
        }
        assertEquals(FRIEND, titlesRequest.url.queryParameter("user_id")!!.removePrefix("eq."))
        val prefs = http.requests.single { it.url.encodedPath.endsWith("/user_prefs") }
        assertEquals("ledger_layout", prefs.url.queryParameter("select"))
        assertEquals("eq.$FRIEND", prefs.url.queryParameter("user_id"))
        assertTrue(http.requests.all { it.header("Authorization") == "Bearer captured-token" })
        assertTrue(http.requests.all { it.method == "GET" || it.url.encodedPath.endsWith("/list_friendships") })
    }

    @Test fun serverCappedPagesUseStableKeysEvenWhenAnEarlierTitleDisappears() = runTest {
        val http = ArchiveHttp()
        http.reply(accepted()); http.reply(JSONArray().put(graph(1)).toString())
        http.reply(JSONArray().put(graph(3)).toString()); http.successTail()
        val result = http.repo.loadFriendLibrary(VIEWER, FRIEND)
        assertEquals(listOf(titleId(1), titleId(3)), result.titles.map { it.id })
        val pages = http.requests.filter { it.url.encodedPath.endsWith("/titles") }
        assertEquals(listOf(null, "gt.${titleId(1)}", "gt.${titleId(3)}"), pages.map { it.url.queryParameter("id") })
        assertTrue(pages.all { it.url.queryParameter("offset") == null })
    }

    @Test fun scopeFilteredEmptyLibraryNeverFallsBackToOwnerOrUnscopedReads() = runTest {
        val http = ArchiveHttp()
        http.reply(accepted()); http.successTail()
        assertTrue(http.repo.loadFriendLibrary(VIEWER, FRIEND).titles.isEmpty())
        assertEquals(listOf("/rest/v1/rpc/list_friendships", "/rest/v1/titles", "/rest/v1/user_prefs",
            "/rest/v1/rpc/list_friendships"), http.requests.map { it.url.encodedPath })
    }

    @Test fun deniedRelationshipsDoNotReadArchiveOrPreferences() = runTest {
        for (response in listOf("[]", accepted("pending"), accepted("blocked"))) {
            val http = ArchiveHttp(); http.reply(response)
            rejected { http.repo.loadFriendLibrary(VIEWER, FRIEND) }
            assertEquals(1, http.requests.size)
        }
    }

    @Test fun revocationAfterPagesRejectsWholeSnapshot() = runTest {
        val http = ArchiveHttp()
        http.reply(accepted()); http.reply(JSONArray().put(graph()).toString()); http.reply("[]")
        http.reply("[]"); http.reply(accepted("blocked"))
        rejected { http.repo.loadFriendLibrary(VIEWER, FRIEND) }
        assertEquals(5, http.requests.size)
    }

    @Test fun accountSwitchDuringReadStopsFollowingRequests() = runTest {
        val http = ArchiveHttp()
        http.reply(accepted())
        http.reply(JSONArray().put(graph()).toString()) { http.session = SupabaseSession("other", "other") }
        rejected { http.repo.loadFriendLibrary(VIEWER, FRIEND) }
        assertEquals(2, http.requests.size)
    }

    @Test fun refreshOfSameOwnerTokenDoesNotChangeCapturedRequestIdentity() = runTest {
        val http = ArchiveHttp()
        http.reply(accepted()) { http.session = SupabaseSession("refreshed", VIEWER) }
        http.successTail()
        http.repo.loadFriendLibrary(VIEWER, FRIEND)
        assertTrue(http.requests.all { it.header("Authorization") == "Bearer captured-token" })
    }

    @Test fun malformedOwnerAndChildParentFailClosed() = runTest {
        val rows = listOf(
            graph().put("user_id", VIEWER),
            graph().put("viewings", JSONArray().put(JSONObject().put("id", "v").put("title_id", titleId(1)).put("user_id", VIEWER))),
            graph().put("viewings", JSONArray().put(JSONObject().put("id", "v").put("title_id", titleId(2)).put("user_id", FRIEND))),
        )
        for (row in rows) {
            val http = ArchiveHttp(); http.reply(accepted()); http.reply(JSONArray().put(row).toString())
            rejected { http.repo.loadFriendLibrary(VIEWER, FRIEND) }
            assertEquals(2, http.requests.size)
        }
    }

    @Test fun failedLaterPageCannotPublishEarlierTitles() = runTest {
        val http = ArchiveHttp()
        http.reply(accepted()); http.reply(JSONArray().put(graph()).toString())
        http.reply("""{"message":"unavailable"}""", 503)
        rejected { http.repo.loadFriendLibrary(VIEWER, FRIEND) }
        assertEquals(3, http.requests.size)
    }

    @Test fun repeatedContinuationAndMalformedLayoutFailClosed() = runTest {
        val repeated = ArchiveHttp()
        repeated.reply(accepted()); repeated.reply(JSONArray().put(graph()).toString())
        repeated.reply(JSONArray().put(graph()).toString())
        rejected { repeated.repo.loadFriendLibrary(VIEWER, FRIEND) }
        val malformed = ArchiveHttp()
        malformed.reply(accepted()); malformed.reply("[]"); malformed.reply("""[{"ledger_layout":{}}]""")
        rejected { malformed.repo.loadFriendLibrary(VIEWER, FRIEND) }
    }

    @Test fun signedOutAndWrongViewerFailBeforeHttp() = runTest {
        val http = ArchiveHttp(); http.session = null
        rejected { http.repo.loadFriendLibrary(VIEWER, FRIEND) }
        http.session = SupabaseSession("other", "other")
        rejected { http.repo.loadFriendLibrary(VIEWER, FRIEND) }
        assertTrue(http.requests.isEmpty())
    }
}

