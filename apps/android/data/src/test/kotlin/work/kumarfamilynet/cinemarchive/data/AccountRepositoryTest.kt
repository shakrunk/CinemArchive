package work.kumarfamilynet.cinemarchive.data

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Records every request and answers from a scripted queue — exercises the real
 *  [SupabaseRestClient] + repositories over OkHttp without any network. */
private class ScriptedHttp {
    val requests = mutableListOf<Request>()
    val bodies = mutableListOf<String>()
    private val responses = ArrayDeque<Pair<Int, String>>()

    fun reply(status: Int, body: String) = responses.addLast(status to body)

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        requests += request
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        bodies += buffer.readUtf8()
        val (status, body) = responses.removeFirst()
        Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(status).message("m")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }).build()
}

private fun session(userId: String = "user-a") =
    SupabaseSession(accessToken = "tok-$userId", userId = userId, email = "$userId@example.com")

private fun profileJson(userId: String = "user-a", name: String? = "Norma", username: String? = "norma-d", owner: Boolean = false) =
    JSONObject()
        .put("user_id", userId).put("email", "$userId@example.com")
        .put("username", username ?: JSONObject.NULL).put("display_name", name ?: JSONObject.NULL)
        .put("created_at", "2026-01-01T00:00:00Z").put("is_owner", owner)

class ProfileRulesTest {
    @Test fun trimsAndLowercasesAndNullsBlanks() {
        val v = ProfileRules.validate("  Norma Desmond ", "  Norma-D ") as ProfileEdit.Valid
        assertEquals("Norma Desmond", v.displayName)
        assertEquals("norma-d", v.username)
        val blank = ProfileRules.validate("   ", "") as ProfileEdit.Valid
        assertNull(blank.displayName)
        assertNull(blank.username)
    }

    @Test fun rejectsMalformedUsernames() {
        listOf("ab", "-abc", "abc-", "a".repeat(25), "has space", "bad!").forEach {
            assertTrue("$it should be invalid", ProfileRules.validate("x", it) is ProfileEdit.Invalid)
        }
        listOf("abc", "a_b-c", "a".repeat(24)).forEach {
            assertTrue("$it should be valid", ProfileRules.validate("x", it) is ProfileEdit.Valid)
        }
    }

    @Test fun displayNameCappedAt60() {
        val v = ProfileRules.validate("x".repeat(80), "") as ProfileEdit.Valid
        assertEquals(60, v.displayName!!.length)
    }
}

class InviteRulesTest {
    private fun code(redeemed: Boolean) = InviteCode("i", "ABCD1234", "t", if (redeemed) "u" else null, null)

    @Test fun capAppliesToNonOwnersIncludingRedeemed() {
        assertTrue(InviteRules.atCap(false, listOf(code(true), code(false))))
        assertFalse(InviteRules.atCap(false, listOf(code(false))))
        assertFalse(InviteRules.atCap(true, listOf(code(true), code(false), code(false))))
    }

    @Test fun newCodeIsEightUppercaseChars() {
        assertEquals("AB12CD34", InviteRules.newCode { "ab12cd34-ef56-7890-aaaa-bbbbbbbbbbbb" })
    }
}

class AccountRepositoryTest {
    private fun repo(http: ScriptedHttp, s: SupabaseSession? = session()) =
        AccountRepository(SupabaseRestClient("https://x.supabase.co", "anon", http.client)) { s }

    @Test fun refreshProfileFiltersByOwnUserIdAndCaches() = runTest {
        val http = ScriptedHttp().apply { reply(200, "[" + profileJson(owner = true) + "]") }
        val repo = repo(http)
        val p = repo.refreshProfile()!!
        assertEquals("Norma", p.displayName)
        assertTrue(p.isOwner)
        assertTrue(http.requests[0].url.toString().contains("user_id=eq.user-a"))
        assertEquals("Bearer tok-user-a", http.requests[0].header("Authorization"))
        assertEquals(p, repo.profile.value)
    }

    @Test fun cachedProfileDroppedWhenSessionUserChanges() = runTest {
        val http = ScriptedHttp().apply { reply(200, "[" + profileJson() + "]") }
        val repo = repo(http)
        repo.refreshProfile()
        repo.onSessionChanged("user-a")
        assertEquals("Norma", repo.profile.value?.displayName)
        repo.onSessionChanged("user-b")
        assertNull(repo.profile.value)
    }

    @Test fun updateProfileSendsNullsAndMapsTakenUsername() = runTest {
        val http = ScriptedHttp().apply {
            reply(200, "[" + profileJson(name = null, username = null) + "]")
            reply(409, """{"code":"23505","message":"duplicate key"}""")
        }
        val repo = repo(http)
        val updated = repo.updateProfile(ProfileEdit.Valid(null, null))
        assertNull(updated.displayName)
        val sent = JSONObject(http.bodies[0])
        assertTrue(sent.isNull("display_name") && sent.isNull("username"))
        assertEquals("PATCH", http.requests[0].method)
        try {
            repo.updateProfile(ProfileEdit.Valid("A", "taken"))
            fail()
        } catch (e: IllegalStateException) {
            assertEquals("That username is already taken.", e.message)
        }
    }

