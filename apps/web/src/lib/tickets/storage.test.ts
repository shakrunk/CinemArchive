// @vitest-environment node
import { IDBFactory, IDBObjectStore, IDBDatabase } from 'fake-indexeddb'
import { afterEach, expect, it, vi } from 'vitest'
import { createCommand, scopeKey } from '../offline/commands'
import { IndexedDbOfflineStore } from '../offline/storage'
import { replayPending } from '../offline/replay'
import { assertMutation, assertSnapshot } from '../offline/validation'
import { ticketFixture, ticketOwner, ticketOuting, ticketSnapshot, ticketId, operationId } from './fixtures.test-support'
import { isTicketAttachment, ticketObjectKey } from './validation'

const stores: IndexedDbOfflineStore[] = []
function store(factory = new IDBFactory()) {
  const result = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'ticket-store-tests' })
  stores.push(result)
  return result
}
afterEach(async () => { vi.restoreAllMocks(); await Promise.all(stores.splice(0).map((db) => db.close())) })

it('commits original bytes and command together, survives reopening and isolates owner/project', async () => {
  const factory = new IDBFactory(), db = store(factory)
  const { attachment, blob } = await ticketFixture()
  await db.replaceBase(ticketOwner, ticketSnapshot())
  const saved = await db.attachTicket(ticketOwner, ticketOuting.id, attachment, blob, { id: operationId })
  expect(saved.document.commands[0].mutation).toMatchObject({ kind: 'ticket.attach', expectedAttachmentId: null })
  expect(replayPending(saved.document.base, saved.document.commands).outings[0]).toMatchObject({ ticketAttachment: attachment, ticketManaged: true })
  await db.close()
  const reopened = store(factory)
  const bytes = (await reopened.readTicketBlob(ticketOwner, ticketId))!.blob
  expect(new Uint8Array(await bytes.arrayBuffer())).toEqual(new Uint8Array(await blob.arrayBuffer()))
  expect((await reopened.read(ticketOwner)).document.commands).toHaveLength(1)
  expect(await reopened.readTicketBlob({ ...ticketOwner, userId: 'other' }, ticketId)).toBeNull()
  expect(await reopened.readTicketBlob({ ...ticketOwner, projectId: 'other' }, ticketId)).toBeNull()
})

it.each(['owners', 'ticketBlobs'])('rolls back both records when %s persistence fails', async (name) => {
  const db = store(), { attachment, blob } = await ticketFixture()
  await db.replaceBase(ticketOwner, ticketSnapshot())
  const method = name === 'owners' ? 'put' : 'add'
  const original = IDBObjectStore.prototype[method]
  vi.spyOn(IDBObjectStore.prototype, method).mockImplementation(function (this: IDBObjectStore, ...args: Parameters<typeof original>) {
    if (this.name === name) throw new DOMException('Quota exceeded', 'QuotaExceededError')
    return original.apply(this, args)
  })
  await expect(db.attachTicket(ticketOwner, ticketOuting.id, attachment, blob)).rejects.toThrow('not saved')
  vi.restoreAllMocks()
  expect((await db.read(ticketOwner)).document.commands).toEqual([])
  expect(await db.readTicketBlob(ticketOwner, ticketId)).toBeNull()
})

it('captures rapid replacement CAS and generic causal guards without rewriting submitted intents', async () => {
  const db = store(), first = await ticketFixture(), second = await ticketFixture('30000000-0000-4000-8000-000000000002')
  await db.replaceBase(ticketOwner, ticketSnapshot())
  const earlier = createCommand(ticketOwner, { kind: 'outing.patch', outingId: ticketOuting.id, patch: { venue: 'New venue' } })
  await db.append(earlier)
  await db.attachTicket(ticketOwner, ticketOuting.id, first.attachment, first.blob, { id: operationId })
  await db.attachTicket(ticketOwner, ticketOuting.id, second.attachment, second.blob)
  const read = await db.append(createCommand(ticketOwner, { kind: 'outing.patch', outingId: ticketOuting.id, patch: { notes: 'After photos' } }))
  const [before, a, b, after] = read.document.commands
  expect(a.dependsOn).toContain(before.id)
  expect(a.preconditions).toBeUndefined()
  expect(b.mutation).toMatchObject({ expectedAttachmentId: first.attachment.id })
  expect(b.dependsOn).toContain(a.id)
  expect(after.preconditions).toEqual([{ table: 'cinema_outings', id: ticketOuting.id, afterCommandId: b.id }])
  await db.acknowledge(ticketOwner, a.id)
  const remaining = (await db.read(ticketOwner)).document.commands.find((command) => command.id === b.id)!
  expect(remaining.mutation).toEqual(b.mutation)
})

it('retains bytes after detach and marks legacy fallback as managed, including anonymous saves', async () => {
  const db = store(), { attachment, blob } = await ticketFixture()
  const anonymous = { ...ticketOwner, userId: 'anonymous-local-only' }
  await db.replaceBase(anonymous, ticketSnapshot())
  await db.attachTicket(anonymous, ticketOuting.id, attachment, blob, { localOnly: true })
  const read = await db.detachTicket(anonymous, ticketOuting.id, { localOnly: true })
  expect(read.document.commands).toEqual([])
  expect(read.document.base.outings[0].ticketAttachment).toBeUndefined()
  expect(read.document.base.outings[0]).toMatchObject({ ticketManaged: true, ticketImagePath: '/private/legacy.jpg' })
  expect(await db.readTicketBlob(anonymous, ticketId)).not.toBeNull()
})

