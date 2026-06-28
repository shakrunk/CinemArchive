import { Plus, LayoutGrid, List, BarChart3, User, LogIn, PlayCircle, Search, Sun, Moon } from 'lucide-react'
import { useShallow } from 'zustand/react/shallow'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { isSupabaseConfigured } from 'src/lib/auth'
import { toggleTheme } from 'src/lib/theme'

interface TopBarProps {
  currentView: 'upnext' | 'library' | 'ledger'
  onViewChange: (view: 'upnext' | 'library' | 'ledger') => void
  onProfileClick: () => void
}

/** Spinning film-reel brand mark (matches The Projection Room). */
function ReelMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="2" />
      <circle cx="12" cy="6.5" r="1.4" />
      <circle cx="12" cy="17.5" r="1.4" />
      <circle cx="6.5" cy="12" r="1.4" />
      <circle cx="17.5" cy="12" r="1.4" />
    </svg>
  )
}

const NAV: { id: 'upnext' | 'library' | 'ledger'; label: string; Icon: typeof BarChart3 }[] = [
  { id: 'upnext', label: 'Up Next', Icon: PlayCircle },
  { id: 'library', label: 'The Library', Icon: LayoutGrid },
  { id: 'ledger', label: 'The Ledger', Icon: BarChart3 },
]

export function TopBar({ currentView, onViewChange, onProfileClick }: TopBarProps) {
  // ⚡ Bolt: Prevent unnecessary re-renders by using useShallow
  const { viewMode, setViewMode, openAddTitle, user, isSharedView, openCommandPalette, theme } = useAppStore(
    useShallow((s) => ({
      viewMode: s.viewMode,
      setViewMode: s.setViewMode,
      openAddTitle: s.openAddTitle,
      user: s.user,
      isSharedView: s.isSharedView,
      openCommandPalette: s.openCommandPalette,
      theme: s.theme,
    }))
  )

  return (
    <header
      className="sticky top-0 z-[200] border-b"
      style={{
        borderColor: 'var(--line)',
        background:
          'linear-gradient(180deg, rgb(var(--void-rgb) / 0.92), rgb(var(--void-rgb) / 0.62) 70%, transparent)',
        backdropFilter: 'blur(14px) saturate(1.1)',
        WebkitBackdropFilter: 'blur(14px) saturate(1.1)',
      }}
    >
      <div className="max-w-[1500px] mx-auto flex items-center gap-3 sm:gap-6 px-4 sm:px-8 py-3.5">
        {/* Brand */}
        <div className="flex items-center gap-3 shrink-0 select-none">
          <ReelMark className="w-[30px] h-[30px] text-amber animate-spin-slow drop-shadow-[0_0_10px_rgba(233,178,102,0.5)]" />
          <div className="hidden sm:flex flex-col leading-[1.05]">
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
          {NAV.map(({ id, label, Icon }) => (
            <button
              key={id}
              aria-current={currentView === id ? 'page' : undefined}
              onClick={() => onViewChange(id)}
              className={cn('navtab', currentView === id && 'is-active')}
            >
              <Icon className="w-4 h-4" />
              <span className="whitespace-nowrap">{label}</span>
            </button>
          ))}
        </nav>

        {/* Actions */}
        <div className="flex items-center gap-2 ml-auto shrink-0">
          <button
            onClick={(e) => toggleTheme({ clientX: e.clientX, clientY: e.clientY })}
            aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            title={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
            className="icon-btn w-9 h-9 border rounded-md text-paper-dim hover:text-amber transition-colors flex items-center justify-center"
            style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
          >
            {theme === 'dark' ? (
              <Sun className="w-[17px] h-[17px]" />
            ) : (
              <Moon className="w-[17px] h-[17px]" />
            )}
          </button>

          <button
            onClick={openCommandPalette}
            aria-label="Open command palette"
            className="icon-btn h-9 border rounded-md text-paper-dim hover:text-amber transition-colors flex items-center gap-2 px-2.5 sm:px-3"
            style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
          >
            <Search className="w-[17px] h-[17px]" />
            <span className="hidden lg:inline font-sans text-[13px] text-paper-faint">Search</span>
            <kbd
              className="hidden lg:inline font-mono text-[10px] tracking-[0.06em] text-paper-faint border rounded px-1.5 py-0.5"
              style={{ borderColor: 'var(--line)' }}
            >
              ⌘K
            </kbd>
          </button>

          {currentView === 'library' && (
            <div className="hidden sm:flex items-center gap-0.5 seg !p-1" role="group" aria-label="View mode">
              <button
                onClick={() => setViewMode('grid')}
                className={cn(
                  'icon-btn w-8 h-8',
                  viewMode === 'grid' && '!text-amber-bright bg-[rgba(233,178,102,0.12)]'
                )}
                aria-label="Poster wall"
                title="Poster wall"
                aria-pressed={viewMode === 'grid'}
              >
                <LayoutGrid className="w-[17px] h-[17px]" />
              </button>
              <button
                onClick={() => setViewMode('list')}
                className={cn(
                  'icon-btn w-8 h-8',
                  viewMode === 'list' && '!text-amber-bright bg-[rgba(233,178,102,0.12)]'
                )}
                aria-label="Ledger list"
                title="Ledger list"
                aria-pressed={viewMode === 'list'}
              >
                <List className="w-[17px] h-[17px]" />
              </button>
            </div>
          )}

          {isSupabaseConfigured && !isSharedView && (
            user ? (
              <button
                onClick={onProfileClick}
                className="icon-btn w-9 h-9 border rounded-md text-amber border-amber/30 bg-amber/5 hover:bg-amber/10 transition-colors flex items-center justify-center"
                aria-label="Profile and Settings"
              >
                <User className="w-[17px] h-[17px]" />
              </button>
            ) : (
              <button
                onClick={onProfileClick}
                className="icon-btn h-9 border rounded-md text-paper-faint border-[var(--line)] hover:text-amber hover:border-amber/30 transition-colors flex items-center gap-1.5 px-2.5"
                aria-label="Sign in"
              >
                <LogIn className="w-[15px] h-[15px]" />
                <span className="hidden sm:inline font-sans text-[12px]">Sign in</span>
              </button>
            )
          )}

          {!isSharedView && (
            <button
              onClick={openAddTitle}
              aria-label="Add Title"
              className="btn-amber inline-flex items-center gap-2 rounded-md px-4 py-2 text-[13px] font-bold"
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
