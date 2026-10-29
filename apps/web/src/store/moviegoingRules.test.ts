import { describe, expect, it } from 'vitest'
import type { CinemaOuting, Title } from './mockData'
import {
  budgetRange, budgetStatus, budgetSummary, defaultRecapYear, deriveMoviegoingTrips, isValidBudgetAmount, isValidPlanCap,
  moviegoingRecap, parseBudgetAmount, parsePlanCap, planRange, planStatus, planSummary, recapFields, recapSummary,
  recapYears, tripDate, type MoviegoingBudget, type MoviegoingTrip, type SubscriptionPlan,
} from './moviegoingRules'

// Each block pins the same cases as Android's MoviegoingBudgetTest, SubscriptionPlanTest and
// MoviegoingRecapTest (docs/android-contracts/moviegoing-prefs.md, moviegoing-recap.md).

const money = (n: number) => `$${n.toFixed(2)}`
const priced = (date: string, ticketPrice: number): MoviegoingTrip => ({ date, companions: [], ticketPrice })
const trip = (date: string, extra: Partial<MoviegoingTrip> = {}): MoviegoingTrip => ({ date, companions: [], ...extra })

describe('budget (#209)', () => {
  const today = '2026-10-18'
  const monthly: MoviegoingBudget = { amount: 40, period: 'month' }
  const yearly: MoviegoingBudget = { amount: 200, period: 'year' }

  it('month and year ranges cover the whole calendar period', () => {
    expect(budgetRange('month', today)).toEqual({ start: '2026-10-01', end: '2026-10-31' })
    expect(budgetRange('year', today)).toEqual({ start: '2026-01-01', end: '2026-12-31' })
    expect(budgetRange('month', '2028-02-10').end).toBe('2028-02-29')
  })

  it('counts only trips inside the period, boundaries included', () => {
    const status = budgetStatus(monthly, [
      priced('2026-09-30', 15), priced('2026-10-01', 12), priced('2026-10-31', 8), priced('2026-11-01', 20),
    ], today)
    expect(status.spent).toBe(20)
    expect(status.state).toBe('under')
  })

  it('a year budget counts every month of that year', () => {
    const status = budgetStatus(yearly, [priced('2025-12-31', 50), priced('2026-01-01', 30), priced('2026-10-05', 25)], today)
    expect(status.spent).toBe(55)
    expect(status.remaining).toBe(145)
  })

  it('unpriced trips add nothing', () => {
    expect(budgetStatus(monthly, [trip('2026-10-02'), priced('2026-10-03', 10)], today).spent).toBe(10)
  })

  it('below 80% is under and exactly 80% is near', () => {
    expect(budgetStatus(monthly, [priced('2026-10-02', 31.99)], today).state).toBe('under')
    expect(budgetStatus(monthly, [priced('2026-10-02', 32)], today).state).toBe('near')
  })

  it('spending exactly the budget is near, not over; a cent past is over', () => {
    const exact = budgetStatus(monthly, [priced('2026-10-02', 40)], today)
    expect(exact.state).toBe('near')
    expect(exact.remaining).toBe(0)
    const past = budgetStatus(monthly, [priced('2026-10-02', 40.01)], today)
    expect(past.state).toBe('over')
    expect(past.fraction).toBeGreaterThan(1)
  })

  it('float drift does not push a met budget over', () => {
    const trips = [1, 2, 3].map((d) => priced(`2026-10-0${d}`, 13.1))
    expect(budgetStatus({ amount: 39.3, period: 'month' }, trips, today).state).toBe('near')
  })

  it('no trips is under with nothing spent', () => {
    const status = budgetStatus(monthly, [], today)
    expect(status.spent).toBe(0)
    expect(status.state).toBe('under')
  })

  it('validates amounts', () => {
    expect(isValidBudgetAmount(0.01)).toBe(true)
    expect(isValidBudgetAmount(100_000)).toBe(true)
    for (const bad of [0, -5, NaN, Infinity, 100_001]) expect(isValidBudgetAmount(bad)).toBe(false)
  })

  it('parses typed amounts leniently', () => {
    expect(parseBudgetAmount('40')).toBe(40)
    expect(parseBudgetAmount(' $1,234.50 ')).toBe(1234.5)
    for (const bad of ['', 'abc', '0', '-10', 'NaN', '1e9']) expect(parseBudgetAmount(bad)).toBeNull()
  })

  it('spells the state out in words', () => {
    expect(budgetSummary(budgetStatus(monthly, [priced('2026-10-02', 18)], today), money)).toBe('$18.00 of $40.00 · $22.00 left')
    expect(budgetSummary(budgetStatus(monthly, [priced('2026-10-02', 35)], today), money)).toBe('$35.00 of $40.00 · near your cap, $5.00 left')
    expect(budgetSummary(budgetStatus(monthly, [priced('2026-10-02', 45)], today), money)).toBe('$45.00 of $40.00 · over budget by $5.00')
  })
})

