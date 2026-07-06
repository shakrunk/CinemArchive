// ─── Activity heatmap (52-week calendar) ──────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, fmtReleaseDate } from 'src/lib/utils'
import { scopedTitles } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { Panel } from '../PanelShell'
import { localDateStr } from '../labels'

export function ActivityHeatmap({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = JSON.stringify(settings ?? {})

  const viewingCounts = useMemo(() => {
    const { titles: scoped } = scopedTitles('activity', titles, settings)
    const map = new Map<string, number>()
    for (const t of scoped) {
      for (const v of t.viewings) {
        if (v.date) map.set(v.date, (map.get(v.date) ?? 0) + 1)
      }
    }
    return map
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [titles, settingsKey])

  const todayStr = localDateStr(new Date())
  const tip = useChartTip()

  const { weeks, monthLabels, totalInYear } = useMemo(() => {
    const end = new Date()
    end.setDate(end.getDate() + (6 - end.getDay())) // advance to Saturday

    const start = new Date(end)
    start.setDate(end.getDate() - 52 * 7 + 1)

    const days: Array<{ date: string; count: number }> = []
    const cursor = new Date(start)
    while (cursor <= end) {
      const ds = localDateStr(cursor)
      days.push({ date: ds, count: viewingCounts.get(ds) ?? 0 })
      cursor.setDate(cursor.getDate() + 1)
    }

    const weeks: Array<Array<{ date: string; count: number }>> = []
    for (let i = 0; i < days.length; i += 7) weeks.push(days.slice(i, i + 7))

    const monthLabels: Array<{ weekIndex: number; label: string }> = []
    let lastMonth = -1
    let lastLabelWeek = -Infinity
    weeks.forEach((week, wi) => {
      const month = Number(week[0].date.slice(5, 7))
      if (month !== lastMonth) {
        lastMonth = month
        // Collision guard: a month boundary within 4 columns of the previous
        // label would overlap it (labels are wider than a 13px column).
        if (wi - lastLabelWeek < 4) return
        const d = new Date(Number(week[0].date.slice(0, 4)), month - 1, 1)
        monthLabels.push({ weekIndex: wi, label: d.toLocaleDateString('en-US', { month: 'short' }) })
        lastLabelWeek = wi
      }
    })

    const totalInYear = days.reduce((sum, d) => sum + d.count, 0)
    return { weeks, monthLabels, totalInYear }
  }, [viewingCounts])

  const maxCellCount = useMemo(
    () => Math.max(...weeks.flat().map((c) => c.count), 1),
    [weeks],
  )

  return (
    <Panel
      title={settings?.title || 'Time in the dark'}
      hint={`past 52 weeks${describeLedgerSettings(settings)}`}
      className={className}
    >
      <div
        className="overflow-x-auto -mx-1 px-1"
        role="img"
        aria-label={`52-week screening heatmap: ${totalInYear} screening${totalInYear !== 1 ? 's' : ''} in the past year`}
      >
        {/* Month labels */}
        <div className="flex mb-1.5">
          {weeks.map((_, wi) => {
            const label = monthLabels.find((m) => m.weekIndex === wi)?.label
            return (
              <div key={wi} className="w-[13px] overflow-visible shrink-0 mr-[2px]">
                {label && (
                  <span className="font-mono text-[9px] text-paper-faint leading-none whitespace-nowrap">
                    {label}
                  </span>
                )}
              </div>
            )
          })}
        </div>
        {/* Heatmap grid */}
        <div className="flex gap-[2px]">
          {weeks.map((week, wi) => (
            <div key={wi} className="flex flex-col gap-[2px]">
              {week.map((cell) => (
                <div
                  key={cell.date}
                  className={cn(
                    'w-[13px] h-[13px] rounded-[2px] transition-opacity',
                    cell.count > 0 ? 'bg-amber' : 'bg-[var(--wash)]',
                    cell.date === todayStr && 'ring-1 ring-amber-bright/60',
                  )}
                  style={
                    cell.count > 0
                      ? {
                          opacity: 0.32 + 0.68 * Math.min(cell.count / maxCellCount, 1),
                          boxShadow: '0 0 6px -1px rgba(233,178,102,0.4)',
                        }
                      : undefined
                  }
                  {...tip.bind({
                    label: fmtReleaseDate(cell.date),
                    value: cell.count > 0 ? `${cell.count} screening${cell.count !== 1 ? 's' : ''}` : 'no screenings',
                  })}
                />
              ))}
            </div>
          ))}
        </div>
      </div>
      <p className="mt-4 font-mono text-[10px] tracking-[0.16em] uppercase text-paper-faint">
        {totalInYear} screening{totalInYear !== 1 ? 's' : ''} in the past year
      </p>
      {tip.node}
    </Panel>
  )
}
