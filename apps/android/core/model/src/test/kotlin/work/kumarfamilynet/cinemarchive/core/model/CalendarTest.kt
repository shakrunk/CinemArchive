package work.kumarfamilynet.cinemarchive.core.model

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the fixtures in apps/web/src/store/calendar.test.ts. Android outing showtimes are
 *  instants, so those fixtures carry an explicit zone instead of web's offset-less local strings. */
class CalendarTest {
    private val today = "2026-10-18"
    private val la = ZoneId.of("America/Los_Angeles")

    private fun movie(
        id: String = "t1",
        name: String = "The Long Reel",
        status: LibraryStatus = LibraryStatus.WATCHLIST,
        releaseDate: String? = null,
    ) = CalendarTitle(id, name, null, MediaType.MOVIE, status, releaseDate)

    private fun show(
        id: String = "tv1",
        name: String = "Night Shift",
        status: LibraryStatus = LibraryStatus.WATCHING,
        releaseDate: String? = null,
        seasons: List<CalendarSeason> = emptyList(),
    ) = CalendarTitle(id, name, null, MediaType.TV, status, releaseDate, seasons)

    private fun episode(number: Int, airDate: String? = null, name: String? = null) = CalendarEpisode(number, airDate, name)

    private fun season(number: Int, vararg episodes: CalendarEpisode) = CalendarSeason(number, episodes.toList())

    private fun outing(
        id: String = "o1",
        titleId: String = "t1",
        showtime: String = "2026-10-21T02:30:00Z",
        status: OutingStatus = OutingStatus.SCHEDULED,
    ) = CinemaOuting(
        id = id, titleId = titleId, showtime = showtime, previewsMinutes = 20, runtimeMinutes = 136,
        endsAt = showtime, venue = null, companions = emptyList(), format = null, ticketPrice = null,
        seat = null, auditorium = null, seatRow = null, seats = emptyList(), bookingRef = null,
        ticketImagePath = null, ticketBarcodePayload = null, ticketBarcodeFormat = null, notes = null,
        status = status, previousStatus = null, completedViewingId = null, followUpDismissedAt = null,
        createdAt = "2026-10-01T00:00:00Z",
    )

    @Test fun listsWatchlistMoviesReleasingTodayOrLaterSoonestFirst() {
        val titles = listOf(
            movie("a", "Later", releaseDate = "2026-12-01"),
            movie("b", "Today", releaseDate = today),
            movie("c", "Past", releaseDate = "2026-10-17"),
            movie("d", "Watched", status = LibraryStatus.WATCHED, releaseDate = "2026-11-01"),
            movie("e", "Undated"),
        )
        val events = computeCalendarEvents(titles, emptyList(), today, la)
        assertEquals(listOf("Today" to today, "Later" to "2026-12-01"), events.map { it.title.name to it.date })
        assertTrue(events.all { it is CalendarEvent.Release })
    }

    @Test fun listsFutureDatedEpisodesOfEveryNonDroppedSeriesSkippingUndatedOnes() {
        val seasons = listOf(season(1, episode(1, "2026-10-01"), episode(2, "2026-10-25", "The Turn"), episode(3)))
        val titles = listOf(
            show("watching", seasons = seasons),
            show("finished", "Finished Show", LibraryStatus.WATCHED, seasons = seasons),
            show("dropped", "Dropped Show", LibraryStatus.DROPPED, seasons = seasons),
        )
        val events = computeCalendarEvents(titles, emptyList(), today, la)
        assertEquals(listOf("finished", "watching"), events.map { it.title.id })
        val first = events[0] as CalendarEvent.Episodes
        assertEquals("2026-10-25", first.date)
        assertEquals(1, first.seasonNumber)
        assertEquals(listOf(2), first.episodeNumbers)
        assertEquals("The Turn", first.episodeName)
    }

    @Test fun foldsASameDaySeasonDropIntoOneEventWithoutASingleEpisodeName() {
        val drop = show(seasons = listOf(season(2, episode(3, "2026-11-01", "C"), episode(1, "2026-11-01", "A"), episode(2, "2026-11-01", "B"))))
        val events = computeCalendarEvents(listOf(drop), emptyList(), today, la)
        assertEquals(1, events.size)
        val event = events[0] as CalendarEvent.Episodes
        assertEquals(listOf(1, 2, 3), event.episodeNumbers)
        assertNull(event.episodeName)
    }

    @Test fun includesSpecialsAsTheirOwnSeason() {
        val special = show(seasons = listOf(season(0, episode(1, "2026-12-24", "Holiday Special"))))
        val event = computeCalendarEvents(listOf(special), emptyList(), today, la).single() as CalendarEvent.Episodes
        assertEquals(0, event.seasonNumber)
    }

    @Test fun addsAPremiereForAWatchlistSeriesUnlessAnEpisodeAlreadyAirsThatDay() {
        val withoutEpisodes = show("p1", status = LibraryStatus.WATCHLIST, releaseDate = "2026-11-05")
        val withPilot = show("p2", "Pilot Known", LibraryStatus.WATCHLIST, "2026-11-06", listOf(season(1, episode(1, "2026-11-06"))))
        val events = computeCalendarEvents(listOf(withoutEpisodes, withPilot), emptyList(), today, la)
        assertEquals(listOf("premiere:p1", "episodes:p2:1:2026-11-06"), events.map { it.id })
    }

