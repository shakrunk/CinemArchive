package work.kumarfamilynet.cinemarchive.data

import work.kumarfamilynet.cinemarchive.core.model.MediaEpisode

/**
 * Seam for [LibraryRepository.backfillEpisodeMetadata]'s on-demand TMDB fetch — implemented by
 * [DiscoverRepository] for the real network call, faked in tests. Kept as its own narrow
 * interface (rather than having [LibraryRepository] depend on the full [DiscoverRepository])
 * so a Room-backed repository test doesn't need a real [SupabaseRestClient]/[AuthRepository] to
 * construct.
 */
interface EpisodeMetadataFetcher {
    /** Episode rows for one season, or empty on any failure — degrades the same way
     *  [DiscoverRepository.fetchDetails]'s own per-season calls do. */
    suspend fun fetchSeasonEpisodes(tmdbId: Int, seasonNumber: Int): List<MediaEpisode>
}
