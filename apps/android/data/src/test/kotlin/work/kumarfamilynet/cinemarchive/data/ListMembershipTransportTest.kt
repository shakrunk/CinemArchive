package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ListMembershipTransportTest {
    private val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private var afterResponse: () -> Unit = {}
    private var session: SupabaseSession? = SupabaseSession("owner-token", MembershipFixture.owner)
    private val client = SupabaseRestClient("https://example.supabase.co", "anon", OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests += chain.request()
        val (code, body) = replies.removeFirst()
        afterResponse()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build())
    private fun transport() = ListMembershipTransport(client) { session }
    private fun body(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()

    @Test fun olderMembershipsNeverDispatchSurrogateWritesBeforeExplicitReview() = runTest {
        val writer = SupabaseRemoteMutationWriter(client) { requireNotNull(session) }
        for (operation in listOf("upsert", "delete", "review")) {
            val entry = MembershipFixture.command().copy(operation = operation)
            val result = writer.push(entry)
            assertTrue(result is PushResult.Retry)
            assertTrue((result as PushResult.Retry).reason.contains("Saved list changes"))
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun uncertainAddRetriesExactCommandThenReadsCanonicalNaturalIdentity() = runTest {
        val entry = MembershipFixture.command()
        replies += 503 to "{\"message\":\"Unknown delivery\"}"
        assertTrue(transport().push(entry) is PushResult.Retry)
        replies += 200 to MembershipFixture.receipt(entry).toString()
        replies += 200 to JSONArray().put(MembershipFixture.row()).toString()
        val applied = transport().push(entry) as PushResult.Applied
        assertEquals(body(0), body(1))
        assertEquals(entry.id, JSONObject(body(0)).getString("p_operation_id"))
        val key = JSONObject(body(0)).getJSONArray("p_operations").getJSONObject(0).getJSONObject("key")
        assertEquals(setOf("list_id", "title_id"), key.keys().asSequence().toSet())
        assertEquals("eq.${MembershipFixture.owner}", requests[2].url.queryParameter("user_id"))
        assertEquals("eq.${MembershipFixture.list}", requests[2].url.queryParameter("list_id"))
        assertEquals(MembershipFixture.canonical, applied.receipt.getJSONObject("current").getString("id"))
    }

    @Test fun oldInsertReceiptWithMissingCurrentMembershipAcknowledgesWithoutResurrection() = runTest {
        val entry = MembershipFixture.command()
        replies += 200 to MembershipFixture.receipt(entry).toString(); replies += 200 to "[]"
        val applied = transport().push(entry) as PushResult.Applied
        assertTrue(applied.receipt.isNull("current"))
    }

    @Test fun ownerChangeCannotPublishCanonicalMembership() = runTest {
        val entry = MembershipFixture.command()
        replies += 200 to MembershipFixture.receipt(entry).toString()
        afterResponse = { session = null }
        assertTrue(transport().push(entry) is PushResult.Retry)
        assertEquals(1, requests.size)
    }

    @Test fun failedCurrentReadKeepsAcceptedRequestRetryable() = runTest {
        val entry = MembershipFixture.command(false)
        replies += 200 to MembershipFixture.receipt(entry).toString()
        replies += 409 to "{\"code\":\"40001\",\"message\":\"Refresh failed\"}"
        assertTrue(transport().push(entry) is PushResult.Retry)
    }

    @Test fun malformedOwnerOrNaturalKeyCannotBeAcknowledged() = runTest {
        val entry = MembershipFixture.command()
        for (row in listOf(MembershipFixture.row().put("user_id", "other"), MembershipFixture.row().put("title_id", "other"))) {
            replies += 200 to MembershipFixture.receipt(entry).toString()
            replies += 200 to JSONArray().put(row).toString()
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
    }
}
