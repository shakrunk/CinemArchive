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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

class EpisodeHistoryWriterTest {
    private val requests = mutableListOf<Request>()
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body("[]".toResponseBody("application/json".toMediaType())).build()
    }).build()
    private val writer = SupabaseRemoteMutationWriter(SupabaseRestClient("https://x.supabase.co", "anon", http)) { SupabaseSession("token", "owner") }
    private fun entry(op: String, payload: JSONObject) = OutboxEntity("command", "episode_watch_event", "watch", op, payload.toString(), 0L)
    private fun body() = JSONObject(Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8())

    @Test fun prePlatformWatchSendsExplicitNullDateAndNotes() = runTest {
        val payload = JSONObject().put("id", "watch").put("episodeId", "episode").put("watchedAt", JSONObject.NULL).put("notes", "Years ago")
        assertEquals(PushResult.Success, writer.push(entry("upsert", payload)))
        assertTrue(body().has("watched_at") && body().isNull("watched_at"))
        assertEquals("Years ago", body().getString("notes"))
        assertEquals("watch", body().getString("id"))
        assertEquals("owner", body().getString("user_id"))
    }

    @Test fun explicitNullNotesRemainNullAndLegacyPayloadDoesNotClearExistingNotes() = runTest {
        val payload = JSONObject().put("id", "watch").put("episodeId", "episode").put("watchedAt", "2026-01-02")
        writer.push(entry("upsert", payload))
        assertFalse(body().has("notes"))
        requests.clear()
        writer.push(entry("upsert", payload.put("notes", JSONObject.NULL)))
        assertTrue(body().has("notes") && body().isNull("notes"))
    }

    @Test fun deleteUsesExactOwnerScopedWatchId() = runTest {
        assertEquals(PushResult.Success, writer.push(entry("delete", JSONObject().put("id", "watch"))))
        val request = requests.single()
        assertEquals("DELETE", request.method)
        assertTrue(request.url.encodedPath.endsWith("/episode_watch_events"))
        assertEquals("eq.watch", request.url.queryParameter("id"))
        assertEquals("eq.owner", request.url.queryParameter("user_id"))
    }
    @Test fun watchAndReviewColorModePreserveExplicitChoiceNullAndLegacyOmission() = runTest {
        for (kind in listOf("episode_watch_event", "episode_review")) {
            val payload = JSONObject().put("id", "history").put("episodeId", "episode")
                .put("watchedAt", JSONObject.NULL).put("reviewText", "Review").put("reviewedAt", "2026-10-09T12:00:00Z")
            for (mode in listOf("absent", "bw", "color", "null")) {
                requests.clear()
                if (mode == "absent") payload.remove("colorMode")
                else payload.put("colorMode", if (mode == "null") JSONObject.NULL else mode)
                assertEquals(PushResult.Success, writer.push(entry("upsert", payload).copy(entityType = kind)))
                when (mode) {
                    "absent" -> assertFalse(body().has("color_mode"))
                    "null" -> assertTrue(body().has("color_mode") && body().isNull("color_mode"))
                    else -> assertEquals(mode, body().getString("color_mode"))
                }
                assertEquals("owner", body().getString("user_id"))
            }
        }
    }

}
