package work.kumarfamilynet.cinemarchive.data

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserFacingErrorTest {
    @Test fun networkFailuresNeverLeakSocketText() {
        val failures = listOf(
            SocketException("Software caused connection abort"),
            UnknownHostException("Unable to resolve host \"x.supabase.co\""),
            SocketTimeoutException("timeout"),
            IOException("unexpected end of stream"),
        )
        for (failure in failures) assertEquals(OFFLINE_MESSAGE, failure.userMessage("Couldn't save."))
    }

    @Test fun serverErrorsAreClassifiedByStatus() {
        fun http(status: Int, body: String = "") = SupabaseHttpException(status, body, "GET https://x.supabase.co/rest failed")
        assertEquals("Your session has expired. Sign in again to continue.", http(401).userMessage("f"))
        assertEquals("Too many requests. Wait a moment and try again.", http(429).userMessage("f"))
        assertEquals(OFFLINE_MESSAGE, http(503).userMessage("f"))
        assertEquals("CinemArchive is having trouble right now. Try again in a moment.", http(500).userMessage("f"))
        assertEquals("Couldn't save.", http(400, """{"code":"23505","message":"duplicate key value"}""").userMessage("Couldn't save."))
        assertFalse(http(500).userMessage("f").contains("supabase"))
    }

    @Test fun deliberateServerMessagesAreKept() {
        val raised = SupabaseHttpException(400, """{"code":"P0001","message":"You've used both of your invites."}""", "POST failed")
        assertEquals("You've used both of your invites.", raised.userMessage("f"))
    }

    @Test fun appAuthoredMessagesSurviveButDeveloperTextDoesNot() {
        assertEquals("Not signed in.", IllegalStateException("Not signed in.").userMessage("f"))
        assertEquals("f", IllegalStateException("PATCH https://x/rest failed: HTTP 400").userMessage("f"))
        assertEquals("f", IllegalArgumentException("   ").userMessage("f"))
    }

    @Test fun unknownAndParseFailuresFallBack() {
        assertEquals("f", NullPointerException("boom").userMessage("f"))
        assertEquals("f", RuntimeException("SQLiteConstraintException: UNIQUE").userMessage("f"))
        assertEquals("Got an unexpected response from the server. Try again in a moment.", JSONException("bad").userMessage("f"))
    }

    @Test fun cancellationIsRethrownNotReported() {
        try { kotlinx.coroutines.CancellationException("left screen").rethrowIfCancelled(); org.junit.Assert.fail() } catch (_: kotlinx.coroutines.CancellationException) {}
        IllegalStateException("x").rethrowIfCancelled()
    }
}
