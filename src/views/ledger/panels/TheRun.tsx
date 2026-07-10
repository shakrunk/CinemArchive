// ─── The run (monthly screening trend) ────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, maxOrOne } from 'src/lib/utils'
import { deriveMonthlySeries } from 'src/store/ledgerDerive'
import { describeLedgerSettings, settingsDepKey, type LedgerPanelWidth, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { MiniLineChart, type SparklinePoint } from 'src/components/LedgerCharts'
import { Panel, PanelEmpty, FOOTER_CAPTION } from '../PanelShell'
import { monthLabel, monthYearLabel } from '../labels'

// How many x-axis labels a card can carry before they start crowding —
// keyed by the widget's board-width preset since that's known up front (see
// panelRegistry.tsx). A ten-year "all time" window can gap-fill to 100+
// months; without thinning, every one of those would render a label. Only
// meaningful at the `lg` breakpoint and up — WIDTH_GRID_CLASSES makes every
// preset render full-width below `lg`, so MOBILE_LABEL_BUDGET covers that case.
const LABEL_BUDGET: Record<LedgerPanelWidth, number> = { sm: 4, md: 5, lg: 6, full: 9 }
const MOBILE_LABEL_BUDGET = 4

/** Evenly-spaced indices into a `length`-long series, always including the
 *  first and last, capped at `maxTicks`. Falls back to every index when the
 *  series is already short enough to not need thinning. */
function pickTickIndices(length: number, maxTicks: number): number[] {
  if (length <= maxTicks) return Array.from({ length }, (_, i) => i)
  if (maxTicks <= 1) return [length - 1]
  const ticks = new Set<number>([0, length - 1])
  const inner = maxTicks - 2
  for (let i = 1; i <= inner; i++) {
    ticks.add(Math.round((i * (length - 1)) / (inner + 1)))
  }
  return [...ticks].sort((a, b) => a - b)
}

export function TheRun({
  className,
  settings,
  width = 'full',
}: {
  className?: string
  settings?: LedgerWidgetSettings
  width?: LedgerPanelWidth
}) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = settingsDepKey(settings)
  const tip = useChartTip()

  // Gap-filled month series: the x-axis represents a true, evenly-spaced
  // calendar timeline rather than compressing silent months out of existence.
  const recent = useMemo(
    () => deriveMonthlySeries(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey],
  )

  const maxCount = maxOrOne(recent.map((d) => d.count))
  const total = recent.reduce((sum, d) => sum + d.count, 0)
  const spansMultipleYears = recent.length > 0 && recent[0].month.slice(0, 4) !== recent[recent.length - 1].month.slice(0, 4)
  const tickLabel = spansMultipleYears ? monthYearLabel : monthLabel

  const peak = useMemo(
    () => recent.reduce((best, d) => (d.count > best.count ? d : best), recent[0]),
    [recent],
  )
  const avgPerMonth = recent.length ? total / recent.length : 0

  const points: SparklinePoint[] = useMemo(
    () =>
      recent.map((d, i) => ({
        x: recent.length === 1 ? 500 : (i / (recent.length - 1)) * 1000,
        y: 170 - (d.count / maxCount) * 140,
        tooltip:
          d.count > 0
            ? {
                label: `${monthLabel(d.month)} ${d.month.slice(0, 4)}${d.month === peak.month ? ' · peak' : ''}`,
                value: `${d.count} screening${d.count !== 1 ? 's' : ''}`,
              }
            : undefined,
      })),
    [recent, maxCount, peak.month],
  )

  const desktopTicks = useMemo(() => pickTickIndices(recent.length, LABEL_BUDGET[width]), [recent.length, width])
  const mobileTicks = useMemo(() => pickTickIndices(recent.length, MOBILE_LABEL_BUDGET), [recent.length])

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
          <div className={cn('flex items-baseline mb-5', width === 'sm' ? 'gap-4' : 'gap-7')}>
            <RunStat value={total} label="total" size={width} />
            <RunStat value={peak.count} label={`peak · ${tickLabel(peak.month)}`} accent size={width} />
            <RunStat value={avgPerMonth.toFixed(1)} label="avg / mo" size={width} />
          </div>

          <div className="relative w-full" style={{ height: 130 }}>
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
          </div>
          {/* Two label sets: WIDTH_GRID_CLASSES only honors the board-width
             *  preset at `lg` and up — every preset renders full-width below
             *  that, so a mobile-safe budget takes over there regardless of
             *  which preset was configured on desktop. */}
          <TickRow className="flex lg:hidden" indices={mobileTicks} months={recent} tickLabel={tickLabel} />
          <TickRow className="hidden lg:flex" indices={desktopTicks} months={recent} tickLabel={tickLabel} />

          <p className={cn('mt-4', FOOTER_CAPTION)}>
            {total} screening{total !== 1 ? 's' : ''} across the last {recent.length} months
          </p>
          {tip.node}
        </div>
      )}
    </Panel>
  )
}

function RunStat({
  value,
  label,
  accent,
  size,
}: {
  value: number | string
  label: string
  accent?: boolean
  size: LedgerPanelWidth
}) {
  return (
    <div className="flex flex-col gap-0.5 min-w-0">
      <span
        className={cn('stat-num truncate', size === 'sm' ? 'text-[20px]' : 'text-[24px]')}
        style={accent ? { color: 'var(--amber-bright)' } : undefined}
      >
        {value}
      </span>
      <span className="font-mono text-[9px] tracking-[0.14em] uppercase text-paper-faint whitespace-nowrap">{label}</span>
    </div>
  )
}

// Absolutely-positioned x-axis labels, aligned to the same 0–1000 coordinate
// space MiniLineChart plots on. `className` toggles which of the two
// (mobile/desktop) label sets is visible at the current breakpoint.
function TickRow({
  className,
  indices,
  months,
  tickLabel,
}: {
  className: string
  indices: number[]
  months: { month: string; count: number }[]
  tickLabel: (month: string) => string
}) {
  return (
    <div className={cn('relative h-4 mt-2', className)}>
      {indices.map((idx) => {
        const isFirst = idx === 0
        const isLast = idx === months.length - 1
        return (
          <span
            key={months[idx].month}
            className="absolute font-mono text-[9px] text-paper-faint whitespace-nowrap"
            style={{
              left: `${(idx / Math.max(months.length - 1, 1)) * 100}%`,
              transform: isFirst ? 'translateX(0)' : isLast ? 'translateX(-100%)' : 'translateX(-50%)',
            }}
          >
            {tickLabel(months[idx].month)}
          </span>
        )
      })}
    </div>
  )
}
