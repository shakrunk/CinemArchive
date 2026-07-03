import { Plus, LayoutGrid, List, BarChart3, User, LogIn, PlayCircle, Search, Sun, Moon, Compass, Users, X } from 'lucide-react'
import { useShallow } from 'zustand/react/shallow'
import { useAppStore } from 'src/store/useAppStore'
import { cn, modKey } from 'src/lib/utils'
import { isSupabaseConfigured } from 'src/lib/auth'
import { toggleTheme } from 'src/lib/theme'
import type { AppView } from 'src/lib/navigation'
import { ReelMark } from 'src/components/ui/reel-mark'

interface TopBarProps {
  currentView: AppView
  onViewChange: (view: AppView) => void
  onProfileClick: () => void
}

const NAV: { id: AppView; label: string; Icon: typeof BarChart3 }[] = [
  { id: 'discover', label: 'Discover', Icon: Compass },
  { id: 'library', label: 'The Library', Icon: LayoutGrid },
  { id: 'upnext', label: 'Up Next', Icon: PlayCircle },
  { id: 'ledger', label: 'The Ledger', Icon: BarChart3 },
]

export function TopBar({ currentView, onViewChange, onProfileClick }: TopBarProps) {
  // ⚡ Bolt: Prevent unnecessary re-renders by using useShallow
  const { viewMode, setViewMode, openAddTitle, user, isSharedView, friendView, exitFriendView, openCommandPalette, theme, activityUnseenCount } = useAppStore(
    useShallow((s) => ({
      viewMode: s.viewMode,
      setViewMode: s.setViewMode,
      openAddTitle: s.openAddTitle,
      user: s.user,
      isSharedView: s.isSharedView,
      friendView: s.friendView,
      exitFriendView: s.exitFriendView,
      openCommandPalette: s.openCommandPalette,
      theme: s.theme,
      activityUnseenCount: s.activityUnseenCount,
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
          <ReelMark className="w-[30px] h-[30px] text-amber animate-spin-slow drop-shadow-[0_0_10px_rgb(var(--amber-rgb)/0.5)]" />
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
              {modKey}K
            </kbd>
          </button>

          {currentView === 'library' && (
            <div className="hidden sm:flex items-center gap-0.5 seg !p-1">
              <button
                onClick={() => setViewMode('grid')}
                className={cn(
                  'icon-btn w-8 h-8',
                  viewMode === 'grid' && '!text-amber-bright bg-amber/12'
                )}
                aria-label="Poster wall"
              >
                <LayoutGrid className="w-[17px] h-[17px]" />
              </button>
              <button
                onClick={() => setViewMode('list')}
                className={cn(
                  'icon-btn w-8 h-8',
                  viewMode === 'list' && '!text-amber-bright bg-amber/12'
                )}
                aria-label="Ledger list"
              >
                <List className="w-[17px] h-[17px]" />
              </button>
            </div>
          )}

          {friendView ? (
            <button
              onClick={exitFriendView}
              className="icon-btn h-9 border rounded-md text-amber border-amber/30 bg-amber/5 hover:bg-amber/10 transition-colors flex items-center gap-1.5 px-2.5"
              aria-label={`Exit ${friendView.displayName}'s library`}
              title={`Viewing ${friendView.displayName}'s library — click to exit`}
            >
              <Users className="w-[15px] h-[15px]" />
              <span className="hidden sm:inline font-sans text-[12px] truncate max-w-[140px]">{friendView.displayName}</span>
              <X className="w-[13px] h-[13px]" />
            </button>
          ) : (
            isSupabaseConfigured && !isSharedView && (
              user ? (
                <button
                  onClick={onProfileClick}
                  aria-current={currentView === 'profile' ? 'page' : undefined}
                  className={cn(
                    'icon-btn relative w-9 h-9 border rounded-md text-amber border-amber/30 bg-amber/5 hover:bg-amber/10 transition-colors flex items-center justify-center',
                    currentView === 'profile' && '!bg-amber/15 border-amber/50'
                  )}
                  aria-label={activityUnseenCount > 0 ? `Profile and Settings — ${activityUnseenCount} new friend activity` : 'Profile and Settings'}
                >
                  <User className="w-[17px] h-[17px]" />
                  {activityUnseenCount > 0 && (
                    <span
                      className="absolute -top-1 -right-1 min-w-[16px] h-4 px-1 rounded-full bg-amber text-[color:var(--on-amber)] text-[9px] font-mono font-bold flex items-center justify-center"
                      aria-hidden="true"
                    >
                      {activityUnseenCount > 9 ? '9+' : activityUnseenCount}
                    </span>
                  )}
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
