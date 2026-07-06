// Per-panel stat derivation for the Ledger board, parameterized by each
// widget instance's settings (scope / time range / top N). Pure functions —
// components memoize with useMemo(() => deriveX(titles, settings), [titles,
// settingsKey]); `titles` is replaced wholesale by the store on any change,
// so reference equality is a sound cache key.
//
// computeLedgerStats (ledgerStats.ts) still powers the hero/ribbon and stays
// untouched — these functions cover the panels, which need per-instance
// parameterization.

import type { Title } from './mockData'
import type { LedgerPanelId, LedgerTimeRange, LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { effectiveLedgerSettings } from 'src/lib/ledgerPanels'

// ─── Scope & time-range helpers ──────────────────────────────────────────────

export function scopeTitles(titles: Title[], settings: { scope: 'all' | 'movies' | 'tv' }): Title[] {
  if (settings.scope === 'movies') return titles.filter((t) => t.type === 'movie')
  if (settings.scope === 'tv') return titles.filter((t) => t.type === 'tv')
  return titles
}

/** Inclusive lower bound (ISO date string) for a time range, or null for
 *  all-time. ISO dates compare correctly as strings. */
export function timeRangeStart(timeRange: LedgerTimeRange, now = new Date()): string | null {
  if (timeRange === 'all') return null
  const d = new Date(now)
  if (timeRange === 'ytd') return `${d.getFullYear()}-01-01`
  if (timeRange === '12mo') d.setFullYear(d.getFullYear() - 1)
  if (timeRange === '5y') d.setFullYear(d.getFullYear() - 5)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** Predicate for dated events; undated (pre-platform) events only pass the
 *  all-time range, since their true date is indeterminate. */
export function dateInRange(date: string | undefined, rangeStart: string | null): boolean {
  if (rangeStart === null) return true
  return typeof date === 'string' && date >= rangeStart
}

/** Convenience: resolve a widget's effective settings and pre-scope titles. */
export function scopedTitles(
  panel: LedgerPanelId,
  titles: Title[],
  settings?: LedgerWidgetSettings,
): { titles: Title[]; topN: number; rangeStart: string | null } {
  const eff = effectiveLedgerSettings(panel, settings)
  return {
    titles: scopeTitles(titles, eff),
    topN: eff.topN,
    rangeStart: timeRangeStart(eff.timeRange),
  }
}

// ─── Per-panel derivations (ported from ledgerStats.ts + inline useMemos) ───

function tally(values: string[]): [string, number][] {
  const counts = new Map<string, number>()
  for (const v of values) counts.set(v, (counts.get(v) ?? 0) + 1)
  return [...counts.entries()]
}

export function deriveTopGenres(titles: Title[], settings?: LedgerWidgetSettings) {
  const { titles: scoped, topN } = scopedTitles('genres', titles, settings)
  return tally(scoped.flatMap((t) => t.genres))
    .sort((a, b) => b[1] - a[1])
    .slice(0, topN)
    .map(([genre, count]) => ({ genre, count }))
}

export function deriveTopDirectors(titles: Title[], settings?: LedgerWidgetSettings) {
  const { titles: scoped, topN } = scopedTitles('auteurs', titles, settings)
  return tally(scoped.map((t) => t.director).filter((d): d is string => Boolean(d)))
    .sort((a, b) => b[1] - a[1])
    .slice(0, topN)
    .map(([director, count]) => ({ director, count }))
}

export interface TopActor {
  actor: string
  count: number
  tmdbPersonId: number | null
}

export function deriveTopActors(titles: Title[], settings?: LedgerWidgetSettings): TopActor[] {
  const { titles: scoped, topN } = scopedTitles('ensemble', titles, settings)
  // Tally by TMDB person id (name as display), falling back to the name as
  // key when an id is missing, so same-named actors don't merge incorrectly.
  const counts = new Map<string | number, { name: string; count: number; id: number | null }>()
  for (const t of scoped) {
    for (const c of t.cast ?? []) {
      if (c.order >= 5) continue
      const key = c.tmdbPersonId || c.name
      const entry = counts.get(key)
      if (entry) entry.count += 1
      else counts.set(key, { name: c.name, count: 1, id: c.tmdbPersonId || null })
    }
  }
  return [...counts.values()]
    .sort((a, b) => b.count - a.count)
    .slice(0, topN)
    .map((e) => ({ actor: e.name, count: e.count, tmdbPersonId: e.id }))
}

export function deriveRatingDistribution(titles: Title[], settings?: LedgerWidgetSettings) {
  const { titles: scoped } = scopedTitles('ratings', titles, settings)
  const rated = scoped.filter((t) => typeof t.rating === 'number')
  const avgRating = rated.length
    ? Math.round((rated.reduce((sum, t) => sum + (t.rating ?? 0), 0) / rated.length) * 10) / 10
    : 0
  const distribution: { rating: number; count: number }[] = []
  for (let r = 5; r >= 1; r -= 0.5) {
    distribution.push({ rating: r, count: scoped.filter((t) => t.rating === r).length })
  }
  return { distribution, avgRating }
}

/** Gap-filled monthly viewing counts for The Run. The month window follows the
 *  widget's time range ('all' spans back to the earliest dated viewing, capped
 *  at 10 years so a stray date can't explode the series). */
export function deriveMonthlySeries(titles: Title[], settings?: LedgerWidgetSettings) {
  const { titles: scoped, rangeStart } = scopedTitles('run', titles, settings)
  const counts = new Map<string, number>()
  let earliest: string | null = null
  for (const t of scoped) {
    for (const v of t.viewings) {
      if (!v.date || !dateInRange(v.date, rangeStart)) continue
      const month = v.date.slice(0, 7)
      counts.set(month, (counts.get(month) ?? 0) + 1)
      if (!earliest || month < earliest) earliest = month
    }
  }

  const now = new Date()
  let start: Date
  if (rangeStart !== null) {
    start = new Date(Number(rangeStart.slice(0, 4)), Number(rangeStart.slice(5, 7)) - 1, 1)
  } else if (earliest) {
    start = new Date(Number(earliest.slice(0, 4)), Number(earliest.slice(5, 7)) - 1, 1)
    const floor = new Date(now.getFullYear() - 10, now.getMonth(), 1)
    if (start < floor) start = floor
  } else {
    start = new Date(now.getFullYear(), now.getMonth() - 11, 1)
  }

  const months: { month: string; count: number }[] = []
  const cursor = new Date(start.getFullYear(), start.getMonth(), 1)
  const endKey = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  for (;;) {
    const key = `${cursor.getFullYear()}-${String(cursor.getMonth() + 1).padStart(2, '0')}`
    months.push({ month: key, count: counts.get(key) ?? 0 })
    if (key >= endKey) break
    cursor.setMonth(cursor.getMonth() + 1)
  }
  // Always show at least a year of axis so a sparse board doesn't collapse.
  while (months.length < 12) {
    const first = months[0]?.month ?? endKey
    const d = new Date(Number(first.slice(0, 4)), Number(first.slice(5, 7)) - 2, 1)
    months.unshift({ month: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`, count: 0 })
  }
  return months
}
