package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Records every request and answers from a scripted queue. */
private class ShareScript {
    val requests = mutableListOf<Request>()
    val bodies = mutableListOf<String>()
    private val responses = ArrayDeque<Pair<Int, String>>()

    fun reply(status: Int, body: String) = responses.addLast(status to body)

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        requests += request
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        bodies += buffer.readUtf8()
        val (status, body) = responses.removeFirst()
        Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(status).message("m")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }).build()
}

private const val SHARE_NOW = 1_800_000_000_000L // 2027-01-15T08:00:00Z

private fun shareSession(userId: String = "user-a") =
    SupabaseSession(accessToken = "tok-$userId", userId = userId, email = "$userId@example.com")

private fun shareRepoAt(http: ShareScript, s: SupabaseSession? = shareSession()): SharingRepository {
    val client = SupabaseRestClient("https://x.supabase.co", "anon-key", http.client)
    return SharingRepository(
        client = client,
        sessionProvider = { s },
        sharedTransport = SharedLibraryTransport.anonymous(client),
        clock = { SHARE_NOW },
    )
}

private fun keyJson(id: String, active: Boolean = true, label: String? = "Site", expires: String? = null, used: String? = null) =
    JSONObject().put("id", id).put("token", "t-$id").put("label", label ?: JSONObject.NULL)
        .put("expires_at", expires ?: JSONObject.NULL).put("is_active", active)
        .put("created_at", "2026-01-01T00:00:00+00:00").put("last_used_at", used ?: JSONObject.NULL)

private fun titleJson(id: String, year: Int? = 1999, poster: String? = null, rating: Double? = null) =
    JSONObject().put("id", id).put("user_id", "owner-1").put("tmdb_id", 603).put("type", "movie").put("title", "The Matrix")
        .put("year", year ?: JSONObject.NULL).put("poster_url", poster ?: JSONObject.NULL)
        .put("status", "watched").put("rating", rating ?: JSONObject.NULL)
        .put("genres", JSONArray(listOf("Sci-Fi"))).put("added_at", "2026-01-02T00:00:00Z")

class SharingRulesTest {
    @Test fun shareLinkUsesWebOriginAndShareParam() {
        assertEquals("https://cinemarchive.kumarfamilynet.work/?share=abc", SharingRules.shareLink("abc"))
    }

    @Test fun parseTokenAcceptsLinkOrBareToken() {
        assertEquals("abc123", SharingRules.parseToken("https://cinemarchive.kumarfamilynet.work/?share=abc123"))
        assertEquals("abc123", SharingRules.parseToken("https://cinemarchive.kumarfamilynet.work/?a=1&share=abc123&b=2"))
        assertEquals("abc123", SharingRules.parseToken("  abc123 "))
        assertNull(SharingRules.parseToken("   "))
        assertNull(SharingRules.parseToken("https://x/?share="))
        assertNull(SharingRules.parseToken("not a token"))
    }

    @Test fun intentLinksRequireTrustedOriginAndSingleValidToken() {
        assertEquals("abc123", SharingRules.tokenFromLink("cinemarchive://share?share=abc123"))
        assertEquals("abc123", SharingRules.tokenFromLink("https://cinemarchive.kumarfamilynet.work/?share=abc%31%32%33"))
        listOf("abc123", "https://evil.test/?share=abc", "https://cinemarchive.kumarfamilynet.work.evil.test/?share=abc",
            "https://user@cinemarchive.kumarfamilynet.work/?share=abc", "cinemarchive://auth-callback?share=abc",
            "https://cinemarchive.kumarfamilynet.work/?share=a&share=b", "cinemarchive://share?share=a%2Fb",
            "cinemarchive://share?share=%", "cinemarchive://share?share=").forEach {
            assertNull(it, SharingRules.tokenFromLink(it))
        }
        assertNull(SharingRules.parseToken("https://evil.test/?share=abc"))
    }

    @Test fun capCountsActiveKeysEvenIfExpired() {
        val keys = (1..10).map { SharedAccessKey("$it", "t", null, "2000-01-01T00:00:00Z", true, "c", null) }
        assertTrue(SharingRules.atCap(keys))
        assertFalse(SharingRules.atCap(keys.dropLast(1) + keys.last().copy(isActive = false)))
    }

