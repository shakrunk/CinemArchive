import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  downloadSentTicket, isCurrentTicket, openReceivedTicket, orderedTickets, parseOpenedTicket, parseReceivedTickets, parseTicketFormat,
  parseTicketGrants, receivedTickets, revokeTicketGrant, sendTicket, ticketGrants, type ReceivedTicket,
} from './ticketSending'

const owner = '10000000-0000-4000-8000-000000000001'
const friend = '20000000-0000-4000-8000-000000000001'
const grant = '30000000-0000-4000-8000-000000000001'
const outing = '40000000-0000-4000-8000-000000000001'

const { client, download } = vi.hoisted(() => ({ client: { rpc: vi.fn(), storage: { from: vi.fn() } }, download: vi.fn() }))
vi.mock('./auth', () => ({ isSupabaseConfigured: true, supabase: client }))

const grantRow = (overrides: Record<string, unknown> = {}) => ({ grant_id: grant, user_id: friend, name: 'Sam Friend', sent_at: '2026-11-01T10:00:00+00:00', opened_at: null, ...overrides })
const receivedRow = (overrides: Record<string, unknown> = {}) => ({
  grant_id: grant, sender_id: friend, sender_name: 'Fran', title: 'Film', year: 2026, poster_url: null,
  showtime: '2099-11-02T19:00:00+00:00', ends_at: '2099-11-02T21:00:00+00:00', venue: 'AMC', sent_at: '2026-11-01T10:00:00+00:00', opened_at: null, ...overrides,
})
const openedRow = (overrides: Record<string, unknown> = {}) => ({
  id: 'x', objectKey: `${friend}/aaa/original`, mimeType: 'image/png', byteLength: 4, sha256: 'a',
  barcode: { payload: 'CODE-123', format: 'QR_CODE' }, title: 'Film', showtime: '2099-11-02T19:00:00+00:00', venue: 'AMC', ...overrides,
})
const ticket = (id: string, showtime: string, endsAt: string): ReceivedTicket => ({ ...parseReceivedTickets([receivedRow({ grant_id: id, showtime, ends_at: endsAt })])[0] })

// The same cases as Android's TicketSendingRulesTest and TicketSharingRepositoryTest.
describe('received ticket rules', () => {
  const now = Date.parse('2026-11-02T12:00:00Z')

  it('lists upcoming and just-ended shows soonest first and drops old ones', () => {
    const list = [
      ticket('50000000-0000-4000-8000-000000000001', '2026-11-05T19:00:00Z', '2026-11-05T21:00:00Z'),
      ticket('50000000-0000-4000-8000-000000000002', '2026-11-03T19:00:00Z', '2026-11-03T21:00:00Z'),
      ticket('50000000-0000-4000-8000-000000000003', '2026-11-02T08:00:00Z', '2026-11-02T10:00:00Z'),
      ticket('50000000-0000-4000-8000-000000000004', '2026-10-30T19:00:00Z', '2026-10-30T21:00:00Z'),
    ]
    expect(orderedTickets(list, now).map((t) => t.grantId.slice(-1))).toEqual(['3', '2', '1'])
  })

  it('keeps a show for a day after it ends', () => {
    expect(isCurrentTicket({ endsAt: '2026-11-01T12:00:01Z' }, now)).toBe(true)
    expect(isCurrentTicket({ endsAt: '2026-11-01T11:59:59Z' }, now)).toBe(false)
  })

  it('formats parse by name and fall back to OTHER', () => {
    expect(parseTicketFormat('PDF_417')).toBe('PDF_417')
    expect(parseTicketFormat('MAXICODE')).toBe('OTHER')
    expect(parseTicketFormat(undefined)).toBe('OTHER')
  })
})

