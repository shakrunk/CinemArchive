import { Plus, LogIn, Search, Sun, Moon, X, Eye, Users } from 'lucide-react'
import { useAppStore, useVisibleNavItems } from 'src/store/useAppStore'
import { cn, modKey } from 'src/lib/utils'
import { isSupabaseConfigured } from 'src/lib/auth'
import { DEV_MOCK_USER } from 'src/lib/devAuth'
import { toggleTheme } from 'src/lib/theme'
import { resolveNavIcon } from 'src/lib/navIcons'
import type { AppView, NavItemId } from 'src/lib/navigation'
import { ReelMark } from 'src/components/ui/reel-mark'
import { NotificationCenter } from 'src/components/NotificationCenter'
import { AccountMenu } from 'src/components/AccountMenu'

interface TopBarProps {
  currentView: AppView
  onViewChange: (view: AppView) => void
  onProfileClick: () => void
}

// Longer copy than BottomNav.tsx's — has room for full labels (see navigation.ts).
const NAV_LABELS: Record<NavItemId, string> = {
  discover: 'Discover',
  library: 'The Library',
  upnext: 'Up Next',
  ledger: 'The Ledger',
}

export function TopBar({ currentView, onViewChange, onProfileClick }: TopBarProps) {
  // ⚡ Bolt: Prevent unnecessary object allocation and shallow diffing by using atomic selectors
  const viewMode = useAppStore((s) => s.viewMode)
  const openAddTitle = useAppStore((s) => s.openAddTitle)
  const user = useAppStore((s) => s.user)
  const setUser = useAppStore((s) => s.setUser)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const viewerContext = useAppStore((s) => s.viewerContext)
  const exitFriendView = useAppStore((s) => s.exitFriendView)
  const openCommandPalette = useAppStore((s) => s.openCommandPalette)
  const theme = useAppStore((s) => s.theme)
  const navPrefs = useAppStore((s) => s.navPrefs)
  const friendView = viewerContext.kind === 'friend' ? viewerContext : null
  // Theme lives in AccountMenu once it's shown, but AccountMenu only renders
  // for an authenticated owner — everyone else (signed out, shared/friend view)
  // still needs a standalone toggle.
  const showAccountMenu = isSupabaseConfigured && !isSharedView && !!user

  function handleExitSharedLink() {
    // An anonymous shared-link session never authenticated, so there's no
    // client state to reset (unlike exitFriendView) — a clean reload into the
    // normal auth flow is the simplest correct exit.
    const url = new URL(window.location.href)
    url.searchParams.delete('share')
    window.location.href = url.toString()
  }

  const visibleNav = useVisibleNavItems()

  return (
    <header
      className="sticky top-0 z-[200] border-b"
      style={{
        borderColor: 'var(--line)',
        background:
          'linear-gradient(180deg, rgb(var(--void-rgb) / 0.97), rgb(var(--void-rgb) / 0.86))',
        backdropFilter: 'blur(14px) saturate(1.1)',
        WebkitBackdropFilter: 'blur(14px) saturate(1.1)',
      }}
    >
      <div className="max-w-[1500px] mx-auto flex items-center gap-3 sm:gap-6 px-4 sm:px-8 py-3.5">
        {/* Brand */}
        <div className="flex items-center gap-3 shrink-0 select-none">
          <ReelMark className="w-[30px] h-[30px] text-amber animate-spin-slow drop-shadow-[0_0_10px_rgb(var(--amber-rgb)/0.5)]" />
          {/* xl: — the word mark competes with the pill nav for width well past the
              tablet sizes KP-033 first addressed; below xl only the reel mark shows
              (KP-043). */}
          <div className="hidden xl:flex flex-col leading-[1.05]">
            <span
              className="font-serif text-xl font-semibold text-paper tracking-tight"
              style={{ fontVariationSettings: '"opsz" 40' }}
            >
              CinemArchive
            </span>
            <span className="font-mono text-[9.5px] tracking-[0.34em] uppercase text-amber-deep mt-[3px]">
              a private film archive
            </span>
          </div>
        </div>

        {/* Pill nav */}
        <nav className="navpill ml-1 hidden sm:flex" aria-label="Main navigation">
          {visibleNav.map((id) => {
            const label = NAV_LABELS[id]
            const Icon = resolveNavIcon(id, viewMode)
            return (
              <button
                key={id}
                aria-current={currentView === id ? 'page' : undefined}
                onClick={() => onViewChange(id)}
                className={cn('navtab', currentView === id && 'is-active')}
                title={navPrefs.compact ? label : undefined}
              >
                <Icon className="w-4 h-4" />
                <span className={cn('whitespace-nowrap', navPrefs.compact && 'sr-only')}>{label}</span>
              </button>
            )
          })}
        </nav>

        {/* Actions */}
        <div className="flex items-center gap-2 ml-auto shrink-0">
          {!showAccountMenu && (
            <button
              onClick={(e) => toggleTheme({ clientX: e.clientX, clientY: e.clientY })}
              aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
              title={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
              className="icon-btn w-9 h-9 border rounded-md text-paper-dim hover:text-amber transition-colors flex items-center justify-center focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
              style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
            >
              {theme === 'dark' ? (
                <Sun className="w-[17px] h-[17px]" />
              ) : (
                <Moon className="w-[17px] h-[17px]" />
              )}
            </button>
          )}

          <button
            onClick={openCommandPalette}
            aria-label="Search (open command palette)"
            className="icon-btn h-9 border rounded-md text-paper-dim hover:text-amber transition-colors flex items-center gap-2 px-2.5 sm:px-3 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
            style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
          >
            <Search className="w-[17px] h-[17px]" />
            {/* xl: — the label + shortcut crowd the bar on laptop widths; below xl the
                icon alone carries it (KP-032). */}
            <span className="hidden xl:inline font-sans text-[13px] text-paper-faint">Search</span>
            <kbd
              className="hidden xl:inline font-mono text-[10px] tracking-[0.06em] text-paper-faint border rounded px-1.5 py-0.5"
              style={{ borderColor: 'var(--line)' }}
            >
              {modKey}K
            </kbd>
          </button>

          {friendView ? (
            <button
              onClick={exitFriendView}
              className="icon-btn h-9 border rounded-md text-amber border-amber/30 bg-amber/5 hover:bg-amber/10 transition-colors flex items-center gap-1.5 px-2.5 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
              aria-label={`Exit ${friendView.displayName}'s library`}
              title={`Viewing ${friendView.displayName}'s library — click to exit`}
            >
              <Users className="w-[15px] h-[15px]" />
              <span className="hidden sm:inline font-sans text-[12px] truncate max-w-[140px]">{friendView.displayName}</span>
              <X className="w-[13px] h-[13px]" />
            </button>
          ) : viewerContext.kind === 'shared-link' ? (
            <button
              onClick={handleExitSharedLink}
              className="icon-btn h-9 border rounded-md text-amber border-amber/30 bg-amber/5 hover:bg-amber/10 transition-colors flex items-center gap-1.5 px-2.5 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
              aria-label="Exit shared view"
              title="Viewing a shared, read-only link — click to exit"
            >
              <Eye className="w-[15px] h-[15px]" />
              <span className="hidden sm:inline font-sans text-[12px]">Shared view</span>
              <X className="w-[13px] h-[13px]" />
            </button>
          ) : (
            isSupabaseConfigured && !isSharedView && (
              <>
                {user && <NotificationCenter onNavigate={onViewChange} />}
                {showAccountMenu ? (
                  <AccountMenu currentView={currentView} onNavigate={onViewChange} />
                ) : (
                  <button
                    onClick={import.meta.env.DEV ? () => setUser(DEV_MOCK_USER) : onProfileClick}
                    className="icon-btn h-9 border rounded-md text-paper-faint border-[var(--line)] hover:text-amber hover:border-amber/30 transition-colors flex items-center gap-1.5 px-2.5 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
                    aria-label="Sign in"
                    title={import.meta.env.DEV ? 'Dev mode: sign in instantly with a mock session' : undefined}
                  >
                    <LogIn className="w-[15px] h-[15px]" />
                    <span className="hidden sm:inline font-sans text-[12px]">Sign in</span>
                  </button>
                )}
              </>
            )
          )}

          {!isSharedView && (
            <button
              onClick={openAddTitle}
              aria-label="Add Title"
              className="btn-amber hidden sm:inline-flex items-center gap-2 rounded-md px-4 py-2 text-[13px] font-bold"
            >
              <Plus className="w-4 h-4" />
              <span className="hidden sm:inline">Add Title</span>
            </button>
          )}
        </div>
      </div>
    </header>
  )
}
