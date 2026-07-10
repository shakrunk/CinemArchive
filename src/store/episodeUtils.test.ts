import { describe, expect, it } from 'vitest'
import {
  avgEpisodeRating,
  avgSeasonRating,
  avgSeriesRating,
  episodesWatchedInSeason,
  totalEpisodesWatched,
  totalEpisodeCount,
  watchedMinutesInSeason,
  nextUnwatchedEpisode,
  allWatchEvents,
  getUnlockedModes,
  getEarnedModes,
} from './episodeUtils'
import { makeEpisode, makeRating, makeSeason, makeWatchEvent } from '@/test/fixtures'
import type { Title } from './mockData'

describe('avgEpisodeRating', () => {
  it('returns null when there are no ratings', () => {
    expect(avgEpisodeRating(makeEpisode())).toBeNull()
  })

  it('averages multiple ratings', () => {
    const episode = makeEpisode({ ratings: [makeRating(4), makeRating(5)] })
    expect(avgEpisodeRating(episode)).toBe(4.5)
  })
})

describe('avgSeasonRating', () => {
  it('returns null when the season has no episodes', () => {
    expect(avgSeasonRating(makeSeason())).toBeNull()
  })

  it('ignores unrated episodes and averages the rest', () => {
    const season = makeSeason({
      episodes: [
        makeEpisode({ ratings: [makeRating(3)] }),
        makeEpisode({ ratings: [] }),
        makeEpisode({ ratings: [makeRating(5)] }),
      ],
    })
    expect(avgSeasonRating(season)).toBe(4)
  })

  it('returns null when no episode in the season has a rating', () => {
    const season = makeSeason({ episodes: [makeEpisode(), makeEpisode()] })
    expect(avgSeasonRating(season)).toBeNull()
  })
})

describe('avgSeriesRating', () => {
  it('averages across seasons, skipping unrated seasons', () => {
    const rated = makeSeason({ episodes: [makeEpisode({ ratings: [makeRating(2)] })] })
    const unrated = makeSeason({ episodes: [makeEpisode()] })
    expect(avgSeriesRating([rated, unrated])).toBe(2)
  })

  it('returns null when given no seasons', () => {
    expect(avgSeriesRating([])).toBeNull()
  })
})

describe('episodesWatchedInSeason', () => {
  it('counts episodes with at least one watch event when episodes[] is present', () => {
    const season = makeSeason({
      episodesWatched: 99, // should be ignored in favor of episodes[]
      episodes: [
        makeEpisode({ watchEvents: [makeWatchEvent()] }),
        makeEpisode({ watchEvents: [] }),
      ],
    })
    expect(episodesWatchedInSeason(season)).toBe(1)
  })

  it('falls back to episodesWatched when episodes[] is absent', () => {
    expect(episodesWatchedInSeason(makeSeason({ episodesWatched: 3 }))).toBe(3)
  })
})

describe('totalEpisodesWatched / totalEpisodeCount', () => {
  const seasons = [
    makeSeason({ episodesWatched: 2, episodeCount: 10 }),
    makeSeason({ episodesWatched: 5, episodeCount: 8 }),
  ]

  it('sums watched episodes across seasons', () => {
    expect(totalEpisodesWatched(seasons)).toBe(7)
  })

  it('sums total episode counts across seasons', () => {
    expect(totalEpisodeCount(seasons)).toBe(18)
  })
})

describe('watchedMinutesInSeason', () => {
  it('returns 0 when the season has no episodes', () => {
    expect(watchedMinutesInSeason(makeSeason())).toBe(0)
  })

  it('sums runtime only for watched episodes', () => {
    const season = makeSeason({
      episodes: [
        makeEpisode({ runtime: 42, watchEvents: [makeWatchEvent()] }),
        makeEpisode({ runtime: 30, watchEvents: [] }),
        makeEpisode({ watchEvents: [makeWatchEvent()] }), // no runtime → treated as 0
      ],
    })
    expect(watchedMinutesInSeason(season)).toBe(42)
  })
})

describe('nextUnwatchedEpisode', () => {
  it('returns null when every episode has a watch event', () => {
    const seasons = [
      makeSeason({ seasonNumber: 1, episodes: [makeEpisode({ watchEvents: [makeWatchEvent()] })] }),
    ]
    expect(nextUnwatchedEpisode(seasons)).toBeNull()
  })

  it('returns null when seasons lack an episodes[] array', () => {
    expect(nextUnwatchedEpisode([makeSeason({ seasonNumber: 1 })])).toBeNull()
  })

  it('finds the first unwatched episode in season → episode order', () => {
    const s1e2 = makeEpisode({ episodeNumber: 2, watchEvents: [] })
    const s2e1 = makeEpisode({ episodeNumber: 1, watchEvents: [] })
    const seasons = [
      makeSeason({
        seasonNumber: 2,
        episodes: [s2e1],
      }),
      makeSeason({
        seasonNumber: 1,
        episodes: [makeEpisode({ episodeNumber: 1, watchEvents: [makeWatchEvent()] }), s1e2],
      }),
    ]
    const result = nextUnwatchedEpisode(seasons)
    expect(result?.season.seasonNumber).toBe(1)
    expect(result?.episode).toBe(s1e2)
  })
})

function makeTitle(seasons: Title['seasons']): Title {
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
    seasons,
  }
}

describe('allWatchEvents', () => {
  it('yields every watch event across all seasons/episodes in order', () => {
    const we1 = makeWatchEvent()
    const we2 = makeWatchEvent()
    const title = makeTitle([
      makeSeason({ episodes: [makeEpisode({ watchEvents: [we1] }), makeEpisode({ watchEvents: [we2] })] }),
    ])
    expect([...allWatchEvents(title)]).toEqual([we1, we2])
  })

  it('yields nothing for a title with no seasons', () => {
    expect([...allWatchEvents(makeTitle(undefined))]).toEqual([])
  })
})

describe('getUnlockedModes / getEarnedModes', () => {
  it('unlocks a mode as soon as one episode has a watch event in it', () => {
    const title = makeTitle([
      makeSeason({
        episodes: [
          makeEpisode({ watchEvents: [makeWatchEvent({ colorMode: 'bw' })] }),
          makeEpisode({ watchEvents: [] }),
        ],
      }),
    ])
    expect(getUnlockedModes(title)).toEqual(new Set(['bw']))
    expect(getEarnedModes(title)).toEqual(new Set())
  })

  it('earns a mode only when every episode has a watch event in it', () => {
    const title = makeTitle([
      makeSeason({
        episodes: [
          makeEpisode({ watchEvents: [makeWatchEvent({ colorMode: 'color' })] }),
          makeEpisode({ watchEvents: [makeWatchEvent({ colorMode: 'color' })] }),
        ],
      }),
    ])
    expect(getEarnedModes(title)).toEqual(new Set(['color']))
  })

  it('returns an empty set when the title has no episodes', () => {
    expect(getEarnedModes(makeTitle([makeSeason()]))).toEqual(new Set())
  })
})
