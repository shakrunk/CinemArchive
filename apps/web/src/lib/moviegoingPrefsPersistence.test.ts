import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Offline and signed-out edits survive a reload and are replayed by the next reconcile
// (docs/android-contracts/moviegoing-prefs.md, "Sync rules").

const fetchRemote = vi.fn<(userId: string) => Promise<unknown>>()
const saveRemote = vi.fn<(userId: string, prefs: unknown) => Promise<void>>()

vi.mock('./db', () => ({
  fetchMoviegoingPrefs: (userId: string) => fetchRemote(userId),
  saveMoviegoingPrefs: (userId: string, prefs: unknown) => saveRemote(userId, prefs),
}))
vi.mock('./auth', async (importOriginal) => ({ ...(await importOriginal<typeof import('./auth')>()), isSupabaseConfigured: true }))

const ALICE = '11111111-1111-4111-8111-111111111111'
const BOB = '22222222-2222-4222-8222-222222222222'

/** A fresh copy of the module, as after a page reload: nothing in memory, `started` unset. */
async function launch(userId: string | null) {
  vi.resetModules()
  const store = await import('src/store/useAppStore')
  store.useAppStore.setState({ user: userId ? ({ id: userId, email: `${userId}@x.test` } as never) : null })
  const sync = await import('./moviegoingPrefsSync')
  sync.startMoviegoingPrefsSync()
  // Let the launch-time reconcile (fetch, decide, maybe push) settle.
  await new Promise((resolve) => setTimeout(resolve, 20))
  return { store: store.useAppStore, sync }
}

const remoteBudget = (amount: number, updatedAt: string) => ({ budget: { amount, period: 'month' }, updatedAt })

beforeEach(() => {
  localStorage.clear()
  fetchRemote.mockReset().mockResolvedValue(null)
  saveRemote.mockReset().mockResolvedValue(undefined)
})
afterEach(() => vi.useRealTimers())

describe('moviegoing prefs persistence (#392)', () => {
  it('keeps an edit made offline across a reload and replays it when the server is reachable', async () => {
    fetchRemote.mockRejectedValue(new Error('offline'))
    saveRemote.mockRejectedValue(new Error('offline'))
    const first = await launch(ALICE)
    await first.sync.setMoviegoingBudget({ amount: 40, period: 'month' })
    expect(first.sync.useMoviegoingPrefs.getState().prefs?.budget).toEqual({ amount: 40, period: 'month' })
    expect(localStorage.getItem(first.sync.storageKey(ALICE))).toContain('"amount":40')

    // Reload while still offline: the edit is there, nothing was lost or pushed.
    const second = await launch(ALICE)
    expect(second.sync.useMoviegoingPrefs.getState().prefs?.budget).toEqual({ amount: 40, period: 'month' })

    // Back online with an older remote copy: the newer local edit is pushed, not overwritten.
    fetchRemote.mockReset().mockResolvedValue(remoteBudget(25, '2020-01-01T00:00:00Z'))
    saveRemote.mockReset().mockResolvedValue(undefined)
    await second.sync.reconcileMoviegoingPrefs()
    expect(saveRemote).toHaveBeenCalledTimes(1)
    expect(saveRemote.mock.calls[0][0]).toBe(ALICE)
    expect(saveRemote.mock.calls[0][1]).toMatchObject({ budget: { amount: 40, period: 'month' } })
    expect(second.sync.useMoviegoingPrefs.getState().prefs?.budget?.amount).toBe(40)
  })

  it('replays a stored edit on the first launch after it was made', async () => {
    const first = await launch(ALICE)
    saveRemote.mockRejectedValue(new Error('offline'))
    await first.sync.setMoviegoingRegion('GB')
    saveRemote.mockReset().mockResolvedValue(undefined)
    fetchRemote.mockResolvedValue(null)
    await launch(ALICE)
    await vi.waitFor(() => expect(saveRemote).toHaveBeenCalled())
    expect(saveRemote.mock.calls[0][1]).toMatchObject({ region: 'GB' })
  })

  it('a newer remote copy replaces an older stored one, and is stored in turn', async () => {
    const first = await launch(ALICE)
    await first.sync.setMoviegoingBudget({ amount: 40, period: 'month' })
    fetchRemote.mockResolvedValue(remoteBudget(99, '2999-01-01T00:00:00Z'))
    const second = await launch(ALICE)
    await vi.waitFor(() => expect(second.sync.useMoviegoingPrefs.getState().prefs?.budget?.amount).toBe(99))
    expect(localStorage.getItem(second.sync.storageKey(ALICE))).toContain('"amount":99')
  })

  it('keeps signed-out edits on this device only and never carries them into an account', async () => {
    const out = await launch(null)
    await out.sync.setMoviegoingBudget({ amount: 15, period: 'year' })
    expect(saveRemote).not.toHaveBeenCalled()
    const reloaded = await launch(null)
    expect(reloaded.sync.useMoviegoingPrefs.getState().prefs?.budget).toEqual({ amount: 15, period: 'year' })

    fetchRemote.mockResolvedValue(null)
    const alice = await launch(ALICE)
    expect(alice.sync.useMoviegoingPrefs.getState().prefs).toBeNull()
    expect(saveRemote).not.toHaveBeenCalled()
  })

  it('never shows or pushes one account\'s copy to another', async () => {
    const alice = await launch(ALICE)
    await alice.sync.setMoviegoingBudget({ amount: 40, period: 'month' })
    saveRemote.mockClear()
    const bob = await launch(BOB)
    expect(bob.sync.useMoviegoingPrefs.getState().prefs).toBeNull()
    expect(saveRemote).not.toHaveBeenCalled()
  })

  it('swaps the in-memory copy when the account changes in the same session', async () => {
    const alice = await launch(ALICE)
    await alice.sync.setMoviegoingBudget({ amount: 40, period: 'month' })
    alice.store.setState({ user: { id: BOB, email: 'b@x.test' } as never })
    expect(alice.sync.useMoviegoingPrefs.getState().prefs).toBeNull()
    alice.store.setState({ user: { id: ALICE, email: 'a@x.test' } as never })
    expect(alice.sync.useMoviegoingPrefs.getState().prefs?.budget?.amount).toBe(40)
  })

  it('ignores a damaged stored copy and still lets edits through', async () => {
    const probe = await launch(ALICE)
    localStorage.setItem(probe.sync.storageKey(ALICE), '{not json')
    const again = await launch(ALICE)
    expect(again.sync.useMoviegoingPrefs.getState().prefs).toBeNull()
    await again.sync.setMoviegoingBudget({ amount: 10, period: 'month' })
    expect(again.sync.readStoredMoviegoingPrefs(ALICE)?.budget?.amount).toBe(10)
  })

  it('keeps unknown keys a newer client wrote through a stored round trip', async () => {
    fetchRemote.mockResolvedValue({ ...remoteBudget(30, '2999-01-01T00:00:00Z'), future: { x: 1 } })
    const first = await launch(ALICE)
    await vi.waitFor(() => expect(first.sync.useMoviegoingPrefs.getState().prefs?.budget?.amount).toBe(30))
    expect(first.sync.readStoredMoviegoingPrefs(ALICE)?.raw).toMatchObject({ future: { x: 1 } })
  })
})
