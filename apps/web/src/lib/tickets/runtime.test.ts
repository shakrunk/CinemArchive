// @vitest-environment node
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, expect, it, vi } from 'vitest'
import { OfflineLibraryRuntime } from '../../store/offlineLibrary'
import { IndexedDbOfflineStore } from '../offline/storage'
import { deferred } from '../offline/fixtures.test-support'
import { ticketFixture, ticketOwner, ticketOuting, ticketSnapshot } from './fixtures.test-support'
import type { OfflineSnapshot } from '../offline/snapshot'
import type { DeliveryContext } from '../offline/coordinator'
import type { TicketAttachment } from './types'

const cleanups: (() => Promise<void>)[] = []
afterEach(async () => { await Promise.all(cleanups.splice(0).map((cleanup) => cleanup())) })
async function setup() {
  const factory = new IDBFactory(), fixture = await ticketFixture()
  const ownerStorage = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'ticket-runtime-owner' })
  const anonymousStorage = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'ticket-runtime-anonymous' })
  const downloadTicket = vi.fn<(context: DeliveryContext, attachment: TicketAttachment) => Promise<Blob>>().mockResolvedValue(fixture.blob)
  const onSnapshot = vi.fn<(snapshot: OfflineSnapshot | null) => void>()
  const runtime = new OfflineLibraryRuntime({ projectId: ticketOwner.projectId, ownerStorage, anonymousStorage, downloadTicket,
    deliver: vi.fn(), onSnapshot, onError: vi.fn(), onStatus: vi.fn(), browserEvents: false, isAuthenticated: async () => true,
    lock: async (_name, work) => work() })
  await ownerStorage.replaceBase(ticketOwner, { ...ticketSnapshot(), ticketAttachmentSupport: 'authoritative', outings: [{ ...ticketOuting, ticketManaged: true, ticketAttachment: fixture.attachment }] })
  await runtime.activate(ticketOwner.userId)
  cleanups.push(async () => { runtime.deactivate(); await ownerStorage.close(); await anonymousStorage.close() })
  return { ...fixture, ownerStorage, anonymousStorage, runtime, downloadTicket, onSnapshot }
}

it('downloads once, verifies and saves original bytes for subsequent offline opens', async () => {
  const s = await setup()
  await s.runtime.readTicketPhoto(ticketOuting.id, s.attachment.id)
  s.downloadTicket.mockRejectedValue(new Error('Offline'))
  const second = await s.runtime.readTicketPhoto(ticketOuting.id, s.attachment.id)
  expect(await second.arrayBuffer()).toEqual(await s.blob.arrayBuffer())
  expect(s.downloadTicket).toHaveBeenCalledOnce()
  expect((await s.ownerStorage.readTicketBlob(ticketOwner, s.attachment.id))?.source).toBe('download')
})

it('aborts and suppresses a late private download after switching account', async () => {
  const s = await setup(), pending = deferred<Blob>()
  s.downloadTicket.mockReturnValueOnce(pending.promise)
  const read = s.runtime.readTicketPhoto(ticketOuting.id, s.attachment.id)
  await vi.waitFor(() => expect(s.downloadTicket).toHaveBeenCalledOnce())
  const context = s.downloadTicket.mock.calls[0][0]
  await s.runtime.activate('50000000-0000-4000-8000-000000000001')
  expect(context.signal.aborted).toBe(true)
  pending.resolve(s.blob)
  await expect(read).rejects.toThrow('account changed')
  expect(await s.ownerStorage.readTicketBlob(ticketOwner, s.attachment.id)).toBeNull()
  expect(s.onSnapshot.mock.lastCall![0]?.outings).toEqual([])
})

it('does not return or cache a download after the outing ticket is detached', async () => {
  const s = await setup(), pending = deferred<Blob>()
  s.downloadTicket.mockReturnValueOnce(pending.promise)
  const read = s.runtime.readTicketPhoto(ticketOuting.id, s.attachment.id)
  await vi.waitFor(() => expect(s.downloadTicket).toHaveBeenCalledOnce())
  await s.runtime.detachTicket(ticketOuting.id)
  pending.resolve(s.blob)
  await expect(read).rejects.toThrow('replaced or removed')
  expect(await s.ownerStorage.readTicketBlob(ticketOwner, s.attachment.id)).toBeNull()
})

it('keeps anonymous captures offline and never downloads another owner’s image', async () => {
  const s = await setup()
  await s.runtime.loadAnonymous(ticketSnapshot())
  await s.runtime.attachTicket(ticketOuting.id, s.attachment, s.blob)
  expect(await (await s.runtime.readTicketPhoto(ticketOuting.id, s.attachment.id)).arrayBuffer()).toEqual(await s.blob.arrayBuffer())
  expect(s.downloadTicket).not.toHaveBeenCalled()
  expect((await s.anonymousStorage.readTicketBlob({ ...ticketOwner, userId: 'anonymous-local-only' }, s.attachment.id))?.attachment.objectKey).toContain('anonymous-local-only/')
})
