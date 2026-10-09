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
import work.kumarfamilynet.cinemarchive.core.database.ViewingEntity

class ProviderMergeTransportTest {
    private val owner = BackupImportFixture.owner
    private val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val entry get() = OutboxEntity(BackupImportFixture.id(80), PROVIDER_MERGE, BackupImportFixture.id(1), PROVIDER_MERGE_COMMAND,
        providerMergePayload(owner, BackupImportFixture.id(1), "Film", JSONObject().put("rating", 4.0),
            listOf(ViewingEntity(BackupImportFixture.id(81), BackupImportFixture.id(1), "2026-01-01", null, null, null)),
            ProviderTitleLink(SyncProvider.PLEX, "movie:12"), BackupImportFixture.at, ViewingGuard(revision = BackupImportFixture.at)), 1)
    private val client = SupabaseRestClient(owner.projectId, "anon", OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request(); val (code, body) = replies.removeFirst()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build())
    private fun source() = ProviderMergeTransport(client, SessionSource { SupabaseSession("token", owner.ownerId) }, owner, { true })
    private fun receipt() = JSONObject().put("operationId", entry.id).put("rows", JSONArray(checkedProviderMerge(entry).operations.importObjects().map { op ->
        val row = exactMetadataObject(metadataJson(op.getJSONObject("values")))
        val key = op.getJSONObject("key"); key.keys().forEach { row.put(it, key.get(it)) }
        row.put("user_id", owner.ownerId).put("updated_at", BackupImportFixture.at)
        JSONObject().put("table", op.getString("table")).put("key", key).put("row", row)
    }))
    private fun body(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()
    @Test fun uncertainRetryKeepsExactRequestAndLaterCurrentState() = runTest {
        replies += 503 to "{}"; assertTrue(source().push(entry) is PushResult.Retry)
        replies += 200 to metadataJson(receipt())
        replies += 200 to JSONArray().put(TitleMetadataFixture.row().put("id", entry.entityId).put("rating", 5.0)).toString()
        replies += 200 to "[]"
        val result = source().push(entry) as PushResult.Applied
        assertEquals(body(0), body(1)); assertEquals(5.0, result.receipt.getJSONObject("currentTitle").getDouble("rating"), 0.0)
    }
    @Test fun onlyDefinitePreReceiptConflictIsReviewable() = runTest {
        for (code in listOf("40001", "23505")) {
            replies += 409 to """{"code":"$code"}"""; assertTrue(source().push(entry) is PushResult.Review)
            replies += 200 to metadataJson(receipt()); replies += 409 to """{"code":"$code"}"""
            assertTrue(source().push(entry) is PushResult.Retry)
        }
    }
    @Test fun wrongOwnerReceiptCannotAcknowledgeAndDeletedCurrentNeverResurrects() = runTest {
        val bad = receipt(); bad.getJSONArray("rows").getJSONObject(2).getJSONObject("row").put("user_id", "other")
        replies += 200 to metadataJson(bad); assertTrue(source().push(entry) is PushResult.Retry)
        assertEquals(1, requests.size)
        replies += 200 to metadataJson(receipt()); replies += 200 to "[]"
        assertTrue((source().push(entry) as PushResult.Applied).receipt.isNull("currentTitle"))
    }
}
