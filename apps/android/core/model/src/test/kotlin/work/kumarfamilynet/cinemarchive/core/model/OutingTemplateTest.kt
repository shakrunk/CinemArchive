package work.kumarfamilynet.cinemarchive.core.model

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OutingTemplateTest {
    private fun template(weekday: DayOfWeek? = null, time: LocalTime? = null) = OutingTemplateRules.create(
        id = "t1", name = "Tuesday matinee", venue = "AMC", companions = listOf("Sam"),
        format = CinemaFormat.IMAX, weekday = weekday, time = time,
    )!!

    @Test
    fun createTrimsTextAndDropsBlankAndDuplicateCompanions() {
        val made = OutingTemplateRules.create(
            id = "t1", name = "  Date night ", venue = "  ", companions = listOf(" Sam ", "", "sam", "Alex"),
            format = null, weekday = null, time = LocalTime.of(19, 30, 45),
        )!!
        assertEquals("Date night", made.name)
        assertNull(made.venue)
        assertEquals(listOf("Sam", "Alex"), made.companions)
        assertEquals(LocalTime.of(19, 30), made.time)
    }

    @Test
    fun createRejectsABlankOrOverLongNameAndOverLongFields() {
        fun make(name: String = "ok", venue: String? = null, companions: List<String> = emptyList(), id: String = "t") =
            OutingTemplateRules.create(id, name, venue, companions, null, null, null)
        assertNull(make(name = "   "))
        assertNull(make(name = "x".repeat(OutingTemplateRules.MAX_NAME + 1)))
        assertNotNull(make(name = "x".repeat(OutingTemplateRules.MAX_NAME)))
        assertNull(make(venue = "v".repeat(OutingTemplateRules.MAX_VENUE + 1)))
        assertNull(make(companions = List(OutingTemplateRules.MAX_COMPANIONS + 1) { "c$it" }))
        assertNull(make(companions = listOf("c".repeat(OutingTemplateRules.MAX_COMPANION_NAME + 1))))
        assertNull(make(id = " "))
    }

    // 2026-10-27 is a Tuesday.
    @Test
    fun aWeekdayTemplateLandsOnTheNextMatchingDay() {
        val monday = LocalDateTime.of(2026, 10, 26, 12, 0)
        assertEquals(LocalDateTime.of(2026, 10, 27, 14, 0),
            OutingTemplateRules.nextShowtime(template(DayOfWeek.TUESDAY, LocalTime.of(14, 0)), monday))
    }

    @Test
    fun todayCountsWhileTheTimeIsStillAhead() {
        val tuesdayMorning = LocalDateTime.of(2026, 10, 27, 9, 0)
        assertEquals(LocalDateTime.of(2026, 10, 27, 14, 0),
            OutingTemplateRules.nextShowtime(template(DayOfWeek.TUESDAY, LocalTime.of(14, 0)), tuesdayMorning))
    }

    @Test
    fun aTimeThatHasPassedMovesToNextWeek() {
        val tuesdayEvening = LocalDateTime.of(2026, 10, 27, 20, 0)
        assertEquals(LocalDateTime.of(2026, 11, 3, 14, 0),
            OutingTemplateRules.nextShowtime(template(DayOfWeek.TUESDAY, LocalTime.of(14, 0)), tuesdayEvening))
        // Exactly now is not "ahead".
        assertEquals(LocalDateTime.of(2026, 11, 3, 14, 0),
            OutingTemplateRules.nextShowtime(template(DayOfWeek.TUESDAY, LocalTime.of(14, 0)), LocalDateTime.of(2026, 10, 27, 14, 0)))
    }

    @Test
    fun noWeekdayMeansTodayOrTomorrow() {
        val noon = LocalDateTime.of(2026, 10, 27, 12, 0)
        assertEquals(LocalDateTime.of(2026, 10, 27, 19, 0), OutingTemplateRules.nextShowtime(template(), noon))
        val lateNight = LocalDateTime.of(2026, 10, 27, 23, 0)
        assertEquals(LocalDateTime.of(2026, 10, 28, 19, 0), OutingTemplateRules.nextShowtime(template(), lateNight))
    }

    @Test
    fun aWeekdayWithoutATimeUsesTheDefaultShowtime() {
        val monday = LocalDateTime.of(2026, 10, 26, 12, 0)
        assertEquals(LocalDateTime.of(2026, 10, 31, 19, 0),
            OutingTemplateRules.nextShowtime(template(DayOfWeek.SATURDAY), monday))
    }

    @Test
    fun summaryListsOnlyWhatIsSet() {
        assertEquals("Tue 14:00 · AMC · IMAX · with Sam", OutingTemplateRules.summary(template(DayOfWeek.TUESDAY, LocalTime.of(14, 0))))
        assertEquals("AMC · IMAX · with Sam", OutingTemplateRules.summary(template()))
        val bare = OutingTemplateRules.create("t", "Bare", null, emptyList(), null, null, null)!!
        assertEquals("", OutingTemplateRules.summary(bare))
    }
}
