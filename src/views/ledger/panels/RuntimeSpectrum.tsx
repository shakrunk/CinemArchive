// ─── Feature lengths (movie runtime buckets) ──────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { maxOrOne, rankBarFill } from 'src/lib/utils'
import type { LedgerPanelWidth, LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty, RowTitle } from '../PanelShell'

const RUNTIME_BUCKETS = [
  { label: 'Short & sweet', range: 'under 90m', min: 0, max: 90 },
  { label: 'Standard feature', range: '90–120m', min: 90, max: 120 },
  { label: 'The long haul', range: '120–150m', min: 120, max: 150 },
  { label: 'An epic', range: '150m and up', min: 150, max: Infinity },
]

export function RuntimeSpectrum({ className, settings, width = 'md' }: { className?: string; settings?: LedgerWidgetSettings; width?: LedgerPanelWidth }) {
  const titles = useAppStore((s) => s.titles)

  const { rows, total, avg } = useMemo(() => {
    const runtimes = titles
      .filter((t) => t.type === 'movie' && (t.runtime ?? 0) > 0)
      .map((t) => t.runtime as number)
    const rows = RUNTIME_BUCKETS.map((b) => ({
      ...b,
      count: runtimes.filter((r) => r >= b.min && r < b.max).length,
    }))
    const avg = runtimes.length
      ? Math.round(runtimes.reduce((sum, r) => sum + r, 0) / runtimes.length)
      : 0
    return { rows, total: runtimes.length, avg }
  }, [titles])

  const maxCount = maxOrOne(rows.map((r) => r.count))

  return (
    <Panel
      title={settings?.title || 'Feature lengths'}
      hint={total ? `movie runtimes · ${avg}m avg` : 'movie runtimes'}
      className={className}
    >
      {total === 0 ? (
        <PanelEmpty message="No movie runtimes yet" />
      ) : (
        <div className="flex flex-col gap-4 py-1">
          {rows.map((b, i) => (
            <div key={b.label} className={width === 'sm' ? 'grid grid-cols-[1fr_auto] gap-x-3 gap-y-1' : 'flex items-center gap-3'}>
              <div className={width === 'sm' ? 'min-w-0' : 'w-[clamp(112px,24%,160px)] shrink-0'}>
                <RowTitle className="block leading-tight">{b.label}</RowTitle>
                <span className="font-mono text-[9px] text-paper-faint">{b.range}</span>
              </div>
              <div className={width === 'sm' ? 'col-span-2 h-[14px] rounded-sm bg-[var(--wash)] overflow-hidden' : 'flex-1 h-[16px] rounded-sm bg-[var(--wash)] overflow-hidden'}>
                <div
                  className="h-full rounded-sm bar-fill"
                  style={rankBarFill(b.count / maxCount, true, i * 80)}
                />
              </div>
              <span className={width === 'sm' ? 'col-start-2 row-start-1 font-mono text-[11px] text-paper-dim text-right' : 'font-mono text-[11px] text-paper-dim w-8 text-right shrink-0'}>{b.count}</span>
            </div>
          ))}
        </div>
      )}
    </Panel>
  )
}