describe('subscription plan (#211)', () => {
  const today = '2026-10-18' // a Sunday; its Monday-to-Sunday week is Oct 12-18
  const weekly3: SubscriptionPlan = { cap: 3, period: 'week' }
  const monthly10: SubscriptionPlan = { cap: 10, period: 'month' }
  const dates = (...days: string[]) => days.map((date) => ({ date }))

  it('a week runs Monday through Sunday whatever the day', () => {
    for (const day of ['2026-10-12', '2026-10-15', '2026-10-18']) {
      expect(planRange('week', day)).toEqual({ start: '2026-10-12', end: '2026-10-18' })
    }
  })

  it('a week can span two months and two years', () => {
    expect(planRange('week', '2026-12-31')).toEqual({ start: '2026-12-28', end: '2027-01-03' })
  })

  it('a month is the calendar month', () => {
    expect(planRange('month', today)).toEqual({ start: '2026-10-01', end: '2026-10-31' })
  })

  it('counts only outings inside the period, and two on one day both count', () => {
    expect(planStatus(weekly3, dates('2026-10-11', '2026-10-12', '2026-10-18', '2026-10-19'), today).used).toBe(2)
    expect(planStatus(weekly3, dates('2026-10-14', '2026-10-14'), today).used).toBe(2)
  })

  it('no outings is under', () => {
    const status = planStatus(weekly3, [], today)
    expect(status.state).toBe('under')
    expect(status.remaining).toBe(3)
  })

  it('one outing left is near', () => {
    const status = planStatus(weekly3, dates('2026-10-13', '2026-10-14'), today)
    expect(status.state).toBe('near')
    expect(status.remaining).toBe(1)
  })

  it('a larger cap is near at 80%', () => {
    const seven = dates('2026-10-01', '2026-10-02', '2026-10-03', '2026-10-04', '2026-10-05', '2026-10-06', '2026-10-07')
    expect(planStatus(monthly10, seven, today).state).toBe('under')
    expect(planStatus(monthly10, [...seven, ...dates('2026-10-08')], today).state).toBe('near')
  })

  it('a cap of one stays under until used', () => {
    const plan: SubscriptionPlan = { cap: 1, period: 'week' }
    expect(planStatus(plan, [], today).state).toBe('under')
    expect(planStatus(plan, dates('2026-10-14'), today).state).toBe('at_cap')
  })

  it('using the cap exactly is at_cap, one more is over', () => {
    const exact = planStatus(weekly3, dates('2026-10-12', '2026-10-13', '2026-10-14'), today)
    expect(exact.state).toBe('at_cap')
    expect(exact.remaining).toBe(0)
    const over = planStatus(weekly3, dates('2026-10-12', '2026-10-13', '2026-10-14', '2026-10-15'), today)
    expect(over.state).toBe('over')
    expect(over.remaining).toBe(-1)
  })

  it('validates caps and parses typed caps as whole numbers only', () => {
    expect(isValidPlanCap(1)).toBe(true)
    expect(isValidPlanCap(100)).toBe(true)
    for (const bad of [0, -1, 101, 2.5]) expect(isValidPlanCap(bad)).toBe(false)
    expect(parsePlanCap(' 4 ')).toBe(4)
    for (const bad of ['', '2.5', 'abc', '0', '101']) expect(parsePlanCap(bad)).toBeNull()
  })

  it('spells the state out in words', () => {
    expect(planSummary(planStatus(weekly3, dates('2026-10-12'), today))).toBe('1 of 3 outings · 2 left')
    expect(planSummary(planStatus(weekly3, dates('2026-10-12', '2026-10-13'), today))).toBe('2 of 3 outings · 1 left, nearly at your cap')
    expect(planSummary(planStatus(weekly3, dates('2026-10-12', '2026-10-13', '2026-10-14'), today))).toBe('3 of 3 outings · cap reached')
    expect(planSummary(planStatus({ cap: 1, period: 'week' }, dates('2026-10-12', '2026-10-13'), today))).toBe('2 of 1 outing · 1 over your cap')
  })
})

