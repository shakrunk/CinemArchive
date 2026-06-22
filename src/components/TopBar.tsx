import { Plus, LayoutGrid, List, BarChart3, User, PlayCircle } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { isSupabaseConfigured } from 'src/lib/auth'

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
  const { viewMode, setViewMode, openAddTitle, user, isSharedView } = useAppStore()

  return (
    <header
      className="sticky top-0 z-[200] border-b"
      style={{
        borderColor: 'var(--line)',
        background:
          'linear-gradient(180deg, rgba(11,9,7,0.92), rgba(11,9,7,0.62) 70%, transparent)',
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
        <nav className="navpill ml-1 hidden sm:flex" role="tablist">
          {NAV.map(({ id, label, Icon }) => (
            <button
              key={id}
              role="tab"
              aria-selected={currentView === id}
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
          {currentView === 'library' && (
            <div className="hidden sm:flex items-center gap-0.5 seg !p-1">
              <button
                onClick={() => setViewMode('grid')}
                className={cn(
                  'icon-btn w-8 h-8',
                  viewMode === 'grid' && '!text-amber-bright bg-[rgba(233,178,102,0.12)]'
                )}
                aria-label="Poster wall"
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
              >
                <List className="w-[17px] h-[17px]" />
              </button>
            </div>
          )}

          {isSupabaseConfigured && !isSharedView && (
            <button
              onClick={onProfileClick}
              className={cn(
                "icon-btn w-9 h-9 border rounded-md hover:text-amber transition-colors flex items-center justify-center relative",
                user ? "text-amber border-amber/30 bg-amber/5" : "text-muted-foreground border-border"
              )}
              aria-label="Profile and Settings"
            >
              <User className="w-[17px] h-[17px]" />
              {user && (
                <span className="absolute top-0.5 right-0.5 w-2 h-2 bg-amber rounded-full border border-void animate-pulse" />
              )}
            </button>
          )}

          {!isSharedView && (
            <button
              onClick={openAddTitle}
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