    @Test fun createInviteMapsRlsDenialAndCollision() = runTest {
        val http = ScriptedHttp().apply {
            reply(201, """[{"id":"1","code":"AAAAAAAA","created_at":"t","redeemed_by":null,"redeemed_at":null}]""")
            reply(403, """{"code":"42501","message":"new row violates row-level security"}""")
            reply(409, """{"code":"23505","message":"dup"}""")
        }
        val repo = repo(http)
        assertEquals("AAAAAAAA", repo.createInviteCode().code)
        val body = JSONObject(http.bodies[0])
        assertEquals("user-a", body.getString("created_by"))
        assertEquals(8, body.getString("code").length)
        try { repo.createInviteCode(); fail() } catch (e: IllegalStateException) { assertEquals("You've used both of your invites.", e.message) }
        try { repo.createInviteCode(); fail() } catch (e: IllegalStateException) { assertEquals("That code collided — please try again.", e.message) }
    }

    @Test fun deleteInviteOnlyTargetsOwnUnredeemedRow() = runTest {
        val http = ScriptedHttp().apply { reply(204, "") }
        repo(http).deleteInviteCode("inv-1")
        val url = http.requests[0].url.toString()
        assertEquals("DELETE", http.requests[0].method)
        assertTrue(url.contains("id=eq.inv-1") && url.contains("created_by=eq.user-a") && url.contains("redeemed_by=is.null"))
    }

    @Test fun signedOutCallsFail() = runTest {
        try { repo(ScriptedHttp(), null).listInviteCodes(); fail() } catch (e: IllegalStateException) { assertEquals("Not signed in.", e.message) }
    }
}

class NotificationsRepositoryTest {
    private fun row(id: String, type: String = "comment_received", read: String? = null) =
        JSONObject().put("id", id).put("type", type).put("actor_id", "f").put("actor_display_name", "Fran")
            .put("actor_username", JSONObject.NULL).put("title_id", "title-$id").put("tmdb_id", 5)
            .put("media_type", "movie").put("title", "Heat").put("poster_url", JSONObject.NULL)
            .put("payload", JSONObject().put("emoji", "❤️")).put("created_at", "2026-02-0${id.last()}T00:00:00Z")
            .put("read_at", read ?: JSONObject.NULL)

    private fun repo(http: ScriptedHttp, s: SupabaseSession? = session()) =
        NotificationsRepository(SupabaseRestClient("https://x.supabase.co", "anon", http.client)) { s }

    @Test fun loadParsesPageAndRefreshesUnreadCount() = runTest {
        val http = ScriptedHttp().apply {
            reply(200, "[" + row("n1") + "," + row("n2", read = "2026-02-03T00:00:00Z") + "]")
            reply(200, "1")
        }
        val repo = repo(http)
        repo.load()
        val inbox = repo.inbox.value
        assertEquals(2, inbox.items.size)
        assertEquals(1, inbox.unreadCount)
        assertFalse(inbox.hasMore)
        assertTrue(http.requests[0].url.toString().endsWith("/rpc/list_notifications"))
        assertEquals(30, JSONObject(http.bodies[0]).getInt("p_limit"))
        assertTrue(JSONObject(http.bodies[0]).isNull("p_before"))
    }

    @Test fun markReadIsOptimisticAndRollsBackOnFailure() = runTest {
        val http = ScriptedHttp().apply {
            reply(200, "[" + row("n1") + "]"); reply(200, "1")
            reply(500, "boom"); reply(200, "1")
        }
        val repo = repo(http)
        repo.load()
        repo.markRead("n1")
        assertTrue("rolled back to unread", repo.inbox.value.items.single().isUnread)
        assertEquals(1, repo.inbox.value.unreadCount)
    }

    @Test fun markReadThenDeleteAdjustUnread() = runTest {
        val http = ScriptedHttp().apply {
            reply(200, "[" + row("n1") + "," + row("n2") + "]"); reply(200, "2")
            reply(200, ""); reply(204, "")
        }
        val repo = repo(http)
        repo.load()
        repo.markRead("n1")
        assertEquals(1, repo.inbox.value.unreadCount)
        repo.delete("n2")
        assertEquals(0, repo.inbox.value.unreadCount)
        assertEquals(listOf("n1"), repo.inbox.value.items.map { it.id })
        val del = http.requests.last().url.toString()
        assertTrue(del.contains("id=eq.n2") && del.contains("recipient_id=eq.user-a"))
    }

    @Test fun inboxClearedWhenUserChanges() = runTest {
        val http = ScriptedHttp().apply { reply(200, "[" + row("n1") + "]"); reply(200, "1") }
        val repo = repo(http)
        repo.load()
        repo.onSessionChanged("user-b")
        assertTrue(repo.inbox.value.items.isEmpty())
        assertEquals(0, repo.inbox.value.unreadCount)
    }

    @Test fun routingAndDescriptionsFollowWeb() {
        val n = parseNotifications("[" + row("n1") + "]").single()
        assertEquals(NotificationRoute.TitleDetail("title-n1"), NotificationRules.route(n))
        assertEquals("Fran commented on \"Heat\"", NotificationRules.describe(n))
        val reaction = parseNotifications("[" + row("n2", type = "reaction_received") + "]").single()
        assertEquals("Fran reacted ❤️ to \"Heat\"", NotificationRules.describe(reaction))
        val invite = parseNotifications("[" + row("n3", type = "invite_redeemed") + "]").single()
        assertEquals(NotificationRoute.Profile, NotificationRules.route(invite))
        val friend = parseNotifications("[" + row("n4", type = "friend_request_received") + "]").single()
        assertEquals(NotificationRoute.Friends, NotificationRules.route(friend))
        val unknown = parseNotifications("[" + row("n5", type = "future_type") + "]").single()
        assertEquals(NotificationType.UNKNOWN, unknown.type)
    }
}
