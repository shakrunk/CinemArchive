import { describe, expect, it, vi } from 'vitest'
import {
  SCHEDULE_CHECK_INTERVAL_MS,
  runEpisodeScheduleRefresh,
  scheduleMutations,
  scheduleRowIds,
  seasonsToCheck,
  seriesDueForScheduleCheck,
} from './episodeSchedule'
import { catalogEpisodeId, catalogSeasonId } from './catalogRowIds'
import { applyMutation } from './offline/replay'
import { createCommand } from './offline/commands'
import { assertDeliverableCommand, libraryOperations } from './offlineRpc'
import type { OfflineSnapshot } from './offline/snapshot'
import type { RawTmdbEpisode, SeasonFetchResult, TvSchedule } from './media'
import type { Episode, Season, Title } from 'src/store/mockData'

vi.mock('./auth', () => ({ supabase: null, isSupabaseConfigured: false }))

const TITLE_ID = '6f1c1d2e-3a4b-4c5d-8e9f-0a1b2c3d4e5f'
const TODAY = '2026-10-18'
const NOW = new Date('2026-10-18T12:00:00')

const episode = (n: number, airDate?: string, extra: Partial<Episode> = {}): Episode => ({
  id: `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`, episodeNumber: n, episodeName: `Episode ${n}`, airDate,
  watchEvents: [], ratings: [], reviews: [], ...extra,
})
const season = (n: number, episodes: Episode[], extra: Partial<Season> = {}): Season => ({
  id: `00000000-0000-4000-9000-${String(n).padStart(12, '0')}`, seasonNumber: n, episodeCount: episodes.length, episodesWatched: 0, episodes, ...extra,
})
const series = (seasons: Season[], extra: Partial<Title> = {}): Title => ({
  id: TITLE_ID, tmdbId: 1399, type: 'tv', title: 'Severance', year: 2022, genres: [], tags: [],
  status: 'watching', addedAt: '2025-01-01', viewings: [], seasons, ...extra,
})
const tmdbEp = (n: number, air_date?: string, name = `Episode ${n}`): RawTmdbEpisode => ({ episode_number: n, name, air_date, overview: '', crew: [] })
const tmdbSeason = (episodes: RawTmdbEpisode[]): SeasonFetchResult => ({ episodes, cast: [] })

// Two watched seasons; TMDB has since announced a dated third.
const watched = (n: number) => episode(n, '2022-02-18', { watchEvents: [{ id: `11111111-1111-4111-8111-${String(n).padStart(12, '0')}`, watchedAt: '2022-03-01T20:00:00Z' }] })
const twoSeasons = () => series([season(1, [watched(1), watched(2)], { episodesWatched: 2 }), season(2, [watched(1), watched(2)], { episodesWatched: 2 })])
const withThird: TvSchedule = {
  status: 'Returning Series', nextEpisodeSeason: 3,
  seasons: [{ season_number: 1, episode_count: 2 }, { season_number: 2, episode_count: 2 }, { season_number: 3, episode_count: 3 }],
}

function snapshotOf(titles: Title[]): OfflineSnapshot {
  return { titles, outings: [], lists: [], listMemberships: {}, pinnedModes: {}, ledgerWidgets: [] }
}

describe('seriesDueForScheduleCheck', () => {
  it('picks tracked, non-dropped series not checked in the last day, oldest first', () => {
    const now = NOW.getTime()
    const titles = [
      series([], { id: 'fresh' }),
      series([], { id: 'stale' }),
      series([], { id: 'never' }),
      series([], { id: 'dropped', status: 'dropped' }),
      series([], { id: 'unlinked', tmdbId: undefined }),
      { ...series([], { id: 'movie' }), type: 'movie' as const },
    ]
    const checked = { fresh: now - 1000, stale: now - SCHEDULE_CHECK_INTERVAL_MS - 1 }

    expect(seriesDueForScheduleCheck(titles, checked, now).map((t) => t.id)).toEqual(['never', 'stale'])
    expect(seriesDueForScheduleCheck(titles, checked, now, 1).map((t) => t.id)).toEqual(['never'])
  })
})