    @Test fun expiryOptionsMatchWeb() {
        assertEquals(listOf(null, 24L, 168L, 720L), ShareExpiry.entries.map { it.hours })
        assertNull(SharingRules.expiresAtIso(ShareExpiry.NEVER, SHARE_NOW))
        assertEquals("2027-01-16T08:00:00Z", SharingRules.expiresAtIso(ShareExpiry.HOURS_24, SHARE_NOW))
        assertEquals("2027-02-14T08:00:00Z", SharingRules.expiresAtIso(ShareExpiry.DAYS_30, SHARE_NOW))
    }

    @Test fun isExpiredAtHandlesNullAndOffsetTimestamps() {
        val open = SharedAccessKey("1", "t", null, null, true, "c", null)
        assertFalse(open.isExpiredAt(SHARE_NOW))
        val past = open.copy(expiresAt = "2027-01-15T07:00:00+00:00")
        val future = open.copy(expiresAt = "2027-01-15T09:00:00+00:00")
        assertTrue(past.isExpiredAt(SHARE_NOW))
        assertFalse(future.isExpiredAt(SHARE_NOW))
    }
}

class SharingRepositoryTest {
    @Test fun listFiltersByOwnerNewestFirstAndParsesNulls() = runTest {
        val http = ShareScript().apply {
            reply(200, "[" + keyJson("1", label = null) + "," + keyJson("2", active = false, expires = "2027-01-01T00:00:00+00:00", used = "2026-02-02T00:00:00+00:00") + "]")
        }
        val keys = shareRepoAt(http).listSharedKeys()
        val url = http.requests[0].url.toString()
        assertTrue(url.contains("/rest/v1/shared_access_keys") && url.contains("user_id=eq.user-a") && url.contains("order=created_at.desc"))
        assertEquals("Bearer tok-user-a", http.requests[0].header("Authorization"))
        assertNull(keys[0].label)
        assertNull(keys[0].expiresAt)
        assertNull(keys[0].lastUsedAt)
        assertTrue(keys[0].isActive)
        assertFalse(keys[1].isActive)
        assertEquals("2026-02-02T00:00:00+00:00", keys[1].lastUsedAt)
    }

    @Test fun createChecksCapThenInsertsWithExpiryLabelAndOwner() = runTest {
        val http = ShareScript().apply {
            reply(200, "[" + keyJson("1") + "]")
            reply(201, "[" + keyJson("new", label = "Friends") + "]")
        }
        val created = shareRepoAt(http).createSharedKey("  Friends ", ShareExpiry.DAYS_7)
        assertEquals("t-new", created.token)
        assertEquals("GET", http.requests[0].method)
        assertEquals("POST", http.requests[1].method)
        assertTrue(http.requests[1].url.toString().endsWith("/rest/v1/shared_access_keys"))
        val sent = JSONObject(http.bodies[1])
        assertEquals("user-a", sent.getString("user_id"))
        assertEquals("Friends", sent.getString("label"))
        assertEquals("2027-01-22T08:00:00Z", sent.getString("expires_at"))
        assertEquals("return=representation", http.requests[1].header("Prefer"))
    }

    @Test fun createOmitsBlankLabelAndNeverExpiry() = runTest {
        val http = ShareScript().apply { reply(200, "[]"); reply(201, "[" + keyJson("n") + "]") }
        shareRepoAt(http).createSharedKey("   ")
        val sent = JSONObject(http.bodies[1])
        assertFalse(sent.has("label"))
        assertFalse(sent.has("expires_at"))
    }

    @Test fun createAtTenActiveLinksFailsWithoutInserting() = runTest {
        val rows = (1..10).joinToString(",", "[", "]") { keyJson("$it").toString() }
        val http = ShareScript().apply { reply(200, rows) }
        try {
            shareRepoAt(http).createSharedKey("x")
            fail()
        } catch (e: ShareLinkLimitException) {
            assertTrue(e.message!!.contains("10"))
        }
        assertEquals(1, http.requests.size)
        assertEquals("GET", http.requests[0].method)
    }

    @Test fun revokedLinksDoNotCountTowardCap() = runTest {
        val rows = (1..12).joinToString(",", "[", "]") { keyJson("$it", active = it <= 9).toString() }
        val http = ShareScript().apply { reply(200, rows); reply(201, "[" + keyJson("n") + "]") }
        shareRepoAt(http).createSharedKey("x")
        assertEquals(2, http.requests.size)
    }

