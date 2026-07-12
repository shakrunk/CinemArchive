import { describe, expect, it } from 'vitest'
import {
  computeMarqueeEntries,
  nextTransitionAt,
  outingPresentation,
  companionSuggestions,
  venueSuggestions,
  formatCompanions,
} from './outings'
import type { CinemaOuting, Title, Viewing } from './mockData'
import type { FriendshipView } from '../lib/auth'

let idCounter = 0
function nextId(prefix: string): string {
  idCounter += 1
  return `${prefix}-${idCounter}`
}

function makeOuting(overrides: Partial<CinemaOuting> = {}): CinemaOuting {
  return {
    id: nextId('outing'),
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

describe('outingPresentation', () => {
  // `now`/`showtime`/`endsAt` are all built from local Date components (never
  // a fixed UTC string mixed with a local one) so the calendar-day math is
  // identical regardless of the machine's timezone running the test.
  const now = new Date(2026, 6, 17, 14, 0) // Jul 17, 2:00 PM local

  it('buckets a same-calendar-day showtime as tonight', () => {
    const outing = makeOuting({
      showtime: new Date(2026, 6, 17, 19, 30).toISOString(),
      endsAt: new Date(2026, 6, 17, 22, 0).toISOString(),
    })
    const p = outingPresentation(outing, now)
    expect(p.kind).toBe('tonight')
    expect(p.label).toMatch(/^TONIGHT ·/)
  })

  it('buckets tomorrow without a time suffix', () => {
    const outing = makeOuting({
      showtime: new Date(2026, 6, 18, 19, 30).toISOString(),
      endsAt: new Date(2026, 6, 18, 22, 0).toISOString(),
    })
    expect(outingPresentation(outing, now)).toMatchObject({ kind: 'tomorrow', label: 'TOMORROW' })
  })

  it('buckets 2-7 days out as "this-week" with a weekday label', () => {
    const outing = makeOuting({
      showtime: new Date(2026, 6, 20, 19, 30).toISOString(),
      endsAt: new Date(2026, 6, 20, 22, 0).toISOString(),
    })
    const p = outingPresentation(outing, now)
    expect(p.kind).toBe('this-week')
    expect(p.label).toMatch(/· /)
  })

  it('buckets more than 7 days out as "upcoming" with a day count', () => {
    const outing = makeOuting({
      showtime: new Date(2026, 6, 30, 19, 30).toISOString(),
      endsAt: new Date(2026, 6, 30, 22, 0).toISOString(),
    })
    const p = outingPresentation(outing, now)
    expect(p.kind).toBe('upcoming')
    expect(p.daysUntil).toBe(13)
    expect(p.label).toBe('in 13 days')
  })

  it('reads NOW SHOWING between showtime and endsAt', () => {
    const outing = makeOuting({
      showtime: '2026-07-17T19:30:00.000Z',
      endsAt: '2026-07-17T22:06:00.000Z',
    })
    const now = new Date('2026-07-17T20:00:00.000Z')
    expect(outingPresentation(outing, now).kind).toBe('now-showing')
  })

  it('reads "ended" once endsAt has passed but the outing is still scheduled', () => {
    const outing = makeOuting({
      showtime: '2026-07-17T19:30:00.000Z',
      endsAt: '2026-07-17T22:06:00.000Z',
    })
    const now = new Date('2026-07-17T23:00:00.000Z')
    expect(outingPresentation(outing, now).kind).toBe('ended')
  })

  it('reads "completed-pending" for a completed outing regardless of timing', () => {
    const outing = makeOuting({ status: 'completed', endsAt: '2026-07-17T22:06:00.000Z' })
    const now = new Date('2026-07-17T22:10:00.000Z')
    expect(outingPresentation(outing, now).kind).toBe('completed-pending')
  })
})

describe('computeMarqueeEntries', () => {
  const titles = [makeTitle({ id: 't1' }), makeTitle({ id: 't2', title: 'Second Feature' })]

  it('includes scheduled outings whose title still exists', () => {
    const outings = [makeOuting({ titleId: 't1' })]
    const entries = computeMarqueeEntries(outings, titles, new Date('2026-07-01'))
    expect(entries).toHaveLength(1)
    expect(entries[0].title.id).toBe('t1')
  })

  it('skips an outing whose title has been deleted', () => {
    const outings = [makeOuting({ titleId: 'missing-title' })]
    expect(computeMarqueeEntries(outings, titles, new Date('2026-07-01'))).toHaveLength(0)
  })

  it('excludes cancelled and missed outings', () => {
    const outings = [makeOuting({ status: 'cancelled' }), makeOuting({ status: 'missed' })]
    expect(computeMarqueeEntries(outings, titles, new Date('2026-07-01'))).toHaveLength(0)
  })

  it('includes a completed outing with a pending follow-up ("fresh from the lobby")', () => {
    const outings = [makeOuting({ status: 'completed', endsAt: '2026-07-17T22:06:00.000Z' })]
    const now = new Date('2026-07-18T00:00:00.000Z')
    expect(computeMarqueeEntries(outings, titles, now)).toHaveLength(1)
  })

  it('excludes a completed outing once the follow-up has been dismissed', () => {
    const outings = [
      makeOuting({
        status: 'completed',
        endsAt: '2026-07-17T22:06:00.000Z',
        followUpDismissedAt: '2026-07-17T23:00:00.000Z',
      }),
    ]
    const now = new Date('2026-07-18T00:00:00.000Z')
    expect(computeMarqueeEntries(outings, titles, now)).toHaveLength(0)
  })

  it('excludes a completed outing past the 14-day follow-up window', () => {
    const outings = [makeOuting({ status: 'completed', endsAt: '2026-01-01T00:00:00.000Z' })]
    const now = new Date('2026-07-01T00:00:00.000Z')
    expect(computeMarqueeEntries(outings, titles, now)).toHaveLength(0)
  })

  it('sorts scheduled outings soonest-first, ahead of completed follow-ups', () => {
    const later = makeOuting({ titleId: 't1', showtime: '2026-07-20T19:30:00.000Z', endsAt: '2026-07-20T22:00:00.000Z' })
    const sooner = makeOuting({ titleId: 't2', showtime: '2026-07-18T19:30:00.000Z', endsAt: '2026-07-18T22:00:00.000Z' })
    const pending = makeOuting({ titleId: 't1', status: 'completed', endsAt: '2026-07-16T22:00:00.000Z' })
    const now = new Date('2026-07-17T00:00:00.000Z')
    const entries = computeMarqueeEntries([later, sooner, pending], titles, now)
    expect(entries.map((e) => e.outing.id)).toEqual([sooner.id, later.id, pending.id])
  })
})

describe('nextTransitionAt', () => {
  it('returns the earliest endsAt among scheduled outings', () => {
    const outings = [
      makeOuting({ endsAt: '2026-07-20T22:00:00.000Z' }),
      makeOuting({ endsAt: '2026-07-18T22:00:00.000Z' }),
    ]
    expect(nextTransitionAt(outings)).toBe('2026-07-18T22:00:00.000Z')
  })

  it('ignores non-scheduled outings', () => {
    const outings = [
      makeOuting({ status: 'completed', endsAt: '2026-07-15T22:00:00.000Z' }),
      makeOuting({ status: 'scheduled', endsAt: '2026-07-20T22:00:00.000Z' }),
    ]
    expect(nextTransitionAt(outings)).toBe('2026-07-20T22:00:00.000Z')
  })

  it('returns null when there are no scheduled outings', () => {
    expect(nextTransitionAt([makeOuting({ status: 'cancelled' })])).toBeNull()
  })

  it('returns null for an empty list', () => {
    expect(nextTransitionAt([])).toBeNull()
  })
})

describe('companionSuggestions', () => {
  it('merges past companions (most-recent first) with accepted friends, deduped by name', () => {
    const outings = [makeOuting({ showtime: '2026-06-01T00:00:00.000Z', companions: [{ name: 'Alex' }] })]
    const viewings: Viewing[] = [
      { id: 'v1', titleId: 't1', date: '2026-07-01', companions: [{ name: 'Sam' }] },
    ]
    const friends: FriendshipView[] = [
      {
        friend_user_id: 'u2', status: 'accepted', requested_by: 'u1', blocked_by: null,
        created_at: '2026-01-01', updated_at: '2026-01-01', display_name: 'Priya', username: 'priya',
      },
      {
        friend_user_id: 'u3', status: 'accepted', requested_by: 'u1', blocked_by: null,
        created_at: '2026-01-01', updated_at: '2026-01-01', display_name: 'Alex', username: 'alexr',
      },
    ]
    const suggestions = companionSuggestions(outings, viewings, friends)
    // Sam (viewing, most recent) then Alex (outing) then Priya (friend); the
    // friend named Alex is deduped against the past companion "Alex".
    expect(suggestions.map((c) => c.name)).toEqual(['Sam', 'Alex', 'Priya'])
    expect(suggestions.find((c) => c.name === 'Priya')?.friendUserId).toBe('u2')
  })

  it('ignores non-accepted friendships', () => {
    const friends: FriendshipView[] = [
      {
        friend_user_id: 'u2', status: 'pending', requested_by: 'u1', blocked_by: null,
        created_at: '2026-01-01', updated_at: '2026-01-01', display_name: 'Priya', username: 'priya',
      },
    ]
    expect(companionSuggestions([], [], friends)).toEqual([])
  })
})

describe('venueSuggestions', () => {
  it('returns distinct venues, most-recent first', () => {
    const outings = [makeOuting({ showtime: '2026-06-01T00:00:00.000Z', venue: 'AMC Georgetown' })]
    const viewings: Viewing[] = [
      { id: 'v1', titleId: 't1', date: '2026-07-01', venue: 'Alamo Drafthouse' },
      { id: 'v2', titleId: 't1', date: '2026-01-01', venue: 'AMC Georgetown' },
    ]
    expect(venueSuggestions(outings, viewings)).toEqual(['Alamo Drafthouse', 'AMC Georgetown'])
  })
})

describe('formatCompanions', () => {
  it('returns an empty string for no companions', () => {
    expect(formatCompanions([])).toBe('')
  })

  it('returns a bare name for a single companion', () => {
    expect(formatCompanions([{ name: 'Alex' }])).toBe('Alex')
  })

  it('joins two companions with "&"', () => {
    expect(formatCompanions([{ name: 'Alex' }, { name: 'Sam' }])).toBe('Alex & Sam')
  })

  it('joins three or more with commas and a trailing "&"', () => {
    expect(formatCompanions([{ name: 'Alex' }, { name: 'Sam' }, { name: 'Priya' }])).toBe('Alex, Sam & Priya')
  })
})
