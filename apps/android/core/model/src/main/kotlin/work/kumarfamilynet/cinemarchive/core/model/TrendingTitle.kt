package work.kumarfamilynet.cinemarchive.core.model

/** A single trending movie/show from TMDB's `trending/{type}/week` endpoint (via the
 *  `media-proxy` Edge Function's `trending` action) — read-only and not persisted locally;
 *  backs the Discover tab's live catalog. */
data class TrendingTitle(
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val type: MediaType,
    val posterUrl: String?,
    val synopsis: String?,
)
