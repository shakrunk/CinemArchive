import { describe, expect, it } from 'vitest'
import { computeLedgerStats } from './ledgerStats'
import { makeEpisode, makeSeason, makeWatchEvent } from '@/test/fixtures'
import type { Title, WatchStatus } from './mockData'

function makeTitle(overrides: Partial<Title> = {}): Title {
  return {
    id: crypto.randomUUID(),
    tmdbId: 1,
    type: 'movie',
    title: 'Test Title',
    year: 2020,
    genres: [],
    status: 'watched',
    tags: [],
    addedAt: '2024-01-01T00:00:00.000Z',
    viewings: [],
    ...overrides,
  }
}

const film = (status: WatchStatus, runtime: number) => makeTitle({ status, runtime })

describe('computeLedgerStats totalMinutes', () => {
  it('counts only watched films', () => {
    const stats = computeLedgerStats([
      film('watched', 120),
      film('watchlist', 90),
      film('dropped', 100),
      film('watching', 110),
    ])
    expect(stats.totalMinutes).toBe(120)
  })

  it('counts a series by the runtime of its watched episodes, regardless of status', () => {
    const series = makeTitle({
      type: 'tv',
      status: 'watching',
      seasons: [
        makeSeason({
          episodeCount: 2,
          episodes: [makeEpisode({ runtime: 45, watchEvents: [makeWatchEvent()] }), makeEpisode({ runtime: 50 })],
        }),
      ],
    })
    expect(computeLedgerStats([series]).totalMinutes).toBe(45)
  })
})
