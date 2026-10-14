import { createElement } from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import fixture from '../../../../docs/android-contracts/fixtures/calculation-parity.json'
import type { Title } from './mockData'
import { useAppStore } from './useAppStore'
import { computeLedgerStats } from './ledgerStats'
import { deriveMonthlySeries, deriveProgress, deriveRevivals, deriveTopActors, deriveTrajectory } from './ledgerDerive'
import { avgEpisodeRating, episodesWatchedInSeason } from './episodeUtils'
import { computeUpNextShows } from './upNext'
import { RuntimeSpectrum } from '../views/ledger/panels/RuntimeSpectrum'

// Identical fixture bytes are read by Android SharedCalculationParityTest.
// Only date tokens are adapted; every assertion invokes shipped calculations.
const today = new Date()
const year = today.getFullYear()
const ymd = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
const titles = JSON.parse(JSON.stringify(fixture.titles)
  .replaceAll('@beforeYearStart', `${year - 1}-12-31`)
  .replaceAll('@yearStart', `${year}-01-01`)
  .replaceAll('@today', ymd(today))) as Title[]
const expected = fixture.expected

beforeEach(() => {
  useAppStore.setState({ titles })
  useAppStore.getState().resetFilters()
})
afterEach(cleanup)

describe('shared Library and Ledger calculation graph', () => {
  it('uses mixed coarse/detailed progress and historical episode ratings without counting Specials or rewatches twice', () => {
    const show = titles.find((title) => title.id === 'show')!
    expect(show.seasons!.map(episodesWatchedInSeason)).toEqual(expected.progress.seasonWatched)
    const episode = show.seasons![1].episodes![0]
    expect(avgEpisodeRating(episode)).toBe(expected.progress.episodeAverage)
    expect(episode.ratings[0].rating).toBe(expected.progress.latestEpisodeRating)
    expect(episode.watchEvents).toHaveLength(expected.progress.episodeWatchCount)
    const [next] = computeUpNextShows(titles)
    expect(next.watchedCount).toBe(expected.progress.watched)
    expect(next.totalCount).toBe(expected.progress.total)
    expect(next.season.seasonNumber).toBe(expected.progress.nextSeason)
    expect(next.episode.episodeNumber).toBe(expected.progress.nextEpisode)
    expect(deriveProgress(titles)).toMatchObject([{ id: expected.progress.id, watched: expected.progress.watched, total: expected.progress.total }])
  })

  it('counts watched movies and distinct watched episodes including Specials in the actual hero rollup', () => {
    const stats = computeLedgerStats(titles)
    expect(stats).toMatchObject({
      totalMovies: expected.stats.movies, totalSeries: expected.stats.series,
      totalViewings: expected.stats.viewings, avgRating: expected.stats.averageRating,
      totalMinutes: expected.stats.totalMinutes,
    })
    expect(Math.round(stats.totalMinutes / 60)).toBe(expected.stats.roundedHours)
    expect(deriveTopActors(titles).map((actor) => actor.count)).toEqual(expected.actorCounts)
    expect(deriveTopActors(titles).map((actor) => actor.tmdbPersonId)).toEqual([42, 99])
  })

  it('renders actual runtime buckets excluding missing and zero runtime', () => {
    render(createElement(RuntimeSpectrum))
    const labels = ['Short & sweet', 'Standard feature', 'The long haul', 'An epic']
    expect(labels.map((label) => Number(screen.getByText(label).parentElement!.parentElement!.lastElementChild!.textContent)))
      .toEqual(expected.runtimeBuckets)
  })

  it.each(['all', 'ytd'] as const)('retains historical premiere meaning through the inclusive %s date window', (timeRange) => {
    const e = expected.ranges[timeRange]
    const revivals = deriveRevivals(titles, { timeRange })
    expect(revivals.reduce((sum, month) => sum + month.premieres, 0)).toBe(e.premieres)
    expect(revivals.reduce((sum, month) => sum + month.revivals, 0)).toBe(e.revivals)
    expect(deriveTrajectory(titles, { timeRange }).points.reduce((sum, point) => sum + point.count, 0)).toBe(e.trajectoryTitles)
    expect(deriveMonthlySeries(titles, { timeRange }).reduce((sum, month) => sum + month.count, 0)).toBe(e.datedViewings)
  })

  it('filters same-named people by identity and sorts current title ratings rather than historical viewing ratings', () => {
    for (const testCase of expected.filterCases) {
      useAppStore.getState().resetFilters()
      useAppStore.getState().setFilter('person', { id: testCase.personId, name: 'Same Name' })
      useAppStore.getState().setFilter('sortField', 'title')
      useAppStore.getState().setFilter('sortDir', 'asc')
      expect(useAppStore.getState().filteredTitles.map((title) => title.id)).toEqual(testCase.expected)
    }
    useAppStore.getState().resetFilters()
    useAppStore.getState().setFilter('sortField', 'rating')
    useAppStore.getState().setFilter('sortDir', 'desc')
    expect(useAppStore.getState().filteredTitles.map((title) => title.id)).toEqual(expected.ratingDescending)
  })
})
