// @vitest-environment node
import { expect, it, vi } from 'vitest'
import { createTicketRemote, mergeOwnedTickets } from './remote'
import { ticketFixture, ticketOuting, ticketOwner, ticketSnapshot } from './fixtures.test-support'
import type { DeliveryContext } from '../offline/coordinator'
import { mapDbOutingToLocal } from '../db'

const context = (): DeliveryContext => ({ scope: ticketOwner, signal: new AbortController().signal, isCurrent: () => true })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })
function remote(fetch = vi.fn<typeof globalThis.fetch>()) {
  const session = vi.fn().mockResolvedValue({ userId: ticketOwner.userId, accessToken: 'captured-owner-token' })
  return { fetch, session, api: createTicketRemote({ projectId: ticketOwner.projectId, anonKey: 'public-key', fetch, session }) }
}

it('reads strict owned descriptors with captured authorization and explicit detach state', async () => {
  const { attachment } = await ticketFixture(), { api, fetch } = remote()
  fetch.mockResolvedValueOnce(response([{ outingId: ticketOuting.id, managed: true, attachment }]))
  const result = await api.descriptors(context())
  expect(fetch.mock.calls[0][1]).toMatchObject({ method: 'POST', headers: { Authorization: 'Bearer captured-owner-token' } })
  expect(mergeOwnedTickets(ticketSnapshot(), result).outings[0]).toMatchObject({ ticketManaged: true, ticketAttachment: attachment })
  const cleared = mergeOwnedTickets(ticketSnapshot(), { support: 'authoritative', outings: [{ outingId: ticketOuting.id, managed: true, attachment: null }] })
  expect(cleared.outings[0].ticketManaged).toBe(true)
  expect(cleared.outings[0].ticketAttachment).toBeUndefined()
  expect(cleared.outings[0].ticketImagePath).toBe('/private/legacy.jpg')
})

it('treats only a missing endpoint as unsupported, never authentication or transient failures', async () => {
  const { api, fetch } = remote()
  fetch.mockResolvedValueOnce(response({ code: 'PGRST202' }, 404))
  expect(await api.descriptors(context())).toEqual({ support: 'unsupported', outings: [] })
  for (const status of [401, 403, 500]) {
    fetch.mockResolvedValueOnce(response({ code: 'PGRST202', message: 'Unavailable' }, status))
    await expect(api.descriptors(context())).rejects.toThrow('Unavailable')
  }
  fetch.mockRejectedValueOnce(new Error('Offline'))
  await expect(api.descriptors(context())).rejects.toThrow('Offline')
})

it('rejects cross-owner, malformed, duplicate and inconsistent descriptor projections', async () => {
  const { attachment } = await ticketFixture(), { api, fetch } = remote()
  const row = { outingId: ticketOuting.id, managed: true, attachment }
  for (const data of [[{ ...row, attachment: { ...attachment, objectKey: attachment.objectKey.replace(ticketOwner.userId, '50000000-0000-4000-8000-000000000001') } }], [row, row], [{ ...row, managed: false }], [{ ...row, extra: true }]]) {
    fetch.mockResolvedValueOnce(response(data))
    await expect(api.descriptors(context())).rejects.toThrow('invalid owner projection')
  }
  expect(() => mergeOwnedTickets({ ...ticketSnapshot(), outings: [] }, { support: 'authoritative', outings: [row as { outingId: string; managed: true; attachment: typeof attachment }] })).toThrow('changed while loading')
  expect(() => mergeOwnedTickets({ ...ticketSnapshot(), outings: [{ ...ticketOuting, ticketManaged: true }] }, { support: 'authoritative', outings: [] })).toThrow('changed while loading')
})

it('downloads privately without HTTP cache, validates actual bytes, and rejects owner changes', async () => {
  const { attachment, blob } = await ticketFixture(), { api, fetch } = remote()
  fetch.mockResolvedValueOnce(new Response(blob, { headers: { 'Content-Type': blob.type } }))
  expect(await (await api.download(context(), attachment)).arrayBuffer()).toEqual(await blob.arrayBuffer())
  expect(fetch.mock.calls[0][1]).toMatchObject({ cache: 'no-store', headers: { Authorization: 'Bearer captured-owner-token' } })
  fetch.mockResolvedValueOnce(new Response(new Blob(['damaged'], { type: blob.type })))
  await expect(api.download(context(), attachment)).rejects.toThrow('metadata')
  let current = true
  fetch.mockImplementationOnce(async () => { current = false; return new Response(blob) })
  await expect(api.download({ ...context(), isCurrent: () => current }, attachment)).rejects.toThrow('account changed')
})

it('rejects a different authenticated session before private network access', async () => {
  const { api, fetch, session } = remote()
  session.mockResolvedValueOnce({ userId: 'another-owner', accessToken: 'wrong' })
  await expect(api.descriptors(context())).rejects.toThrow('Sign in')
  expect(fetch).not.toHaveBeenCalled()
})

it('preserves legacy ticket fields only as recovery data on managed database rows', () => {
  expect(mapDbOutingToLocal({ id: ticketOuting.id, ticket_attachment_managed: true, ticket_image_path: '/native/private.jpg', ticket_barcode_payload: 'actual-code', ticket_barcode_format: 'CODE_128' })).toMatchObject({
    ticketManaged: true, ticketImagePath: '/native/private.jpg', ticketBarcodePayload: 'actual-code', ticketBarcodeFormat: 'CODE_128',
  })
})
