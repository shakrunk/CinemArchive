import { useState, useEffect, lazy, Suspense } from 'react'
import { Loader2 } from 'lucide-react'
import { TopBar } from 'src/components/TopBar'
import { BottomNav } from 'src/components/BottomNav'
import { isSupabaseConfigured, onAuthStateChange, listFriendships } from 'src/lib/auth'
import { useAppStore, useVisibleNavItems } from 'src/store/useAppStore'
import { ProfileModal } from 'src/components/ProfileModal'
import { parseNav, type AppView } from 'src/lib/navigation'
import { useNavigationSync } from 'src/lib/useNavigationSync'
import { useOutingReconciler } from 'src/lib/useOutingReconciler'
import { useEverTrue } from 'src/lib/useEverTrue'
import { applyTheme, toggleTheme, watchSystemTheme } from 'src/lib/theme'
import { NotificationStack } from 'src/components/NotificationStack'
import { PWAUpdateToast } from 'src/components/PWAUpdateToast'
import { LandingScreen } from 'src/components/LandingScreen'
import { useKeyboardShortcuts } from 'src/lib/useKeyboardShortcuts'
import { useSmoothScroll } from 'src/lib/useSmoothScroll'
import { useTabVisibility } from 'src/lib/useTabVisibility'

// Code-split everything that isn't part of the unauthenticated landing path
// (LandingScreen + ProfileModal stay eagerly bundled — that's the actual
// first-paint route for a new visitor). Only one of the seven views below is
// ever rendered at a time (see `currentView === '…' &&` below), so lazy
// imports mean a page load only fetches the JS for the view actually shown,
// not all seven.
const UpNext = lazy(() => import('src/views/UpNext').then((m) => ({ default: m.UpNext })))
const Library = lazy(() => import('src/views/Library').then((m) => ({ default: m.Library })))
const Ledger = lazy(() => import('src/views/Ledger').then((m) => ({ default: m.Ledger })))
const Discover = lazy(() => import('src/views/Discover').then((m) => ({ default: m.Discover })))
const Lists = lazy(() => import('src/views/Lists').then((m) => ({ default: m.Lists })))
const Profile = lazy(() => import('src/views/Profile').then((m) => ({ default: m.Profile })))
const Friends = lazy(() => import('src/views/Friends').then((m) => ({ default: m.Friends })))

// These modals/sheets are unconditionally rendered (they manage their own
// open/closed *visual* state internally, reading their store flag) rather
// than JSX-gated like the views above — so lazy-loading them alone would
// still fetch every chunk on first authed render regardless of whether
// they're ever opened. Each is instead only *mounted* once its flag has been
// true at least once (`useEverTrue`), deferring the fetch to first open, then
// left mounted so a later close still has a component to animate out.
//
// Verified this doesn't cost the entrance transition on that first open:
// Radix's `Presence` (`present: forceMount || context.open`) never renders
// `Dialog.Content` into the DOM at all while closed, so a cold first open
// already popped in directly at `data-state="open"` with nothing to
// transition from, identically whether the outer component existed
// beforehand or not (confirmed against a production preview build).
const AddTitleWorkflow = lazy(() => import('src/components/AddTitleWorkflow').then((m) => ({ default: m.AddTitleWorkflow })))
const TitleDetailDrawer = lazy(() => import('src/components/TitleDetailDrawer').then((m) => ({ default: m.TitleDetailDrawer })))
const RefreshMetadataModal = lazy(() => import('src/components/RefreshMetadataModal').then((m) => ({ default: m.RefreshMetadataModal })))
const OutingScheduleSheet = lazy(() => import('src/components/OutingScheduleSheet').then((m) => ({ default: m.OutingScheduleSheet })))
const PostShowSheet = lazy(() => import('src/components/PostShowSheet').then((m) => ({ default: m.PostShowSheet })))
const AppCommandPalette = lazy(() => import('src/components/AppCommandPalette').then((m) => ({ default: m.AppCommandPalette })))
const KeyboardShortcutsHelp = lazy(() => import('src/components/KeyboardShortcutsHelp').then((m) => ({ default: m.KeyboardShortcutsHelp })))

