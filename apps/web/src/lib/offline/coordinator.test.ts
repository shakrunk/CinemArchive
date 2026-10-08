import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { browserExclusiveLock, OfflineCoordinator, type DeliveryResult, type ExclusiveLock, type OfflineCoordinatorState } from './coordinator'
import { IndexedDbOfflineStore } from './storage'
import { deferred, owner, snapshot, title, writeRawOwner } from './fixtures.test-support'
import type { OfflineSnapshot } from './snapshot'

function mutex(): ExclusiveLock {
  const tails = new Map<string, Promise<unknown>>()
  return async (name, work) => {
    const running = (tails.get(name) ?? Promise.resolve()).catch(() => {}).then(work)
    tails.set(name, running)
    return running
  }
}
const coordinators: OfflineCoordinator[] = []
const stores: IndexedDbOfflineStore[] = []
function setup(options: {
  factory?: IDBFactory
  lock?: ExclusiveLock
  deliver?: ConstructorParameters<typeof OfflineCoordinator>[0]['deliver']
  isAuthenticated?: ConstructorParameters<typeof OfflineCoordinator>[0]['isAuthenticated']
  now?: () => number
} = {}) {
  const store = new IndexedDbOfflineStore({ indexedDB: options.factory ?? new IDBFactory(), databaseName: 'coordinator-test' })
  const onState = vi.fn<(state: OfflineCoordinatorState | null) => void>()
  const deliver = vi.fn(options.deliver ?? (async (): Promise<DeliveryResult> => ({ kind: 'success' })))
  const coordinator = new OfflineCoordinator({ store, onState, onError: vi.fn(), deliver,
    isAuthenticated: options.isAuthenticated ?? (() => true), lock: options.lock ?? mutex(), now: options.now, random: () => 0.5,
  })
  stores.push(store)
  coordinators.push(coordinator)
  return { store, coordinator, deliver, onState }
}
afterEach(async () => {
  coordinators.splice(0).forEach((c) => c.deactivate())
  await Promise.all(stores.splice(0).map((s) => s.close()))
  vi.restoreAllMocks()
})

