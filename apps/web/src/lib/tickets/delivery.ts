import type { DeliveryContext, DeliveryResult } from '../offline/coordinator'
import { sameScope, type OfflineScope, type PendingCommand } from '../offline/commands'
import type { OfflineSnapshot } from '../offline/snapshot'
import { assertCommand } from '../offline/validation'
import { classifyLibraryError } from '../offlineRpc'
import { isTicketMutation, type TicketAttachment, type TicketBlobRecord, type TicketMutation } from './types'
import { assertTicketBytes, isTicketAttachment, sameTicketAttachment, ticketObjectKey, ticketTimestampMicros } from './validation'

interface TicketReceipt {
  operationId: string
  outingId: string
  attachment: TicketAttachment | null
  outingUpdatedAt: string
  outingRevisionGuarded?: boolean
  request: { kind: TicketMutation['kind']; outingId: string; attachmentId: string | null; expectedAttachmentId: string | null; expectedUpdatedAt?: string | null; expectedOperationId?: string | null }
  rows: { table: string; key: { id: string }; row: { id: string; updated_at: string } }[]
}
interface TicketDeliveryOptions {
  projectId: string
  anonKey: string
  session: () => Promise<{ userId: string; accessToken: string } | null>
  readBlob: (scope: OfflineScope, attachmentId: string) => Promise<TicketBlobRecord | null>
  /** Must include authoritative managed descriptors, not an old-backend fallback. */
  fetchBase: (context: DeliveryContext) => Promise<OfflineSnapshot>
  fetch?: typeof fetch
}
const object = (value: unknown): value is Record<string, unknown> => value !== null && typeof value === 'object' && !Array.isArray(value)
function assertReceipt(value: unknown, command: PendingCommand, mutation: TicketMutation): asserts value is TicketReceipt {
  const request = object(value) && value.request
  const guarded = !!(mutation.expectedUpdatedAt || mutation.expectedOperationId)
  const keys = ['kind', 'outingId', 'attachmentId', 'expectedAttachmentId', ...(guarded ? ['expectedUpdatedAt', 'expectedOperationId'] : [])]
  const guardMatches = object(request) && (!guarded || (object(value) && value.outingRevisionGuarded === true &&
    request.expectedOperationId === (mutation.expectedOperationId ?? null) &&
    (mutation.expectedUpdatedAt ? ticketTimestampMicros(request.expectedUpdatedAt) === ticketTimestampMicros(mutation.expectedUpdatedAt)
      : request.expectedUpdatedAt === null)))
  if (!object(value) || value.operationId !== command.id || value.outingId !== mutation.outingId ||
      typeof value.outingUpdatedAt !== 'string' || !Number.isFinite(Date.parse(value.outingUpdatedAt)) ||
      !object(request) || !guardMatches || Object.keys(request).length !== keys.length || !keys.every((key) => Object.hasOwn(request, key)) ||
      (!guarded && value.outingRevisionGuarded === true) ||
      request.kind !== mutation.kind || request.outingId !== mutation.outingId || request.expectedAttachmentId !== mutation.expectedAttachmentId ||
      request.attachmentId !== (mutation.kind === 'ticket.attach' ? mutation.attachment.id : null) ||
      (mutation.kind === 'ticket.attach' ? !isTicketAttachment(value.attachment) || !sameTicketAttachment(value.attachment, mutation.attachment) : value.attachment !== null) ||
      !Array.isArray(value.rows) || !value.rows.some((effect: unknown) => object(effect) && effect.table === 'cinema_outings' &&
        object(effect.key) && effect.key.id === mutation.outingId && object(effect.row) && effect.row.id === mutation.outingId && effect.row.updated_at === value.outingUpdatedAt)) {
    throw new Error('Ticket sync returned a receipt for a different or unrecognized operation')
  }
}
class TicketRequestError extends Error {
  status: number
  code: string | undefined
  constructor(status: number, body: unknown) {
    super(object(body) && typeof body.message === 'string' ? body.message : 'Ticket attachment sync failed')
    this.status = status
    this.code = object(body) && typeof body.code === 'string' ? body.code : undefined
  }
}

/** A single journal command owns prepare/upload/finalize. None of these stages
 * changes its persisted JSON. Receipt lookup precedes any dependency on bytes,
 * so an acknowledged-old upload can recover even after replacement/cleanup. */
