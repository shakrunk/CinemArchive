import type { CinemaOuting, Title } from './mockData'
import { normalizeCompanions } from './companions'

// Pure moviegoing-budget (#209), subscription-plan (#211) and yearly-recap (#216)
// rules. They mirror Android's MoviegoingBudget.kt / SubscriptionPlan.kt /
// MoviegoingRecap.kt case for case; the contracts are
// docs/android-contracts/moviegoing-prefs.md and moviegoing-recap.md. Dates are
// plain `YYYY-MM-DD` calendar dates (a viewing's date, or `localDateStr(now)`),
// so no time zone arithmetic can shift a trip across a period boundary.

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/

/** `YYYY-MM-DD` of a viewing's date, or null when it has none or it is malformed. */
export function tripDate(value: string | undefined): string | null {
  const day = value?.slice(0, 10)
  return day && DATE_RE.test(day) && Number.isFinite(Date.parse(`${day}T00:00:00Z`)) ? day : null
}

const pad = (n: number) => String(n).padStart(2, '0')

function ymd(year: number, month: number, day: number): string {
  return `${String(year).padStart(4, '0')}-${pad(month)}-${pad(day)}`
}

/** Last day of a 1-based month. */
function lastDayOfMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate()
}

export interface DateRange { start: string; end: string }

const inRange = (date: string, range: DateRange) => date >= range.start && date <= range.end

// ─── Trips ────────────────────────────────────────────────────────────────────

/** One dated cinema trip with the facts the budget, plan and recap tally. `format`
 *  and `ticketPrice` come from the owner-private outing the viewing was logged
 *  through, so they are absent for a trip logged by hand. */
export interface MoviegoingTrip {
  date: string
  venue?: string
  companions: string[]
  format?: string
  ticketPrice?: number
}

/** Every viewing with a venue and a valid date (the Ledger's trip set), joined to its
 *  outing for format and price. Pass no outings in a friend/shared view. */
export function deriveMoviegoingTrips(titles: Title[], outings: CinemaOuting[]): MoviegoingTrip[] {
  const outingById = new Map(outings.map((o) => [o.id, o]))
  const trips: MoviegoingTrip[] = []
  for (const title of titles) {
    for (const viewing of title.viewings) {
      if (!viewing.venue) continue
      const date = tripDate(viewing.date)
      if (!date) continue
      const outing = viewing.outingId ? outingById.get(viewing.outingId) : undefined
      trips.push({
        date,
        venue: viewing.venue,
        companions: normalizeCompanions(viewing.companions).map((c) => c.name),
        format: outing?.format,
        ticketPrice: outing?.ticketPrice ?? undefined,
      })
    }
  }
  return trips
}

// ─── Budget (#209) ────────────────────────────────────────────────────────────

export type BudgetPeriod = 'month' | 'year'
export interface MoviegoingBudget { amount: number; period: BudgetPeriod }

export const MAX_BUDGET_AMOUNT = 100_000
export const NEAR_THRESHOLD_PERCENT = 80

export const isValidBudgetAmount = (amount: number) => Number.isFinite(amount) && amount > 0 && amount <= MAX_BUDGET_AMOUNT

/** What a person types into the budget field: an optional `$` and thousands commas are
 *  ignored. Null for anything that is not a valid amount. */
export function parseBudgetAmount(text: string): number | null {
  const cleaned = text.trim().replace(/^\$/, '').replace(/,/g, '').trim()
  if (!/^[+-]?(\d+\.?\d*|\.\d+)$/.test(cleaned)) return null
  const amount = Number(cleaned)
  return isValidBudgetAmount(amount) ? amount : null
}

export function budgetRange(period: BudgetPeriod, today: string): DateRange {
  const year = Number(today.slice(0, 4))
  if (period === 'year') return { start: ymd(year, 1, 1), end: ymd(year, 12, 31) }
  const month = Number(today.slice(5, 7))
  return { start: ymd(year, month, 1), end: ymd(year, month, lastDayOfMonth(year, month)) }
}

const cents = (amount: number) => Math.round(amount * 100)

