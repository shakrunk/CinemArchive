import type { CinemaOuting } from 'src/store/mockData'
import { formatCompanions, type OutingSharePayload } from 'src/store/outings'

// Hand-rolled cinema-outing calendar/share helpers (plan §4.5/§4.10) — no
// calendar/share npm dependency anywhere in this feature.

/** Lowercase, hyphenated, filesystem-safe slug for the .ics filename. */
function slugify(s: string): string {
  return s
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '') || 'outing'
}

/** `{title-slug}-{yyyy-mm-dd}.ics` — the date is the *showtime's* local date,
 *  matching what a user would expect to find the file named. */
export function outingIcsFilename(title: string, showtimeIso: string): string {
  const d = new Date(showtimeIso)
  const ymd = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  return `${slugify(title)}-${ymd}.ics`
}

// RFC 5545 §3.3.11 TEXT escaping — backslash, semicolon, comma, then newlines
// as the literal two-character "\n" (order matters: escape backslash first).
function icsEscape(s: string): string {
  return s
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n')
}

// UTC "floating" instant per RFC 5545 §3.3.5, e.g. 20260717T193000Z.
function icsDateTime(iso: string): string {
  return new Date(iso).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z')
}

/** Builds the DESCRIPTION body: companions, seat, booking ref, notes — each
 *  on its own line, blank fields simply omitted (plan §13). */
function buildDescription(outing: CinemaOuting): string {
  const lines: string[] = []
  const companions = formatCompanions(outing.companions)
  if (companions) lines.push(`With ${companions}`)
  if (outing.seat) lines.push(`Seat ${outing.seat}`)
  if (outing.bookingRef) lines.push(`Booking ref: ${outing.bookingRef}`)
  if (outing.notes) lines.push(outing.notes)
  return lines.join('\n')
}

interface VeventFields {
  uid: string
  titleName: string
  venue?: string
  showtime: string
  endsAt: string
  description: string
}

/** Hand-rolled VEVENT (plan §4.5): DTSTART/DTEND bracket the showtime→ends_at
 *  window, LOCATION carries the venue so phone calendars offer directions,
 *  and a VALARM fires 2 hours before showtime — the only pre-show reminder
 *  v1 can offer (no server push until Phase G). Shared by the owner's outing
 *  export and the recipient's plan-share export (plan §4.10), which have no
 *  `CinemaOuting` row to read from. */
function buildVevent({ uid, titleName, venue, showtime, endsAt, description }: VeventFields): string {
  const summary = venue ? `🎬 ${titleName} — ${venue}` : `🎬 ${titleName}`

  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//CinemArchive//Cinema Outings//EN',
    'BEGIN:VEVENT',
    `UID:${uid}@cinemarchive`,
    `DTSTAMP:${icsDateTime(new Date().toISOString())}`,
    `DTSTART:${icsDateTime(showtime)}`,
    `DTEND:${icsDateTime(endsAt)}`,
    `SUMMARY:${icsEscape(summary)}`,
  ]
  if (venue) lines.push(`LOCATION:${icsEscape(venue)}`)
  if (description) lines.push(`DESCRIPTION:${icsEscape(description)}`)
  lines.push(
    'BEGIN:VALARM',
    'ACTION:DISPLAY',
    'DESCRIPTION:Reminder',
    'TRIGGER:-PT2H',
    'END:VALARM',
    'END:VEVENT',
    'END:VCALENDAR'
  )
  // CRLF line endings per RFC 5545 §3.1.
  return lines.join('\r\n')
}

export function buildOutingIcs(outing: CinemaOuting, titleName: string): string {
  return buildVevent({
    uid: outing.id,
    titleName,
    venue: outing.venue,
    showtime: outing.showtime,
    endsAt: outing.endsAt,
    description: buildDescription(outing),
  })
}

/** The recipient's "Add to calendar" CTA on an `outing_plans_shared`
 *  notification (plan §4.10) — built entirely from the shared snapshot, with
 *  no access to (or need for) the sender's outing row. */
export function buildOutingIcsFromSharePayload(payload: OutingSharePayload): string {
  const companions = formatCompanions(payload.companions.map((name) => ({ name })))
  const description = companions ? `With ${companions}` : ''
  return buildVevent({
    // No outing id to key off of on the recipient's side — tmdbId + showtime
    // is stable enough to make re-downloads collide (rather than duplicate)
    // in a calendar app that dedupes by UID.
    uid: `share-${payload.tmdbId}-${payload.showtime}`,
    titleName: payload.title,
    venue: payload.venue,
    showtime: payload.showtime,
    endsAt: payload.endsAt,
    description,
  })
}

/** Triggers a browser download of the .ics text — no dependency, just an
 *  anchor click against an object URL (same pattern as any hand-rolled
 *  client-side file download). */
export function downloadIcsFile(filename: string, icsText: string): void {
  const blob = new Blob([icsText], { type: 'text/calendar;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** The out-of-app share snippet (plan §4.10) — reads like a text a friend
 *  would actually send, not a data dump. Fields left blank are omitted
 *  entirely rather than rendered as empty placeholders. */
export function formatOutingShareSnippet(
  titleName: string,
  showtimeIso: string,
  venue?: string,
  format?: string,
  seat?: string
): string {
  const d = new Date(showtimeIso)
  // Built from separate weekday / month-day calls (not the combined
  // weekday+month+day form) so the result reads "Fri Jul 17" — ICU inserts a
  // comma after the weekday in the combined form ("Fri, Jul 17"), which reads
  // like a data dump rather than the text a friend would actually send.
  const weekday = d.toLocaleDateString('en-US', { weekday: 'short' })
  const monthDay = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
  const dateLabel = `${weekday} ${monthDay}`
  const timeLabel = d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', hour12: true })

  const segments = [`${dateLabel}, ${timeLabel}`]
  if (venue) segments.push(venue)
  if (format && format !== 'Standard') segments.push(format)
  if (seat) segments.push(`I'm in ${seat} — grab a seat nearby!`)

  return `🎬 ${titleName} — ${segments.join(' · ')}`
}

export type ShareOutcome = 'shared' | 'copied' | 'cancelled'

/** navigator.share where available (mobile/PWA share sheet), clipboard
 *  fallback otherwise (plan §4.10). A user-cancelled share sheet is reported
 *  as 'cancelled' rather than falling through to the clipboard, so the
 *  caller doesn't surface a confusing "copied" toast after a deliberate
 *  dismissal. */
export async function shareOutingSnippet(text: string): Promise<ShareOutcome> {
  if (navigator.share) {
    try {
      await navigator.share({ text })
      return 'shared'
    } catch (err) {
      // A user-dismissed native share sheet rejects with an AbortError (a
      // DOMException in browsers) — checked by name, not `instanceof
      // DOMException`, since that's what the rejection is contracted to be.
      if (err && typeof err === 'object' && 'name' in err && err.name === 'AbortError') return 'cancelled'
      // Fall through to the clipboard for any other share failure.
    }
  }
  await navigator.clipboard.writeText(text)
  return 'copied'
}
