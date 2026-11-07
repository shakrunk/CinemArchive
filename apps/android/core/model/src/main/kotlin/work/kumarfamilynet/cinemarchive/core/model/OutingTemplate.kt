package work.kumarfamilynet.cinemarchive.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A reusable outing shape (#204): the venue, company and format of a recurring habit, plus an
 * optional usual weekday and showtime. Applying one fills the schedule form; the title and the
 * actual showtime stay the user's to confirm. Names only for [companions], as on a saved outing
 * without a linked friend.
 */
data class OutingTemplate(
    val id: String,
    val name: String,
    val venue: String?,
    val companions: List<String>,
    val format: CinemaFormat?,
    val weekday: DayOfWeek?,
    val time: LocalTime?,
)

object OutingTemplateRules {
    const val MAX_TEMPLATES = 12
    const val MAX_NAME = 40
    const val MAX_VENUE = 100
    const val MAX_COMPANIONS = 6
    const val MAX_COMPANION_NAME = 40

    /** The showtime used when a template has a weekday but no time. */
    val DEFAULT_TIME: LocalTime = LocalTime.of(19, 0)

    /** A template is built from trimmed text: a blank name is rejected, a blank venue or
     *  companion is dropped, over-long values are rejected rather than silently cut. */
    fun create(
        id: String,
        name: String,
        venue: String?,
        companions: List<String>,
        format: CinemaFormat?,
        weekday: DayOfWeek?,
        time: LocalTime?,
    ): OutingTemplate? {
        val cleanName = name.trim()
        if (cleanName.isEmpty() || cleanName.length > MAX_NAME || id.isBlank()) return null
        val cleanVenue = venue?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanVenue != null && cleanVenue.length > MAX_VENUE) return null
        val cleanCompanions = companions.map(String::trim).filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        if (cleanCompanions.size > MAX_COMPANIONS || cleanCompanions.any { it.length > MAX_COMPANION_NAME }) return null
        return OutingTemplate(id, cleanName, cleanVenue, cleanCompanions, format, weekday, time?.withSecond(0)?.withNano(0))
    }

    /** The first showtime at or after [now] that fits the template: the template's weekday
     *  (today counts while its time is still ahead), else today, else tomorrow when today's
     *  time has gone. A template with no weekday keeps the next [time] that is still ahead. */
    fun nextShowtime(template: OutingTemplate, now: LocalDateTime): LocalDateTime {
        val time = template.time ?: DEFAULT_TIME
        var date: LocalDate = now.toLocalDate()
        val weekday = template.weekday
        while (true) {
            val matches = weekday == null || date.dayOfWeek == weekday
            val candidate = date.atTime(time)
            if (matches && candidate.isAfter(now)) return candidate
            date = date.plusDays(1)
        }
    }

    /** A short line for a template row: "Tue 19:00 · AMC Georgetown · IMAX · with Sam". */
    fun summary(template: OutingTemplate): String = buildList {
        val day = template.weekday?.let { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
        val time = template.time?.toString()?.take(5)
        listOfNotNull(day, time).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" ")) }
        template.venue?.let(::add)
        template.format?.let { add(it.wireValue) }
        if (template.companions.isNotEmpty()) add("with " + template.companions.joinToString(", "))
    }.joinToString(" · ")
}
