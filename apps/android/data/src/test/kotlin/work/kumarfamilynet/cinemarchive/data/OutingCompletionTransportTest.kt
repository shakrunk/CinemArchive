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
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

class OutingCompletionTransportTest {
    private val owner = OutingCommandFixture.owner
    private val provisional = "50000000-0000-4000-8000-000000000001"
    private val canonical = "50000000-0000-4000-8000-000000000002"
    private val originalVersion = "2026-10-08T12:01:00.123456+00:00"
    private val command = OutingCompletionCommand(OutingCommandFixture.outing, OutingCommandFixture.title,
        provisional, OutingCommandFixture.baseline, null, "America/Denver")
    private val entry get() = OutboxEntity(OutingCommandFixture.operation, "outing_completion", command.outingId,
        OUTING_COMPLETION, JSONObject().put(COMPLETION_COMMAND_DATA, command.persisted()).toString(), 1)
    private val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private var afterResponse: () -> Unit = {}
    private var session: SupabaseSession? = SupabaseSession("owner-token", owner)
    private val client = SupabaseRestClient("https://example.supabase.co", "anon", OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests += chain.request()
        val (code, body) = replies.removeFirst()
        afterResponse()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build())
    private fun transport() = OutingCompletionTransport(client) { session }
    private fun body(index: Int) = Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8()
    private fun viewing() = JSONObject().put("id", canonical).put("user_id", owner).put("title_id", command.titleId)
        .put("outing_id", command.outingId).put("updated_at", originalVersion).put("notes", JSONObject.NULL)
        .put("viewed_at", "2026-10-08").put("rating", JSONObject.NULL).put("venue", "Cinema").put("companions", JSONArray())
    private fun response(): JSONObject {
        val outing = OutingCommandFixture.row().put("status", "completed").put("completed_viewing_id", canonical)
        val proof = JSONObject(viewing().toString()).apply { listOf("notes", "viewed_at", "rating", "venue", "companions").forEach(::remove) }
        return JSONObject().put("status", "already_completed").put("operationId", entry.id).put("outingId", command.outingId)
            .put("canonicalViewingId", canonical).put("request", command.request())
            .put("rows", JSONArray().put(JSONObject().put("table", "cinema_outings")
                .put("key", JSONObject().put("id", command.outingId)).put("row", outing))
                .put(JSONObject().put("table", "viewings").put("key", JSONObject().put("id", canonical)).put("row", proof)))
            .put("outing", JSONObject(outing.toString())).put("viewing", viewing())
            .put("title", JSONObject().put("id", command.titleId).put("status", "watched").put("updated_at", originalVersion))
    }
    private fun responseWithOutingProof(version: String?): JSONObject = response().apply {
        put("completionOutingVersion", version ?: JSONObject.NULL)
        getJSONArray("rows").getJSONObject(0).put("row", JSONObject().put("id", command.outingId)
            .put("user_id", owner).put("title_id", command.titleId).put("updated_at", version ?: JSONObject.NULL))
    }
    private fun responseWithTitleProof(version: String? = originalVersion): JSONObject = responseWithOutingProof(originalVersion).apply {
        put("completionTitleVersion", version ?: JSONObject.NULL)
        if (version != null) getJSONArray("rows").put(JSONObject().put("table", "titles")
            .put("key", JSONObject().put("id", command.titleId)).put("row", JSONObject()
                .put("id", command.titleId).put("user_id", owner).put("updated_at", version)))
    }

    @Test fun actualTitleEffectRetainsOriginalRevisionSeparatelyFromLaterTitleState() {
        val reply = responseWithTitleProof()
        reply.getJSONObject("title").put("status", "dropped").put("updated_at", "2026-10-09T12:00:00Z")
        reply.getJSONArray("rows").getJSONObject(2).getJSONObject("row")
            .put("updated_at", "2026-10-08T12:01:00.123456Z")
        val checked = checkedCompletionResponse(entry, reply, owner)
        assertEquals(originalVersion, checked.completionTitleVersion)
        assertEquals(originalVersion, checked.completionOutingVersion)
        assertEquals(originalVersion, checked.alias!!.canonicalViewingVersion)
        assertEquals("dropped", checked.title!!.getString("status"))
        assertEquals("2026-10-09T12:00:00Z", checked.title.getString("updated_at"))
    }

