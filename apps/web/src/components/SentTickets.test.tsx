import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import * as ticketSending from 'src/lib/ticketSending'
import { listFriendships, type FriendshipView } from 'src/lib/auth'
import { ReceivedTickets, SendTicketPanel } from './SentTickets'

vi.mock('src/lib/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/auth')>()),
  isSupabaseConfigured: true,
  listFriendships: vi.fn(),
}))
vi.mock('src/lib/ticketSending', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/ticketSending')>()),
  ticketGrants: vi.fn(),
  sendTicket: vi.fn(),
  revokeTicketGrant: vi.fn(),
  receivedTickets: vi.fn(),
  openReceivedTicket: vi.fn(),
  downloadSentTicket: vi.fn(),
}))
vi.mock('src/lib/tickets/barcode', () => ({ renderTicketBarcode: vi.fn().mockResolvedValue(null) }))

const owner = '10000000-0000-4000-8000-000000000001'
const friendId = '20000000-0000-4000-8000-000000000001'
const grantId = '30000000-0000-4000-8000-000000000001'
const outingId = '40000000-0000-4000-8000-000000000001'
const friend = { friend_user_id: friendId, display_name: 'Sam Friend', username: 'sam', status: 'accepted' } as FriendshipView
const grant = (openedAt: string | null = null): ticketSending.TicketGrant => ({ grantId, userId: friendId, name: 'Sam Friend', sentAt: '2026-11-01T10:00:00Z', openedAt })
const received = (overrides: Partial<ticketSending.ReceivedTicket> = {}): ticketSending.ReceivedTicket => ({
  grantId, senderId: friendId, senderName: 'Fran Friend', title: 'Film', year: 2026, posterUrl: null,
  showtime: '2099-11-02T19:00:00Z', endsAt: '2099-11-02T21:00:00Z', venue: 'AMC', sentAt: '2026-11-01T10:00:00Z', openedAt: null, ...overrides,
})

const initialState = useAppStore.getState()
beforeEach(() => {
  useAppStore.setState({ user: { id: owner, email: 'a@b.test' } as never })
  vi.mocked(listFriendships).mockResolvedValue([friend])
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:ticket'), revokeObjectURL: vi.fn() }))
})
afterEach(() => {
  cleanup()
  useAppStore.setState(initialState, true)
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

it('sends the ticket to a friend and shows whether it was opened', async () => {
  vi.mocked(ticketSending.ticketGrants).mockResolvedValue([])
  vi.mocked(ticketSending.sendTicket).mockResolvedValue([grant()])
  render(<SendTicketPanel outingId={outingId} />)
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Send the ticket to Sam Friend' }))
  expect(ticketSending.sendTicket).toHaveBeenCalledWith(outingId, [friendId])
  expect(await screen.findByText('Sent · not opened yet')).toBeInTheDocument()
})

it('shows an opened ticket and takes it back', async () => {
  vi.mocked(ticketSending.ticketGrants).mockResolvedValueOnce([grant('2026-11-01T11:00:00Z')]).mockResolvedValueOnce([])
  vi.mocked(ticketSending.revokeTicketGrant).mockResolvedValue()
  render(<SendTicketPanel outingId={outingId} />)
  expect(await screen.findByText('Sent · opened')).toBeInTheDocument()
  await userEvent.setup().click(screen.getByRole('button', { name: 'Take the ticket back from Sam Friend' }))
  expect(ticketSending.revokeTicketGrant).toHaveBeenCalledWith(grantId)
  expect(await screen.findByRole('button', { name: 'Send the ticket to Sam Friend' })).toBeInTheDocument()
})

it('shows a short refusal from the server', async () => {
  vi.mocked(ticketSending.ticketGrants).mockResolvedValue([])
  vi.mocked(ticketSending.sendTicket).mockRejectedValue(new Error('This outing has no ticket to send'))
  render(<SendTicketPanel outingId={outingId} />)
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Send the ticket to Sam Friend' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('This outing has no ticket to send')
})

it('lists a ticket a friend sent and opens it into the photo', async () => {
  vi.mocked(ticketSending.receivedTickets).mockResolvedValue([received()])
  vi.mocked(ticketSending.openReceivedTicket).mockResolvedValue({
    objectKey: `${friendId}/aaa/original`, mimeType: 'image/png', byteLength: 4, barcode: { payload: 'CODE', format: 'QR_CODE' },
    title: 'Film', showtime: '2099-11-02T19:00:00Z', venue: 'AMC',
  })
  vi.mocked(ticketSending.downloadSentTicket).mockResolvedValue(new Blob(['1234']))
  render(<ReceivedTickets />)
  expect(await screen.findByText('Fran Friend sent you a ticket')).toBeInTheDocument()
  await userEvent.setup().click(screen.getByRole('button', { name: 'Open the ticket to Film' }))
  expect(await screen.findByRole('img', { name: 'Ticket photo' })).toBeInTheDocument()
  expect(ticketSending.openReceivedTicket).toHaveBeenCalledWith(grantId)
})

it('shows why a ticket could not be opened', async () => {
  vi.mocked(ticketSending.receivedTickets).mockResolvedValue([received()])
  vi.mocked(ticketSending.openReceivedTicket).mockRejectedValue(new Error('This ticket is no longer available.'))
  render(<ReceivedTickets />)
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Open the ticket to Film' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('no longer available')
})

it('the received list is empty without an account or when nothing was sent', async () => {
  vi.mocked(ticketSending.receivedTickets).mockResolvedValue([])
  const { container } = render(<ReceivedTickets />)
  await vi.waitFor(() => expect(ticketSending.receivedTickets).toHaveBeenCalled())
  expect(container).toBeEmptyDOMElement()
  cleanup()
  useAppStore.setState({ user: null })
  const second = render(<ReceivedTickets />)
  expect(second.container).toBeEmptyDOMElement()
  const panel = render(<SendTicketPanel outingId={outingId} />)
  expect(panel.container).toBeEmptyDOMElement()
})
