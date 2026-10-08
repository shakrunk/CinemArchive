import { IDBFactory, IDBObjectStore } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createCommand } from './commands'
import { IndexedDbOfflineStore } from './storage'
import { replayPending } from './replay'
import { owner, snapshot, title, writeRawOwner } from './fixtures.test-support'

const connections: IndexedDbOfflineStore[] = []
function store(factory = new IDBFactory()) {
  const db = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'offline-test' })
  connections.push(db)
  return db
}
afterEach(async () => { vi.restoreAllMocks(); await Promise.all(connections.splice(0).map((db) => db.close())) })

describe('IndexedDB offline journal', () => {
  it('acknowledges a receipt retry with fresh server state and replays remaining work', async () => {
    const db = store()
    await db.replaceBase(owner, snapshot())
    const delivered = createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { notes: 'old delivered edit' } })
    await db.append(delivered)
    await db.append(createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { rating: 4 } }, { dependsOn: [delivered.id] }))
    const fresh = { ...snapshot(), titles: [{ ...title, notes: 'newer mobile edit' }] }
    await db.acknowledge(owner, delivered.id, undefined, fresh)
    const { document } = await db.read(owner)
    expect(document.commands).toHaveLength(1)
    expect(document.commands[0].dependsOn).toEqual([])
    expect(replayPending(document.base, document.commands).titles[0]).toMatchObject({ notes: 'newer mobile edit', rating: 4 })
  })

  it('survives connection close/reopen and isolates both account and project', async () => {
    const factory = new IDBFactory()
    const first = store(factory)
    await first.replaceBase(owner, snapshot())
    const command = createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { notes: 'offline edit' } })
    await first.append(command)
    await first.close()
    const reopened = store(factory)
    const read = await reopened.read(owner)
    expect(read.document.commands[0].id).toBe(command.id)
    expect(replayPending(read.document.base, read.document.commands).titles[0].notes).toBe('offline edit')
    expect((await reopened.read({ ...owner, userId: 'owner-b' })).document.commands).toEqual([])
    expect((await reopened.read({ ...owner, projectId: 'other-project' })).document.base.titles).toEqual([])
  })

  it('serializes concurrent tabs without losing appends, even with identical timestamps', async () => {
    const factory = new IDBFactory()
    const a = store(factory)
    const b = store(factory)
    const writes = Array.from({ length: 12 }, (_, i) => createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { rating: i % 5 } }))
    await Promise.all(writes.map((c, i) => (i % 2 ? a : b).append(c)))
    const { document } = await a.read(owner)
    expect(document.commands).toHaveLength(12)
    expect(new Set(document.commands.map((c) => c.id)).size).toBe(12)
    expect(document.commands.map((c) => c.sequence)).toEqual(Array.from({ length: 12 }, (_, i) => i + 1))
  })

  it('acknowledges base and dependent commands atomically', async () => {
    const db = store()
    const parent = createCommand(owner, { kind: 'title.create', title })
    const child = createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { rating: 5 } }, { dependsOn: [parent.id] })
    await db.append(parent)
    await db.append(child)
    await db.acknowledge(owner, parent.id)
    const { document } = await db.read(owner)
    expect(document.base.titles).toEqual([title])
    expect(document.commands).toHaveLength(1)
    expect(document.commands[0].dependsOn).toEqual([])
    expect(replayPending(document.base, document.commands).titles[0].rating).toBe(5)
  })

  it('keeps journal and old base intact when acknowledgement storage fails', async () => {
    const db = store()
    const command = createCommand(owner, { kind: 'title.create', title })
    await db.append(command)
    const original = IDBObjectStore.prototype.put
    const put = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(function (this: IDBObjectStore, ...args: Parameters<typeof original>) {
      if (this.name === 'owners') throw new DOMException('Quota exhausted', 'QuotaExceededError')
      return original.apply(this, args)
    })
    await expect(db.acknowledge(owner, command.id)).rejects.toThrow('not saved')
    put.mockRestore()
    const read = await db.read(owner)
    expect(read.document.base.titles).toEqual([])
    expect(read.document.commands[0].id).toBe(command.id)
  })

  it('rejects a failed enqueue rather than claiming a durable save', async () => {
    const db = store()
    await db.read(owner)
    vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(() => { throw new DOMException('Blocked', 'QuotaExceededError') })
    await expect(db.append(createCommand(owner, { kind: 'title.create', title }))).rejects.toThrow('not saved')
    vi.restoreAllMocks()
    expect((await db.read(owner)).document.commands).toEqual([])
  })

  it('quarantines unsupported records without assigning or dropping their contents', async () => {
    const factory = new IDBFactory()
    const db = store(factory)
    await db.read(owner)
    const raw = { version: 999, unknownFuturePayload: { important: 'do not discard' } }
    await writeRawOwner(factory, 'offline-test', raw)
    const read = await db.read(owner)
    expect(read.document.commands).toEqual([])
    expect(read.quarantined).toHaveLength(1)
    expect(read.quarantined[0].raw).toEqual(raw)
    expect((await db.read(owner)).quarantined).toHaveLength(1)
    expect((await db.read({ ...owner, userId: 'owner-b' })).quarantined).toEqual([])
  })

  it('preserves a pending patch when a stale server base arrives', async () => {
    const db = store()
    await db.replaceBase(owner, snapshot())
    await db.append(createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { rating: 4 } }))
    const read = await db.replaceBase(owner, { ...snapshot(), titles: [{ ...title, rating: 1, notes: undefined }] })
    expect(replayPending(read.document.base, read.document.commands).titles[0].rating).toBe(4)
  })

  it('rejects missing dependencies, reused IDs, and unsafe prerequisite discard', async () => {
    const db = store()
    const parent = createCommand(owner, { kind: 'title.create', title })
    await expect(db.append(createCommand(owner, { kind: 'title.delete', titleId: title.id }, { dependsOn: ['missing'] }))).rejects.toThrow()
    await db.append(parent)
    await db.append(createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { rating: 4 } }, { dependsOn: [parent.id] }))
    await expect(db.discard(owner, parent.id)).rejects.toThrow()
    await expect(db.append({ ...parent, mutation: { kind: 'title.delete', titleId: title.id } })).rejects.toThrow()
    expect((await db.read(owner)).document.commands).toHaveLength(2)
  })

  it.each(['missing-command', 'later-command'])('quarantines persisted dependency %s instead of delivering out of order', async (dependency) => {
    const factory = new IDBFactory()
    const db = store(factory)
    await db.append(createCommand(owner, { kind: 'title.create', title }, { id: 'earlier-command' }))
    await db.append(createCommand(owner, { kind: 'title.patch', titleId: title.id, patch: { rating: 4 } }, { id: 'later-command' }))
    const raw = (await db.read(owner)).document
    raw.commands[0].dependsOn = [dependency]
    await writeRawOwner(factory, 'offline-test', raw)
    const read = await db.read(owner)
    expect(read.document.commands).toEqual([])
    expect(read.quarantined[0].reason).toContain('dependency')
    expect(read.quarantined[0].raw).toEqual(raw)
  })

  it('closes a late successful connection after rejecting a blocked open', async () => {
    const close = vi.fn()
    const request = { onblocked: null, onsuccess: null, result: { close } } as unknown as IDBOpenDBRequest
    const factory = { open: () => request } as unknown as IDBFactory
    const db = store(factory)
    const pending = db.read(owner)
    const rejected = expect(pending).rejects.toThrow('blocked')
    request.onblocked!.call(request, new Event('blocked') as IDBVersionChangeEvent)
    await rejected
    request.onsuccess!.call(request, new Event('success'))
    expect(close).toHaveBeenCalledOnce()
  })
})
