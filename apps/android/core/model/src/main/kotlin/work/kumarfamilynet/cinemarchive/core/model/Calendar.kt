package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

// ─── Calendar: upcoming releases, episode airings and cinema outings ─────────
//
// Pure derivation over the library already synced to the device — no extra fetches. Mirrors
// `apps/web/src/store/calendar.ts`. Every date is a local calendar day (YYYY-MM-DD), the format
// TMDB uses for `air_date`/`release_date`, so they compare correctly as strings. Never derive
// "today" from a UTC instant: west of UTC the evening is already tomorrow there.

/** A library title with just the fields the calendar reads. */
data class CalendarTitle(
    val id: String,
    val name: String,
    val posterUrl: String?,
    val type: MediaType,
    val status: LibraryStatus,
    /** A movie's release date or a series' first-air date (YYYY-MM-DD). */
    val releaseDate: String?,
    val seasons: List<CalendarSeason> = emptyList(),
)

data class CalendarSeason(val seasonNumber: Int, val episodes: List<CalendarEpisode>)

data class CalendarEpisode(val episodeNumber: Int, val airDate: String?, val episodeName: String? = null)

sealed interface CalendarEvent {
    val id: String
    val date: String
    val title: CalendarTitle

    data class Release(override val id: String, override val date: String, override val title: CalendarTitle) : CalendarEvent

    data class Premiere(override val id: String, override val date: String, override val title: CalendarTitle) : CalendarEvent

    data class Episodes(
        override val id: String,
        override val date: String,
        override val title: CalendarTitle,
        val seasonNumber: Int,
        /** Ascending. More than one when a season drops several episodes at once. */
        val episodeNumbers: List<Int>,
        /** Name of the episode, only when exactly one airs that day. */
        val episodeName: String?,
    ) : CalendarEvent

    data class Outing(
        override val id: String,
        override val date: String,
        override val title: CalendarTitle,
        val outing: CinemaOuting,
    ) : CalendarEvent
}

data class CalendarDay(val date: String, val events: List<CalendarEvent>)

