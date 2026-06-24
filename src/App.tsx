import { useState, useEffect, useMemo } from 'react'
import { TopBar } from 'src/components/TopBar'
import { BottomNav } from 'src/components/BottomNav'
import { AddTitleWorkflow } from 'src/components/AddTitleWorkflow'
import { UpNext } from 'src/views/UpNext'
import { Library } from 'src/views/Library'
import { Ledger } from 'src/views/Ledger'
import { TitleDetailDrawer } from 'src/components/TitleDetailDrawer'
import { RefreshMetadataModal } from 'src/components/RefreshMetadataModal'
import { isSupabaseConfigured, onAuthStateChange } from 'src/lib/auth'
import { useAppStore } from 'src/store/useAppStore'
import { computeUpNextShows } from 'src/store/upNext'
import { ProfileModal } from 'src/components/ProfileModal'
import { parseNav, type AppView } from 'src/lib/navigation'
import { useNavigationSync } from 'src/lib/useNavigationSync'
import { CommandPalette } from 'src/components/CommandPalette'
import type { Command } from 'src/store/commands'

export default function App() {
  // Smart landing unless the URL already names a view (deep link / refresh).
  const [currentView, setCurrentView] = useState<AppView>(() => {
    const smart: AppView =
      computeUpNextShows(useAppStore.getState().titles).length > 0 ? 'upnext' : 'library'
    return parseNav(window.location.search, smart).view
  })

  useNavigationSync({ currentView, setCurrentView })

  const [isProfileOpen, setIsProfileOpen] = useState(false)
  const setUser = useAppStore((s) => s.setUser)
  const loadSharedLibrary = useAppStore((s) => s.loadSharedLibrary)

  const titles = useAppStore((s) => s.titles)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const isCommandPaletteOpen = useAppStore((s) => s.isCommandPaletteOpen)
  const closeCommandPalette = useAppStore((s) => s.closeCommandPalette)
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const openAddTitle = useAppStore((s) => s.openAddTitle)
  const setViewMode = useAppStore((s) => s.setViewMode)

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

  // Build the command list + an id→handler map. Title commands open the drawer
  // (which, via useNavigationSync, becomes a back-button-closable history entry).
  const { commands, runMap } = useMemo(() => {
    const list: Command[] = []
    const map: Record<string, () => void> = {}

    if (!isSharedView) {
      list.push({ id: 'action:add', kind: 'action', label: 'Add a title', hint: 'new', keywords: 'create new movie series' })
      map['action:add'] = () => openAddTitle()
    }
    list.push({ id: 'action:view-upnext', kind: 'action', label: 'Go to Up Next', hint: 'view', keywords: 'continue watching' })
    map['action:view-upnext'] = () => setCurrentView('upnext')
    list.push({ id: 'action:view-library', kind: 'action', label: 'Go to the Library', hint: 'view', keywords: 'collection posters' })
    map['action:view-library'] = () => setCurrentView('library')
    list.push({ id: 'action:view-ledger', kind: 'action', label: 'Go to the Ledger', hint: 'view', keywords: 'stats dashboard' })
    map['action:view-ledger'] = () => setCurrentView('ledger')
    list.push({ id: 'action:layout-grid', kind: 'action', label: 'Library: poster wall', hint: 'layout', keywords: 'grid posters' })
    map['action:layout-grid'] = () => { setCurrentView('library'); setViewMode('grid') }
    list.push({ id: 'action:layout-list', kind: 'action', label: 'Library: ledger list', hint: 'layout', keywords: 'list table' })
    map['action:layout-list'] = () => { setCurrentView('library'); setViewMode('list') }

    for (const t of titles) {
      const id = `title:${t.id}`
      const hint = [t.director ? `dir. ${t.director}` : t.type === 'tv' ? 'series' : 'film', t.year]
        .filter(Boolean)
        .join(' · ')
      list.push({ id, kind: 'title', label: t.title, hint, keywords: t.genres.join(' ') })
      map[id] = () => openDetailDrawer(t.id)
    }
    return { commands: list, runMap: map }
  }, [titles, isSharedView, openAddTitle, openDetailDrawer, setViewMode])

  function runCommand(cmd: Command) {
    closeCommandPalette()
    runMap[cmd.id]?.()
  }

  useEffect(() => {
    if (!isSupabaseConfigured) return

    const params = new URLSearchParams(window.location.search)
    const shareToken = params.get('share')

    if (shareToken) {
      loadSharedLibrary(shareToken)
      return
    }

    const subscription = onAuthStateChange((user) => {
      setUser(user)
    })

    return () => {
      subscription.unsubscribe()
    }
  }, [setUser, loadSharedLibrary])

  return (
    <div className="relative min-h-screen">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:z-[300] focus:top-3 focus:left-3 focus:px-4 focus:py-2 focus:rounded-md focus:bg-amber focus:text-void focus:font-sans focus:text-sm focus:font-medium focus:shadow-lg"
      >
        Skip to content
      </a>

      {/* ── Atmosphere layers (fixed, full-viewport) ── */}
      <div className="projector-beam" aria-hidden="true" />
      <div className="dust" aria-hidden="true" />
      <div className="vignette" aria-hidden="true" />
      <div className="grain" aria-hidden="true" />

      <TopBar
        currentView={currentView}
        onViewChange={setCurrentView}
        onProfileClick={() => setIsProfileOpen(true)}
      />

      <main id="main-content" key={currentView} className="animate-view-in pb-24 sm:pb-12">
        {currentView === 'upnext' && <UpNext onBrowseLibrary={() => setCurrentView('library')} />}
        {currentView === 'library' && <Library />}
        {currentView === 'ledger' && <Ledger />}
      </main>

      <BottomNav currentView={currentView} onViewChange={setCurrentView} />
      <AddTitleWorkflow />
      <TitleDetailDrawer />
      <RefreshMetadataModal />
      <ProfileModal open={isProfileOpen} onClose={() => setIsProfileOpen(false)} />
      <CommandPalette
        open={isCommandPaletteOpen}
        onClose={closeCommandPalette}
        commands={commands}
        onRun={runCommand}
      />
    </div>
  )
}
