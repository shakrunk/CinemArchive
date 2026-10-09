// Floating details panel for the selected widget: size, position, per-widget
// settings (time range / scope / top N / custom title), duplicate, remove.

import { X, Copy, ChevronUp, ChevronDown, Minus, Plus, PanelRightClose } from 'lucide-react'
import { useState } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import {
  LEDGER_PANEL_LABELS,
  LEDGER_PANEL_WIDTH_ORDER,
  LEDGER_PANEL_WIDTH_LABELS,
  LEDGER_TIME_RANGE_ORDER,
  LEDGER_TIME_RANGE_LABELS,
  LEDGER_SCOPE_ORDER,
  LEDGER_SCOPE_LABELS,
  LEDGER_TOP_N_MIN,
  LEDGER_TOP_N_MAX,
  PANEL_SETTING_KEYS,
  effectiveLedgerSettings,
  clampTopN,
} from 'src/lib/ledgerPanels'
import { editorBtnClass, floatingPanelStyle } from './chrome'
import { Eyebrow } from 'src/components/ui/typography'
import { useLedgerSave } from './useLedgerSave'

const segmentBtnClass = (active: boolean) =>
  cn(
    'rounded-md border py-1.5 px-1 font-mono text-[10px] transition-colors',
    active
      ? 'border-amber/40 bg-amber/10 text-amber'
      : 'border-(--line) text-paper-faint hover:text-paper hover:border-(--line-2)',
  )

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
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const widgets = useAppStore((s) => s.ledgerPrefs.widgets)
  const duplicateLedgerWidget = useAppStore((s) => s.duplicateLedgerWidget)
  const removeLedgerWidget = useAppStore((s) => s.removeLedgerWidget)
  const moveLedgerWidget = useAppStore((s) => s.moveLedgerWidget)
  const setLedgerWidgetWidth = useAppStore((s) => s.setLedgerWidgetWidth)
  const setLedgerWidgetSettings = useAppStore((s) => s.setLedgerWidgetSettings)
  const { save, pending, error } = useLedgerSave()

  const selected = widgets.find((w) => w.id === selectedId)
  const selectedIndex = selected ? widgets.findIndex((w) => w.id === selected.id) : -1
  if (!selected) return null

  const settingKeys = PANEL_SETTING_KEYS[selected.panel]
  const effective = effectiveLedgerSettings(selected.panel, selected.settings)

  return (
    <aside
      aria-label="Widget details"
      className={cn('rounded-xl border border-(--line) p-4 flex flex-col gap-3.5 overflow-y-auto min-h-0 scrollbar-thin', className)}
      style={floatingPanelStyle}
    >
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      <fieldset disabled={pending} className="contents" aria-busy={pending}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="font-serif text-[15px] font-medium text-paper leading-tight">
            {LEDGER_PANEL_LABELS[selected.panel]}
          </h3>
          <Eyebrow as="p" className="mt-1">
            widget {selectedIndex + 1} of {widgets.length}
          </Eyebrow>
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
            <X className="w-3.5 h-3.5" aria-hidden="true" />
          </button>
        </div>
      </div>

      <div>
        <Eyebrow as="p" className="mb-1.5">Size</Eyebrow>
        <div className="grid grid-cols-4 gap-1">
          {LEDGER_PANEL_WIDTH_ORDER.map((w) => (
            <button
              key={w}
              type="button"
              onClick={() => void save(() => setLedgerWidgetWidth(selected.id, w))}
              aria-pressed={selected.width === w}
              className={cn(
                'rounded-md border py-1.5 font-mono text-[10px] transition-colors',
                selected.width === w
                  ? 'border-amber/40 bg-amber/10 text-amber'
                  : 'border-(--line) text-paper-faint hover:text-paper hover:border-(--line-2)',
              )}
            >
              {LEDGER_PANEL_WIDTH_LABELS[w]}
            </button>
          ))}
        </div>
      </div>

      <div>
        <Eyebrow as="p" className="mb-1.5">Position</Eyebrow>
        <div className="flex items-center gap-1.5">
          <button
            type="button"
            onClick={() => void save(() => moveLedgerWidget(selected.id, 'up'))}
            disabled={selectedIndex <= 0}
            aria-label="Move widget earlier"
            className={editorBtnClass}
          >
            <ChevronUp className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            onClick={() => void save(() => moveLedgerWidget(selected.id, 'down'))}
            disabled={selectedIndex === widgets.length - 1}
            aria-label="Move widget later"
            className={editorBtnClass}
          >
            <ChevronDown className="w-3.5 h-3.5" />
          </button>
          <span className="flex-1" />
          <button
            type="button"
            onClick={() => void save(async () => { onSelect(await duplicateLedgerWidget(selected.id)) })}
            className={editorBtnClass}
          >
            <Copy className="w-3 h-3" /> Duplicate
          </button>
        </div>
      </div>

      {settingKeys.includes('timeRange') && (
        <div>
          <Eyebrow as="p" className="mb-1.5">Range</Eyebrow>
          <div className="grid grid-cols-4 gap-1">
            {LEDGER_TIME_RANGE_ORDER.map((r) => (
              <button
                key={r}
                type="button"
                onClick={() => void save(() => setLedgerWidgetSettings(selected.id, { timeRange: r }))}
                aria-pressed={effective.timeRange === r}
                className={segmentBtnClass(effective.timeRange === r)}
              >
                {LEDGER_TIME_RANGE_LABELS[r]}
              </button>
            ))}
          </div>
        </div>
      )}

      {settingKeys.includes('scope') && (
        <div>
          <Eyebrow as="p" className="mb-1.5">Scope</Eyebrow>
          <div className="grid grid-cols-3 gap-1">
            {LEDGER_SCOPE_ORDER.map((sc) => (
              <button
                key={sc}
                type="button"
                onClick={() => void save(() => setLedgerWidgetSettings(selected.id, { scope: sc }))}
                aria-pressed={effective.scope === sc}
                className={segmentBtnClass(effective.scope === sc)}
              >
                {LEDGER_SCOPE_LABELS[sc]}
              </button>
            ))}
          </div>
        </div>
      )}

      {settingKeys.includes('topN') && (
        <div>
          <Eyebrow as="p" className="mb-1.5">Show top</Eyebrow>
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              onClick={() => void save(() => setLedgerWidgetSettings(selected.id, { topN: clampTopN(effective.topN - 1) }))}
              disabled={effective.topN <= LEDGER_TOP_N_MIN}
              aria-label="Show fewer entries"
              className={editorBtnClass}
            >
              <Minus className="w-3.5 h-3.5" />
            </button>
            <span className="w-10 text-center font-mono text-[12px] text-paper">{effective.topN}</span>
            <button
              type="button"
              onClick={() => void save(() => setLedgerWidgetSettings(selected.id, { topN: clampTopN(effective.topN + 1) }))}
              disabled={effective.topN >= LEDGER_TOP_N_MAX}
              aria-label="Show more entries"
              className={editorBtnClass}
            >
              <Plus className="w-3.5 h-3.5" />
            </button>
            <Eyebrow as="span" className="ml-1">entries</Eyebrow>
          </div>
        </div>
      )}

      {settingKeys.includes('title') && (
        <div>
          <Eyebrow as="p" className="mb-1.5">Custom title</Eyebrow>
          <TitleSetting key={selected.id} value={selected.settings?.title ?? ''}
            placeholder={LEDGER_PANEL_LABELS[selected.panel]}
            onSave={(value) => save(() => setLedgerWidgetSettings(selected.id, { title: value.trim() ? value : undefined }))} />
        </div>
      )}

      <button
        type="button"
        onClick={() => void save(async () => {
          await removeLedgerWidget(selected.id)
          onSelect(null)
        })}
        className="w-full shrink-0 rounded-md border py-1.5 text-xs font-sans transition-colors"
        style={{ color: 'var(--ember)', borderColor: 'rgba(200,90,60,0.35)' }}
      >
        Remove from board
      </button>
      </fieldset>
    </aside>
  )
}

function TitleSetting({ value, placeholder, onSave }: {
  value: string; placeholder: string; onSave: (value: string) => Promise<boolean>
}) {
  const [draft, setDraft] = useState<string | null>(null)
  async function commit() {
    if (draft !== null && await onSave(draft)) setDraft(null)
  }
  return <div className="flex gap-2">
    <input type="text" aria-label="Custom widget title" value={draft ?? value} maxLength={40}
      placeholder={placeholder} onChange={(event) => setDraft(event.target.value)}
      onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); void commit() } }}
      className="min-w-0 flex-1 rounded-md border border-(--line) bg-transparent px-2.5 py-1.5 font-mono text-[12px] text-paper placeholder:text-paper-faint focus:border-amber/40 focus:outline-hidden transition-colors" />
    <button type="button" disabled={draft === null} onClick={() => void commit()} className={editorBtnClass}>Save title</button>
  </div>
}
