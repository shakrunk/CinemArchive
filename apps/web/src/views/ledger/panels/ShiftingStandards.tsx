// ─── Shifting standards (average rating over time) ────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { deriveTrajectory } from 'src/store/ledgerDerive'
import { describeLedgerSettings, settingsDepKey, type LedgerPanelWidth, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { MiniLineChart, type SparklinePoint } from 'src/components/LedgerCharts'
import { Panel, PanelEmpty, FOOTER_CAPTION } from '../PanelShell'

export function ShiftingStandards({ className, settings, width = 'md' }: { className?: string; settings?: LedgerWidgetSettings; width?: LedgerPanelWidth }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = settingsDepKey(settings)
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
  const labelBudget = width === 'sm' ? 4 : width === 'md' ? 6 : width === 'lg' ? 9 : 12
  const labelStep = Math.max(1, Math.ceil(quarters.length / labelBudget))

  return (
    <Panel title={panelTitle} hint={hint} className={className}>
      <div className="min-w-0 overflow-hidden">
        <div className="min-w-0">
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
            {quarters.map((q, i) => (
              <span key={q.quarter} className="font-mono text-[9px] text-paper-faint whitespace-nowrap">
                {(i % labelStep === 0 || i === quarters.length - 1) ? q.quarter : '\u00a0'}
              </span>
            ))}
          </div>
        </div>
      </div>
      <p className={cn('mt-4', FOOTER_CAPTION)}>
        dashed line marks your {allTimeAvg.toFixed(1)}★ all-time average
      </p>
      {tip.node}
    </Panel>
  )
}
