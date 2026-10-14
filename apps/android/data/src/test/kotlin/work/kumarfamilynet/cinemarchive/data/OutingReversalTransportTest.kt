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

class OutingReversalTransportTest {
    private val owner = OutingCommandFixture.owner
    private val viewing = "50000000-0000-4000-8000-000000000002"
    private val command = OutingReversalCommand(OutingCommandFixture.outing, OutingCommandFixture.title, viewing,
        ViewingGuard(revision = OutingCommandFixture.baseline), ViewingGuard(revision = OutingCommandFixture.baseline))
    private val entry = OutboxEntity(OutingCommandFixture.operation, "outing_reversal", command.outingId, OUTING_REVERSAL,
        JSONObject().put("ownerId", owner).put("titleId", command.titleId).put("reversalCommand", command.persisted()).toString(), 1)
    private val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private var session: SupabaseSession? = SupabaseSession("token", owner)
    private var afterResponse: () -> Unit = {}
    private val client = SupabaseRestClient("https://example.supabase.co", "anon", OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        val (code, body) = replies.removeFirst()
        afterResponse()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build())
    private fun result() = JSONObject().put("status", "applied").put("operationId", entry.id).put("outingId", command.outingId)
        .put("canonicalViewingId", viewing).put("request", command.request()).put("titleStatusRestored", false)
        .put("rows", JSONArray().put(JSONObject().put("table", "cinema_outings").put("key", JSONObject().put("id", command.outingId))
            .put("row", OutingCommandFixture.row().put("status", "missed").put("completed_viewing_id", JSONObject.NULL)))
            .put(JSONObject().put("table", "titles").put("key", JSONObject().put("id", command.titleId))
                .put("row", TitleMetadataFixture.row().put("id", command.titleId).put("user_id", owner))))
    private fun transport() = OutingReversalTransport(client) { session }
    private fun body(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()

    @Test fun unknownOutcomeAndAcceptedReadFailureRetainExactOperationRatherThanRebase() = runTest {
        replies += 503 to "{}"
        assertTrue(transport().push(entry) is PushResult.Retry)
        replies += 200 to result().toString(); replies += 409 to """{"code":"40001","message":"read failed"}"""
        assertTrue(transport().push(entry) is PushResult.Retry)
        assertEquals(body(0), body(1))
        assertEquals(entry.id, JSONObject(body(0)).getString("p_operation_id"))
    }

    @Test fun definitiveConflictRequiresReviewAndChangedReceiptCannotAcknowledge() = runTest {
        replies += 409 to """{"code":"40001","message":"newer rating"}"""
        assertTrue(transport().push(entry) is PushResult.Review)
        replies += 200 to result().put("operationId", viewing).toString()
        assertTrue(transport().push(entry) is PushResult.Retry)
        assertEquals(2, requests.size)
    }

    @Test fun deletedCurrentRowsRemainAbsentEvenWhenImmutableReceiptHadRows() = runTest {
        replies += 200 to result().toString()
        repeat(3) { replies += 200 to "[]" }
        val applied = transport().push(entry) as PushResult.Applied
        assertTrue(applied.receipt.getJSONObject("currentOutings").isNull(command.outingId))
        assertTrue(applied.receipt.isNull("currentViewing"))
        assertTrue(applied.receipt.isNull("currentTitle"))
        assertEquals("eq.$owner", requests[2].url.queryParameter("user_id"))
        assertEquals("eq.$viewing", requests[2].url.queryParameter("id"))
    }

    @Test fun currentTitleOpaquePhysicalNumberSurvivesExactReader() = runTest {
        replies += 200 to result().toString(); replies += 200 to "[]"; replies += 200 to "[]"
        val title = TitleMetadataFixture.row().put("id", command.titleId).put("user_id", owner)
            .put("physical_media", JSONArray().put(JSONObject().put("id", "copy").put("format", "other").put("opaque", "NUMBER")))
        replies += 200 to JSONArray().put(title).toString().replace("\"NUMBER\"", "9007199254740993")
        val applied = transport().push(entry) as PushResult.Applied
        assertTrue(metadataJson(applied.receipt.getJSONObject("currentTitle")).contains("9007199254740993"))
    }

    @Test fun ownerLossAfterAcceptedRpcPublishesNoProjection() = runTest {
        replies += 200 to result().toString(); afterResponse = { session = null }
        assertTrue(transport().push(entry) is PushResult.Retry)
        assertEquals(1, requests.size)
    }
}
