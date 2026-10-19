import { describe, expect, it } from 'vitest'
import { computeCalendarEvents, formatEpisodeRange, groupEventsByDay, monthGrid } from './calendar'
import { localTodayYmd } from './episodeUtils'
import type { CinemaOuting, Episode, Season, Title } from './mockData'

const TODAY = '2026-10-18'

function makeTitle(overrides: Partial<Title> = {}): Title {
  return {
    id: 't1',
    tmdbId: 1,
    type: 'movie',
    title: 'The Long Reel',
    year: 2026,
    genres: [],
    tags: [],
    status: 'watchlist',
    addedAt: '2026-01-01',
    viewings: [],
    ...overrides,
  }
}

function makeEpisode(episodeNumber: number, airDate?: string, episodeName?: string): Episode {
  return { id: `e${episodeNumber}`, episodeNumber, airDate, episodeName, watchEvents: [], ratings: [], reviews: [] }
}

function makeSeason(seasonNumber: number, episodes: Episode[]): Season {
  return { id: `s${seasonNumber}`, seasonNumber, episodeCount: episodes.length, episodesWatched: 0, episodes }
}

function makeShow(overrides: Partial<Title> = {}): Title {
  return makeTitle({ id: 'tv1', type: 'tv', title: 'Night Shift', status: 'watching', ...overrides })
}

function makeOuting(overrides: Partial<CinemaOuting> = {}): CinemaOuting {
  return {
    id: 'o1',
    titleId: 't1',
    showtime: '2026-10-20T19:30:00',
    previewsMinutes: 20,
    runtimeMinutes: 136,
    endsAt: '2026-10-20T22:06:00',
    companions: [],
    seats: [],
    status: 'scheduled',
    createdAt: '2026-10-01T00:00:00.000Z',
    ...overrides,
  }
}