export function createTicketCommandDelivery(options: TicketDeliveryOptions) {
  const send = options.fetch ?? globalThis.fetch
  return async (command: PendingCommand, context: DeliveryContext): Promise<DeliveryResult> => {
    if (!isTicketMutation(command.mutation)) return { kind: 'failed', message: 'Expected a standalone ticket command' }
    const mutation = command.mutation
    try { assertCommand(command) } catch (error) { return { kind: 'failed', message: error instanceof Error ? error.message : 'Invalid ticket command' } }
    if (!sameScope(command.scope, context.scope) || context.scope.projectId !== options.projectId) return { kind: 'failed', message: 'Ticket command belongs to another account or project' }
    const current = () => context.isCurrent() && !context.signal.aborted
    if (!current()) return { kind: 'auth', message: 'Library account changed' }
    const session = await options.session()
    if (!session || session.userId !== context.scope.userId || !current()) return { kind: 'auth', message: 'Sign in to sync this account’s ticket' }
    const headers = { apikey: options.anonKey, Authorization: `Bearer ${session.accessToken}` }
    const root = options.projectId.replace(/\/$/, '')
    const assertCurrent = () => { if (!current()) throw new Error('Library account changed') }
    const rpc = async (name: string, args: object): Promise<unknown> => {
      assertCurrent()
      const response = await send(`${root}/rest/v1/rpc/${name}`, { method: 'POST', signal: context.signal,
        headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify(args) })
      const body: unknown = await response.json()
      assertCurrent()
      if (!response.ok) throw new TicketRequestError(response.status, body)
      return body
    }
    try {
      const guardArgs = mutation.expectedUpdatedAt || mutation.expectedOperationId
        ? { p_expected_updated_at: mutation.expectedUpdatedAt ?? null, p_expected_operation_id: mutation.expectedOperationId ?? null } : {}
      const receipt = await rpc('get_ticket_command_receipt', { p_operation_id: command.id })
      if (receipt !== null) assertReceipt(receipt, command, mutation)
      else if (mutation.kind === 'ticket.detach') {
        assertReceipt(await rpc('detach_ticket_attachment', { p_operation_id: command.id, p_outing_id: mutation.outingId,
          p_expected_attachment_id: mutation.expectedAttachmentId, ...guardArgs }), command, mutation)
      } else {
        const attachment = mutation.attachment
        if (attachment.objectKey !== ticketObjectKey(context.scope, attachment.id)) return { kind: 'failed', message: 'Ticket object belongs to another owner' }
        const prepared = await rpc('prepare_ticket_attachment', { p_attachment_id: attachment.id, p_outing_id: mutation.outingId,
          p_metadata: { mimeType: attachment.mimeType, byteLength: attachment.byteLength, sha256: attachment.sha256, barcode: attachment.barcode } })
        if (!object(prepared) || !isTicketAttachment(prepared.attachment) || !sameTicketAttachment(prepared.attachment, attachment) || !['prepared', 'attached', 'retired'].includes(prepared.state as string)) throw new Error('Ticket preparation returned unexpected metadata')
        if (prepared.state === 'retired') {
          const concurrentReceipt = await rpc('get_ticket_command_receipt', { p_operation_id: command.id })
          if (concurrentReceipt === null) return { kind: 'conflict', message: 'This ticket upload was retired. Your photo remains saved for recovery.' }
          assertReceipt(concurrentReceipt, command, mutation)
        } else {
          if (prepared.state === 'prepared') {
            const record = await options.readBlob(context.scope, attachment.id)
            assertCurrent()
            if (!record || !sameScope(record.scope, context.scope) || record.outingId !== mutation.outingId || !isTicketAttachment(record.attachment) || !sameTicketAttachment(record.attachment, attachment)) return { kind: 'failed', message: 'The original ticket photo is missing or belongs to another capture. Recover the saved photo before retrying.' }
            try { await assertTicketBytes(record.blob, attachment) } catch (error) { return { kind: 'failed', message: error instanceof Error ? error.message : 'The ticket photo is damaged' } }
            assertCurrent()
            const upload = await send(`${root}/storage/v1/object/ticket-attachments/${attachment.objectKey}`, { method: 'POST', signal: context.signal,
              headers: { ...headers, 'Content-Type': attachment.mimeType, 'x-upsert': 'false' }, body: record.blob })
            assertCurrent()
            if (!upload.ok) {
              const body: unknown = await upload.json()
              const duplicate = upload.status === 409 || object(body) && (body.error === 'Duplicate' || body.code === 'Duplicate' || body.message === 'The resource already exists')
              if (!duplicate) throw new TicketRequestError(upload.status, body)
              assertCurrent()
              const existing = await send(`${root}/storage/v1/object/authenticated/ticket-attachments/${attachment.objectKey}`, { headers, signal: context.signal })
              assertCurrent()
              if (!existing.ok) throw new TicketRequestError(existing.status, await existing.json())
              await assertTicketBytes(await existing.blob(), attachment)
              assertCurrent()
            }
          }
          assertReceipt(await rpc('finalize_ticket_attachment', { p_operation_id: command.id, p_outing_id: mutation.outingId,
            p_attachment_id: attachment.id, p_expected_attachment_id: mutation.expectedAttachmentId, ...guardArgs }), command, mutation)
        }
      }
      assertCurrent()
      const canonicalBase = await options.fetchBase(context)
      assertCurrent()
      if (canonicalBase.ticketAttachmentSupport !== 'authoritative') throw new Error('Ticket sync requires an authoritative attachment refresh before acknowledgment')
      return { kind: 'success', canonicalBase }
    } catch (error) {
      if (!current()) return { kind: 'auth', message: 'Library account changed during ticket sync' }
      if (error instanceof TicketRequestError) return classifyLibraryError(error.status, error.code, error.message)
      return { kind: 'retry', message: error instanceof Error ? error.message : 'Ticket sync was not confirmed' }
    }
  }
}
