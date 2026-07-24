import { useEffect } from 'react'
import { Plus, Users, type LucideIcon } from 'lucide-react'
import { useAppStore, useVisibleNavItems } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { resolveNavIcon } from 'src/lib/navIcons'
import type { AppView, NavItemId } from 'src/lib/navigation'

interface BottomNavProps {
  currentView: AppView
  onViewChange: (view: AppView) => void
}

// Shorter copy than TopBar.tsx's — this nav is space-constrained (see navigation.ts).
const NAV_LABELS: Record<NavItemId, string> = {
  discover: 'Discover',
  library: 'Library',
  upnext: 'Up Next',
  ledger: 'Ledger',
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
  Icon: LucideIcon
}) {
  return (
    <button
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'flex flex-col items-center gap-1 px-3 pt-3 pb-1 relative transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 focus-visible:z-10 rounded-md',
        active ? 'text-amber' : 'text-paper-faint'
      )}
    >
      <span
        className={cn(
          'absolute top-0 left-1/2 -translate-x-1/2 h-0.5 rounded-b-full bg-amber transition-all duration-300',
          active ? 'w-8 shadow-[0_0_8px_rgb(var(--amber-rgb)/0.6)]' : 'w-0'
        )}
      />
      <Icon className="w-5 h-5" />
      <span className="text-[11px] font-sans">{label}</span>
    </button>
  )
}

export function BottomNav({ currentView, onViewChange }: BottomNavProps) {
  const openAddTitle = useAppStore((s) => s.openAddTitle)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const viewMode = useAppStore((s) => s.viewMode)
  const user = useAppStore((s) => s.user)

  const visibleNav = useVisibleNavItems()
  // Split around the central Add button, biasing an odd extra item to the left
  // (matches the original fixed Discover/Library · Add · Up Next/Ledger layout).
  const splitAt = Math.ceil(visibleNav.length / 2)
  const leftNav = visibleNav.slice(0, splitAt)
  const rightNav = visibleNav.slice(splitAt)

  // Firefox Android (and Samsung Internet) position fixed elements relative to
  // the layout viewport, not the visual viewport, so the nav floats above the
  // screen bottom when the browser toolbar hides. Track the visual viewport
  // and compensate.
  useEffect(() => {
    const vv = window.visualViewport
    if (!vv) return

    let lastGap = -1
    function update() {
      const gap = Math.max(0, window.innerHeight - vv!.offsetTop - vv!.height)
      if (gap === lastGap) return
      lastGap = gap
      document.documentElement.style.setProperty('--vv-bottom', `${gap}px`)
    }

    update()
    vv.addEventListener('resize', update)
    vv.addEventListener('scroll', update)
    // Some engines fire a layout-viewport resize (toolbar collapse) without a
    // matching visualViewport event — catch those too.
    window.addEventListener('resize', update)
    // Samsung Internet auto-hides its toolbar on page-content scroll without
    // firing any of the above (the toolbar collapse is neither a visualViewport
    // resize/scroll nor a window resize) — the gap goes stale, leaving the nav
    // floating at its old toolbar-visible offset. Document scroll is the one
    // event that reliably fires as that happens, so recompute there too.
    window.addEventListener('scroll', update, { passive: true })
    return () => {
      vv.removeEventListener('resize', update)
      vv.removeEventListener('scroll', update)
      window.removeEventListener('resize', update)
      window.removeEventListener('scroll', update)
    }
  }, [])

  return (
    <nav
      className="fixed inset-x-0 z-[200] sm:hidden border-t"
      style={{
        bottom: 'var(--vv-bottom, 0px)',
        borderColor: 'var(--line)',
        background: 'linear-gradient(0deg, rgb(var(--void-rgb) / 0.96), rgb(var(--void-rgb) / 0.78))',
        backdropFilter: 'blur(14px)',
        WebkitBackdropFilter: 'blur(14px)',
      }}
    >
      <div
        className="flex items-center justify-around h-16 px-2"
        style={{ paddingBottom: 'env(safe-area-inset-bottom, 0px)' }}
      >
        {leftNav.map((id) => {
          const label = NAV_LABELS[id]
          const Icon = resolveNavIcon(id, viewMode)
          return <NavTab key={id} active={currentView === id} onClick={() => onViewChange(id)} label={label} Icon={Icon} />
        })}

        {!isSharedView && (
          <button onClick={openAddTitle} className="flex flex-col items-center gap-0.5 px-3 py-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 rounded-md" aria-label="Add Title">
            <div
              className="w-11 h-11 rounded-full flex items-center justify-center -mt-6 amber-glow transition-transform active:scale-95"
              style={{ background: 'linear-gradient(180deg, var(--amber-bright), var(--amber))' }}
            >
              <Plus className="w-5 h-5 text-[color:var(--on-amber)]" strokeWidth={2.5} />
            </div>
            <span className="text-[11px] font-sans text-paper-faint mt-0.5">Add</span>
          </button>
        )}

        {rightNav.map((id) => {
          const label = NAV_LABELS[id]
          const Icon = resolveNavIcon(id, viewMode)
          return <NavTab key={id} active={currentView === id} onClick={() => onViewChange(id)} label={label} Icon={Icon} />
        })}

        {/* Fixed, non-reorderable — mirrors the Add button's placement, not part of navPrefs.order. */}
        {user && !isSharedView && (
          <NavTab active={currentView === 'friends'} onClick={() => onViewChange('friends')} label="Friends" Icon={Users} />
        )}
      </div>
    </nav>
  )
}
