import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OfflineLibraryRuntime, fetchOwnerSnapshot, readLegacyDevicePreferences, pickDevicePreferences } from './offlineLibrary'
import { IndexedDbOfflineStore } from '../lib/offline/storage'
import { createCommand } from '../lib/offline/commands'
import { emptySnapshot, type OfflineSnapshot } from '../lib/offline/snapshot'
import { deferred, snapshot, title } from '../lib/offline/fixtures.test-support'
import { fetchAllTitlePins, fetchLedgerLayout, fetchListMemberships, fetchLists, fetchUserLibrary } from '../lib/db'
import { fetchOwnedMoviegoingPreferences } from '../lib/moviegoingPreferences'

vi.mock('../lib/db', () => ({ fetchAllTitlePins: vi.fn(), fetchLedgerLayout: vi.fn(), fetchListMemberships: vi.fn(), fetchLists: vi.fn(), fetchUserLibrary: vi.fn() }))
vi.mock('../lib/tickets/remote', async (original) => ({ ...await original<typeof import('../lib/tickets/remote')>(), ticketRemote: { descriptors: vi.fn().mockResolvedValue({ support: 'authoritative', outings: [] }) } }))
vi.mock('../lib/moviegoingPreferences', async (original) => ({ ...await original<typeof import('../lib/moviegoingPreferences')>(), fetchOwnedMoviegoingPreferences: vi.fn().mockResolvedValue({ support: 'authoritative', venueNotes: [], theaterInterest: [] }) }))

const stores: IndexedDbOfflineStore[] = []
const runtimes: OfflineLibraryRuntime[] = []
function setup(factory = new IDBFactory(), browserEvents = false) {
  const ownerStorage = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'owner' })
  const anonymousStorage = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'anonymous' })
  stores.push(ownerStorage, anonymousStorage)
  const onSnapshot = vi.fn<(value: OfflineSnapshot | null) => void>()
  const fetchBase = vi.fn().mockResolvedValue(emptySnapshot())
  const deliver = vi.fn().mockResolvedValue({ kind: 'success' })
  const runtime = new OfflineLibraryRuntime({ projectId: 'project', ownerStorage, anonymousStorage,
    onSnapshot, onStatus: vi.fn(), onError: vi.fn(), fetchBase, deliver,
    isAuthenticated: async () => true, lock: async (_name, work) => work(), browserEvents,
  })
  runtimes.push(runtime)
  return { runtime, ownerStorage, anonymousStorage, onSnapshot, fetchBase, deliver }
}
afterEach(async () => { runtimes.splice(0).forEach((runtime) => runtime.deactivate()); vi.restoreAllMocks(); vi.unstubAllGlobals(); await Promise.all(stores.splice(0).map((store) => store.close())) })

