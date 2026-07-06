// ─── Rating distribution histogram ────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { ratingColorVar } from 'src/components/LedgerCharts'
import { Panel } from '../PanelShell'
import { renderStarLabel } from '../labels'

export function RatingDistribution({ className }: { className?: string }) {
  const dist = useAppStore((s) => s.stats.ratingDistribution)
  const avgRating = useAppStore((s) => s.stats.avgRating)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const data = dist.filter((d) => d.count > 0).sort((a, b) => b.rating - a.rating)
  const total = data.reduce((sum, d) => sum + d.count, 0)

  const gradient = useMemo(() => {
    if (total === 0) return 'var(--wash)'
    let cursor = 0
    const stops = data.map((d) => {
      const start = (cursor / total) * 100
      cursor += d.count
      const end = (cursor / total) * 100
      return `${ratingColorVar(d.rating)} ${start}% ${end}%`
    })
    return `conic-gradient(${stops.join(', ')})`
  }, [data, total])

  return (
    <Panel
      title="Critical record"
      hint={`rating distribution · ${avgRating.toFixed(1)} avg`}
      className={className}
    >
      {total === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 gap-3">
          <p className="text-center text-sm text-paper-faint">No ratings yet</p>
          <button
            onClick={() => requestView('library')}
            className="text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
          >
            Browse Library
          </button>
        </div>
      ) : (
        <div className="flex flex-col sm:flex-row items-center gap-8">
          <div className="relative w-[168px] h-[168px] shrink-0">
            <div className="donut-ring absolute inset-0 rounded-full" style={{ background: gradient }} />
            <div
              className="donut-hole absolute rounded-full flex flex-col items-center justify-center"
              style={{
                inset: '24px',
                background: 'linear-gradient(168deg, var(--ink-1), var(--ink-2))',
                border: '1px solid var(--line)',
              }}
            >
              <span className="stat-num text-[28px]">{avgRating.toFixed(1)}</span>
              <span className="font-mono text-[9px] tracking-[0.14em] uppercase text-paper-faint mt-1">
                avg · {total} rated
              </span>
            </div>
          </div>
          <div className="flex-1 w-full flex flex-col gap-0.5">
            {data.map((d) => (
              <button
                key={d.rating}
                onClick={() => {
                  setFilter('minRating', d.rating)
                  requestView('library')
                }}
                className="w-full flex items-center justify-between gap-3 px-2 py-1.5 rounded-md transition-colors hover:bg-[var(--wash)] cursor-pointer group"
              >
                <span className="flex items-center gap-2.5">
                  <i
                    className="w-2 h-2 rounded-full shrink-0"
                    style={{ background: ratingColorVar(d.rating), boxShadow: `0 0 8px -1px ${ratingColorVar(d.rating)}` }}
                  />
                  <span className="font-mono text-[12px] text-amber group-hover:text-amber-bright transition-colors">
                    {renderStarLabel(d.rating)}
                  </span>
                </span>
                <span className="font-mono text-[11px] text-paper-faint">
                  <span className="text-paper-dim">{d.count}</span> · {Math.round((d.count / total) * 100)}%
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
    </Panel>
  )
}
