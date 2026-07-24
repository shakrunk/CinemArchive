import { useState, useEffect } from 'react'
import { TopBar } from 'src/components/TopBar'
import { BottomNav } from 'src/components/BottomNav'
import { AddTitleWorkflow } from 'src/components/AddTitleWorkflow'
import { UpNext } from 'src/views/UpNext'
import { Library } from 'src/views/Library'
import { Ledger } from 'src/views/Ledger'
import { Discover } from 'src/views/Discover'
import { Profile } from 'src/views/Profile'
import { Friends } from 'src/views/Friends'
import { TitleDetailDrawer } from 'src/components/TitleDetailDrawer'
import { RefreshMetadataModal } from 'src/components/RefreshMetadataModal'
import { OutingScheduleSheet } from 'src/components/OutingScheduleSheet'
import { PostShowSheet } from 'src/components/PostShowSheet'
import { isSupabaseConfigured, onAuthStateChange, listFriendships } from 'src/lib/auth'
import { useShallow } from 'zustand/react/shallow'
import { useAppStore, useVisibleNavItems } from 'src/store/useAppStore'
import { ProfileModal } from 'src/components/ProfileModal'
import { parseNav, type AppView } from 'src/lib/navigation'
import { useNavigationSync } from 'src/lib/useNavigationSync'
import { useOutingReconciler } from 'src/lib/useOutingReconciler'
import { applyTheme, toggleTheme } from 'src/lib/theme'
import { AppCommandPalette } from 'src/components/AppCommandPalette'
import { KeyboardShortcutsHelp } from 'src/components/KeyboardShortcutsHelp'
import { NotificationStack } from 'src/components/NotificationStack'
import { PWAUpdateToast } from 'src/components/PWAUpdateToast'
import { LandingScreen } from 'src/components/LandingScreen'
import { useKeyboardShortcuts } from 'src/lib/useKeyboardShortcuts'

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

  const [isProfileOpen, setIsProfileOpen] = useState(false)
  // Start true when Supabase isn't configured (no auth needed) so we never
  // flash the landing screen in local/mock-data mode.
  const [authChecked, setAuthChecked] = useState(!isSupabaseConfigured)
  // ⚡ Bolt: Batch Zustand selectors to reduce store subscriptions
  const {
    setUser,
    loadSharedLibrary,
    loadFriendLibrary,
    user,
    isSharedView,
    isCommandPaletteOpen,
    closeCommandPalette,
    openCommandPalette,
    openAddTitle,
    setViewMode,
    isAddTitleOpen,
    isDetailDrawerOpen,
    isRefreshMetadataOpen,
  } = useAppStore(
    useShallow((s) => ({
      setUser: s.setUser,
      loadSharedLibrary: s.loadSharedLibrary,
      loadFriendLibrary: s.loadFriendLibrary,
      user: s.user,
      isSharedView: s.isSharedView,
      isCommandPaletteOpen: s.isCommandPaletteOpen,
      closeCommandPalette: s.closeCommandPalette,
      openCommandPalette: s.openCommandPalette,
      openAddTitle: s.openAddTitle,
      setViewMode: s.setViewMode,
      isAddTitleOpen: s.isAddTitleOpen,
      isDetailDrawerOpen: s.isDetailDrawerOpen,
      isRefreshMetadataOpen: s.isRefreshMetadataOpen,
    }))
  )

  const [isKeyboardHelpOpen, setIsKeyboardHelpOpen] = useState(false)

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
  useEffect(() => {
    applyTheme(useAppStore.getState().theme)
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
            {currentView === 'upnext' && <UpNext onBrowseLibrary={() => setCurrentView('library')} />}
            {currentView === 'library' && <Library />}
            {currentView === 'ledger' && <Ledger />}
            {currentView === 'discover' && <Discover />}
            {currentView === 'profile' && <Profile />}
            {currentView === 'friends' && <Friends />}
          </main>

          <BottomNav currentView={currentView} onViewChange={setCurrentView} />
          <AddTitleWorkflow />
          <TitleDetailDrawer />
          <RefreshMetadataModal />
          <OutingScheduleSheet />
          <PostShowSheet />
        </>
      )}
      <AppCommandPalette onNavigate={setCurrentView} />
      <KeyboardShortcutsHelp open={isKeyboardHelpOpen} onClose={() => setIsKeyboardHelpOpen(false)} />
      <NotificationStack />
      <PWAUpdateToast />
    </div>
  )
}
