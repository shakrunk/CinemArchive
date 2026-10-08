import type { OfflineScope } from '../offline/commands'

export const TICKET_MAX_BYTES = 20 * 1024 * 1024
export const TICKET_FORMATS = ['QR_CODE', 'CODE_128', 'PDF_417', 'AZTEC', 'ITF', 'CODABAR', 'OTHER'] as const
export type TicketBarcode = { payload: string; format: typeof TICKET_FORMATS[number] }
export interface TicketAttachment {
  id: string
  objectKey: string
  mimeType: 'image/jpeg' | 'image/png' | 'image/webp'
  byteLength: number
  sha256: string
  barcode: TicketBarcode | null
}
export type TicketCapture = Omit<TicketAttachment, 'objectKey'>
export type TicketMutation =
  | { kind: 'ticket.attach'; outingId: string; expectedAttachmentId: string | null; attachment: TicketAttachment }
  | { kind: 'ticket.detach'; outingId: string; expectedAttachmentId: string | null }
export interface TicketBlobRecord {
  scope: OfflineScope
  outingId: string
  attachmentId: string
  attachment: TicketAttachment
  blob: Blob
  sha256: string
  mimeType: TicketAttachment['mimeType']
  byteLength: number
  createdAt: string
  source: 'capture' | 'download'
}
export const TICKET_OUTING_FIELDS = ['ticketAttachment', 'ticketManaged', 'ticketImagePath', 'ticketBarcodePayload', 'ticketBarcodeFormat'] as const
export type TicketOutingField = typeof TICKET_OUTING_FIELDS[number]
export function isTicketMutation(value: { kind: string }): value is TicketMutation {
  return value.kind === 'ticket.attach' || value.kind === 'ticket.detach'
}