export type BudgetState = 'under' | 'near' | 'over'
export interface BudgetStatus {
  budget: MoviegoingBudget
  spent: number
  remaining: number
  /** Share of the budget spent, uncapped (1.25 means 25% over). */
  fraction: number
  range: DateRange
  state: BudgetState
}

/** Sums priced trips dated inside the period containing `today`. Compared in whole
 *  cents so a budget met exactly is "near", never "over", whatever the float sum does. */
export function budgetStatus(budget: MoviegoingBudget, trips: MoviegoingTrip[], today: string): BudgetStatus {
  const range = budgetRange(budget.period, today)
  const spentCents = trips
    .filter((t) => t.ticketPrice != null && inRange(t.date, range))
    .reduce((sum, t) => sum + cents(t.ticketPrice as number), 0)
  const budgetCents = cents(budget.amount)
  const state: BudgetState = spentCents > budgetCents ? 'over'
    : spentCents * 100 >= budgetCents * NEAR_THRESHOLD_PERCENT ? 'near' : 'under'
  const spent = spentCents / 100
  return { budget, spent, remaining: budget.amount - spent, fraction: spent / budget.amount, range, state }
}

// ─── Subscription plan (#211) ─────────────────────────────────────────────────

export type PlanPeriod = 'week' | 'month'
export interface SubscriptionPlan { cap: number; period: PlanPeriod }

export const MAX_PLAN_CAP = 100

export const isValidPlanCap = (cap: number) => Number.isInteger(cap) && cap >= 1 && cap <= MAX_PLAN_CAP

/** What a person types into the cap field: a whole number in range, else null. */
export function parsePlanCap(text: string): number | null {
  const trimmed = text.trim()
  if (!/^[+-]?\d+$/.test(trimmed)) return null
  const cap = Number(trimmed)
  return isValidPlanCap(cap) ? cap : null
}

/** A week runs Monday to Sunday everywhere, whatever the locale's first day, so both
 *  clients and every device agree on when a weekly cap resets. A month is the calendar month. */
export function planRange(period: PlanPeriod, today: string): DateRange {
  if (period === 'month') return budgetRange('month', today)
  const date = new Date(Date.UTC(Number(today.slice(0, 4)), Number(today.slice(5, 7)) - 1, Number(today.slice(8, 10))))
  const sinceMonday = (date.getUTCDay() + 6) % 7
  const monday = new Date(date.getTime() - sinceMonday * 86_400_000)
  const sunday = new Date(monday.getTime() + 6 * 86_400_000)
  const fmt = (d: Date) => ymd(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate())
  return { start: fmt(monday), end: fmt(sunday) }
}

export type PlanState = 'under' | 'near' | 'at_cap' | 'over'
export interface PlanStatus {
  plan: SubscriptionPlan
  used: number
  remaining: number
  range: DateRange
  state: PlanState
}

/** Counts trips inside the period containing `today` (priced or not; two on one day both
 *  count). Near means one outing left, or at least 80% used, once any has been used; a cap
 *  used exactly is `at_cap` and only a further outing is `over`. */
export function planStatus(plan: SubscriptionPlan, trips: Pick<MoviegoingTrip, 'date'>[], today: string): PlanStatus {
  const range = planRange(plan.period, today)
  const used = trips.filter((t) => inRange(t.date, range)).length
  const state: PlanState = used > plan.cap ? 'over'
    : used === plan.cap ? 'at_cap'
      : used > 0 && (plan.cap - used <= 1 || used * 100 >= plan.cap * NEAR_THRESHOLD_PERCENT) ? 'near' : 'under'
  return { plan, used, remaining: plan.cap - used, range, state }
}

// ─── Yearly recap (#216) ──────────────────────────────────────────────────────

export interface RecapHighlight { label: string; count: number }
export interface MoviegoingRecap {
  year: number
  tripCount: number
  topVenue: RecapHighlight | null
  topFormat: RecapHighlight | null
  topCompanion: RecapHighlight | null
  /** Null when no trip that year had a recorded ticket price. */
  totalSpend: number | null
}

/** Years with at least one trip, most recent first. */
export function recapYears(trips: MoviegoingTrip[]): number[] {
  return [...new Set(trips.map((t) => Number(t.date.slice(0, 4))))].sort((a, b) => b - a)
}

