package work.kumarfamilynet.cinemarchive.core.model

import java.time.Duration
import java.time.Instant

/**
 * "N minutes before showtime" reminders (#213). A reminder is due from `showtime - lead` until
 * [GRACE_MINUTES] after that: a phone that was off when the alarm fired still tells the user,
 * but a show scheduled well inside the lead window, or a reminder missed by hours, stays quiet
 * rather than announcing a show that is about to start or is long under way.
 *
 * Pure functions over the scheduled outings so the one alarm can serve both this and the
 * completion engine ([nextAlarmAt]). Sent reminders are remembered as [key]s, which include the
 * showtime, so moving an outing earns it a fresh reminder.
 */
object OutingReminderRules {
    /** The lead times offered in Settings; null (off) is the default. */
    val LEAD_OPTIONS_MINUTES = listOf(30, 60, 120)
    const val GRACE_MINUTES = 10L

    fun isValidLead(minutes: Int?): Boolean = minutes == null || minutes in LEAD_OPTIONS_MINUTES

    /** Identity of one reminder: the outing and the showtime it was armed for. */
    fun key(outing: CinemaOuting): String = "${outing.id}@${outing.showtime}"

    fun reminderAt(outing: CinemaOuting, leadMinutes: Int): Instant =
        Instant.parse(outing.showtime).minus(Duration.ofMinutes(leadMinutes.toLong()))

    /** Scheduled outings whose reminder should be posted now and has not been sent. */
    fun due(outings: List<CinemaOuting>, leadMinutes: Int?, now: Instant, sent: Set<String>): List<CinemaOuting> {
        if (leadMinutes == null) return emptyList()
        return outings.filter { outing ->
            outing.status == OutingStatus.SCHEDULED && key(outing) !in sent &&
                reminderAt(outing, leadMinutes).let { at -> !at.isAfter(now) && now.isBefore(at.plus(Duration.ofMinutes(GRACE_MINUTES))) } &&
                now.isBefore(Instant.parse(outing.showtime))
        }
    }

    /** The soonest unsent reminder still ahead of [now], or [now] itself when one is already due. */
    fun nextReminderAt(outings: List<CinemaOuting>, leadMinutes: Int?, now: Instant, sent: Set<String>): Instant? {
        if (leadMinutes == null) return null
        if (due(outings, leadMinutes, now, sent).isNotEmpty()) return now
        return outings
            .filter { it.status == OutingStatus.SCHEDULED && key(it) !in sent }
            .map { reminderAt(it, leadMinutes) }
            .filter { it.isAfter(now) }
            .minOrNull()
    }

    /** The one instant the alarm must wake the app: a show letting out or a reminder, whichever is first. */
    fun nextAlarmAt(outings: List<CinemaOuting>, leadMinutes: Int?, now: Instant, sent: Set<String>): Instant? =
        listOfNotNull(CinemaOutingRules.nextTransitionAt(outings), nextReminderAt(outings, leadMinutes, now, sent)).minOrNull()

    /** The sent keys still worth remembering: those of outings that are still scheduled for that showtime. */
    fun pruneSent(outings: List<CinemaOuting>, sent: Set<String>): Set<String> {
        val live = outings.filter { it.status == OutingStatus.SCHEDULED }.map(::key).toSet()
        return sent.intersect(live)
    }
}
