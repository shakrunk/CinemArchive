import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OfflineLibraryRuntime, fetchOwnerSnapshot, readLegacyDevicePreferences, pickDevicePreferences } from './offlineLibrary'
import { IndexedDbOfflineStore } from '../lib/offline/storage'
import { createCommand } from '../lib/offline/commands'
import { emptySnapshot, type OfflineSnapshot } from '../lib/offline/snapshot'
import { deferred, snapshot, title } from '../lib/offline/fixtures.test-support'
import { fetchAllTitlePins, fetchLedgerLayout, fetchListMemberships, fetchLists, fetchUserLibrary } from '../lib/db'

vi.mock('../lib/db', () => ({ fetchAllTitlePins: vi.fn(), fetchLedgerLayout: vi.fn(), fetchListMemberships: vi.fn(), fetchLists: vi.fn(), fetchUserLibrary: vi.fn() }))

const stores: IndexedDbOfflineStore[] = []
function setup(factory = new IDBFactory()) {
  const ownerStorage = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'owner' })
  const anonymousStorage = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'anonymous' })
  stores.push(ownerStorage, anonymousStorage)
  const onSnapshot = vi.fn<(value: OfflineSnapshot | null) => void>()
  const fetchBase = vi.fn().mockResolvedValue(emptySnapshot())
  const deliver = vi.fn().mockResolvedValue({ kind: 'success' })
  const runtime = new OfflineLibraryRuntime({ projectId: 'project', ownerStorage, anonymousStorage,
    onSnapshot, onStatus: vi.fn(), onError: vi.fn(), fetchBase, deliver,
    isAuthenticated: async () => true, lock: async (_name, work) => work(),
  })
  return { runtime, ownerStorage, anonymousStorage, onSnapshot, fetchBase, deliver }
}
afterEach(async () => { vi.restoreAllMocks(); await Promise.all(stores.splice(0).map((store) => store.close())) })

describe('owner library runtime', () => {
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
})
