import { describe, expect, it } from 'vitest'
import { mapDbOutingToLocal, mapDbViewingToLocal } from './db'

describe('mapDbViewingToLocal', () => {
  it('maps snake_case columns to the camelCase Viewing shape', () => {
    const row = {
      id: 'v1',
      title_id: 't1',
      viewed_at: '2026-07-17',
      rating: '4.5',
      notes: 'Great trip',
      venue: 'AMC Georgetown',
      companions: [{ name: 'Alex' }, { name: 'Sam', friendUserId: 'u2' }],
      outing_id: 'o1',
    }
    expect(mapDbViewingToLocal(row)).toEqual({
      id: 'v1',
      titleId: 't1',
      date: '2026-07-17',
      rating: 4.5,
      notes: 'Great trip',
      venue: 'AMC Georgetown',
      companions: [{ name: 'Alex' }, { name: 'Sam', friendUserId: 'u2' }],
      outingId: 'o1',
    })
  })

  it('omits venue/companions/outingId when absent (a plain home viewing)', () => {
    const row = { id: 'v2', title_id: 't1', viewed_at: '2026-01-01', rating: null, notes: null, companions: [] }
    const viewing = mapDbViewingToLocal(row)
    expect(viewing.venue).toBeUndefined()
    expect(viewing.companions).toBeUndefined()
    expect(viewing.outingId).toBeUndefined()
  })

  it('treats a null viewed_at as an indeterminate (pre-platform) date', () => {
    const row = { id: 'v3', title_id: 't1', viewed_at: null, companions: [] }
    expect(mapDbViewingToLocal(row).date).toBeUndefined()
  })
})

describe('mapDbOutingToLocal', () => {
  const fullRow = {
    id: 'o1',
    title_id: 't1',
    showtime: '2026-07-17T19:30:00.000Z',
    previews_minutes: 20,
    runtime_minutes: 166,
    ends_at: '2026-07-17T22:36:00.000Z',
    venue: 'AMC Georgetown',
    companions: [{ name: 'Alex' }, { name: 'Sam' }],
    format: 'IMAX',
    ticket_price: '18.50',
    seat: 'H12',
    booking_ref: 'AMC-4X9KQ2',
    notes: 'Grab popcorn early',
    status: 'scheduled',
    previous_status: null,
    completed_viewing_id: null,
    follow_up_dismissed_at: null,
    created_at: '2026-07-16T00:00:00.000Z',
  }

  it('maps every scheduled-outing column to the camelCase CinemaOuting shape', () => {
    expect(mapDbOutingToLocal(fullRow)).toEqual({
      id: 'o1',
      titleId: 't1',
      showtime: '2026-07-17T19:30:00.000Z',
      previewsMinutes: 20,
      runtimeMinutes: 166,
      endsAt: '2026-07-17T22:36:00.000Z',
      venue: 'AMC Georgetown',
      companions: [{ name: 'Alex' }, { name: 'Sam' }],
      format: 'IMAX',
      ticketPrice: 18.5,
      seat: 'H12',
      bookingRef: 'AMC-4X9KQ2',
      notes: 'Grab popcorn early',
      status: 'scheduled',
      previousStatus: undefined,
      completedViewingId: undefined,
      followUpDismissedAt: undefined,
      createdAt: '2026-07-16T00:00:00.000Z',
    })
  })

  it('maps a completed outing carrying its revert bookkeeping', () => {
    const row = {
      ...fullRow,
      status: 'completed',
      previous_status: 'watchlist',
      completed_viewing_id: 'v1',
    }
    const outing = mapDbOutingToLocal(row)
    expect(outing.status).toBe('completed')
    expect(outing.previousStatus).toBe('watchlist')
    expect(outing.completedViewingId).toBe('v1')
  })

  it('defaults companions to an empty array when the column is missing', () => {
    const row = { ...fullRow, companions: undefined }
    expect(mapDbOutingToLocal(row).companions).toEqual([])
  })
})
