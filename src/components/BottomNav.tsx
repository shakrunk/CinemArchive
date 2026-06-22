import { LayoutGrid, BarChart3, Plus } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'

interface BottomNavProps {
  currentView: 'library' | 'ledger'
  onViewChange: (view: 'library' | 'ledger') => void
}

export function BottomNav({ currentView, onViewChange }: BottomNavProps) {
  const openAddTitle = useAppStore((s) => s.openAddTitle)

  return (
    <nav
      className="fixed bottom-0 inset-x-0 z-[200] sm:hidden border-t"
      style={{
        borderColor: 'var(--line)',
        background: 'linear-gradient(0deg, rgba(11,9,7,0.96), rgba(11,9,7,0.78))',
        backdropFilter: 'blur(14px)',
        WebkitBackdropFilter: 'blur(14px)',
      }}
    >
      <div className="flex items-center justify-around h-16 px-4">
        <button
          onClick={() => onViewChange('library')}
          aria-current={currentView === 'library' ? 'page' : undefined}
          className={cn(
            'flex flex-col items-center gap-1 px-4 pt-3 pb-1 relative transition-colors',
            currentView === 'library' ? 'text-amber' : 'text-paper-faint'
          )}
        >
          <span
            className={cn(
              'absolute top-0 left-1/2 -translate-x-1/2 h-0.5 rounded-b-full bg-amber transition-all duration-300',
              currentView === 'library' ? 'w-8 shadow-[0_0_8px_rgba(233,178,102,0.6)]' : 'w-0'
            )}
          />
          <LayoutGrid className="w-5 h-5" />
          <span className="text-[11px] font-sans">Library</span>
        </button>

        <button onClick={openAddTitle} className="flex flex-col items-center gap-0.5 px-4 py-2" aria-label="Add Title">
          <div
            className="w-11 h-11 rounded-full flex items-center justify-center -mt-6 amber-glow transition-transform active:scale-95"
            style={{ background: 'linear-gradient(180deg, var(--amber-bright), var(--amber))' }}
          >
            <Plus className="w-5 h-5 text-void" strokeWidth={2.5} />
          </div>
          <span className="text-[11px] font-sans text-paper-faint mt-0.5">Add</span>
        </button>

        <button
          onClick={() => onViewChange('ledger')}
          aria-current={currentView === 'ledger' ? 'page' : undefined}
          className={cn(
            'flex flex-col items-center gap-1 px-4 pt-3 pb-1 relative transition-colors',
            currentView === 'ledger' ? 'text-amber' : 'text-paper-faint'
          )}
        >
          <span
            className={cn(
              'absolute top-0 left-1/2 -translate-x-1/2 h-0.5 rounded-b-full bg-amber transition-all duration-300',
              currentView === 'ledger' ? 'w-8 shadow-[0_0_8px_rgba(233,178,102,0.6)]' : 'w-0'
            )}
          />
          <BarChart3 className="w-5 h-5" />
          <span className="text-[11px] font-sans">Ledger</span>
        </button>
      </div>
    </nav>
  )
}