/** The year a recap opens on: the current one when it has trips, otherwise the latest
 *  year that does, otherwise null. */
export function defaultRecapYear(trips: MoviegoingTrip[], today: string): number | null {
  const years = recapYears(trips)
  const current = Number(today.slice(0, 4))
  return years.includes(current) ? current : years[0] ?? null
}

/** The most frequent label (trimmed, blanks ignored); a tie goes to the label that sorts
 *  first, so the same trips give the same card on every device and client. */
function top(labels: (string | undefined)[]): RecapHighlight | null {
  const counts = new Map<string, number>()
  for (const raw of labels) {
    const label = raw?.trim()
    if (label) counts.set(label, (counts.get(label) ?? 0) + 1)
  }
  let best: RecapHighlight | null = null
  for (const [label, count] of counts) {
    if (!best || count > best.count || (count === best.count && label < best.label)) best = { label, count }
  }
  return best
}

/** The recap for `year`, or null when that year has no trips. */
export function moviegoingRecap(trips: MoviegoingTrip[], year: number): MoviegoingRecap | null {
  const inYear = trips.filter((t) => Number(t.date.slice(0, 4)) === year)
  if (inYear.length === 0) return null
  const prices = inYear.flatMap((t) => (t.ticketPrice != null ? [t.ticketPrice] : []))
  return {
    year,
    tripCount: inYear.length,
    topVenue: top(inYear.map((t) => t.venue)),
    topFormat: top(inYear.map((t) => t.format)),
    topCompanion: top(inYear.flatMap((t) => t.companions)),
    totalSpend: prices.length > 0 ? prices.reduce((sum, p) => sum + cents(p), 0) / 100 : null,
  }
}

/** The lines under the headline trip count, in print order; a figure the year lacks is left out. */
export function recapFields(recap: MoviegoingRecap, money: (amount: number) => string): { label: string; value: string }[] {
  const fields: { label: string; value: string }[] = []
  if (recap.topVenue) fields.push({ label: 'top venue', value: recap.topVenue.label })
  if (recap.topFormat) fields.push({ label: 'top format', value: recap.topFormat.label })
  if (recap.topCompanion) fields.push({ label: 'most often with', value: recap.topCompanion.label })
  if (recap.totalSpend != null) fields.push({ label: 'total spent', value: money(recap.totalSpend) })
  return fields
}

/** "My 2026 at the movies: 24 trips, top venue AMC, $312.50 spent." */
export function recapSummary(recap: MoviegoingRecap, money: (amount: number) => string): string {
  const parts = [`${recap.tripCount} ${recap.tripCount === 1 ? 'trip' : 'trips'}`]
  if (recap.topVenue) parts.push(`top venue ${recap.topVenue.label}`)
  if (recap.totalSpend != null) parts.push(`${money(recap.totalSpend)} spent`)
  return `My ${recap.year} at the movies: ${parts.join(', ')}.`
}

// ─── Wording ──────────────────────────────────────────────────────────────────

/** "$18.00 of $40.00 · $22.00 left" / "near your cap" / "over budget by $5.00". */
export function budgetSummary(status: BudgetStatus, money: (amount: number) => string): string {
  const head = `${money(status.spent)} of ${money(status.budget.amount)}`
  if (status.state === 'under') return `${head} · ${money(status.remaining)} left`
  if (status.state === 'near') return `${head} · near your cap, ${money(status.remaining)} left`
  return `${head} · over budget by ${money(-status.remaining)}`
}

/** "2 of 4 outings · 2 left", "3 of 4 outings · 1 left, nearly at your cap", "4 of 4 outings ·
 *  cap reached" or "5 of 4 outings · 1 over your cap". */
export function planSummary(status: PlanStatus): string {
  const head = `${status.used} of ${status.plan.cap} ${status.plan.cap === 1 ? 'outing' : 'outings'}`
  if (status.state === 'under') return `${head} · ${status.remaining} left`
  if (status.state === 'near') return `${head} · ${status.remaining} left, nearly at your cap`
  if (status.state === 'at_cap') return `${head} · cap reached`
  return `${head} · ${-status.remaining} over your cap`
}
