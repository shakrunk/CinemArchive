import { useEffect, useRef } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { parseNav, serializeNav, preservedParams, type AppView, type NavState } from './navigation'

// Bridges the URL <-> (App currentView + store drawer/add flags) without feedback
// loops. Modal opens push a history entry so the browser/mobile back button closes
// them; everything else replaces. A push-depth counter lets close transitions pop
// the entry we added (instead of leaving stale entries), while never calling back()
// when there is no entry of ours to pop (which would leave the app).
export function useNavigationSync({
  currentView,
  setCurrentView,
}: {
  currentView: AppView
  setCurrentView: (v: AppView) => void
}): void {
  const selectedTitleId = useAppStore((s) => s.selectedTitleId)
  const isDetailDrawerOpen = useAppStore((s) => s.isDetailDrawerOpen)
  const isAddTitleOpen = useAppStore((s) => s.isAddTitleOpen)

  const drawerTitle = isDetailDrawerOpen ? selectedTitleId : null
  const desired: NavState = { view: currentView, title: drawerTitle, add: isAddTitleOpen }

  const prevRef = useRef<NavState | null>(null)
  const pushDepth = useRef(0)
  const stateToUrlInit = useRef(false)
  const didInit = useRef(false)
  // Keep the latest view available to the popstate handler without re-subscribing
  // the listener. Updated in an effect (not during render) per the refs lint rule.
  const viewRef = useRef(currentView)
  useEffect(() => { viewRef.current = currentView })

  // ── Initial mount: open whatever modal the URL names (deep link / refresh). ──
  // Does NOT write history; the state->URL effect's first pass leaves the URL as-is.
  useEffect(() => {
    if (didInit.current) return
    didInit.current = true
    const s = useAppStore.getState()
    const fromUrl = parseNav(window.location.search, viewRef.current)
    if (fromUrl.view !== viewRef.current) setCurrentView(fromUrl.view)
    if (fromUrl.title) {
      if (!s.isDetailDrawerOpen) s.openDetailDrawer(fromUrl.title)
    } else if (fromUrl.add) {
      if (!s.isAddTitleOpen) s.openAddTitle()
    }
  }, [setCurrentView])

  // ── popstate: URL drives state (this is what makes Back close the drawer) ──
  useEffect(() => {
    function onPop() {
      const s = useAppStore.getState()
      const fromUrl = parseNav(window.location.search, viewRef.current)
      setCurrentView(fromUrl.view)
      if (fromUrl.title) {
        if (!s.isDetailDrawerOpen || s.selectedTitleId !== fromUrl.title) s.openDetailDrawer(fromUrl.title)
      } else if (s.isDetailDrawerOpen) {
        s.closeDetailDrawer()
      }
      if (fromUrl.add) {
        if (!s.isAddTitleOpen) s.openAddTitle()
      } else if (s.isAddTitleOpen) {
        s.closeAddTitle()
      }
      // A pop consumed one of our pushed entries (if any).
      if (pushDepth.current > 0) pushDepth.current -= 1
      prevRef.current = { view: fromUrl.view, title: fromUrl.title, add: fromUrl.add }
    }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [setCurrentView])

  // ── state -> URL ──
  useEffect(() => {
    // First pass after mount: seed prevRef from the URL (the source of truth) and
    // write nothing, so a deep-linked ?title=/?add= survives until the init effect
    // opens it and state catches up.
    if (!stateToUrlInit.current) {
      stateToUrlInit.current = true
      prevRef.current = parseNav(window.location.search, desired.view)
      return
    }

    const preserved = preservedParams(window.location.search)
    const currentNav = parseNav(window.location.search, desired.view)
    const sameAsUrl =
      currentNav.view === desired.view &&
      currentNav.title === desired.title &&
      currentNav.add === desired.add
    if (sameAsUrl) {
      prevRef.current = desired
      return
    }

    const prev = prevRef.current
    const nextUrl = serializeNav(desired, preserved)
    const wasModal = prev ? !!prev.title || prev.add : false
    const isModal = !!desired.title || desired.add

    if (prev === null) {
      // Defensive: normalize without adding history.
      window.history.replaceState({}, '', nextUrl)
    } else if (!wasModal && isModal) {
      window.history.pushState({}, '', nextUrl)
      pushDepth.current += 1
    } else if (wasModal && !isModal && pushDepth.current > 0) {
      // Closing a modal we pushed: pop it so history stays clean. popstate's
      // handler then reconciles (state already matches → no-op) and decrements.
      window.history.back()
      prevRef.current = desired
      return
    } else {
      window.history.replaceState({}, '', nextUrl)
    }
    prevRef.current = desired
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [desired.view, desired.title, desired.add])
}
