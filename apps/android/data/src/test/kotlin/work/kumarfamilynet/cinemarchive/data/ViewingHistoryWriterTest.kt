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

class ViewingHistoryWriterTest {
    private val requests = mutableListOf<Request>()
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body("[{}]".toResponseBody("application/json".toMediaType())).build()
    }).build()
    private val writer = SupabaseRemoteMutationWriter(SupabaseRestClient("https://x.supabase.co", "anon", http)) { SupabaseSession("token", "owner") }
    private fun entry(op: String, payload: JSONObject, type: String = "viewing") = OutboxEntity("command", type, "watch", op, payload.toString(), 0L)
    private fun body() = JSONObject(Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8())

    @Test fun editPreservesExplicitNullsAndSendsOnlySuppliedFields() = runTest {
        val payload = JSONObject().put("id", "watch")
        listOf("date", "rating", "notes", "venue").forEach { payload.put(it, JSONObject.NULL) }
        assertEquals(PushResult.Success, writer.push(entry("update", payload)))
        assertEquals("PATCH", requests.single().method)
        assertEquals("eq.owner", requests.single().url.queryParameter("user_id"))
        val sent = body()
        listOf("viewed_at", "rating", "notes", "venue").forEach { assertTrue(sent.has(it)); assertTrue(sent.isNull(it)) }
        assertFalse(sent.has("companions"))
        assertFalse(sent.has("outing_id"))
    }

    @Test fun createIncludesDateRatingNotesVenueAndCompanionObjects() = runTest {
        val payload = JSONObject().put("id", "watch").put("titleId", "title").put("date", JSONObject.NULL)
            .put("rating", 4.5).put("notes", "Lovely").put("venue", "Cinema")
            .put("companions", JSONArray().put(JSONObject().put("name", "Sam")))
        assertEquals(PushResult.Success, writer.push(entry("upsert", payload)))
        val sent = body()
        assertEquals("POST", requests.single().method)
        assertTrue(sent.has("viewed_at") && sent.isNull("viewed_at"))
        assertEquals(4.5, sent.getDouble("rating"), 0.0)
        assertEquals("Lovely", sent.getString("notes"))
        assertEquals("Cinema", sent.getString("venue"))
        assertEquals("Sam", sent.getJSONArray("companions").getJSONObject(0).getString("name"))
    }

    @Test fun deleteUsesOwnerScopedDeleteNotUpsert() = runTest {
        assertEquals(PushResult.Success, writer.push(entry("delete", JSONObject().put("id", "watch"))))
        val request = requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("eq.watch", request.url.queryParameter("id"))
        assertEquals("eq.owner", request.url.queryParameter("user_id"))
        assertTrue(request.url.encodedPath.endsWith("/viewings"))
    }

    @Test fun queuedOutingCompletionNamesAreEncodedAsWebCompanionObjects() = runTest {
        val payload = JSONObject().put("id", "watch").put("titleId", "title").put("date", "2026-01-03")
            .put("outingId", "outing").put("companions", JSONArray().put("Sam"))
        assertEquals(PushResult.Success, writer.push(entry("upsert", payload)))
        assertEquals("Sam", body().getJSONArray("companions").getJSONObject(0).getString("name"))
        assertEquals("outing", body().getString("outing_id"))
    }

    @Test fun legacyOutingUnlinkWithoutBaselineRequiresReviewInsteadOfUnconditionalPatch() = runTest {
        val payload = JSONObject().put("id", "outing").put("completedViewingId", JSONObject.NULL)
            .put("followUpDismissedAt", "2026-10-08T12:00:00Z").put("updatedAt", "2026-10-08T12:00:00Z")
        assertTrue(writer.push(entry("update", payload, "cinema_outing")) is PushResult.Review)
        assertTrue(requests.isEmpty())
    }
}
