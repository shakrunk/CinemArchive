package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionPlanTest {
    // Sunday 2026-10-18; its Monday-to-Sunday week is Oct 12-18.
    private val today = LocalDate.parse("2026-10-18")
    private val weekly3 = SubscriptionPlan(3, PlanPeriod.WEEK)
    private val monthly10 = SubscriptionPlan(10, PlanPeriod.MONTH)

    private fun dates(vararg days: String) = days.map(LocalDate::parse)

    @Test fun weekRunsMondayThroughSundayWhateverTheDay() {
        listOf("2026-10-12", "2026-10-15", "2026-10-18").forEach {
            val range = SubscriptionPlanRules.periodRange(PlanPeriod.WEEK, LocalDate.parse(it))
            assertEquals(LocalDate.parse("2026-10-12"), range.start)
            assertEquals(LocalDate.parse("2026-10-18"), range.endInclusive)
        }
    }

    @Test fun aWeekCanSpanTwoMonthsAndTwoYears() {
        val range = SubscriptionPlanRules.periodRange(PlanPeriod.WEEK, LocalDate.parse("2026-12-31"))
        assertEquals(LocalDate.parse("2026-12-28"), range.start)
        assertEquals(LocalDate.parse("2027-01-03"), range.endInclusive)
    }

    @Test fun monthIsTheCalendarMonth() {
        val range = SubscriptionPlanRules.periodRange(PlanPeriod.MONTH, today)
        assertEquals(LocalDate.parse("2026-10-01"), range.start)
        assertEquals(LocalDate.parse("2026-10-31"), range.endInclusive)
    }

    @Test fun onlyOutingsInsideThePeriodCount() {
        val trips = dates("2026-10-11", "2026-10-12", "2026-10-18", "2026-10-19")
        assertEquals(2, SubscriptionPlanRules.status(weekly3, trips, today).used)
    }

    @Test fun twoOutingsInTheSameDayBothCount() {
        assertEquals(2, SubscriptionPlanRules.status(weekly3, dates("2026-10-14", "2026-10-14"), today).used)
    }

    @Test fun noOutingsIsUnder() {
        val status = SubscriptionPlanRules.status(weekly3, emptyList(), today)
        assertEquals(PlanState.UNDER, status.state)
        assertEquals(3, status.remaining)
    }

    @Test fun oneOutingLeftIsNear() {
        val status = SubscriptionPlanRules.status(weekly3, dates("2026-10-13", "2026-10-14"), today)
        assertEquals(PlanState.NEAR, status.state)
        assertEquals(1, status.remaining)
    }

    @Test fun aLargerCapIsNearAtEightyPercent() {
        val seven = dates("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06", "2026-10-07")
        assertEquals(PlanState.UNDER, SubscriptionPlanRules.status(monthly10, seven, today).state)
        assertEquals(PlanState.NEAR, SubscriptionPlanRules.status(monthly10, seven + dates("2026-10-08"), today).state)
    }

    @Test fun aCapOfOneIsUnderUntilUsed() {
        val plan = SubscriptionPlan(1, PlanPeriod.WEEK)
        assertEquals(PlanState.UNDER, SubscriptionPlanRules.status(plan, emptyList(), today).state)
        assertEquals(PlanState.AT_CAP, SubscriptionPlanRules.status(plan, dates("2026-10-14"), today).state)
    }

    @Test fun usingTheCapExactlyIsAtCapNotOver() {
        val status = SubscriptionPlanRules.status(weekly3, dates("2026-10-12", "2026-10-13", "2026-10-14"), today)
        assertEquals(PlanState.AT_CAP, status.state)
        assertEquals(0, status.remaining)
    }

    @Test fun oneMoreThanTheCapIsOver() {
        val status = SubscriptionPlanRules.status(weekly3, dates("2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15"), today)
        assertEquals(PlanState.OVER, status.state)
        assertEquals(-1, status.remaining)
    }

    @Test fun capValidationRejectsZeroNegativeAndHugeValues() {
        assertTrue(SubscriptionPlan.isValidCap(1))
        assertTrue(SubscriptionPlan.isValidCap(SubscriptionPlan.MAX_CAP))
        assertFalse(SubscriptionPlan.isValidCap(0))
        assertFalse(SubscriptionPlan.isValidCap(-1))
        assertFalse(SubscriptionPlan.isValidCap(SubscriptionPlan.MAX_CAP + 1))
    }

    @Test fun typedCapsParseOnlyAsWholeNumbers() {
        assertEquals(4, SubscriptionPlan.parseCap(" 4 "))
        assertNull(SubscriptionPlan.parseCap(""))
        assertNull(SubscriptionPlan.parseCap("2.5"))
        assertNull(SubscriptionPlan.parseCap("abc"))
        assertNull(SubscriptionPlan.parseCap("0"))
        assertNull(SubscriptionPlan.parseCap("101"))
    }

    @Test fun periodWireNamesRoundTripAndRejectUnknowns() {
        assertEquals(PlanPeriod.WEEK, PlanPeriod.fromWire("week"))
        assertEquals(PlanPeriod.MONTH, PlanPeriod.fromWire("month"))
        assertNull(PlanPeriod.fromWire("year"))
        assertNull(PlanPeriod.fromWire(null))
    }
}
