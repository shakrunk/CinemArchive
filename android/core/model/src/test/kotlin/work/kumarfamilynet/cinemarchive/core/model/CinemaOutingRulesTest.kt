package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun outing(
    id: String = "outing-1",
    showtime: String,
    endsAt: String,
    status: OutingStatus = OutingStatus.SCHEDULED,
    followUpDismissedAt: String? = null,
    completedViewingId: String? = null,
    titleId: String = "title-1",
) = CinemaOuting(
    id = id,
    titleId = titleId,
    showtime = showtime,
    previewsMinutes = 20,
    runtimeMinutes = 120,
    endsAt = endsAt,
    venue = "AMC Georgetown",
    companions = emptyList(),
    format = CinemaFormat.IMAX,
    ticketPrice = null,
    seat = null,
    bookingRef = null,
    notes = null,
    status = status,
    previousStatus = null,
    completedViewingId = completedViewingId,
    followUpDismissedAt = followUpDismissedAt,
    createdAt = "2026-07-01T00:00:00Z",
)

class CinemaOutingRulesTest {

    @Test
    fun `isNowShowing is true between showtime and endsAt`() {
        val o = outing(showtime = "2026-07-17T19:30:00Z", endsAt = "2026-07-17T22:16:00Z")
        assertTrue(CinemaOutingRules.isNowShowing(o, Instant.parse("2026-07-17T20:00:00Z")))
        assertTrue(!CinemaOutingRules.isNowShowing(o, Instant.parse("2026-07-17T19:00:00Z")))
        assertTrue(!CinemaOutingRules.isNowShowing(o, Instant.parse("2026-07-17T23:00:00Z")))
    }

    @Test
    fun `isPastEnd is true once endsAt has passed for a still-scheduled outing`() {
        val o = outing(showtime = "2026-07-17T19:30:00Z", endsAt = "2026-07-17T22:16:00Z")
        assertTrue(CinemaOutingRules.isPastEnd(o, Instant.parse("2026-07-17T23:00:00Z")))
        assertTrue(!CinemaOutingRules.isPastEnd(o, Instant.parse("2026-07-17T20:00:00Z")))
    }

    @Test
    fun `countdownLabel reads NOW SHOWING while live or past end, regardless of day math`() {
        val o = outing(showtime = "2026-07-17T19:30:00Z", endsAt = "2026-07-17T22:16:00Z")
        assertEquals("NOW SHOWING", CinemaOutingRules.countdownLabel(o, Instant.parse("2026-07-17T20:00:00Z"), java.time.ZoneOffset.UTC))
        assertEquals("NOW SHOWING", CinemaOutingRules.countdownLabel(o, Instant.parse("2026-07-18T01:00:00Z"), java.time.ZoneOffset.UTC))
    }

    @Test
    fun `countdownLabel buckets by day distance`() {
        val zone = java.time.ZoneOffset.UTC
        val o = outing(showtime = "2026-07-24T19:30:00Z", endsAt = "2026-07-24T22:16:00Z")
        assertEquals("TOMORROW", CinemaOutingRules.countdownLabel(o, Instant.parse("2026-07-23T10:00:00Z"), zone))

        val farOuting = outing(showtime = "2026-08-10T19:30:00Z", endsAt = "2026-08-10T22:16:00Z")
        assertEquals("in 20 days", CinemaOutingRules.countdownLabel(farOuting, Instant.parse("2026-07-21T10:00:00Z"), zone))
    }

    @Test
    fun `countdownLabel counts total elapsed days, not a Period's remainder-days component`() {
        // Regression: LocalDate#until(...).days (a Period's remainder after months/years
        // extraction) would report ~10 here, not 204 — this spans more than a month.
        val zone = java.time.ZoneOffset.UTC
        val farOuting = outing(showtime = "2027-02-10T19:30:00Z", endsAt = "2027-02-10T22:16:00Z")
        assertEquals("in 204 days", CinemaOutingRules.countdownLabel(farOuting, Instant.parse("2026-07-21T10:00:00Z"), zone))
    }

    @Test
    fun `marqueeEntries only includes scheduled outings, soonest first`() {
        val later = outing(id = "later", showtime = "2026-07-25T19:30:00Z", endsAt = "2026-07-25T22:00:00Z")
        val sooner = outing(id = "sooner", showtime = "2026-07-18T19:30:00Z", endsAt = "2026-07-18T22:00:00Z")
        val cancelled = outing(id = "cancelled", showtime = "2026-07-17T19:30:00Z", endsAt = "2026-07-17T22:00:00Z", status = OutingStatus.CANCELLED)

        val result = CinemaOutingRules.marqueeEntries(listOf(later, sooner, cancelled), Instant.parse("2026-07-15T00:00:00Z"))

        assertEquals(listOf("sooner", "later"), result.map { it.id })
    }

    @Test
    fun `pendingFollowUp excludes rated, dismissed, or too-old completions`() {
        val viewingRated = Viewing(id = "v-rated", date = "2026-07-10", rating = 4.5, notes = null, venue = null)
        val viewingUnrated = Viewing(id = "v-unrated", date = "2026-07-10", rating = null, notes = null, venue = null)
        val viewingsById = mapOf("v-rated" to viewingRated, "v-unrated" to viewingUnrated)
        val now = Instant.parse("2026-07-18T00:00:00Z")

        val rated = outing(id = "rated", showtime = "2026-07-10T19:00:00Z", endsAt = "2026-07-10T21:30:00Z", status = OutingStatus.COMPLETED, completedViewingId = "v-rated")
        val dismissed = outing(id = "dismissed", showtime = "2026-07-10T19:00:00Z", endsAt = "2026-07-10T21:30:00Z", status = OutingStatus.COMPLETED, completedViewingId = "v-unrated", followUpDismissedAt = "2026-07-11T00:00:00Z")
        val pending = outing(id = "pending", showtime = "2026-07-10T19:00:00Z", endsAt = "2026-07-10T21:30:00Z", status = OutingStatus.COMPLETED, completedViewingId = "v-unrated")
        val tooOld = outing(id = "too-old", showtime = "2026-01-01T19:00:00Z", endsAt = "2026-01-01T21:30:00Z", status = OutingStatus.COMPLETED, completedViewingId = "v-unrated")

        val result = CinemaOutingRules.pendingFollowUp(listOf(rated, dismissed, pending, tooOld), viewingsById, now)

        assertEquals(listOf("pending"), result.map { it.id })
    }

    @Test
    fun `nextTransitionAt returns the soonest scheduled endsAt, or null when none scheduled`() {
        val a = outing(id = "a", showtime = "2026-07-25T19:30:00Z", endsAt = "2026-07-25T22:00:00Z")
        val b = outing(id = "b", showtime = "2026-07-18T19:30:00Z", endsAt = "2026-07-18T22:00:00Z")
        assertEquals(Instant.parse("2026-07-18T22:00:00Z"), CinemaOutingRules.nextTransitionAt(listOf(a, b)))
        assertNull(CinemaOutingRules.nextTransitionAt(emptyList()))
    }

    @Test
    fun `titleIdsWithScheduledOuting only counts scheduled status`() {
        val scheduled = outing(id = "s", titleId = "t1", showtime = "2026-07-25T19:30:00Z", endsAt = "2026-07-25T22:00:00Z")
        val cancelled = outing(id = "c", titleId = "t2", showtime = "2026-07-25T19:30:00Z", endsAt = "2026-07-25T22:00:00Z", status = OutingStatus.CANCELLED)
        assertEquals(setOf("t1"), CinemaOutingRules.titleIdsWithScheduledOuting(listOf(scheduled, cancelled)))
    }
}