describe('yearly recap (#216)', () => {
  it('lists only years with trips, newest first', () => {
    expect(recapYears([trip('2024-03-01'), trip('2026-01-01'), trip('2024-12-31')])).toEqual([2026, 2024])
    expect(recapYears([])).toEqual([])
  })

  it('opens on this year when it has trips, else the latest one', () => {
    const trips = [trip('2024-03-01'), trip('2025-06-01')]
    expect(defaultRecapYear(trips, '2026-10-29')).toBe(2025)
    expect(defaultRecapYear(trips, '2025-02-01')).toBe(2025)
    expect(defaultRecapYear([], '2026-10-29')).toBeNull()
  })

  it('a year with no trips has no recap', () => {
    expect(moviegoingRecap([trip('2025-06-01')], 2026)).toBeNull()
    expect(moviegoingRecap([], 2026)).toBeNull()
  })

  it('counts trips from 1 January through 31 December of the year only', () => {
    const trips = [trip('2025-12-31'), trip('2026-01-01'), trip('2026-12-31'), trip('2027-01-01')]
    expect(moviegoingRecap(trips, 2026)?.tripCount).toBe(2)
  })

  it('leads with the most frequent venue, format and companion', () => {
    const recap = moviegoingRecap([
      trip('2026-01-05', { venue: 'AMC', companions: ['Sam', 'Jordan'], format: 'IMAX' }),
      trip('2026-02-05', { venue: 'Alamo', companions: ['Sam'], format: 'IMAX' }),
      trip('2026-03-05', { venue: 'AMC', companions: ['Jordan', 'Sam'], format: 'Standard' }),
    ], 2026)
    expect(recap?.topVenue).toEqual({ label: 'AMC', count: 2 })
    expect(recap?.topFormat).toEqual({ label: 'IMAX', count: 2 })
    expect(recap?.topCompanion).toEqual({ label: 'Sam', count: 3 })
  })

  it('a tie goes to the label that sorts first', () => {
    const recap = moviegoingRecap([trip('2026-01-05', { venue: 'Regal' }), trip('2026-02-05', { venue: 'AMC' })], 2026)
    expect(recap?.topVenue).toEqual({ label: 'AMC', count: 1 })
  })

  it('other years do not leak into the tallies', () => {
    const recap = moviegoingRecap([
      trip('2025-01-05', { venue: 'AMC' }), trip('2025-02-05', { venue: 'AMC' }), trip('2025-03-05', { venue: 'AMC' }),
      trip('2026-01-05', { venue: 'Alamo' }),
    ], 2026)
    expect(recap?.topVenue).toEqual({ label: 'Alamo', count: 1 })
  })

  it('missing facts leave the highlight absent', () => {
    const recap = moviegoingRecap([trip('2026-01-05')], 2026)
    expect(recap).toMatchObject({ topVenue: null, topFormat: null, topCompanion: null, totalSpend: null, tripCount: 1 })
  })

  it('trims labels before tallying them', () => {
    const recap = moviegoingRecap([trip('2026-01-05', { companions: [' Sam '] }), trip('2026-02-05', { companions: ['Sam'] })], 2026)
    expect(recap?.topCompanion).toEqual({ label: 'Sam', count: 2 })
  })

  it('ignores blank labels', () => {
    const recap = moviegoingRecap([trip('2026-01-05', { venue: '  ', companions: [''] })], 2026)
    expect(recap?.topVenue).toBeNull()
    expect(recap?.topCompanion).toBeNull()
  })

  it('sums only priced trips in whole cents', () => {
    const recap = moviegoingRecap([
      priced('2026-01-05', 13.1), priced('2026-02-05', 13.1), priced('2026-03-05', 13.1),
      trip('2026-04-05'), priced('2025-04-05', 99),
    ], 2026)
    expect(recap?.tripCount).toBe(4)
    expect(recap?.totalSpend).toBe(39.3)
  })

  it('a zero price still makes spend known', () => {
    expect(moviegoingRecap([priced('2026-01-05', 0)], 2026)?.totalSpend).toBe(0)
  })

  const full = {
    year: 2026, tripCount: 24, topVenue: { label: 'AMC', count: 9 }, topFormat: { label: 'IMAX', count: 5 },
    topCompanion: { label: 'Sam', count: 12 }, totalSpend: 1312.5,
  }

  it('prints every figure in order and omits missing ones', () => {
    expect(recapFields(full, money)).toEqual([
      { label: 'top venue', value: 'AMC' }, { label: 'top format', value: 'IMAX' },
      { label: 'most often with', value: 'Sam' }, { label: 'total spent', value: '$1312.50' },
    ])
    expect(recapFields({ ...full, topVenue: null, topFormat: null, topCompanion: null, totalSpend: null }, money)).toEqual([])
  })

  it('summarises on one line', () => {
    expect(recapSummary(full, money)).toBe('My 2026 at the movies: 24 trips, top venue AMC, $1312.50 spent.')
    expect(recapSummary({ ...full, year: 2025, tripCount: 1, topVenue: null, totalSpend: null }, money)).toBe('My 2025 at the movies: 1 trip.')
  })
})

