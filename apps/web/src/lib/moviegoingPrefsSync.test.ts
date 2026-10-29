import { describe, expect, it } from 'vitest'
import {
  moviegoingPrefsWithBudget, moviegoingPrefsWithPlan, parseMoviegoingPrefs, resolveReconciliation, type MoviegoingPrefs,
} from './moviegoingPrefsSync'

// The same payload and merge cases as Android's MoviegoingPrefsTest
// (docs/android-contracts/moviegoing-prefs.md).

const STAMP = '"updatedAt":"2026-10-29T10:00:00Z"'
const prefs = (updatedAt: string, budget: unknown = { amount: 40, period: 'month' }) =>
  parseMoviegoingPrefs({ budget, updatedAt }) as MoviegoingPrefs

describe('parseMoviegoingPrefs', () => {
  it('parses a valid budget and plan together', () => {
    const parsed = parseMoviegoingPrefs(JSON.parse(`{"budget":{"amount":40,"period":"month"},"plan":{"cap":4,"period":"week"},${STAMP}}`))
    expect(parsed?.budget).toEqual({ amount: 40, period: 'month' })
    expect(parsed?.plan).toEqual({ cap: 4, period: 'week' })
    expect(parsed?.updatedAt).toBe('2026-10-29T10:00:00Z')
  })

  it('an absent or null budget or plan means none', () => {
    for (const text of [`{${STAMP}}`, `{"budget":null,"plan":null,${STAMP}}`]) {
      const parsed = parseMoviegoingPrefs(JSON.parse(text))
      expect(parsed?.budget).toBeNull()
      expect(parsed?.plan).toBeNull()
    }
  })

  it('rejects the whole payload on any invalid field', () => {
    const invalid = [
      `{"budget":{"amount":0,"period":"month"},${STAMP}}`,
      `{"budget":{"amount":-3,"period":"month"},${STAMP}}`,
      `{"budget":{"amount":100001,"period":"month"},${STAMP}}`,
      `{"budget":{"amount":"40","period":"month"},${STAMP}}`,
      `{"budget":{"amount":40,"period":"week"},${STAMP}}`,
      `{"budget":{"amount":40},${STAMP}}`,
      `{"budget":"forty",${STAMP}}`,
      '{"budget":{"amount":40,"period":"month"}}',
      '{"budget":{"amount":40,"period":"month"},"updatedAt":"yesterday"}',
      '{"budget":{"amount":40,"period":"month"},"updatedAt":12}',
      `{"plan":{"cap":0,"period":"week"},${STAMP}}`,
      `{"plan":{"cap":-2,"period":"week"},${STAMP}}`,
      `{"plan":{"cap":101,"period":"week"},${STAMP}}`,
      `{"plan":{"cap":2.5,"period":"week"},${STAMP}}`,
      `{"plan":{"cap":"3","period":"week"},${STAMP}}`,
      `{"plan":{"cap":3,"period":"year"},${STAMP}}`,
      `{"plan":{"cap":3},${STAMP}}`,
      `{"plan":"unlimited",${STAMP}}`,
      // A valid budget does not rescue an invalid plan.
      `{"budget":{"amount":40,"period":"month"},"plan":{"cap":0,"period":"week"},${STAMP}}`,
    ]
    for (const text of invalid) expect(parseMoviegoingPrefs(JSON.parse(text)), text).toBeNull()
    expect(parseMoviegoingPrefs(null)).toBeNull()
    expect(parseMoviegoingPrefs([])).toBeNull()
  })

  it('accepts a whole number written as a decimal', () => {
    expect(parseMoviegoingPrefs(JSON.parse(`{"plan":{"cap":3.0,"period":"month"},${STAMP}}`))?.plan).toEqual({ cap: 3, period: 'month' })
  })
})

