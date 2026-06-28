const fs = require('fs');
const content = fs.readFileSync('src/App.tsx', 'utf8');

const newContent = content + `
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
        className="sr-only focus:not-sr-only focus:absolute focus:z-[300] focus:top-3 focus:left-3 focus:px-4 focus:py-2 focus:rounded-md focus:bg-amber focus:text-[color:var(--on-amber)] focus:font-sans focus:text-sm focus:font-medium focus:shadow-lg"
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
        onNavigate={setCurrentView}
      />
      <KeyboardShortcutsHelp open={isKeyboardHelpOpen} onClose={() => setIsKeyboardHelpOpen(false)} />
      <NotificationStack />
    </div>
  )
}
`;

fs.writeFileSync('src/App.tsx', newContent);