    @Test fun includesScheduledOutingsByLocalShowtimeDayAndDropsOtherStatuses() {
        val outings = listOf(
            outing("late", showtime = "2026-10-21T04:00:00Z"), // Oct 20, 21:00 in LA
            outing("early", showtime = "2026-10-20T20:00:00Z"), // Oct 20, 13:00 in LA
            outing("cancelled", status = OutingStatus.CANCELLED),
            outing("orphan", titleId = "missing"),
            outing("unparseable", showtime = "not-a-time"),
        )
        val events = computeCalendarEvents(listOf(movie()), outings, today, la)
        assertEquals(listOf("outing:early", "outing:late"), events.map { it.id })
        // 04:00Z on the 21st is still the 20th locally — the local day, not the UTC one.
        assertEquals(listOf("2026-10-20", "2026-10-20"), events.map { it.date })
    }

    @Test fun ordersSameDayEventsOutingPremiereReleaseThenEpisodes() {
        val day = "2026-11-10"
        val titles = listOf(
            show("ep", seasons = listOf(season(1, episode(1, day)))),
            movie("rel", releaseDate = day),
            show("prem", status = LibraryStatus.WATCHLIST, releaseDate = day),
        )
        val outings = listOf(outing(titleId = "rel", showtime = "2026-11-11T03:00:00Z")) // Nov 10, 19:00 in LA
        val events = computeCalendarEvents(titles, outings, today, la)
        assertEquals(
            listOf(CalendarEvent.Outing::class, CalendarEvent.Premiere::class, CalendarEvent.Release::class, CalendarEvent.Episodes::class),
            events.map { it::class },
        )
    }

    @Test fun treatsTheLocalCalendarDayNotTheUtcOneAsToday() {
        // 23:30 local on Oct 18 in LA is already Oct 19 in UTC, which must not hide an
        // episode airing on the 18th.
        val lateEvening = Instant.parse("2026-10-19T06:30:00Z")
        val localToday = localTodayYmd(lateEvening, la)
        assertEquals("2026-10-18", localToday)
        val airing = show(seasons = listOf(season(1, episode(1, "2026-10-18"))))
        assertEquals(1, computeCalendarEvents(listOf(airing), emptyList(), localToday, la).size)
    }

    @Test fun ignoresPartialDatesThatWouldSortAfterToday() {
        val titles = listOf(
            movie("year", releaseDate = "2027"),
            movie("blank", releaseDate = ""),
            show("tv", status = LibraryStatus.WATCHLIST, releaseDate = "2027-01", seasons = listOf(season(1, episode(1, "2027")))),
        )
        assertTrue(computeCalendarEvents(titles, emptyList(), today, la).isEmpty())
    }

    @Test fun groupsConsecutiveEventsByDate() {
        val titles = listOf(
            movie("a", "A", releaseDate = "2026-11-01"),
            movie("b", "B", releaseDate = "2026-11-01"),
            movie("c", "C", releaseDate = "2026-11-02"),
        )
        val days = groupEventsByDay(computeCalendarEvents(titles, emptyList(), today, la))
        assertEquals(listOf("2026-11-01" to 2, "2026-11-02" to 1), days.map { it.date to it.events.size })
    }

    @Test fun formatsSingleContiguousAndGappedEpisodeLists() {
        assertEquals("E4", formatEpisodeRange(listOf(4)))
        assertEquals("E1–E4", formatEpisodeRange(listOf(1, 2, 3, 4)))
        assertEquals("E1, E3", formatEpisodeRange(listOf(1, 3)))
        assertEquals("", formatEpisodeRange(emptyList()))
    }

    @Test fun labelsEpisodeEventsWithSeasonCodeAndNameOrCount() {
        val events = computeCalendarEvents(
            listOf(
                show("a", seasons = listOf(season(2, episode(1, "2026-11-01"), episode(2, "2026-11-01")))),
                show("b", seasons = listOf(season(0, episode(4, "2026-11-02", "Holiday Special")))),
            ),
            emptyList(), today, la,
        )
        assertEquals(listOf("S2 · E1–E2 (2 episodes)", "SP · E4 — Holiday Special"), events.map(::calendarEventLabel))
        assertEquals(listOf("S2 · E1–E2 (2 episodes)", "SP · E4"), events.map { calendarEventLabel(it, hideEpisodeNames = true) })
    }

    @Test fun padsToWholeSundayFirstWeeks() {
        // October 2026 starts on a Thursday and has 31 days.
        val cells = monthGrid(YearMonth.of(2026, 10))
        assertEquals(0, cells.size % 7)
        assertEquals(listOf(null, null, null, null), cells.take(4))
        assertEquals("2026-10-01", cells[4])
        assertEquals(31, cells.filterNotNull().size)
        // A month starting on Sunday has no leading padding (November 2026).
        assertEquals("2026-11-01", monthGrid(YearMonth.of(2026, 11)).first())
    }
}