// Suspense fallback for a view swap — the app's existing spinner convention
// (see e.g. ProfileModal), sized to sit quietly in the content area rather
// than jumping the layout.
function ViewLoadingFallback() {
  return (
    <div className="flex items-center justify-center py-24">
      <Loader2 className="w-6 h-6 text-paper-faint animate-spin" aria-hidden="true" />
    </div>
  )
}

// Shared pill style for the accessibility toolbar's controls — subdued at rest
// so the amber focus state marks which of the revealed pills is active.
const A11Y_PILL =
  'px-4 py-2 rounded-md font-sans text-sm font-medium text-paper bg-secondary/60 transition-colors focus:outline-none focus-visible:bg-amber focus-visible:text-[color:var(--on-amber)]'

export default function App() {
  // Smart landing unless the URL already names a view (deep link / refresh).
  const [currentView, setCurrentView] = useState<AppView>(() => {
    return parseNav(window.location.search, 'discover').view
  })

  useNavigationSync({ currentView, setCurrentView })
  useOutingReconciler()
  useSmoothScroll()
  useTabVisibility()

  const [isProfileOpen, setIsProfileOpen] = useState(false)
  // Start true when Supabase isn't configured (no auth needed) so we never
  // flash the landing screen in local/mock-data mode.
  const [authChecked, setAuthChecked] = useState(!isSupabaseConfigured)
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const setUser = useAppStore((s) => s.setUser)
  const loadSharedLibrary = useAppStore((s) => s.loadSharedLibrary)
  const loadFriendLibrary = useAppStore((s) => s.loadFriendLibrary)
  const user = useAppStore((s) => s.user)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const isCommandPaletteOpen = useAppStore((s) => s.isCommandPaletteOpen)
  const closeCommandPalette = useAppStore((s) => s.closeCommandPalette)
  const openCommandPalette = useAppStore((s) => s.openCommandPalette)
  const openAddTitle = useAppStore((s) => s.openAddTitle)
  const setViewMode = useAppStore((s) => s.setViewMode)
  const isAddTitleOpen = useAppStore((s) => s.isAddTitleOpen)
  const isDetailDrawerOpen = useAppStore((s) => s.isDetailDrawerOpen)
  const isRefreshMetadataOpen = useAppStore((s) => s.isRefreshMetadataOpen)
  const isOutingScheduleOpen = useAppStore((s) => s.isOutingScheduleOpen)
  const isPostShowSheetOpen = useAppStore((s) => s.isPostShowSheetOpen)

  const [isKeyboardHelpOpen, setIsKeyboardHelpOpen] = useState(false)

  // Each lazy modal/sheet mounts (and so fetches its chunk) the first time
  // its flag goes true, then stays mounted — see the lazy() comments above.
  const addTitleEverOpened = useEverTrue(isAddTitleOpen)
  const detailDrawerEverOpened = useEverTrue(isDetailDrawerOpen)
  const refreshMetadataEverOpened = useEverTrue(isRefreshMetadataOpen)
  const outingScheduleEverOpened = useEverTrue(isOutingScheduleOpen)
  const postShowSheetEverOpened = useEverTrue(isPostShowSheetOpen)
  const commandPaletteEverOpened = useEverTrue(isCommandPaletteOpen)
  const keyboardHelpEverOpened = useEverTrue(isKeyboardHelpOpen)

  // A component without access to currentView (e.g. the detail drawer's
  // browse-by-person) requests a view change via the store. We consume it in a
  // store listener — not a synchronous setState in the effect body — so it reads
  // like the popstate handler and avoids cascading-render lint.
  useEffect(() => {
    return useAppStore.subscribe((state, prev) => {
      if (state.pendingView && state.pendingView !== prev.pendingView) {
        setCurrentView(state.pendingView)
        useAppStore.getState().requestView(null)
      }
    })
  }, [])

  // Re-sync <html data-theme> with the rehydrated store. The inline FOUC script
  // in index.html sets it before paint; this covers any post-rehydration drift.
  // Also starts live OS scheme tracking for a persisted 'system' theme mode.
  useEffect(() => {
    applyTheme(useAppStore.getState().theme)
    watchSystemTheme()
  }, [])

  // ⌘K / Ctrl+K toggles the palette from anywhere.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        const s = useAppStore.getState()
        if (s.isCommandPaletteOpen) s.closeCommandPalette()
        else s.openCommandPalette()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // ⌘, / Ctrl+, opens Settings from anywhere — the near-universal desktop
  // convention for preferences. Additive alongside the numbered nav slot
  // (visibleNav.length + 1 below), which stays as the configurable "N jumps
  // to nav slot N" system's stable-enough fallback.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key === ',') {
        e.preventDefault()
        setCurrentView('profile')
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // Single-key shortcuts — suppressed while any modal/dialog is open or focus
  // is in a text field (handled inside the hook).
  const shortcutsActive =
    !isAddTitleOpen && !isDetailDrawerOpen && !isRefreshMetadataOpen &&
    !isCommandPaletteOpen && !isKeyboardHelpOpen

  // Number-key shortcuts follow the user's nav order/visibility from Settings
  // → Navigation, so key N always jumps to whatever sits in slot N. Hidden
  // tabs get no number key; Profile always gets the next number after them.
  const visibleNav = useVisibleNavItems()
  const navShortcuts = Object.fromEntries(
    visibleNav.map((id, i) => [String(i + 1), () => setCurrentView(id)])
  )

  useKeyboardShortcuts(
    {
      ...navShortcuts,
      [String(visibleNav.length + 1)]: () => setCurrentView('profile'),
      [String(visibleNav.length + 2)]: () => setCurrentView('friends'),
      'n': () => { if (!isSharedView) openAddTitle() },
      '/': () => isCommandPaletteOpen ? closeCommandPalette() : openCommandPalette(),
      'g': () => { setCurrentView('library'); setViewMode('grid') },
      'l': () => { setCurrentView('library'); setViewMode('list') },
      't': () => toggleTheme({ clientX: window.innerWidth / 2, clientY: window.innerHeight / 2 }),
      '?': () => setIsKeyboardHelpOpen(true),
    },
    shortcutsActive,
  )

  useEffect(() => {
    if (!isSupabaseConfigured) return  // authChecked already true from initial state

    const params = new URLSearchParams(window.location.search)
    const shareToken = params.get('share')

    if (shareToken) {
      loadSharedLibrary(shareToken).then(() => setAuthChecked(true))
      return
    }

    // A friend-view deep link (?friend=<userId>) needs auth.uid() to already
    // exist for the friend-read RLS policy to apply — unlike the anonymous
    // share-link path above, so it's resolved from inside the auth callback
    // (after a user exists) rather than before subscribing.
    const friendId = params.get('friend')
    let friendResolved = false

    const subscription = onAuthStateChange((user) => {
      setUser(user)
      setAuthChecked(true)

      if (!friendId || friendResolved) return
      friendResolved = true

      if (!user) {
        // No session to back a friend view — drop the stale/shared-out param.
        const url = new URL(window.location.href)
        url.searchParams.delete('friend')
        window.history.replaceState({}, '', url.toString())
        return
      }

      listFriendships()
        .then((friendships) => {
          const match = friendships.find((f) => f.friend_user_id === friendId && f.status === 'accepted')
          if (match) {
            void loadFriendLibrary(friendId, match.display_name || match.username || 'Friend')
          } else {
            const url = new URL(window.location.href)
            url.searchParams.delete('friend')
            window.history.replaceState({}, '', url.toString())
          }
        })
        .catch((err) => console.error('Failed to resolve friend from URL:', err))
    })

    return () => {
      subscription.unsubscribe()
    }
  }, [setUser, loadSharedLibrary, loadFriendLibrary])

  return (
    <div className="relative min-h-screen">
      {/* Accessibility toolbar — parked above the viewport until either control
          gains keyboard focus, then the whole cluster slides in so the skip link
          and the shortcuts button (for keyboard/screen-reader users who can't
          hover-discover "?") read as one surface, not two stray pills (KP-042).
          Both stay in the tab order the whole time. */}
      <nav
        aria-label="Accessibility shortcuts"
        className="absolute top-3 left-3 z-[300] flex items-center gap-1.5 p-1.5 rounded-lg border border-amber/30 bg-card shadow-lg -translate-y-[200%] opacity-0 pointer-events-none transition-[transform,opacity] duration-150 focus-within:translate-y-0 focus-within:opacity-100 focus-within:pointer-events-auto"
      >
        <a href="#main-content" className={A11Y_PILL}>
          Skip to content
        </a>
        <button type="button" onClick={() => setIsKeyboardHelpOpen(true)} className={A11Y_PILL}>
          Keyboard shortcuts
        </button>
      </nav>

      {/* ── Atmosphere layers (fixed, full-viewport) ── */}
      <div className="projector-beam" aria-hidden="true" />
      <div className="dust" aria-hidden="true" />
      <div className="vignette" aria-hidden="true" />
      <div className="grain" aria-hidden="true" />

      {/* ── Landing screen for unauthenticated visitors on the live site ── */}
      {!import.meta.env.DEV && isSupabaseConfigured && authChecked && !user && !isSharedView ? (
        <>
          <LandingScreen onSignIn={() => setIsProfileOpen(true)} />
          <ProfileModal open={isProfileOpen} onClose={() => setIsProfileOpen(false)} />
        </>
      ) : (
        <>
          <TopBar
            currentView={currentView}
            onViewChange={setCurrentView}
            onProfileClick={() => setCurrentView('profile')}
          />

          <main id="main-content" key={currentView} className="animate-view-in pb-24 sm:pb-12">
            <Suspense fallback={<ViewLoadingFallback />}>
              {currentView === 'upnext' && <UpNext onBrowseLibrary={() => setCurrentView('library')} />}
              {currentView === 'library' && <Library />}
              {currentView === 'ledger' && <Ledger />}
              {currentView === 'discover' && <Discover />}
              {currentView === 'lists' && <Lists />}
              {currentView === 'profile' && <Profile />}
              {currentView === 'friends' && <Friends />}
            </Suspense>
          </main>

          <BottomNav currentView={currentView} onViewChange={setCurrentView} />
          {/* Mounted (and so fetched) once each has been opened at least once —
              see the useEverTrue calls above — then left mounted so a closing
              one still has a component in the tree to animate out. A null
              Suspense fallback is fine: these chunks are small, and it's the
              sheet/modal's own transition doing the animating, not this
              boundary. */}
          {addTitleEverOpened && (
            <Suspense fallback={null}><AddTitleWorkflow /></Suspense>
          )}
          {detailDrawerEverOpened && (
            <Suspense fallback={null}><TitleDetailDrawer /></Suspense>
          )}
          {refreshMetadataEverOpened && (
            <Suspense fallback={null}><RefreshMetadataModal /></Suspense>
          )}
          {outingScheduleEverOpened && (
            <Suspense fallback={null}><OutingScheduleSheet /></Suspense>
          )}
          {postShowSheetEverOpened && (
            <Suspense fallback={null}><PostShowSheet /></Suspense>
          )}
        </>
      )}
      {commandPaletteEverOpened && (
        <Suspense fallback={null}><AppCommandPalette onNavigate={setCurrentView} /></Suspense>
      )}
      {keyboardHelpEverOpened && (
        <Suspense fallback={null}>
          <KeyboardShortcutsHelp open={isKeyboardHelpOpen} onClose={() => setIsKeyboardHelpOpen(false)} />
        </Suspense>
      )}
      <NotificationStack />
      <PWAUpdateToast />
    </div>
  )
}
