// ─── The run (monthly screening trend) ────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { deriveMonthlySeries } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { MiniLineChart, type SparklinePoint } from 'src/components/LedgerCharts'
import { Panel, PanelEmpty } from '../PanelShell'
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

  const points: SparklinePoint[] = useMemo(
    () =>
      recent.map((d, i) => ({
        x: recent.length === 1 ? 500 : (i / (recent.length - 1)) * 1000,
        y: 170 - (d.count / maxCount) * 140,
        tooltip:
          d.count > 0
            ? { label: `${monthLabel(d.month)} ${d.month.slice(0, 4)}`, value: `${d.count} screening${d.count !== 1 ? 's' : ''}` }
            : undefined,
      })),
    [recent, maxCount],
  )

  return (
    <Panel
      title={settings?.title || 'The run'}
      hint={`monthly screenings · last ${recent.length} mo${describeLedgerSettings(settings)}`}
      className={className}
    >
      {total === 0 ? (
        <PanelEmpty message="No screenings in the past year" />
      ) : (
        <div>
          <div className="overflow-x-auto overflow-y-hidden scrollbar-thin">
            <div style={{ minWidth: Math.max(recent.length * 52, 420) }}>
              <MiniLineChart
                points={points}
                viewBoxHeight={190}
                areaBaseline={170}
                heightPx={130}
                lineColor="var(--moon)"
                areaOpacity={0.4}
                dotStyle="filled"
                tipBind={tip.bind}
              />
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
