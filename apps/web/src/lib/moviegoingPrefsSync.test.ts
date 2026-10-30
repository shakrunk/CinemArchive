import { describe, expect, it } from 'vitest'
import fixture from '../../../../docs/android-contracts/fixtures/moviegoing.json'
import {
  moviegoingPrefsWithBudget, moviegoingPrefsWithPlan, moviegoingPrefsWithRegion, moviegoingPrefsWithTemplates, parseMoviegoingPrefs, resolveReconciliation,
  type MoviegoingPrefs,
} from './moviegoingPrefsSync'
import { MAX_TEMPLATES, type OutingTemplate } from 'src/store/outingTemplates'

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

// Outing templates (#204): the same cases as Android's MoviegoingTemplatesPrefsTest.
describe('outing templates in the payload', () => {
  const parseTemplates = (templates: unknown) => parseMoviegoingPrefs({ templates, updatedAt: '2026-10-29T10:00:00Z' })
  const full = { id: 'a', name: 'Tuesday matinee', venue: 'AMC', companions: ['Sam'], format: 'IMAX', weekday: 2, time: '14:00' }

  it('parses a full template and needs only an id and name', () => {
    expect(parseTemplates([full])?.templates).toEqual([
      { id: 'a', name: 'Tuesday matinee', venue: 'AMC', companions: ['Sam'], format: 'IMAX', weekday: 2, time: '14:00' },
    ])
    expect(parseTemplates([{ id: 'a', name: 'Bare' }])?.templates).toEqual([{ id: 'a', name: 'Bare', companions: [] }])
  })

  it('an absent or null list means no templates, and a non-array rejects the payload', () => {
    expect(parseMoviegoingPrefs({ updatedAt: '2026-10-29T10:00:00Z' })?.templates).toEqual([])
    expect(parseTemplates(null)?.templates).toEqual([])
    expect(parseTemplates({ id: 'a' })).toBeNull()
    expect(parseTemplates('nope')).toBeNull()
  })

  it('skips invalid entries without losing the rest', () => {
    const bad: unknown[] = [
      { id: 'b' },
      { id: 'b', name: '  ' },
      { id: 'b', name: 'x', weekday: 0 },
      { id: 'b', name: 'x', weekday: 8 },
      { id: 'b', name: 'x', weekday: 2.5 },
      { id: 'b', name: 'x', time: '25:00' },
      { id: 'b', name: 'x', time: '7pm' },
      { id: 'b', name: 'x', format: 'Betamax' },
      { id: 'b', name: 'x', companions: 'Sam' },
      { id: 'b', name: 'x', companions: [1] },
      { id: 'b', name: 'x', venue: 4 },
      7,
    ]
    for (const entry of bad) expect(parseTemplates([full, entry])?.templates.map((t) => t.id)).toEqual(['a'])
  })

  it('a bad template never rejects the budget and plan', () => {
    const parsed = parseMoviegoingPrefs({ budget: { amount: 40, period: 'month' }, templates: [{ id: 'b' }], updatedAt: '2026-10-29T10:00:00Z' })
    expect(parsed?.budget).toEqual({ amount: 40, period: 'month' })
  })

  it('keeps the first of a repeated id and caps the list', () => {
    expect(parseTemplates([full, { id: 'a', name: 'Other' }])?.templates.map((t) => t.name)).toEqual(['Tuesday matinee'])
    const many = Array.from({ length: MAX_TEMPLATES + 3 }, (_, i) => ({ id: `t${i}`, name: `T${i}` }))
    expect(parseTemplates(many)?.templates).toHaveLength(MAX_TEMPLATES)
  })

  it('writing templates keeps every other key and round-trips', () => {
    const template: OutingTemplate = { id: 'a', name: 'Matinee', venue: 'AMC', companions: ['Sam'], format: 'Dolby', weekday: 7, time: '11:30' }
    const base = { budget: { amount: 40, period: 'month' }, future: { x: 1 }, updatedAt: '2026-10-29T10:00:00Z' }
    const written = moviegoingPrefsWithTemplates(base, [template], '2026-10-30T00:00:00Z')
    expect(written.updatedAt).toBe('2026-10-30T00:00:00Z')
    expect(written.future).toEqual({ x: 1 })
    const reread = parseMoviegoingPrefs(written)
    expect(reread?.templates).toEqual([template])
    expect(reread?.budget).toEqual({ amount: 40, period: 'month' })
  })

  it('clearing writes an empty list with a fresh stamp', () => {
    const written = moviegoingPrefsWithTemplates({ templates: [full], updatedAt: '2026-10-29T10:00:00Z' }, [], '2026-10-30T00:00:00Z')
    expect(written.templates).toEqual([])
    expect(written.updatedAt).toBe('2026-10-30T00:00:00Z')
  })
})

// Default now-playing region (#202): the same cases as Android's MoviegoingRegionPrefsTest.
describe('default region in the payload', () => {
  const stamp = '2026-10-29T10:00:00Z'
  const parseRegion = (region: unknown) => parseMoviegoingPrefs({ region, updatedAt: stamp })

  it('parses an uppercase code, and absent or null means none', () => {
    expect(parseRegion('GB')?.region).toBe('GB')
    expect(parseMoviegoingPrefs({ updatedAt: stamp })?.region).toBeNull()
    expect(parseRegion(null)?.region).toBeNull()
  })

  it('anything but two uppercase letters rejects the payload', () => {
    for (const bad of ['gb', 'G', 'GBR', 'G1', '', ' GB', 7, ['GB']]) expect(parseRegion(bad)).toBeNull()
  })

  it('setting keeps every other key and stamps the edit; clearing writes an explicit null', () => {
    const base = { budget: { amount: 50, period: 'month' }, future: 1, updatedAt: stamp }
    const next = moviegoingPrefsWithRegion(base, 'CA', '2026-10-30T10:00:00Z')
    expect(next).toMatchObject({ region: 'CA', future: 1, budget: { amount: 50 }, updatedAt: '2026-10-30T10:00:00Z' })
    const cleared = moviegoingPrefsWithRegion(next, null, '2026-10-31T10:00:00Z')
    expect(cleared).toHaveProperty('region', null)
    expect(parseMoviegoingPrefs(cleared)?.region).toBeNull()
  })
})

describe('updatedAt strictness (shared fixture)', () => {
  it('accepts only the UTC instants Android accepts', () => {
    for (const c of fixture.timestamps) {
      expect(parseMoviegoingPrefs({ updatedAt: c.value }) !== null, c.value).toBe(c.valid)
    }
  })

  it('compares sub-second instants by their written time', () => {
    const older = { budget: { amount: 40, period: 'month' }, updatedAt: '2026-10-29T10:00:00.100Z' }
    const newer = { budget: { amount: 50, period: 'month' }, updatedAt: '2026-10-29T10:00:00.200Z' }
    expect(resolveReconciliation(newer, parseMoviegoingPrefs(older)).kind).toBe('apply-remote')
  })
})
