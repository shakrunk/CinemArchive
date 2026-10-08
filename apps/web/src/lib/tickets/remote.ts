import { supabase } from '../auth'
import type { DeliveryContext } from '../offline/coordinator'
import type { OfflineSnapshot } from '../offline/snapshot'
import type { TicketAttachment } from './types'
import { assertTicketBytes, isTicketAttachment, isTicketId, ticketObjectKey } from './validation'

export interface OwnedTicketDescriptors {
  support: 'authoritative' | 'unsupported'
  outings: { outingId: string; managed: true; attachment: TicketAttachment | null }[]
}
export interface TicketRemoteOptions {
  projectId: string
  anonKey: string
  session: () => Promise<{ userId: string; accessToken: string } | null>
  fetch?: typeof fetch
}
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)

export function createTicketRemote(options: TicketRemoteOptions) {
  async function connection(context: DeliveryContext) {
    const check = () => {
      if (!context.isCurrent() || context.signal.aborted || context.scope.projectId !== options.projectId) throw new Error('Library account changed')
    }
    check()
    const session = await options.session()
    check()
    if (!session || session.userId !== context.scope.userId) throw new Error('Sign in to read this account’s tickets')
    return { check, headers: { apikey: options.anonKey, Authorization: `Bearer ${session.accessToken}` },
      root: options.projectId.replace(/\/$/, ''), send: options.fetch ?? globalThis.fetch }
  }
  return {
    async descriptors(context: DeliveryContext): Promise<OwnedTicketDescriptors> {
      const { check, headers, root, send } = await connection(context)
      const response = await send(`${root}/rest/v1/rpc/get_outing_ticket_attachments`, {
        method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: '{}', signal: context.signal,
      })
      const data: unknown = await response.json()
      check()
      if (!response.ok) {
        if ((response.status === 404 || response.status === 400) && object(data) &&
            (data.code === 'PGRST202' || data.code === '42883')) return { support: 'unsupported', outings: [] }
        throw new Error(object(data) && typeof data.message === 'string' ? data.message : 'Could not read ticket attachments')
      }
      const ids = new Set<string>()
      if (!Array.isArray(data) || !data.every((row: unknown) => {
        if (!object(row) || Object.keys(row).length !== 3 || !isTicketId(row.outingId) || ids.has(row.outingId) || row.managed !== true ||
            (row.attachment !== null && (!isTicketAttachment(row.attachment) || row.attachment.objectKey !== ticketObjectKey(context.scope, row.attachment.id)))) return false
        ids.add(row.outingId)
        return true
      })) throw new Error('Ticket attachments returned an invalid owner projection')
      return { support: 'authoritative', outings: data as OwnedTicketDescriptors['outings'] }
    },
    async download(context: DeliveryContext, attachment: TicketAttachment): Promise<Blob> {
      if (!isTicketAttachment(attachment) || attachment.objectKey !== ticketObjectKey(context.scope, attachment.id)) throw new Error('Ticket belongs to another account')
      const { check, headers, root, send } = await connection(context)
      const response = await send(`${root}/storage/v1/object/authenticated/ticket-attachments/${attachment.objectKey}`, { headers, signal: context.signal, cache: 'no-store' })
      check()
      if (!response.ok) throw new Error(response.status === 404 ? 'The original ticket photo is unavailable on the server' : 'Could not download the private ticket photo')
      const blob = await response.blob()
      await assertTicketBytes(blob, attachment)
      check()
      return blob
    },
  }
}

export function mergeOwnedTickets(snapshot: OfflineSnapshot, result: OwnedTicketDescriptors): OfflineSnapshot {
  if (result.support === 'unsupported') return { ...snapshot, ticketAttachmentSupport: 'unsupported' }
  const rows = new Map(result.outings.map((row) => [row.outingId, row]))
  const known = new Set(snapshot.outings.map((outing) => outing.id))
  if (result.outings.some((row) => !known.has(row.outingId)) || snapshot.outings.some((outing) => outing.ticketManaged && !rows.has(outing.id))) {
    throw new Error('Ticket associations changed while loading the library; retry the refresh')
  }
  return { ...snapshot, ticketAttachmentSupport: 'authoritative', outings: snapshot.outings.map((outing) => {
    const row = rows.get(outing.id)
    const rest = { ...outing }
    delete rest.ticketAttachment
    delete rest.ticketManaged
    return row ? { ...rest, ticketManaged: true, ...(row.attachment ? { ticketAttachment: row.attachment } : {}) } : rest
  }) }
}

export const ticketRemoteOptions: TicketRemoteOptions = {
  projectId: import.meta.env.VITE_SUPABASE_URL || 'unconfigured-local',
  anonKey: import.meta.env.VITE_SUPABASE_ANON_KEY || '',
  session: async () => {
    if (!supabase) return null
    const { data, error } = await supabase.auth.getSession()
    return error || !data.session ? null : { userId: data.session.user.id, accessToken: data.session.access_token }
  },
}
export const ticketRemote = createTicketRemote(ticketRemoteOptions)
