import type { Title, Season, Episode } from './mockData'
import { nextUnwatchedEpisode, totalEpisodesWatched, totalEpisodeCount } from './episodeUtils'

export interface UpNextEntry {
  title: Title
  season: Season
  episode: Episode
  watchedCount: number
  totalCount: number
  lastWatchedAt: string | null
}

/** Latest `watchedAt` across all of a title's episode watch events, or null. */
function lastWatchedAtForTitle(title: Title): string | null {
  let max: string | null = null
  for (const season of title.seasons ?? []) {
    for (const episode of season.episodes ?? []) {
      for (const we of episode.watchEvents) {
        if (max === null || we.watchedAt > max) max = we.watchedAt
      }
    }
  }
  return max
}

/** In-progress TV shows (status 'watching') that have a next unwatched episode,
 *  sorted most-recent-activity first by `(lastWatchedAt ?? addedAt)` descending. */
export function computeUpNextShows(titles: Title[]): UpNextEntry[] {
  const entries: UpNextEntry[] = []
  for (const title of titles) {
    if (title.type !== 'tv' || title.status !== 'watching') continue
    if (!title.seasons || title.seasons.length === 0) continue
    const next = nextUnwatchedEpisode(title.seasons)
    if (!next) continue
    entries.push({
      title,
      season: next.season,
      episode: next.episode,
      watchedCount: totalEpisodesWatched(title.seasons),
      totalCount: totalEpisodeCount(title.seasons),
      lastWatchedAt: lastWatchedAtForTitle(title),
    })
  }
  entries.sort((a, b) => {
    const aTs = a.lastWatchedAt ?? a.title.addedAt
    const bTs = b.lastWatchedAt ?? b.title.addedAt
    if (aTs < bTs) return 1
    if (aTs > bTs) return -1
    return 0
  })
  return entries
}
