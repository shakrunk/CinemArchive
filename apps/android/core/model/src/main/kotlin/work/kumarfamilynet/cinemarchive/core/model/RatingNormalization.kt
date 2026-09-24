package work.kumarfamilynet.cinemarchive.core.model

import java.text.Collator
import kotlin.math.sqrt

enum class RatingBaseline { ALL, MEDIA }

/** One current title rating, never one observation per viewing or episode. */
data class RatingObservation(val titleId: String, val title: String, val type: MediaType, val rating: Double?)

data class RatingReference(
    val type: MediaType?,
    val label: String,
    val count: Int,
    val mean: Double?,
    val deviation: Double?,
    val percentiles: Map<Double, Double>,
)

data class NormalizedRating(
    val title: RatingObservation,
    val zScore: Double?,
    val percentile: Double,
    val baseline: RatingReference,
)

data class RatingNormalization(val groups: List<RatingReference>, val rows: List<NormalizedRating>)

/** Mirrors web ratingNormalization.ts: population SD, empirical midrank, and display-only scope. */
fun deriveRatingNormalization(
    titles: List<RatingObservation>,
    baseline: RatingBaseline = RatingBaseline.ALL,
    scope: String = "all",
): RatingNormalization {
    val rated = titles.filter { it.rating?.let { r -> r.isFinite() && r in 0.0..5.0 } == true }
    fun summarize(type: MediaType?, label: String): RatingReference {
        val ratings = rated.filter { type == null || it.type == type }.map { it.rating!! }
        val mean = ratings.takeIf { it.isNotEmpty() }?.average()
        val deviation = mean?.let { m -> sqrt(ratings.sumOf { (it - m) * (it - m) } / ratings.size) }
        var below = 0
        val percentiles = ratings.groupingBy { it }.eachCount().toSortedMap().mapValues { (_, tied) ->
            val rank = 100.0 * (below + tied / 2.0) / ratings.size
            below += tied
            rank
        }
        return RatingReference(type, label, ratings.size, mean, deviation, percentiles)
    }
    val groups = if (baseline == RatingBaseline.ALL) listOf(summarize(null, "All titles")) else listOf(
        summarize(MediaType.MOVIE, "Films"), summarize(MediaType.TV, "Series"),
    )
    val collator = Collator.getInstance()
    val rows = rated.filter { scope == "all" || it.type == if (scope == "movies") MediaType.MOVIE else MediaType.TV }
        .map { title ->
            val group = groups.first { it.type == null || it.type == title.type }
            val z = if (group.count >= 2 && (group.deviation ?: 0.0) > 1e-12) {
                (title.rating!! - group.mean!!) / group.deviation!!
            } else null
            NormalizedRating(title, z, group.percentiles.getValue(title.rating!!), group)
        }.sortedWith(
            compareByDescending<NormalizedRating> { it.zScore ?: Double.NEGATIVE_INFINITY }
                .thenByDescending { it.percentile }
                .thenComparator { a, b -> collator.compare(a.title.title, b.title.title) },
        )
    return RatingNormalization(groups, rows)
}
