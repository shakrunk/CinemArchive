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
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

internal object ViewingCommandFixture {
    const val owner = "11111111-1111-4111-8111-111111111111"
    const val title = "22222222-2222-4222-8222-222222222222"
    const val viewing = "33333333-3333-4333-8333-333333333333"
    const val operation = "44444444-4444-4444-8444-444444444444"
    const val nextOperation = "55555555-5555-4555-8555-555555555555"
    const val baseline = "2026-10-08T10:00:00.123456Z"
    fun fields() = JSONObject().put("date", "2026-10-01").put("rating", JSONObject.NULL)
        .put("notes", JSONObject.NULL).put("venue", "Cinema").put("companions", JSONArray())
    fun entry(action: String = "update", fields: JSONObject = JSONObject().put("notes", "Saved note"),
        predecessor: String? = null, id: String = operation) = OutboxEntity(id, "viewing", viewing, VIEWING_COMMAND,
        viewingCommandPayload(viewing, title, action, fields, baseline.takeIf { action != "insert" && predecessor == null }, predecessor).toString(), 1)
    fun row() = JSONObject().put("id", viewing).put("user_id", owner).put("title_id", title)
        .put("viewed_at", "2026-10-01").put("rating", JSONObject.NULL).put("notes", JSONObject.NULL)
        .put("venue", "Cinema").put("companions", JSONArray()).put("outing_id", JSONObject.NULL).put("updated_at", baseline)
    fun applied(entry: OutboxEntity) = row().apply {
        viewingCommandOperations(entry).getJSONObject(0).optJSONObject("values")?.let { values -> values.keys().forEach { put(it, values.get(it)) } }
    }
    fun receipt(entry: OutboxEntity, row: JSONObject = applied(entry)): JSONObject {
        val result = JSONObject().put("table", "viewings").put("key", JSONObject().put("id", viewing))
        if (viewingCommandOperations(entry).getJSONObject(0).getString("action") == "delete") result.put("deleted", true)
        else result.put("row", row)
        return JSONObject().put("operationId", entry.id).put("rows", JSONArray().put(result))
    }
    fun envelope(entry: OutboxEntity, current: JSONObject? = applied(entry)) = JSONObject()
        .put("receipt", receipt(entry)).put("current", current ?: JSONObject.NULL)
}

