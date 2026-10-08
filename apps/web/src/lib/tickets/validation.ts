import type { OfflineScope } from '../offline/commands'
import { TICKET_FORMATS, TICKET_MAX_BYTES, type TicketAttachment, type TicketMutation } from './types'

export const isTicketId = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const plain = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' &&
  (Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null)
const exact = (value: Record<string, unknown>, keys: readonly string[]) => Object.keys(value).length === keys.length && keys.every((key) => Object.hasOwn(value, key))

/** PostgreSQL receipt JSON normalizes offsets and fractional widths. */
export function ticketTimestampMicros(value: unknown): bigint | null {
  if (typeof value !== 'string') return null
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?(Z|[+-]\d{2}:\d{2})$/.exec(value)
  if (!match) return null
  const seconds = Date.parse(`${match[1]}${match[3]}`)
  return Number.isFinite(seconds) ? BigInt(seconds) * 1000n + BigInt((match[2] ?? '').padEnd(6, '0')) : null
}

export function ticketObjectKey(scope: OfflineScope, attachmentId: string): string {
  if ((!isTicketId(scope.userId) && scope.userId !== 'anonymous-local-only') || !isTicketId(attachmentId)) throw new Error('Invalid ticket owner or attachment ID')
  return `${scope.userId}/${attachmentId}/original`
}
export function isTicketAttachment(value: unknown): value is TicketAttachment {
  if (!plain(value) || !exact(value, ['id', 'objectKey', 'mimeType', 'byteLength', 'sha256', 'barcode'])) return false
  if (!isTicketId(value.id) || typeof value.objectKey !== 'string' ||
      !/^(?:[0-9a-f-]{36}|anonymous-local-only)\/[0-9a-f-]{36}\/original$/.test(value.objectKey) ||
      !value.objectKey.endsWith(`/${value.id}/original`) ||
      !['image/jpeg', 'image/png', 'image/webp'].includes(value.mimeType as string) ||
      !Number.isSafeInteger(value.byteLength) || (value.byteLength as number) <= 0 || (value.byteLength as number) > TICKET_MAX_BYTES ||
      typeof value.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(value.sha256)) return false
  const barcode = value.barcode
  return barcode === null || (plain(barcode) && exact(barcode, ['payload', 'format']) && typeof barcode.payload === 'string' &&
    barcode.payload.length > 0 && new TextEncoder().encode(barcode.payload).length <= 32768 && TICKET_FORMATS.includes(barcode.format as typeof TICKET_FORMATS[number]))
}
export function sameTicketAttachment(a: TicketAttachment, b: TicketAttachment): boolean {
  return a.id === b.id && a.objectKey === b.objectKey && a.mimeType === b.mimeType && a.byteLength === b.byteLength && a.sha256 === b.sha256 &&
    (a.barcode === null ? b.barcode === null : b.barcode !== null && a.barcode.payload === b.barcode.payload && a.barcode.format === b.barcode.format)
}
export function isValidTicketMutation(value: unknown): value is TicketMutation {
  if (!plain(value) || !isTicketId(value.outingId) || (value.expectedAttachmentId !== null && !isTicketId(value.expectedAttachmentId))) return false
  const guardKeys = ['expectedUpdatedAt', 'expectedOperationId'].filter((key) => Object.hasOwn(value, key))
  if (guardKeys.length > 1 || (guardKeys[0] === 'expectedUpdatedAt' &&
      ticketTimestampMicros(value.expectedUpdatedAt) === null) ||
      (guardKeys[0] === 'expectedOperationId' && !isTicketId(value.expectedOperationId))) return false
  return value.kind === 'ticket.attach'
    ? exact(value, ['kind', 'outingId', 'expectedAttachmentId', 'attachment', ...guardKeys]) && isTicketAttachment(value.attachment)
    : value.kind === 'ticket.detach' && exact(value, ['kind', 'outingId', 'expectedAttachmentId', ...guardKeys])
}

export async function ticketDigest(blob: Blob): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer()))].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}
/** Validate the original before durable admission and again before upload. */
export async function assertTicketBytes(blob: Blob, attachment: TicketAttachment): Promise<void> {
  if (!isTicketAttachment(attachment) || !blob || typeof blob.arrayBuffer !== 'function' || blob.size !== attachment.byteLength || blob.type !== attachment.mimeType) throw new Error('Ticket photo metadata does not match its bytes')
  const head = new Uint8Array(await blob.slice(0, 12).arrayBuffer())
  const mime = attachment.mimeType
  const signature = mime === 'image/jpeg' ? head[0] === 255 && head[1] === 216 && head[2] === 255
    : mime === 'image/png' ? [137, 80, 78, 71, 13, 10, 26, 10].every((byte, index) => head[index] === byte)
      : String.fromCharCode(...head.slice(0, 4)) === 'RIFF' && String.fromCharCode(...head.slice(8, 12)) === 'WEBP'
  if (!signature || await ticketDigest(blob) !== attachment.sha256) throw new Error('Ticket photo is damaged or its content does not match the saved digest')
}