describe('owner offline coordinator', () => {
  it('publishes optimism only after durable commit and restores it on activation', async () => {
    const { store, coordinator, onState } = setup()
    await coordinator.activate(owner)
    await store.replaceBase(owner, snapshot())
    const command = await coordinator.submit({ kind: 'title.patch', titleId: title.id, patch: { rating: 4 } })
    expect((await store.read(owner)).document.commands[0].id).toBe(command.id)
    expect(onState.mock.lastCall![0]!.snapshot.titles[0].rating).toBe(4)
    coordinator.deactivate()
    await coordinator.activate(owner)
    expect(onState.mock.lastCall![0]!.snapshot.titles[0].rating).toBe(4)
  })

  it('rebases a command submitted during a slow fetch and serializes delivery behind it', async () => {
    const { store, coordinator, onState, deliver } = setup()
    await coordinator.activate(owner)
    await store.replaceBase(owner, snapshot())
    const entered = deferred<void>()
    const response = deferred<OfflineSnapshot>()
    const refresh = coordinator.refresh(() => { entered.resolve(); return response.promise })
    await entered.promise
    await coordinator.submit({ kind: 'title.patch', titleId: title.id, patch: { rating: 5 } })
    const flush = coordinator.flush()
    expect(deliver).not.toHaveBeenCalled()
    response.resolve({ ...snapshot(), titles: [{ ...title, rating: 1 }] })
    await Promise.all([refresh, flush])
    expect(onState.mock.lastCall![0]!.snapshot.titles[0].rating).toBe(5)
    expect((await store.read(owner)).document.commands).toEqual([])
    expect((await store.read(owner)).document.base.titles[0].rating).toBe(5)
  })

  it('does not publish or acknowledge an old account delivery after switching', async () => {
    const entered = deferred<void>()
    const response = deferred<DeliveryResult>()
    const { store, coordinator, onState } = setup({ deliver: () => { entered.resolve(); return response.promise } })
    await coordinator.activate(owner)
    const command = await coordinator.submit({ kind: 'title.create', title })
    const flush = coordinator.flush()
    await entered.promise
    const other = { ...owner, userId: 'owner-b' }
    await coordinator.activate(other)
    response.resolve({ kind: 'success' })
    await flush
    expect((await store.read(owner)).document.commands[0].id).toBe(command.id)
    expect(onState.mock.lastCall![0]!.document.scope).toEqual(other)
    expect(onState.mock.lastCall![0]!.snapshot.titles).toEqual([])
  })

  it('does not apply a stale fetch even after switching away and back to the same owner', async () => {
    const { coordinator, store } = setup()
    await coordinator.activate(owner)
    const response = deferred<OfflineSnapshot>()
    const entered = deferred<void>()
    const refresh = coordinator.refresh(() => { entered.resolve(); return response.promise })
    await entered.promise
    await coordinator.activate({ ...owner, userId: 'owner-b' })
    await coordinator.activate(owner)
    response.resolve(snapshot())
    await refresh
    expect((await store.read(owner)).document.base.titles).toEqual([])
  })

  it('checks authentication before delivery and retains commands until auth resumes', async () => {
    let authenticated = false
    const { coordinator, store, deliver } = setup({ isAuthenticated: () => authenticated })
    await coordinator.activate(owner)
    await coordinator.submit({ kind: 'title.create', title })
    await coordinator.flush()
    expect(deliver).not.toHaveBeenCalled()
    expect((await store.read(owner)).document.commands).toHaveLength(1)
    authenticated = true
    await coordinator.flush()
    expect(deliver).toHaveBeenCalledTimes(1)
    expect((await store.read(owner)).document.commands).toEqual([])
  })

  it('persists retry state and reuses the same operation after an unknown outcome', async () => {
    let now = 1000
    let attempts = 0
    const { coordinator, store, deliver } = setup({ now: () => now, deliver: async () => {
      attempts++
      if (attempts === 1) throw new Error('Response lost')
      return { kind: 'success' }
    } })
    await coordinator.activate(owner)
    const command = await coordinator.submit({ kind: 'title.create', title })
    await coordinator.flush()
    const pending = (await store.read(owner)).document.commands[0]
    expect(pending.attempts).toBe(1)
    expect(pending.lastError).toBe('Response lost')
    await coordinator.flush()
    expect(deliver).toHaveBeenCalledTimes(1)
    now = pending.nextAttemptAt
    await coordinator.flush()
    expect(deliver).toHaveBeenCalledTimes(2)
    expect(deliver.mock.calls.map(([c]) => c.id)).toEqual([command.id, command.id])
    expect((await store.read(owner)).document.commands).toEqual([])
  })

  it('does not send a later update ahead of a failed head; explicit retry resumes', async () => {
    const { coordinator, store, deliver } = setup({ deliver: async () => ({ kind: 'failed', message: 'Invalid title' }) })
    await coordinator.activate(owner)
    const first = await coordinator.submit({ kind: 'title.create', title })
    await coordinator.submit({ kind: 'title.patch', titleId: title.id, patch: { rating: 5 } }, { dependsOn: [first.id] })
    await coordinator.flush()
    await coordinator.flush()
    expect(deliver).toHaveBeenCalledTimes(1)
    expect((await store.read(owner)).document.commands[0].state).toBe('failed')
    deliver.mockResolvedValue({ kind: 'success' })
    await coordinator.retry(first.id)
    expect((await store.read(owner)).document.commands).toEqual([])
    expect((await store.read(owner)).document.base.titles[0].rating).toBe(5)
  })

  it('uses cross-tab exclusion to deliver a shared command once', async () => {
    const factory = new IDBFactory()
    const lock = mutex()
    const a = setup({ factory, lock })
    const b = setup({ factory, lock })
    await Promise.all([a.coordinator.activate(owner), b.coordinator.activate(owner)])
    await a.coordinator.submit({ kind: 'title.create', title })
    await Promise.all([a.coordinator.flush(), b.coordinator.flush()])
    expect(a.deliver.mock.calls.length + b.deliver.mock.calls.length).toBe(1)
    expect((await a.store.read(owner)).document.commands).toEqual([])
  })

  it('delivers an independent title after a failed create while retaining its dependent edit', async () => {
    const { coordinator, store, deliver } = setup({ deliver: async (command) =>
      command.mutation.kind === 'title.create' && command.mutation.title.id === title.id
        ? { kind: 'failed', message: 'Invalid title' } : { kind: 'success' } })
    await coordinator.activate(owner)
    const failed = await coordinator.submit({ kind: 'title.create', title })
    const dependent = await coordinator.submit({ kind: 'title.patch', titleId: title.id, patch: { rating: 4 } })
    const independent = await coordinator.submit({ kind: 'title.create', title: { ...title, id: 'independent', tmdbId: 222 } })
    await coordinator.flush()
    expect(deliver.mock.calls.map(([command]) => command.id)).toEqual([failed.id, independent.id])
    expect((await store.read(owner)).document.commands.map((command) => command.id)).toEqual([failed.id, dependent.id])
    expect((await store.read(owner)).document.base.titles.map((row) => row.id)).toEqual(['independent'])
  })

  it('delivers independent work during backoff and resumes related work after its prerequisite succeeds', async () => {
    let now = 1000
    const { coordinator, store, deliver } = setup({ now: () => now })
    deliver.mockResolvedValueOnce({ kind: 'retry', message: 'Busy', retryAfterMs: 10000 })
    await coordinator.activate(owner)
    const first = await coordinator.submit({ kind: 'title.create', title })
    const dependent = await coordinator.submit({ kind: 'title.patch', titleId: title.id, patch: { rating: 4 } })
    const independent = await coordinator.submit({ kind: 'title.create', title: { ...title, id: 'independent', tmdbId: 222 } })
    await coordinator.flush()
    expect(deliver.mock.calls.map(([command]) => command.id)).toEqual([first.id, independent.id])
    now = 11000
    await coordinator.flush()
    expect(deliver.mock.calls.map(([command]) => command.id)).toEqual([first.id, independent.id, first.id, dependent.id])
    expect((await store.read(owner)).document.commands).toEqual([])
  })

  it('pauses all work on authentication failure even for independent records', async () => {
    const { coordinator, store, deliver } = setup({ deliver: async () => ({ kind: 'auth', message: 'Sign in again' }) })
    await coordinator.activate(owner)
    await coordinator.submit({ kind: 'title.create', title })
    await coordinator.submit({ kind: 'title.create', title: { ...title, id: 'independent', tmdbId: 222 } })
    await coordinator.flush()
    expect(deliver).toHaveBeenCalledTimes(1)
    expect((await store.read(owner)).document.commands).toHaveLength(2)
  })

  it('does not start uncoordinated remote writes when browser locks are unavailable', async () => {
    vi.stubGlobal('navigator', {})
    const work = vi.fn()
    try {
      await expect(browserExclusiveLock('owner-lock', work)).rejects.toThrow('coordination is unavailable')
      expect(work).not.toHaveBeenCalled()
    } finally { vi.unstubAllGlobals() }
  })

  it('rechecks the durable queue before idle remote work, including another tab edits', async () => {
    const factory = new IDBFactory(), lock = mutex()
    const first = setup({ factory, lock }), second = setup({ factory, lock })
    await first.coordinator.activate(owner)
    await second.coordinator.activate(owner)
    await second.coordinator.submit({ kind: 'title.create', title })
    const remote = vi.fn(async () => ['completed'])
    expect(await first.coordinator.runIdleRemote(remote, async () => snapshot())).toBeUndefined()
    expect(remote).not.toHaveBeenCalled()
  })

  it('holds the delivery lock through idle reconciliation and preserves edits made during it', async () => {
    const { coordinator, store, deliver, onState } = setup()
    await coordinator.activate(owner)
    await store.replaceBase(owner, snapshot())
    const entered = deferred<void>(), response = deferred<string[]>()
    const reconcile = coordinator.runIdleRemote(() => { entered.resolve(); return response.promise }, async () => snapshot())
    await entered.promise
    await coordinator.submit({ kind: 'title.patch', titleId: title.id, patch: { rating: 5 } })
    const flush = coordinator.flush()
    expect(deliver).not.toHaveBeenCalled()
    response.resolve(['completed'])
    expect(await reconcile).toEqual(['completed'])
    await flush
    expect(onState.mock.lastCall![0]!.snapshot.titles[0].rating).toBe(5)
  })

  it('discards old-account reconciliation results before refreshing or notifying', async () => {
    const { coordinator } = setup()
    await coordinator.activate(owner)
    const entered = deferred<void>(), response = deferred<string[]>()
    const fetchBase = vi.fn(async () => snapshot())
    const reconcile = coordinator.runIdleRemote(() => { entered.resolve(); return response.promise }, fetchBase)
    await entered.promise
    await coordinator.activate({ ...owner, userId: 'next-owner' })
    response.resolve(['private-completion'])
    expect(await reconcile).toBeUndefined()
    expect(fetchBase).not.toHaveBeenCalled()
  })

  it('does not publish successful optimism when persistence rejects a submission', async () => {
    const { coordinator, store, onState } = setup()
    await coordinator.activate(owner)
    const before = onState.mock.calls.length
    vi.spyOn(store, 'append').mockRejectedValue(new Error('Quota exhausted'))
    await expect(coordinator.submit({ kind: 'title.create', title })).rejects.toThrow('Quota')
    expect(onState).toHaveBeenCalledTimes(before)
  })

  it('publishes quarantine after a high revision and rejects a delayed pre-corruption read', async () => {
    const factory = new IDBFactory()
    const { coordinator, store, onState, deliver } = setup({ factory })
    await coordinator.activate(owner)
    await store.replaceBase(owner, snapshot())
    for (let i = 0; i < 3; i++) await coordinator.submit({ kind: 'title.patch', titleId: title.id, patch: { rating: i } })
    const oldRead = await store.read(owner)
    expect(oldRead.document.revision).toBeGreaterThan(0)
    const delayed = deferred<typeof oldRead>()
    vi.spyOn(store, 'read').mockReturnValueOnce(delayed.promise)
    const staleReload = coordinator.reload()
    await writeRawOwner(factory, 'coordinator-test', { ...oldRead.document, version: 999 })
    await coordinator.reload()
    expect(onState.mock.lastCall![0]!.quarantined).toHaveLength(1)
    expect(onState.mock.lastCall![0]!.document.revision).toBe(0)
    const callsAfterCorruption = onState.mock.calls.length
    delayed.resolve(oldRead)
    await staleReload
    expect(onState.mock.calls).toHaveLength(callsAfterCorruption)
    await coordinator.submit({ kind: 'title.create', title })
    expect(onState.mock.lastCall![0]!.document.revision).toBe(1)
    expect(onState.mock.lastCall![0]!.quarantined).toHaveLength(1)
    await coordinator.flush()
    expect(deliver).not.toHaveBeenCalled()
  })
})
