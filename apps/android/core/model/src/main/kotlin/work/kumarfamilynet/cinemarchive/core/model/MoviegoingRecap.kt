package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate

/** One dated cinema trip with the facts a yearly recap tallies. [format] and [ticketPrice]
 *  come from the owner-private outing the viewing was logged through, so they are null for a
 *  trip logged by hand. */
data class MoviegoingTrip(
    val date: LocalDate,
    val venue: String?,
    val companions: List<String>,
    val format: String?,
    val ticketPrice: Double?,
)

/** A recap's leading venue, format or companion and how many trips it appeared in. */
data class RecapHighlight(val label: String, val count: Int)

/** A calendar year of moviegoing (docs/android-contracts/moviegoing-recap.md). [totalSpend] is
 *  null when no trip that year had a recorded ticket price. */
data class MoviegoingRecap(
    val year: Int,
    val tripCount: Int,
    val topVenue: RecapHighlight?,
    val topFormat: RecapHighlight?,
    val topCompanion: RecapHighlight?,
    val totalSpend: Double?,
)

object MoviegoingRecapRules {
    /** Years with at least one trip, most recent first. */
    fun years(trips: List<MoviegoingTrip>): List<Int> = trips.map { it.date.year }.distinct().sortedDescending()

    /** The year a recap opens on: the current one when it has trips, otherwise the latest year
     *  that does, otherwise null. */
    fun defaultYear(trips: List<MoviegoingTrip>, today: LocalDate): Int? {
        val years = years(trips)
        return if (today.year in years) today.year else years.firstOrNull()
    }

    /** The recap for [year], or null when that year has no trips. The leading venue, format and
     *  companion are the most frequent (labels trimmed, blanks ignored); a tie goes to the label
     *  that sorts first, so the same trips give the same card on every device and client. */
    fun recap(trips: List<MoviegoingTrip>, year: Int): MoviegoingRecap? {
        val inYear = trips.filter { it.date.year == year }
        if (inYear.isEmpty()) return null
        val prices = inYear.mapNotNull { it.ticketPrice }
        return MoviegoingRecap(
            year = year,
            tripCount = inYear.size,
            topVenue = top(inYear.mapNotNull { it.venue }),
            topFormat = top(inYear.mapNotNull { it.format }),
            topCompanion = top(inYear.flatMap { it.companions }),
            totalSpend = prices.takeIf { it.isNotEmpty() }?.sumOf(MoviegoingBudgetRules::cents)?.div(100.0),
        )
    }

    private fun top(labels: List<String>): RecapHighlight? = labels
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()
        .entries
        .minWithOrNull(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        ?.let { RecapHighlight(it.key, it.value) }
}

/** One labelled line of the recap card. */
data class RecapField(val label: String, val value: String)

/** The recap's wording, shared by the on-screen card, the shared image and the text summary so
 *  all three always show the same figures. */
object MoviegoingRecapText {
    /** The lines under the headline trip count, in print order; a figure the year lacks is left out. */
    fun fields(recap: MoviegoingRecap): List<RecapField> = listOfNotNull(
        recap.topVenue?.let { RecapField("top venue", it.label) },
        recap.topFormat?.let { RecapField("top format", it.label) },
        recap.topCompanion?.let { RecapField("most often with", it.label) },
        recap.totalSpend?.let { RecapField("total spent", money(it)) },
    )

    /** "My 2026 at the movies: 24 trips, top venue AMC, $312.50 spent." */
    fun summary(recap: MoviegoingRecap): String {
        val trips = "${recap.tripCount} ${if (recap.tripCount == 1) "trip" else "trips"}"
        val parts = listOfNotNull(
            trips,
            recap.topVenue?.let { "top venue ${it.label}" },
            recap.totalSpend?.let { "${money(it)} spent" },
        )
        return "My ${recap.year} at the movies: ${parts.joinToString(", ")}."
    }

    private fun money(amount: Double): String = MoviegoingRules.formatMoney(amount)
}
