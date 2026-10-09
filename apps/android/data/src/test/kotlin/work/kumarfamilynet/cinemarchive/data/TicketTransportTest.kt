package work.kumarfamilynet.cinemarchive.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

class TicketTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = TicketAttachmentFixture
    private val attachment get() = fixture.descriptor()
    private val command get() = TicketAttachmentCommand(fixture.operation, fixture.scope, fixture.outing, attachment, null,
        "2026-10-08T12:00:00.123456Z")
    private fun entry(command: TicketAttachmentCommand = this.command) = OutboxEntity(command.operationId, TICKET_COMMAND_ENTITY,
        command.outingId, TICKET_COMMAND_OPERATION, command.toTicketJson().toString(), 1)
    private var current = true
    private var session: SupabaseSession? = SupabaseSession("owner-token", fixture.scope.ownerId)
    private var afterResponse: () -> Unit = {}
    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<ByteArray>()
    private val replies = ArrayDeque<Triple<Int, ByteArray, String>>()
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests += chain.request()
        bodies += Buffer().also { chain.request().body?.writeTo(it) }.readByteArray()
        val (status, bytes, mime) = replies.removeFirst()
        afterResponse()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(bytes.toResponseBody(mime.toMediaType())).build()
    }).build()
    private val files by lazy { TicketAttachmentFiles(temporary.newFolder(), fixture.scope, { current }, {}) }
    private fun transport() = TicketAttachmentTransport(fixture.scope,
        SupabaseTicketAttachmentRemote(fixture.scope.projectId, "public-key", http), files, { session }, { current })
    private fun reply(value: Any?, status: Int = 200) { replies += Triple(status, value?.toString()?.toByteArray() ?: "null".toByteArray(), "application/json") }
    private fun original() = files.capture(attachment.id, attachment.mimeType, fixture.bytes.inputStream())
    private fun row(descriptor: work.kumarfamilynet.cinemarchive.core.model.TicketAttachment? = attachment) = OutingCommandFixture.row()
        .put("id", fixture.outing).put("user_id", fixture.scope.ownerId).put("ticket_attachment_managed", true)
        .put("ticket_attachment_id", descriptor?.id ?: JSONObject.NULL).put("updated_at", "2026-10-08T14:00:00.111111Z")
    private fun receipt(command: TicketAttachmentCommand = this.command) = JSONObject().put("operationId", command.operationId)
        .put("outingId", command.outingId).put("request", command.receiptRequest())
        .put("attachment", command.attachment?.toTicketJson() ?: JSONObject.NULL).put("outingUpdatedAt", "2026-10-08T14:00:00.111111Z")
        .put("outingRevisionGuarded", command.revisionGuarded)
        .put("rows", JSONArray().put(JSONObject().put("table", "cinema_outings").put("key", JSONObject().put("id", fixture.outing)).put("row", row(command.attachment))))
    private fun descriptors(descriptor: work.kumarfamilynet.cinemarchive.core.model.TicketAttachment? = attachment) = JSONArray().put(
        JSONObject().put("outingId", fixture.outing).put("managed", true).put("attachment", descriptor?.toTicketJson() ?: JSONObject.NULL))
    private fun currentGraph(descriptor: work.kumarfamilynet.cinemarchive.core.model.TicketAttachment? = attachment) {
        reply(descriptors(descriptor)); reply(JSONArray().put(row(descriptor)))
    }
    private fun prepared(state: String = "prepared") = JSONObject().put("attachment", attachment.toTicketJson()).put("state", state)

    @Test fun receiptFirstNeedsNoOriginal() = runTest {
        reply(receipt()); currentGraph()
        assertTrue(transport().push(entry()) is PushResult.Applied)
        assertEquals(3, requests.size)
        assertTrue(requests[0].url.encodedPath.endsWith("get_ticket_command_receipt"))
        assertTrue(requests.none { it.url.encodedPath.contains("/storage/") })
    }

    @Test fun uploadsOriginalAndImmutableGuard() = runTest {
        original()
        reply(null); reply(prepared()); reply(JSONObject()); reply(receipt()); currentGraph()
        assertTrue(transport().push(entry()) is PushResult.Applied)
        assertArrayEquals(fixture.bytes, bodies[2])
        assertEquals("false", requests[2].header("x-upsert"))
        assertEquals("image/png", requests[2].body!!.contentType().toString())
        val args = JSONObject(String(bodies[3]))
        assertEquals(command.expectedUpdatedAt, args.getString("p_expected_updated_at"))
        assertTrue(args.isNull("p_expected_operation_id"))
        requests.forEach { assertEquals("Bearer owner-token", it.header("Authorization")); assertEquals("public-key", it.header("apikey")) }
    }

    @Test fun verifiesCollisionBeforeFinalize() = runTest {
        original()
        reply(null); reply(prepared()); reply(JSONObject().put("error", "Duplicate"), 400)
        replies += Triple(200, fixture.bytes, "image/png")
        reply(receipt()); currentGraph()
        assertTrue(transport().push(entry()) is PushResult.Applied)
        assertTrue(requests[3].url.encodedPath.contains("object/authenticated"))
        assertArrayEquals(fixture.bytes, files.read(attachment).readBytes())
    }

    @Test fun badCollisionNeverFinalizes() = runTest {
        original()
        reply(null); reply(prepared()); reply(JSONObject(), 409)
        replies += Triple(200, fixture.bytes.copyOf().also { it[it.lastIndex] = 12 }, "image/png")
        assertTrue(transport().push(entry()) is PushResult.Retry)
        assertEquals(4, requests.size)
        assertArrayEquals(fixture.bytes, files.read(attachment).readBytes())
    }

    @Test fun refreshFailureRetriesOnlyReceipt() = runTest {
        reply(receipt()); reply(JSONObject().put("message", "offline"), 503)
        assertTrue(transport().push(entry()) is PushResult.Retry)
        reply(receipt()); currentGraph()
        assertTrue(transport().push(entry()) is PushResult.Applied)
        assertTrue(requests.none { it.url.encodedPath.contains("prepare_") || it.url.encodedPath.contains("/storage/") })
    }

    @Test fun acceptedReceiptCannotBecomeRejectedDuringConfirmation() = runTest {
        for (code in listOf("40001", "23505")) {
            reply(receipt()); reply(JSONObject().put("code", code), 409)
            assertTrue(transport().push(entry()) is PushResult.Retry)
            reply(receipt()); reply(descriptors()); reply(JSONObject().put("code", code), 409)
            assertTrue(transport().push(entry()) is PushResult.Retry)
        }
        assertTrue(requests.none { it.url.encodedPath.contains("prepare_") || it.url.encodedPath.contains("/storage/") })
    }

    @Test fun finalizationAndRetiredRaceAcceptanceRemainRetryable() = runTest {
        for (code in listOf("40001", "23505")) {
            reply(null); reply(prepared("attached")); reply(receipt()); reply(JSONObject().put("code", code), 409)
            assertTrue(transport().push(entry()) is PushResult.Retry)
            reply(null); reply(prepared("retired")); reply(receipt()); reply(JSONObject().put("code", code), 409)
            assertTrue(transport().push(entry()) is PushResult.Retry)
        }
    }

    @Test fun retiredPreparationNeedsExistingReceipt() = runTest {
        reply(null); reply(prepared("retired")); reply(null); reply(JSONObject().put("code", "40001"), 409)
        assertTrue(transport().push(entry()) is PushResult.Review)
        reply(null); reply(prepared("retired")); reply(receipt()); currentGraph()
        assertTrue(transport().push(entry()) is PushResult.Applied)
    }

    @Test fun detachUsesPredecessorAndNeverBytes() = runTest {
        val predecessor = "40000000-0000-4000-8000-000000000002"
        val clear = command.copy(attachment = null, expectedAttachmentId = attachment.id, expectedUpdatedAt = null, expectedOperationId = predecessor)
        reply(null); reply(receipt(clear)); currentGraph(null)
        assertTrue(transport().push(entry(clear)) is PushResult.Applied)
        val args = JSONObject(String(bodies[1]))
        assertEquals(predecessor, args.getString("p_expected_operation_id")); assertTrue(args.isNull("p_expected_updated_at"))
        assertTrue(requests[1].url.encodedPath.endsWith("detach_ticket_attachment"))
    }

    @Test fun legacyIntentRetainsOriginalRequest() = runTest {
        val legacy = command.copy(expectedUpdatedAt = null)
        val accepted = receipt(legacy).apply { remove("outingRevisionGuarded") }
        reply(accepted); currentGraph()
        assertTrue(transport().push(entry(legacy)) is PushResult.Applied)
        assertFalse(legacy.finalizeArgs().has("p_expected_updated_at"))
        reply(receipt())
        assertTrue(transport().push(entry(legacy)) is PushResult.Retry)
    }

    @Test fun exactReceiptAndMicrosecondProof() {
        val equivalent = receipt().apply { getJSONObject("request").put("expectedUpdatedAt", "2026-10-08T06:00:00.123456-06:00") }
        checkedTicketReceipt(command, equivalent)
        val bad = listOf(
            receipt().apply { getJSONObject("request").put("expectedUpdatedAt", "2026-10-08T12:00:00.123457Z") },
            receipt().put("outingRevisionGuarded", false), receipt().apply { remove("outingRevisionGuarded") },
            receipt().apply { getJSONObject("request").put("expectedOperationId", fixture.operation) },
            receipt().apply { getJSONArray("rows").getJSONObject(0).getJSONObject("row").put("user_id", "other") },
            receipt().apply { getJSONArray("rows").getJSONObject(0).getJSONObject("key").put("other", true) },
            receipt().put("operationId", fixture.outing),
        )
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { checkedTicketReceipt(command, it) } }
    }

    @Test fun newerReplacementAndDeletedGraphAreNotReplayed() = runTest {
        val newer = attachment.copy(id = "30000000-0000-4000-8000-000000000002", objectKey = "${fixture.scope.ownerId}/30000000-0000-4000-8000-000000000002/original")
        reply(receipt()); currentGraph(newer)
        val result = transport().push(entry()) as PushResult.Applied
        val checked = checkedTicketEnvelope(command, result.receipt)
        assertEquals(newer, checked.association!!.attachment)
        reply(receipt()); reply(JSONArray()); reply(JSONArray())
        val removed = transport().push(entry()) as PushResult.Applied
        assertNull(checkedTicketEnvelope(command, removed.receipt).currentOuting)
    }

    @Test fun inconsistentGraphCannotAcknowledge() = runTest {
        reply(receipt()); reply(descriptors()); reply(JSONArray().put(row(null)))
        assertTrue(transport().push(entry()) is PushResult.Retry)
        reply(receipt()); reply(descriptors()); reply(JSONArray().put(row().put("user_id", "another")))
        assertTrue(transport().push(entry()) is PushResult.Retry)
    }

    @Test fun accountGenerationFencesLateResult() = runTest {
        reply(receipt()); afterResponse = { current = false }
        assertTrue(transport().push(entry()) is PushResult.Retry)
        assertEquals(1, requests.size)
        current = true; session = SupabaseSession("other-token", "10000000-0000-4000-8000-000000000002")
        assertTrue(transport().push(entry()) is PushResult.Retry)
        assertEquals(1, requests.size)
    }

    @Test fun oldEndpointFallbackNeverAcknowledges() = runTest {
        val missing = JSONObject().put("code", "PGRST202")
        reply(missing, 404)
        assertFalse(transport().readDescriptors().authoritative)
        reply(receipt()); reply(missing, 404)
        assertTrue(transport().push(entry()) is PushResult.Retry)
    }

    @Test fun originalDownloadIsBoundedAndHashVerified() = runTest {
        replies += Triple(200, fixture.bytes, "image/png")
        assertArrayEquals(fixture.bytes, transport().download(attachment).file.readBytes())
        replies += Triple(200, fixture.bytes + byteArrayOf(1), "image/png")
        try { transport().download(attachment); fail("Reject wrong size") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(fixture.bytes, files.read(attachment).readBytes())
    }

    @Test fun conflictKeepsIntentWhileCancellationPropagates() = runTest {
        reply(null); reply(prepared("attached")); reply(JSONObject().put("code", "40001"), 409)
        assertTrue(transport().push(entry()) is PushResult.Review)
        reply(receipt()); afterResponse = { throw IOException("Response lost") }
        assertTrue(transport().push(entry()) is PushResult.Retry)
        reply(receipt()); afterResponse = { throw CancellationException("Stopped") }
        try { transport().push(entry()); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }

    @Test fun foreignScopeAndMalformedQueueNeverReachNetwork() = runTest {
        assertTrue(transport().push(entry().copy(entityId = fixture.operation)) is PushResult.Review)
        assertTrue(transport().push(entry().copy(operation = "replace")) is PushResult.Review)
        assertTrue(requests.isEmpty())
    }
}
