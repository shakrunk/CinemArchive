import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchMediaDetails, fetchSeasonDetails, type SearchResult } from 'src/lib/media'
import { fetchRefreshedTitlePatch, titleToSearchResult } from 'src/lib/refreshMetadata'
import type { Title } from 'src/store/mockData'

vi.mock('src/lib/media', async (original) => ({
  ...await original<typeof import('src/lib/media')>(),
  fetchMediaDetails: vi.fn(),
  fetchSeasonDetails: vi.fn(),
}))

const series: Title = {
  id: 'dark', tmdbId: 70523, type: 'tv', title: 'Dark', year: 2017,
  genres: [], tags: [], status: 'watchlist', addedAt: '2026-09-16', viewings: [],
}

const details: SearchResult = { tmdbId: 70523, type: 'tv', title: 'Dark', year: 2017, genres: [], seasonCount: 3 }

const episode = (n: number) => ({ episode_number: n, name: `Episode ${n}`, air_date: '2017-12-01', runtime: 50, overview: '', still_path: null, crew: [] })

beforeEach(() => {
  vi.mocked(fetchMediaDetails).mockResolvedValue({
    result: details,
    tmdbSeasons: [
      { season_number: 0, episode_count: 0 },
      { season_number: 2, episode_count: 2 },
      { season_number: 1, episode_count: 3 },
    ] as never,
  })
  vi.mocked(fetchSeasonDetails).mockImplementation(async (_id, seasonNumber) => ({
    episodes: Array.from({ length: seasonNumber === 1 ? 3 : 2 }, (_, i) => episode(i + 1)) as never,
    cast: [],
  }))
})

describe('fetchRefreshedTitlePatch', () => {
  it('builds seasons and episodes from TMDB for a series saved with none', async () => {
    const patch = await fetchRefreshedTitlePatch(series, titleToSearchResult(series))

    expect(patch.seasons?.map((s) => [s.seasonNumber, s.episodes?.length])).toEqual([[1, 3], [2, 2]])
    expect(patch.seasons?.every((s) => s.episodesWatched === 0)).toBe(true)
  })

  it('leaves seasons out for a series TMDB lists no seasons for', async () => {
    vi.mocked(fetchMediaDetails).mockResolvedValue({ result: details, tmdbSeasons: [] })

    const patch = await fetchRefreshedTitlePatch(series, titleToSearchResult(series))

    expect(patch.seasons).toBeUndefined()
  })
})