it('rejects reused IDs with different metadata, corrupt bytes and generic bypasses', async () => {
  const db = store(), { attachment, blob, command } = await ticketFixture()
  await db.replaceBase(ticketOwner, ticketSnapshot())
  expect(() => db.append(command)).toThrow('atomic ticket')
  await expect(db.attachTicket(ticketOwner, ticketOuting.id, { ...attachment, sha256: '0'.repeat(64) }, blob)).rejects.toThrow('digest')
  await db.attachTicket(ticketOwner, ticketOuting.id, attachment, blob, { id: operationId })
  await db.attachTicket(ticketOwner, ticketOuting.id, attachment, blob, { id: operationId })
  expect((await db.read(ticketOwner)).document.commands).toHaveLength(1)
  await expect(db.attachTicket(ticketOwner, ticketOuting.id, { ...attachment, barcode: null }, blob)).rejects.toThrow('not saved')
  expect((await db.read(ticketOwner)).document.commands).toHaveLength(1)
})

it('preserves version-one journals and quarantine during the blob-store upgrade', async () => {
  const factory = new IDBFactory()
  await new Promise<void>((resolve, reject) => {
    const open = factory.open('ticket-store-tests', 1)
    open.onupgradeneeded = () => {
      open.result.createObjectStore('owners')
      open.result.createObjectStore('quarantine', { keyPath: 'id' }).createIndex('scopeKey', 'scopeKey')
    }
    open.onsuccess = () => {
      const db = open.result, tx = db.transaction(['owners', 'quarantine'], 'readwrite')
      tx.objectStore('owners').put({ version: 1, scope: ticketOwner, revision: 2, nextSequence: 1, base: ticketSnapshot(), commands: [] }, scopeKey(ticketOwner))
      tx.objectStore('quarantine').add({ id: 'kept', scopeKey: scopeKey(ticketOwner), capturedAt: '2026-01-01', reason: 'Preserve', raw: { important: true } })
      tx.oncomplete = () => { db.close(); resolve() }; tx.onabort = () => reject(tx.error)
    }
    open.onerror = () => reject(open.error)
  })
  const read = await store(factory).read(ticketOwner)
  expect(read.document.base.outings).toEqual([ticketOuting])
  expect(read.document.revision).toBe(2)
  expect(read.quarantined[0].raw).toEqual({ important: true })
})

it('validates ticket bounds, ownership, standalone commands and read-only outing fields', async () => {
  const { attachment, command } = await ticketFixture()
  expect(isTicketAttachment({ ...attachment, byteLength: 20 * 1024 * 1024 + 1 })).toBe(false)
  expect(isTicketAttachment({ ...attachment, barcode: { format: 'QR_CODE', payload: '😀'.repeat(8193) } })).toBe(false)
  expect(() => assertMutation({ kind: 'batch', mutations: [command.mutation] })).toThrow()
  expect(() => createCommand({ ...ticketOwner, userId: '50000000-0000-4000-8000-000000000001' }, command.mutation)).toThrow('another owner')
  for (const patch of [{ ticketAttachment: attachment }, { ticketManaged: false }, { ticketImagePath: null }, { ticketBarcodePayload: 'x' }]) {
    expect(() => assertMutation({ kind: 'outing.patch', outingId: ticketOuting.id, patch })).toThrow()
  }
  expect(() => assertSnapshot({ ...ticketSnapshot(), outings: [{ ...ticketOuting, ticketManaged: true, ticketAttachment: attachment }] })).not.toThrow()
  expect(ticketObjectKey(ticketOwner, ticketId)).toBe(attachment.objectKey)
})

it('preserves cached managed associations on unsupported refresh and generic ACK, but honors authoritative clears', async () => {
  const db = store(), { attachment } = await ticketFixture()
  await db.replaceBase(ticketOwner, { ...ticketSnapshot(), outings: [{ ...ticketOuting, ticketManaged: true, ticketAttachment: attachment }] })
  const fallback = { ...ticketSnapshot(), ticketAttachmentSupport: 'unsupported' as const }
  expect((await db.replaceBase(ticketOwner, fallback)).document.base.outings[0].ticketAttachment).toEqual(attachment)
  const command = createCommand(ticketOwner, { kind: 'outing.patch', outingId: ticketOuting.id, patch: { venue: 'Cinema' } })
  await db.append(command)
  expect((await db.acknowledge(ticketOwner, command.id, undefined, fallback)).document.base.outings[0].ticketAttachment).toEqual(attachment)
  await db.replaceBase(ticketOwner, { ...ticketSnapshot(), ticketAttachmentSupport: 'authoritative', outings: [{ ...ticketOuting, ticketManaged: true }] })
  const cleared = await db.replaceBase(ticketOwner, fallback)
  expect(cleared.document.base.outings[0].ticketManaged).toBe(true)
  expect(cleared.document.base.outings[0].ticketAttachment).toBeUndefined()
  expect((await db.read(ticketOwner)).quarantined).toEqual([])
})

