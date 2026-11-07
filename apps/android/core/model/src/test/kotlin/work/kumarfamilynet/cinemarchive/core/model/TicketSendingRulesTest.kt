package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TicketSendingRulesTest {
    private val now = Instant.parse("2026-11-02T12:00:00Z")

    private fun ticket(id: String, showtime: String, endsAt: String) =
        ReceivedTicket(id, "s", "Fran", "Film $id", 2026, null, showtime, endsAt, null, "2026-11-01T10:00:00Z", null)

    @Test fun listsUpcomingAndJustEndedShowsSoonestFirstAndDropsOldOnes() {
        val list = listOf(
            ticket("late", "2026-11-05T19:00:00Z", "2026-11-05T21:00:00Z"),
            ticket("soon", "2026-11-03T19:00:00Z", "2026-11-03T21:00:00Z"),
            ticket("justEnded", "2026-11-02T08:00:00Z", "2026-11-02T10:00:00Z"),
            ticket("old", "2026-10-30T19:00:00Z", "2026-10-30T21:00:00Z"),
        )
        assertEquals(listOf("justEnded", "soon", "late"), TicketSendingRules.ordered(list, now).map { it.grantId })
    }

    @Test fun aShowThatEndedExactlyADayAgoIsStillListedUntilThePastTheDay() {
        assertEquals(true, TicketSendingRules.isCurrent(ticket("a", "2026-11-01T09:00:00Z", "2026-11-01T12:00:01Z"), now))
        assertEquals(false, TicketSendingRules.isCurrent(ticket("b", "2026-11-01T09:00:00Z", "2026-11-01T11:59:59Z"), now))
    }

    @Test fun formatsParseByNameAndFallBackToOther() {
        assertEquals(TicketBarcodeFormat.PDF_417, TicketSendingRules.parseFormat("PDF_417"))
        assertEquals(TicketBarcodeFormat.OTHER, TicketSendingRules.parseFormat("MAXICODE"))
        assertEquals(TicketBarcodeFormat.OTHER, TicketSendingRules.parseFormat(null))
    }
}
