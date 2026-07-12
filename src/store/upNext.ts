import type { CinemaOuting, Title, Season, Episode } from './mockData'
import { nextUnwatchedEpisode, totalEpisodesWatched, totalEpisodeCount, allWatchEvents } from './episodeUtils'

export interface UpNextEntry {
  title: Title
  season: Season
  episode: Episode
  watchedCount: number
  totalCount: number
  lastWatchedAt: string | null
}

export interface UpcomingEntry {
  title: Title
  /** YYYY-MM-DD if the title has a future release date, otherwise null (already
   *  released, or no known release date) — used to label/order the card. */
  releaseDate: string | null
}

/** Latest `watchedAt` across all of a title's episode watch events, or null.
 *  Dateless (pre-platform) watch events are skipped — they carry no recency signal. */
function lastWatchedAtForTitle(title: Title): string | null {
  let max: string | null = null
  for (const we of allWatchEvents(title)) {
    if (we.watchedAt && (max === null || we.watchedAt > max)) max = we.watchedAt
  }
  return max
}

/** All watchlist movies/tv — not just unreleased ones. Already-released (or
 *  undated) titles are actionable now, so they lead, sorted most-recently-added
 *  first; titles with a future releaseDate follow, sorted soonest first.
 *  Titles with a scheduled cinema outing are excluded — they render on the
 *  marquee instead of the watchlist section (plan §7.2). */
export function computeUpcomingTitles(titles: Title[], today: string, outings: CinemaOuting[] = []): UpcomingEntry[] {
  const scheduledTitleIds = new Set(
    outings.filter((o) => o.status === 'scheduled').map((o) => o.titleId)
  )
  const watchlist = titles.filter((t) => t.status === 'watchlist' && !scheduledTitleIds.has(t.id))

  const available = watchlist
    .filter((t) => !t.releaseDate || t.releaseDate <= today)
    .map((t): UpcomingEntry => ({ title: t, releaseDate: null }))
    .sort((a, b) => (a.title.addedAt < b.title.addedAt ? 1 : a.title.addedAt > b.title.addedAt ? -1 : 0))

  const upcoming = watchlist
    .filter((t) => t.releaseDate && t.releaseDate > today)
    .map((t): UpcomingEntry => ({ title: t, releaseDate: t.releaseDate! }))
    .sort((a, b) => (a.releaseDate! < b.releaseDate! ? -1 : a.releaseDate! > b.releaseDate! ? 1 : 0))

  return [...available, ...upcoming]
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