    @Test fun revokePatchesIsActiveFalseForOwnRowOnly() = runTest {
        val http = ShareScript().apply { reply(200, "[" + keyJson("k1", active = false) + "]") }
        shareRepoAt(http).revokeSharedKey("k1")
        val url = http.requests[0].url.toString()
        assertEquals("PATCH", http.requests[0].method)
        assertTrue(url.contains("id=eq.k1") && url.contains("user_id=eq.user-a"))
        assertFalse(JSONObject(http.bodies[0]).getBoolean("is_active"))
    }

    @Test fun revokeOfUnknownRowThrows() = runTest {
        val http = ShareScript().apply { reply(200, "[]") }
        try { shareRepoAt(http).revokeSharedKey("nope"); fail() } catch (e: IllegalStateException) { assertEquals("Share link not found.", e.message) }
    }

    @Test fun missingScopeRowMeansUnrestricted() = runTest {
        val http = ShareScript().apply { reply(200, "[]") }
        assertNull(shareRepoAt(http).getShareScope(ShareScopeTarget.Link("k1")))
        val url = http.requests[0].url.toString()
        assertTrue(url.contains("/rest/v1/share_scopes") && url.contains("shared_key_id=eq.k1") && url.contains("owner_user_id=eq.user-a"))
    }

    @Test fun scopeParsesNullListsAsNoRestriction() = runTest {
        val http = ShareScript().apply {
            reply(200, """[{"allowed_genres":["Drama","Horror"],"allowed_statuses":null}]""")
            reply(200, """[{"allowed_genres":null,"allowed_statuses":["watched"]}]""")
        }
        val repo = shareRepoAt(http)
        val link = repo.getShareScope(ShareScopeTarget.Link("k1"))!!
        assertEquals(listOf("Drama", "Horror"), link.allowedGenres)
        assertNull(link.allowedStatuses)
        val friend = repo.getShareScope(ShareScopeTarget.Friend("f1"))!!
        assertNull(friend.allowedGenres)
        assertEquals(listOf("watched"), friend.allowedStatuses)
        assertTrue(http.requests[1].url.toString().contains("friend_user_id=eq.f1"))
    }

    @Test fun linkScopeUpsertsOnSharedKeyId() = runTest {
        val http = ShareScript().apply { reply(201, "[]") }
        shareRepoAt(http).setShareScope(ShareScopeTarget.Link("k1"), ShareScope(listOf("Drama"), null))
        val req = http.requests[0]
        assertEquals("POST", req.method)
        assertEquals("shared_key_id", req.url.queryParameter("on_conflict"))
        assertTrue(req.header("Prefer")!!.contains("merge-duplicates"))
        val sent = JSONObject(http.bodies[0])
        assertEquals("user-a", sent.getString("owner_user_id"))
        assertEquals("k1", sent.getString("shared_key_id"))
        assertFalse(sent.has("friend_user_id"))
        assertEquals("Drama", sent.getJSONArray("allowed_genres").getString(0))
        assertTrue(sent.isNull("allowed_statuses"))
        assertEquals("2027-01-15T08:00:00Z", sent.getString("updated_at"))
    }

    @Test fun friendScopeUpsertsOnOwnerAndFriendPair() = runTest {
        val http = ShareScript().apply { reply(201, "[]") }
        shareRepoAt(http).setShareScope(ShareScopeTarget.Friend("f1"), ShareScope(null, listOf("watched", "watching")))
        val req = http.requests[0]
        assertEquals("owner_user_id,friend_user_id", req.url.queryParameter("on_conflict"))
        val sent = JSONObject(http.bodies[0])
        assertEquals("f1", sent.getString("friend_user_id"))
        assertFalse(sent.has("shared_key_id"))
        assertTrue(sent.isNull("allowed_genres"))
        assertEquals(2, sent.getJSONArray("allowed_statuses").length())
    }

    @Test fun nullOrUnrestrictedScopeDeletesRatherThanStoringAllowEverything() = runTest {
        val http = ShareScript().apply { reply(204, ""); reply(204, "") }
        val repo = shareRepoAt(http)
        repo.setShareScope(ShareScopeTarget.Link("k1"), null)
        repo.setShareScope(ShareScopeTarget.Friend("f1"), ShareScope(null, null))
        assertEquals(listOf("DELETE", "DELETE"), http.requests.map { it.method })
        assertTrue(http.requests[0].url.toString().contains("shared_key_id=eq.k1") && http.requests[0].url.toString().contains("owner_user_id=eq.user-a"))
        assertTrue(http.requests[1].url.toString().contains("friend_user_id=eq.f1"))
    }

