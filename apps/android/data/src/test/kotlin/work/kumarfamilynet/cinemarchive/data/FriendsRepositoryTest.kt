package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.LibraryStatus
import work.kumarfamilynet.cinemarchive.core.model.MediaType

/** Records every request and answers from a scripted queue. */
private class FriendsScriptedHttp {
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

    fun path(i: Int): String = requests[i].url.encodedPath
    fun params(i: Int): JSONObject = JSONObject(bodies[i])
}

private fun friendsSession(userId: String = "me") =
    SupabaseSession(accessToken = "tok-$userId", userId = userId, email = "$userId@example.com")

private fun friendsRepo(http: FriendsScriptedHttp, s: SupabaseSession? = friendsSession()) =
    FriendsRepository(SupabaseRestClient("https://x.supabase.co", "anon", http.client)) { s }

private suspend fun expectFailure(message: String, block: suspend () -> Unit) {
    try {
        block()
        fail("expected failure: $message")
    } catch (e: IllegalStateException) {
        assertEquals(message, e.message)
    }
}

class FriendsRulesTest {
    @Test fun normalizeEmailTrimsAndLowercases() {
        assertEquals("a@b.com", FriendsRules.normalizeEmail("  A@B.Com \n"))
    }

    @Test fun reactionEmojiMatchWeb() {
        assertEquals(listOf("👍", "❤️", "😂", "😮"), FriendsRules.REACTION_EMOJIS)
    }

    @Test fun commentPreparationTrimsCapsAndRejectsBlank() {
        assertEquals("hi", FriendsRules.prepareComment("  hi \n"))
        assertNull(FriendsRules.prepareComment("   "))
        assertEquals(1000, FriendsRules.prepareComment("x".repeat(1500))!!.length)
    }

    @Test fun paginationHelpers() {
        assertEquals(1, FriendsRules.clampPageSize(0))
        assertEquals(50, FriendsRules.clampPageSize(500))
        assertTrue(FriendsRules.hasMore(30))
        assertFalse(FriendsRules.hasMore(29))
        assertNull(FriendsRules.nextCursor(emptyList()))
    }

    @Test fun relationSemanticsMirrorFriendshipView() {
        fun f(status: FriendshipStatus, requestedBy: String, blockedBy: String? = null) =
            Friendship("them", status, requestedBy, blockedBy, "c", "u", null, null)
        assertEquals(FriendshipRelation.REQUEST_SENT, f(FriendshipStatus.PENDING, "me").relationFor("me"))
        assertEquals(FriendshipRelation.REQUEST_RECEIVED, f(FriendshipStatus.PENDING, "them").relationFor("me"))
        assertEquals(FriendshipRelation.FRIENDS, f(FriendshipStatus.ACCEPTED, "them").relationFor("me"))
        assertEquals(FriendshipRelation.BLOCKED_BY_ME, f(FriendshipStatus.BLOCKED, "me", "me").relationFor("me"))
        assertEquals(FriendshipRelation.UNAVAILABLE, f(FriendshipStatus.BLOCKED, "them", "them").relationFor("me"))
        assertEquals(FriendshipRelation.UNKNOWN, f(FriendshipStatus.UNKNOWN, "them").relationFor("me"))
        assertEquals("Unknown user", f(FriendshipStatus.PENDING, "me").name)
        assertEquals("Friend", f(FriendshipStatus.PENDING, "me").libraryLabel)
    }

    @Test fun reactionSummaryCountsOnlyKnownEmojiInOrder() {
        val rs = listOf(
            TitleReaction("a", "Ann", null, "❤️"),
            TitleReaction("b", null, "bob", "❤️"),
            TitleReaction("c", null, null, "🔥"),
        )
        val summary = FriendsRules.summarize(rs)
        assertEquals(FriendsRules.REACTION_EMOJIS, summary.map { it.emoji })
        assertEquals(2, summary[1].count)
        assertEquals(listOf("Ann", "bob"), summary[1].names)
        assertEquals(0, summary[0].count)
        assertEquals("❤️", FriendsRules.myReaction(rs, "b"))
        assertNull(FriendsRules.myReaction(rs, "zzz"))
        assertNull(FriendsRules.nextReaction("❤️", "❤️"))
        assertEquals("😂", FriendsRules.nextReaction("❤️", "😂"))
    }
}

