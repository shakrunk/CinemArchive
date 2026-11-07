package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedOutingRulesTest {
    private val now = Instant.parse("2026-11-01T12:00:00Z")

    private fun outing(
        id: String, mine: MemberStatus, status: SharedOutingStatus = SharedOutingStatus.SCHEDULED,
        showtime: String = "2026-11-02T19:00:00Z", endsAt: String = "2026-11-02T21:00:00Z",
    ) = SharedOuting(id, status, mine, "owner", "Fran", showtime, endsAt, "Film", 2026, null, "AMC", null, emptyList())

    @Test fun anInvitationCanBeAnsweredOnlyWhileScheduledAndNotEnded() {
        assertTrue(SharedOutingRules.canRespond(outing("a", MemberStatus.INVITED), now))
        assertFalse(SharedOutingRules.canRespond(outing("a", MemberStatus.INVITED, SharedOutingStatus.CANCELLED), now))
        assertFalse(SharedOutingRules.canRespond(outing("a", MemberStatus.INVITED, SharedOutingStatus.COMPLETED), now))
        assertFalse(SharedOutingRules.canRespond(outing("a", MemberStatus.INVITED, endsAt = "2026-11-01T11:59:59Z"), now))
    }

    @Test fun invitationsAreSoonestFirstAndDropStaleOnes() {
        val list = listOf(
            outing("late", MemberStatus.INVITED, showtime = "2026-11-05T19:00:00Z"),
            outing("soon", MemberStatus.INVITED, showtime = "2026-11-02T19:00:00Z"),
            outing("cancelled", MemberStatus.INVITED, SharedOutingStatus.CANCELLED),
            outing("going", MemberStatus.ACCEPTED),
        )
        assertEquals(listOf("soon", "late"), SharedOutingRules.invitations(list, now).map { it.id })
    }

    @Test fun goingKeepsACancelledOutingSoTheChangeIsNotSilent() {
        val list = listOf(
            outing("b", MemberStatus.ACCEPTED, showtime = "2026-11-05T19:00:00Z"),
            outing("a", MemberStatus.ACCEPTED, SharedOutingStatus.CANCELLED, showtime = "2026-11-02T19:00:00Z"),
            outing("pending", MemberStatus.INVITED),
        )
        assertEquals(listOf("a", "b"), SharedOutingRules.going(list).map { it.id })
    }
}
