// ─── Shifting standards (average rating over time) ────────────────────────────

import { useId, useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { areaPath, linePath } from 'src/components/LedgerCharts'
import { deriveTrajectory } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { Panel, PanelEmpty } from '../PanelShell'

export function ShiftingStandards({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = JSON.stringify(settings ?? {})
  const gradientId = useId()
  const tip = useChartTip()

  const { points: quarters, allTimeAvg } = useMemo(
    () => deriveTrajectory(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey],
  )

  const panelTitle = settings?.title || 'Shifting standards'
  const hint = `your average, over time${allTimeAvg ? ` · ${allTimeAvg.toFixed(1)} all-time` : ''}${describeLedgerSettings(settings)}`

  // Rating axis is fixed 0–5 so the line's height is comparable across boards.
  const yFor = (avg: number) => 176 - (avg / 5) * 152
  const chartPoints = useMemo(
    () =>
      quarters.map((q, i) => ({
        x: quarters.length === 1 ? 500 : (i / (quarters.length - 1)) * 1000,
        y: yFor(q.avg),
      })),
    [quarters],
  )

  if (quarters.length === 0) {
    return (
      <Panel title={panelTitle} hint={hint} className={className}>
        <PanelEmpty message="Rate a few dated screenings to chart your standards" />
      </Panel>
    )
  }

  const refY = yFor(allTimeAvg)

  return (
    <Panel title={panelTitle} hint={hint} className={className}>
      <div className="overflow-x-auto overflow-y-hidden scrollbar-thin">
        <div style={{ minWidth: Math.max(quarters.length * 68, 420) }}>
          <div className="relative w-full h-[150px]">
            <svg viewBox="0 0 1000 190" preserveAspectRatio="none" className="absolute inset-0 w-full h-full block">
              <defs>
                <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor="var(--amber)" stopOpacity="0.3" />
                  <stop offset="100%" stopColor="var(--amber)" stopOpacity="0" />
                </linearGradient>
              </defs>
              {/* All-time average reference */}
              <line
                x1={0}
                y1={refY}
                x2={1000}
                y2={refY}
                stroke="var(--paper-faint)"
                strokeWidth={1}
                strokeDasharray="6 6"
                vectorEffect="non-scaling-stroke"
                opacity={0.6}
              />
              <path d={areaPath(chartPoints, 176)} fill={`url(#${gradientId})`} />
              <path
                d={linePath(chartPoints)}
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
            {/* HTML dots, not SVG circles — preserveAspectRatio="none" stretches
                x/y independently, which would turn <circle> into ellipses. */}
            {chartPoints.map((p, i) => (
              <span
                key={i}
                {...tip.bind({
                  label: quarters[i].quarter,
                  value: `${quarters[i].avg.toFixed(1)}★ over ${quarters[i].count} title${quarters[i].count !== 1 ? 's' : ''}`,
                })}
                className="absolute rounded-full -translate-x-1/2 -translate-y-1/2"
                style={{
                  left: `${(p.x / 1000) * 100}%`,
                  top: `${(p.y / 190) * 100}%`,
                  width: 10,
                  height: 10,
                  background: 'var(--ink-1)',
                  border: '2.5px solid var(--amber-bright)',
                }}
              />
            ))}
          </div>
          <div className="flex justify-between px-1">
            {quarters.map((q) => (
              <span key={q.quarter} className="font-mono text-[9px] text-paper-faint whitespace-nowrap">
                {q.quarter}
              </span>
            ))}
          </div>
        </div>
      </div>
      <p className="mt-4 font-mono text-[10px] tracking-[0.16em] uppercase text-paper-faint">
        dashed line marks your {allTimeAvg.toFixed(1)}★ all-time average
      </p>
      {tip.node}
    </Panel>
  )
}
