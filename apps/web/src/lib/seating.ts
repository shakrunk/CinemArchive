import type { CinemaOuting } from 'src/store/mockData'

/** The seat-assignment fields of an outing — the structured trio plus the
 *  legacy free-text `seat` it falls back to. Taken as a structural subset so
 *  callers holding a partial outing (a form draft, a share payload) can use
 *  these helpers without constructing a whole `CinemaOuting`. */
export type SeatAssignment = Pick<CinemaOuting, 'auditorium' | 'seatRow' | 'seats' | 'seat'>

/** True when any part of the structured trio is filled in. Drives whether a
 *  surface renders the auditorium-first layout or the legacy single string. */
export function hasStructuredSeating(a: SeatAssignment): boolean {
  return Boolean(a.auditorium || a.seatRow || (a.seats && a.seats.length > 0))
}

/** "Theatre 7" for a bare number, the venue's own wording otherwise ("Grand
 *  Hall", "IMAX 1"). Vendors are inconsistent about whether they include the
 *  noun, and prefixing one onto "Grand Hall" reads badly. */
export function formatAuditorium(auditorium: string | undefined): string | undefined {
  const trimmed = auditorium?.trim()
  if (!trimmed) return undefined
  return /^\d+$/.test(trimmed) ? `Theatre ${trimmed}` : trimmed
}

/** "12" → "Seat 12"; "12", "13" → "Seats 12, 13". */
export function formatSeats(seats: string[] | undefined): string | undefined {
  const clean = (seats ?? []).map((s) => s.trim()).filter(Boolean)
  if (clean.length === 0) return undefined
  return `${clean.length === 1 ? 'Seat' : 'Seats'} ${clean.join(', ')}`
}

/** One-line seat assignment, ordered by what you need first walking into a
 *  multiplex: auditorium, then row, then seats — e.g.
 *  "Theatre 7 · Row F · Seats 12, 13". Falls back to the legacy free-text
 *  `seat` when nothing structured is set, and to undefined when neither is. */
export function formatSeatLine(a: SeatAssignment): string | undefined {
  if (!hasStructuredSeating(a)) return a.seat?.trim() || undefined
  const segments = [
    formatAuditorium(a.auditorium),
    a.seatRow?.trim() ? `Row ${a.seatRow.trim()}` : undefined,
    formatSeats(a.seats),
  ].filter((s): s is string => Boolean(s))
  return segments.length > 0 ? segments.join(' · ') : undefined
}

/** The compact form for running prose — "F12", "F12, F13" — where the full
 *  auditorium/row/seats line would swamp the sentence around it (the share
 *  snippet's "I'm in F12 — grab a seat nearby!"). Row and seat are run
 *  together the way a printed ticket prints them; auditorium is dropped, since
 *  a friend picking their own seat is already choosing the same screening. */
export function formatSeatShort(a: SeatAssignment): string | undefined {
  if (!hasStructuredSeating(a)) return a.seat?.trim() || undefined
  const row = a.seatRow?.trim() ?? ''
  const clean = (a.seats ?? []).map((s) => s.trim()).filter(Boolean)
  if (clean.length === 0) return row || formatAuditorium(a.auditorium)
  return clean.map((s) => `${row}${s}`).join(', ')
}

/** Parses the comma/space-separated seat entry a text input produces into the
 *  stored array: "12, 13" and "12 13" both give ["12", "13"]. */
export function parseSeatsInput(value: string): string[] {
  return value
    .split(/[,\s]+/)
    .map((s) => s.trim())
    .filter(Boolean)
}
