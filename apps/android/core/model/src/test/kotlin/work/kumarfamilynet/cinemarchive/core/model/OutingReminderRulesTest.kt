package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun scheduled(id: String, showtime: String, status: OutingStatus = OutingStatus.SCHEDULED): CinemaOuting {
    val start = Instant.parse(showtime)
    return CinemaOuting(
        id = id, titleId = "title-$id", showtime = showtime, previewsMinutes = 20, runtimeMinutes = 100,
        endsAt = start.plusSeconds(120 * 60L).toString(), venue = "AMC", companions = emptyList(), format = null,
        ticketPrice = null, seat = null, auditorium = null, seatRow = null, seats = emptyList(), bookingRef = null,
        ticketImagePath = null, ticketBarcodePayload = null, ticketBarcodeFormat = null, notes = null, status = status,
        previousStatus = null, completedViewingId = null, followUpDismissedAt = null, createdAt = "2026-10-01T00:00:00Z",
    )
}

class OutingReminderRulesTest {
    private val showtime = "2026-11-01T19:00:00Z"
    private val outing = scheduled("a", showtime)

    @Test fun offersThreeLeadsAndOffIsAlwaysValid() {
        assertEquals(listOf(30, 60, 120), OutingReminderRules.LEAD_OPTIONS_MINUTES)
        assertTrue(OutingReminderRules.isValidLead(null))
        assertTrue(OutingReminderRules.isValidLead(60))
        assertFalse(OutingReminderRules.isValidLead(45))
        assertFalse(OutingReminderRules.isValidLead(0))
    }

    @Test fun reminderIsDueFromTheLeadUntilTheGraceEnds() {
        fun due(at: String) = OutingReminderRules.due(listOf(outing), 60, Instant.parse(at), emptySet())
        assertTrue(due("2026-11-01T17:59:59Z").isEmpty())
        assertEquals(listOf("a"), due("2026-11-01T18:00:00Z").map { it.id })
        assertEquals(listOf("a"), due("2026-11-01T18:09:59Z").map { it.id })
        assertTrue(due("2026-11-01T18:10:00Z").isEmpty())
    }

    @Test fun anOutingScheduledInsideTheGraceWindowIsNotRemindedLate() {
        // 20 minutes out with a 60 minute lead: the reminder time passed 40 minutes ago.
        val soon = scheduled("b", "2026-11-01T19:00:00Z")
        assertTrue(OutingReminderRules.due(listOf(soon), 60, Instant.parse("2026-11-01T18:40:00Z"), emptySet()).isEmpty())
    }

    @Test fun nothingIsDueWhenOffSentNotScheduledOrAlreadyStarted() {
        val now = Instant.parse("2026-11-01T18:05:00Z")
        assertTrue(OutingReminderRules.due(listOf(outing), null, now, emptySet()).isEmpty())
        assertTrue(OutingReminderRules.due(listOf(outing), 60, now, setOf(OutingReminderRules.key(outing))).isEmpty())
        assertTrue(OutingReminderRules.due(listOf(scheduled("c", showtime, OutingStatus.CANCELLED)), 60, now, emptySet()).isEmpty())
        // A 120 minute lead on an outing that already began: reminder time is long past the grace window.
        assertTrue(OutingReminderRules.due(listOf(outing), 120, Instant.parse("2026-11-01T19:30:00Z"), emptySet()).isEmpty())
    }

    @Test fun movingAnOutingEarnsAFreshReminder() {
        val sentForOldTime = setOf(OutingReminderRules.key(outing))
        val moved = scheduled("a", "2026-11-01T20:00:00Z")
        assertEquals(
            listOf("a"),
            OutingReminderRules.due(listOf(moved), 60, Instant.parse("2026-11-01T19:02:00Z"), sentForOldTime).map { it.id },
        )
    }

    @Test fun nextReminderIsTheEarliestUnsentOneAheadOrNowWhenOneIsDue() {
        val later = scheduled("b", "2026-11-02T19:00:00Z")
        val now = Instant.parse("2026-11-01T12:00:00Z")
        assertEquals(Instant.parse("2026-11-01T18:00:00Z"), OutingReminderRules.nextReminderAt(listOf(later, outing), 60, now, emptySet()))
        assertEquals(Instant.parse("2026-11-02T18:00:00Z"), OutingReminderRules.nextReminderAt(listOf(later, outing), 60, now, setOf(OutingReminderRules.key(outing))))
        val due = Instant.parse("2026-11-01T18:03:00Z")
        assertEquals(due, OutingReminderRules.nextReminderAt(listOf(later, outing), 60, due, emptySet()))
        assertNull(OutingReminderRules.nextReminderAt(listOf(outing), null, now, emptySet()))
        assertNull(OutingReminderRules.nextReminderAt(emptyList(), 60, now, emptySet()))
    }

    @Test fun theOneAlarmIsWhicheverOfReminderAndCompletionComesFirst() {
        val now = Instant.parse("2026-11-01T12:00:00Z")
        // Reminder 18:00 is before the 21:00 end of the show.
        assertEquals(Instant.parse("2026-11-01T18:00:00Z"), OutingReminderRules.nextAlarmAt(listOf(outing), 60, now, emptySet()))
        // Once sent, or with reminders off, only the completion remains.
        val end = Instant.parse(outing.endsAt)
        assertEquals(end, OutingReminderRules.nextAlarmAt(listOf(outing), 60, now, setOf(OutingReminderRules.key(outing))))
        assertEquals(end, OutingReminderRules.nextAlarmAt(listOf(outing), null, now, emptySet()))
        assertNull(OutingReminderRules.nextAlarmAt(emptyList(), 60, now, emptySet()))
    }

    @Test fun sentKeysAreKeptOnlyForOutingsStillScheduledForThatShowtime() {
        val moved = scheduled("a", "2026-11-01T20:00:00Z")
        val cancelled = scheduled("b", showtime, OutingStatus.CANCELLED)
        val sent = setOf(OutingReminderRules.key(outing), OutingReminderRules.key(cancelled), "gone@2026-01-01T00:00:00Z")
        assertEquals(setOf(OutingReminderRules.key(outing)), OutingReminderRules.pruneSent(listOf(outing), sent))
        assertTrue(OutingReminderRules.pruneSent(listOf(moved, cancelled), sent).isEmpty())
    }
}
