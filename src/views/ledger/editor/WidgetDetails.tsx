// Floating details panel for the selected widget: size, position, duplicate,
// remove.

import { X, Copy, ChevronUp, ChevronDown, PanelRightClose } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import {
  LEDGER_PANEL_LABELS,
  LEDGER_PANEL_WIDTH_ORDER,
  LEDGER_PANEL_WIDTH_LABELS,
} from 'src/lib/ledgerPanels'
import { editorBtnClass, floatingPanelStyle } from './chrome'

export function WidgetDetails({
  selectedId,
  onSelect,
  onHide,
  className,
}: {
  selectedId: string
  onSelect: (id: string | null) => void
  onHide: () => void
  className?: string
}) {
  const widgets = useAppStore((s) => s.ledgerPrefs.widgets)
  const duplicateLedgerWidget = useAppStore((s) => s.duplicateLedgerWidget)
  const removeLedgerWidget = useAppStore((s) => s.removeLedgerWidget)
  const moveLedgerWidget = useAppStore((s) => s.moveLedgerWidget)
  const setLedgerWidgetWidth = useAppStore((s) => s.setLedgerWidgetWidth)

  const selected = widgets.find((w) => w.id === selectedId)
  const selectedIndex = selected ? widgets.findIndex((w) => w.id === selected.id) : -1
  if (!selected) return null

  return (
    <aside
      aria-label="Widget details"
      className={cn('rounded-xl border border-[var(--line)] p-4 flex flex-col gap-3.5', className)}
      style={floatingPanelStyle}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="font-serif text-[15px] font-medium text-paper leading-tight">
            {LEDGER_PANEL_LABELS[selected.panel]}
          </h3>
          <p className="font-mono text-[9px] tracking-[0.14em] uppercase text-paper-faint mt-1">
            widget {selectedIndex + 1} of {widgets.length}
          </p>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button
            type="button"
            onClick={onHide}
            aria-label="Hide widget details"
            className="w-6 h-6 rounded-md text-paper-faint hover:text-paper flex items-center justify-center transition-colors"
          >
            <PanelRightClose className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={() => onSelect(null)}
            aria-label="Deselect widget"
            className="w-6 h-6 rounded-md text-paper-faint hover:text-paper flex items-center justify-center"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      <div>
        <p className="font-mono text-[9px] tracking-[0.16em] uppercase text-paper-faint mb-1.5">Size</p>
        <div className="grid grid-cols-4 gap-1">
          {LEDGER_PANEL_WIDTH_ORDER.map((w) => (
            <button
              key={w}
              type="button"
              onClick={() => setLedgerWidgetWidth(selected.id, w)}
              aria-pressed={selected.width === w}
              className={cn(
                'rounded-md border py-1.5 font-mono text-[10px] transition-colors',
                selected.width === w
                  ? 'border-amber/40 bg-amber/10 text-amber'
                  : 'border-[var(--line)] text-paper-faint hover:text-paper hover:border-[var(--line-2)]',
              )}
            >
              {LEDGER_PANEL_WIDTH_LABELS[w]}
            </button>
          ))}
        </div>
      </div>

      <div>
        <p className="font-mono text-[9px] tracking-[0.16em] uppercase text-paper-faint mb-1.5">Position</p>
        <div className="flex items-center gap-1.5">
          <button
            type="button"
            onClick={() => moveLedgerWidget(selected.id, 'up')}
            disabled={selectedIndex <= 0}
            aria-label="Move widget earlier"
            className={editorBtnClass}
          >
            <ChevronUp className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={() => moveLedgerWidget(selected.id, 'down')}
            disabled={selectedIndex === widgets.length - 1}
            aria-label="Move widget later"
            className={editorBtnClass}
          >
            <ChevronDown className="w-3.5 h-3.5" />
          </button>
          <span className="flex-1" />
          <button
            type="button"
            onClick={() => onSelect(duplicateLedgerWidget(selected.id))}
            className={editorBtnClass}
          >
            <Copy className="w-3 h-3" /> Duplicate
          </button>
        </div>
      </div>

      <button
        type="button"
        onClick={() => {
          removeLedgerWidget(selected.id)
          onSelect(null)
        }}
        className="w-full rounded-md border py-1.5 text-xs font-sans transition-colors"
        style={{ color: 'var(--ember)', borderColor: 'rgba(200,90,60,0.35)' }}
      >
        Remove from board
      </button>
    </aside>
  )
}
