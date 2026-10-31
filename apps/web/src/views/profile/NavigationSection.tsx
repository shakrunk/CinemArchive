import { useState, useRef } from 'react'
import { Eye, EyeOff, LayoutGrid, GripVertical } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { NAV_ITEM_LABELS, type NavItemId } from 'src/lib/navigation'
import { Section } from 'src/components/ui/section'

// Gap between rows in the reorderable nav list (matches the container's space-y-2).
const NAV_ROW_GAP = 8

interface NavDragMeta {
  id: NavItemId
  startIndex: number
  itemHeight: number
}

export function NavigationSection() {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const navPrefs = useAppStore((s) => s.navPrefs)
  const moveNavItem = useAppStore((s) => s.moveNavItem)
  const reorderNav = useAppStore((s) => s.reorderNav)
  const toggleNavItemHidden = useAppStore((s) => s.toggleNavItemHidden)
  const setNavCompact = useAppStore((s) => s.setNavCompact)

  const order = navPrefs.order
  const itemRefs = useRef(new Map<NavItemId, HTMLDivElement>())
  // Only read inside event handlers, never during render — safe as a ref.
  const startYRef = useRef(0)
  // Read during render (to offset non-dragged rows), so these live in state.
  const [dragMeta, setDragMeta] = useState<NavDragMeta | null>(null)
  const [dragOffset, setDragOffset] = useState(0)
  const [dropIndex, setDropIndex] = useState<number | null>(null)

  function handlePointerDown(e: React.PointerEvent<HTMLButtonElement>, id: NavItemId) {
    const el = itemRefs.current.get(id)
    if (!el) return
    const startIndex = order.indexOf(id)
    const itemHeight = el.getBoundingClientRect().height + NAV_ROW_GAP
    startYRef.current = e.clientY
    setDragMeta({ id, startIndex, itemHeight })
    setDragOffset(0)
    setDropIndex(startIndex)
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  function handlePointerMove(e: React.PointerEvent<HTMLButtonElement>) {
    if (!dragMeta) return
    const offset = e.clientY - startYRef.current
    setDragOffset(offset)
    const rawIndex = dragMeta.startIndex + Math.round(offset / dragMeta.itemHeight)
    setDropIndex(Math.max(0, Math.min(order.length - 1, rawIndex)))
  }

  function endDrag(e: React.PointerEvent<HTMLButtonElement>) {
    if (!dragMeta) return
    e.currentTarget.releasePointerCapture(e.pointerId)
    // Recompute from the live offset rather than trusting `dropIndex` state,
    // which may not have committed yet if pointerup follows pointermove
    // within the same tick.
    const finalOffset = e.clientY - startYRef.current
    const rawIndex = dragMeta.startIndex + Math.round(finalOffset / dragMeta.itemHeight)
    const target = Math.max(0, Math.min(order.length - 1, rawIndex))
    if (target !== dragMeta.startIndex) {
      const next = [...order]
      const [moved] = next.splice(dragMeta.startIndex, 1)
      next.splice(target, 0, moved)
      reorderNav(next)
    }
    setDragMeta(null)
    setDragOffset(0)
    setDropIndex(null)
  }

  function handleGripKeyDown(e: React.KeyboardEvent, id: NavItemId) {
    if (e.key === 'ArrowUp') { e.preventDefault(); moveNavItem(id, 'up') }
    if (e.key === 'ArrowDown') { e.preventDefault(); moveNavItem(id, 'down') }
  }

  return (
    <Section
      id="navigation"
      title="Navigation"
      Icon={LayoutGrid}
      description="Drag to reorder tabs in the top bar and bottom nav — number-key shortcuts (1, 2, 3…) follow the new order. Hidden tabs stay reachable from the command palette (⌘K)."
    >
      <div className="space-y-2" role="list" aria-label="Navigation tabs">
        {order.map((id: NavItemId, i) => {
          const hidden = navPrefs.hidden.includes(id)
          const isDragging = dragMeta?.id === id
          let translateY = 0
          if (dragMeta && dropIndex !== null) {
            if (isDragging) {
              translateY = dragOffset
            } else if (dragMeta.startIndex < dropIndex && i > dragMeta.startIndex && i <= dropIndex) {
              translateY = -dragMeta.itemHeight
            } else if (dragMeta.startIndex > dropIndex && i >= dropIndex && i < dragMeta.startIndex) {
              translateY = dragMeta.itemHeight
            }
          }
          return (
            <div
              key={id}
              ref={(el) => {
                if (el) itemRefs.current.set(id, el)
                else itemRefs.current.delete(id)
              }}
              role="listitem"
              className={cn('flex items-center gap-3 rounded-lg border p-2.5', isDragging && 'shadow-lg')}
              style={{
                borderColor: 'var(--line)',
                background: 'var(--inset)',
                transform: translateY ? `translateY(${translateY}px)` : undefined,
                transition: isDragging ? 'none' : 'transform 180ms ease',
                position: isDragging ? 'relative' : undefined,
                zIndex: isDragging ? 10 : undefined,
              }}
            >
              <button
                onPointerDown={(e) => handlePointerDown(e, id)}
                onPointerMove={handlePointerMove}
                onPointerUp={endDrag}
                onPointerCancel={endDrag}
                onKeyDown={(e) => handleGripKeyDown(e, id)}
                aria-label={`Reorder ${NAV_ITEM_LABELS[id]} — drag, or use arrow keys`}
                className="text-paper-faint hover:text-amber cursor-grab active:cursor-grabbing -my-1 p-1 rounded focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
                style={{ touchAction: 'none' }}
              >
                <GripVertical className="w-4 h-4" />
              </button>
              <span className={cn('flex-1 font-sans text-sm', hidden ? 'text-muted-foreground' : 'text-paper')}>
                {NAV_ITEM_LABELS[id]}
              </span>
              <button
                onClick={() => toggleNavItemHidden(id)}
                aria-pressed={!hidden}
                aria-label={hidden ? `Show ${NAV_ITEM_LABELS[id]} in navigation` : `Hide ${NAV_ITEM_LABELS[id]} from navigation`}
                className={cn(
                  'icon-btn w-8 h-8 border rounded-md flex items-center justify-center shrink-0 focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60',
                  hidden
                    ? 'text-muted-foreground border-border'
                    : 'text-amber border-amber/30 bg-amber/5'
                )}
              >
                {hidden ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
          )
        })}
      </div>

      <label className="flex items-center gap-2.5 cursor-pointer pt-1">
        <input
          type="checkbox"
          checked={navPrefs.compact}
          onChange={(e) => setNavCompact(e.target.checked)}
          className="sr-only"
        />
        <span
          className={cn('w-9 h-5 rounded-full transition-colors relative shrink-0', navPrefs.compact ? 'bg-amber' : 'bg-secondary')}
          aria-hidden="true"
        >
          <span
            className={cn(
              'absolute top-0.5 left-0.5 w-4 h-4 rounded-full bg-white transition-transform',
              navPrefs.compact && 'translate-x-4'
            )}
          />
        </span>
        <span className="font-sans text-sm text-paper">Compact top bar (icons only)</span>
      </label>
    </Section>
  )
}