class FriendsParsingTest {
    @Test fun friendshipsParseNullsAndUnknownStatus() {
        val list = parseFriendships(
            """[{"friend_user_id":"f1","status":"accepted","requested_by":"me","blocked_by":null,
            "created_at":"c","updated_at":"u","display_name":null,"username":"fran"},
            {"friend_user_id":"f2","status":"frozen","requested_by":"f2","blocked_by":null,
            "created_at":"c","updated_at":"u","display_name":"","username":null}]""",
        )
        assertEquals(FriendshipStatus.ACCEPTED, list[0].status)
        assertNull(list[0].blockedBy)
        assertEquals("fran", list[0].name)
        assertEquals(FriendshipStatus.UNKNOWN, list[1].status)
        assertNull(list[1].displayName)
    }

    @Test fun inviteConnectionsParseUnknownKind() {
        val list = parseInviteConnections(
            """[{"user_id":"u1","username":null,"display_name":"Ann","connection":"invited_by_you"},
            {"user_id":"u2","username":"bo","display_name":null,"connection":"mystery"}]""",
        )
        assertEquals(InviteConnectionKind.INVITED_BY_YOU, list[0].connection)
        assertEquals(InviteConnectionKind.UNKNOWN, list[1].connection)
        assertNull(list[0].username)
    }

    @Test fun recommendationsParseNullsAndUnknownEnums() {
        val list = parseRecommendations(
            """[{"id":"r1","sender_user_id":"s","sender_display_name":null,"sender_username":null,"tmdb_id":5,
            "type":"tv","title":"Heat","year":null,"poster_url":null,"note":null,"watch_url":null,
            "status":"unread","created_at":"t"},
            {"id":"r2","sender_user_id":"s","sender_display_name":"Sam","sender_username":"sam","tmdb_id":6,
            "type":"game","title":"X","year":1999,"poster_url":"p","note":"n","watch_url":"w",
            "status":"archived","created_at":"t"}]""",
        )
        assertEquals(MediaType.TV, list[0].type)
        assertNull(list[0].year)
        assertTrue(list[0].isUnread)
        assertEquals("Someone", list[0].senderName)
        assertNull(list[1].type)
        assertEquals(RecommendationStatus.UNKNOWN, list[1].status)
        assertEquals("Sam", list[1].senderName)
        assertEquals("w", list[1].watchUrl)
    }

    @Test fun activityParsesRatingStringsNullsAndUnknownKinds() {
        val list = parseActivityEvents(
            """[{"event_type":"viewing_logged","event_at":"2026-01-02T00:00:00Z","friend_user_id":"f","friend_display_name":null,
            "friend_username":"fran","title_id":"t1","tmdb_id":1,"type":"movie","title":"Heat","year":1995,"poster_url":null,"rating":"8.5"},
            {"event_type":"title_added","event_at":"e","friend_user_id":"f","friend_display_name":null,"friend_username":null,
            "title_id":"t2","tmdb_id":2,"type":"tv","title":"Lost","year":null,"poster_url":null,"rating":null},
            {"event_type":"quantum_leap","event_at":"e","friend_user_id":"f","friend_display_name":null,"friend_username":null,
            "title_id":"t3","tmdb_id":3,"type":"movie","title":"T","year":null,"poster_url":null,"rating":7}]""",
        )
        assertEquals(ActivityKind.VIEWING_LOGGED, list[0].kind)
        assertEquals(8.5, list[0].rating!!, 0.0001)
        assertEquals("watched", list[0].kind.verb)
        assertEquals("fran", list[0].friendName)
        assertNull(list[1].rating)
        assertNull(list[1].year)
        assertEquals("Friend", list[1].friendName)
        assertEquals(ActivityKind.UNKNOWN, list[2].kind)
        assertEquals(7.0, list[2].rating!!, 0.0001)
        assertEquals(listOf("added", "commented on", "reacted to"), listOf(ActivityKind.TITLE_ADDED, ActivityKind.COMMENT_ADDED, ActivityKind.REACTION_ADDED).map { it.verb })
    }