describe('writing a key', () => {
  it('setting a budget keeps keys this client does not know and does not mutate the input', () => {
    const base = { budget: null, region: 'CA', updatedAt: '2026-10-29T10:00:00Z' }
    const next = moviegoingPrefsWithBudget(base, { amount: 25.5, period: 'year' }, '2026-10-30T09:00:00Z')
    expect(next.region).toBe('CA')
    expect(next.updatedAt).toBe('2026-10-30T09:00:00Z')
    expect(parseMoviegoingPrefs(next)?.budget).toEqual({ amount: 25.5, period: 'year' })
    expect(base.budget).toBeNull()
  })

  it('clearing writes an explicit null with a fresh stamp', () => {
    const next = moviegoingPrefsWithBudget(prefs('2026-10-29T10:00:00Z').raw, null, '2026-10-30T09:00:00Z')
    expect('budget' in next && next.budget === null).toBe(true)
    expect(parseMoviegoingPrefs(next)?.budget).toBeNull()
    expect(next.updatedAt).toBe('2026-10-30T09:00:00Z')
  })

  it('starting from nothing produces a valid payload', () => {
    expect(parseMoviegoingPrefs(moviegoingPrefsWithBudget(null, { amount: 40, period: 'month' }, '2026-10-29T10:00:00Z'))).not.toBeNull()
  })

  it('setting a plan keeps the budget and unknown keys, and the reverse', () => {
    const base = { budget: { amount: 40, period: 'month' }, region: 'CA', updatedAt: '2026-10-29T10:00:00Z' }
    const withPlan = moviegoingPrefsWithPlan(base, { cap: 4, period: 'week' }, '2026-10-30T09:00:00Z')
    expect(withPlan.region).toBe('CA')
    const parsed = parseMoviegoingPrefs(withPlan)
    expect(parsed?.plan).toEqual({ cap: 4, period: 'week' })
    expect(parsed?.budget).toEqual({ amount: 40, period: 'month' })
    const back = moviegoingPrefsWithBudget(withPlan, { amount: 50, period: 'year' }, '2026-10-31T09:00:00Z')
    expect(parseMoviegoingPrefs(back)?.plan).toEqual({ cap: 4, period: 'week' })
  })

  it('clearing a plan writes an explicit null with a fresh stamp', () => {
    const base = { plan: { cap: 4, period: 'week' }, updatedAt: '2026-10-29T10:00:00Z' }
    const next = moviegoingPrefsWithPlan(base, null, '2026-10-30T09:00:00Z')
    expect(next.plan).toBeNull()
    expect(parseMoviegoingPrefs(next)?.plan).toBeNull()
    expect(next.updatedAt).toBe('2026-10-30T09:00:00Z')
  })
})

describe('resolveReconciliation', () => {
  it('a newer remote is applied', () => {
    const decision = resolveReconciliation(prefs('2026-10-30T00:00:00Z').raw, prefs('2026-10-29T00:00:00Z'))
    expect(decision.kind).toBe('apply-remote')
  })

  it('a newer local is pushed', () => {
    expect(resolveReconciliation(prefs('2026-10-29T00:00:00Z').raw, prefs('2026-10-30T00:00:00Z'))).toEqual({ kind: 'push-local' })
  })

  it('equal stamps change nothing', () => {
    expect(resolveReconciliation(prefs('2026-10-29T00:00:00Z').raw, prefs('2026-10-29T00:00:00Z'))).toEqual({ kind: 'keep' })
  })

  it('no remote row pushes a local copy and otherwise does nothing; an empty default counts as no row', () => {
    expect(resolveReconciliation(null, prefs('2026-10-29T00:00:00Z'))).toEqual({ kind: 'push-local' })
    expect(resolveReconciliation({}, prefs('2026-10-29T00:00:00Z'))).toEqual({ kind: 'push-local' })
    expect(resolveReconciliation(null, null)).toEqual({ kind: 'keep' })
  })

  it('no local copy applies the remote one', () => {
    expect(resolveReconciliation(prefs('2026-10-29T00:00:00Z').raw, null).kind).toBe('apply-remote')
  })

  it('an invalid remote is neither applied nor overwritten', () => {
    const invalid = { budget: { amount: -1, period: 'month' }, updatedAt: '2026-12-01T00:00:00Z' }
    expect(resolveReconciliation(invalid, prefs('2026-10-29T00:00:00Z'))).toEqual({ kind: 'keep' })
    expect(resolveReconciliation(invalid, null)).toEqual({ kind: 'keep' })
  })
})
