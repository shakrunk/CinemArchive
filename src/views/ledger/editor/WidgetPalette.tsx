// Floating widget palette for the Ledger's layout editor: live previews of
// every panel type, draggable (or tappable) onto the board.

import { useMemo } from 'react'
import { Check, Plus, RotateCcw, PanelLeftClose } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import type { LedgerPanelId } from 'src/lib/ledgerPanels'
import {
  DEFAULT_LEDGER_PANEL_ORDER,
  LEDGER_PANEL_LABELS,
  LEDGER_PANEL_DESCRIPTIONS,
} from 'src/lib/ledgerPanels'
import { PANEL_REGISTRY } from '../panelRegistry'
import { floatingPanelStyle } from './chrome'

/** Live, scaled-down render of a panel type for the palette. */
function WidgetPreview({ panel }: { panel: LedgerPanelId }) {
  const { Component } = PANEL_REGISTRY[panel]
  return (
    <div
      aria-hidden
      className="relative h-[96px] overflow-hidden rounded-md border border-[var(--line)] pointer-events-none select-none bg-[var(--ink-2)]"
    >
      <div className="absolute top-0 left-0 w-[600px] origin-top-left" style={{ transform: 'scale(0.4)' }}>
        <Component />
      </div>
      <div
        className="absolute inset-0"
        style={{ background: 'linear-gradient(180deg, transparent 55%, var(--ink-1) 96%)' }}
      />
    </div>
  )
}

export function WidgetPalette({
  onItemPointerDown,
  onItemPointerMove,
  onItemPointerEnd,
  onItemActivate,
  onClose,
  onReset,
  onHide,
  className,
}: {
  onItemPointerDown: (e: React.PointerEvent<HTMLDivElement>, panel: LedgerPanelId) => void
  onItemPointerMove: (e: React.PointerEvent<HTMLDivElement>) => void
  onItemPointerEnd: (e: React.PointerEvent<HTMLDivElement>) => void
  onItemActivate: (panel: LedgerPanelId) => void
  onClose: () => void
  onReset: () => void
  onHide: () => void
  className?: string
}) {
  const widgets = useAppStore((s) => s.ledgerPrefs.widgets)
  const counts = useMemo(() => {
    const map = new Map<LedgerPanelId, number>()
    for (const w of widgets) map.set(w.panel, (map.get(w.panel) ?? 0) + 1)
    return map
  }, [widgets])

  return (
    <aside
      aria-label="Widget palette"
      className={cn('rounded-xl border border-[var(--line)] p-3.5 flex flex-col gap-3 min-h-0', className)}
      style={floatingPanelStyle}
    >
      <header className="flex items-center justify-between gap-2 shrink-0">
        <h2 className="font-mono text-[10px] tracking-[0.18em] uppercase text-paper-dim">Widgets</h2>
        <div className="flex items-center gap-1">
          <button
            type="button"
            onClick={onHide}
            aria-label="Hide widget palette"
            className="w-6 h-6 rounded-md text-paper-faint hover:text-paper flex items-center justify-center transition-colors"
          >
            <PanelLeftClose className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-sans border border-amber/40 bg-amber/10 text-amber"
          >
            <Check className="w-3.5 h-3.5" /> Done
          </button>
        </div>
      </header>
      <p className="font-mono text-[9px] tracking-[0.12em] uppercase text-paper-faint shrink-0">
        drag onto the board · or tap to add
      </p>
      <ul className="flex flex-col gap-2.5 overflow-y-auto scrollbar-thin -mx-1 px-1 min-h-0">
        {DEFAULT_LEDGER_PANEL_ORDER.map((panel) => {
          const count = counts.get(panel) ?? 0
          return (
            <li key={panel}>
              {/* Not a <button>: the live preview inside renders panels that
                  contain their own buttons, and buttons cannot nest. */}
              <div
                role="button"
                tabIndex={0}
                onPointerDown={(e) => onItemPointerDown(e, panel)}
                onPointerMove={onItemPointerMove}
                onPointerUp={onItemPointerEnd}
                onPointerCancel={onItemPointerEnd}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onItemActivate(panel)
                  }
                }}
                style={{ touchAction: 'pan-y' }}
                aria-label={`Add ${LEDGER_PANEL_LABELS[panel]} to the board`}
                className="w-full rounded-lg border border-transparent hover:border-amber/30 p-1.5 text-left transition-colors cursor-grab active:cursor-grabbing group"
              >
                <WidgetPreview panel={panel} />
                <span className="flex items-center gap-2 mt-1.5 px-0.5">
                  <span className="flex-1 min-w-0">
                    <span className="block text-[12px] text-paper truncate">{LEDGER_PANEL_LABELS[panel]}</span>
                    <span className="block font-mono text-[9px] text-paper-faint truncate mt-0.5">
                      {LEDGER_PANEL_DESCRIPTIONS[panel]}
                    </span>
                  </span>
                  {count > 0 && <span className="font-mono text-[9px] text-amber-deep shrink-0">×{count}</span>}
                  <span className="w-5 h-5 shrink-0 rounded-md border border-[var(--line)] flex items-center justify-center text-paper-faint group-hover:text-amber group-hover:border-amber/30 transition-colors">
                    <Plus className="w-3 h-3" />
                  </span>
                </span>
              </div>
            </li>
          )
        })}
      </ul>
      <button
        type="button"
        onClick={onReset}
        className="shrink-0 inline-flex items-center justify-center gap-1.5 rounded-md px-2.5 py-1 text-[11px] font-sans text-paper-faint hover:text-paper transition-colors"
      >
        <RotateCcw className="w-3 h-3" /> Reset to default layout
      </button>
    </aside>
  )
}
