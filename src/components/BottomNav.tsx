import { LayoutGrid, BarChart3, Plus, PlayCircle } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'

interface BottomNavProps {
  currentView: 'upnext' | 'library' | 'ledger'
  onViewChange: (view: 'upnext' | 'library' | 'ledger') => void
}

function NavTab({
  active,
  onClick,
  label,
  Icon,
}: {
  active: boolean
  onClick: () => void
  label: string
  Icon: typeof LayoutGrid
}) {
  return (
    <button
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'flex flex-col items-center gap-1 px-3 pt-3 pb-1 relative transition-colors',
        active ? 'text-amber' : 'text-paper-faint'
      )}
    >
      <span
        className={cn(
          'absolute top-0 left-1/2 -translate-x-1/2 h-0.5 rounded-b-full bg-amber transition-all duration-300',
          active ? 'w-8 shadow-[0_0_8px_rgba(233,178,102,0.6)]' : 'w-0'
        )}
      />
      <Icon className="w-5 h-5" />
      <span className="text-[11px] font-sans">{label}</span>
    </button>
  )
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
      <div className="flex items-center justify-around h-16 px-2">
        <NavTab active={currentView === 'upnext'} onClick={() => onViewChange('upnext')} label="Up Next" Icon={PlayCircle} />
        <NavTab active={currentView === 'library'} onClick={() => onViewChange('library')} label="Library" Icon={LayoutGrid} />

        <button onClick={openAddTitle} className="flex flex-col items-center gap-0.5 px-3 py-2" aria-label="Add Title">
          <div
            className="w-11 h-11 rounded-full flex items-center justify-center -mt-6 amber-glow transition-transform active:scale-95"
            style={{ background: 'linear-gradient(180deg, var(--amber-bright), var(--amber))' }}
          >
            <Plus className="w-5 h-5 text-void" strokeWidth={2.5} />
          </div>
          <span className="text-[11px] font-sans text-paper-faint mt-0.5">Add</span>
        </button>

        <NavTab active={currentView === 'ledger'} onClick={() => onViewChange('ledger')} label="Ledger" Icon={BarChart3} />
      </div>
    </nav>
  )
}