    @Test fun signedOutManagementCallsFailWithoutAnyRequest() = runTest {
        val http = ShareScript()
        val repo = shareRepoAt(http, null)
        suspend fun expectNotSignedIn(block: suspend () -> Unit) {
            try { block(); fail() } catch (e: IllegalStateException) { assertEquals("Not signed in.", e.message) }
        }
        expectNotSignedIn { repo.listSharedKeys() }
        expectNotSignedIn { repo.createSharedKey("x") }
        expectNotSignedIn { repo.revokeSharedKey("k") }
        expectNotSignedIn { repo.getShareScope(ShareScopeTarget.Link("k")) }
        expectNotSignedIn { repo.setShareScope(ShareScopeTarget.Link("k"), null) }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun mutationsUseCurrentSessionNotAPreviousOne() = runTest {
        var current: SupabaseSession? = shareSession("user-a")
        val http = ShareScript().apply { reply(200, "[]"); reply(200, "[]") }
        val repo = SharingRepository(
            SupabaseRestClient("https://x.supabase.co", "anon-key", http.client),
            { current },
        )
        repo.listSharedKeys()
        current = shareSession("user-b")
        repo.listSharedKeys()
        assertTrue(http.requests[0].url.toString().contains("user_id=eq.user-a"))
        assertTrue(http.requests[1].url.toString().contains("user_id=eq.user-b"))
        assertEquals("Bearer tok-user-b", http.requests[1].header("Authorization"))
    }
}

class SharedLibraryFetchTest {
    @Test fun publicKeysAreApikeyOnlyAndAuthenticatedRpcKeepsUserBearer() {
        for (key in listOf("sb_publishable_fixture", "eyJ.legacy.anon")) {
            val http = ShareScript().apply { reply(200, "{}"); reply(200, "{}") }
            val client = SupabaseRestClient("https://x.supabase.co", key, http.client)
            SharedLibraryTransport.anonymous(client).rpc("get_shared_library", "{}")
            client.rpc("authenticated_operation", "{}", "user-jwt")
            assertEquals(key, http.requests[0].header("apikey"))
            assertNull(http.requests[0].header("Authorization"))
            assertEquals(key, http.requests[1].header("apikey"))
            assertEquals("Bearer user-jwt", http.requests[1].header("Authorization"))
        }
    }

    private fun page(rows: List<JSONObject> = emptyList(), more: Boolean = false, owner: String = "owner-1", layout: JSONArray? = null) =
        JSONObject().put("ownerUserId", owner).put("titles", JSONArray(rows))
            .put("hasMore", more).put("ledgerLayout", layout ?: JSONObject.NULL).toString()

    @Test fun anonymousReadUsesOnlyStatelessRpcAndIncludesFirstPageLayout() = runTest {
        val http = ShareScript().apply {
            reply(200, page(listOf(titleJson("t1", year = null), titleJson("t2", rating = 4.5)),
                layout = JSONArray("""[{"id":"w1","panel":"stats","width":"full"}]""")))
        }
        val lib = shareRepoAt(http, null).fetchSharedLibrary("tok-abc")
        assertEquals("owner-1", lib.ownerUserId)
        assertEquals(2, lib.titles.size)
        assertNull(lib.titles[0].year)
        assertEquals(4.5, lib.titles[1].rating!!, 0.0)
        assertEquals("w1", JSONArray(lib.ledgerLayoutJson).getJSONObject(0).getString("id"))
        assertEquals(1, http.requests.size)
        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.toString().endsWith("/rpc/get_shared_library"))
        assertNull(request.header("Authorization"))
        assertEquals("anon-key", request.header("apikey"))
        val params = JSONObject(http.bodies.single())
        assertEquals("tok-abc", params.getString("p_token"))
        assertEquals(0, params.getInt("p_offset"))
        assertEquals(100, params.getInt("p_limit"))
    }

    @Test fun validEmptyScopeDoesNotRetryOrReadOwnerPreferences() = runTest {
        val http = ShareScript().apply { reply(200, page()) }
        val library = shareRepoAt(http, null).fetchSharedLibrary("tok")
        assertEquals("owner-1", library.ownerUserId)
        assertTrue(library.titles.isEmpty())
        assertNull(library.ledgerLayoutJson)
        assertEquals(1, http.requests.size)
    }

