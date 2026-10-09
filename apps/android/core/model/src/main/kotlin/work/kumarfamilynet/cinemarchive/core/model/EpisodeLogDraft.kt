package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate

/** IDs and timestamp belong to one submission and are retained if saving needs a retry. */
data class EpisodeLogDraft(
    val watchEventId: String,
    val ratingId: String,
    val reviewId: String,
    val recordedAt: String,
    val includeWatch: Boolean,
    val watchedAt: String?,
    val watchNotes: String? = null,
    val rating: Double? = null,
    val reviewText: String? = null,
    val colorMode: String? = null,
) {
    init {
        require(colorMode == null || colorMode in setOf("bw", "color")) { "Unknown episode color mode" }
        require(watchEventId.isNotBlank() && ratingId.isNotBlank() && reviewId.isNotBlank())
        watchedAt?.let { LocalDate.parse(it) }
        require(rating == null || (rating.isFinite() && rating in 0.5..5.0 && rating * 2 % 1 == 0.0))
        require(includeWatch || rating != null || !reviewText.isNullOrBlank()) { "Add a watch, rating, or review" }
    }
}
