import { describe, expect, it } from 'vitest'
import { parseGuids, planMerge, ratingFromTen, ratingToTen, toDateOnly, type SyncItem } from './core'
import type { Title } from '../../store/mockData'

const item = (over: Partial<SyncItem> = {}): SyncItem => ({
  provider: 'simkl', externalId: '1', type: 'movie', title: 'Heat', year: 1995,
  ids: {}, status: 'watched', watchedDates: [], ...over,
})
const title = (over: Partial<Title> = {}): Title =>
  ({ id: 't1', tmdbId: 1, type: 'movie', title: 'Heat', year: 1995, genres: [], status: 'watchlist', tags: [], addedAt: '2026-01-01', viewings: [], ...over }) as Title

describe('rating conversion', () => {
  it('halves 1–10 to half stars', () => {
    expect(ratingFromTen(10)).toBe(5)
    expect(ratingFromTen(7)).toBe(3.5)
    expect(ratingFromTen(1)).toBe(0.5)
    expect(ratingFromTen(0)).toBeUndefined()
    expect(ratingFromTen(undefined)).toBeUndefined()
  })
  it('doubles for push', () => {
    expect(ratingToTen(3.5)).toBe(7)
    expect(ratingToTen(0.5)).toBe(1)
    expect(ratingToTen(undefined)).toBeUndefined()
  })
})

describe('parseGuids', () => {
  it('reads modern and legacy guids', () => {
    expect(parseGuids(['tmdb://603', 'imdb://tt0133093', 'tvdb://78901'])).toEqual({ tmdb: 603, imdb: 'tt0133093', tvdb: 78901 })
    expect(parseGuids(['com.plexapp.agents.imdb://tt0133093?lang=en'])).toEqual({ imdb: 'tt0133093' })
  })
  it('ignores junk', () => {
    expect(parseGuids([undefined, 'plex://movie/5d77', 'imdb://nope'])).toEqual({})
  })
})

describe('toDateOnly', () => {
  it('normalizes', () => {
    expect(toDateOnly('2024-03-05T22:10:00Z')).toBe('2024-03-05')
    expect(toDateOnly('2024-03-05')).toBe('2024-03-05')
    expect(toDateOnly('garbage')).toBeUndefined()
  })
})

describe('planMerge', () => {
  it('is a no-op when nothing new', () => {
    const existing = title({ status: 'watched', rating: 4, viewings: [{ id: 'v', titleId: 't1', date: '2024-01-01' }] })
    expect(planMerge(existing, item({ rating: 2, watchedDates: ['2024-01-01'] }))).toBeNull()
  })
  it('never overwrites an existing rating', () => {
    expect(planMerge(title({ rating: 4, status: 'watched' }), item({ rating: 1 }))).toBeNull()
  })
  it('fills empty rating and promotes watchlist', () => {
    expect(planMerge(title(), item({ rating: 4 }))).toEqual({ status: 'watched', rating: 4 })
  })
  it('adds only unseen viewing dates', () => {
    const existing = title({ status: 'watched', viewings: [{ id: 'v', titleId: 't1', date: '2024-01-01' }] })
    const patch = planMerge(existing, item({ watchedDates: ['2024-01-01', '2024-02-02'] }))
    expect(patch?.viewings?.map((v) => v.date)).toEqual(['2024-02-02'])
  })
})
