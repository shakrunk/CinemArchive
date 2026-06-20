import type { Episode, Season } from './mockData'

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
