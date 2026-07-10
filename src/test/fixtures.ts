import type { Episode, EpisodeRating, EpisodeWatchEvent, Season } from '@/store/mockData'

let counter = 0
function nextId(prefix: string): string {
  counter += 1
  return `${prefix}-${counter}`
}

export function makeWatchEvent(overrides: Partial<EpisodeWatchEvent> = {}): EpisodeWatchEvent {
  return { id: nextId('we'), watchedAt: '2024-01-01', ...overrides }
}

export function makeRating(rating: number, overrides: Partial<EpisodeRating> = {}): EpisodeRating {
  return { id: nextId('rating'), rating, ratedAt: '2024-01-01T00:00:00.000Z', ...overrides }
}

export function makeEpisode(overrides: Partial<Episode> = {}): Episode {
  return {
    id: nextId('ep'),
    episodeNumber: 1,
    watchEvents: [],
    ratings: [],
    reviews: [],
    ...overrides,
  }
}

export function makeSeason(overrides: Partial<Season> = {}): Season {
  return {
    id: nextId('season'),
    seasonNumber: 1,
    episodeCount: 0,
    episodesWatched: 0,
    ...overrides,
  }
}
