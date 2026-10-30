import { isSupabaseConfigured, supabase } from './auth'
import { TICKET_FORMATS, type TicketBarcode } from './tickets/types'

// Sending an outing's ticket to accepted friends (#220): the owner sends the ticket photo and its
// decoded barcode, sees who was sent it and who has opened it, and can take it back; a friend lists
// what was sent to them, opens one and downloads the file. Mirrors Android's TicketSending.kt /
// TicketSharingRepository.kt; the contract is docs/android-contracts/ticket-sending.md. Online-only,
// and the ticket is the owner's existing private attachment: nothing is copied. Seats, booking
// references and notes are never shared.

export interface TicketGrant {
  grantId: string
  userId: string
  name: string
  sentAt: string
  /** When the friend first opened it, or null while it is unopened. */
  openedAt: string | null
}

/** A ticket a friend sent the signed-in user. Listing it shows no barcode; opening it does. */
export interface ReceivedTicket {
  grantId: string
  senderId: string
  senderName: string
  title: string
  year: number | null
  posterUrl: string | null
  showtime: string
  endsAt: string
  venue: string | null
  sentAt: string
  openedAt: string | null
}

/** Everything needed to show a received ticket: the file to download and the decoded barcode. */
export interface OpenedTicket {
  objectKey: string
  mimeType: 'image/jpeg' | 'image/png' | 'image/webp'
  byteLength: number
  barcode: TicketBarcode | null
  title: string
  showtime: string
  venue: string | null
}

/** The most friends one ticket is sent to; the database refuses a ninth. */
export const MAX_TICKET_RECIPIENTS = 8

const BUCKET = 'ticket-attachments'
const DAY = 24 * 60 * 60 * 1000
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const isUuid = (value: unknown): value is string => typeof value === 'string' && UUID_RE.test(value)
const text = (value: unknown): string | null => (typeof value === 'string' && value.trim() ? value : null)
const instant = (value: unknown): string | null => (typeof value === 'string' && !Number.isNaN(Date.parse(value)) ? value : null)
const record = (value: unknown): Record<string, unknown> | null =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null

/** Received tickets stay listed until a day after the show ends. */
export const isCurrentTicket = (ticket: Pick<ReceivedTicket, 'endsAt'>, now: number) => Date.parse(ticket.endsAt) > now - DAY

/** Soonest show first. */
export const orderedTickets = (tickets: ReceivedTicket[], now: number) =>
  tickets.filter((t) => isCurrentTicket(t, now)).sort((a, b) => Date.parse(a.showtime) - Date.parse(b.showtime))

/** The wire value for a format (`QR_CODE`...), tolerating one this build does not know. */
export const parseTicketFormat = (wire: unknown): TicketBarcode['format'] =>
  TICKET_FORMATS.find((format) => format === wire) ?? 'OTHER'

/** Rows with an unexpected shape are skipped, never shown. */
export function parseTicketGrants(rows: unknown): TicketGrant[] {
  if (!Array.isArray(rows)) return []
  return rows.flatMap((raw): TicketGrant[] => {
    const row = record(raw)
    if (!row || !isUuid(row.grant_id) || !isUuid(row.user_id)) return []
    return [{ grantId: row.grant_id, userId: row.user_id, name: text(row.name) ?? 'A friend', sentAt: typeof row.sent_at === 'string' ? row.sent_at : '', openedAt: text(row.opened_at) }]
  })
}

export function parseReceivedTickets(rows: unknown): ReceivedTicket[] {
  if (!Array.isArray(rows)) return []
  return rows.flatMap((raw): ReceivedTicket[] => {
    const row = record(raw)
    const showtime = instant(row?.showtime)
    const endsAt = instant(row?.ends_at)
    const title = text(row?.title)
    if (!row || !isUuid(row.grant_id) || !isUuid(row.sender_id) || !showtime || !endsAt || !title) return []
    return [{
      grantId: row.grant_id, senderId: row.sender_id, senderName: text(row.sender_name) ?? 'A friend', title,
      year: typeof row.year === 'number' && row.year > 0 ? row.year : null, posterUrl: text(row.poster_url),
      showtime, endsAt, venue: text(row.venue), sentAt: typeof row.sent_at === 'string' ? row.sent_at : '', openedAt: text(row.opened_at),
    }]
  })
}

