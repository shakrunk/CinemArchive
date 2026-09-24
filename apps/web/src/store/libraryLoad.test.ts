import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '@supabase/supabase-js'
import { useAppStore } from './useAppStore'
import { fetchUserLibrary } from '../lib/db'

vi.mock('../lib/db', async (importOriginal) => ({
  ...await importOriginal<typeof import('../lib/db')>(),
  fetchUserLibrary: vi.fn(),
  fetchLedgerLayout: vi.fn().mockResolvedValue([]),
  fetchPinnedModes: vi.fn().mockResolvedValue({}),
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
    expect(fetchLibrary).toHaveBeenCalledTimes(1)
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
    useAppStore.setState({ isSharedView: true, viewerContext: { kind: 'friend', userId: 'friend', displayName: 'Friend' } })
    finish({ titles: [], outings: [] })
    await pending
    expect(useAppStore.getState().viewerContext.kind).toBe('friend')
    expect(useAppStore.getState().notifications).toHaveLength(0)
  })
})
