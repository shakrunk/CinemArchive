// ─── Activity heatmap (52-week calendar) ──────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { Panel } from '../PanelShell'
import { localDateStr } from '../labels'

export function ActivityHeatmap({ className }: { className?: string }) {
  const titles = useAppStore((s) => s.titles)

  const viewingCounts = useMemo(() => {
    const map = new Map<string, number>()
    for (const t of titles) {
      for (const v of t.viewings) {
        if (v.date) map.set(v.date, (map.get(v.date) ?? 0) + 1)
      }
    }
    return map
  }, [titles])

  const todayStr = localDateStr(new Date())

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
    weeks.forEach((week, wi) => {
      const month = Number(week[0].date.slice(5, 7))
      if (month !== lastMonth) {
        const d = new Date(Number(week[0].date.slice(0, 4)), month - 1, 1)
        monthLabels.push({ weekIndex: wi, label: d.toLocaleDateString('en-US', { month: 'short' }) })
        lastMonth = month
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
    <Panel title="Time in the dark" hint="past 52 weeks" className={className}>
      <div className="overflow-x-auto -mx-1 px-1">
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
                  title={
                    cell.count > 0
                      ? `${cell.date} - ${cell.count} viewing${cell.count !== 1 ? 's' : ''}`
                      : cell.date
                  }
                />
              ))}
            </div>
          ))}
        </div>
      </div>
      <p className="mt-4 font-mono text-[10px] tracking-[0.16em] uppercase text-paper-faint">
        {totalInYear} screening{totalInYear !== 1 ? 's' : ''} in the past year
      </p>
    </Panel>
  )
}
