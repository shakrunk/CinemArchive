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

class OutingCommandTransportTest {
    private val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private var afterResponse: () -> Unit = {}
    private var session: SupabaseSession? = SupabaseSession("owner-token", OutingCommandFixture.owner)
    private val client = SupabaseRestClient("https://example.supabase.co", "anon", OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests += chain.request()
        val (code, body) = replies.removeFirst()
        afterResponse()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build())
    private fun transport() = OutingCommandTransport(client) { session }
    private fun body(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()

    @Test fun unknownDeliveryReusesExactIdAndPayloadThenFetchesCurrentStateAfterHistoricReceipt() = runTest {
        val entry = OutingCommandFixture.patch()
        replies += 503 to """{"message":"Unknown delivery"}"""
        assertTrue(transport().push(entry) is PushResult.Retry)
        replies += 200 to OutingCommandFixture.receipt(entry).toString()
        replies += 200 to JSONArray().put(OutingCommandFixture.applied(entry).put("venue", "Changed later").put("notes", "New remote note")).toString()
        val result = transport().push(entry) as PushResult.Applied
        assertEquals(body(0), body(1))
        assertEquals(entry.id, JSONObject(body(0)).getString("p_operation_id"))
        assertEquals("/rest/v1/rpc/apply_library_command", requests[0].url.encodedPath)
        assertEquals("GET", requests[2].method)
        assertEquals("eq.${OutingCommandFixture.owner}", requests[2].url.queryParameter("user_id"))
        assertEquals("Changed later", result.receipt.getJSONObject("current").getString("venue"))
        assertTrue(requests.all { it.header("Authorization") == "Bearer owner-token" })
    }

    @Test fun receiptRetryKeepsDeletedCurrentOutingAbsent() = runTest {
        val entry = OutingCommandFixture.patch()
        replies += 200 to OutingCommandFixture.receipt(entry).toString(); replies += 200 to "[]"
        val result = transport().push(entry) as PushResult.Applied
        assertTrue(result.receipt.has("current") && result.receipt.isNull("current"))
    }

    @Test fun accountChangeAfterRpcCannotFetchOrPublishOldOwnerProjection() = runTest {
        val entry = OutingCommandFixture.patch()
        replies += 200 to OutingCommandFixture.receipt(entry).toString()
        afterResponse = { session = null }
        assertTrue(transport().push(entry) is PushResult.Retry)
        assertEquals(1, requests.size)
    }

    @Test fun wrongReceiptIdentityOwnerOrFieldsKeepsCommandUnacknowledged() = runTest {
        val entry = OutingCommandFixture.patch()
        val invalid = listOf(
            OutingCommandFixture.receipt(entry).put("operationId", "wrong"),
            OutingCommandFixture.receipt(entry, OutingCommandFixture.applied(entry).put("user_id", "other")),
            OutingCommandFixture.receipt(entry, OutingCommandFixture.applied(entry).put("venue", "Wrong intent")),
            OutingCommandFixture.receipt(entry).put("rows", JSONArray()),
        )
        invalid.forEach { reply ->
            replies += 200 to reply.toString()
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
        assertTrue(requests.all { it.method == "POST" })
    }

    @Test fun definiteCasFailuresRequireReviewButFailedRefreshAfterReceiptStaysUnknown() = runTest {
        val entry = OutingCommandFixture.patch()
        listOf("40001", "P0002", "23505").forEach { code ->
            replies += 409 to JSONObject().put("code", code).put("message", "Conflict").toString()
            assertTrue(transport().push(entry) is PushResult.Review)
        }
        replies += 200 to OutingCommandFixture.receipt(entry).toString()
        replies += 409 to """{"code":"40001","message":"Refresh failed"}"""
        assertTrue(transport().push(entry) is PushResult.Retry)
    }

    @Test fun existingCreateIdentityWithDifferentValuesIsReviewableRatherThanUnknownOrAcknowledged() = runTest {
        val entry = OutingCommandFixture.create()
        replies += 200 to OutingCommandFixture.receipt(entry, OutingCommandFixture.applied(entry).put("venue", "Other plan")).toString()
        assertTrue(transport().push(entry) is PushResult.Review)
        assertEquals(1, requests.size)
    }

    @Test fun legitimateCreateReceiptAcceptsPostgresMicrosecondRepresentationAndExplicitNulls() = runTest {
        val original = OutingCommandFixture.create()
        val payload = JSONObject(original.payloadJson).put("createdAt", "2026-10-08T12:00:00.123456789Z")
        payload.remove(OUTING_COMMAND_DATA)
        val entry = original.copy(payloadJson = outingCommandPayload(payload, true, null, null).toString())
        val row = OutingCommandFixture.applied(entry).put("created_at", "2026-10-08T12:00:00.123457+00:00")
        replies += 200 to OutingCommandFixture.receipt(entry, row).toString()
        replies += 200 to JSONArray().put(row).toString()
        assertTrue(transport().push(entry) is PushResult.Applied)
    }

    @Test fun unlinkUsesExplicitNullAndCausalRpcWithoutReplacingOtherOutingFields() = runTest {
        val payload = JSONObject().put("id", OutingCommandFixture.outing).put("completedViewingId", JSONObject.NULL)
            .put("followUpDismissedAt", "2026-10-08T12:00:00Z").put("updatedAt", "2026-10-08T13:00:00Z")
        val entry = OutingCommandFixture.patch().copy(payloadJson = outingCommandPayload(payload, false, OutingCommandFixture.baseline, null).toString())
        replies += 200 to OutingCommandFixture.receipt(entry).toString()
        replies += 200 to JSONArray().put(OutingCommandFixture.applied(entry)).toString()
        assertTrue(transport().push(entry) is PushResult.Applied)
        val values = JSONObject(body(0)).getJSONArray("p_operations").getJSONObject(0).getJSONObject("values")
        assertEquals(setOf("completed_viewing_id", "follow_up_dismissed_at"), values.keys().asSequence().toSet())
        assertTrue(values.isNull("completed_viewing_id"))
        assertEquals("POST", requests.first().method)
    }

    @Test fun changedPersistedEnvelopeCannotRegenerateDifferentIntentOnRetry() = runTest {
        val entry = OutingCommandFixture.patch()
        val payload = JSONObject(entry.payloadJson)
        payload.getJSONObject(OUTING_COMMAND_DATA).getJSONArray("operations").getJSONObject(0)
            .getJSONObject("values").put("venue", "Tampered")
        assertTrue(transport().push(entry.copy(payloadJson = payload.toString())) is PushResult.Retry)
        assertTrue(requests.isEmpty())
    }
}
