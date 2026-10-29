package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoviegoingBudgetTest {
    private val today = LocalDate.parse("2026-10-18")
    private val monthly = MoviegoingBudget(40.0, BudgetPeriod.MONTH)
    private val yearly = MoviegoingBudget(200.0, BudgetPeriod.YEAR)

    private fun trip(date: String, amount: Double) = MoviegoingPricedTrip(LocalDate.parse(date), amount)

    @Test fun monthRangeCoversTheWholeCalendarMonth() {
        val range = MoviegoingBudgetRules.periodRange(BudgetPeriod.MONTH, today)
        assertEquals(LocalDate.parse("2026-10-01"), range.start)
        assertEquals(LocalDate.parse("2026-10-31"), range.endInclusive)
    }

    @Test fun yearRangeCoversTheWholeCalendarYear() {
        val range = MoviegoingBudgetRules.periodRange(BudgetPeriod.YEAR, today)
        assertEquals(LocalDate.parse("2026-01-01"), range.start)
        assertEquals(LocalDate.parse("2026-12-31"), range.endInclusive)
    }

    @Test fun februaryEndsOnItsLastDayInALeapYear() {
        val range = MoviegoingBudgetRules.periodRange(BudgetPeriod.MONTH, LocalDate.parse("2028-02-10"))
        assertEquals(LocalDate.parse("2028-02-29"), range.endInclusive)
    }

    @Test fun onlyTripsInsideThePeriodCount() {
        val trips = listOf(
            trip("2026-09-30", 15.0), // last day of the previous month
            trip("2026-10-01", 12.0), // first day of this month
            trip("2026-10-31", 8.0), // last day of this month
            trip("2026-11-01", 20.0), // first day of next month
        )
        val status = MoviegoingBudgetRules.status(monthly, trips, today)
        assertEquals(20.0, status.spent, 0.0)
        assertEquals(BudgetState.UNDER, status.state)
    }

    @Test fun aYearBudgetCountsEveryMonthOfThatYear() {
        val trips = listOf(trip("2025-12-31", 50.0), trip("2026-01-01", 30.0), trip("2026-10-05", 25.0))
        val status = MoviegoingBudgetRules.status(yearly, trips, today)
        assertEquals(55.0, status.spent, 0.0)
        assertEquals(145.0, status.remaining, 0.0)
    }

    @Test fun belowEightyPercentIsUnderAndExactlyEightyIsNear() {
        assertEquals(BudgetState.UNDER, MoviegoingBudgetRules.status(monthly, listOf(trip("2026-10-02", 31.99)), today).state)
        assertEquals(BudgetState.NEAR, MoviegoingBudgetRules.status(monthly, listOf(trip("2026-10-02", 32.0)), today).state)
    }

    @Test fun spendingExactlyTheBudgetIsNearNotOver() {
        val status = MoviegoingBudgetRules.status(monthly, listOf(trip("2026-10-02", 40.0)), today)
        assertEquals(BudgetState.NEAR, status.state)
        assertEquals(0.0, status.remaining, 0.0)
    }

    @Test fun oneCentPastTheBudgetIsOver() {
        val status = MoviegoingBudgetRules.status(monthly, listOf(trip("2026-10-02", 40.01)), today)
        assertEquals(BudgetState.OVER, status.state)
        assertTrue(status.fraction > 1.0)
    }

    @Test fun floatingPointDriftDoesNotPushAMetBudgetOver() {
        // 0.1 + 0.2 style drift: three 13.1 tickets are 39.3, never 39.300000000000004 over 39.3.
        val budget = MoviegoingBudget(39.3, BudgetPeriod.MONTH)
        val trips = List(3) { trip("2026-10-0${it + 1}", 13.1) }
        assertEquals(BudgetState.NEAR, MoviegoingBudgetRules.status(budget, trips, today).state)
    }

    @Test fun noTripsIsUnderWithZeroSpent() {
        val status = MoviegoingBudgetRules.status(monthly, emptyList(), today)
        assertEquals(0.0, status.spent, 0.0)
        assertEquals(BudgetState.UNDER, status.state)
    }

    @Test fun amountValidationRejectsNonPositiveNonFiniteAndHugeValues() {
        assertTrue(MoviegoingBudget.isValidAmount(0.01))
        assertTrue(MoviegoingBudget.isValidAmount(MoviegoingBudget.MAX_AMOUNT))
        assertFalse(MoviegoingBudget.isValidAmount(0.0))
        assertFalse(MoviegoingBudget.isValidAmount(-5.0))
        assertFalse(MoviegoingBudget.isValidAmount(Double.NaN))
        assertFalse(MoviegoingBudget.isValidAmount(Double.POSITIVE_INFINITY))
        assertFalse(MoviegoingBudget.isValidAmount(MoviegoingBudget.MAX_AMOUNT + 1))
    }

    @Test fun periodWireNamesRoundTripAndRejectUnknowns() {
        assertEquals(BudgetPeriod.MONTH, BudgetPeriod.fromWire("month"))
        assertEquals(BudgetPeriod.YEAR, BudgetPeriod.fromWire("year"))
        assertNull(BudgetPeriod.fromWire("week"))
        assertNull(BudgetPeriod.fromWire(null))
    }
}