/** Local YYYY-MM-DD for [now] in [zone] — the calendar's "today". */
fun localTodayYmd(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
    now.atZone(zone).toLocalDate().toString()

// Only full calendar days take part: a partial date such as a year-only "2027" would otherwise
// sort after today as a string and then fail to parse as a day.
private val Ymd = Regex("""\d{4}-\d{2}-\d{2}""")

private fun String?.calendarDay(): String? = this?.takeIf { Ymd.matches(it) }

private fun CalendarEvent.kindOrder(): Int = when (this) {
    is CalendarEvent.Outing -> 0
    is CalendarEvent.Premiere -> 1
    is CalendarEvent.Release -> 2
    is CalendarEvent.Episodes -> 3
}

/**
 * Every upcoming event on or after [today], sorted by date.
 *
 * - **Movie releases:** watchlist movies with a release date.
 * - **Episodes:** every series except dropped ones (a finished show getting a new season is
 *   exactly what this is for). Episodes airing the same day in the same season fold into one
 *   event so a binge drop doesn't flood the day. Undated episodes are skipped — TMDB lists
 *   undated placeholders that aren't scheduled. Specials (season 0) are included.
 * - **Premieres:** a watchlist series' first-air date, unless an episode of it already airs
 *   that day (which then carries the event instead).
 * - **Outings:** scheduled cinema outings, by the showtime's local day in [zone]. Pass an empty
 *   list outside the owner's own view: outings are never shared.
 */
fun computeCalendarEvents(
    titles: List<CalendarTitle>,
    outings: List<CinemaOuting>,
    today: String,
    zone: ZoneId = ZoneId.systemDefault(),
): List<CalendarEvent> {
    val events = mutableListOf<CalendarEvent>()
    val titlesById = titles.associateBy { it.id }

    for (title in titles) {
        if (title.type == MediaType.MOVIE) {
            val date = title.releaseDate.calendarDay()
            if (title.status == LibraryStatus.WATCHLIST && date != null && date >= today) {
                events += CalendarEvent.Release("release:${title.id}", date, title)
            }
            continue
        }

        if (title.status == LibraryStatus.DROPPED) continue
        val episodeDays = mutableSetOf<String>()
        for (season in title.seasons) {
            // LinkedHashMap keeps first-seen order, like the web Map; the final sort fixes it anyway.
            val byDay = linkedMapOf<String, MutableList<CalendarEpisode>>()
            for (episode in season.episodes) {
                val airDate = episode.airDate.calendarDay() ?: continue
                if (airDate < today) continue
                byDay.getOrPut(airDate) { mutableListOf() } += episode
            }
            for ((date, group) in byDay) {
                episodeDays += date
                val numbers = group.map { it.episodeNumber }.sorted()
                events += CalendarEvent.Episodes(
                    id = "episodes:${title.id}:${season.seasonNumber}:$date",
                    date = date,
                    title = title,
                    seasonNumber = season.seasonNumber,
                    episodeNumbers = numbers,
                    episodeName = if (numbers.size == 1) group.single().episodeName else null,
                )
            }
        }

        val premiere = title.releaseDate.calendarDay()
        if (title.status == LibraryStatus.WATCHLIST && premiere != null && premiere >= today && premiere !in episodeDays) {
            events += CalendarEvent.Premiere("premiere:${title.id}", premiere, title)
        }
    }

    for (outing in outings) {
        if (outing.status != OutingStatus.SCHEDULED) continue
        val title = titlesById[outing.titleId] ?: continue
        val showtime = runCatching { Instant.parse(outing.showtime) }.getOrNull() ?: continue
        val date = localTodayYmd(showtime, zone)
        if (date < today) continue
        events += CalendarEvent.Outing("outing:${outing.id}", date, title, outing)
    }

    return events.sortedWith { a, b ->
        when {
            a.date != b.date -> a.date.compareTo(b.date)
            a is CalendarEvent.Outing && b is CalendarEvent.Outing -> outingInstant(a).compareTo(outingInstant(b))
            a.kindOrder() != b.kindOrder() -> a.kindOrder() - b.kindOrder()
            else -> a.title.name.compareTo(b.title.name, ignoreCase = true)
        }
    }
}

// Only reached for outings that already parsed above.
private fun outingInstant(event: CalendarEvent.Outing): Instant = Instant.parse(event.outing.showtime)

/** Groups already-sorted events into consecutive days. */
fun groupEventsByDay(events: List<CalendarEvent>): List<CalendarDay> {
    val days = mutableListOf<CalendarDay>()
    for (event in events) {
        val last = days.lastOrNull()
        if (last != null && last.date == event.date) days[days.lastIndex] = last.copy(events = last.events + event)
        else days += CalendarDay(event.date, listOf(event))
    }
    return days
}

/** "E3" / "E1–E8" / "E1, E3" — compact episode range for an [CalendarEvent.Episodes]. */
fun formatEpisodeRange(numbers: List<Int>): String {
    if (numbers.isEmpty()) return ""
    if (numbers.size == 1) return "E${numbers[0]}"
    val contiguous = numbers.zipWithNext().all { (a, b) -> b == a + 1 }
    if (contiguous) return "E${numbers.first()}–E${numbers.last()}"
    return numbers.joinToString(", ") { "E$it" }
}

/** "S2 · E1–E8 (8 episodes)" / "S1 · E2 — The Turn" / "Release day" … — the agenda row's subtitle,
 *  except for outings, whose time label is formatted by the UI. Calendar events carry no watch
 *  history, so [hideEpisodeNames] (spoiler protection) drops every episode name. */
fun calendarEventLabel(event: CalendarEvent, hideEpisodeNames: Boolean = false): String = when (event) {
    is CalendarEvent.Release -> "Release day"
    is CalendarEvent.Premiere -> "Series premiere"
    is CalendarEvent.Episodes -> {
        val code = "${seasonShortLabel(event.seasonNumber)} · ${formatEpisodeRange(event.episodeNumbers)}"
        when {
            event.episodeName != null && !hideEpisodeNames -> "$code — ${event.episodeName}"
            event.episodeNumbers.size > 1 -> "$code (${event.episodeNumbers.size} episodes)"
            else -> code
        }
    }
    is CalendarEvent.Outing -> "Tickets"
}

/**
 * The cells of a month grid starting on Sunday: leading `null`s pad the first week, then one
 * YYYY-MM-DD per day, then trailing `null`s fill the last week.
 */
fun monthGrid(month: YearMonth): List<String?> {
    // DayOfWeek runs Monday=1 … Sunday=7, so % 7 makes Sunday the first column.
    val leading = month.atDay(1).dayOfWeek.value % 7
    val cells = MutableList<String?>(leading) { null }
    for (day in 1..month.lengthOfMonth()) cells += month.atDay(day).toString()
    while (cells.size % 7 != 0) cells += null
    return cells
}
