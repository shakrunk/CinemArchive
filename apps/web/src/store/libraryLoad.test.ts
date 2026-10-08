import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '@supabase/supabase-js'
import { useAppStore } from './useAppStore'
import { fetchUserLibrary } from '../lib/db'
import { title } from '../lib/offline/fixtures.test-support'

vi.mock('../lib/offlineRpc', () => ({ createLibraryCommandDelivery: () => vi.fn() }))
vi.mock('./offlineLibrary', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./offlineLibrary')>()
  const { IDBFactory } = await import('fake-indexeddb')
  const { IndexedDbOfflineStore } = await import('../lib/offline/storage')
  const factory = new IDBFactory()
  return { ...actual, OfflineLibraryRuntime: class extends actual.OfflineLibraryRuntime {
    constructor(options: ConstructorParameters<typeof actual.OfflineLibraryRuntime>[0]) {
      super({ ...options, browserEvents: false, isAuthenticated: async () => true, lock: async (_name, work) => work(),
        ownerStorage: new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'owner-store-tests' }),
        anonymousStorage: new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'anonymous-store-tests' }),
      })
    }
  } }
})
vi.mock('../lib/db', async (importOriginal) => ({
  ...await importOriginal<typeof import('../lib/db')>(),
  fetchUserLibrary: vi.fn(),
  fetchLedgerLayout: vi.fn().mockResolvedValue([]),
  fetchPinnedModes: vi.fn().mockResolvedValue({}),
  fetchAllTitlePins: vi.fn().mockResolvedValue([]),
  fetchLists: vi.fn().mockResolvedValue([]),
  fetchListMemberships: vi.fn().mockResolvedValue({}),
  fetchUnreadNotificationCount: vi.fn().mockResolvedValue(0),
}))

const user = { id: 'owner' } as User
const fetchLibrary = vi.mocked(fetchUserLibrary)

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  useAppStore.getState().setUser(null)
  useAppStore.setState({
    user, titles: [], filteredTitles: [], outings: [], notifications: [],
    isSharedView: false, viewerContext: { kind: 'owner' }, libraryLoadError: null,
  })
  fetchLibrary.mockReset()
})

afterEach(() => {
  useAppStore.getState().setUser(null)
  vi.restoreAllMocks()
})

describe('owner library loading', () => {
  it('clears private state synchronously when switching accounts', async () => {
    fetchLibrary.mockResolvedValue({ titles: [], outings: [] })
    useAppStore.setState({ titles: [title], lists: [{ id: 'private', name: 'Private', description: null, createdAt: '2026-01-01', updatedAt: '2026-01-01' }],
      pinnedModes: { private: 'bw' }, listMemberships: { private: new Set([title.id]) } })
    useAppStore.getState().setUser({ id: 'next-owner' } as User)
    const state = useAppStore.getState()
    expect(state.titles).toEqual([])
    expect(state.lists).toEqual([])
    expect(state.listMemberships).toEqual({})
    expect(state.pinnedModes).toEqual({})
    expect(state.offlineStatus).toMatchObject({ ownerId: 'next-owner', hydrated: false, commands: [] })
    await useAppStore.getState().loadUserLibrary()
  })

  it('persists device preferences without writing private owner data to localStorage', () => {
    useAppStore.setState({ titles: [title] })
    const saved = JSON.parse(localStorage.getItem('cinemarchive-device-preferences-v1')!).state
    expect(saved).toHaveProperty('theme')
    for (const field of ['titles', 'outings', 'lists', 'listMemberships', 'ledgerPrefs', 'pinnedModes', 'user']) expect(saved).not.toHaveProperty(field)
  })

  it('updates the user without reloading on repeated auth events', () => {
    useAppStore.getState().setUser({ ...user, email: 'updated@example.com' })
    expect(useAppStore.getState().user?.email).toBe('updated@example.com')
    expect(fetchLibrary).not.toHaveBeenCalled()
  })

  it('shares an in-flight load and keeps a failed retry in one notification', async () => {
    let fail!: (reason: unknown) => void
    fetchLibrary.mockImplementationOnce(() => new Promise((_resolve, reject) => { fail = reject }))
    const first = useAppStore.getState().loadUserLibrary()
    const second = useAppStore.getState().loadUserLibrary()
    await vi.waitFor(() => expect(fetchLibrary).toHaveBeenCalledTimes(1))
    fail({ code: '57014' })
    await Promise.all([first, second])
    const notification = useAppStore.getState().notifications[0]
    expect(notification.message).toContain('timed out')
    fetchLibrary.mockRejectedValue({ code: '57014' })
    await expect(notification.retry!()).rejects.toThrow('timed out')
    expect(useAppStore.getState().notifications).toHaveLength(1)
    expect(useAppStore.getState().notifications[0].id).toBe(notification.id)
    expect(useAppStore.getState().loadingUser).toBe(false)
  })

  it('removes the warning when a retry succeeds', async () => {
    fetchLibrary.mockRejectedValueOnce({ code: '57014' })
    await useAppStore.getState().loadUserLibrary()
    const notification = useAppStore.getState().notifications[0]
    fetchLibrary.mockResolvedValueOnce({ titles: [], outings: [] })
    await notification.retry!()
    expect(useAppStore.getState().notifications).toHaveLength(0)
    expect(useAppStore.getState().libraryLoadError).toBeNull()
  })

  it('ignores an old failure after the account changes', async () => {
    let fail!: (reason: unknown) => void
    fetchLibrary.mockImplementationOnce(() => new Promise((_resolve, reject) => { fail = reject }))
    const pending = useAppStore.getState().loadUserLibrary()
    await vi.waitFor(() => expect(fetchLibrary).toHaveBeenCalledOnce())
    useAppStore.getState().setUser(null)
    fail({ code: '57014' })
    await pending
    expect(useAppStore.getState().notifications).toHaveLength(0)
    expect(useAppStore.getState().libraryLoadError).toBeNull()
    expect(useAppStore.getState().loadingUser).toBe(false)
  })

  it('does not overwrite a friend view when an owner load finishes', async () => {
    let finish!: (value: Awaited<ReturnType<typeof fetchUserLibrary>>) => void
    fetchLibrary.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
    const pending = useAppStore.getState().loadUserLibrary()
    await vi.waitFor(() => expect(fetchLibrary).toHaveBeenCalledOnce())
    useAppStore.setState({ isSharedView: true, viewerContext: { kind: 'friend', userId: 'friend', displayName: 'Friend' } })
    finish({ titles: [], outings: [] })
    await pending
    expect(useAppStore.getState().viewerContext.kind).toBe('friend')
    expect(useAppStore.getState().notifications).toHaveLength(0)
  })
})
