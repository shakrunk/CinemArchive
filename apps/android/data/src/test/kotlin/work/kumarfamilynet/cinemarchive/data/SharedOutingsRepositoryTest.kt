package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat
import work.kumarfamilynet.cinemarchive.core.model.MemberStatus
import work.kumarfamilynet.cinemarchive.core.model.SharedOutingStatus

/** Shared outings (#206): request shapes, parsing and owner fencing against a scripted HTTP layer. */
class SharedOutingsRepositoryTest {
    private val owner = "10000000-0000-4000-8000-000000000001"
    private val friend = "20000000-0000-4000-8000-000000000001"
    private val shared = "30000000-0000-4000-8000-000000000001"
    private val outing = "40000000-0000-4000-8000-000000000001"
    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()
    private var current = true
    private var signedIn: SupabaseSession? = SupabaseSession("token-1", owner)

    private fun respond(vararg replies: Pair<Int, String>): SharedOutingsRepository {
        val queue = replies.toMutableList()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            bodies += request.body?.let { Buffer().also { buffer -> it.writeTo(buffer) }.readUtf8() }.orEmpty()
            val (code, text) = queue.removeAt(0)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("scripted")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return SharedOutingsRepository(SupabaseRestClient("https://project.example.test", "anon", http), { signedIn }, owner, { current })
    }

    private fun row(myStatus: String = "invited", status: String = "scheduled", id: String = shared) = """
        {"id":"$id","status":"$status","my_status":"$myStatus","owner_id":"$friend","owner_name":"Fran Friend",
         "showtime":"2099-11-01T19:00:00+00:00","ends_at":"2099-11-01T21:00:00+00:00",
         "snapshot":{"tmdb_id":1,"type":"movie","title":"Shared film","year":2026,"poster_url":null,"venue":"AMC","format":"IMAX","companions":["Sam","Pat"]}}"""

    @Test fun listsInvitationsWithTheMinimalSnapshot() = runBlocking {
        val repo = respond(200 to "[${row()}]")
        val outing = repo.list().single()
        assertEquals("/rest/v1/rpc/get_shared_outings", requests.single().url.encodedPath)
        assertEquals("Bearer token-1", requests.single().header("Authorization"))
        assertEquals(MemberStatus.INVITED, outing.myStatus)
        assertEquals(SharedOutingStatus.SCHEDULED, outing.status)
        assertEquals("Fran Friend", outing.ownerName)
        assertEquals("Shared film", outing.title)
        assertEquals(CinemaFormat.IMAX, outing.format)
        assertEquals(listOf("Sam", "Pat"), outing.companions)
        assertEquals(null, outing.posterUrl)
    }

    @Test fun skipsRowsWithAnUnexpectedShapeOrADeclinedMembership() {
        val body = "[${row()},{\"id\":\"x\"},${row("declined", id = "50000000-0000-4000-8000-000000000001")},${row("accepted", "bogus", "60000000-0000-4000-8000-000000000001")}]"
        assertEquals(listOf(shared), parseSharedOutings(body).map { it.id })
    }

    @Test fun invitesThenReadsBackTheMembers() = runBlocking {
        val repo = respond(200 to "{}", 200 to """[{"shared_outing_id":"$shared","user_id":"$friend","name":"Fran Friend","status":"invited"}]""")
        val members = repo.invite(outing, listOf(friend))
        assertEquals("/rest/v1/rpc/invite_friends_to_outing", requests[0].url.encodedPath)
        val sent = JSONObject(bodies[0])
        assertEquals(outing, sent.getString("p_outing_id"))
        assertEquals(friend, sent.getJSONArray("p_friend_ids").getString(0))
        assertEquals("/rest/v1/rpc/get_outing_members", requests[1].url.encodedPath)
        assertEquals(listOf(Triple(friend, "Fran Friend", MemberStatus.INVITED)), members.map { Triple(it.userId, it.name, it.status) })
        assertEquals(shared, members.single().sharedOutingId)
    }

