import { useEffect } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { isSupabaseConfigured } from 'src/lib/auth'
import { isDevMockUser } from 'src/lib/devAuth'
import { runEpisodeScheduleRefresh } from 'src/lib/episodeSchedule'

const LOCK_NAME = 'cinemarchive-episode-schedule'
/** Minimum gap between passes in one tab; each pass checks one batch of series. */
const PASS_GAP_MS = 15 * 60 * 1000
const storageKey = (userId: string) => `cinemarchive:episode-schedule-checked:${userId}`

function readCheckedAt(userId: string): Record<string, number> {
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(storageKey(userId)) ?? '{}')
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    return Object.fromEntries(Object.entries(parsed).filter(([, at]) => typeof at === 'number'))
  } catch {
    return {}
  }
}

function writeCheckedAt(userId: string, checkedAt: Record<string, number>): void {
  try {
    localStorage.setItem(storageKey(userId), JSON.stringify(checkedAt))
  } catch {
    // Blocked storage only means series get re-checked on the next open.
  }
}

// Keeps upcoming episode air dates current for the owner's tracked series
// (see episodeSchedule.ts). Runs once the owner library has loaded, and again
// when the tab becomes visible or the browser comes back online — each series
// is checked at most once a day, so the extra triggers are cheap no-ops.
export function useEpisodeScheduleRefresh(): void {
  const user = useAppStore((s) => s.user)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const session = useAppStore((s) => s.librarySession)

  useEffect(() => {
    if (!isSupabaseConfigured || !user || isDevMockUser(user) || isSharedView) return
    const userId = user.id
    let running = false
    let lastPass = 0
    let disposed = false

    const isCurrent = () => {
      const s = useAppStore.getState()
      return !disposed && s.user?.id === userId && s.librarySession === session && !s.isSharedView && s.viewerContext.kind === 'owner'
    }
    // Wait for this session's server snapshot, not just the local cache: the
    // commands carry each row's last-known revision, and a stale one would be
    // rejected as a conflict the user then has to review.
    const ready = () => {
      const { offlineStatus, loadingUser, libraryLoadError } = useAppStore.getState()
      return navigator.onLine && !loadingUser && !libraryLoadError && offlineStatus.hydrated &&
        offlineStatus.commands.length === 0 && offlineStatus.quarantined.length === 0
    }

    async function pass() {
      await runEpisodeScheduleRefresh({
        getTitles: () => useAppStore.getState().titles,
        isCurrent,
        readCheckedAt: () => readCheckedAt(userId),
        writeCheckedAt: (checkedAt) => writeCheckedAt(userId, checkedAt),
        submit: (titleId, build) => useAppStore.getState().applyCatalogRefresh(titleId, build),
      })
    }

    async function refresh() {
      if (running || Date.now() - lastPass < PASS_GAP_MS || !isCurrent() || !ready()) return
      running = true
      lastPass = Date.now()
      try {
        // One tab at a time; a tab that finds the lock held just skips.
        if (navigator.locks) await navigator.locks.request(LOCK_NAME, { ifAvailable: true }, (lock) => lock ? pass() : undefined)
        else await pass()
      } catch (error) {
        console.warn('Episode schedule refresh failed:', error)
      } finally {
        running = false
      }
    }

    void refresh()
    // The library is usually still loading on mount: try again once it lands.
    const unsubscribe = useAppStore.subscribe((state, prev) => {
      if (lastPass === 0 && (state.loadingUser !== prev.loadingUser || state.offlineStatus !== prev.offlineStatus) && ready()) void refresh()
    })
    function onVisibility() {
      if (document.visibilityState === 'visible') void refresh()
    }
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('online', refresh)

    return () => {
      disposed = true
      unsubscribe()
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('online', refresh)
    }
  }, [user, isSharedView, session])
}