describe('seasonsToCheck', () => {
  it('fetches a newly announced season', () => {
    expect(seasonsToCheck(twoSeasons(), withThird, TODAY)).toEqual([3])
  })

  it('fetches a stored season TMDB now counts more episodes for, even once the show ended', () => {
    const schedule: TvSchedule = { status: 'Ended', seasons: [{ season_number: 1, episode_count: 2 }, { season_number: 2, episode_count: 3 }] }
    expect(seasonsToCheck(twoSeasons(), schedule, TODAY)).toEqual([2])
  })

  it('re-checks undated or upcoming episodes only while the show is running', () => {
    const title = series([season(1, [episode(1, '2026-01-01'), episode(2, '2026-11-01'), episode(3)])])
    const counts = [{ season_number: 1, episode_count: 3 }]
    expect(seasonsToCheck(title, { status: 'Returning Series', seasons: counts }, TODAY)).toEqual([1])
    expect(seasonsToCheck(title, { status: 'Canceled', seasons: counts }, TODAY)).toEqual([])
  })

  it('skips finished seasons, Specials TMDB adds and coarse-count seasons', () => {
    const coarse = series([season(1, [watched(1), watched(2)]), season(2, [], { episodeCount: 8, episodesWatched: 3 })])
    const schedule: TvSchedule = {
      status: 'Returning Series',
      seasons: [{ season_number: 0, episode_count: 4 }, { season_number: 1, episode_count: 2 }, { season_number: 2, episode_count: 10 }],
    }
    expect(seasonsToCheck(coarse, schedule, TODAY)).toEqual([])
  })
})

describe('scheduleMutations', () => {
  async function build(title: Title, fetched: Map<number, SeasonFetchResult>) {
    return scheduleMutations(title, fetched, await scheduleRowIds(title.id, fetched))
  }

  it('adds a new season with its dated episodes under catalog IDs', async () => {
    const fetched = new Map([[3, tmdbSeason([tmdbEp(2, '2026-11-07'), tmdbEp(1, '2026-10-31'), tmdbEp(3)])]])
    const [mutation, ...rest] = await build(twoSeasons(), fetched)

    expect(rest).toEqual([])
    expect(mutation.kind).toBe('season.put')
    if (mutation.kind !== 'season.put') return
    expect(mutation.season).toMatchObject({ id: await catalogSeasonId(TITLE_ID, 3), seasonNumber: 3, episodeCount: 3, episodesWatched: 0 })
    expect(mutation.season.episodes?.map((ep) => [ep.episodeNumber, ep.airDate])).toEqual([[1, '2026-10-31'], [2, '2026-11-07'], [3, undefined]])
    expect(mutation.season.episodes?.[0].id).toBe(await catalogEpisodeId(TITLE_ID, 3, 1))
    expect(() => assertDeliverableCommand(createCommand({ projectId: 'p', userId: 'u' }, mutation))).not.toThrow()
  })

  it('moves a changed air date and appends a newly listed episode without resending stored rows', async () => {
    const stored = season(1, [watched(1), episode(2, '2026-11-01', { crew: [{ tmdbPersonId: 7, name: 'Ben Stiller', job: 'Director' }] })], { episodesWatched: 1, cast: [{ tmdbPersonId: 9, name: 'Adam Scott', order: 0 }] })
    const title = series([stored])
    const fetched = new Map([[1, tmdbSeason([tmdbEp(1, '2022-02-18'), tmdbEp(2, '2026-11-08'), tmdbEp(3, '2026-11-15', 'Finale')])]])

    const mutations = await build(title, fetched)

    expect(mutations[0]).toEqual({ kind: 'episode.metadata', titleId: TITLE_ID, episodeId: stored.episodes![1].id, patch: { airDate: '2026-11-08' } })
    expect(mutations[1].kind).toBe('season.put')
    if (mutations[1].kind !== 'season.put') return
    expect(mutations[1].season.cast).toBeUndefined()
    expect(mutations[1].season.episodes?.map((ep) => ep.episodeNumber)).toEqual([3])
    expect(mutations[1].season.episodeCount).toBe(3)

    // Delivery inserts only the new row and leaves season cast and stored episode crew alone.
    const operations = libraryOperations(createCommand({ projectId: 'p', userId: 'u' }, mutations[1]))
    expect(operations.filter((op) => op.table === 'episodes' && op.action === 'insert').map((op) => op.values?.episode_number)).toEqual([3])
    expect(operations.some((op) => op.table === 'season_cast' || op.action === 'delete')).toBe(false)

    // The local projection keeps watch history and airing order.
    const next = mutations.reduce(applyMutation, snapshotOf([title])).titles[0].seasons![0]
    expect(next.episodes?.map((ep) => [ep.episodeNumber, ep.airDate, ep.watchEvents.length])).toEqual([[1, '2022-02-18', 1], [2, '2026-11-08', 0], [3, '2026-11-15', 0]])
    expect(next.episodesWatched).toBe(1)
    expect(next.cast).toEqual(stored.cast)
  })

  it('is idempotent: a second pass over its own result changes nothing', async () => {
    const fetched = new Map([
      [2, tmdbSeason([tmdbEp(1, '2022-02-18'), tmdbEp(2, '2022-02-18'), tmdbEp(3, '2026-10-25')])],
      [3, tmdbSeason([tmdbEp(1, '2026-10-31')])],
    ])
    const title = twoSeasons()
    const first = await build(title, fetched)
    expect(first.length).toBeGreaterThan(0)

    const updated = first.reduce(applyMutation, snapshotOf([title])).titles[0]
    expect(await build(updated, fetched)).toEqual([])
  })

  it('never adds rows to a coarse-count season', async () => {
    const title = series([season(1, [], { episodeCount: 8, episodesWatched: 3 })])
    expect(await build(title, new Map([[1, tmdbSeason([tmdbEp(1, '2026-01-01')])]]))).toEqual([])
  })
})