class ViewingCommandTransportTest {
    private val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private var afterResponse: () -> Unit = {}
    private var session: SupabaseSession? = SupabaseSession("owner-token", ViewingCommandFixture.owner)
    private val client = SupabaseRestClient("https://example.supabase.co", "anon", OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests += chain.request()
        val (code, body) = replies.removeFirst()
        afterResponse()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build())
    private fun transport() = ViewingCommandTransport(client) { session }
    private fun body(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()
    private fun accepted(entry: OutboxEntity, current: JSONObject? = ViewingCommandFixture.applied(entry)) {
        replies += 200 to ViewingCommandFixture.receipt(entry).toString()
        replies += 200 to JSONArray().apply { current?.let(::put) }.toString()
    }

    @Test fun unknownOutcomeRetriesExactCommandAndUsesFreshHistory() = runTest {
        val entry = ViewingCommandFixture.entry()
        replies += 503 to """{"message":"Unknown outcome"}"""
        assertTrue(transport().push(entry) is PushResult.Retry)
        accepted(entry, ViewingCommandFixture.applied(entry).put("notes", "Newer note").put("rating", 4.5))
        val result = transport().push(entry) as PushResult.Applied
        assertEquals(body(0), body(1))
        assertEquals(entry.id, JSONObject(body(0)).getString("p_operation_id"))
        assertEquals("/rest/v1/rpc/apply_library_command", requests.first().url.encodedPath)
        assertEquals("eq.${ViewingCommandFixture.viewing}", requests.last().url.queryParameter("id"))
        assertEquals("eq.${ViewingCommandFixture.owner}", requests.last().url.queryParameter("user_id"))
        assertEquals("Newer note", result.receipt.getJSONObject("current").getString("notes"))
        assertTrue(requests.all { it.header("Authorization") == "Bearer owner-token" })
    }

    @Test fun deletedCurrentEventStaysAbsentAfterHistoricalReceipt() = runTest {
        val entry = ViewingCommandFixture.entry(); accepted(entry, null)
        assertTrue((transport().push(entry) as PushResult.Applied).receipt.isNull("current"))
    }

    @Test fun definiteConflictRequiresReviewButRefreshFailureRetainsSameCommand() = runTest {
        val entry = ViewingCommandFixture.entry()
        for (code in listOf("40001", "P0002", "23505")) {
            replies += 409 to JSONObject().put("code", code).put("message", "Conflict").toString()
            assertTrue(transport().push(entry) is PushResult.Review)
            replies += 200 to ViewingCommandFixture.receipt(entry).toString()
            replies += 409 to JSONObject().put("code", code).put("message", "Refresh failed").toString()
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
    }

    @Test fun wrongReceiptIdentityOwnerTitleOrValuesNeverAcknowledges() = runTest {
        val entry = ViewingCommandFixture.entry()
        val repliesToCheck = listOf(
            ViewingCommandFixture.receipt(entry).put("operationId", ViewingCommandFixture.nextOperation),
            ViewingCommandFixture.receipt(entry, ViewingCommandFixture.applied(entry).put("user_id", ViewingCommandFixture.title)),
            ViewingCommandFixture.receipt(entry, ViewingCommandFixture.applied(entry).put("title_id", ViewingCommandFixture.owner)),
            ViewingCommandFixture.receipt(entry, ViewingCommandFixture.applied(entry).put("notes", "Other note")),
            ViewingCommandFixture.receipt(entry).put("rows", JSONArray()),
        )
        for (reply in repliesToCheck) { replies += 200 to reply.toString(); assertTrue(transport().push(entry) is PushResult.Retry) }
        assertTrue(requests.all { it.method == "POST" })
    }

    @Test fun differentOwnerOrTitleInFreshReadDoesNotLeakIntoProjection() = runTest {
        val entry = ViewingCommandFixture.entry()
        for (field in listOf("user_id", "title_id", "id")) {
            accepted(entry, ViewingCommandFixture.applied(entry).put(field, ViewingCommandFixture.nextOperation))
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
    }

    @Test fun accountChangeAfterEitherNetworkBoundaryCannotPublish() = runTest {
        val entry = ViewingCommandFixture.entry()
        replies += 200 to ViewingCommandFixture.receipt(entry).toString()
        afterResponse = { session = null }
        assertTrue(transport().push(entry) is PushResult.Retry)
        assertEquals(1, requests.size)
        session = SupabaseSession("owner-token", ViewingCommandFixture.owner)
        accepted(entry)
        afterResponse = { if (requests.last().method == "GET") session = null }
        assertTrue(transport().push(entry) is PushResult.Retry)
        assertEquals(3, requests.size)
    }

    @Test fun explicitNullPatchChangesOnlySelectedFields() = runTest {
        val entry = ViewingCommandFixture.entry(fields = JSONObject().put("rating", JSONObject.NULL).put("notes", JSONObject.NULL))
        accepted(entry); assertTrue(transport().push(entry) is PushResult.Applied)
        val values = JSONObject(body(0)).getJSONArray("p_operations").getJSONObject(0).getJSONObject("values")
        assertEquals(setOf("rating", "notes"), values.keys().asSequence().toSet())
        assertTrue(values.isNull("rating") && values.isNull("notes"))
    }

    @Test fun exactDeleteUsesOriginalCausalDependencyAndCannotUpsert() = runTest {
        val entry = ViewingCommandFixture.entry("delete", JSONObject(), predecessor = ViewingCommandFixture.nextOperation)
        accepted(entry, null)
        assertTrue(transport().push(entry) is PushResult.Applied)
        val operation = JSONObject(body(0)).getJSONArray("p_operations").getJSONObject(0)
        assertEquals("delete", operation.getString("action"))
        assertEquals(ViewingCommandFixture.nextOperation, operation.getString("expectedOperationId"))
        assertFalse(operation.has("values") || operation.has("expectedUpdatedAt"))
    }

    @Test fun missingDeletionConfirmationIsNotAnAcknowledgment() = runTest {
        val entry = ViewingCommandFixture.entry("delete", JSONObject())
        val receipt = ViewingCommandFixture.receipt(entry)
        receipt.getJSONArray("rows").getJSONObject(0).put("deleted", false)
        replies += 200 to receipt.toString()
        assertTrue(transport().push(entry) is PushResult.Retry)
        assertEquals(1, requests.size)
    }

    @Test fun manualCreateRetainsAllFieldsAndCannotClaimAnOutingCompletion() = runTest {
        val entry = ViewingCommandFixture.entry("insert", ViewingCommandFixture.fields())
        accepted(entry); assertTrue(transport().push(entry) is PushResult.Applied)
        val operation = JSONObject(body(0)).getJSONArray("p_operations").getJSONObject(0)
        assertFalse(operation.has("expectedUpdatedAt") || operation.has("expectedOperationId"))
        assertTrue(operation.getJSONObject("values").isNull("outing_id"))
        assertTrue(runCatching { ViewingCommandFixture.entry("insert", JSONObject().put("date", "2026-10-01")) }.isFailure)
        assertTrue(runCatching { ViewingCommandFixture.entry("insert", ViewingCommandFixture.fields().put("outingId", ViewingCommandFixture.operation)) }.isFailure)
    }

    @Test fun existingCreateWithDifferentHistoryRequiresExplicitReview() = runTest {
        val entry = ViewingCommandFixture.entry("insert", ViewingCommandFixture.fields())
        replies += 200 to ViewingCommandFixture.receipt(entry, ViewingCommandFixture.applied(entry).put("notes", "Existing history")).toString()
        assertTrue(transport().push(entry) is PushResult.Review)
        assertEquals(1, requests.size)
    }

    @Test fun tamperedPersistedValuesNeverProduceNewHttpIntent() = runTest {
        val original = ViewingCommandFixture.entry()
        val payload = JSONObject(original.payloadJson)
        payload.getJSONObject(VIEWING_COMMAND_DATA).getJSONArray("operations").getJSONObject(0).getJSONObject("values").put("notes", "Different")
        assertTrue(transport().push(original.copy(payloadJson = payload.toString())) is PushResult.Retry)
        assertTrue(requests.isEmpty())
    }
}
