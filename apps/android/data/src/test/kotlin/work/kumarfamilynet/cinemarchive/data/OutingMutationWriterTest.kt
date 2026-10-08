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
import work.kumarfamilynet.cinemarchive.core.database.CinemaOutingEntity
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat

class OutingMutationWriterTest {
    private val requests = mutableListOf<Request>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests += chain.request()
        val (code, body) = replies.removeFirst()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build()
    private val writer = SupabaseRemoteMutationWriter(SupabaseRestClient("https://x.supabase.co", "anon", http)) {
        SupabaseSession("token", "owner")
    }
    private fun entry(operation: String, payload: JSONObject) = OutboxEntity("op", "cinema_outing", "outing", operation, payload.toString(), 0)
    private fun body(index: Int = 0) = JSONObject(Buffer().also { requests[index].body!!.writeTo(it) }.readUtf8())
    private fun seed() = CinemaOutingEntity(
        id = "outing", titleId = "title", showtime = "2099-10-08T19:00:00Z", runtimeMinutes = 90,
        endsAt = "2099-10-08T20:50:00Z", venue = "Cinema", companions = listOf("Sam"), format = "THREE_D", ticketPrice = null,
        createdAt = "2026-10-08T12:00:00Z", updatedAt = "2026-10-08T12:00:00Z",
    )
    private fun remote() = JSONObject("""{
        "id":"outing","title_id":"title","user_id":"owner","showtime":"2099-10-08T19:00:00+00:00",
        "previews_minutes":20,"runtime_minutes":90,"ends_at":"2099-10-08T20:50:00+00:00",
        "venue":"Cinema","companions":[{"name":"Sam"}],"format":"3D","ticket_price":null,
        "seat":null,"auditorium":null,"seat_row":null,"seats":[],"booking_ref":null,
        "ticket_image_path":null,"ticket_barcode_payload":null,"ticket_barcode_format":null,
        "notes":null,"status":"scheduled","previous_status":null,"completed_viewing_id":null,
        "follow_up_dismissed_at":null,"created_at":"2026-10-08T12:00:00+00:00","updated_at":"2026-10-08T12:01:00Z"
    }""")

    @Test fun explicitCreateUsesInsertAndCanonicalFormatAndCompanionObjects() = runTest {
        replies += 201 to JSONArray().put(remote()).toString()
        assertEquals(PushResult.Success, writer.push(entry("insert", seed().mutationPayload())))
        assertEquals("POST", requests.single().method)
        assertNull(requests.single().url.queryParameter("on_conflict"))
        assertFalse(requests.single().header("Prefer").orEmpty().contains("merge-duplicates"))
        assertEquals("3D", body().getString("format"))
        assertEquals("Sam", body().getJSONArray("companions").getJSONObject(0).getString("name"))
        assertTrue(body().has("ticket_image_path") && body().isNull("ticket_image_path"))
    }

    @Test fun legacyVenuePatchWithoutBaselineRequiresReviewWithoutNetworkWrite() = runTest {
        val existing = seed()
        val changed = existing.copy(venue = "New cinema", updatedAt = "2026-10-08T13:00:00Z")
        assertTrue(writer.push(entry("update", changed.mutationPayload(existing))) is PushResult.Review)
        assertTrue(requests.isEmpty())
    }

    @Test fun explicitClearsDoNotClearAbsentFieldsAndPreserveCompanionFriendIds() = runTest {
        val payload = JSONObject().put("id", "outing").put("notes", JSONObject.NULL)
            .put("companions", JSONArray().put(JSONObject().put("name", "Sam").put("friendUserId", "friend")))
            .put("updatedAt", "2026-10-08T13:00:00Z")
        val command = outingCommandPayload(payload, false, "2026-10-08T12:00:00Z", null)
        val values = outingCommandOperations(entry(OUTING_COMMAND, command)).getJSONObject(0).getJSONObject("values")
        assertTrue(values.isNull("notes"))
        assertFalse(values.has("completed_viewing_id"))
        assertFalse(values.has("follow_up_dismissed_at"))
        assertEquals("friend", values.getJSONArray("companions").getJSONObject(0).getString("friendUserId"))
    }

    @Test fun missingPatchTargetIsRetainedInsteadOfRecreated() = runTest {
        assertTrue(writer.push(entry("update", JSONObject().put("id", "outing").put("venue", "Edit"))) is PushResult.Review)
        assertTrue(requests.isEmpty())
    }

    @Test fun exactLegacySnapshotIsAcknowledgedReadOnlyDespiteTimestampAndFormatSpelling() = runTest {
        replies += 200 to JSONArray().put(remote()).toString()
        assertEquals(PushResult.Success, writer.push(entry("upsert", seed().mutationPayload())))
        assertEquals("GET", requests.single().method)
        assertEquals("eq.owner", requests.single().url.queryParameter("user_id"))
    }

