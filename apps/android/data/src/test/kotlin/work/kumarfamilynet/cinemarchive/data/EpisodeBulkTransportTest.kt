package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

class EpisodeBulkTransportTest {
    private val owner = "10000000-0000-4000-8000-000000000001"
    private val title = "10000000-0000-4000-8000-000000000002"
    private val watch = "10000000-0000-4000-8000-000000000003"
    private val episode = "10000000-0000-4000-8000-000000000004"
    private val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private var active = true
    private var after: () -> Unit = {}
    private val client = SupabaseRestClient("https://example.supabase.co", "anon", OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request(); val (code, body) = replies.removeFirst(); after()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build())
    private fun transport() = EpisodeBulkTransport(client, SessionSource { if (active) SupabaseSession("token", owner) else null })
    private fun entry() = OutboxEntity("10000000-0000-4000-8000-000000000005", EPISODE_BULK, title, EPISODE_BULK_COMMAND,
        JSONObject().put("version", 1).put("operationId", "10000000-0000-4000-8000-000000000005").put("ownerId", owner).put("titleId", title).put("seasonNumber", 1)
            .put("watches", JSONArray().put(JSONObject().put("id", watch).put("episodeId", episode))).put("seasons", JSONArray()).toString(), 0)
    private fun receipt(entry: OutboxEntity) = JSONObject().put("operationId", entry.id).put("rows", JSONArray().put(JSONObject()
        .put("table", "episode_watch_events").put("key", JSONObject().put("id", watch)).put("row", JSONObject()
            .put("id", watch).put("user_id", owner).put("episode_id", episode).put("watched_at", JSONObject.NULL)
            .put("notes", JSONObject.NULL).put("color_mode", JSONObject.NULL))))
    private fun body(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()
    private fun currentEmpty() { repeat(3) { replies += 200 to "[]" } }

    @Test fun uncertainRetryReusesCommandIdentityAndAtomicOperations() = runTest {
        val entry = entry(); replies += 503 to "{}"
        assertTrue(transport().push(entry) is PushResult.Retry)
        replies += 200 to receipt(entry).toString(); currentEmpty()
        val applied = transport().push(entry) as PushResult.Applied
        assertEquals(body(0), body(1))
        assertEquals(entry.id, JSONObject(body(0)).getString("p_operation_id"))
        assertTrue(applied.receipt.isNull("title"))
        assertTrue(requests.drop(2).all { it.url.query!!.contains("user_id=eq.$owner") })
    }
    @Test fun preReceiptConflictReviewsButPostReceiptFailureCannotDiscardAcceptedIntent() = runTest {
        val entry = entry()
        for (code in listOf("40001", "23505", "P0002")) {
            replies += 409 to """{"code":"$code"}"""
            assertTrue(transport().push(entry) is PushResult.Review)
            replies += 200 to receipt(entry).toString(); replies += 409 to """{"code":"$code"}"""
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
    }
    @Test fun malformedReceiptOwnerParentOrValuesNeverAcknowledges() = runTest {
        val entry = entry()
        for (field in listOf("user_id", "episode_id", "watched_at")) {
            val bad = receipt(entry)
            bad.getJSONArray("rows").getJSONObject(0).getJSONObject("row").put(field, "wrong")
            replies += 200 to bad.toString()
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
        assertEquals(3, requests.size)
    }
    @Test fun reviewAndExpiredOwnerCannotPublishSuccess() = runTest {
        assertTrue(transport().push(entry().copy(operation = "review")) is PushResult.Retry)
        assertTrue(transport().push(entry().copy(id = watch)) is PushResult.Retry)
        assertTrue(requests.isEmpty())
        replies += 200 to receipt(entry()).toString(); after = { active = false }
        assertTrue(transport().push(entry()) is PushResult.Retry)
        assertEquals(1, requests.size)
    }
}
