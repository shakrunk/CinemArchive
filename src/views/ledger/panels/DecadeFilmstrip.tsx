// ─── Decade filmstrip ─────────────────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { areaPath, linePath } from 'src/components/LedgerCharts'
import { scopedTitles } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel } from '../PanelShell'

const FILMSTRIP_HOLES = Array.from({ length: 28 })

export function DecadeFilmstrip({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const settingsKey = JSON.stringify(settings ?? {})

  const decades = useMemo(() => {
    const { titles: scoped } = scopedTitles('decades', titles, settings)
    const counts = new Map<number, number>()
    for (const t of scoped) {
      const decade = Math.floor(t.year / 10) * 10
      counts.set(decade, (counts.get(decade) ?? 0) + 1)
    }
    return [...counts.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([decade, count]) => ({ label: `${decade}s`, count }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [titles, settingsKey])

  const maxCount = Math.max(...decades.map((d) => d.count), 1)

  const points = useMemo(
    () =>
      decades.map((d, i) => ({
        x: decades.length === 1 ? 500 : (i / (decades.length - 1)) * 1000,
        y: 190 - (d.count / maxCount) * 164,
      })),
    [decades, maxCount],
  )

  return (
    <Panel
      title={settings?.title || 'By the era'}
      hint={`decade breakdown${describeLedgerSettings(settings)}`}
      className={className}
    >
      {decades.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 gap-3">
          <p className="text-center text-sm text-paper-faint">No titles yet</p>
          <button
            onClick={() => requestView('library')}
            className="text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
          >
            Browse Library
          </button>
        </div>
      ) : (
        <div
          className="rounded-xl overflow-x-auto overflow-y-hidden scrollbar-thin"
          style={{ background: 'linear-gradient(180deg, var(--ink-2), var(--ink-1))', border: '1px solid var(--line)' }}
        >
          <div style={{ minWidth: Math.max(decades.length * 64, 420) }}>
            <div className="filmstrip-holes pt-3">
              {FILMSTRIP_HOLES.map((_, i) => (
                <span key={i} />
              ))}
            </div>
            <div className="relative w-full h-[150px]">
              <svg viewBox="0 0 1000 200" preserveAspectRatio="none" className="absolute inset-0 w-full h-full block">
                <defs>
                  <linearGradient id="filmstrip-area" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="var(--amber)" stopOpacity="0.35" />
                    <stop offset="100%" stopColor="var(--amber)" stopOpacity="0" />
                  </linearGradient>
                </defs>
                <path d={areaPath(points, 190)} fill="url(#filmstrip-area)" />
                <path
                  d={linePath(points)}
                  fill="none"
                  stroke="var(--amber-bright)"
                  strokeWidth={3}
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  pathLength={1}
                  className="chart-path-draw"
                  style={{ strokeDasharray: 1 }}
                  vectorEffect="non-scaling-stroke"
                />
              </svg>
              {/* Rendered as HTML dots, not SVG circles — preserveAspectRatio="none"
                  stretches x/y independently, which would turn <circle> into ellipses. */}
              {points.map((p, i) => (
                <span
                  key={i}
                  className="absolute rounded-full -translate-x-1/2 -translate-y-1/2"
                  style={{
                    left: `${(p.x / 1000) * 100}%`,
                    top: `${(p.y / 200) * 100}%`,
                    width: 14,
                    height: 14,
                    background: 'var(--ink-1)',
                    border: '2.5px solid var(--amber-bright)',
                  }}
                />
              ))}
            </div>
            <div className="filmstrip-holes pb-3">
              {FILMSTRIP_HOLES.map((_, i) => (
                <span key={i} />
              ))}
            </div>
            <div className="flex justify-between px-3.5 pb-3.5 pt-1.5">
              {decades.map((d) => (
                <button
                  key={d.label}
                  onClick={() => {
                    setFilter('decades', [d.label])
                    requestView('library')
                  }}
                  className="flex flex-col items-center gap-0.5 group cursor-pointer"
                >
                  <span className="font-mono text-[11px] text-paper-dim group-hover:text-amber-bright transition-colors">
                    {d.label}
                  </span>
                  <span className="font-mono text-[9px] text-paper-faint">{d.count}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
    </Panel>
  )
}
