package work.kumarfamilynet.cinemarchive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MoviegoingRegionTest {
    @Test fun validIsExactlyTwoUppercaseAsciiLetters() {
        assertTrue(MoviegoingRegion.isValid("US"))
        listOf(null, "", "u", "us", "USA", "U1", "Ü!", "U S").forEach { assertFalse(it.toString(), MoviegoingRegion.isValid(it)) }
    }

    @Test fun normalizeTrimsAndUppercasesButNeverInvents() {
        assertEquals("GB", MoviegoingRegion.normalize(" gb "))
        assertNull(MoviegoingRegion.normalize("great britain"))
        assertNull(MoviegoingRegion.normalize(null))
    }

    @Test fun effectivePrefersTheSavedRegionThenTheLocaleThenTheFallback() {
        assertEquals("CA", MoviegoingRegion.effective("CA", Locale("fr", "FR")))
        assertEquals("FR", MoviegoingRegion.effective(null, Locale("fr", "FR")))
        assertEquals("FR", MoviegoingRegion.effective("bogus", Locale("fr", "FR")))
        assertEquals(MoviegoingRegion.FALLBACK, MoviegoingRegion.effective(null, Locale("fr")))
    }
}
