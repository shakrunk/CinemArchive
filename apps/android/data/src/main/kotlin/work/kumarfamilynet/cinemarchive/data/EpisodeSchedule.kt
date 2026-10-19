package work.kumarfamilynet.cinemarchive.data

import work.kumarfamilynet.cinemarchive.core.model.MediaEpisode
import work.kumarfamilynet.cinemarchive.core.model.MediaSeason

// Pure planning for catalog growth: which TMDB seasons/episodes a stored series is missing, and
// which known episodes have a new air date or name. Used by the manual metadata refresh
// (EpisodeCatalogFill.kt). Mirrors the web app's apps/web/src/lib/refreshMetadata.ts.

/** The local catalog graph of one series, as the planner needs it. */
internal data class StoredSeason(
    val id: String,
    val seasonNumber: Int,
    val episodeCount: Int,
    val updatedAt: String?,
    val episodes: List<StoredEpisode>,
)

internal data class StoredEpisode(val id: String, val episodeNumber: Int, val name: String?, val airDate: String?)

/** Rows to add for one season. [newSeasonId] is null for a stored season or a new Specials season. */
internal data class SeasonAddition(
    val season: MediaSeason,
    val storedSeasonId: String?,
    val newSeasonId: String?,
    /** New episode rows with their catalog IDs, in episode order. */
    val episodes: List<Pair<String, MediaEpisode>>,
    /** Episode count to record on a stored season that grew, with its observed revision. */
    val grownCount: Int?,
    val expectedUpdatedAt: String?,
)

/**
 * Seasons and episodes [remote] lists that the stored graph does not hold yet. Existing rows keep
 * their IDs and history; new rows get the catalog IDs every client derives (CatalogRowIds.kt).
 * A season TMDB returned no episodes for is never added as an empty shell.
 *
 * [fillCoarse]: also fill a stored season with no episode rows (manual refresh only). A new
 * Specials season keeps a natural-key parent: the web app gives that shell a random ID.
 */
internal fun catalogAdditions(titleId: String, stored: List<StoredSeason>, remote: List<MediaSeason>, fillCoarse: Boolean): List<SeasonAddition> {
    val byNumber = stored.associateBy { it.seasonNumber }
    return remote.filter { it.seasonNumber >= 0 && it.episodes.isNotEmpty() }
        .distinctBy { it.seasonNumber }
        .sortedBy { it.seasonNumber }
        .mapNotNull { season ->
            val local = byNumber[season.seasonNumber]
            if (local != null && local.episodes.isEmpty() && !fillCoarse) return@mapNotNull null
            val known = local?.episodes.orEmpty().map { it.episodeNumber }.toSet()
            val added = season.episodes.filter { it.episodeNumber >= 0 && it.episodeNumber !in known }
                .distinctBy { it.episodeNumber }.sortedBy { it.episodeNumber }
                .map { catalogEpisodeId(titleId, season.seasonNumber, it.episodeNumber) to it }
            if (added.isEmpty()) return@mapNotNull null
            val total = local?.episodes.orEmpty().size + added.size
            // Only grow a stored count: a coarse count may include watched progress beyond the rows.
            val grown = if (local != null && local.updatedAt != null && total > local.episodeCount) total else null
            SeasonAddition(
                season = season,
                storedSeasonId = local?.id,
                newSeasonId = if (local == null && season.seasonNumber >= 1) catalogSeasonId(titleId, season.seasonNumber) else null,
                episodes = added,
                grownCount = grown,
                expectedUpdatedAt = local?.updatedAt?.takeIf { grown != null },
            )
        }
}

/** A changed air date and/or name for a stored episode; null fields are unchanged. */
internal data class EpisodeScheduleChange(val episodeId: String, val airDate: String?, val name: String?)

/** Air date/name changes TMDB lists for stored episodes. Idempotent: a re-run yields none. */
internal fun episodeScheduleChanges(stored: List<StoredSeason>, remote: List<MediaSeason>): List<EpisodeScheduleChange> {
    val remoteBySeason = remote.associateBy { it.seasonNumber }
    return stored.sortedBy { it.seasonNumber }.flatMap { season ->
        val episodes = remoteBySeason[season.seasonNumber]?.episodes?.associateBy { it.episodeNumber } ?: return@flatMap emptyList()
        season.episodes.sortedBy { it.episodeNumber }.mapNotNull { episode ->
            val tmdb = episodes[episode.episodeNumber] ?: return@mapNotNull null
            val airDate = tmdb.airDate?.takeIf { it.isNotBlank() && it != episode.airDate }
            val name = tmdb.name?.takeIf { it.isNotBlank() && it != episode.name }
            if (airDate == null && name == null) null else EpisodeScheduleChange(episode.id, airDate, name)
        }
    }
}