    @Test fun addedCommentAcceptsObjectOrArray() {
        val obj = parseAddedComment("""{"id":"c1","title_id":"t","author_id":"me","body":"hi","created_at":"t"}""")
        assertEquals("c1", obj.id)
        assertNull(obj.authorDisplayName)
        assertEquals("Someone", obj.authorName)
        assertEquals("c2", parseAddedComment("""[{"id":"c2","author_id":"me","body":"b","created_at":"t"}]""").id)
    }

    @Test fun friendTitlesParse() {
        val list = parseFriendTitles(
            """[{"id":"t1","tmdb_id":1,"type":"movie","title":"Heat","year":1995,"poster_url":null,"status":"watched","rating":9.0,"genres":["Crime","Drama"]},
            {"id":"t2","tmdb_id":2,"type":"zzz","title":"?","year":null,"poster_url":"p","status":"someday","rating":null,"genres":null}]""",
        )
        assertEquals(LibraryStatus.WATCHED, list[0].status)
        assertEquals(listOf("Crime", "Drama"), list[0].genres)
        assertEquals(9.0, list[0].rating!!, 0.0001)
        assertNull(list[1].type)
        assertNull(list[1].status)
        assertTrue(list[1].genres.isEmpty())
    }
}

class FriendsRepositoryTest {
    @Test fun findUserByEmailNormalizesAndReturnsProfileOrNull() = runTest {
        val http = FriendsScriptedHttp().apply {
            reply(200, """[{"user_id":"u9","username":"fran","display_name":null}]""")
            reply(200, "[]")
        }
        val repo = friendsRepo(http)
        val found = repo.findUserByEmail("  Fran@Example.COM ")!!
        assertEquals("u9", found.userId)
        assertNull(found.displayName)
        assertEquals("/rest/v1/rpc/find_user_by_email", http.path(0))
        assertEquals("fran@example.com", http.params(0).getString("lookup_email"))
        assertEquals("Bearer tok-me", http.requests[0].header("Authorization"))
        assertNull(repo.findUserByEmail("nobody@example.com"))
    }

