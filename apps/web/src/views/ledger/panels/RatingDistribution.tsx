// ─── Rating distribution histogram ────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, ratingColorVar, toPercent } from 'src/lib/utils'
import { deriveRatingDistribution } from 'src/store/ledgerDerive'
import {
  describeLedgerSettings,
  settingsDepKey,
  type LedgerPanelWidth,
  type LedgerWidgetSettings,
} from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty } from '../PanelShell'
import { renderStarLabel } from '../labels'

export function RatingDistribution({
  className,
  settings,
  width = 'md',
}: {
  className?: string
  settings?: LedgerWidgetSettings
  width?: LedgerPanelWidth
}) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const settingsKey = settingsDepKey(settings)
  const { distribution, avgRating } = useMemo(
    () => deriveRatingDistribution(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey]
  )
  const data = distribution.filter((d) => d.count > 0).sort((a, b) => b.rating - a.rating)
  const total = data.reduce((sum, d) => sum + d.count, 0)
  const donutSize = width === 'sm' ? 108 : width === 'md' ? 156 : width === 'lg' ? 176 : 192

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
      title={settings?.title || 'Critical record'}
      hint={`rating distribution · ${avgRating.toFixed(1)} avg${describeLedgerSettings(settings)}`}
      className={className}
    >
      {total === 0 ? (
        <PanelEmpty message="No ratings yet" />
      ) : (
        <div
          className={cn(
            'flex items-center',
            width === 'sm' ? 'gap-4' : 'gap-8',
            width === 'sm' ? 'flex-col' : 'flex-row',
            width === 'full' && 'gap-12'
          )}
        >
          <div
            className="donut-ring relative shrink-0 rounded-full"
            style={{ width: donutSize, height: donutSize, background: gradient }}
          >
            <div
              className="donut-hole absolute rounded-full flex flex-col items-center justify-center"
              style={{
                inset: Math.round(donutSize * 0.145),
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
          <div
            className={cn(
              'flex-1 w-full min-w-0',
              width === 'full' ? 'grid grid-cols-2 gap-x-8 gap-y-1' : 'flex flex-col gap-1'
            )}
          >
            {data.map((d) => (
              <button
                key={d.rating}
                onClick={() => {
                  setFilter('minRating', d.rating)
                  requestView('library')
                }}
                className="flex w-full items-center gap-2.5 rounded-md px-2 py-1.5 text-left transition-colors hover:bg-[var(--wash)] cursor-pointer group"
              >
                <i
                  className="w-2 h-2 rounded-full shrink-0"
                  style={{
                    background: ratingColorVar(d.rating),
                    boxShadow: `0 0 8px -1px ${ratingColorVar(d.rating)}`,
                  }}
                />
                <span className="shrink-0 font-mono text-[12px] text-amber group-hover:text-amber-bright transition-colors">
                  {renderStarLabel(d.rating)}
                </span>
                {/* Dot leader — ties each star label to its count so wide rows still
                    read as one item, not two lists (KP-038). */}
                <span
                  aria-hidden="true"
                  className="flex-1 min-w-3 self-center border-b border-dotted opacity-50 translate-y-[1px]"
                  style={{ borderColor: 'var(--paper-faint)' }}
                />
                <span className="shrink-0 whitespace-nowrap text-right font-mono text-[11px] text-paper-faint">
                  <span className="text-paper-dim">{d.count}</span>
                  <span aria-hidden="true"> · </span>
                  {toPercent(d.count, total)}%
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
    </Panel>
  )
}
