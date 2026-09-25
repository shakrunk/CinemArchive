import type { Episode, EpisodeWatchEvent, Season, Title } from './mockData'

// ─── Canonical rating helpers — single source of truth for all rollups ───────

export function avgEpisodeRating(episode: Episode): number | null {
  if (episode.ratings.length === 0) return null
  return episode.ratings.reduce((sum, r) => sum + r.rating, 0) / episode.ratings.length
}

export function avgSeasonRating(season: Season): number | null {
  if (!season.episodes?.length) return null
  const rated = season.episodes
    .map(avgEpisodeRating)
    .filter((r): r is number => r !== null)
  if (rated.length === 0) return null
  return rated.reduce((sum, r) => sum + r, 0) / rated.length
}

export function avgSeriesRating(seasons: Season[]): number | null {
  const rated = seasons
    .map(avgSeasonRating)
    .filter((r): r is number => r !== null)
  if (rated.length === 0) return null
  return rated.reduce((sum, r) => sum + r, 0) / rated.length
}

// ─── Episode watch count helpers ─────────────────────────────────────────────

export function episodesWatchedInSeason(season: Season): number {
  if (season.episodes?.length) {
    return season.episodes.filter((e) => e.watchEvents.length > 0).length
  }
  return season.episodesWatched
}

export function totalEpisodesWatched(seasons: Season[]): number {
  return seasons.reduce((sum, s) => sum + episodesWatchedInSeason(s), 0)
}

export function totalEpisodeCount(seasons: Season[]): number {
  return seasons.reduce((sum, s) => sum + s.episodeCount, 0)
}

// ─── Runtime helpers ─────────────────────────────────────────────────────────

export function watchedMinutesInSeason(season: Season): number {
  if (!season.episodes?.length) return 0
  return season.episodes
    .filter((e) => e.watchEvents.length > 0)
    .reduce((sum, e) => sum + (e.runtime ?? 0), 0)
}

// ─── Up Next: next unwatched episode ─────────────────────────────────────────

/** First episode (ascending season → episode) with no watch events. Seasons
 *  lacking an `episodes[]` array (coarse-only progress) are skipped. */
export function nextUnwatchedEpisode(
  seasons: Season[]
): { season: Season; episode: Episode } | null {
  const orderedSeasons = [...seasons].sort((a, b) => a.seasonNumber - b.seasonNumber)
  for (const season of orderedSeasons) {
    if (!season.episodes || season.episodes.length === 0) continue
    const orderedEpisodes = [...season.episodes].sort((a, b) => a.episodeNumber - b.episodeNumber)
    for (const episode of orderedEpisodes) {
      if (episode.watchEvents.length === 0) return { season, episode }
    }
  }
  return null
}

/** Today's date as local YYYY-MM-DD — the format TMDB uses for `air_date`,
 *  so the two compare correctly as strings. */
export function localTodayYmd(now: Date = new Date()): string {
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

/** True when the episode has a known air date that is still in the future. */
export function isUnaired(episode: Episode, today: string = localTodayYmd()): boolean {
  return !!episode.airDate && episode.airDate > today
}

/** Earliest (ascending season → episode) episode with a known air date after
 *  `today` — the next scheduled broadcast. Episodes without an air date are
 *  skipped: TMDB lists placeholder episodes with no date that aren't scheduled. */
export function nextScheduledEpisode(
  seasons: Season[],
  today: string = localTodayYmd()
): { season: Season; episode: Episode } | null {
  const orderedSeasons = [...seasons].sort((a, b) => a.seasonNumber - b.seasonNumber)
  for (const season of orderedSeasons) {
    const orderedEpisodes = [...(season.episodes ?? [])].sort((a, b) => a.episodeNumber - b.episodeNumber)
    for (const episode of orderedEpisodes) {
      if (isUnaired(episode, today)) return { season, episode }
    }
  }
  return null
}

// ─── Cross-title episode watch-event traversal ───────────────────────────────

/** Every watch event across all of a title's seasons/episodes, in season →
 *  episode order. Single traversal shared by everything that needs to scan a
 *  title's full watch history rather than one season/episode at a time. */
export function* allWatchEvents(title: Title): Generator<EpisodeWatchEvent> {
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) {
      yield* ep.watchEvents
    }
  }
}

// ─── Spider-Noir color mode progression ──────────────────────────────────────

/** Modes with at least one episode watch event recorded in that mode. */
export function getUnlockedModes(title: Title): Set<'bw' | 'color'> {
  const modes = new Set<'bw' | 'color'>()
  for (const we of allWatchEvents(title)) {
    if (we.colorMode) modes.add(we.colorMode)
  }
  return modes
}

/** Modes where every episode has at least one watch event in that mode. */
export function getEarnedModes(title: Title): Set<'bw' | 'color'> {
  const allEps = (title.seasons ?? []).flatMap((s) => s.episodes ?? [])
  if (allEps.length === 0) return new Set()
  const earned = new Set<'bw' | 'color'>()
  for (const mode of ['bw', 'color'] as const) {
    if (allEps.every((ep) => ep.watchEvents.some((we) => we.colorMode === mode))) {
      earned.add(mode)
    }
  }
  return earned
}