    @Test fun legacyMissingRowDoesNotProveCreateEvenWhenCreatedAndUpdatedTimesMatch() = runTest {
        replies += 200 to "[]"
        val payload = seed().mutationPayload()
        assertEquals(payload.getString("createdAt"), payload.getString("updatedAt"))
        assertTrue(writer.push(entry("upsert", payload)) is PushResult.Retry)
        assertEquals(listOf("GET"), requests.map { it.method })
    }

    @Test fun retryMatchesPostgresMicrosecondPrecisionIncludingTiesAndSecondCarry() = runTest {
        // Expected values verified with actual PostgreSQL casts in a local PGlite database.
        val timestamps = listOf(
            "12:00:00.0000005Z" to "12:00:00Z",
            "12:00:00.0000015Z" to "12:00:00.000002Z",
            "12:00:00.1234565Z" to "12:00:00.123456Z",
            "12:00:00.9999995Z" to "12:00:01Z",
            "12:00:00.123456789Z" to "12:00:00.123457Z",
        )
        for ((native, server) in timestamps) {
            replies += 200 to JSONArray().put(remote().put("created_at", "2026-10-08T$server")).toString()
            assertEquals(PushResult.Success, writer.push(entry("upsert", seed().copy(createdAt = "2026-10-08T$native").mutationPayload())))
        }
        replies += 200 to JSONArray().put(remote().put("created_at", "2026-10-08T12:00:00.123458Z")).toString()
        assertTrue(writer.push(entry("upsert", seed().copy(createdAt = "2026-10-08T12:00:00.123456789Z").mutationPayload())) is PushResult.Retry)
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun legacySnapshotCannotEraseLinkedCompanionOrNewerStatusOrAttachment() = runTest {
        val changedRows = listOf(
            remote().put("companions", JSONArray().put(JSONObject().put("name", "Sam").put("friendUserId", "friend"))),
            remote().put("status", "completed"),
            remote().put("ticket_image_path", "owner/remote-ticket"),
        )
        for (changed in changedRows) {
            replies += 200 to JSONArray().put(changed).toString()
            assertTrue(writer.push(entry("upsert", seed().mutationPayload())) is PushResult.Retry)
        }
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test fun uncertainCreateRetryAcknowledgesOnlyAnExactExistingRow() = runTest {
        replies += 409 to """{"code":"23505","message":"duplicate"}"""
        replies += 200 to JSONArray().put(remote()).toString()
        assertEquals(PushResult.Success, writer.push(entry("insert", seed().mutationPayload())))
        replies += 409 to """{"code":"23505","message":"duplicate"}"""
        replies += 200 to JSONArray().put(remote().put("venue", "Changed on web")).toString()
        assertTrue(writer.push(entry("insert", seed().mutationPayload())) is PushResult.Retry)
        assertEquals(listOf("POST", "GET", "POST", "GET"), requests.map { it.method })
        assertTrue(requests.none { it.header("Prefer").orEmpty().contains("merge-duplicates") })
    }

    @Test fun incompleteLegacyIntentCannotAccidentallyAcknowledgeAndDisappear() = runTest {
        assertTrue(writer.push(entry("upsert", JSONObject().put("id", "outing"))) is PushResult.Retry)
        assertTrue(requests.isEmpty())
    }

    @Test fun formatCodecAcceptsEveryWebAndLegacyValueWithoutChangingUnknownValues() {
        val wire = listOf("Standard", "IMAX", "3D", "Dolby", "70mm", "Drive-in", "Other")
        CinemaFormat.entries.zip(wire).forEach { (format, expected) ->
            assertEquals(expected, format.wireValue)
            assertEquals(format, CinemaFormat.fromWire(expected))
            assertEquals(format, CinemaFormat.fromWire(format.name))
            assertEquals(format, CinemaFormat.fromWire(expected.lowercase()))
        }
        assertNull(CinemaFormat.fromWire("Future format"))
        val payload = JSONObject().put("format", "Future format")
        assertEquals("Future format", outingWireBody(payload, "owner", false).getString("format"))
    }

    @Test fun equivalentWireFormatsAndInstantsAreNotUnrelatedEdits() {
        val previous = seed().copy(showtime = "2099-10-08T19:00:00+00:00", format = "3D")
        val current = previous.copy(showtime = "2099-10-08T19:00:00Z", format = "THREE_D")
        assertEquals(setOf("id", "updatedAt"), current.mutationPayload(previous).keys().asSequence().toSet())
        assertEquals(CinemaFormat.THREE_D, previous.toDomain().format)
    }
}

