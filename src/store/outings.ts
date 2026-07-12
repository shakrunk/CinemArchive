import type { CinemaOuting, Companion, Title, Viewing } from './mockData'
import type { FriendshipView } from '../lib/auth'

// Pure, unit-testable derivations for Cinema Outings ("I've got tickets") —
// modeled on upNext.ts. See docs/superpowers/plans/2026-07-11-cinema-outings.md
// §4.3/§4.5/§7.2 for the behavior this mirrors.

/** YYYY-MM-DD from a Date's *local* components (not toISOString, which is UTC) —
 *  matches the calendar date the complete_due_outings RPC derives server-side
 *  from the same IANA zone the client passed it (plan §5.1). */
export function localDateStr(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// Calendar-day difference (not a raw 24h count) so "TOMORROW" means the next
// calendar date, not "within 24 hours" — a 11 PM-to-1 AM gap is still tomorrow.
function calendarDayDiff(from: Date, to: Date): number {
  const a = new Date(from.getFullYear(), from.getMonth(), from.getDate())
  const b = new Date(to.getFullYear(), to.getMonth(), to.getDate())
  return Math.round((b.getTime() - a.getTime()) / 86_400_000)
}

function formatShowtime(d: Date): string {
  return d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true })
}

function formatWeekday(d: Date): string {
  return d.toLocaleDateString('en-US', { weekday: 'long' })
}

export type CountdownKind =
  | 'upcoming'          // "in 12 days"
  | 'this-week'         // "Friday · 7:30 PM"
  | 'tomorrow'          // "TOMORROW"
  | 'tonight'           // "TONIGHT · 7:30 PM"
  | 'now-showing'       // "NOW SHOWING"
  | 'ended'             // showtime has passed but the RPC hasn't reconciled it yet
  | 'completed-pending' // "Fresh from the lobby" — completed, follow-up not yet handled

export interface OutingPresentation {
  kind: CountdownKind
  /** Human countdown chip text — see plan §4.5/§13 (never a raw ISO string). */
  label: string
  /** Full-sentence screen-reader text for the chip (polish checklist §13). */
  ariaLabel: string
  /** Only set for kind === 'upcoming'. */
  daysUntil?: number
}

const FOLLOW_UP_WINDOW_MS = 14 * 24 * 60 * 60 * 1000

/** The countdown-chip bucket for a scheduled/now-showing outing (plan §4.5),
 *  or the "Fresh from the lobby" bucket for a completed one whose follow-up
 *  is still pending. Cancelled/missed outings, and completed ones whose
 *  follow-up has been handled, have no marquee presentation — callers should
 *  filter those out (see computeMarqueeEntries) rather than call this. */
export function outingPresentation(outing: CinemaOuting, now: Date): OutingPresentation {
  if (outing.status === 'completed') {
    return {
      kind: 'completed-pending',
      label: 'Fresh from the lobby',
      ariaLabel: 'Just let out — how was it?',
    }
  }

  const showtime = new Date(outing.showtime)
  const endsAt = new Date(outing.endsAt)

  // The show has ended but the reconciler hasn't caught up yet (offline, or
  // simply between triggers) — never faked client-side (plan §4.3).
  if (now.getTime() >= endsAt.getTime()) {
    return { kind: 'ended', label: 'Finalizing…', ariaLabel: 'Show has ended — finalizing your viewing.' }
  }
  if (now.getTime() >= showtime.getTime()) {
    return { kind: 'now-showing', label: 'NOW SHOWING', ariaLabel: 'Now showing.' }
  }

  const dayDiff = calendarDayDiff(now, showtime)
  const time = formatShowtime(showtime)

  if (dayDiff === 0) {
    return { kind: 'tonight', label: `TONIGHT · ${time}`, ariaLabel: `Showing tonight at ${time}.` }
  }
  if (dayDiff === 1) {
    return { kind: 'tomorrow', label: 'TOMORROW', ariaLabel: `Showing tomorrow at ${time}.` }
  }
  if (dayDiff <= 7) {
    const weekday = formatWeekday(showtime)
    return { kind: 'this-week', label: `${weekday} · ${time}`, ariaLabel: `Showing ${weekday} at ${time}.` }
  }
  return {
    kind: 'upcoming',
    label: `in ${dayDiff} days`,
    daysUntil: dayDiff,
    ariaLabel: `Showing in ${dayDiff} days, ${formatWeekday(showtime)} at ${time}.`,
  }
}

// True while the "how was it?" follow-up (rate/note/recommend/didn't-make-it)
// is still live for a completed outing: not yet dismissed (explicitly, or by
// rating — dismissOutingFollowUp is called either way), and within the
// 14-day window the plan grants (§4.4).
function isFollowUpPending(outing: CinemaOuting, now: Date): boolean {
  if (outing.status !== 'completed') return false
  if (outing.followUpDismissedAt) return false
  return now.getTime() - new Date(outing.endsAt).getTime() <= FOLLOW_UP_WINDOW_MS
}