    @Test fun pagesFollowHasMoreAndDedupeWithoutChangingOffsets() = runTest {
        val http = ShareScript().apply {
            reply(200, page(listOf(titleJson("a"), titleJson("b")), more = true,
                layout = JSONArray("""[{"id":"first"}]""")))
            reply(200, page(listOf(titleJson("b"), titleJson("c")), layout = JSONArray("""[{"id":"later"}]""")))
        }
        val library = shareRepoAt(http).fetchSharedLibrary("tok")
        assertEquals(listOf("a", "b", "c"), library.titles.map { it.id })
        assertEquals(listOf(0, 2), http.bodies.map { JSONObject(it).getInt("p_offset") })
        assertEquals("first", JSONArray(library.ledgerLayoutJson).getJSONObject(0).getString("id"))
        assertTrue(http.requests.all { it.url.toString().endsWith("/rpc/get_shared_library") })
        assertTrue(http.requests.all { it.header("Authorization") == null && it.header("apikey") == "anon-key" })
    }

    @Test fun invalidLinksAreExplicitForBothAnonymousAndAuthenticatedHttpCodes() = runTest {
        for (status in listOf(401, 403)) {
            val http = ShareScript().apply { reply(status, """{"code":"42501","message":"Invalid or expired share link"}""") }
            try { shareRepoAt(http).fetchSharedLibrary("dead"); fail() } catch (_: SharedLinkUnavailableException) {}
            assertEquals(1, http.requests.size)
        }
    }

    @Test fun continuationFailureNeverReturnsPartialLibrary() = runTest {
        val http = ShareScript().apply {
            reply(200, page(listOf(titleJson("a")), more = true))
            reply(500, """{"code":"XX000"}""")
        }
        try { shareRepoAt(http).fetchSharedLibrary("tok"); fail() } catch (error: SupabaseHttpException) {
            assertEquals(500, error.status)
        }
    }

    @Test fun malformedEmptyContinuationAndChangedOwnerFailClosed() = runTest {
        for (second in listOf(page(more = true), page(owner = "other"))) {
            val http = ShareScript().apply {
                reply(200, page(listOf(titleJson("a")), more = true)); reply(200, second)
            }
            try { shareRepoAt(http).fetchSharedLibrary("tok"); fail() } catch (_: IllegalStateException) {}
            assertEquals(2, http.requests.size)
        }
    }

