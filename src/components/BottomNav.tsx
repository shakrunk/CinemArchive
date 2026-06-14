import { Film, BarChart2, Plus } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'

interface BottomNavProps {
  currentView: 'library' | 'ledger'
  onViewChange: (view: 'library' | 'ledger') => void
}

export function BottomNav({ currentView, onViewChange }: BottomNavProps) {
  const openAddTitle = useAppStore((s) => s.openAddTitle)

  return (
    <nav className="fixed bottom-0 inset-x-0 z-50 sm:hidden border-t border-border bg-void/90 backdrop-blur-md">
      <div className="flex items-center justify-around h-16 px-4">
        {/* Library tab */}
        <button
          onClick={() => onViewChange('library')}
          className={cn(
            'flex flex-col items-center gap-0.5 px-4 pt-3 pb-1 rounded-lg transition-colors relative',
            currentView === 'library' ? 'text-amber' : 'text-muted-foreground'
          )}
        >
          {/* Active indicator bar */}
          <span
            className={cn(
              'absolute top-0 left-1/2 -translate-x-1/2 h-0.5 rounded-b-full bg-amber transition-all duration-300',
              currentView === 'library' ? 'w-8' : 'w-0'
            )}
          />
          <Film className="w-5 h-5" />
          <span className="text-xs font-sans">Library</span>
        </button>

        {/* Add button — centered, floating */}
        <button
          onClick={openAddTitle}
          className="flex flex-col items-center gap-0.5 px-4 py-2"
        >
          <div className="w-10 h-10 rounded-full bg-amber flex items-center justify-center -mt-5 amber-glow transition-transform active:scale-95">
            <Plus className="w-5 h-5 text-void" />
          </div>
          <span className="text-xs font-sans text-muted-foreground mt-0.5">Add</span>
        </button>

        {/* Ledger tab */}
        <button
          onClick={() => onViewChange('ledger')}
          className={cn(
            'flex flex-col items-center gap-0.5 px-4 pt-3 pb-1 rounded-lg transition-colors relative',
            currentView === 'ledger' ? 'text-amber' : 'text-muted-foreground'
          )}
        >
          {/* Active indicator bar */}
          <span
            className={cn(
              'absolute top-0 left-1/2 -translate-x-1/2 h-0.5 rounded-b-full bg-amber transition-all duration-300',
              currentView === 'ledger' ? 'w-8' : 'w-0'
            )}
          />
          <BarChart2 className="w-5 h-5" />
          <span className="text-xs font-sans">Ledger</span>
        </button>
      </div>
    </nav>
  )
}
