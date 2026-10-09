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

class TitleMetadataTransportTest {
    private val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private var active = true
    private var afterResponse: () -> Unit = {}
    private val client = SupabaseRestClient("https://example.supabase.co", "anon", OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        val (code, body) = replies.removeFirst()
        afterResponse()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build())
    private fun transport() = TitleMetadataTransport(client, SessionSource { if (active) SupabaseSession("token", TitleMetadataFixture.owner) else null })
    private fun body(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()

    @Test fun lostResponseRetriesIdenticalCommandThenRefreshesCurrentOwnedProjection() = runTest {
        val entry = TitleMetadataFixture.entry()
        replies += 503 to """{"message":"Unknown outcome"}"""
        assertTrue(transport().push(entry) is PushResult.Retry)
        replies += 200 to TitleMetadataFixture.receipt(entry).toString()
        replies += 200 to JSONArray().put(TitleMetadataFixture.row().put("title", "Changed remotely")).toString()
        val result = transport().push(entry) as PushResult.Applied
        assertEquals(body(0), body(1)); assertEquals(entry.id, JSONObject(body(0)).getString("p_operation_id"))
        assertEquals("Changed remotely", result.receipt.getJSONObject("current").getString("title"))
        assertTrue(requests.last().url.query!!.contains("user_id=eq.${TitleMetadataFixture.owner}"))
    }

    @Test fun definitePreEffectConflictIsReviewButPostReceiptReadFailuresRemainRetry() = runTest {
        val entry = TitleMetadataFixture.entry()
        for (code in listOf("40001", "23505", "P0002")) {
            replies += 409 to """{"code":"$code","message":"Conflict"}"""
            assertTrue(transport().push(entry) is PushResult.Review)
            replies += 200 to TitleMetadataFixture.receipt(entry).toString()
            replies += 409 to """{"code":"$code","message":"Read failed"}"""
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
    }

    @Test fun wrongReceiptIdentityOwnerOrSavedFieldsCannotAcknowledge() = runTest {
        val entry = TitleMetadataFixture.entry()
        val receipts = listOf(
            TitleMetadataFixture.receipt(entry).put("operationId", "other"),
            TitleMetadataFixture.receipt(entry).apply { getJSONArray("rows").getJSONObject(0).getJSONObject("row").put("user_id", "other-owner") },
            TitleMetadataFixture.receipt(entry).apply { getJSONArray("rows").getJSONObject(0).getJSONObject("row").put("tags", JSONArray(listOf("Wrong"))) },
        )
        for (receipt in receipts) {
            replies += 200 to receipt.toString()
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
        assertEquals(3, requests.size)
    }

    @Test fun exactOriginalEffectRevisionRemainsCausalGuardAfterNewerCurrentRow() = runTest {
        val first = TitleMetadataFixture.entry()
        val second = first.copy(id = "30000000-0000-4000-8000-000000000002", payloadJson = titleMetadataPayload(
            TitleMetadataFixture.owner, TitleMetadataFixture.title, TitleMetadataFixture.patch("Later"), null, first.id).toString())
        replies += 200 to TitleMetadataFixture.receipt(second).toString()
        replies += 200 to JSONArray().put(TitleMetadataFixture.row().put("updated_at", "2026-10-08T09:00:00Z")).toString()
        assertTrue(transport().push(second) is PushResult.Applied)
        val operation = JSONObject(body(0)).getJSONArray("p_operations").getJSONObject(0)
        assertEquals(first.id, operation.getString("expectedOperationId")); assertFalse(operation.has("expectedUpdatedAt"))
    }

    @Test fun legacyReviewDraftMakesNoRequestAndNeverGuessesBaseline() = runTest {
        val draft = TitleMetadataFixture.entry().copy(operation = "review", payloadJson = titleMetadataPayload(
            TitleMetadataFixture.owner, TitleMetadataFixture.title, TitleMetadataFixture.patch("Keep"), null, null).toString())
        assertTrue(transport().push(draft) is PushResult.Retry)
        assertTrue(requests.isEmpty())
    }

    @Test fun endedAccountAndDeletedCurrentRowCannotBeMistakenForFreshProjection() = runTest {
        val entry = TitleMetadataFixture.entry()
        replies += 200 to TitleMetadataFixture.receipt(entry).toString()
        afterResponse = { active = false }
        assertTrue(transport().push(entry) is PushResult.Retry)
        assertEquals(1, requests.size)
        active = true; afterResponse = {}
        replies += 200 to TitleMetadataFixture.receipt(entry).toString()
        replies += 200 to "[]"
        val result = transport().push(entry) as PushResult.Applied
        assertTrue(result.receipt.isNull("current"))
    }
}