    @Test fun blankEmailMakesNoRequest() = runTest {
        val http = FriendsScriptedHttp()
        assertNull(friendsRepo(http).findUserByEmail("   "))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun sendByEmailLooksUpThenSendsAndUnknownEmailSendsNothing() = runTest {
        val http = FriendsScriptedHttp().apply {
            reply(200, """[{"user_id":"u9","username":null,"display_name":"Fran"}]""")
            reply(204, "")
            reply(200, "[]")
        }
        val repo = friendsRepo(http)
        assertEquals("u9", repo.sendFriendRequestByEmail("f@x.com")!!.userId)
        assertEquals("/rest/v1/rpc/send_friend_request", http.path(1))
        assertEquals("u9", http.params(1).getString("target_user_id"))
        assertNull(repo.sendFriendRequestByEmail("none@x.com"))
        assertEquals(3, http.requests.size)
    }

    @Test fun friendshipMutationsUseWebRpcNamesAndParamKeys() = runTest {
        val http = FriendsScriptedHttp().apply { repeat(5) { reply(204, "") } }
        val repo = friendsRepo(http)
        repo.acceptFriendRequest("a")
        repo.declineFriendRequest("b")
        repo.cancelFriendRequest("c")
        repo.blockFriend("d")
        repo.unblockFriend("e")
        val expected = listOf(
            Triple("accept_friend_request", "requester_user_id", "a"),
            Triple("decline_friend_request", "requester_user_id", "b"),
            Triple("cancel_friend_request", "recipient_user_id", "c"),
            Triple("block_user", "target_user_id", "d"),
            Triple("unblock_user", "target_user_id", "e"),
        )
        expected.forEachIndexed { i, (name, key, value) ->
            assertEquals("/rest/v1/rpc/$name", http.path(i))
            assertEquals(setOf(key), http.params(i).keys().asSequence().toSet())
            assertEquals(value, http.params(i).getString(key))
            assertEquals("POST", http.requests[i].method)
        }
    }

    @Test fun listFriendshipsAndInviteConnectionsSendEmptyParams() = runTest {
        val http = FriendsScriptedHttp().apply {
            reply(200, """[{"friend_user_id":"f","status":"pending","requested_by":"f","blocked_by":null,"created_at":"c","updated_at":"u","display_name":"Fran","username":null}]""")
            reply(200, """[{"user_id":"u","username":"u","display_name":null,"connection":"invited_you"}]""")
        }
        val repo = friendsRepo(http)
        val friendships = repo.listFriendships()
        assertEquals(FriendshipRelation.REQUEST_RECEIVED, friendships.single().relationFor("me"))
        assertEquals(InviteConnectionKind.INVITED_YOU, repo.listInviteConnections().single().connection)
        assertEquals("/rest/v1/rpc/list_friendships", http.path(0))
        assertEquals("/rest/v1/rpc/list_invite_connections", http.path(1))
        assertEquals("{}", http.bodies[0])
    }

    @Test fun sendRequestMapsServerMessagesAndFallbacks() = runTest {
        val http = FriendsScriptedHttp().apply {
            reply(400, """{"code":"P0001","message":"Cannot send a friend request to this user"}""")
            reply(403, """{"code":"42501","message":"permission denied for function send_friend_request"}""")
            reply(409, """{"code":"23505"}""")
            reply(500, "boom")
        }
        val repo = friendsRepo(http)
        expectFailure("Cannot send a friend request to this user") { repo.sendFriendRequest("x") }
        expectFailure("permission denied for function send_friend_request") { repo.sendFriendRequest("x") }
        expectFailure("Failed to send friend request.") { repo.sendFriendRequest("x") }
        expectFailure("Failed to send friend request.") { repo.sendFriendRequest("x") }
    }

    @Test fun cancelMapsServerMessageAndFallback() = runTest {
        val http = FriendsScriptedHttp().apply {
            reply(400, """{"code":"P0001","message":"No pending friend request to this user"}""")
            reply(502, "<html>")
        }
        val repo = friendsRepo(http)
        expectFailure("No pending friend request to this user") { repo.cancelFriendRequest("x") }
        expectFailure("Failed to cancel friend request.") { repo.cancelFriendRequest("x") }
    }

    @Test fun errorCauseIsPreserved() = runTest {
        val http = FriendsScriptedHttp().apply { reply(403, """{"code":"42501","message":"denied"}""") }
        try {
            friendsRepo(http).blockFriend("x")
            fail()
        } catch (e: IllegalStateException) {
            val http = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SupabaseHttpException>().first()
            assertEquals("42501", http.postgresCode)
        }
    }

    @Test fun fetchFriendTitlesReadsTitlesTableThroughRlsAndFiltersByFriend() = runTest {
        val http = FriendsScriptedHttp().apply {
            reply(200, """[{"id":"t1","tmdb_id":1,"type":"tv","title":"Lost","year":2004,"poster_url":null,"status":"watching","rating":null,"genres":["Drama"]}]""")
        }
        val titles = friendsRepo(http).fetchFriendTitles("friend-1")
        assertEquals(MediaType.TV, titles.single().type)
        assertEquals(LibraryStatus.WATCHING, titles.single().status)
        val url = http.requests[0].url
        assertEquals("GET", http.requests[0].method)
        assertEquals("/rest/v1/titles", url.encodedPath)
        assertEquals("eq.friend-1", url.queryParameter("user_id"))
        assertEquals("id,tmdb_id,type,title,year,poster_url,status,rating,genres", url.queryParameter("select"))
    }

    @Test fun sendRecommendationSendsExactParamsWithTrimmedNullableFields() = runTest {
        val http = FriendsScriptedHttp().apply { reply(204, ""); reply(204, "") }
        val repo = friendsRepo(http)
        repo.sendRecommendation("friend-1", RecommendationDraft(603, MediaType.MOVIE, "The Matrix", 1999, "poster"), "  must see  ", " https://w ")
        val p = http.params(0)
        assertEquals("/rest/v1/rpc/send_recommendation", http.path(0))
        assertEquals(
            setOf("recipient_id", "p_tmdb_id", "p_type", "p_title", "p_year", "p_poster_url", "p_note", "p_watch_url"),
            p.keys().asSequence().toSet(),
        )
        assertEquals("friend-1", p.getString("recipient_id"))
        assertEquals(603, p.getInt("p_tmdb_id"))
        assertEquals("movie", p.getString("p_type"))
        assertEquals(1999, p.getInt("p_year"))
        assertEquals("must see", p.getString("p_note"))
        assertEquals("https://w", p.getString("p_watch_url"))

        repo.sendRecommendation("friend-1", RecommendationDraft(1, MediaType.TV, "Lost", null, null), "   ", null)
        val q = http.params(1)
        assertEquals("tv", q.getString("p_type"))
        assertTrue(q.isNull("p_year") && q.isNull("p_poster_url") && q.isNull("p_note") && q.isNull("p_watch_url"))
    }

    @Test fun sendRecommendationSurfacesNotFriendsMessage() = runTest {
        val http = FriendsScriptedHttp().apply { reply(400, """{"code":"P0001","message":"Can only send recommendations to accepted friends"}""") }
        expectFailure("Can only send recommendations to accepted friends") {
            friendsRepo(http).sendRecommendation("x", RecommendationDraft(1, MediaType.MOVIE, "T", null, null))
        }
    }

    @Test fun sentRecommendationStatusIsScopedToSenderAndTitle() = runTest {
        val http = FriendsScriptedHttp().apply {
            reply(200, """[{"recipient_user_id":"f1","status":"read"},{"recipient_user_id":"f2","status":"weird"}]""")
        }
        val map = friendsRepo(http).fetchSentRecommendationStatus(603, MediaType.MOVIE)
        assertEquals(RecommendationStatus.READ, map["f1"])
        assertEquals(RecommendationStatus.UNKNOWN, map["f2"])
        val url = http.requests[0].url
        assertEquals("/rest/v1/recommendations", url.encodedPath)
        assertEquals("eq.603", url.queryParameter("tmdb_id"))
        assertEquals("eq.movie", url.queryParameter("type"))
        assertEquals("eq.me", url.queryParameter("sender_user_id"))
        assertEquals("recipient_user_id,status", url.queryParameter("select"))
    }

    @Test fun recommendationInboxReadAndDismissUseRecId() = runTest {
        val http = FriendsScriptedHttp().apply {
            reply(200, "[]"); reply(204, ""); reply(204, "")
        }
        val repo = friendsRepo(http)
        assertTrue(repo.fetchRecommendations().isEmpty())
        repo.markRecommendationRead("rec-1")
        repo.dismissRecommendation("rec-2")
        assertEquals("/rest/v1/rpc/list_recommendations", http.path(0))
        assertEquals("/rest/v1/rpc/mark_recommendation_read", http.path(1))
        assertEquals("rec-1", http.params(1).getString("rec_id"))
        assertEquals("/rest/v1/rpc/dismiss_recommendation", http.path(2))
        assertEquals("rec-2", http.params(2).getString("rec_id"))
    }

    @Test fun commentsListAddDeleteUseWebParamNames() = runTest {
        val http = FriendsScriptedHttp().apply {
            reply(200, """[{"id":"c1","author_id":"f","body":"hi","created_at":"t","display_name":"Fran","username":null}]""")
            reply(200, """{"id":"c2","title_id":"t1","author_id":"me","body":"yo","created_at":"t2"}""")
            reply(204, "")
        }
        val repo = friendsRepo(http)
        assertEquals("Fran", repo.fetchTitleComments("t1").single().authorName)
        assertEquals("/rest/v1/rpc/list_title_comments", http.path(0))
        assertEquals("t1", http.params(0).getString("p_title_id"))

        val added = repo.addTitleComment("t1", "  yo  ")
        assertEquals("c2", added.id)
        assertEquals("/rest/v1/rpc/add_title_comment", http.path(1))
        assertEquals(setOf("p_title_id", "p_body"), http.params(1).keys().asSequence().toSet())
        assertEquals("yo", http.params(1).getString("p_body"))

        repo.deleteTitleComment("c2")
        assertEquals("/rest/v1/rpc/delete_title_comment", http.path(2))
        assertEquals("c2", http.params(2).getString("p_comment_id"))
    }

    @Test fun blankCommentMakesNoRequestAndLongOnesAreCapped() = runTest {
        val http = FriendsScriptedHttp().apply { reply(200, """{"id":"c","author_id":"me","body":"b","created_at":"t"}""") }
        val repo = friendsRepo(http)
        try { repo.addTitleComment("t1", "   "); fail() } catch (e: IllegalArgumentException) { assertEquals("Comment can't be empty.", e.message) }
        assertTrue(http.requests.isEmpty())
        repo.addTitleComment("t1", "x".repeat(2000))
        assertEquals(1000, http.params(0).getString("p_body").length)
    }

    @Test fun commentAndReactionFailuresUseWebFixedMessages() = runTest {
        val http = FriendsScriptedHttp().apply {
            reply(403, """{"code":"42501","message":"Not authorized to comment on this title"}""")
            reply(400, """{"message":"Comment not found or not authorized to delete it"}""")
            reply(500, "x")
        }
        val repo = friendsRepo(http)
        expectFailure("Couldn't post that comment — check your connection.") { repo.addTitleComment("t", "hi") }
        expectFailure("Couldn't delete that comment — check your connection.") { repo.deleteTitleComment("c") }
        expectFailure("Couldn't save that reaction — check your connection.") { repo.setTitleReaction("t", "👍") }
    }

    @Test fun reactionsListAndSetSendEmojiOrNull() = runTest {
        val http = FriendsScriptedHttp().apply {
            reply(200, """[{"author_id":"f","emoji":"😮","display_name":null,"username":"fran"}]""")
            reply(204, ""); reply(204, "")
        }
        val repo = friendsRepo(http)
        val reactions = repo.fetchTitleReactions("t1")
        assertEquals("😮", reactions.single().emoji)
        assertEquals("fran", reactions.single().authorName)
        assertEquals("/rest/v1/rpc/list_title_reactions", http.path(0))
        assertEquals("t1", http.params(0).getString("p_title_id"))

        repo.setTitleReaction("t1", "❤️")
        repo.setTitleReaction("t1", null)
        assertEquals("/rest/v1/rpc/set_title_reaction", http.path(1))
        assertEquals("❤️", http.params(1).getString("p_emoji"))
        assertTrue(http.params(2).has("p_emoji") && http.params(2).isNull("p_emoji"))
    }

    @Test fun unsupportedReactionMakesNoRequest() = runTest {
        val http = FriendsScriptedHttp()
        try { friendsRepo(http).setTitleReaction("t", "🔥"); fail() } catch (e: IllegalArgumentException) { }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun activityFeedSendsKeysetParams() = runTest {
        val row = """{"event_type":"title_added","event_at":"2026-03-01T00:00:00Z","friend_user_id":"f","friend_display_name":"Fran","friend_username":null,
            "title_id":"t","tmdb_id":1,"type":"movie","title":"Heat","year":1995,"poster_url":null,"rating":null}"""
        val http = FriendsScriptedHttp().apply { reply(200, "[$row]"); reply(200, "[]"); reply(200, "[]") }
        val repo = friendsRepo(http)
        val first = repo.fetchFriendActivityFeed()
        assertEquals("/rest/v1/rpc/friend_activity_feed", http.path(0))
        assertTrue(http.params(0).isNull("p_before") && http.params(0).has("p_before"))
        assertEquals(30, http.params(0).getInt("p_limit"))
        assertEquals("2026-03-01T00:00:00Z", FriendsRules.nextCursor(first))
        assertFalse(FriendsRules.hasMore(first.size))

        repo.fetchFriendActivityFeed(before = FriendsRules.nextCursor(first), limit = 500)
        assertEquals("2026-03-01T00:00:00Z", http.params(1).getString("p_before"))
        assertEquals(50, http.params(1).getInt("p_limit"))

        repo.fetchFriendActivityFeed(limit = 10)
        assertEquals(10, http.params(2).getInt("p_limit"))
    }

    @Test fun signedOutFailsWithoutAnyRequest() = runTest {
        val http = FriendsScriptedHttp()
        val repo = friendsRepo(http, null)
        expectFailure("Not signed in.") { repo.findUserByEmail("a@b.com") }
        expectFailure("Not signed in.") { repo.findUserByEmail("  ") }
        expectFailure("Not signed in.") { repo.sendFriendRequest("x") }
        expectFailure("Not signed in.") { repo.acceptFriendRequest("x") }
        expectFailure("Not signed in.") { repo.declineFriendRequest("x") }
        expectFailure("Not signed in.") { repo.cancelFriendRequest("x") }
        expectFailure("Not signed in.") { repo.blockFriend("x") }
        expectFailure("Not signed in.") { repo.unblockFriend("x") }
        expectFailure("Not signed in.") { repo.listFriendships() }
        expectFailure("Not signed in.") { repo.listInviteConnections() }
        expectFailure("Not signed in.") { repo.fetchFriendTitles("x") }
        expectFailure("Not signed in.") { repo.sendRecommendation("x", RecommendationDraft(1, MediaType.MOVIE, "T", null, null)) }
        expectFailure("Not signed in.") { repo.fetchSentRecommendationStatus(1, MediaType.MOVIE) }
        expectFailure("Not signed in.") { repo.fetchRecommendations() }
        expectFailure("Not signed in.") { repo.markRecommendationRead("x") }
        expectFailure("Not signed in.") { repo.dismissRecommendation("x") }
        expectFailure("Not signed in.") { repo.fetchTitleComments("x") }
        expectFailure("Not signed in.") { repo.addTitleComment("x", "hi") }
        expectFailure("Not signed in.") { repo.deleteTitleComment("x") }
        expectFailure("Not signed in.") { repo.fetchTitleReactions("x") }
        expectFailure("Not signed in.") { repo.setTitleReaction("x", null) }
        expectFailure("Not signed in.") { repo.fetchFriendActivityFeed() }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun eachCallUsesTheSessionCurrentAtCallTime() = runTest {
        val http = FriendsScriptedHttp().apply { reply(200, "[]"); reply(200, "[]") }
        var current: SupabaseSession? = friendsSession("user-a")
        val repo = FriendsRepository(SupabaseRestClient("https://x.supabase.co", "anon", http.client)) { current }
        repo.listFriendships()
        current = friendsSession("user-b")
        repo.listFriendships()
        assertEquals("Bearer tok-user-a", http.requests[0].header("Authorization"))
        assertEquals("Bearer tok-user-b", http.requests[1].header("Authorization"))
    }
}