describe('owner library runtime', () => {
  it('resumes on browser events, reloads broadcasts without a refresh loop, and detaches on logout', async () => {
    const channels: FakeChannel[] = []
    class FakeChannel {
      onmessage: ((event: MessageEvent) => void) | null = null
      postMessage = vi.fn()
      close = vi.fn()
      constructor() { channels.push(this) }
    }
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    const { runtime, fetchBase, ownerStorage } = setup(new IDBFactory(), true)
    const read = vi.spyOn(ownerStorage, 'read')
    await runtime.activate('owner')
    await vi.waitFor(() => expect(fetchBase).toHaveBeenCalledOnce())
    await vi.waitFor(() => expect(channels[0].postMessage).toHaveBeenCalled())
    const reads = read.mock.calls.length
    channels[0].onmessage!(new MessageEvent('message', { data: { kind: 'changed' } }))
    await vi.waitFor(() => expect(read.mock.calls.length).toBeGreaterThan(reads + 1))
    expect(fetchBase).toHaveBeenCalledOnce()
    window.dispatchEvent(new Event('online'))
    await vi.waitFor(() => expect(fetchBase).toHaveBeenCalledTimes(2))
    runtime.deactivate()
    window.dispatchEvent(new Event('online'))
    expect(channels[0].close).toHaveBeenCalledOnce()
    expect(fetchBase).toHaveBeenCalledTimes(2)
  })

  it('publishes anonymous edits only after the durable transaction commits', async () => {
    const { runtime, anonymousStorage, onSnapshot, deliver } = setup()
    await runtime.saveAnonymous(snapshot())
    await runtime.loadAnonymous()
    const read = await anonymousStorage.read({ projectId: 'project', userId: 'anonymous-local-only' })
    read.document.base.titles[0].notes = 'durable'
    const delayed = deferred<typeof read>()
    vi.spyOn(anonymousStorage, 'applyLocal').mockReturnValueOnce(delayed.promise)
    const pending = runtime.submitAnonymous({ kind: 'title.patch', titleId: title.id, patch: { notes: 'durable' } })
    expect(onSnapshot.mock.lastCall![0]!.titles[0].notes).toBeUndefined()
    delayed.resolve(read)
    await pending
    expect(onSnapshot.mock.lastCall![0]!.titles[0].notes).toBe('durable')
    expect(deliver).not.toHaveBeenCalled()
  })

  it('reopens the same owner with pending optimism before fetching, while other owners start empty', async () => {
    const factory = new IDBFactory()
    const first = setup(factory)
    const scope = { projectId: 'project', userId: 'owner-a' }
    await first.ownerStorage.replaceBase(scope, snapshot())
    await first.ownerStorage.append(createCommand(scope, { kind: 'title.patch', titleId: title.id, patch: { notes: 'queued' } }))
    await first.ownerStorage.close()
    const reopened = setup(factory)
    await reopened.runtime.activate('owner-a')
    expect(reopened.onSnapshot.mock.lastCall![0]!.titles[0].notes).toBe('queued')
    expect(reopened.fetchBase).not.toHaveBeenCalled()
    expect(reopened.runtime.canReconcile).toBe(false)
    const switching = reopened.runtime.activate('owner-b')
    expect(reopened.onSnapshot.mock.lastCall![0]).toBeNull()
    await switching
    expect(reopened.onSnapshot.mock.lastCall![0]!.titles).toEqual([])
  })

  it('keeps anonymous work separate and never delivers it as an authenticated owner', async () => {
    const { runtime, onSnapshot, deliver } = setup()
    await runtime.saveAnonymous(snapshot())
    await runtime.activate('owner-a')
    expect(onSnapshot.mock.lastCall![0]!.titles).toEqual([])
    await runtime.flush()
    expect(deliver).not.toHaveBeenCalled()
    await runtime.loadAnonymous()
    expect(onSnapshot.mock.lastCall![0]!.titles).toEqual([title])
  })

  it('ignores delayed anonymous hydration after login', async () => {
    const { runtime, anonymousStorage, onSnapshot } = setup()
    const read = await anonymousStorage.read({ projectId: 'project', userId: 'anonymous-local-only' })
    read.document.base = snapshot()
    read.document.revision = 1
    const delayed = deferred<typeof read>()
    vi.spyOn(anonymousStorage, 'read').mockReturnValueOnce(delayed.promise)
    const anonymousLoad = runtime.loadAnonymous()
    await runtime.activate('owner-b')
    delayed.resolve(read)
    await anonymousLoad
    expect(onSnapshot.mock.lastCall![0]!.titles).toEqual([])
  })

  it('retains cached data when refresh fails, and fences an old successful refresh on account switch', async () => {
    const { runtime, ownerStorage, fetchBase, onSnapshot } = setup()
    await ownerStorage.replaceBase({ projectId: 'project', userId: 'owner-a' }, snapshot())
    await runtime.activate('owner-a')
    fetchBase.mockRejectedValueOnce(new Error('pins unavailable'))
    await expect(runtime.refresh()).rejects.toThrow('pins unavailable')
    expect(onSnapshot.mock.lastCall![0]!.titles).toEqual([title])
    const delayed = deferred<OfflineSnapshot>()
    fetchBase.mockReturnValueOnce(delayed.promise)
    const refresh = runtime.refresh()
    await vi.waitFor(() => expect(fetchBase).toHaveBeenCalledTimes(2))
    await runtime.activate('owner-b')
    delayed.resolve(snapshot())
    await refresh
    expect(onSnapshot.mock.lastCall![0]!.titles).toEqual([])
    expect((await ownerStorage.read({ projectId: 'project', userId: 'owner-b' })).document.base.titles).toEqual([])
  })
})

