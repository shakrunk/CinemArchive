package work.kumarfamilynet.cinemarchive.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupabaseHttpExceptionTest {
    @Test fun publicConflictUsesCanonicalRejectionCodeWithoutChangingRawEvidence() {
        val body = """{"code":"PT409","message":"Revision changed","details":"Original evidence"}"""
        val error = SupabaseHttpException(409, body, "RPC failed")

        assertEquals("40001", error.postgresCode)
        assertEquals(body, error.responseBody)
        assertEquals(409, error.status)
        assertEquals("RPC failed", error.message)
    }

    @Test fun existingDatabaseAndHttpCodesKeepTheirIdentity() {
        for (code in listOf("40001", "23505", "42501", "23503", "PT400", "PT404", "PGRST116")) {
            val error = SupabaseHttpException(409, """{"code":"$code"}""", "RPC failed")
            assertEquals(code, error.postgresCode)
        }
    }

    @Test fun absentOrMalformedCodesDoNotBecomeBusinessConflicts() {
        for (body in listOf("", "Gateway timeout", "{}", """{"code":""}""")) {
            assertNull(SupabaseHttpException(502, body, "RPC failed").postgresCode)
        }
    }
}