    @Test fun preservedAndHistoricalTitlesNeverGainEffectFromCurrentSnapshot() {
        val variants = listOf(response(), responseWithOutingProof(originalVersion), responseWithTitleProof(null),
            responseWithTitleProof(null).apply { getJSONArray("rows").remove(1) })
        variants.forEach { reply ->
            assertNull(checkedCompletionResponse(entry, reply, owner).completionTitleVersion)
            assertNotNull(reply.getJSONObject("title").getString("updated_at"))
        }
    }

    @Test fun newTitleEffectSurvivesTransportRefreshWithoutRestoringDeletedCurrentRows() = runTest {
        val reply = responseWithTitleProof().put("outing", JSONObject.NULL)
            .put("viewing", JSONObject.NULL).put("title", JSONObject.NULL)
        replies += 200 to reply.toString()
        replies += 200 to "[]"
        val result = transport().push(entry) as PushResult.Applied
        val checked = checkedCompletionEnvelope(entry, result.receipt, owner)
        assertEquals(originalVersion, checked.completionTitleVersion)
        assertNull(checked.outing); assertNull(checked.viewing); assertNull(checked.title)
        assertEquals(canonical, checked.alias!!.canonicalViewingId)
        assertEquals(2, requests.size)
    }

    @Test fun malformedTitleOwnerIdentityAndKeyNeverAcknowledge() {
        val invalid = listOf(
            responseWithTitleProof().apply { getJSONArray("rows").getJSONObject(2).getJSONObject("row").put("user_id", canonical) },
            responseWithTitleProof().apply { getJSONArray("rows").getJSONObject(2).getJSONObject("row").put("id", canonical) },
            responseWithTitleProof().apply { getJSONArray("rows").getJSONObject(2).getJSONObject("key").put("id", canonical) },
            responseWithTitleProof().apply { getJSONArray("rows").getJSONObject(2).getJSONObject("key").put("user_id", owner) },
        )
        invalid.forEach { reply -> assertThrows(IllegalArgumentException::class.java) { checkedCompletionResponse(entry, reply, owner) } }
    }

