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
import work.kumarfamilynet.cinemarchive.core.model.NOIR_PIN_KEY

class TitlePinTransportTest {
    private val owner = id(1); private val title = id(2)
    private var active = true
    private var after: () -> Unit = {}
    private val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val client = SupabaseRestClient("https://example.supabase.co", "anon", OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request(); val (code, body) = replies.removeFirst(); after()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build())
    private fun remote() = TitlePinTransport(client, SessionSource { if (active) SupabaseSession("token", owner) else null })
    private fun entry(mode: String? = "bw") = OutboxEntity(id(3), TITLE_PIN, title, TITLE_PIN_COMMAND,
        JSONObject().put("version", 1).put("ownerId", owner).put("titleId", title).put("variant", mode ?: JSONObject.NULL).toString(), 0)
    private fun row(mode: String) = JSONObject().put("user_id", owner).put("title_id", title).put("easter_egg_key", NOIR_PIN_KEY).put("pinned_variant", mode)
    private fun receipt(entry: OutboxEntity) = JSONObject().put("operationId", entry.id).put("rows", JSONArray().put(JSONObject()
        .put("table", "user_title_pins").put("key", titlePinOperations(entry, owner).getJSONObject(0).getJSONObject("key")).also {
            val payload = JSONObject(entry.payloadJson)
            if (payload.isNull("variant")) it.put("deleted", true) else it.put("row", row(payload.getString("variant")))
        }))
    private fun body(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()
    @Test fun uncertainRetryUsesSameOperationAndFreshPinRatherThanHistoricReceipt() = runTest {
        val entry = entry(); replies += 503 to "{}"
        assertTrue(remote().push(entry) is PushResult.Retry)
        replies += 200 to receipt(entry).toString(); replies += 200 to JSONArray().put(row("color")).toString()
        val result = remote().push(entry) as PushResult.Applied
        assertEquals(body(0), body(1)); assertEquals("color", result.receipt.getJSONObject("current").getString("pinned_variant"))
        val operation = JSONObject(body(0)).getJSONArray("p_operations").getJSONObject(0)
        assertEquals("put", operation.getString("action")); assertEquals(NOIR_PIN_KEY, operation.getJSONObject("key").getString("easter_egg_key"))
        assertEquals("eq.$owner", requests.last().url.queryParameter("user_id"))
        requests.clear(); val clear = this@TitlePinTransportTest.entry(null)
        replies += 200 to receipt(clear).toString(); replies += 200 to "[]"
        assertTrue(remote().push(clear) is PushResult.Applied)
        assertEquals("delete", JSONObject(body(0)).getJSONArray("p_operations").getJSONObject(0).getString("action"))
    }
    @Test fun onlyDefinitePreAcceptanceRejectionPlusFreshAbsentParentResolvesMissingPreference() = runTest {
        val entry = entry()
        replies += 409 to """{"code":"P0002"}"""; replies += 200 to "[]"
        val missing = remote().push(entry) as PushResult.Applied
        assertTrue(missing.receipt.getBoolean("missingParent")); assertFalse(missing.receipt.has("receipt"))
        assertEquals("eq.$owner", requests.last().url.queryParameter("user_id"))
        replies += 409 to """{"code":"23503"}"""; replies += 503 to "{}"
        assertTrue(remote().push(entry) is PushResult.Retry)
        replies += 200 to receipt(entry).toString(); replies += 409 to """{"code":"P0002"}"""
        val before = requests.size
        assertTrue(remote().push(entry) is PushResult.Retry); assertEquals(2, requests.size - before)
    }
    @Test fun foreignReceiptAndCurrentRowsNeverAcknowledgeAndOwnerChangeIsFenced() = runTest {
        val entry = entry(); val wrong = receipt(entry)
        wrong.getJSONArray("rows").getJSONObject(0).getJSONObject("row").put("user_id", id(9))
        replies += 200 to wrong.toString(); assertTrue(remote().push(entry) is PushResult.Retry)
        replies += 200 to receipt(entry).toString(); replies += 200 to JSONArray().put(row("bw").put("title_id", id(9))).toString()
        assertTrue(remote().push(entry) is PushResult.Retry)
        replies += 200 to receipt(entry).toString(); after = { active = false }
        assertTrue(remote().push(entry) is PushResult.Retry)
    }
    @Test fun fullFetchHandlesEmptyAndRejectsForeignOrRepeatedPages() = runTest {
        replies += 200 to "[]"; assertTrue(remote().fetch().isEmpty())
        replies += 200 to JSONArray().put(row("bw").put("user_id", id(9))).toString()
        try { remote().fetch(); fail() } catch (_: IllegalArgumentException) { }
        val page = JSONArray(); repeat(500) { page.put(row("bw").put("title_id", id(it + 100))) }
        replies += 200 to page.toString(); replies += 200 to JSONArray().put(row("color").put("title_id", id(100))).toString()
        try { remote().fetch(); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("500", requests.last().url.queryParameter("offset"))
    }
    companion object { private fun id(n: Int) = "10000000-0000-4000-8000-${n.toString().padStart(12, '0')}" }
}
