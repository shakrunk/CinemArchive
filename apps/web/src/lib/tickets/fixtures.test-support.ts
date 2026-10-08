import { createCommand } from '../offline/commands'
import { outing, snapshot } from '../offline/fixtures.test-support'
import type { TicketAttachment, TicketBlobRecord } from './types'
import { ticketDigest, ticketObjectKey } from './validation'

export const ticketOwner = { projectId: 'https://example.supabase.co', userId: '10000000-0000-4000-8000-000000000001' }
export const ticketOuting = { ...outing, id: '20000000-0000-4000-8000-000000000001', ticketImagePath: '/private/legacy.jpg' }
export const ticketSnapshot = () => ({ ...snapshot(), outings: [ticketOuting] })
export const ticketId = '30000000-0000-4000-8000-000000000001'
export const operationId = '40000000-0000-4000-8000-000000000001'
export async function ticketFixture(id = ticketId) {
  const blob = new Blob([Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j1ioAAAAASUVORK5CYII='), (character) => character.charCodeAt(0))], { type: 'image/png' })
  const attachment: TicketAttachment = { id, objectKey: ticketObjectKey(ticketOwner, id), mimeType: 'image/png', byteLength: blob.size, sha256: await ticketDigest(blob), barcode: { format: 'QR_CODE', payload: 'real-ticket-code' } }
  const command = createCommand(ticketOwner, { kind: 'ticket.attach', outingId: ticketOuting.id, expectedAttachmentId: null, attachment }, { id: operationId })
  const record: TicketBlobRecord = { scope: ticketOwner, outingId: ticketOuting.id, attachmentId: id, attachment, blob, sha256: attachment.sha256,
    mimeType: attachment.mimeType, byteLength: blob.size, createdAt: command.createdAt, source: 'capture' }
  const receipt = { operationId, outingId: ticketOuting.id, attachment, outingUpdatedAt: '2026-10-08T20:00:00Z',
    request: { kind: 'ticket.attach', outingId: ticketOuting.id, attachmentId: id, expectedAttachmentId: null },
    rows: [{ table: 'cinema_outings', key: { id: ticketOuting.id }, row: { id: ticketOuting.id, updated_at: '2026-10-08T20:00:00Z' } }] }
  return { blob, attachment, command, record, receipt }
}