    @Test fun respondsWithTheAnswerAndMapsServerRefusalsToAReadableMessage() = runBlocking {
        val repo = respond(200 to """{"status":"accepted"}""", 400 to """{"code":"22023","message":"This outing is no longer open"}""")
        repo.respond(shared, true)
        val sent = JSONObject(bodies[0])
        assertEquals(shared, sent.getString("p_shared_outing_id"))
        assertTrue(sent.getBoolean("p_accept"))
        try { repo.respond(shared, false); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun removesAMemberAsTheOwner() = runBlocking {
        val repo = respond(204 to "")
        repo.removeMember(shared, friend)
        assertEquals("/rest/v1/rpc/remove_outing_member", requests.single().url.encodedPath)
    }

    @Test fun rejectsEmptyInvitesAndMalformedIdsBeforeAnyRequest() = runBlocking {
        val repo = respond()
        try { repo.invite(outing, emptyList()); fail() } catch (_: IllegalArgumentException) {}
        try { repo.invite("1&x", listOf(friend)); fail() } catch (_: IllegalArgumentException) {}
        try { repo.invite(outing, listOf("not-a-uuid")); fail() } catch (_: IllegalArgumentException) {}
        try { repo.respond("nope", true); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(requests.isEmpty())
    }

    @Test fun refusesAnotherAccountOrAnEndedSignInBeforeAnyRequest() = runBlocking {
        val repo = respond()
        signedIn = SupabaseSession("token-2", "someone-else")
        try { repo.list(); fail() } catch (_: IllegalStateException) {}
        signedIn = null
        try { repo.list(); fail() } catch (_: IllegalStateException) {}
        signedIn = SupabaseSession("token-1", owner); current = false
        try { repo.list(); fail() } catch (_: IllegalStateException) {}
        assertTrue(requests.isEmpty())
    }

    private fun reaction(me: Boolean, name: String, rating: String = "4.5", note: String = "\"Loved it\"") =
        """{"user_id":"$friend","is_me":$me,"name":"$name","rating":$rating,"note":$note,"updated_at":"2026-11-01T22:00:00+00:00"}"""

    @Test fun readsEveryonesReactionsAndMarksTheCallersOwn() = runBlocking {
        val repo = respond(200 to "[${reaction(false, "Fran Friend")},${reaction(true, "You", "null", "\"Just words\"")}]")
        val list = repo.reactions(shared)
        assertEquals("/rest/v1/rpc/get_outing_reactions", requests.single().url.encodedPath)
        assertEquals(shared, JSONObject(bodies.single()).getString("p_shared_outing_id"))
        assertEquals(listOf(Triple("Fran Friend", false, 4.5), Triple("You", true, null)), list.map { Triple(it.name, it.isMe, it.rating) })
        assertEquals("Just words", list[1].note)
    }

    @Test fun parsingSkipsReactionsWithNothingToShowAndDropsAnInvalidRatingButKeepsTheNote() {
        val body = "[${reaction(false, "ok")},${reaction(false, "bad", "2.3")},${reaction(false, "empty", "null", "null")},{\"user_id\":\"x\"}]"
        val parsed = parseOutingReactions(body)
        assertEquals(listOf("ok", "bad"), parsed.map { it.name })
        assertEquals(null, parsed[1].rating)
        assertEquals("Loved it", parsed[1].note)
    }

    @Test fun sharesTheTrimmedNoteAndRatingAndRefusesAnEmptyOne() = runBlocking {
        val repo = respond(200 to "{}", 200 to "{}")
        repo.shareReaction(shared, 3.5, "  fun  ")
        val sent = JSONObject(bodies[0])
        assertEquals("/rest/v1/rpc/share_outing_reaction", requests[0].url.encodedPath)
        assertEquals(3.5, sent.getDouble("p_rating"), 0.0)
        assertEquals("fun", sent.getString("p_note"))
        repo.shareReaction(shared, null, "words only")
        assertTrue(JSONObject(bodies[1]).isNull("p_rating"))
        for ((rating, note) in listOf<Pair<Double?, String?>>(null to null, null to "  ", 2.3 to null, null to "x".repeat(501))) {
            try { repo.shareReaction(shared, rating, note); fail() } catch (_: IllegalArgumentException) {}
        }
        assertEquals(2, requests.size)
    }

    @Test fun mapsAClosedOutingToAReadableMessageAndWithdraws() = runBlocking {
        val repo = respond(400 to """{"code":"22023","message":"Reactions open once the show has ended"}""", 204 to "")
        try { repo.shareReaction(shared, 4.0, null); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.isNotBlank()) }
        repo.withdrawReaction(shared)
        assertEquals("/rest/v1/rpc/withdraw_outing_reaction", requests[1].url.encodedPath)
    }

    @Test fun theOwnersActionsResolveThroughTheirMembersOrAreAbsent() = runBlocking {
        val repo = respond(
            200 to """[{"shared_outing_id":"$shared","user_id":"$friend","name":"Fran","status":"accepted"}]""",
            200 to "[${reaction(false, "Fran Friend")}]",
            200 to "[]",
        )
        val actions = repo.reactionActionsForOuting(outing)!!
        assertEquals(listOf("Fran Friend"), actions.load().map { it.name })
        assertEquals(null, repo.reactionActionsForOuting(outing))
    }
}
