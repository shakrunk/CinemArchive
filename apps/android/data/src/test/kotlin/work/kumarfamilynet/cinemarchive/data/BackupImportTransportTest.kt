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

class BackupImportTransportTest {
    private val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private var active = true
    private var afterResponse: () -> Unit = {}
    private val client = SupabaseRestClient("https://backup.invalid", "anon", OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        val (code, body) = replies.removeFirst(); afterResponse()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build())
    private fun transport() = BackupImportTransport(client, SessionSource {
        if (active) SupabaseSession("token", BackupImportFixture.owner.ownerId) else null
    }, BackupImportFixture.owner, { active })
    private fun body(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()
    private fun success(entry: work.kumarfamilynet.cinemarchive.core.database.OutboxEntity) {
        replies += 200 to metadataJson(BackupImportFixture.receipt(entry))
        replies += 200 to JSONArray().put(BackupImportFixture.current(entry)).toString()
        replies += 200 to "[]"; replies += 200 to "[]"
    }


    private fun providerEntry(): work.kumarfamilynet.cinemarchive.core.database.OutboxEntity {
        val original = BackupImportFixture.entry()
        val command = checkedImportCommand(original)
        return original.copy(payloadJson = importPayload(command.scope, command.title, command.outings, command.admittedAt,
            listOf(ProviderTitleLink(SyncProvider.PLEX, "movie:123"))))
    }

    @Test fun providerLinkAndCompleteGraphRetryAsOneExactRequest() = runTest {
        val entry = providerEntry()
        replies += 503 to "{}"
        assertTrue(transport().push(entry) is PushResult.Retry)
        success(entry)
        assertTrue(transport().push(entry) is PushResult.Applied)
        assertEquals(body(0), body(1))
        val operations = exactMetadataObject(body(1)).getJSONArray("p_operations")
        val link = operations.getJSONObject(operations.length() - 1)
        assertEquals("external_title_links", link.getString("table"))
        assertEquals("movie:123", link.getJSONObject("key").getString("external_id"))
        assertEquals(entry.entityId, link.getJSONObject("values").getString("title_id"))
        assertEquals(2, requests.count { it.url.encodedPath.endsWith("/rpc/apply_library_command") })
    }

    @Test fun mismatchedProviderReceiptCannotAcknowledgeOrBecomeDiscardable() = runTest {
        val entry = providerEntry()
        for (field in listOf("user_id", "title_id", "external_id")) {
            val receipt = BackupImportFixture.receipt(entry)
            val rows = receipt.getJSONArray("rows")
            rows.getJSONObject(rows.length() - 1).getJSONObject("row").put(field, "different")
            replies += 200 to metadataJson(receipt)
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
        assertEquals(3, requests.size) // No current-state fetch follows invalid receipt proof.
    }

    @Test fun unknownOutcomeRetriesExactGraphWithOriginalPrecisionThenReadsCurrent() = runTest {
        val entry = BackupImportFixture.entry()
        replies += 503 to "{}"
        assertTrue(transport().push(entry) is PushResult.Retry)
        success(entry)
        val result = transport().push(entry) as PushResult.Applied
        assertEquals(body(0), body(1)); assertTrue(body(1).contains("9007199254740993"))
        assertEquals("Current server title", result.receipt.getJSONObject("currentTitle").getString("title"))
        assertTrue(requests.drop(2).all { it.url.query!!.contains("user_id=eq.${BackupImportFixture.owner.ownerId}") })
    }

    @Test fun acceptedReceiptNeverBecomesDiscardableWhenCurrentReadConflicts() = runTest {
        val entry = BackupImportFixture.entry()
        for (code in listOf("40001", "23505")) {
            replies += 409 to """{"code":"$code"}"""
            assertTrue(transport().push(entry) is PushResult.Review)
            replies += 200 to metadataJson(BackupImportFixture.receipt(entry))
            replies += 409 to """{"code":"$code"}"""
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
    }

    @Test fun receiptAfterDeletionAcknowledgesWithoutHistoricResurrection() = runTest {
        val entry = BackupImportFixture.entry()
        replies += 200 to metadataJson(BackupImportFixture.receipt(entry)); replies += 200 to "[]"
        val result = transport().push(entry) as PushResult.Applied
        assertTrue(result.receipt.isNull("currentTitle")); assertEquals(2, requests.size)
    }

    @Test fun ownerChangeMalformedReceiptAndForeignProjectCannotAcknowledge() = runTest {
        val entry = BackupImportFixture.entry()
        replies += 200 to metadataJson(BackupImportFixture.receipt(entry))
        afterResponse = { active = false }
        assertTrue(transport().push(entry) is PushResult.Retry); assertEquals(1, requests.size)
        active = true; afterResponse = {}
        replies += 200 to metadataJson(BackupImportFixture.receipt(entry).put("operationId", BackupImportFixture.id(99)))
        assertTrue(transport().push(entry) is PushResult.Retry)
        val foreign = entry.copy(payloadJson = entry.payloadJson.replace("https://backup.invalid", "https://other.invalid"))
        assertTrue(transport().push(foreign) is PushResult.Retry); assertEquals(2, requests.size)
    }

    @Test fun crossOwnerChildAndTamperedOperationsStayQueued() = runTest {
        val entry = BackupImportFixture.entry()
        replies += 200 to metadataJson(BackupImportFixture.receipt(entry))
        replies += 200 to JSONArray().put(BackupImportFixture.current(entry)).toString()
        replies += 200 to JSONArray().put(JSONObject().put("id", BackupImportFixture.id(7)).put("title_id", entry.entityId).put("user_id", "other")).toString()
        assertTrue(transport().push(entry) is PushResult.Retry)
        val payload = exactMetadataObject(entry.payloadJson)
        payload.getJSONObject(BACKUP_IMPORT_DATA).getJSONArray("operations").getJSONObject(0).getJSONObject("values").put("title", "Tampered")
        assertTrue(transport().push(entry.copy(payloadJson = metadataJson(payload))) is PushResult.Retry)
        assertEquals(3, requests.size)
    }
}
