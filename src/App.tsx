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
import { isSupabaseConfigured, onAuthStateChange, listFriendships } from 'src/lib/auth'
import { useShallow } from 'zustand/react/shallow'
import { useAppStore, useVisibleNavItems } from 'src/store/useAppStore'
import { ProfileModal } from 'src/components/ProfileModal'
import { parseNav, type AppView } from 'src/lib/navigation'
import { useNavigationSync } from 'src/lib/useNavigationSync'
import { applyTheme, toggleTheme } from 'src/lib/theme'
import { AppCommandPalette } from 'src/components/AppCommandPalette'
import { KeyboardShortcutsHelp } from 'src/components/KeyboardShortcutsHelp'
import { NotificationStack } from 'src/components/NotificationStack'
import { LandingScreen } from 'src/components/LandingScreen'
import { useKeyboardShortcuts } from 'src/lib/useKeyboardShortcuts'

export default function App() {
  // Smart landing unless the URL already names a view (deep link / refresh).
  const [currentView, setCurrentView] = useState<AppView>(() => {
    return parseNav(window.location.search, 'discover').view
  })

  useNavigationSync({ currentView, setCurrentView })

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
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:z-[300] focus:top-3 focus:left-3 focus:px-4 focus:py-2 focus:rounded-md focus:bg-amber focus:text-[color:var(--on-amber)] focus:font-sans focus:text-sm focus:font-medium focus:shadow-lg"
      >
        Skip to content
      </a>
      {/* Hidden-until-focused, like the skip link above — surfaces the shortcuts
          panel for keyboard and screen-reader users who can't hover-discover "?". */}
      <button
        type="button"
        onClick={() => setIsKeyboardHelpOpen(true)}
        className="sr-only focus:not-sr-only focus:absolute focus:z-[300] focus:top-3 focus:left-40 focus:px-4 focus:py-2 focus:rounded-md focus:bg-amber focus:text-[color:var(--on-amber)] focus:font-sans focus:text-sm focus:font-medium focus:shadow-lg"
      >
        Keyboard shortcuts
      </button>

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
        </>
      )}
      <AppCommandPalette onNavigate={setCurrentView} />
      <KeyboardShortcutsHelp open={isKeyboardHelpOpen} onClose={() => setIsKeyboardHelpOpen(false)} />
      <NotificationStack />
    </div>
  )
}