export function parseOpenedTicket(value: unknown): OpenedTicket | null {
  const row = record(value)
  if (!row) return null
  const mimeType = row.mimeType
  if (mimeType !== 'image/jpeg' && mimeType !== 'image/png' && mimeType !== 'image/webp') return null
  const objectKey = text(row.objectKey)
  const title = text(row.title)
  const showtime = instant(row.showtime)
  if (!objectKey || objectKey.includes('..') || !title || !showtime) return null
  if (typeof row.byteLength !== 'number' || !Number.isInteger(row.byteLength) || row.byteLength <= 0) return null
  const code = record(row.barcode)
  const payload = text(code?.payload)
  return { objectKey, mimeType, byteLength: row.byteLength, barcode: payload ? { payload, format: parseTicketFormat(code?.format) } : null, title, showtime, venue: text(row.venue) }
}

function connection() {
  if (!isSupabaseConfigured || !supabase) throw new Error('Sign in to send tickets to friends.')
  return supabase
}

function failure(error: { message?: string } | null, fallback: string): Error {
  return new Error(error?.message && error.message.length < 160 ? error.message : fallback)
}

/** The owner's view of who was sent this outing's current ticket, and who has opened it. */
export async function ticketGrants(outingId: string): Promise<TicketGrant[]> {
  if (!isUuid(outingId)) throw new Error('Invalid outing')
  const { data, error } = await connection().rpc('get_ticket_grants', { p_outing_id: outingId })
  if (error) throw failure(error, "Couldn't load who has this ticket.")
  return parseTicketGrants(data)
}

/** Sends the outing's current ticket to accepted friends and returns everyone it has now been sent to. */
export async function sendTicket(outingId: string, recipientIds: string[]): Promise<TicketGrant[]> {
  const ids = [...new Set(recipientIds)]
  if (!isUuid(outingId) || ids.length === 0 || !ids.every(isUuid)) throw new Error('Choose at least one friend.')
  const { data, error } = await connection().rpc('send_ticket', { p_outing_id: outingId, p_recipient_ids: ids })
  if (error) throw failure(error, 'Could not send the ticket.')
  return parseTicketGrants(data)
}

/** Takes the ticket back from one friend; they lose access at once. */
export async function revokeTicketGrant(grantId: string): Promise<void> {
  if (!isUuid(grantId)) throw new Error('Invalid id')
  const { error } = await connection().rpc('revoke_ticket_grant', { p_grant_id: grantId })
  if (error) throw failure(error, "Couldn't take the ticket back.")
}

export async function receivedTickets(): Promise<ReceivedTicket[]> {
  const { data, error } = await connection().rpc('get_received_tickets')
  if (error) throw failure(error, "Couldn't load tickets from friends.")
  return parseReceivedTickets(data)
}

/** Opens a received ticket: the first open is recorded and the sender is told. */
export async function openReceivedTicket(grantId: string): Promise<OpenedTicket> {
  if (!isUuid(grantId)) throw new Error('Invalid id')
  const { data, error } = await connection().rpc('open_received_ticket', { p_grant_id: grantId })
  if (error) throw failure(error, 'This ticket is no longer available.')
  const ticket = parseOpenedTicket(data)
  if (!ticket) throw new Error('This ticket could not be read.')
  return ticket
}

/** The ticket file, checked against the size the server reported. */
export async function downloadSentTicket(ticket: OpenedTicket): Promise<Blob> {
  const { data, error } = await connection().storage.from(BUCKET).download(ticket.objectKey)
  if (error || !data) throw new Error('Could not download the ticket. Check your connection.')
  if (data.size !== ticket.byteLength) throw new Error('The downloaded ticket has an unexpected size.')
  return data
}
