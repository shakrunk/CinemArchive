package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoviegoingRecapTest {
    private fun trip(
        date: String,
        venue: String? = null,
        companions: List<String> = emptyList(),
        format: String? = null,
        price: Double? = null,
    ) = MoviegoingTrip(LocalDate.parse(date), venue, companions, format, price)

    @Test fun yearsListsOnlyYearsWithTripsNewestFirst() {
        val trips = listOf(trip("2024-03-01"), trip("2026-01-01"), trip("2024-12-31"))
        assertEquals(listOf(2026, 2024), MoviegoingRecapRules.years(trips))
        assertEquals(emptyList<Int>(), MoviegoingRecapRules.years(emptyList()))
    }

    @Test fun defaultYearIsThisYearWhenItHasTripsElseTheLatestOne() {
        val trips = listOf(trip("2024-03-01"), trip("2025-06-01"))
        assertEquals(2025, MoviegoingRecapRules.defaultYear(trips, LocalDate.parse("2026-10-29")))
        assertEquals(2025, MoviegoingRecapRules.defaultYear(trips, LocalDate.parse("2025-02-01")))
        assertNull(MoviegoingRecapRules.defaultYear(emptyList(), LocalDate.parse("2026-10-29")))
    }

    @Test fun aYearWithNoTripsHasNoRecap() {
        assertNull(MoviegoingRecapRules.recap(listOf(trip("2025-06-01")), 2026))
        assertNull(MoviegoingRecapRules.recap(emptyList(), 2026))
    }

    @Test fun onlyTripsOnJanFirstThroughDecThirtyFirstOfTheYearCount() {
        val trips = listOf(trip("2025-12-31"), trip("2026-01-01"), trip("2026-12-31"), trip("2027-01-01"))
        assertEquals(2, MoviegoingRecapRules.recap(trips, 2026)!!.tripCount)
    }

    @Test fun leadingVenueFormatAndCompanionAreTheMostFrequent() {
        val trips = listOf(
            trip("2026-01-05", "AMC", listOf("Sam", "Jordan"), "IMAX"),
            trip("2026-02-05", "Alamo", listOf("Sam"), "IMAX"),
            trip("2026-03-05", "AMC", listOf("Jordan", "Sam"), "Standard"),
        )
        val recap = MoviegoingRecapRules.recap(trips, 2026)!!
        assertEquals(RecapHighlight("AMC", 2), recap.topVenue)
        assertEquals(RecapHighlight("IMAX", 2), recap.topFormat)
        assertEquals(RecapHighlight("Sam", 3), recap.topCompanion)
    }

    @Test fun tiesGoToTheLabelThatSortsFirst() {
        val trips = listOf(trip("2026-01-05", "Regal"), trip("2026-02-05", "AMC"))
        assertEquals(RecapHighlight("AMC", 1), MoviegoingRecapRules.recap(trips, 2026)!!.topVenue)
    }

    @Test fun otherYearsDoNotLeakIntoTheTallies() {
        val trips = listOf(
            trip("2025-01-05", "AMC"), trip("2025-02-05", "AMC"), trip("2025-03-05", "AMC"),
            trip("2026-01-05", "Alamo"),
        )
        assertEquals(RecapHighlight("Alamo", 1), MoviegoingRecapRules.recap(trips, 2026)!!.topVenue)
    }

    @Test fun missingFactsLeaveTheHighlightAbsent() {
        val recap = MoviegoingRecapRules.recap(listOf(trip("2026-01-05")), 2026)!!
        assertNull(recap.topVenue)
        assertNull(recap.topFormat)
        assertNull(recap.topCompanion)
        assertNull(recap.totalSpend)
        assertEquals(1, recap.tripCount)
    }

    @Test fun blankLabelsAreIgnored() {
        val recap = MoviegoingRecapRules.recap(listOf(trip("2026-01-05", venue = "  ", companions = listOf(""))), 2026)!!
        assertNull(recap.topVenue)
        assertNull(recap.topCompanion)
    }

    @Test fun spendSumsOnlyPricedTripsInWholeCents() {
        val trips = listOf(
            trip("2026-01-05", price = 13.1),
            trip("2026-02-05", price = 13.1),
            trip("2026-03-05", price = 13.1),
            trip("2026-04-05"), // unpriced: counts as a trip, adds no spend
            trip("2025-04-05", price = 99.0),
        )
        val recap = MoviegoingRecapRules.recap(trips, 2026)!!
        assertEquals(4, recap.tripCount)
        assertEquals(39.3, recap.totalSpend!!, 0.0)
    }

    @Test fun aZeroPricedTripStillMakesSpendKnown() {
        assertEquals(0.0, MoviegoingRecapRules.recap(listOf(trip("2026-01-05", price = 0.0)), 2026)!!.totalSpend!!, 0.0)
    }
}
