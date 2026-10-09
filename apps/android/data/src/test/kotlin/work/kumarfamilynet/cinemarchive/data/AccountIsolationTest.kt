package work.kumarfamilynet.cinemarchive.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import work.kumarfamilynet.cinemarchive.core.database.OutboxEntity

private class MemorySessionStore(var stored: SupabaseSession? = null) : SessionStore {
    override fun read() = stored
    override fun write(session: SupabaseSession) { stored = session }
    override fun clear() { stored = null }
}

/** HTTP stub whose refresh-token endpoint can be held open to simulate a slow network. */
private class RefreshHttp(private val gate: CountDownLatch? = null) {
    val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
    val refreshCalls = AtomicInteger()
    val inRefresh = CountDownLatch(1)

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        requests += request
        if (request.url.toString().contains("grant_type=refresh_token")) {
            refreshCalls.incrementAndGet()
            inRefresh.countDown()
            gate?.await(10, TimeUnit.SECONDS)
        }
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body(
                """{"access_token":"fresh","refresh_token":"rotated","expires_in":3600,"user":{"id":"user-a","email":"a@x"}}"""
                    .toResponseBody("application/json".toMediaType()),
            )
            .build()
    }).build()
}

private fun expired(userId: String, refresh: String = "r1") =
    SupabaseSession(accessToken = "old-$userId", userId = userId, refreshToken = refresh, expiresAt = 1L, email = "$userId@x")

private fun live(userId: String) =
    SupabaseSession(accessToken = "tok-$userId", userId = userId, refreshToken = "r-$userId", expiresAt = Long.MAX_VALUE / 2, email = "$userId@x")

class AuthRepositoryConcurrencyTest {
    private fun auth(http: RefreshHttp, store: SessionStore) =
        AuthRepository(store, SupabaseRestClient("https://x.supabase.co", "anon", http.client))

    @Test fun parallelRefreshesAreSingleFlight() {
        val http = RefreshHttp()
        val auth = auth(http, MemorySessionStore(expired("user-a")))
        val results = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val threads = (1..8).map { thread { results += auth.currentSession()?.accessToken } }
        threads.forEach { it.join(10_000) }
        assertEquals("exactly one network refresh", 1, http.refreshCalls.get())
        assertEquals(List(8) { "fresh" }, results)
    }

    @Test fun refreshFinishingAfterSignOutDoesNotResurrectSession() {
        val gate = CountDownLatch(1)
        val http = RefreshHttp(gate)
        val store = MemorySessionStore(expired("user-a"))
        val auth = auth(http, store)
        var result: SupabaseSession? = SupabaseSession("sentinel", "x")
        val refresher = thread { result = auth.currentSession() }
        assertTrue(http.inRefresh.await(10, TimeUnit.SECONDS))

        // Sign-out must take effect immediately — NOT wait behind the in-flight refresh.
        val start = System.nanoTime()
        auth.signOut()
        assertTrue("signOut waited on refresh", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2))
        assertNull(auth.observeSession().value)

        gate.countDown()
        refresher.join(10_000)
        assertNull("refresh result must be discarded", auth.observeSession().value)
        assertNull(store.stored)
        assertNull(auth.observeIdentity().value)
    }

    @Test fun refreshInFlightForOldSignInIsDiscardedWhenSameUserSignsInAgain() {
        val gate = CountDownLatch(1)
        val http = RefreshHttp(gate)
        val store = MemorySessionStore(expired("user-a"))
        val auth = auth(http, store)
        val refresher = thread { auth.currentSession() }
        assertTrue(http.inRefresh.await(10, TimeUnit.SECONDS))
        auth.adoptSession(live("user-a")) // a brand-new sign-in of the same user
        gate.countDown()
        refresher.join(10_000)
        assertEquals("tok-user-a", auth.observeSession().value?.accessToken)
        assertEquals("tok-user-a", store.stored?.accessToken)
    }

    @Test fun identityChangesOnEverySignInAndSignOutButNotOnRefresh() {
        val http = RefreshHttp()
        val auth = auth(http, MemorySessionStore(null))
        assertNull(auth.observeIdentity().value)
        auth.adoptSession(live("user-a"))
        val a1 = auth.observeIdentity().value!!
        auth.adoptSession(live("user-b"))
        val b = auth.observeIdentity().value!!
        auth.adoptSession(live("user-a"))
        val a2 = auth.observeIdentity().value!!
        assertEquals("user-a", a1.userId)
        assertEquals("user-a", a2.userId)
        assertNotEquals("A→B→A must not look like the first A", a1, a2)
        assertTrue(a1.generation < b.generation && b.generation < a2.generation)
        auth.signOut()
        assertNull(auth.observeIdentity().value)

        auth.adoptSession(expired("user-a"))
        val before = auth.observeIdentity().value
        auth.currentSession() // refresh
        assertEquals("refresh does not change identity", before, auth.observeIdentity().value)
    }

    @Test fun fencedSourceNeverReturnsAnotherSignInsSession() {
        val http = RefreshHttp()
        val auth = auth(http, MemorySessionStore(null))
        auth.adoptSession(live("user-a"))
        val fencedA = FencedSessionSource(auth, auth.observeIdentity().value!!)
        assertEquals("tok-user-a", fencedA.currentSession()?.accessToken)

        auth.adoptSession(live("user-b"))
        assertNull(fencedA.currentSession())

        auth.adoptSession(live("user-a")) // A again — a NEW sign-in
        assertNull("old A fence stays dead", fencedA.currentSession())
        assertTrue(http.requests.isEmpty())
    }

    @Test fun queuedWriteOfAIsNeverPushedUnderBsToken() {
        val http = RefreshHttp()
        val auth = auth(http, MemorySessionStore(null))
        auth.adoptSession(live("user-a"))
        val fencedA = FencedSessionSource(auth, auth.observeIdentity().value!!)
        val writer = SupabaseRemoteMutationWriter(SupabaseRestClient("https://x.supabase.co", "anon", http.client)) {
            fencedA.currentSession() ?: error("Not signed in")
        }
        val entry = OutboxEntity(
            id = "o1", entityType = "title", entityId = "t1", operation = "upsert",
            payloadJson = """{"id":"t1","title":"Heat"}""", createdAt = 1L,
        )

        auth.adoptSession(live("user-b"))
        val result = runBlocking { writer.push(entry) }

        assertTrue("must be retryable, not success: $result", result is PushResult.Retry)
        assertTrue("no request may carry any token", http.requests.isEmpty())
    }
}

