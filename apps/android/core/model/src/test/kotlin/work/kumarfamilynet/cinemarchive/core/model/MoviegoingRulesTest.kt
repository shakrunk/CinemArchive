package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared parsing and formatting cases; the web twins are in moviegoingRules.test.ts. */
class MoviegoingRulesTest {
    @Test fun parsesTypedBudgetAmounts() {
        for (case in MoviegoingFixture.cases("amounts")) {
            assertEquals(case.getString("text"), case.doubleOrNull("amount"), MoviegoingBudget.parseAmount(case.getString("text")))
        }
    }

    @Test fun parsesTypedPlanCaps() {
        for (case in MoviegoingFixture.cases("caps")) {
            assertEquals(case.getString("text"), case.intOrNull("cap"), SubscriptionPlan.parseCap(case.getString("text")))
        }
    }

    @Test fun readsOnlyRealCalendarDates() {
        for (case in MoviegoingFixture.cases("tripDates")) {
            val expected = case.stringOrNull("date")?.let(LocalDate::parse)
            assertEquals(case.opt("value").toString(), expected, MoviegoingRules.parseTripDate(case.stringOrNull("value")))
        }
    }

    @Test fun aViewingIsATripOnlyWithANonEmptyVenue() {
        for (case in MoviegoingFixture.cases("tripVenues")) {
            assertEquals(case.opt("venue").toString(), case.getBoolean("isTrip"), MoviegoingRules.isTripVenue(case.stringOrNull("venue")))
        }
    }

    @Test fun acceptsOnlyUtcInstantsForUpdatedAt() {
        for (case in MoviegoingFixture.cases("timestamps")) {
            val parsed = MoviegoingRules.parseInstant(case.getString("value"))
            assertEquals(case.getString("value"), case.getBoolean("valid"), parsed != null)
        }
    }

    @Test fun formatsMoneyFromWholeCents() {
        for (case in MoviegoingFixture.cases("money")) {
            assertEquals(case.getDouble("amount").toString(), case.getString("text"), MoviegoingRules.formatMoney(case.getDouble("amount")))
        }
    }

    @Test fun anInstantKeepsItsSubSecondPrecision() {
        val older = MoviegoingRules.parseInstant("2026-10-29T10:00:00.100Z")
        val newer = MoviegoingRules.parseInstant("2026-10-29T10:00:00.200Z")
        assertTrue(newer!!.isAfter(older))
        assertNull(MoviegoingRules.parseInstant(null))
    }
}
