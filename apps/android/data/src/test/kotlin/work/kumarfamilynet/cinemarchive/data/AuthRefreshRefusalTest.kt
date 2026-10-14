package work.kumarfamilynet.cinemarchive.data

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AuthRefreshRefusalTest {
    private class Store(var session: SupabaseSession?) : SessionStore {
        override fun read() = session
        override fun write(session: SupabaseSession) { this.session = session }
        override fun clear() { session = null }
    }

    private val expired = SupabaseSession("old", "owner", "refresh", 1)
    private fun repository(store: Store, response: () -> Pair<Int, String>): AuthRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = response()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody()).build()
        }.build()
        return AuthRepository(store, SupabaseRestClient("https://example.test", "anon", http))
    }

    @Test fun permanentRefusalEndsIdentityClearsCredentialsAndRequestsSignIn() {
        listOf("refresh_token_not_found", "refresh_token_already_used", "session_not_found",
            "session_expired", "user_not_found", "user_banned").forEach { code ->
            val store = Store(expired)
            val auth = repository(store) { 400 to """{"error_code":"$code","msg":"refused"}""" }
            val oldIdentity = auth.observeIdentity().value!!
            assertNull(code, auth.currentSession())
            assertNull(store.session)
            assertNull(auth.observeIdentity().value)
            assertTrue(auth.observeReauthenticationRequired().value)
            assertNull(auth.sessionFor(oldIdentity))
            auth.adoptSession(expired.copy(accessToken = "new", expiresAt = Long.MAX_VALUE / 2))
            assertFalse(auth.observeReauthenticationRequired().value)
            assertNotEquals(oldIdentity, auth.observeIdentity().value)
        }
    }

    @Test fun transientAndUnclassifiedFailuresPreserveOfflineSessionAndIdentity() {
        listOf(400 to "bad_json", 400 to "conflict", 401 to "bad_jwt", 429 to "refresh_token_not_found",
            503 to "session_expired", 500 to "unexpected_failure", 400 to "").forEach { (status, code) ->
            val store = Store(expired)
            val auth = repository(store) { status to """{"error_code":"$code"}""" }
            val identity = auth.observeIdentity().value
            assertEquals(expired, auth.currentSession())
            assertEquals(expired, store.session)
            assertEquals(identity, auth.observeIdentity().value)
            assertFalse(auth.observeReauthenticationRequired().value)
        }
        val auth = repository(Store(expired)) { throw IOException("offline") }
        assertEquals(expired, auth.currentSession())
        assertFalse(auth.observeReauthenticationRequired().value)
    }

    @Test fun cancellationIsNotConvertedIntoOfflineFallback() {
        val cancelled = CancellationException("cancelled")
        val auth = repository(Store(expired)) { throw cancelled }
        try { auth.currentSession(); fail("Expected cancellation") } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertEquals(expired, auth.observeSession().value)
        assertFalse(auth.observeReauthenticationRequired().value)
    }

    @Test fun lateRefusalCannotInvalidateNewSignInIncludingSameOwnerAndToken() {
        listOf("owner", "other").forEach { owner ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val store = Store(expired)
            val auth = repository(store) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                400 to """{"error_code":"refresh_token_not_found"}"""
            }
            val worker = thread { auth.currentSession() }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val replacement = expired.copy(userId = owner, accessToken = "new", expiresAt = Long.MAX_VALUE / 2)
                auth.adoptSession(replacement)
                val identity = auth.observeIdentity().value
                release.countDown()
                worker.join(10_000)
                assertFalse(worker.isAlive)
                assertEquals(replacement, store.session)
                assertEquals(identity, auth.observeIdentity().value)
                assertFalse(auth.observeReauthenticationRequired().value)
            } finally { release.countDown(); worker.join(10_000) }
        }
    }

    @Test fun explicitSignOutClearsExpiryReason() {
        val auth = repository(Store(expired)) { 400 to """{"error_code":"session_expired"}""" }
        auth.currentSession()
        auth.signOut()
        assertFalse(auth.observeReauthenticationRequired().value)
    }

    @Test fun versionedAuthCodeAlsoRequestsSignIn() {
        val auth = repository(Store(expired)) { 400 to """{"code":"refresh_token_not_found","message":"refused"}""" }
        assertNull(auth.currentSession())
        assertTrue(auth.observeReauthenticationRequired().value)
    }

    @Test fun lateRefusalAfterSignOutDoesNotResurrectExpiryNotice() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val auth = repository(Store(expired)) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            400 to """{"error_code":"refresh_token_already_used"}"""
        }
        val worker = thread { auth.currentSession() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            auth.signOut()
            release.countDown()
            worker.join(10_000)
            assertFalse(worker.isAlive)
            assertNull(auth.observeSession().value)
            assertFalse(auth.observeReauthenticationRequired().value)
        } finally { release.countDown(); worker.join(10_000) }
    }
}
