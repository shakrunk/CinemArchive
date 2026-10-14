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

class CatalogRefreshTransportTest {
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val bodies = mutableListOf<String>()
    private val client = SupabaseRestClient("https://example.supabase.co", "anon", OkHttpClient.Builder().addInterceptor { chain ->
        chain.request().body?.let { body -> bodies += Buffer().also { body.writeTo(it) }.readUtf8() }
        val (code, text) = replies.removeFirst()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(text.toResponseBody("application/json".toMediaType())).build()
    }.build())

    @Test fun catalogTitleUsesImmutableCasReceiptThenNewerCurrentRow() = runTest {
        val patch = catalogMetadataPatch(TitleMetadataFixture.entity(), providerDetails().copy(title = "Fresh title"))
        val entry = TitleMetadataFixture.entry(patch)
        val effect = TitleMetadataFixture.row().apply { patch.keys().forEach { put(it, patch.get(it)) } }
        val receipt = JSONObject().put("operationId", entry.id).put("rows", JSONArray().put(JSONObject()
            .put("table", "titles").put("key", JSONObject().put("id", entry.entityId)).put("row", effect)))
        val transport = TitleMetadataTransport(client, SessionSource { SupabaseSession("token", TitleMetadataFixture.owner) })
        replies += 503 to "{}"
        assertTrue(transport.push(entry) is PushResult.Retry)
        replies += 200 to receipt.toString()
        replies += 200 to JSONArray().put(JSONObject(effect.toString()).put("title", "Newer remote title")).toString()
        val applied = transport.push(entry) as PushResult.Applied
        assertEquals(bodies[0], bodies[1])
        assertEquals(TitleMetadataFixture.baseline, JSONObject(bodies[0]).getJSONArray("p_operations").getJSONObject(0).getString("expectedUpdatedAt"))
        assertEquals("Newer remote title", applied.receipt.getJSONObject("current").getString("title"))
        replies += 409 to """{"code":"40001","message":"Changed"}"""
        assertTrue(transport.push(entry) is PushResult.Review)
    }

    @Test fun episodeMetadataRetryUsesSameIdAndFreshOwnedRowWithoutHistoryColumns() = runTest {
        val id = BackupImportFixture.id(22)
        val operations = JSONArray().put(JSONObject().put("table", "titles").put("action", "update")
            .put("key", JSONObject().put("id", TitleMetadataFixture.title)).put("values", JSONObject()))
            .put(JSONObject().put("table", "episodes").put("action", "update").put("key", JSONObject().put("id", id))
                .put("values", JSONObject().put("episode_name", "Fetched name").put("runtime", 45)))
        val entry = OutboxEntity(BackupImportFixture.id(50), "title_catalog", TitleMetadataFixture.title, "ensure",
            JSONObject().put("ownerId", TitleMetadataFixture.owner).put("titleId", TitleMetadataFixture.title)
                .put("operations", operations).put("catalog", JSONArray()).toString(), 1)
        val row = JSONObject().put("id", id).put("user_id", TitleMetadataFixture.owner).put("title_id", TitleMetadataFixture.title)
            .put("episode_name", "Fetched name").put("runtime", 45)
        val receipt = JSONObject().put("operationId", entry.id).put("rows", JSONArray().put(JSONObject().put("table", "titles")
            .put("key", JSONObject().put("id", entry.entityId)).put("row", TitleMetadataFixture.row()))
            .put(JSONObject().put("table", "episodes").put("key", JSONObject().put("id", id)).put("row", row)))
        val transport = EpisodeCatalogFillTransport(client) { SupabaseSession("token", TitleMetadataFixture.owner) }
        replies += 503 to "{}"
        assertTrue(runCatching { transport.push(entry) }.isFailure)
        replies += 200 to receipt.toString()
        replies += 200 to JSONArray().put(JSONObject(row.toString()).put("episode_name", "Newer remote name")).toString()
        val applied = transport.push(entry) as PushResult.Applied
        assertEquals(bodies[0], bodies[1]); assertFalse(bodies[0].contains("watch_events")); assertFalse(bodies[0].contains("episodes_watched"))
        assertEquals("Newer remote name", applied.receipt.getJSONArray("currentRows").getJSONObject(0).getJSONObject("row").getString("episode_name"))
        replies += 200 to receipt.toString(); replies += 200 to "[]"
        assertEquals(0, (transport.push(entry) as PushResult.Applied).receipt.getJSONArray("currentRows").length())
    }
}