describe('ticket sending parsing', () => {
  it('reads grants and received tickets, and skips malformed rows', () => {
    expect(parseTicketGrants([grantRow(), { grant_id: 'x' }, null]).map((g) => [g.name, g.openedAt])).toEqual([['Sam Friend', null]])
    expect(parseReceivedTickets([receivedRow(), { grant_id: grant }]).map((t) => t.title)).toEqual(['Film'])
    expect(parseTicketGrants({ not: 'a list' })).toEqual([])
  })

  it('opened tickets tolerate a missing barcode and an unknown format but not a bad file', () => {
    expect(parseOpenedTicket(openedRow())?.barcode).toEqual({ payload: 'CODE-123', format: 'QR_CODE' })
    expect(parseOpenedTicket(openedRow({ barcode: null }))?.barcode).toBeNull()
    expect(parseOpenedTicket(openedRow({ barcode: { payload: 'x', format: 'MAXICODE' } }))?.barcode?.format).toBe('OTHER')
    expect(parseOpenedTicket(openedRow({ mimeType: 'text/html' }))).toBeNull()
    expect(parseOpenedTicket(openedRow({ objectKey: '../other-bucket/x' }))).toBeNull()
    expect(parseOpenedTicket(openedRow({ byteLength: 0 }))).toBeNull()
  })
})

describe('ticket sending requests', () => {
  beforeEach(() => { client.rpc.mockReset(); download.mockReset(); client.storage.from.mockReset(); client.storage.from.mockReturnValue({ download }) })

  it('sends to distinct friends and returns who has it', async () => {
    client.rpc.mockResolvedValueOnce({ data: [grantRow()], error: null })
    expect((await sendTicket(outing, [friend, friend])).map((g) => g.name)).toEqual(['Sam Friend'])
    expect(client.rpc).toHaveBeenCalledWith('send_ticket', { p_outing_id: outing, p_recipient_ids: [friend] })
  })

  it('surfaces a short server refusal', async () => {
    client.rpc.mockResolvedValueOnce({ data: null, error: { message: 'This outing has no ticket to send' } })
    await expect(sendTicket(outing, [friend])).rejects.toThrow('This outing has no ticket to send')
  })

  it('lists, revokes and reads received tickets', async () => {
    client.rpc.mockResolvedValueOnce({ data: [grantRow()], error: null }).mockResolvedValueOnce({ data: null, error: null }).mockResolvedValueOnce({ data: [receivedRow()], error: null })
    expect(await ticketGrants(outing)).toHaveLength(1)
    expect(client.rpc).toHaveBeenLastCalledWith('get_ticket_grants', { p_outing_id: outing })
    await revokeTicketGrant(grant)
    expect(client.rpc).toHaveBeenLastCalledWith('revoke_ticket_grant', { p_grant_id: grant })
    expect((await receivedTickets()).map((t) => t.senderName)).toEqual(['Fran'])
    expect(client.rpc).toHaveBeenLastCalledWith('get_received_tickets')
  })

  it('opens a ticket, then downloads its file from the private bucket and checks the size', async () => {
    client.rpc.mockResolvedValueOnce({ data: openedRow(), error: null })
    const opened = await openReceivedTicket(grant)
    expect(client.rpc).toHaveBeenLastCalledWith('open_received_ticket', { p_grant_id: grant })
    download.mockResolvedValueOnce({ data: new Blob(['1234']), error: null }).mockResolvedValueOnce({ data: new Blob(['123']), error: null })
    expect((await downloadSentTicket(opened)).size).toBe(4)
    expect(client.storage.from).toHaveBeenCalledWith('ticket-attachments')
    expect(download).toHaveBeenCalledWith(`${friend}/aaa/original`)
    await expect(downloadSentTicket(opened)).rejects.toThrow('unexpected size')
    download.mockResolvedValueOnce({ data: null, error: new Error('x') })
    await expect(downloadSentTicket(opened)).rejects.toThrow('Could not download')
  })

  it('rejects empty sends and malformed ids before any request', async () => {
    client.rpc.mockReset()
    await expect(sendTicket(outing, [])).rejects.toThrow('Choose at least one friend.')
    await expect(sendTicket('1&x', [friend])).rejects.toThrow('Choose at least one friend.')
    await expect(sendTicket(outing, ['nope'])).rejects.toThrow('Choose at least one friend.')
    await expect(revokeTicketGrant('nope')).rejects.toThrow('Invalid id')
    await expect(openReceivedTicket('nope')).rejects.toThrow('Invalid id')
    await expect(ticketGrants('nope')).rejects.toThrow('Invalid outing')
    expect(client.rpc).not.toHaveBeenCalled()
    void owner
  })
})
