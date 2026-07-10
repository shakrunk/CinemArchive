// Per-panel stat derivation for the Ledger board, parameterized by each
// widget instance's settings (scope / time range / top N). Pure functions —
// components memoize with useMemo(() => deriveX(titles, settings), [titles,
// settingsKey]); `titles` is replaced wholesale by the store on any change,
// so reference equality is a sound cache key.
//
// computeLedgerStats (ledgerStats.ts) calls several of these unscoped (no
// settings) for the whole-library hero/ribbon — the same rollup, computed
// once here, feeds both the hero stats and the per-widget panels.

import type { Title } from './mockData'
import type { LedgerPanelId, LedgerTimeRange, LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { effectiveLedgerSettings } from 'src/lib/ledgerPanels'
import { allWatchEvents } from './episodeUtils'

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

export function tally(values: string[]): [string, number][] {
  const counts = new Map<string, number>()
  for (const v of values) counts.set(v, (counts.get(v) ?? 0) + 1)
  return [...counts.entries()]
}

/** Viewing counts bucketed by calendar month (YYYY-MM), optionally restricted
 *  to an inclusive lower-bound date. Shared core of both the hero ribbon's
 *  viewingsByMonth (ledgerStats.ts) and The Run's gap-filled monthly series. */
export function bucketViewingsByMonth(titles: Title[], rangeStart: string | null = null): Map<string, number> {
  const counts = new Map<string, number>()
  for (const t of titles) {
    for (const v of t.viewings) {
      if (!v.date || !dateInRange(v.date, rangeStart)) continue
      const month = v.date.slice(0, 7)
      counts.set(month, (counts.get(month) ?? 0) + 1)
    }
  }
  return counts
}

export function deriveTopGenres(titles: Title[], settings?: LedgerWidgetSettings) {
  const { titles: scoped, topN } = scopedTitles('genres', titles, settings)
  return tally(scoped.flatMap((t) => t.genres))
    .sort((a, b) => b[1] - a[1])
    .slice(0, topN)
    .map(([genre, count]) => ({ genre, count }))
}

export interface TopDirector {
  director: string
  count: number
  tmdbPersonId: number | null
}

export function deriveTopDirectors(titles: Title[], settings?: LedgerWidgetSettings): TopDirector[] {
  const { titles: scoped, topN } = scopedTitles('auteurs', titles, settings)
  const counts = new Map<string, { count: number; id: number | null }>()
  for (const t of scoped) {
    if (!t.director) continue
    const entry = counts.get(t.director) ?? { count: 0, id: null }
    entry.count += 1
    // `Title.director` is a display string; recover the TMDB person id from
    // the crew credits when available so click-through can filter precisely.
    if (entry.id === null) {
      entry.id = t.crew?.find((c) => c.job === 'Director' && c.name === t.director)?.tmdbPersonId ?? null
    }
    counts.set(t.director, entry)
  }
  return [...counts.entries()]
    .sort((a, b) => b[1].count - a[1].count)
    .slice(0, topN)
    .map(([director, e]) => ({ director, count: e.count, tmdbPersonId: e.id }))
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
  const counts = bucketViewingsByMonth(scoped, rangeStart)
  let earliest: string | null = null
  for (const month of counts.keys()) {
    if (!earliest || month < earliest) earliest = month
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

// ─── New-panel derivations ───────────────────────────────────────────────────

function isoToDayNumber(date: string): number {
  const [y, m, d] = date.split('-').map(Number)
  return Math.round(new Date(y, m - 1, d).getTime() / 86_400_000)
}

/** Every distinct local date with any screening activity: title viewings plus
 *  per-episode watch events. */
function distinctScreeningDates(titles: Title[]): Set<string> {
  const dates = new Set<string>()
  for (const t of titles) {
    for (const v of t.viewings) if (v.date) dates.add(v.date)
    for (const we of allWatchEvents(t)) if (we.watchedAt) dates.add(we.watchedAt)
  }
  return dates
}

export interface StreakStats {
  longest: number
  current: number
  totalDays: number
  /** Activity for the trailing 30 days, oldest first. */
  last30: boolean[]
}

export function deriveStreaks(titles: Title[], settings?: LedgerWidgetSettings, now = new Date()): StreakStats {
  const { titles: scoped } = scopedTitles('streaks', titles, settings)
  const dates = distinctScreeningDates(scoped)
  const days = [...dates].map(isoToDayNumber).sort((a, b) => a - b)

  let longest = 0
  let run = 0
  for (let i = 0; i < days.length; i++) {
    run = i > 0 && days[i] === days[i - 1] + 1 ? run + 1 : 1
    if (run > longest) longest = run
  }

  const todayNum = Math.round(new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime() / 86_400_000)
  const daySet = new Set(days)
  // A streak still counts as "current" if the last screening was yesterday —
  // tonight's feature hasn't rolled yet.
  let current = 0
  let cursor = daySet.has(todayNum) ? todayNum : daySet.has(todayNum - 1) ? todayNum - 1 : NaN
  while (!Number.isNaN(cursor) && daySet.has(cursor)) {
    current += 1
    cursor -= 1
  }

  const last30: boolean[] = []
  for (let i = 29; i >= 0; i--) last30.push(daySet.has(todayNum - i))

  return { longest, current, totalDays: days.length, last30 }
}

export interface TrajectoryPoint {
  /** Quarter key, e.g. "2025 Q3". */
  quarter: string
  avg: number
  count: number
}

/** Average title rating bucketed by the quarter each title was first seen.
 *  Title-level ratings are undated, so a title lands in the quarter of its
 *  first dated viewing (falling back to when it was added). */
export function deriveTrajectory(titles: Title[], settings?: LedgerWidgetSettings) {
  const { titles: scoped, rangeStart } = scopedTitles('trajectory', titles, settings)
  const buckets = new Map<string, { sum: number; count: number }>()
  let allSum = 0
  let allCount = 0
  for (const t of scoped) {
    if (typeof t.rating !== 'number') continue
    allSum += t.rating
    allCount += 1
    const dates = t.viewings.map((v) => v.date).filter((d): d is string => Boolean(d)).sort()
    const anchor = dates[0] ?? t.addedAt?.slice(0, 10)
    if (!anchor || !dateInRange(anchor, rangeStart)) continue
    const year = Number(anchor.slice(0, 4))
    const q = Math.floor((Number(anchor.slice(5, 7)) - 1) / 3) + 1
    const key = `${year} Q${q}`
    const b = buckets.get(key)
    if (b) {
      b.sum += t.rating
      b.count += 1
    } else buckets.set(key, { sum: t.rating, count: 1 })
  }
  const points: TrajectoryPoint[] = [...buckets.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([quarter, b]) => ({ quarter, avg: Math.round((b.sum / b.count) * 10) / 10, count: b.count }))
  const allTimeAvg = allCount ? Math.round((allSum / allCount) * 10) / 10 : 0
  return { points, allTimeAvg }
}

export interface RevivalMonth {
  month: string
  premieres: number
  revivals: number
}

/** Per month: how many viewings were a title's first (premiere) vs. a
 *  rewatch (revival). Undated viewings are excluded — no month to land in —
 *  but still consume "first viewing" status, so a dated rewatch of a
 *  pre-platform title correctly counts as a revival. */
export function deriveRevivals(titles: Title[], settings?: LedgerWidgetSettings): RevivalMonth[] {
  const { titles: scoped, rangeStart } = scopedTitles('revivals', titles, settings)
  const months = new Map<string, { premieres: number; revivals: number }>()
  for (const t of scoped) {
    // Chronological with undated (pre-platform) viewings first.
    const ordered = [...t.viewings].sort((a, b) => (a.date ?? '').localeCompare(b.date ?? ''))
    ordered.forEach((v, i) => {
      if (!v.date || !dateInRange(v.date, rangeStart)) return
      const month = v.date.slice(0, 7)
      const m = months.get(month) ?? { premieres: 0, revivals: 0 }
      if (i === 0) m.premieres += 1
      else m.revivals += 1
      months.set(month, m)
    })
  }
  return [...months.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([month, m]) => ({ month, ...m }))
}

export interface TimewarpBucket {
  key: string
  label: string
  range: string
  count: number
}

const TIMEWARP_BUCKETS = [
  { key: 'first-run', label: 'First run', range: '0–1 yr', min: 0, max: 2 },
  { key: 'recent', label: 'Recent', range: '2–5 yr', min: 2, max: 6 },
  { key: 'modern', label: 'Modern', range: '6–20 yr', min: 6, max: 21 },
  { key: 'classic', label: 'Classic', range: '21–50 yr', min: 21, max: 51 },
  { key: 'vintage', label: 'Vintage', range: '50+ yr', min: 51, max: Infinity },
]

/** How old titles were when screened: viewing year minus release year, over
 *  all dated viewings. */
export function deriveTimewarp(titles: Title[], settings?: LedgerWidgetSettings) {
  const { titles: scoped } = scopedTitles('timewarp', titles, settings)
  const ages: number[] = []
  for (const t of scoped) {
    if (!t.year) continue
    for (const v of t.viewings) {
      if (!v.date) continue
      ages.push(Math.max(0, Number(v.date.slice(0, 4)) - t.year))
    }
  }
  const buckets: TimewarpBucket[] = TIMEWARP_BUCKETS.map((b) => ({
    key: b.key,
    label: b.label,
    range: b.range,
    count: ages.filter((a) => a >= b.min && a < b.max).length,
  }))
  const sorted = [...ages].sort((a, b) => a - b)
  const median = sorted.length ? sorted[Math.floor(sorted.length / 2)] : 0
  return { buckets, median, total: ages.length }
}

export interface SeriesProgress {
  id: string
  title: string
  year: number
  watched: number
  total: number
  pct: number
}

/** TV series mid-flight: marked watching, or with partial episode progress. */
export function deriveProgress(titles: Title[], settings?: LedgerWidgetSettings): SeriesProgress[] {
  const { titles: scoped, topN } = scopedTitles('progress', titles, settings)
  const rows: SeriesProgress[] = []
  for (const t of scoped) {
    if (t.type !== 'tv') continue
    const seasons = t.seasons ?? []
    const total = seasons.reduce((sum, s) => sum + s.episodeCount, 0)
    const watched = seasons.reduce((sum, s) => sum + s.episodesWatched, 0)
    const partial = total > 0 && watched > 0 && watched < total
    if (t.status !== 'watching' && !partial) continue
    rows.push({
      id: t.id,
      title: t.title,
      year: t.year,
      watched,
      total,
      pct: total > 0 ? watched / total : 0,
    })
  }
  return rows.sort((a, b) => b.pct - a.pct).slice(0, topN)
}

/** The watchlist, weighed: how much is queued and how long it would take. */
export function deriveAttractions(titles: Title[]) {
  const queued = titles.filter((t) => t.status === 'watchlist')
  const movies = queued.filter((t) => t.type === 'movie')
  const series = queued.filter((t) => t.type === 'tv')
  const minutesOwed = movies.reduce((sum, t) => sum + (t.runtime ?? 0), 0)
  const topGenres = tally(queued.flatMap((t) => t.genres))
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(([genre, count]) => ({ genre, count }))
  return {
    count: queued.length,
    movies: movies.length,
    series: series.length,
    hoursOwed: Math.round(minutesOwed / 60),
    topGenres,
  }
}