describe('computeCalendarEvents', () => {
  it('lists watchlist movies releasing today or later, soonest first', () => {
    const titles = [
      makeTitle({ id: 'a', title: 'Later', releaseDate: '2026-12-01' }),
      makeTitle({ id: 'b', title: 'Today', releaseDate: TODAY }),
      makeTitle({ id: 'c', title: 'Past', releaseDate: '2026-10-17' }),
      makeTitle({ id: 'd', title: 'Watched', status: 'watched', releaseDate: '2026-11-01' }),
      makeTitle({ id: 'e', title: 'Undated' }),
    ]
    const events = computeCalendarEvents(titles, [], TODAY)
    expect(events.map((e) => [e.kind, e.title.title, e.date])).toEqual([
      ['release', 'Today', TODAY],
      ['release', 'Later', '2026-12-01'],
    ])
  })

  it('lists future dated episodes of every non-dropped series, skipping undated ones', () => {
    const seasons = [makeSeason(1, [makeEpisode(1, '2026-10-01'), makeEpisode(2, '2026-10-25', 'The Turn'), makeEpisode(3)])]
    const titles = [
      makeShow({ id: 'watching', seasons }),
      makeShow({ id: 'finished', title: 'Finished Show', status: 'watched', seasons }),
      makeShow({ id: 'dropped', title: 'Dropped Show', status: 'dropped', seasons }),
    ]
    const events = computeCalendarEvents(titles, [], TODAY)
    expect(events.map((e) => e.title.id)).toEqual(['finished', 'watching'])
    expect(events[0]).toMatchObject({ kind: 'episodes', date: '2026-10-25', seasonNumber: 1, episodeNumbers: [2], episodeName: 'The Turn' })
  })

  it('folds a same-day season drop into one event without a single episode name', () => {
    const show = makeShow({
      seasons: [makeSeason(2, [makeEpisode(3, '2026-11-01', 'C'), makeEpisode(1, '2026-11-01', 'A'), makeEpisode(2, '2026-11-01', 'B')])],
    })
    const events = computeCalendarEvents([show], [], TODAY)
    expect(events).toHaveLength(1)
    expect(events[0]).toMatchObject({ kind: 'episodes', episodeNumbers: [1, 2, 3], episodeName: undefined })
  })

  it('includes specials as their own season', () => {
    const show = makeShow({ seasons: [makeSeason(0, [makeEpisode(1, '2026-12-24', 'Holiday Special')])] })
    expect(computeCalendarEvents([show], [], TODAY)[0]).toMatchObject({ kind: 'episodes', seasonNumber: 0 })
  })

  it('adds a premiere for a watchlist series unless an episode already airs that day', () => {
    const withoutEpisodes = makeShow({ id: 'p1', status: 'watchlist', releaseDate: '2026-11-05' })
    const withPilot = makeShow({
      id: 'p2',
      title: 'Pilot Known',
      status: 'watchlist',
      releaseDate: '2026-11-06',
      seasons: [makeSeason(1, [makeEpisode(1, '2026-11-06')])],
    })
    const events = computeCalendarEvents([withoutEpisodes, withPilot], [], TODAY)
    expect(events.map((e) => [e.kind, e.title.id])).toEqual([
      ['premiere', 'p1'],
      ['episodes', 'p2'],
    ])
  })

  it('includes scheduled outings by local showtime day and drops other statuses', () => {
    const movie = makeTitle({ status: 'watchlist' })
    const outings = [
      makeOuting({ id: 'late', showtime: '2026-10-20T21:00:00' }),
      makeOuting({ id: 'early', showtime: '2026-10-20T13:00:00' }),
      makeOuting({ id: 'cancelled', status: 'cancelled' }),
      makeOuting({ id: 'orphan', titleId: 'missing' }),
    ]
    const events = computeCalendarEvents([movie], outings, TODAY)
    expect(events.map((e) => e.id)).toEqual(['outing:early', 'outing:late'])
    expect(events[0].date).toBe('2026-10-20')
  })

  it('orders same-day events outing, premiere, release, then episodes', () => {
    const day = '2026-11-10'
    const titles = [
      makeShow({ id: 'ep', seasons: [makeSeason(1, [makeEpisode(1, day)])] }),
      makeTitle({ id: 'rel', releaseDate: day }),
      makeShow({ id: 'prem', status: 'watchlist', releaseDate: day }),
    ]
    const outings = [makeOuting({ titleId: 'rel', showtime: `${day}T19:00:00` })]
    const events = computeCalendarEvents(titles, outings, TODAY)
    expect(events.map((e) => e.kind)).toEqual(['outing', 'premiere', 'release', 'episodes'])
  })

  it('treats the local calendar day, not the UTC one, as today', () => {
    // 23:30 local on Oct 18 — in any zone west of UTC this instant is already
    // Oct 19 in UTC, which must not hide an episode airing on the 18th.
    const lateEvening = new Date(2026, 9, 18, 23, 30)
    const today = localTodayYmd(lateEvening)
    expect(today).toBe('2026-10-18')
    const show = makeShow({ seasons: [makeSeason(1, [makeEpisode(1, '2026-10-18')])] })
    expect(computeCalendarEvents([show], [], today)).toHaveLength(1)
  })
})

describe('groupEventsByDay', () => {
  it('groups consecutive events by date', () => {
    const titles = [
      makeTitle({ id: 'a', title: 'A', releaseDate: '2026-11-01' }),
      makeTitle({ id: 'b', title: 'B', releaseDate: '2026-11-01' }),
      makeTitle({ id: 'c', title: 'C', releaseDate: '2026-11-02' }),
    ]
    const days = groupEventsByDay(computeCalendarEvents(titles, [], TODAY))
    expect(days.map((d) => [d.date, d.events.length])).toEqual([
      ['2026-11-01', 2],
      ['2026-11-02', 1],
    ])
  })
})

describe('formatEpisodeRange', () => {
  it('formats single, contiguous and gapped episode lists', () => {
    expect(formatEpisodeRange([4])).toBe('E4')
    expect(formatEpisodeRange([1, 2, 3, 4])).toBe('E1–E4')
    expect(formatEpisodeRange([1, 3])).toBe('E1, E3')
  })
})

describe('monthGrid', () => {
  it('pads to whole Sunday-first weeks', () => {
    // October 2026 starts on a Thursday and has 31 days.
    const cells = monthGrid(2026, 9)
    expect(cells.length % 7).toBe(0)
    expect(cells.slice(0, 4)).toEqual([null, null, null, null])
    expect(cells[4]).toBe('2026-10-01')
    expect(cells.filter(Boolean)).toHaveLength(31)
  })
})
