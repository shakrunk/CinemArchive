package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Pure presentation/derivation logic for [CinemaOuting] — the Android analogue of the web
 *  app's `src/store/outings.ts`. No Room/DAO/Context dependency so it's unit-testable in
 *  isolation, same rationale as [LedgerLayoutRules]. */
object CinemaOutingRules {

    /** A scheduled outing is "Now Showing" while the show is playing; anything after
     *  [CinemaOuting.endsAt] is no longer live (the completion engine should have already
     *  transitioned it — this is a presentation-only derivation, not a state mutation). */
    fun isNowShowing(outing: CinemaOuting, now: Instant): Boolean {
        val showtime = Instant.parse(outing.showtime)
        val endsAt = Instant.parse(outing.endsAt)
        return outing.status == OutingStatus.SCHEDULED && !now.isBefore(showtime) && now.isBefore(endsAt)
    }

    fun isPastEnd(outing: CinemaOuting, now: Instant): Boolean =
        outing.status == OutingStatus.SCHEDULED && !now.isBefore(Instant.parse(outing.endsAt))

    /** Human countdown chip: `in 12 days` → weekday+time (≤7d) → `TOMORROW` →
     *  `TONIGHT · 7:30 PM` → `NOW SHOWING`. Mirrors web plan §4.5. [zone] defaults to the
     *  device's zone since this is display-only (unlike [Viewing.date], which needs the
     *  IANA zone baked in for the completion engine — see the plan's rule 1). */
    fun countdownLabel(outing: CinemaOuting, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        if (isNowShowing(outing, now) || isPastEnd(outing, now)) return "NOW SHOWING"

        val showtime = ZonedDateTime.ofInstant(Instant.parse(outing.showtime), zone)
        val today = ZonedDateTime.ofInstant(now, zone)
        // ChronoUnit.DAYS.between, not LocalDate#until(...).days: the latter is a Period's
        // remainder-days component (post month/year extraction), not total elapsed days — it
        // silently gives the wrong bucket for anything more than about a month out.
        val daysBetween = ChronoUnit.DAYS.between(today.toLocalDate(), showtime.toLocalDate())
        val timeLabel = showtime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))

        return when {
            daysBetween <= 0 -> "TONIGHT · $timeLabel"
            daysBetween == 1L -> "TOMORROW"
            daysBetween <= 7 -> "${showtime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)} · $timeLabel"
            else -> "in $daysBetween days"
        }
    }

    /** The Up Next "On the Marquee" list: scheduled + now-showing outings, soonest showtime
     *  first. Completed outings pending follow-up render separately ("Fresh from the lobby",
     *  see [pendingFollowUp]) rather than mixing into this list. */
    fun marqueeEntries(outings: List<CinemaOuting>, now: Instant): List<CinemaOuting> = outings
        .filter { it.status == OutingStatus.SCHEDULED }
        .sortedBy { Instant.parse(it.showtime) }

    /** Completed outings still awaiting "how was it?" — no rating yet, not dismissed, and
     *  within the 14-day window (web plan §4.4). */
    fun pendingFollowUp(outings: List<CinemaOuting>, viewingsById: Map<String, Viewing>, now: Instant): List<CinemaOuting> =
        outings.filter { outing ->
            if (outing.status != OutingStatus.COMPLETED) return@filter false
            if (outing.followUpDismissedAt != null) return@filter false
            val viewing = outing.completedViewingId?.let(viewingsById::get) ?: return@filter false
            if (viewing.rating != null) return@filter false
            val completedAt = Instant.parse(outing.endsAt)
            now.isBefore(completedAt.plusSeconds(FOLLOW_UP_WINDOW_SECONDS))
        }

    /** The next instant any scheduled outing needs the completion engine to run — drives the
     *  alarm re-arm (Android's on-device analogue of the web's single armed `setTimeout`). */
    fun nextTransitionAt(outings: List<CinemaOuting>): Instant? = outings
        .filter { it.status == OutingStatus.SCHEDULED }
        .minOfOrNull { Instant.parse(it.endsAt) }

    /** Titles that should NOT also render as a plain watchlist card — they have a scheduled
     *  outing and belong on the marquee instead (web plan §4.5). */
    fun titleIdsWithScheduledOuting(outings: List<CinemaOuting>): Set<String> =
        outings.filter { it.status == OutingStatus.SCHEDULED }.map { it.titleId }.toSet()

    private const val FOLLOW_UP_WINDOW_SECONDS = 14L * 24 * 60 * 60
}
