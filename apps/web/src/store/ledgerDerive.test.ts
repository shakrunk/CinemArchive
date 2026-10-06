import { describe, expect, it } from 'vitest'
import { deriveProgress } from './ledgerDerive'
import { makeEpisode, makeSeason, makeWatchEvent } from '@/test/fixtures'
import type { Title } from './mockData'

function makeShow(overrides: Partial<Title> = {}): Title {
  return {
    id: 't1',
    tmdbId: 1,
    type: 'tv',
    title: 'Test Show',
    year: 2020,
    genres: [],
    status: 'watching',
    tags: [],
    addedAt: '2024-01-01T00:00:00.000Z',
    viewings: [],
    ...overrides,
  }
}

const watched = () => makeEpisode({ watchEvents: [makeWatchEvent()] })

describe('deriveProgress', () => {
  it('counts watched episodes from watch events, not the stored episodesWatched column', () => {
    // The DB column lags behind: episode logging never writes it back.
    const title = makeShow({
      seasons: [
        makeSeason({ seasonNumber: 1, episodeCount: 3, episodesWatched: 1, episodes: [watched(), watched(), watched()] }),
        makeSeason({ seasonNumber: 2, episodeCount: 2, episodesWatched: 0, episodes: [watched(), makeEpisode()] }),
      ],
    })
    const [row] = deriveProgress([title])
    expect(row).toMatchObject({ watched: 4, total: 5 })
    expect(row.pct).toBeCloseTo(0.8)
  })

  it('falls back to the stored count for seasons without episode-level data', () => {
    const title = makeShow({
      seasons: [makeSeason({ seasonNumber: 1, episodeCount: 10, episodesWatched: 4 })],
    })
    expect(deriveProgress([title])[0]).toMatchObject({ watched: 4, total: 10 })
  })

  it('excludes Specials from the rollup', () => {
    const title = makeShow({
      seasons: [
        makeSeason({ seasonNumber: 0, episodeCount: 2, episodes: [watched(), watched()] }),
        makeSeason({ seasonNumber: 1, episodeCount: 2, episodes: [watched(), makeEpisode()] }),
      ],
    })
    expect(deriveProgress([title])[0]).toMatchObject({ watched: 1, total: 2 })
  })

  it('surfaces a partially watched series even when its status is not "watching"', () => {
    const title = makeShow({
      status: 'watched',
      seasons: [makeSeason({ seasonNumber: 1, episodeCount: 2, episodesWatched: 2, episodes: [watched(), makeEpisode()] })],
    })
    expect(deriveProgress([title])).toHaveLength(1)
  })
})