private class FakeRuntime(override val identity: AuthIdentity, private val log: MutableList<String>) : RuntimeHandle {
    var closed = false
    override suspend fun close() {
        if (closed) return
        closed = true
        log += "close ${identity.userId}#${identity.generation}"
    }
}

class AccountRuntimeManagerTest {
    @Test fun oldRuntimeIsClosedBeforeNextIsBuiltAndAToBToAGivesThreeRuntimes() = runBlocking {
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val identity = MutableStateFlow<AuthIdentity?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = AccountRuntimeManager(identity, scope) { id ->
            log += "build ${id.userId}#${id.generation}"
            FakeRuntime(id, log)
        }

        val a1 = AuthIdentity("user-a", 1)
        val b = AuthIdentity("user-b", 2)
        val a2 = AuthIdentity("user-a", 3)
        for (next in listOf(a1, b, a2, null)) {
            identity.value = next
            manager.reconcile()
            assertEquals(next, manager.runtime.value?.identity)
        }

        assertEquals(
            listOf(
                "build user-a#1", "close user-a#1", "build user-b#2", "close user-b#2",
                "build user-a#3", "close user-a#3",
            ),
            log.toList(),
        )
        scope.cancel()
    }

    @Test fun runtimeIsClearedBeforeOldOneFinishesClosing() = runBlocking {
        val observedDuringClose = CompletableDeferred<Any?>()
        val identity = MutableStateFlow<AuthIdentity?>(AuthIdentity("user-a", 1))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var manager: AccountRuntimeManager<RuntimeHandle>
        manager = AccountRuntimeManager(identity, scope) { id ->
            object : RuntimeHandle {
                override val identity = id
                override suspend fun close() { observedDuringClose.complete(manager.runtime.value) }
            }
        }
        manager.reconcile()
        assertNotNull(manager.runtime.value)
        identity.value = null
        manager.reconcile()
        assertNull("UI must stop seeing the runtime before it is closed", withTimeout(5000) { observedDuringClose.await() })
        scope.cancel()
    }

    @Test fun runtimeBuiltForADeadSignInIsClosedAndNeverPublished() = runBlocking {
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val identity = MutableStateFlow<AuthIdentity?>(AuthIdentity("user-a", 1))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = AccountRuntimeManager(identity, scope) { id ->
            identity.value = null // signs out while the runtime is being built
            FakeRuntime(id, log)
        }
        manager.reconcile()
        assertNull(manager.runtime.value)
        assertEquals(listOf("close user-a#1"), log.toList())
        scope.cancel()
    }
}

class OwnerNamespaceTest {
    @Test fun keysAreDeterministicDistinctAndFilesystemSafe() {
        val a = OwnerNamespace.key("3f1c0a52-aaaa-4bbb-8ccc-000000000001")
        val b = OwnerNamespace.key("3f1c0a52-aaaa-4bbb-8ccc-000000000002")
        assertEquals(a, OwnerNamespace.key("3f1c0a52-aaaa-4bbb-8ccc-000000000001"))
        assertNotEquals(a, b)
        assertTrue(Regex("^[0-9a-f]{16}$").matches(a))
        assertFalse(OwnerNamespace.databaseName("u").contains("u.db"))
        assertNotEquals("legacy global file must never be an owner database", "cinemarchive.db", OwnerNamespace.databaseName("u"))
        assertEquals("cinemarchive_sync_$a".length, OwnerNamespace.dataStoreName("cinemarchive_sync", "3f1c0a52-aaaa-4bbb-8ccc-000000000001").length)
    }
}

class VisibleRuntimeTest {
    private fun runtime(id: AuthIdentity) = FakeRuntime(id, mutableListOf())

