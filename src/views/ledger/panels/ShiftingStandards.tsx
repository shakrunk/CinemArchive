// ─── Shifting standards (average rating over time) ────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { deriveTrajectory } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { MiniLineChart, type SparklinePoint } from 'src/components/LedgerCharts'
import { Panel, PanelEmpty } from '../PanelShell'

export function ShiftingStandards({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = JSON.stringify(settings ?? {})
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
  const chartPoints: SparklinePoint[] = useMemo(
    () =>
      quarters.map((q, i) => ({
        x: quarters.length === 1 ? 500 : (i / (quarters.length - 1)) * 1000,
        y: yFor(q.avg),
        tooltip: { label: q.quarter, value: `${q.avg.toFixed(1)}★ over ${q.count} title${q.count !== 1 ? 's' : ''}` },
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
          <MiniLineChart
            points={chartPoints}
            viewBoxHeight={190}
            areaBaseline={176}
            heightPx={150}
            lineColor="var(--amber-bright)"
            areaColor="var(--amber)"
            areaOpacity={0.3}
            tipBind={tip.bind}
            beforePaths={
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
            }
          />
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