    @Test fun invalidTokenShapeMakesNoRequest() = runTest {
        val http = ShareScript()
        for (token in listOf("  ", "a".repeat(513))) {
            try { shareRepoAt(http).fetchSharedLibrary(token); fail() } catch (_: IllegalArgumentException) {}
        }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun repeatedNonemptyContinuationFailsInsteadOfLooping() = runTest {
        val http = ShareScript().apply {
            reply(200, page(listOf(titleJson("a")), more = true))
            reply(200, page(listOf(titleJson("a")), more = true))
        }
        try { shareRepoAt(http).fetchSharedLibrary("tok"); fail() } catch (_: IllegalStateException) {}
        assertEquals(2, http.requests.size)
    }

    @Test fun oversizedPagesAndMissingOrMalformedLayoutFailClosed() = runTest {
        val missing = JSONObject(page()).apply { remove("ledgerLayout") }.toString()
        val malformed = JSONObject(page()).put("ledgerLayout", JSONObject()).toString()
        for (body in listOf(missing, malformed, page((0..100).map { titleJson("t$it") }))) {
            val http = ShareScript().apply { reply(200, body) }
            try { shareRepoAt(http).fetchSharedLibrary("tok"); fail() } catch (_: IllegalStateException) {}
            assertEquals(1, http.requests.size)
        }
    }

    @Test fun nestedGraphRetainsCreditsIndependentLogsAndMetadataWithoutMutableAliases() = runTest {
        val title = titleJson("series").put("type", "tv").put("notes", "Owner notes")
            .put("tags", JSONArray(listOf("favorite"))).put("rt_score", 95)
            .put("seasons", JSONArray("""[{"id":"s1","user_id":"owner-1","title_id":"series","season_number":1,"season_cast":[{"id":"sc","user_id":"owner-1","season_id":"s1","name":"Actor"}]}]"""))
            .put("episodes", JSONArray("""[{"id":"e1","user_id":"owner-1","title_id":"series","season_number":1,"episode_number":1,"episode_crew":[{"id":"ec","user_id":"owner-1","episode_id":"e1","name":"Director"}],"episode_watch_events":[{"id":"w","episode_id":"e1","user_id":"owner-1","watched_at":null}],"episode_ratings":[{"id":"r","user_id":"owner-1","episode_id":"e1","rating":4}],"episode_reviews":[{"id":"rv","user_id":"owner-1","episode_id":"e1","review_text":"Great"}]}]"""))
        val http = ShareScript().apply { reply(200, page(listOf(title))) }
        val result = shareRepoAt(http).fetchSharedLibrary("tok").titles.single()
        val graph = result.graph()
        assertEquals("Actor", graph.getJSONArray("seasons").getJSONObject(0).getJSONArray("season_cast").getJSONObject(0).getString("name"))
        val episode = graph.getJSONArray("episodes").getJSONObject(0)
        assertEquals("Great", episode.getJSONArray("episode_reviews").getJSONObject(0).getString("review_text"))
        assertTrue(episode.getJSONArray("episode_watch_events").getJSONObject(0).isNull("watched_at"))
        assertEquals(95, graph.getInt("rt_score"))
        graph.put("notes", "Changed outside viewer")
        assertEquals("Owner notes", result.graph().getString("notes"))
    }

    @Test fun ownerAndNestedParentMismatchesAndPrivateAttachmentsFailClosed() = runTest {
        val badRows = listOf(
            titleJson("a").put("user_id", "other"),
            titleJson("a").put("viewings", JSONArray("""[{"id":"v","title_id":"hidden","user_id":"owner-1"}]""")),
            titleJson("a").put("episodes", JSONArray("""[{"id":"e","user_id":"owner-1","title_id":"a","episode_reviews":[{"id":"r","episode_id":"e","user_id":"other"}]}]""")),
            titleJson("a").put("ticket_image_path", "/private/ticket.png"),
            titleJson("a").put("viewings", JSONArray("""[{"id":"v","user_id":"owner-1","title_id":"a","ticket_image_path":"/private/ticket.png"}]""")),
            titleJson("a").put("seasons", JSONArray("""[{"id":"s","user_id":"owner-1","title_id":"a","season_cast":[{"id":"c","user_id":"owner-1","season_id":"s","title_id":"hidden"}]}]""")),
        )
        for (title in badRows) {
            val http = ShareScript().apply { reply(200, page(listOf(title))) }
            try { shareRepoAt(http).fetchSharedLibrary("tok"); fail() } catch (_: IllegalStateException) {}
        }
    }

    @Test fun everyPersistedNestedRelationRequiresItsOwnerButCompanionObjectsDoNot() = runTest {
        val relationPaths = listOf("title_cast", "title_crew", "viewings", "seasons", "episodes")
        for (relation in relationPaths) {
            val row = titleJson("a").put(relation, JSONArray("""[{"id":"child","title_id":"a"}]"""))
            val http = ShareScript().apply { reply(200, page(listOf(row))) }
            try { shareRepoAt(http).fetchSharedLibrary("tok"); fail(relation) } catch (_: IllegalStateException) {}
        }
        for (relation in listOf("episode_crew", "episode_watch_events", "episode_ratings", "episode_reviews")) {
            val ep = JSONObject().put("id", "ep").put("title_id", "a").put("user_id", "owner-1")
                .put(relation, JSONArray("""[{"id":"child","episode_id":"ep"}]"""))
            val row = titleJson("a").put("episodes", JSONArray(listOf(ep)))
            val http = ShareScript().apply { reply(200, page(listOf(row))) }
            try { shareRepoAt(http).fetchSharedLibrary("tok"); fail(relation) } catch (_: IllegalStateException) {}
        }
        val season = JSONObject("""{"id":"s","title_id":"a","user_id":"owner-1","season_cast":[{"id":"c","season_id":"s"}]}""")
        val http = ShareScript().apply { reply(200, page(listOf(titleJson("a").put("seasons", JSONArray(listOf(season)))))) }
        try { shareRepoAt(http).fetchSharedLibrary("tok"); fail("season_cast") } catch (_: IllegalStateException) {}
        val valid = titleJson("a").put("viewings", JSONArray("""[{"id":"v","title_id":"a","user_id":"owner-1","companions":[{"name":"Sam"}]}]"""))
        val validHttp = ShareScript().apply { reply(200, page(listOf(valid))) }
        assertEquals(1, shareRepoAt(validHttp).fetchSharedLibrary("tok").titles.size)
    }
}
