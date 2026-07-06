// ─── Feature lengths (movie runtime buckets) ──────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import type { LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty } from '../PanelShell'

const RUNTIME_BUCKETS = [
  { label: 'Short & sweet', range: 'under 90m', min: 0, max: 90 },
  { label: 'Standard feature', range: '90–120m', min: 90, max: 120 },
  { label: 'The long haul', range: '120–150m', min: 120, max: 150 },
  { label: 'An epic', range: '150m and up', min: 150, max: Infinity },
]

export function RuntimeSpectrum({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
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

  const maxCount = Math.max(...rows.map((r) => r.count), 1)

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
            <div key={b.label} className="flex items-center gap-3">
              <div className="w-[128px] shrink-0">
                <span
                  className="font-serif text-sm font-medium text-paper block leading-tight"
                  style={{ fontVariationSettings: '"opsz" 30' }}
                >
                  {b.label}
                </span>
                <span className="font-mono text-[9px] text-paper-faint">{b.range}</span>
              </div>
              <div className="flex-1 h-[16px] rounded-sm bg-[var(--wash)] overflow-hidden">
                <div
                  className="h-full rounded-sm bar-fill"
                  style={{
                    width: `${(b.count / maxCount) * 100}%`,
                    background: 'linear-gradient(90deg, var(--amber-deep), var(--amber-bright))',
                    animationDelay: `${i * 80}ms`,
                  }}
                />
              </div>
              <span className="font-mono text-[11px] text-paper-dim w-8 text-right shrink-0">{b.count}</span>
            </div>
          ))}
        </div>
      )}
    </Panel>
  )
}
