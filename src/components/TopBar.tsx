import { Film, Plus, LayoutGrid, List } from 'lucide-react'
import { Button } from 'src/components/ui/button'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'

interface TopBarProps {
  currentView: 'library' | 'ledger'
  onViewChange: (view: 'library' | 'ledger') => void
}

export function TopBar({ currentView, onViewChange }: TopBarProps) {
  const { viewMode, setViewMode, openAddTitle } = useAppStore()

  return (
    <header className="sticky top-0 z-50 w-full border-b border-border bg-void/80 backdrop-blur-md projector-beam">
      <div className="max-w-7xl mx-auto px-4 h-14 flex items-center justify-between gap-4">
        {/* Brand */}
        <div className="flex items-center gap-2 shrink-0">
          <Film className="w-5 h-5 text-amber" />
          <span className="font-serif text-lg font-light text-gold hidden sm:block">
            CinemArchive
          </span>
        </div>

        {/* Nav */}
        <nav className="flex items-center gap-1">
          <button
            onClick={() => onViewChange('library')}
            className={cn(
              'px-3 py-1.5 rounded-md text-sm font-sans transition-colors',
              currentView === 'library'
                ? 'text-amber bg-amber/10'
                : 'text-muted-foreground hover:text-foreground hover:bg-white/5'
            )}
          >
            Library
          </button>
          <button
            onClick={() => onViewChange('ledger')}
            className={cn(
              'px-3 py-1.5 rounded-md text-sm font-sans transition-colors',
              currentView === 'ledger'
                ? 'text-amber bg-amber/10'
                : 'text-muted-foreground hover:text-foreground hover:bg-white/5'
            )}
          >
            Ledger
          </button>
        </nav>

        {/* Actions */}
        <div className="flex items-center gap-2 shrink-0">
          {currentView === 'library' && (
            <div className="hidden sm:flex items-center gap-0.5 bg-secondary rounded-md p-0.5">
              <button
                onClick={() => setViewMode('grid')}
                className={cn(
                  'p-1.5 rounded transition-colors',
                  viewMode === 'grid'
                    ? 'bg-amber/20 text-amber'
                    : 'text-muted-foreground hover:text-foreground'
                )}
                aria-label="Grid view"
              >
                <LayoutGrid className="w-4 h-4" />
              </button>
              <button
                onClick={() => setViewMode('list')}
                className={cn(
                  'p-1.5 rounded transition-colors',
                  viewMode === 'list'
                    ? 'bg-amber/20 text-amber'
                    : 'text-muted-foreground hover:text-foreground'
                )}
                aria-label="List view"
              >
                <List className="w-4 h-4" />
              </button>
            </div>
          )}
          <Button
            size="sm"
            onClick={openAddTitle}
            className="bg-amber hover:bg-amber-muted text-void font-sans font-medium gap-1.5"
          >
            <Plus className="w-4 h-4" />
            <span className="hidden sm:inline">Add</span>
          </Button>
        </div>
      </div>
    </header>
  )
}
