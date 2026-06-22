import { useState, useEffect } from 'react'
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

type AppView = 'upnext' | 'library' | 'ledger'

export default function App() {
  // Smart landing: open Up Next when shows are in progress, else Library.
  // Computed once from the synchronously-rehydrated persisted titles.
  const [currentView, setCurrentView] = useState<AppView>(() =>
    computeUpNextShows(useAppStore.getState().titles).length > 0 ? 'upnext' : 'library'
  )
  const [isProfileOpen, setIsProfileOpen] = useState(false)
  const setUser = useAppStore((s) => s.setUser)
  const loadSharedLibrary = useAppStore((s) => s.loadSharedLibrary)

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
    </div>
  )
}