export interface MarqueeEntry {
  outing: CinemaOuting
  title: Title
  presentation: OutingPresentation
}

/** "On the Marquee" (plan §4.5): scheduled/now-showing outings plus
 *  completed ones still awaiting follow-up, sorted so the most time-sensitive
 *  card leads — scheduled/now-showing by soonest showtime, then completed
 *  follow-ups by most-recently-ended. Movies whose title has been deleted
 *  out from under the outing are silently skipped. */
export function computeMarqueeEntries(outings: CinemaOuting[], titles: Title[], now: Date): MarqueeEntry[] {
  const titleById = new Map(titles.map((t) => [t.id, t]))
  const entries: MarqueeEntry[] = []

  for (const outing of outings) {
    const title = titleById.get(outing.titleId)
    if (!title) continue
    if (outing.status === 'scheduled' || isFollowUpPending(outing, now)) {
      entries.push({ outing, title, presentation: outingPresentation(outing, now) })
    }
  }

  entries.sort((a, b) => {
    const aPending = a.outing.status === 'completed'
    const bPending = b.outing.status === 'completed'
    if (aPending !== bPending) return aPending ? 1 : -1
    if (aPending) {
      // Most-recently-ended first.
      return a.outing.endsAt < b.outing.endsAt ? 1 : a.outing.endsAt > b.outing.endsAt ? -1 : 0
    }
    // Soonest showtime first.
    return a.outing.showtime < b.outing.showtime ? -1 : a.outing.showtime > b.outing.showtime ? 1 : 0
  })

  return entries
}

/** The instant the store should re-arm its single completion timer at (plan
 *  §4.3) — the soonest `endsAt` across scheduled outings, or null when none
 *  are scheduled (nothing to wait for). */
export function nextTransitionAt(outings: CinemaOuting[]): string | null {
  let earliest: string | null = null
  for (const outing of outings) {
    if (outing.status !== 'scheduled') continue
    if (earliest === null || outing.endsAt < earliest) earliest = outing.endsAt
  }
  return earliest
}

/** Distinct past companion names (most-recent first, deduped case-
 *  insensitively) merged with accepted friends not already covered — the
 *  schedule form's chip-input autocomplete (plan §4.1/§13). */
export function companionSuggestions(
  outings: CinemaOuting[],
  viewings: Viewing[],
  friends: FriendshipView[]
): Companion[] {
  const timestamped: Array<{ companion: Companion; ts: string }> = []
  for (const o of outings) {
    for (const c of o.companions) timestamped.push({ companion: c, ts: o.showtime })
  }
  for (const v of viewings) {
    for (const c of v.companions ?? []) timestamped.push({ companion: c, ts: v.date ?? '' })
  }
  timestamped.sort((a, b) => (a.ts < b.ts ? 1 : a.ts > b.ts ? -1 : 0))

  const seen = new Set<string>()
  const past: Companion[] = []
  for (const { companion } of timestamped) {
    const key = companion.name.trim().toLowerCase()
    if (!key || seen.has(key)) continue
    seen.add(key)
    past.push(companion)
  }

  const friendCompanions: Companion[] = friends
    .filter((f) => f.status === 'accepted')
    .map((f): Companion => ({ name: f.display_name || f.username || 'Friend', friendUserId: f.friend_user_id }))
    .filter((c) => !seen.has(c.name.trim().toLowerCase()))

  return [...past, ...friendCompanions]
}

/** Joins companion names the way a sentence would ("Alex", "Alex & Sam",
 *  "Alex, Sam & Priya") — used by the drawer's scheduled banner, the ticket
 *  stub, the .ics DESCRIPTION, and the out-of-app share snippet. Empty input
 *  yields ''. */
export function formatCompanions(companions: Companion[]): string {
  const names = companions.map((c) => c.name).filter((n) => n.trim())
  if (names.length === 0) return ''
  if (names.length === 1) return names[0]
  return `${names.slice(0, -1).join(', ')} & ${names[names.length - 1]}`
}

/** Distinct past venues (most-recent first) across outings + viewings — the
 *  schedule form's theater autocomplete (plan §4.1/§13: "your usual theater
 *  is one tap"). */
export function venueSuggestions(outings: CinemaOuting[], viewings: Viewing[]): string[] {
  const timestamped: Array<{ venue: string; ts: string }> = []
  for (const o of outings) if (o.venue) timestamped.push({ venue: o.venue, ts: o.showtime })
  for (const v of viewings) if (v.venue) timestamped.push({ venue: v.venue, ts: v.date ?? '' })
  timestamped.sort((a, b) => (a.ts < b.ts ? 1 : a.ts > b.ts ? -1 : 0))

  const seen = new Set<string>()
  const venues: string[] = []
  for (const { venue } of timestamped) {
    const key = venue.trim().toLowerCase()
    if (!key || seen.has(key)) continue
    seen.add(key)
    venues.push(venue)
  }
  return venues
}
