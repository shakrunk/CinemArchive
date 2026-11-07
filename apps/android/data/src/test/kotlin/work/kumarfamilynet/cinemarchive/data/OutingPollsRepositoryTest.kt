package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.PollCandidate
import work.kumarfamilynet.cinemarchive.core.model.PollStatus

/** Outing polls (#203): request shapes, parsing, local checks and owner fencing against a scripted HTTP layer. */
class OutingPollsRepositoryTest {
    private val owner = "10000000-0000-4000-8000-000000000001"
    private val friend = "20000000-0000-4000-8000-000000000001"
    private val poll = "30000000-0000-4000-8000-000000000001"
    private val optionA = "40000000-0000-4000-8000-000000000001"
    private val optionB = "40000000-0000-4000-8000-000000000002"
    private val titleA = "50000000-0000-4000-8000-000000000001"
    private val titleB = "50000000-0000-4000-8000-000000000002"
    private val now = Instant.parse("2026-11-01T12:00:00Z")
    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()
    private var current = true
    private var signedIn: SupabaseSession? = SupabaseSession("token-1", owner)

    private fun respond(vararg replies: Pair<Int, String>): OutingPollsRepository {
        val queue = replies.toMutableList()
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            bodies += request.body?.let { Buffer().also { buffer -> it.writeTo(buffer) }.readUtf8() }.orEmpty()
            val (code, text) = queue.removeAt(0)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("scripted")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return OutingPollsRepository(SupabaseRestClient("https://project.example.test", "anon", http), { signedIn }, owner, { current }, { emptyList() }) { now }
    }

    private fun pollJson(status: String = "open", winner: String? = null) = """
        [{"id":"$poll","question":"Friday or Saturday?","status":"$status","is_owner":false,"owner_name":"Olive Owner",
          "created_at":"2026-10-30T10:00:00+00:00","closed_at":null,"winner_option_id":${winner?.let { "\"$it\"" } ?: "null"},
          "members":[{"user_id":"$friend","name":"Fran"}],
          "options":[
            {"id":"$optionA","position":1,"tmdb_id":1,"title":"Film A","year":2026,"poster_url":null,"showtime":"2026-11-02T19:00:00+00:00","venue":"AMC","votes":2,"mine":true,"voters":["Fran","Sam"]},
            {"id":"$optionB","position":2,"tmdb_id":2,"title":"Film B","year":null,"poster_url":null,"showtime":"2026-11-03T19:00:00+00:00","venue":null,"votes":0,"mine":false,"voters":[]}]}]"""

    private val candidates = listOf(
        PollCandidate(titleA, Instant.parse("2026-11-02T19:00:00Z"), " AMC "),
        PollCandidate(titleB, Instant.parse("2026-11-03T19:00:00Z"), null),
    )

    @Test fun listsPollsWithTheirTalliesAndVoters() = runBlocking {
        val repo = respond(200 to pollJson())
        val polls = repo.list()
        assertEquals("/rest/v1/rpc/get_outing_polls", requests.single().url.encodedPath)
        val first = polls.single()
        assertEquals(PollStatus.OPEN, first.status)
        assertEquals("Olive Owner", first.ownerName)
        assertEquals(listOf(2, 0), first.options.map { it.votes })
        assertEquals(listOf("Fran", "Sam"), first.options[0].voters)
        assertTrue(first.options[0].mine)
        assertNull(first.options[1].year)
        assertEquals(listOf("Fran"), first.members.map { it.name })
    }

    @Test fun parsesAClosedPollsWinnerAndSkipsMalformedPolls() {
        val closed = parseOutingPolls(pollJson("closed", optionB)).single()
        assertEquals(optionB, closed.winner?.id)
        assertEquals(PollStatus.CLOSED, closed.status)
        assertTrue(parseOutingPolls("""[{"id":"x"},{"id":"$poll","status":"bogus","options":[]}]""").isEmpty())
    }

    @Test fun createsAPollFromTheOwnersTitlesAndReturnsItsId() = runBlocking {
        val repo = respond(200 to """{"poll_id":"$poll"}""")
        assertEquals(poll, repo.create("  Friday or Saturday?  ", candidates, listOf(friend, friend)))
        val sent = JSONObject(bodies.single())
        assertEquals("/rest/v1/rpc/create_outing_poll", requests.single().url.encodedPath)
        assertEquals("Friday or Saturday?", sent.getString("p_question"))
        val options = sent.getJSONArray("p_options")
        assertEquals(titleA, options.getJSONObject(0).getString("title_id"))
        assertEquals("2026-11-02T19:00:00Z", options.getJSONObject(0).getString("showtime"))
        assertEquals("AMC", options.getJSONObject(0).getString("venue"))
        assertTrue(options.getJSONObject(1).isNull("venue"))
        assertEquals(listOf(friend), (0 until sent.getJSONArray("p_friend_ids").length()).map { sent.getJSONArray("p_friend_ids").getString(it) })
    }

    @Test fun refusesAPollThatBreaksARuleBeforeAnyRequest() = runBlocking {
        val repo = respond()
        val past = candidates.map { it.copy(showtime = Instant.parse("2026-10-31T19:00:00Z")) }
        for ((cand, friends) in listOf(candidates.take(1) to listOf(friend), past to listOf(friend), candidates to emptyList())) {
            try { repo.create("q", cand, friends); fail() } catch (_: IllegalArgumentException) {}
        }
        try { repo.create("q", candidates.map { it.copy(titleId = "nope") }, listOf(friend)); fail() } catch (_: IllegalArgumentException) {}
        assertTrue(requests.isEmpty())
    }

    @Test fun votesReplaceTheCallersTicksAndAnEmptyListClearsThem() = runBlocking {
        val repo = respond(200 to "", 200 to "")
        repo.vote(poll, listOf(optionA, optionB, optionA))
        val sent = JSONObject(bodies[0])
        assertEquals(2, sent.getJSONArray("p_option_ids").length())
        repo.vote(poll, emptyList())
        assertEquals(0, JSONObject(bodies[1]).getJSONArray("p_option_ids").length())
        assertEquals("/rest/v1/rpc/cast_poll_votes", requests[0].url.encodedPath)
    }

    @Test fun closesOnAWinnerOrCancelsAndMapsServerRefusals() = runBlocking {
        val repo = respond(200 to "{}", 200 to "{}", 400 to """{"code":"22023","message":"This poll is already closed"}""")
        repo.close(poll, optionB)
        assertEquals(optionB, JSONObject(bodies[0]).getString("p_option_id"))
        repo.close(poll, null)
        assertTrue(JSONObject(bodies[1]).isNull("p_option_id"))
        try { repo.close(poll, optionA); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.isNotBlank()) }
    }

    @Test fun rejectsMalformedIdsAndAnotherAccountBeforeAnyRequest() = runBlocking {
        val repo = respond()
        try { repo.vote("nope", emptyList()); fail() } catch (_: IllegalArgumentException) {}
        try { repo.vote(poll, listOf("1&x")); fail() } catch (_: IllegalArgumentException) {}
        try { repo.close(poll, "nope"); fail() } catch (_: IllegalArgumentException) {}
        signedIn = SupabaseSession("token-2", "someone-else")
        try { repo.list(); fail() } catch (_: IllegalStateException) {}
        signedIn = null
        try { repo.list(); fail() } catch (_: IllegalStateException) {}
        signedIn = SupabaseSession("token-1", owner); current = false
        try { repo.list(); fail() } catch (_: IllegalStateException) {}
        assertTrue(requests.isEmpty())
    }
}
