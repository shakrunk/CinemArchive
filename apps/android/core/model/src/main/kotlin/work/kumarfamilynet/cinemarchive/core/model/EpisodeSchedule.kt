package work.kumarfamilynet.cinemarchive.core.model

import java.time.LocalDate

/**
 * Air-date helpers for the title detail screen's "next episode" callout — mirrors
 * `isUnaired` / `nextScheduledEpisode` in the web app's `src/store/episodeUtils.ts`.
 * TMDB's `air_date` is a plain YYYY-MM-DD, so it compares correctly against
 * [LocalDate.toString] as a string without any timezone handling.
 */

/** True when the episode has a known air date that is still in the future. */
fun EpisodeDetail.isUnaired(today: LocalDate = LocalDate.now()): Boolean =
    airDate?.let { it > today.toString() } ?: false

data class ScheduledEpisode(val season: SeasonDetail, val episode: EpisodeDetail)

/** Earliest (ascending season → episode) episode with a known air date after [today] — the
 *  next scheduled broadcast. Undated episodes are skipped: TMDB lists placeholders with no
 *  date that aren't actually scheduled. */
fun List<SeasonDetail>.nextScheduledEpisode(today: LocalDate = LocalDate.now()): ScheduledEpisode? {
    for (season in sortedBy { it.seasonNumber }) {
        val episode = season.episodes.sortedBy { it.episodeNumber }.firstOrNull { it.isUnaired(today) }
        if (episode != null) return ScheduledEpisode(season, episode)
    }
    return null
}