describe('deriveMoviegoingTrips', () => {
  const outing = { id: 'o1', format: 'IMAX', ticketPrice: 24.5 } as CinemaOuting
  const titles = [{
    id: 't', viewings: [
      { id: 'v1', titleId: 't', outingId: 'o1', venue: 'AMC', date: '2026-03-04', companions: [{ name: ' Sam ' }, { name: 'Jordan' }] },
      { id: 'v2', titleId: 't', venue: 'Alamo', date: '2026-03-05T19:00:00Z', companions: [] },
      { id: 'v3', titleId: 't', date: '2026-03-06', companions: [] }, // couch rewatch: no venue
      { id: 'v4', titleId: 't', venue: 'Regal', companions: [] }, // no date
      { id: 'v5', titleId: 't', venue: 'Regal', date: 'sometime', companions: [] },
    ],
  }] as unknown as Title[]

  it('keeps dated venue viewings and joins format and price from the outing', () => {
    expect(deriveMoviegoingTrips(titles, [outing])).toEqual([
      { date: '2026-03-04', venue: 'AMC', companions: ['Sam', 'Jordan'], format: 'IMAX', ticketPrice: 24.5 },
      { date: '2026-03-05', venue: 'Alamo', companions: [], format: undefined, ticketPrice: undefined },
    ])
  })

  it('never joins private outing data when none are passed', () => {
    const [first] = deriveMoviegoingTrips(titles, [])
    expect(first.format).toBeUndefined()
    expect(first.ticketPrice).toBeUndefined()
  })

  it('reads only the calendar-date prefix of a viewing date', () => {
    expect(tripDate('2026-03-05T19:00:00Z')).toBe('2026-03-05')
    expect(tripDate('2026-13-45')).toBeNull()
    expect(tripDate(undefined)).toBeNull()
  })
})
