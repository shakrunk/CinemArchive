// @vitest-environment node
import { IDBFactory, IDBObjectStore, IDBDatabase } from 'fake-indexeddb'
import { afterEach, expect, it, vi } from 'vitest'
import { createCommand, scopeKey, type PendingCommand } from '../offline/commands'
import { IndexedDbOfflineStore } from '../offline/storage'
import { replayPending } from '../offline/replay'
import { assertCommand, assertMutation, assertSnapshot } from '../offline/validation'
import { ticketFixture, ticketOwner, ticketOuting, ticketSnapshot, ticketId, operationId, ticketRevision } from './fixtures.test-support'
import { isTicketAttachment, ticketObjectKey } from './validation'

const stores: IndexedDbOfflineStore[] = []
function store(factory = new IDBFactory()) {
  const result = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'ticket-store-tests' })
  stores.push(result)
  return result
}
afterEach(async () => { vi.restoreAllMocks(); await Promise.all(stores.splice(0).map((db) => db.close())) })

async function legacyJournal(factory: IDBFactory, commands: PendingCommand[]) {
  const db = store(factory)
  await db.replaceBase(ticketOwner, ticketSnapshot())
  await db.close()
  await new Promise<void>((resolve, reject) => {
    const open = factory.open('ticket-store-tests')
    open.onsuccess = () => {
      const connection = open.result, tx = connection.transaction('owners', 'readwrite')
      tx.objectStore('owners').put({ version: 1, scope: ticketOwner, revision: 7, nextSequence: commands.length + 1,
        base: ticketSnapshot(), commands }, scopeKey(ticketOwner))
      tx.oncomplete = () => { connection.close(); resolve() }; tx.onabort = () => reject(tx.error)
    }
    open.onerror = () => reject(open.error)
  })
}

