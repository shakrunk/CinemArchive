import { useEffect } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { nextTransitionAt } from 'src/store/outings'

// Arms the completion timer/triggers for Cinema Outings (plan §4.3). The
// actual completion logic lives server-side in complete_due_outings — this
// hook is only responsible for *asking* at the right moments: window focus,
// the tab becoming visible again, the browser coming back online, and a
// single setTimeout re-armed for the soonest scheduled outing's ends_at.
// (The right-after-load trigger lives in loadUserLibrary itself, so it fires
// exactly once per load regardless of whether this hook has mounted yet.)
export function useOutingReconciler(): void {
  const user = useAppStore((s) => s.user)

  useEffect(() => {
    if (!user) return

    function reconcile() {
      void useAppStore.getState().reconcileOutings()
    }

    // Covers the case where outings were already loaded before this hook
    // mounted (e.g. a hot reload) — harmless/idempotent otherwise, since the
    // RPC only ever touches outings whose ends_at has actually passed.
    reconcile()

    function onVisibility() {
      if (document.visibilityState === 'visible') reconcile()
    }

    window.addEventListener('focus', reconcile)
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener('online', reconcile)

    let timer: ReturnType<typeof window.setTimeout> | undefined

    function armTimer() {
      window.clearTimeout(timer)
      const next = nextTransitionAt(useAppStore.getState().outings)
      if (!next) return
      const delay = Math.max(0, new Date(next).getTime() - Date.now())
      timer = window.setTimeout(() => {
        reconcile()
        armTimer()
      }, delay)
    }
    armTimer()

    // Re-arm whenever the outings list changes shape (a new outing scheduled,
    // one completed/cancelled/rescheduled) — nextTransitionAt may have moved.
    const unsubscribe = useAppStore.subscribe((state, prev) => {
      if (state.outings !== prev.outings) armTimer()
    })

    return () => {
      window.removeEventListener('focus', reconcile)
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('online', reconcile)
      window.clearTimeout(timer)
      unsubscribe()
    }
  }, [user])
}
