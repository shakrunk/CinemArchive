import { describe, it, expect } from 'vitest'
import {
  formatAuditorium,
  formatSeatLine,
  formatSeatShort,
  formatSeats,
  hasStructuredSeating,
  parseSeatsInput,
  type SeatAssignment,
} from 'src/lib/seating'

const empty: SeatAssignment = { seats: [] }

describe('hasStructuredSeating', () => {
  it('is false for an outing carrying only the legacy free-text seat', () => {
    expect(hasStructuredSeating({ ...empty, seat: 'F12-13' })).toBe(false)
  })

  it('is true as soon as any one part of the trio is set', () => {
    expect(hasStructuredSeating({ ...empty, auditorium: '7' })).toBe(true)
    expect(hasStructuredSeating({ ...empty, seatRow: 'F' })).toBe(true)
    expect(hasStructuredSeating({ ...empty, seats: ['12'] })).toBe(true)
  })
})

describe('formatAuditorium', () => {
  it('names a bare number so it reads as a place, not a quantity', () => {
    expect(formatAuditorium('7')).toBe('Theatre 7')
  })

  it("leaves a venue's own wording alone", () => {
    expect(formatAuditorium('Grand Hall')).toBe('Grand Hall')
    expect(formatAuditorium('IMAX 1')).toBe('IMAX 1')
  })

  it('treats blank and whitespace-only as absent', () => {
    expect(formatAuditorium(undefined)).toBeUndefined()
    expect(formatAuditorium('   ')).toBeUndefined()
  })
})

describe('formatSeats', () => {
  it('singularizes a lone seat', () => {
    expect(formatSeats(['12'])).toBe('Seat 12')
  })

  it('lists a party', () => {
    expect(formatSeats(['12', '13'])).toBe('Seats 12, 13')
  })

  it('drops blank entries rather than emitting a dangling comma', () => {
    expect(formatSeats(['12', '  ', ''])).toBe('Seat 12')
    expect(formatSeats([])).toBeUndefined()
  })
})

describe('formatSeatLine', () => {
  it('orders the trio the way you need it walking in', () => {
    expect(formatSeatLine({ auditorium: '7', seatRow: 'F', seats: ['12', '13'] })).toBe(
      'Theatre 7 · Row F · Seats 12, 13'
    )
  })

  it('omits the parts that are missing', () => {
    expect(formatSeatLine({ auditorium: '7', seats: [] })).toBe('Theatre 7')
    expect(formatSeatLine({ seatRow: 'F', seats: ['12'] })).toBe('Row F · Seat 12')
  })

  it('falls back to the legacy free-text seat verbatim', () => {
    expect(formatSeatLine({ ...empty, seat: 'Row F, seats 12 and 13' })).toBe('Row F, seats 12 and 13')
  })

  it('prefers the structured trio over a stale legacy string', () => {
    expect(formatSeatLine({ seat: 'H12', auditorium: '7', seats: [] })).toBe('Theatre 7')
  })

  it('is undefined when the outing has no seating at all', () => {
    expect(formatSeatLine(empty)).toBeUndefined()
  })
})

describe('formatSeatShort', () => {
  it('runs row and seat together the way a printed ticket does', () => {
    expect(formatSeatShort({ auditorium: '7', seatRow: 'F', seats: ['12'] })).toBe('F12')
    expect(formatSeatShort({ auditorium: '7', seatRow: 'F', seats: ['12', '13'] })).toBe('F12, F13')
  })

  it('degrades to whatever it has when seats are unknown', () => {
    expect(formatSeatShort({ seatRow: 'F', seats: [] })).toBe('F')
    expect(formatSeatShort({ auditorium: '7', seats: [] })).toBe('Theatre 7')
    expect(formatSeatShort({ ...empty, seat: 'H12' })).toBe('H12')
  })
})

describe('parseSeatsInput', () => {
  it('accepts commas, spaces, or both', () => {
    expect(parseSeatsInput('12, 13')).toEqual(['12', '13'])
    expect(parseSeatsInput('12 13')).toEqual(['12', '13'])
    expect(parseSeatsInput('12,13')).toEqual(['12', '13'])
  })

  it('is empty for blank entry', () => {
    expect(parseSeatsInput('')).toEqual([])
    expect(parseSeatsInput('  ,  ')).toEqual([])
  })
})