    @Test fun missingNullOrContradictoryTitleProvenanceNeverAcknowledge() = runTest {
        val invalid = listOf(
            responseWithTitleProof().apply { getJSONArray("rows").remove(2) },
            responseWithTitleProof().put("completionTitleVersion", JSONObject.NULL),
            responseWithTitleProof().apply { remove("completionTitleVersion") },
            responseWithTitleProof().put("completionTitleVersion", 42),
            responseWithTitleProof().put("completionTitleVersion", "not-a-revision"),
            responseWithTitleProof().apply { getJSONArray("rows").getJSONObject(2).getJSONObject("row").put("updated_at", "2026-10-09T12:00:00Z") },
        )
        invalid.forEach { reply ->
            replies += 200 to reply.toString()
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
        assertEquals(invalid.size, requests.size) // No malformed receipt authorizes the follow-up lookup.
        assertEquals(1, requests.indices.map(::body).distinct().size)
    }

    @Test fun extraReorderedDuplicateOrDeletedTitleEffectsAreRejected() {
        val invalid = listOf(
            responseWithTitleProof().apply { getJSONArray("rows").getJSONObject(2).getJSONObject("row").put("status", "watched") },
            responseWithTitleProof().apply { getJSONArray("rows").getJSONObject(2).put("deleted", true) },
            responseWithTitleProof().apply { getJSONArray("rows").getJSONObject(2).put("table", "title_credits") },
            responseWithTitleProof().apply { getJSONArray("rows").put(getJSONArray("rows").getJSONObject(2)) },
            responseWithTitleProof().apply {
                val rows = getJSONArray("rows")
                val viewingEffect = rows.getJSONObject(1)
                rows.put(1, rows.getJSONObject(2)); rows.put(2, viewingEffect)
            },
        )
        invalid.forEach { reply -> assertThrows(IllegalArgumentException::class.java) { checkedCompletionResponse(entry, reply, owner) } }
    }

    @Test fun laterVenueCannotBecomeOriginalOutingBaseline() {
        val reply = responseWithOutingProof(originalVersion)
        reply.getJSONObject("outing").put("venue", "Newer web venue").put("updated_at", "2026-10-09T12:00:00Z")
        val checked = checkedCompletionResponse(entry, reply, owner)
        assertEquals(originalVersion, checked.completionOutingVersion)
        assertEquals("Newer web venue", checked.outing!!.getString("venue"))
    }

    @Test fun historicalAndOldAcceptedOutingRowsNeverGainCausalProvenance() {
        assertNull(checkedCompletionResponse(entry, response(), owner).completionOutingVersion)
        val checked = checkedCompletionResponse(entry, responseWithOutingProof(null), owner)
        assertNull(checked.completionOutingVersion)
        assertNotNull(checked.outing!!.getString("updated_at"))
        assertEquals(originalVersion, checked.alias!!.canonicalViewingVersion)
    }

    @Test fun contradictoryOriginalOutingVersionOrUnknownProofMustNotAcknowledge() {
        val contradictory = responseWithOutingProof(originalVersion)
        contradictory.getJSONArray("rows").getJSONObject(0).getJSONObject("row").put("updated_at", "2026-10-09T12:00:00Z")
        assertThrows(IllegalArgumentException::class.java) { checkedCompletionResponse(entry, contradictory, owner) }
        val invented = responseWithOutingProof(null)
        invented.getJSONArray("rows").getJSONObject(0).getJSONObject("row").put("updated_at", originalVersion)
        assertThrows(IllegalArgumentException::class.java) { checkedCompletionResponse(entry, invented, owner) }
    }

    @Test fun firstAppliedCompletionMustUseItsOwnProvisionalIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            checkedCompletionResponse(entry, response().put("status", "applied"), owner)
        }
    }

    @Test fun unknownDeliveryRetriesSameOperationPreconditionAndCapturedZone() = runTest {
        replies += 503 to "{}"
        assertTrue(transport().push(entry) is PushResult.Retry)
        replies += 200 to response().toString()
        replies += 200 to JSONArray().put(viewing()).toString()
        assertTrue(transport().push(entry) is PushResult.Applied)
        assertEquals(body(0), body(1))
        val wire = JSONObject(body(0))
        assertEquals(entry.id, wire.getString("p_operation_id"))
        assertEquals("America/Denver", wire.getString("p_tz"))
        assertEquals(provisional, wire.getString("p_provisional_viewing_id"))
        assertTrue(wire.isNull("p_expected_operation_id"))
        assertTrue(requests.all { it.header("Authorization") == "Bearer owner-token" })
        assertTrue(requests.take(2).all { it.url.encodedPath == "/rest/v1/rpc/complete_cinema_outing" })
        assertEquals("eq.$canonical", requests.last().url.queryParameter("id"))
        assertEquals("eq.$owner", requests.last().url.queryParameter("user_id"))
    }

    @Test fun deletedOutingDoesNotEraseSurvivingUnlinkedCanonicalHistory() = runTest {
        replies += 200 to response().put("outing", JSONObject.NULL).put("viewing", JSONObject.NULL).put("title", JSONObject.NULL).toString()
        replies += 200 to JSONArray().put(viewing().put("outing_id", JSONObject.NULL).put("notes", "Keep independent history")).toString()
        val result = transport().push(entry) as PushResult.Applied
        val checked = checkedCompletionEnvelope(entry, result.receipt, owner)
        assertNull(checked.outing)
        assertEquals("Keep independent history", checked.viewing!!.getString("notes"))
        assertTrue(checked.viewing.isNull("outing_id"))
    }

    @Test fun exactOwnedLookupAbsenceIsSeparateFromImmutableViewingProof() = runTest {
        replies += 200 to response().toString(); replies += 200 to "[]"
        val checked = checkedCompletionEnvelope(entry, (transport().push(entry) as PushResult.Applied).receipt, owner)
        assertNull(checked.viewing)
        assertEquals(originalVersion, checked.alias!!.canonicalViewingVersion)
    }

    @Test fun wrongParentInFreshLookupOrRefreshFailureCannotAcknowledgeAcceptedCommand() = runTest {
        replies += 200 to response().toString()
        replies += 200 to JSONArray().put(viewing().put("outing_id", provisional)).toString()
        assertTrue(transport().push(entry) is PushResult.Retry)
        replies += 200 to response().toString()
        replies += 409 to """{"code":"40001","message":"Lookup failed"}"""
        assertTrue(transport().push(entry) is PushResult.Retry)
    }

    @Test fun laterWebNotesRemainCurrentButNeverBecomeDependentViewingBaseline() {
        val reply = response()
        reply.getJSONObject("viewing").put("notes", "Later web note").put("updated_at", "2026-10-09T12:00:00Z")
        val checked = checkedCompletionResponse(entry, reply, owner)
        assertEquals(canonical, checked.alias!!.canonicalViewingId)
        assertEquals(originalVersion, checked.alias.canonicalViewingVersion)
        assertEquals("Later web note", checked.viewing!!.getString("notes"))
    }

    @Test fun deletedViewingAndOutingKeepOnlyImmutableIdentityNotRecreatedRows() {
        val reply = response().put("viewing", JSONObject.NULL).put("outing", JSONObject.NULL).put("title", JSONObject.NULL)
        val checked = checkedCompletionResponse(entry, reply, owner)
        assertEquals(canonical, checked.alias!!.canonicalViewingId)
        assertNull(checked.outing); assertNull(checked.viewing); assertNull(checked.title)
    }

    @Test fun legacyAcceptedIdentityHasUnknownBaselineEvenWhenCurrentViewingHasRevision() {
        val reply = response()
        reply.getJSONArray("rows").remove(1)
        val checked = checkedCompletionResponse(entry, reply, owner)
        assertEquals(canonical, checked.alias!!.canonicalViewingId)
        assertNull(checked.alias.canonicalViewingVersion)
        assertNotNull(checked.viewing!!.getString("updated_at"))
    }

    @Test fun equivalentPostgresTimestampAcceptedButChangedSavedRequestRejected() {
        val reply = response()
        reply.getJSONObject("request").put("expectedUpdatedAt", "2026-10-08T12:00:00+00:00")
        checkedCompletionResponse(entry, reply, owner)
        reply.getJSONObject("request").put("timezone", "UTC")
        assertThrows(IllegalArgumentException::class.java) { checkedCompletionResponse(entry, reply, owner) }
    }

    @Test fun causalPredecessorIsCapturedAndCannotAlsoSupplyTimestamp() {
        val predecessor = "40000000-0000-4000-8000-000000000002"
        val causal = command.copy(expectedUpdatedAt = null, expectedOperationId = predecessor)
        val saved = entry.copy(payloadJson = JSONObject().put(COMPLETION_COMMAND_DATA, causal.persisted()).toString())
        assertEquals(predecessor, completionCommand(saved).rpc(saved.id).getString("p_expected_operation_id"))
        assertThrows(IllegalArgumentException::class.java) { command.copy(expectedOperationId = predecessor) }
        assertThrows(IllegalArgumentException::class.java) { completionCommand(saved.copy(id = predecessor)) }
    }

    @Test fun malformedIdentityOwnerParentOrEffectsNeverAcknowledge() = runTest {
        val invalid = listOf(
            response().put("operationId", canonical),
            response().apply { getJSONObject("outing").put("user_id", canonical) },
            response().apply { getJSONObject("viewing").put("title_id", canonical) },
            response().apply { getJSONObject("viewing").put("outing_id", canonical) },
            response().apply { getJSONArray("rows").getJSONObject(1).getJSONObject("row").put("user_id", canonical) },
            response().apply { getJSONArray("rows").getJSONObject(1).getJSONObject("row").put("outing_id", canonical) },
            response().apply { getJSONArray("rows").getJSONObject(0).getJSONObject("row").put("title_id", canonical) },
            response().apply { getJSONArray("rows").put(getJSONArray("rows").getJSONObject(1)) },
            response().put("rows", JSONArray()),
        )
        invalid.forEach { reply ->
            replies += 200 to reply.toString()
            assertTrue(transport().push(entry) is PushResult.Retry)
        }
    }

    @Test fun ownerSwitchAfterRpcCannotPublishPreviousAccountProof() = runTest {
        replies += 200 to response().toString()
        afterResponse = { session = null }
        assertTrue(transport().push(entry) is PushResult.Retry)
        assertEquals(1, requests.size)
    }

    @Test fun knownConflictRequiresReviewWhileUnknownFailuresRetainOriginalCommand() = runTest {
        val conflict = response().put("status", "conflict").apply { remove("rows") }
        replies += 200 to conflict.toString()
        assertTrue(transport().push(entry) is PushResult.Review)
        replies += 409 to """{"code":"40001","message":"Changed"}"""
        assertTrue(transport().push(entry) is PushResult.Review)
        replies += 200 to response().put("request", JSONObject()).toString()
        assertTrue(transport().push(entry) is PushResult.Retry)
        replies += 200 to response().toString()
        afterResponse = { throw IOException("Response lost") }
        assertTrue(transport().push(entry) is PushResult.Retry)
    }

    @Test fun cancellationDoesNotTurnIntoAnAcknowledgmentOrReview() = runTest {
        replies += 200 to response().toString()
        afterResponse = { throw CancellationException("Account runtime closed") }
        try { transport().push(entry); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }
}