    @Test fun laggingManagerNeverExposesThePreviousAccountsRuntime() = runBlocking {
        val identity = MutableStateFlow<AuthIdentity?>(AuthIdentity("user-a", 1))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = AccountRuntimeManager(identity, scope) { runtime(it) }
        manager.reconcile()
        val a1 = manager.runtime.value
        assertEquals(a1, visibleRuntime(identity.value, a1))

        // Auth adopts B, but the manager has not reconciled yet — A's runtime is still published.
        identity.value = AuthIdentity("user-b", 2)
        assertEquals(a1, manager.runtime.value)
        assertNull("A must not render under B's sign-in", visibleRuntime(identity.value, manager.runtime.value))

        // A→B→A while the manager still lags on the FIRST A: same user id, older generation.
        identity.value = AuthIdentity("user-a", 3)
        assertEquals("user-a", manager.runtime.value?.identity?.userId)
        assertNull("old A must not render under the new A sign-in", visibleRuntime(identity.value, manager.runtime.value))

        manager.reconcile()
        assertEquals(manager.runtime.value, visibleRuntime(identity.value, manager.runtime.value))
        assertEquals(3L, manager.runtime.value?.identity?.generation)
        assertNull(visibleRuntime(null, manager.runtime.value))
        scope.cancel()
    }
}

class PullProtectionTest {
    private fun row(type: String, id: String, tombstoneOf: String? = null) =
        org.json.JSONObject().put("entity_type", type).put("entity_id", id)
            .put("payload", org.json.JSONObject().apply { if (tombstoneOf != null) put("entityType", tombstoneOf) })

    @Test fun pendingEditsAndDeletesAreNeverOverwrittenOrResurrectedByAPull() {
        val pending = setOf(
            pendingKey("title", "t-edited"),       // offline status edit
            pendingKey("title", "t-deleted"),      // offline delete (remote still has the row)
            pendingKey("list_item", "li-1"),
            pendingKey("episode_metadata", "ep-1"), // patches an `episode` row
        )
        assertTrue(isProtectedFromPull(row("title", "t-edited"), pending))
        assertTrue("remote upsert must not resurrect an offline-deleted title", isProtectedFromPull(row("title", "t-deleted"), pending))
        assertTrue(isProtectedFromPull(row("list_item", "li-1"), pending))
        assertTrue(isProtectedFromPull(row("episode", "ep-1"), pending))
        assertTrue("remote tombstone must not drop an unpushed local edit", isProtectedFromPull(row("tombstone", "t-edited", "title"), pending))

        assertFalse(isProtectedFromPull(row("title", "other"), pending))
        assertFalse(isProtectedFromPull(row("viewing", "t-edited"), pending))
        assertFalse(isProtectedFromPull(row("tombstone", "t-edited", "viewing"), pending))
        assertFalse(isProtectedFromPull(row("title", "t-edited"), emptySet()))
    }
}

class UiAttachmentGateTest {
    @Test fun closeWaitsForTheCurrentAttachmentNotJustTheFirstDetach() = runBlocking {
        val gate = UiAttachmentGate()
        assertTrue("never attached: nothing to wait for", gate.awaitDetached(50))

        gate.attach()
        gate.detach()      // rotation: first composition leaves…
        gate.attach()      // …and a new one attaches before close begins
        assertFalse("still attached: must NOT report detached", gate.awaitDetached(100))

        val waiter = async(Dispatchers.Default) { gate.awaitDetached(5_000) }
        delay(50)
        gate.detach()
        assertTrue(waiter.await())
        assertEquals(0, gate.attachedCount)
        gate.detach() // extra detach never goes negative
        assertEquals(0, gate.attachedCount)
    }
}

class InviteRedemptionTest {
    @Test fun refusalFromTheEdgeFunctionBecomesTheErrorMessageAndSuccessPasses() {
        parseRedeemInviteResponse("""{"success":true}""")
        listOf("Invalid invite code.", "This invite code has already been used.", "Too many attempts — please try again later.",
            "An account with this email already exists — sign in instead.").forEach { msg ->
            try {
                parseRedeemInviteResponse("""{"error":${org.json.JSONObject.quote(msg)}}""")
                org.junit.Assert.fail("should refuse: $msg")
            } catch (e: IllegalStateException) {
                assertEquals(msg, e.message)
            }
        }
        try { parseRedeemInviteResponse("<html>"); org.junit.Assert.fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Unexpected"))
        }
    }

    @Test fun redeemPostsEmailAndCodeThenSendsTheMagicLinkOnlyOnSuccess() {
        val http = RefreshHttp()
        val auth = AuthRepository(MemorySessionStore(null), SupabaseRestClient("https://x.supabase.co", "anon", http.client))
        // RefreshHttp answers every call with a session-shaped JSON (no "error" key): redemption passes,
        // and the follow-up OTP request goes out.
        auth.redeemInviteAndSendLink("  Me@X.com ", " abcd1234 ")
        val urls = http.requests.map { it.url.toString() }
        assertTrue(urls[0].endsWith("/functions/v1/redeem-invite"))
        assertTrue(urls[1].contains("/auth/v1/otp"))
        assertEquals("Bearer anon", http.requests[0].header("Authorization"))
    }
}
