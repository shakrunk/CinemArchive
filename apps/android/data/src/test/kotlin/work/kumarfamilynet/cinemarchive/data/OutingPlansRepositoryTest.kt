package work.kumarfamilynet.cinemarchive.data

import java.time.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.model.CinemaFormat

class OutingPlansRepositoryTest {
    private val receipt = """{"tmdb_id":42,"type":"movie","title":"Delivered Film","year":2026,"poster_url":null,"showtime":"2099-10-08T19:00:00Z","ends_at":"2099-10-08T21:00:00Z","venue":"Original cinema","format":"3D","seat":"G8","companions":["Friend"]}"""
    private val operationId = java.util.UUID.randomUUID().toString()
    private val plan = PublicOutingPlan("outing", "Film", "2099-10-08T19:00:00Z", "2099-10-08T21:00:00Z", "Cinema", CinemaFormat.IMAX, "F12", listOf("Friend"))
    private fun friend(status: FriendshipStatus = FriendshipStatus.ACCEPTED) = Friendship("friend", status, "owner", null, "", "", "Friend", null)
    private suspend fun failure(fragment: String, action: suspend () -> Unit) {
        try { action(); fail("Expected failure") } catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains(fragment)) }
    }

    @Test fun pendingChangesPreventNetworkShareAndBecomeRetryableAfterSync() = runTest {
        var pending = true
        var shares = 0
        val repo = OutingPlansRepository("owner", { SupabaseSession("token", "owner") },
            { OutingPlanSnapshot(plan, true, pending) }, { listOf(friend()) }, { _, _, _, _ -> shares++; plan })
        failure("still syncing") { repo.send("outing", "friend", operationId) }
        assertEquals(0, shares)
        pending = false
        assertEquals(plan, repo.send("outing", "friend", operationId))
        assertEquals(1, shares)
    }

    @Test fun missingCancelledAndEndedPlansCannotBeShared() = runTest {
        var snapshot: OutingPlanSnapshot? = null
        var shares = 0
        val repo = OutingPlansRepository("owner", { SupabaseSession("token", "owner") },
            { snapshot }, { listOf(friend()) }, { _, _, _, _ -> shares++; plan }, { Instant.parse("2026-10-08T12:00:00Z") })
        failure("no longer available") { repo.send("outing", "friend", operationId) }
        snapshot = OutingPlanSnapshot(plan, false, false)
        failure("Only a scheduled") { repo.send("outing", "friend", operationId) }
        snapshot = OutingPlanSnapshot(plan.copy(endsAt = "2020-01-01T00:00:00Z"), true, false)
        failure("Only a scheduled") { repo.send("outing", "friend", operationId) }
        assertEquals(0, shares)
    }

    @Test fun pendingOrBlockedFriendCannotReceivePlans() = runTest {
        var status = FriendshipStatus.PENDING
        var shares = 0
        val repo = OutingPlansRepository("owner", { SupabaseSession("token", "owner") },
            { OutingPlanSnapshot(plan, true, false) }, { listOf(friend(status)) }, { _, _, _, _ -> shares++; plan })
        failure("accepted friend") { repo.send("outing", "friend", operationId) }
        status = FriendshipStatus.BLOCKED
        failure("accepted friend") { repo.send("outing", "friend", operationId) }
        assertEquals(0, shares)
    }

    @Test fun freshOutingCheckOccursAfterFriendLookup() = runTest {
        var cancelled = false
        val repo = OutingPlansRepository("owner", { SupabaseSession("token", "owner") },
            { OutingPlanSnapshot(plan, !cancelled, false) },
            { cancelled = true; listOf(friend()) }, { _, _, _, _ -> error("Must not send cancelled plans") })
        failure("Only a scheduled") { repo.send("outing", "friend", operationId) }
    }

    @Test fun accountSwitchDuringLookupCannotSendOrExposeOldPlan() = runTest {
        var session: SupabaseSession? = SupabaseSession("token", "owner")
        val repo = OutingPlansRepository("owner", { session }, { OutingPlanSnapshot(plan, true, false) },
            { session = SupabaseSession("other-token", "other"); listOf(friend()) }, { _, _, _, _ -> error("Wrong account") })
        failure("Account changed") { repo.send("outing", "friend", operationId) }
        assertFalse(repo.isActive())
        session = null
        failure("Account changed") { repo.load("outing") }
    }

    @Test fun accountSwitchDuringSnapshotCannotReturnPrivatePlan() = runTest {
        var session: SupabaseSession? = SupabaseSession("token", "owner")
        val repo = OutingPlansRepository("owner", { session },
            { session = null; OutingPlanSnapshot(plan, true, false) }, { emptyList() }, { _, _, _, _ -> plan })
        failure("Account changed") { repo.load("outing") }
    }

    @Test fun failedRecipientCanRetryAndEveryExplicitReshareIsANewCall() = runTest {
        var calls = 0
        val repo = OutingPlansRepository("owner", { SupabaseSession("token", "owner") },
            { OutingPlanSnapshot(plan, true, false) }, { listOf(friend()) }, { token, outing, recipient, _ ->
                assertEquals("token", token); assertEquals("outing", outing); assertEquals("friend", recipient)
                calls++; if (calls == 1) error("Offline"); plan
            })
        failure("Offline") { repo.send("outing", "friend", operationId) }
        repo.send("outing", "friend", operationId); repo.send("outing", "friend", operationId)
        assertEquals(3, calls)
    }

    @Test fun rpcSendsOnlyOutingAndRecipientIdsWithCurrentAuth() = runTest {
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val buffer = okio.Buffer(); request.body?.writeTo(buffer); bodies += buffer.readUtf8()
            val body = if (request.url.encodedPath.endsWith("list_friendships")) """[{"friend_user_id":"friend","status":"accepted","requested_by":"owner","display_name":"Friend"}]""" else receipt
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = SupabaseRestClient("https://unused.invalid", "public-key", http)
        val session = { SupabaseSession("owner-token", "owner") }
        val repo = OutingPlansRepository.create(client, "owner", session, { OutingPlanSnapshot(plan, true, false) }, FriendsRepository(client, session))
        val acknowledged = repo.send("outing", "friend", operationId)
        assertEquals("Delivered Film", acknowledged.title)
        assertEquals("Original cinema", acknowledged.venue)
        assertEquals(CinemaFormat.THREE_D, acknowledged.format)
        assertEquals("/rest/v1/rpc/share_outing_plans", requests.last().url.encodedPath)
        assertEquals("Bearer owner-token", requests.last().header("Authorization"))
        val params = JSONObject(bodies.last())
        assertEquals(setOf("p_outing_id", "p_recipient_ids", "p_operation_id"), params.keys().asSequence().toSet())
        assertEquals("outing", params.getString("p_outing_id"))
        assertEquals("friend", params.getJSONArray("p_recipient_ids").getString(0))
        assertEquals(operationId, params.getString("p_operation_id"))
    }

    @Test fun unknownTransportOutcomeRetainsOperationAndUsesServerReceiptAfterPlanChanges() = runTest {
        var attempts = 0
        var localPlan = plan
        val operations = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath.endsWith("list_friendships")) {
                """[{"friend_user_id":"friend","status":"accepted","requested_by":"owner","display_name":"Friend"}]"""
            } else {
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                operations += JSONObject(buffer.readUtf8()).getString("p_operation_id")
                attempts++
                if (attempts == 1) throw java.io.IOException("Response lost after delivery")
                receipt
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = SupabaseRestClient("https://unused.invalid", "public-key", http)
        val session = { SupabaseSession("owner-token", "owner") }
        val repo = OutingPlansRepository.create(client, "owner", session, { OutingPlanSnapshot(localPlan, true, false) }, FriendsRepository(client, session))
        failure(OutingPlansRepository.UNKNOWN_DELIVERY) { repo.send("outing", "friend", operationId) }
        assertEquals(1, attempts)
        localPlan = plan.copy(venue = "Later edited cinema")
        assertEquals("Original cinema", repo.send("outing", "friend", operationId).venue)
        assertEquals(listOf(operationId, operationId), operations)
    }

    @Test fun receiptParserAcceptsBothFormatSpellingsAndRequiresPublicShape() {
        assertEquals(CinemaFormat.THREE_D, parseSharedOutingReceipt("outing", receipt.replace("3D", "THREE_D")).format)
        assertEquals(CinemaFormat.SEVENTY_MM, parseSharedOutingReceipt("outing", receipt.replace("3D", "70mm")).format)
        try { parseSharedOutingReceipt("outing", "null"); fail("Malformed receipt") } catch (_: org.json.JSONException) { }
        val parsed = parseSharedOutingReceipt("outing", receipt.dropLast(1) + ",\"booking_ref\":\"secret\",\"notes\":\"private\"}")
        assertFalse(parsed.toString().contains("secret"))
        assertFalse(parsed.toString().contains("private"))
    }
}