describe('runEpisodeScheduleRefresh', () => {
  function harness(titles: Title[], checkedAt: Record<string, number> = {}) {
    let state = snapshotOf(titles)
    const stamps = { value: checkedAt }
    const fetchSeason = vi.fn(async (_id: number, n: number) => n === 3
      ? tmdbSeason([tmdbEp(1, '2026-10-31'), tmdbEp(2, '2026-11-07')])
      : tmdbSeason([tmdbEp(1, '2022-02-18'), tmdbEp(2, '2022-02-18')]))
    const fetchSchedule = vi.fn(async (): Promise<TvSchedule> => withThird)
    const submit = vi.fn(async (titleId: string, build: (title: Title) => ReturnType<typeof scheduleMutations>) => {
      const title = state.titles.find((t) => t.id === titleId)
      if (title) state = build(title).reduce(applyMutation, state)
    })
    const run = () => runEpisodeScheduleRefresh({
      now: () => NOW,
      getTitles: () => state.titles,
      isCurrent: () => true,
      readCheckedAt: () => stamps.value,
      writeCheckedAt: (next) => { stamps.value = next },
      submit, fetchSchedule, fetchSeason,
    })
    return { run, state: () => state, stamps, fetchSchedule, fetchSeason, submit }
  }

  it('puts a newly announced season on the schedule without user action', async () => {
    const h = harness([twoSeasons()])

    expect(await h.run()).toBe(1)

    const seasons = h.state().titles[0].seasons!
    expect(seasons.map((s) => s.seasonNumber)).toEqual([1, 2, 3])
    expect(seasons[2].episodes?.map((ep) => ep.airDate)).toEqual(['2026-10-31', '2026-11-07'])
    expect(seasons[0].episodes?.every((ep) => ep.watchEvents.length === 1)).toBe(true)
    expect(h.fetchSeason).toHaveBeenCalledTimes(1)
    expect(h.stamps.value[TITLE_ID]).toBe(NOW.getTime())
  })

  it('checks each series at most once a day', async () => {
    const h = harness([twoSeasons()], { [TITLE_ID]: NOW.getTime() - 60_000 })
    expect(await h.run()).toBe(0)
    expect(h.fetchSchedule).not.toHaveBeenCalled()
  })

  it('stamps series before fetching, and forgets titles no longer in the library', async () => {
    const h = harness([twoSeasons()], { gone: 1 })
    h.fetchSchedule.mockImplementation(async () => {
      expect(h.stamps.value).toEqual({ [TITLE_ID]: NOW.getTime() })
      throw new Error('offline')
    })
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

    expect(await h.run()).toBe(0)
    expect(h.submit).not.toHaveBeenCalled()
    warn.mockRestore()
  })

  it('writes nothing for a series dropped while its seasons were fetched', async () => {
    const h = harness([twoSeasons()])
    h.fetchSeason.mockImplementation(async () => {
      h.state().titles[0].status = 'dropped'
      return tmdbSeason([tmdbEp(1, '2026-10-31')])
    })
    expect(await h.run()).toBe(0)
    expect(h.state().titles[0].seasons).toHaveLength(2)
  })
})

describe('catalog row IDs', () => {
  it('are stable RFC 4122 v5 UUIDs per title and number', async () => {
    const id = await catalogEpisodeId(TITLE_ID, 3, 1)
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(await catalogEpisodeId(TITLE_ID, 3, 1)).toBe(id)
    expect(await catalogEpisodeId(TITLE_ID, 3, 2)).not.toBe(id)
    expect(await catalogSeasonId(TITLE_ID, 3)).not.toBe(await catalogSeasonId('7f1c1d2e-3a4b-4c5d-8e9f-0a1b2c3d4e5f', 3))
  })
})