it('commits original bytes and command together, survives reopening and isolates owner/project', async () => {
  const factory = new IDBFactory(), db = store(factory)
  const { attachment, blob } = await ticketFixture()
  await db.replaceBase(ticketOwner, ticketSnapshot())
  const saved = await db.attachTicket(ticketOwner, ticketOuting.id, attachment, blob, { id: operationId })
  expect(saved.document.commands[0].mutation).toMatchObject({ kind: 'ticket.attach', expectedAttachmentId: null, expectedUpdatedAt: ticketRevision })
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
  expect(a.mutation).toMatchObject({ expectedOperationId: before.id })
  expect(b.mutation).toMatchObject({ expectedAttachmentId: first.attachment.id, expectedOperationId: a.id })
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

it('preserves unknown-outcome guards across restart, refresh and retry, and chains a detach transactionally', async () => {
  const factory = new IDBFactory(), db = store(factory), fixture = await ticketFixture()
  await db.replaceBase(ticketOwner, ticketSnapshot())
  await db.attachTicket(ticketOwner, ticketOuting.id, fixture.attachment, fixture.blob, { id: operationId })
  await db.recordFailure(ticketOwner, operationId, { state: 'pending', message: 'Response lost', nextAttemptAt: 123 })
  const original = (await db.read(ticketOwner)).document.commands[0].mutation
  await db.close()
  const reopened = store(factory)
  await reopened.replaceBase(ticketOwner, { ...ticketSnapshot(), rowRevisions: { [`cinema_outings:${ticketOuting.id}`]: '2026-10-09T00:00:00.000001Z' } })
  await reopened.retry(ticketOwner, operationId)
  await reopened.attachTicket(ticketOwner, ticketOuting.id, fixture.attachment, fixture.blob, { id: operationId })
  const saved = await reopened.detachTicket(ticketOwner, ticketOuting.id)
  expect(saved.document.commands[0].mutation).toEqual(original)
  expect(saved.document.commands[1].mutation).toMatchObject({ kind: 'ticket.detach', expectedAttachmentId: fixture.attachment.id, expectedOperationId: operationId })
  expect(saved.document.commands[1].dependsOn).toContain(operationId)
})

it('requires a known revision for authenticated tickets and retains the prior photo when proof is missing', async () => {
  const db = store(), fixture = await ticketFixture()
  await db.replaceBase(ticketOwner, { ...ticketSnapshot(), rowRevisions: {}, outings: [{ ...ticketOuting, ticketManaged: true, ticketAttachment: fixture.attachment }] })
  await expect(db.detachTicket(ticketOwner, ticketOuting.id)).rejects.toThrow('Reconnect and refresh')
  const replacement = await ticketFixture('30000000-0000-4000-8000-000000000002')
  await expect(db.attachTicket(ticketOwner, ticketOuting.id, replacement.attachment, replacement.blob)).rejects.toThrow('Reconnect and refresh')
  expect((await db.read(ticketOwner)).document.base.outings[0].ticketAttachment).toEqual(fixture.attachment)
  expect(await db.readTicketBlob(ticketOwner, replacement.attachment.id)).toBeNull()
  expect((await db.read(ticketOwner)).document.commands).toEqual([])
})

it('chains a ticket from an offline outing create without requiring a guessed server revision', async () => {
  const db = store(), fixture = await ticketFixture()
  await db.replaceBase(ticketOwner, { ...ticketSnapshot(), outings: [], rowRevisions: {} })
  const outing = { ...ticketOuting, ticketImagePath: undefined }
  const create = createCommand(ticketOwner, { kind: 'outing.create', outing })
  await db.append(create)
  const read = await db.attachTicket(ticketOwner, ticketOuting.id, fixture.attachment, fixture.blob)
  expect(read.document.commands[1].mutation).toMatchObject({ expectedOperationId: create.id })
  expect(read.document.commands[1].dependsOn).toContain(create.id)
})

it('keeps legacy pending intent immutable and rejects new dependent edits instead of borrowing its unproven revision', async () => {
  const factory = new IDBFactory(), fixture = await ticketFixture()
  const legacy = { ...fixture.command, sequence: 1, attempts: 2 }
  await legacyJournal(factory, [legacy])
  const db = store(factory)
  await db.attachTicket(ticketOwner, ticketOuting.id, fixture.attachment, fixture.blob, { id: legacy.id })
  await expect(db.detachTicket(ticketOwner, ticketOuting.id)).rejects.toThrow('older ticket change')
  const edit = createCommand(ticketOwner, { kind: 'outing.patch', outingId: ticketOuting.id, patch: { venue: 'Unseen edit' } })
  await expect(db.append(edit)).rejects.toThrow('Sync or review')
  await expect(db.append({ ...edit, preconditions: [{ table: 'cinema_outings', id: ticketOuting.id, updatedAt: ticketRevision }] })).rejects.toThrow('Sync or review')
  const read = await db.read(ticketOwner)
  expect(read.document.commands).toEqual([legacy])
  expect(read.quarantined).toEqual([])
})

it('retains an already-persisted dependency on a legacy ticket for backend conflict review without rewriting it', async () => {
  const factory = new IDBFactory(), fixture = await ticketFixture()
  const legacy = { ...fixture.command, sequence: 1 }
  const dependent = { ...createCommand(ticketOwner, { kind: 'outing.patch', outingId: ticketOuting.id, patch: { venue: 'Old queued edit' } },
    { dependsOn: [legacy.id], preconditions: [{ table: 'cinema_outings', id: ticketOuting.id, afterCommandId: legacy.id }] }), sequence: 2 }
  await legacyJournal(factory, [legacy, dependent])
  const read = await store(factory).read(ticketOwner)
  expect(read.document.commands).toEqual([legacy, dependent])
  expect(read.quarantined).toEqual([])
})

it('validates exclusive ticket revision guards, exact precision and self-dependencies', async () => {
  const { command } = await ticketFixture()
  for (const guard of [
    { expectedUpdatedAt: null }, { expectedOperationId: null }, { expectedOperationId: 'not-a-uuid' },
    { expectedUpdatedAt: ticketRevision, expectedOperationId: operationId }, { expectedUpdatedAt: '2026-10-08' },
    { expectedUpdatedAt: '2026-10-08T19:00:00.1234567Z' },
  ]) expect(() => assertMutation({ ...command.mutation, ...guard })).toThrow()
  expect(() => assertCommand({ ...command, mutation: { ...command.mutation, expectedOperationId: command.id } })).toThrow('own operation')
  expect(() => assertCommand({ ...command, mutation: { ...command.mutation, expectedUpdatedAt: ticketRevision } })).not.toThrow()
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
