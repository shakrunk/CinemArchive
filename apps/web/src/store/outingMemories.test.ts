import { describe, expect, it } from 'vitest'
import { computeOutingMemories } from './outingMemories'
import type { CinemaOuting, Title } from './mockData'

const title: Title = {
  id: 'title', tmdbId: 1, type: 'movie', title: 'A night at the cinema', year: 2020,
  genres: [], tags: [], status: 'watched', addedAt: '2020-01-01', viewings: [],
}

function outing(id: string, year: number, patch: Partial<CinemaOuting> = {}): CinemaOuting {
  return {
    id, titleId: title.id, status: 'completed', companions: [], seats: [], previewsMinutes: 20, runtimeMinutes: 120,
    showtime: new Date(year, 9, 8, 23, 30).toISOString(), endsAt: new Date(year, 9, 9, 1, 50).toISOString(),
    createdAt: new Date(year, 9, 1).toISOString(), ...patch,
  }
}

describe('cinema memories', () => {
  const today = new Date(2026, 9, 8, 12)

  it('matches local showtime anniversaries, newest first, regardless of completion date', () => {
    const entries = computeOutingMemories([outing('older', 2023), outing('newer', 2025)], [title], today)
    expect(entries.map((entry) => [entry.outing.id, entry.yearsAgo])).toEqual([['newer', 1], ['older', 3]])
  })

  it('correctly sorts mixed-offset times chronologically', () => {
    // 20:00+00:00 (14:00 MDT) vs 23:00-06:00 (23:00 MDT)
    const tripA = outing('tripA', 2025, { showtime: '2025-10-08T23:00:00-06:00' })
    const tripB = outing('tripB', 2025, { showtime: '2025-10-08T20:00:00+00:00' })
    // computeOutingMemories descending sort: tripA (later in time) comes first.
    const entries = computeOutingMemories([tripB, tripA], [title], today)
    expect(entries.map(e => e.outing.id)).toEqual(['tripA', 'tripB'])
  })

  it('excludes this year, future years, other days, invalid dates, missing titles, and uncompleted trips', () => {
    const candidates = [
      outing('current', 2026), outing('future', 2027),
      outing('other-day', 2025, { showtime: new Date(2025, 9, 9).toISOString() }),
      outing('invalid', 2025, { showtime: 'not a date' }), outing('deleted', 2025, { titleId: 'deleted' }),
      ...(['scheduled', 'cancelled', 'missed'] as const).map((status) => outing(status, 2025, { status })),
    ]
    expect(computeOutingMemories(candidates, [title], today)).toEqual([])
  })

  it('uses the linked viewing for rating and notes instead of the title or another rewatch', () => {
    const linked = { id: 'linked', titleId: title.id, rating: 4.5, notes: 'Great crowd' }
    const entry = computeOutingMemories([outing('trip', 2025, { completedViewingId: 'linked' })], [{
      ...title, rating: 3, viewings: [{ id: 'other', titleId: title.id, rating: 2 }, linked],
    }], today)[0]
    expect(entry.viewing).toEqual(linked)
  })

  it('recovers the viewing via outingId when no completion pointer is available', () => {
    const linked = { id: 'linked', titleId: title.id, outingId: 'trip', notes: 'A rewatch' }
    expect(computeOutingMemories([outing('trip', 2025)], [{ ...title, viewings: [linked] }], today)[0].viewing).toEqual(linked)
    expect(computeOutingMemories([outing('trip', 2025)], [title], today)[0].viewing).toBeUndefined()
  })

  it('shows leap-day anniversaries on leap day only', () => {
    const trip = outing('leap', 2024, { showtime: new Date(2024, 1, 29, 20).toISOString() })
    expect(computeOutingMemories([trip], [title], new Date(2028, 1, 29))).toHaveLength(1)
    expect(computeOutingMemories([trip], [title], new Date(2027, 1, 28))).toEqual([])
  })
})
