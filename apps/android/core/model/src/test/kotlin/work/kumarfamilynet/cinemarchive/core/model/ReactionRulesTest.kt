package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionRulesTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")

    @Test fun reactionsOpenAfterTheShowAndStayClosedWhenCancelled() {
        assertFalse(ReactionRules.isOpen(SharedOutingStatus.SCHEDULED, "2026-11-01T12:00:01Z", now))
        assertTrue(ReactionRules.isOpen(SharedOutingStatus.SCHEDULED, "2026-11-01T12:00:00Z", now))
        assertTrue(ReactionRules.isOpen(SharedOutingStatus.COMPLETED, "2026-11-02T12:00:00Z", now))
        assertFalse(ReactionRules.isOpen(SharedOutingStatus.CANCELLED, "2026-10-01T12:00:00Z", now))
    }

    @Test fun ratingsAreHalfStarsFromHalfToFive() {
        for (ok in listOf(0.5, 1.0, 3.5, 5.0)) assertTrue("$ok", ReactionRules.isValidRating(ok))
        for (bad in listOf(0.0, 0.4, 2.3, 5.5, -1.0)) assertFalse("$bad", ReactionRules.isValidRating(bad))
    }

    @Test fun aReactionNeedsARatingOrANoteWithinTheLimit() {
        assertTrue(ReactionRules.canShare(4.0, null))
        assertTrue(ReactionRules.canShare(null, "Loved it"))
        assertTrue(ReactionRules.canShare(2.5, "  ok  "))
        assertFalse(ReactionRules.canShare(null, null))
        assertFalse(ReactionRules.canShare(null, "   "))
        assertFalse(ReactionRules.canShare(2.3, "ok"))
        assertTrue(ReactionRules.canShare(null, "x".repeat(ReactionRules.MAX_NOTE)))
        assertFalse(ReactionRules.canShare(null, "x".repeat(ReactionRules.MAX_NOTE + 1)))
    }

    @Test fun notesAreTrimmedAndBlankBecomesNull() {
        assertEquals("hi", ReactionRules.cleanNote("  hi "))
        assertNull(ReactionRules.cleanNote("   "))
        assertNull(ReactionRules.cleanNote(null))
    }
}