it('caches a verified download only while that exact descriptor remains current', async () => {
  const db = store(), { attachment, blob } = await ticketFixture()
  await db.replaceBase(ticketOwner, { ...ticketSnapshot(), outings: [{ ...ticketOuting, ticketManaged: true, ticketAttachment: attachment }] })
  await db.cacheTicketBlob(ticketOwner, ticketOuting.id, attachment, blob)
  expect(await db.readTicketBlob(ticketOwner, attachment.id)).toMatchObject({ source: 'download', outingId: ticketOuting.id })
  const replacement = await ticketFixture('30000000-0000-4000-8000-000000000002')
  await db.attachTicket(ticketOwner, ticketOuting.id, replacement.attachment, replacement.blob)
  await expect(db.cacheTicketBlob(ticketOwner, ticketOuting.id, attachment, blob)).rejects.toMatchObject({ cause: expect.objectContaining({ message: expect.stringContaining('replaced or removed') }) })
  expect((await db.readTicketBlob(ticketOwner, attachment.id))?.attachment).toEqual(attachment)
})

it('keeps the original association and journal intact when downloaded-byte persistence exceeds quota', async () => {
  const db = store(), { attachment, blob } = await ticketFixture()
  await db.replaceBase(ticketOwner, { ...ticketSnapshot(), outings: [{ ...ticketOuting, ticketManaged: true, ticketAttachment: attachment }] })
  const before = (await db.read(ticketOwner)).document
  const original = IDBObjectStore.prototype.add
  vi.spyOn(IDBObjectStore.prototype, 'add').mockImplementation(function (this: IDBObjectStore, ...args: Parameters<typeof original>) {
    if (this.name === 'ticketBlobs') throw new DOMException('Quota exceeded', 'QuotaExceededError')
    return original.apply(this, args)
  })
  await expect(db.cacheTicketBlob(ticketOwner, ticketOuting.id, attachment, blob)).rejects.toThrow('not saved')
  expect((await db.read(ticketOwner)).document).toEqual(before)
  expect(await db.readTicketBlob(ticketOwner, attachment.id)).toBeNull()
})

it('writes raw original bytes and reads both new byte records and legacy Blob records after reopening', async () => {
  const factory = new IDBFactory(), db = store(factory), fixture = await ticketFixture()
  await db.replaceBase(ticketOwner, ticketSnapshot())
  await db.attachTicket(ticketOwner, ticketOuting.id, fixture.attachment, fixture.blob)
  await db.close()
  const originalBytes = await fixture.blob.arrayBuffer()
  await new Promise<void>((resolve, reject) => {
    const request = factory.open('ticket-store-tests')
    request.onsuccess = () => {
      const connection = request.result, tx = connection.transaction('ticketBlobs', 'readwrite'), records = tx.objectStore('ticketBlobs')
      const key = JSON.stringify([ticketOwner.projectId, ticketOwner.userId, fixture.attachment.id])
      const read = records.get(key)
      read.onsuccess = () => {
        expect(read.result.blob).toBeUndefined()
        expect(read.result.bytes).toBeInstanceOf(ArrayBuffer)
        expect(read.result.bytes).toEqual(originalBytes)
        records.put(fixture.record, key)
      }
      tx.oncomplete = () => { connection.close(); resolve() }; tx.onabort = () => reject(tx.error)
    }
    request.onerror = () => reject(request.error)
  })
  const restored = await store(factory).readTicketBlob(ticketOwner, fixture.attachment.id)
  expect(restored!.blob.type).toBe(fixture.attachment.mimeType)
  expect(await restored!.blob.arrayBuffer()).toEqual(await fixture.blob.arrayBuffer())
})

it('explicitly aborts and rejects when a browser emits a transaction error without an automatic abort', async () => {
  const db = store(), fixture = await ticketFixture()
  await db.replaceBase(ticketOwner, ticketSnapshot())
  const transaction = IDBDatabase.prototype.transaction
  vi.spyOn(IDBDatabase.prototype, 'transaction').mockImplementation(function (this: IDBDatabase, ...args: Parameters<typeof transaction>) {
    const tx = transaction.apply(this, args)
    const get = tx.objectStore('owners').get
    vi.spyOn(tx.objectStore('owners'), 'get').mockImplementation(function (this: IDBObjectStore, ...getArgs: Parameters<typeof get>) {
      const request = get.apply(this, getArgs)
      request.addEventListener('success', () => tx.dispatchEvent(new Event('error')))
      return request
    })
    return tx
  })
  await expect(db.attachTicket(ticketOwner, ticketOuting.id, fixture.attachment, fixture.blob)).rejects.toThrow('not saved')
  vi.restoreAllMocks()
  expect((await db.read(ticketOwner)).document.commands).toEqual([])
  expect(await db.readTicketBlob(ticketOwner, fixture.attachment.id)).toBeNull()
})
