import { describe, expect, it } from 'vitest'
import { computeUpcomingTitles } from './upNext'
import type { CinemaOuting, Title } from './mockData'

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

function makeOuting(overrides: Partial<CinemaOuting> = {}): CinemaOuting {
  return {
    id: 'o1',
    titleId: 't1',
    showtime: '2026-07-17T19:30:00.000Z',
    previewsMinutes: 20,
    runtimeMinutes: 136,
    endsAt: '2026-07-17T22:06:00.000Z',
    companions: [],
    status: 'scheduled',
    createdAt: '2026-07-16T00:00:00.000Z',
    ...overrides,
  }
}

describe('computeUpcomingTitles', () => {
  it('excludes a watchlist title that has a scheduled cinema outing', () => {
    const titles = [makeTitle({ id: 't1' }), makeTitle({ id: 't2', title: 'Second Feature' })]
    const outings = [makeOuting({ titleId: 't1' })]
    const entries = computeUpcomingTitles(titles, '2026-07-01', outings)
    expect(entries.map((e) => e.title.id)).toEqual(['t2'])
  })

  it('does not exclude a title whose outing was cancelled', () => {
    const titles = [makeTitle({ id: 't1' })]
    const outings = [makeOuting({ titleId: 't1', status: 'cancelled' })]
    const entries = computeUpcomingTitles(titles, '2026-07-01', outings)
    expect(entries.map((e) => e.title.id)).toEqual(['t1'])
  })

  it('behaves exactly as before when no outings are passed', () => {
    const titles = [makeTitle({ id: 't1' })]
    expect(computeUpcomingTitles(titles, '2026-07-01').map((e) => e.title.id)).toEqual(['t1'])
  })
})
