package work.kumarfamilynet.cinemarchive.data

import work.kumarfamilynet.cinemarchive.core.model.MediaEpisode
import work.kumarfamilynet.cinemarchive.core.model.MediaSeason

// Pure planning for catalog growth: which TMDB seasons/episodes a stored series is missing, and
// which known episodes have a new air date or name. Shared by the manual metadata refresh
// (EpisodeCatalogFill.kt) and the background schedule check (EpisodeScheduleRefresher.kt).
// Mirrors the web app's apps/web/src/lib/refreshMetadata.ts and apps/web/src/lib/episodeSchedule.ts.

/** How long a series stays checked. Matches the media-proxy cache TTL. */
internal const val SCHEDULE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
/** Series checked per run, least recently checked first. */
internal const val SCHEDULE_CHECK_BATCH = 20

private val FINISHED_STATUSES = setOf("Ended", "Canceled")

/** The local catalog graph of one series, as the planner needs it. */
internal data class StoredSeason(
    val id: String,
    val seasonNumber: Int,
    val episodeCount: Int,
    val updatedAt: String?,
    val episodes: List<StoredEpisode>,
)

internal data class StoredEpisode(val id: String, val episodeNumber: Int, val name: String?, val airDate: String?)

/** TMDB-only series schedule from the `details` proxy action: status, season list, next episode. */
data class TvSchedule(
    /** TMDB series status, e.g. "Returning Series", "Ended", "Canceled". */
    val status: String?,
    /** Season of TMDB's next_episode_to_air, when one is announced. */
    val nextEpisodeSeason: Int?,
    val seasons: List<ScheduleSeason>,
)

data class ScheduleSeason(val seasonNumber: Int, val episodeCount: Int, val airYear: Int? = null)

internal data class ScheduleCandidate(val id: String, val tmdbId: Int, val type: String, val status: String)

/** Series eligible for a schedule check, least recently checked first. */
internal fun seriesDueForScheduleCheck(
    titles: List<ScheduleCandidate>,
    checkedAt: Map<String, Long>,
    now: Long,
    limit: Int = SCHEDULE_CHECK_BATCH,
): List<ScheduleCandidate> = titles
    .filter { it.type == "TV" && it.status != "DROPPED" && it.tmdbId > 0 }
    .filter { now - (checkedAt[it.id] ?: 0L) >= SCHEDULE_CHECK_INTERVAL_MS }
    .sortedBy { checkedAt[it.id] ?: 0L }
    .take(limit)

/**
 * Season numbers worth a season-details fetch:
 * - a season TMDB lists that isn't stored yet (newly announced);
 * - a stored season TMDB now counts more episodes for;
 * - for a series still running, a stored season with an undated or upcoming episode (dates get
 *   set and moved), and the season of the next episode.
 * A stored season without episode rows (an old coarse count) is left to a manual refresh.
 */
internal fun seasonsToCheck(stored: List<StoredSeason>, schedule: TvSchedule, today: String): List<Int> {
    val byNumber = stored.associateBy { it.seasonNumber }
    val running = schedule.status !in FINISHED_STATUSES
    val due = sortedSetOf<Int>()
    for (tmdb in schedule.seasons) {
        val season = byNumber[tmdb.seasonNumber]
        if (season == null) {
            if (tmdb.seasonNumber >= 1 && tmdb.episodeCount > 0) due += tmdb.seasonNumber
            continue
        }
        if (season.episodes.isEmpty()) continue
        if (tmdb.episodeCount > season.episodes.size) due += tmdb.seasonNumber
        else if (running && season.episodes.any { it.airDate.isNullOrBlank() || it.airDate >= today }) due += tmdb.seasonNumber
    }
    val next = schedule.nextEpisodeSeason
    if (running && next != null) {
        val season = byNumber[next]
        if (if (season == null) next >= 1 else season.episodes.isNotEmpty()) due += next
    }
    return due.toList()
}

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
