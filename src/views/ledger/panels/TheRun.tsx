// ─── The run (monthly screening trend) ────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { areaPath, linePath } from 'src/lib/utils'
import { deriveMonthlySeries } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { Panel } from '../PanelShell'
import { monthLabel } from '../labels'

export function TheRun({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = JSON.stringify(settings ?? {})
  const tip = useChartTip()

  // Gap-filled month series: the x-axis represents a true, evenly-spaced
  // calendar timeline rather than compressing silent months out of existence.
  const recent = useMemo(
    () => deriveMonthlySeries(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey],
  )

  const maxCount = Math.max(...recent.map((d) => d.count), 1)
  const total = recent.reduce((sum, d) => sum + d.count, 0)

  const points = useMemo(
    () =>
      recent.map((d, i) => ({
        x: recent.length === 1 ? 500 : (i / (recent.length - 1)) * 1000,
        y: 170 - (d.count / maxCount) * 140,
      })),
    [recent, maxCount],
  )

  const requestView = useAppStore((s) => s.requestView)

  return (
    <Panel
      title={settings?.title || 'The run'}
      hint={`monthly screenings · last ${recent.length} mo${describeLedgerSettings(settings)}`}
      className={className}
    >
      {total === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 gap-3">
          <p className="text-center text-sm text-paper-faint">No screenings in the past year</p>
          <button
            onClick={() => requestView('library')}
            className="text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
          >
            Browse Library
          </button>
        </div>
      ) : (
        <div>
          <div className="overflow-x-auto overflow-y-hidden scrollbar-thin">
            <div style={{ minWidth: Math.max(recent.length * 52, 420) }}>
              <div className="relative w-full h-[130px]">
                <svg viewBox="0 0 1000 190" preserveAspectRatio="none" className="absolute inset-0 w-full h-full block">
                  <defs>
                    <linearGradient id="run-area" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="var(--moon)" stopOpacity="0.4" />
                      <stop offset="100%" stopColor="var(--moon)" stopOpacity="0" />
                    </linearGradient>
                  </defs>
                  <path d={areaPath(points, 170)} fill="url(#run-area)" />
                  <path
                    d={linePath(points)}
                    fill="none"
                    stroke="var(--moon)"
                    strokeWidth={3}
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    pathLength={1}
                    className="chart-path-draw"
                    style={{ strokeDasharray: 1 }}
                    vectorEffect="non-scaling-stroke"
                  />
                </svg>
                {/* HTML dots, not SVG circles — preserveAspectRatio="none" stretches
                    x/y independently, which would turn <circle> into ellipses. */}
                {points.map(
                  (p, i) =>
                    recent[i].count > 0 && (
                      <span
                        key={i}
                        {...tip.bind({
                          label: `${monthLabel(recent[i].month)} ${recent[i].month.slice(0, 4)}`,
                          value: `${recent[i].count} screening${recent[i].count !== 1 ? 's' : ''}`,
                        })}
                        className="absolute rounded-full -translate-x-1/2 -translate-y-1/2"
                        style={{
                          left: `${(p.x / 1000) * 100}%`,
                          top: `${(p.y / 190) * 100}%`,
                          width: 10,
                          height: 10,
                          background: 'var(--moon)',
                        }}
                      />
                    ),
                )}
              </div>
              <div className="flex justify-between px-1">
                {recent.map((d) => (
                  <span key={d.month} className="font-mono text-[9px] text-paper-faint">
                    {monthLabel(d.month)}
                  </span>
                ))}
              </div>
            </div>
          </div>
          <p className="mt-4 font-mono text-[10px] tracking-[0.16em] uppercase text-paper-faint">
            {total} screening{total !== 1 ? 's' : ''} across the last {recent.length} months
          </p>
          {tip.node}
        </div>
      )}
    </Panel>
  )
}