describe('safe cache migration and strict owner refresh', () => {
  it('carries presentation preferences only and preserves the raw unknown-owner cache', () => {
    const raw = JSON.stringify({ state: { theme: 'noir', titles: [title], outings: [], ledgerPrefs: { widgets: [] }, user: { id: 'unknown' }, navPrefs: { order: ['bogus'], hidden: ['bogus'], compact: true } } })
    const storage = { getItem: vi.fn(() => raw) }
    const prefs = readLegacyDevicePreferences(storage)
    expect(prefs).toMatchObject({ theme: 'noir', navPrefs: { hidden: [], compact: true } })
    expect(prefs).not.toHaveProperty('titles')
    expect(prefs).not.toHaveProperty('ledgerPrefs')
    expect(storage.getItem()).toBe(raw)
    expect(pickDevicePreferences({ theme: 'invalid', user: { id: 'a' }, titles: [title] })).toEqual({})
  })

  it('rejects a partial snapshot when pins fail instead of clearing that domain', async () => {
    vi.mocked(fetchUserLibrary).mockResolvedValue({ titles: [title], outings: [] })
    vi.mocked(fetchLists).mockResolvedValue([])
    vi.mocked(fetchListMemberships).mockResolvedValue({})
    vi.mocked(fetchLedgerLayout).mockResolvedValue(null)
    vi.mocked(fetchAllTitlePins).mockRejectedValue(new Error('pins unavailable'))
    await expect(fetchOwnerSnapshot({ scope: { projectId: 'project', userId: 'owner' }, signal: new AbortController().signal, isCurrent: () => true })).rejects.toThrow('pins unavailable')
  })

  it('includes private preferences only in the owner snapshot and fences late results', async () => {
    vi.mocked(fetchUserLibrary).mockResolvedValue({ titles: [title], outings: [] })
    vi.mocked(fetchLists).mockResolvedValue([])
    vi.mocked(fetchListMemberships).mockResolvedValue({})
    vi.mocked(fetchLedgerLayout).mockResolvedValue(null)
    vi.mocked(fetchAllTitlePins).mockResolvedValue([])
    const context = { scope: { projectId: 'project', userId: 'owner' }, signal: new AbortController().signal, isCurrent: () => true }
    expect(await fetchOwnerSnapshot(context)).toMatchObject({ moviegoingPreferencesSupport: 'authoritative', venueNotes: [], theaterInterest: [] })
    expect(fetchOwnedMoviegoingPreferences).toHaveBeenCalledWith(context)
    let current = true
    vi.mocked(fetchOwnedMoviegoingPreferences).mockImplementationOnce(async () => { current = false; return { support: 'unsupported' } })
    await expect(fetchOwnerSnapshot({ ...context, isCurrent: () => current })).rejects.toThrow('owner changed')
    const calls = vi.mocked(fetchOwnedMoviegoingPreferences).mock.calls.length
    const { runtime } = setup()
    await runtime.loadAnonymous()
    runtime.deactivate() // Friend/shared navigation uses this path, never the owner reader.
    expect(fetchOwnedMoviegoingPreferences).toHaveBeenCalledTimes(calls)
  })
})
