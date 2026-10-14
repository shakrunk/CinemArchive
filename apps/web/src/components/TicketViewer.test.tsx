import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { TicketButton } from './TicketViewer'
import { useAppStore } from '../store/useAppStore'
import { ticketOuting, ticketOwner, ticketId } from '../lib/tickets/fixtures.test-support'
import { title, deferred } from '../lib/offline/fixtures.test-support'
import type { TicketAttachment } from '../lib/tickets/types'
import { prepareTicketCapture } from '../lib/tickets/barcode'

vi.mock('../lib/tickets/barcode', () => ({ prepareTicketCapture: vi.fn(), renderTicketBarcode: vi.fn().mockResolvedValue(null) }))
const initial = useAppStore.getState()
const attachment: TicketAttachment = { id: ticketId, objectKey: `${ticketOwner.userId}/${ticketId}/original`, mimeType: 'image/png', byteLength: 1, sha256: 'a'.repeat(64), barcode: null }
const outing = { ...ticketOuting, titleId: title.id, ticketManaged: true, ticketAttachment: attachment, auditorium: '7', seatRow: 'F', seats: ['12', '13'], bookingRef: 'REFERENCE-NOT-A-CODE' }
const attach = vi.fn(), detach = vi.fn(), read = vi.fn()
beforeEach(() => {
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:private-ticket'), revokeObjectURL: vi.fn() }))
  attach.mockReset(); detach.mockReset(); read.mockReset().mockResolvedValue(new Blob(['photo']))
  vi.mocked(prepareTicketCapture).mockResolvedValue({ ...attachment, id: '30000000-0000-4000-8000-000000000002' })
  useAppStore.setState({ titles: [title], outings: [outing], librarySession: 100, isSharedView: false, viewerContext: { kind: 'owner' },
    attachOutingTicket: attach, detachOutingTicket: detach, readOutingTicket: read })
})
afterEach(() => { cleanup(); useAppStore.setState(initial, true); vi.unstubAllGlobals() })
async function open() { render(<TicketButton outingId={outing.id} />); fireEvent.click(screen.getByRole('button', { name: 'Ticket & seats' })); await screen.findByAltText('Original ticket photo') }

it('shows the original photo and structured seats without inventing a barcode for a booking reference', async () => {
  await open()
  expect(screen.queryByRole('button', { name: 'Ticket barcode' })).toBeNull()
  expect(screen.getByText('Booking reference: REFERENCE-NOT-A-CODE')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Seats' }))
  expect(screen.getByText('Theatre 7 · Row F · Seats 12, 13')).toBeTruthy()
  expect(screen.queryByAltText('Original ticket photo')).toBeNull()
})

it('retains the current photo and retry capture when durable replacement fails', async () => {
  await open()
  attach.mockRejectedValueOnce(new Error('Storage quota exceeded')).mockResolvedValueOnce(undefined)
  fireEvent.change(screen.getByLabelText('Replace ticket photo'), { target: { files: [new File(['new'], 'ticket.png', { type: 'image/png' })] } })
  await screen.findByText('Storage quota exceeded')
  expect(screen.getByAltText('Original ticket photo')).toBeTruthy()
  expect(useAppStore.getState().outings[0].ticketAttachment).toEqual(attachment)
  fireEvent.click(screen.getByRole('button', { name: 'Retry saving photo' }))
  await waitFor(() => expect(attach).toHaveBeenCalledTimes(2))
  expect(attach.mock.calls[0][1]).toEqual(attach.mock.calls[1][1])
  expect(prepareTicketCapture).toHaveBeenCalledTimes(1)
})

it('awaits durable removal and leaves the current photo available when removal fails', async () => {
  await open()
  const pending = deferred<void>()
  detach.mockReturnValueOnce(pending.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Remove photo' }))
  fireEvent.click(screen.getByRole('button', { name: 'Confirm removal' }))
  expect(screen.getByAltText('Original ticket photo')).toBeTruthy()
  pending.reject(new Error('Could not save removal'))
  await screen.findByText('Could not save removal')
  expect(screen.getByRole('button', { name: 'Confirm removal' })).toBeTruthy()
})

it('closes private content and revokes its blob URL immediately on an account-session change', async () => {
  await open()
  useAppStore.setState({ librarySession: 101 })
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:private-ticket')
})

it('suppresses a late capture after an account switch and never invokes its durable writer', async () => {
  await open()
  const pending = deferred<Awaited<ReturnType<typeof prepareTicketCapture>>>()
  vi.mocked(prepareTicketCapture).mockReturnValueOnce(pending.promise)
  fireEvent.change(screen.getByLabelText('Replace ticket photo'), { target: { files: [new File(['new'], 'ticket.png', { type: 'image/png' })] } })
  await waitFor(() => expect(prepareTicketCapture).toHaveBeenCalled())
  useAppStore.setState({ librarySession: 101 })
  pending.resolve(attachment)
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(attach).not.toHaveBeenCalled()
})

it('labels device-local legacy data separately and hides it as the active ticket after managed removal', async () => {
  useAppStore.setState({ outings: [{ ...ticketOuting, titleId: title.id }] })
  render(<TicketButton outingId={outing.id} />)
  fireEvent.click(screen.getByRole('button', { name: 'Ticket & seats' }))
  expect(screen.getByText(/stored on your original device/)).toBeTruthy()
  useAppStore.setState({ outings: [{ ...ticketOuting, titleId: title.id, ticketManaged: true }] })
  await screen.findByText(/Older device-local ticket data is retained/)
  expect(screen.queryByText(/stored on your original device/)).toBeNull()
  expect(screen.queryByRole('img')).toBeNull()
})
