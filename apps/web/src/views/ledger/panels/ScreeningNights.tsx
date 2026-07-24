// ─── Screening nights (viewings by day of week) ───────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, maxOrOne } from 'src/lib/utils'
import { scopedTitles, dateInRange } from 'src/store/ledgerDerive'
import {
  describeLedgerSettings,
  settingsDepKey,
  type LedgerPanelWidth,
  type LedgerWidgetSettings,
} from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty, COL_GROW_ANIMATION } from '../PanelShell'

const DAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

export function ScreeningNights({
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

  const { counts, total, peak } = useMemo(() => {
    const { titles: scoped, rangeStart } = scopedTitles('weekdays', titles, settings)
    const counts = Array.from({ length: 7 }, () => 0)
    for (const t of scoped) {
      for (const v of t.viewings) {
        if (!v.date || !dateInRange(v.date, rangeStart)) continue
        const [y, m, d] = v.date.split('-').map(Number)
        counts[new Date(y, m - 1, d).getDay()]++
      }
    }
    const total = counts.reduce((sum, c) => sum + c, 0)
    const peak = counts.indexOf(Math.max(...counts))
    return { counts, total, peak }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [titles, settingsKey])

  const maxCount = maxOrOne(counts)
  const radarPoints = counts
    .map((count, index) => {
      const angle = -Math.PI / 2 + (index * Math.PI * 2) / 7
      const radius = 24 + (count / maxCount) * 64
      return `${100 + Math.cos(angle) * radius},${100 + Math.sin(angle) * radius}`
    })
    .join(' ')
  const guidePoints = [0.33, 0.66, 1].map((scale) =>
    DAY_LABELS.map((_, index) => {
      const angle = -Math.PI / 2 + (index * Math.PI * 2) / 7
      return `${100 + Math.cos(angle) * 88 * scale},${100 + Math.sin(angle) * 88 * scale}`
    }).join(' ')
  )
  const weekend = counts[0] + counts[6]

  return (
    <Panel
      title={settings?.title || 'Screening nights'}
      hint={
        (total ? `by day of week · ${DAY_LABELS[peak]} is busiest` : 'by day of week') +
        describeLedgerSettings(settings)
      }
      className={className}
    >
      {total === 0 ? (
        <PanelEmpty message="No dated screenings yet" />
      ) : (
        <div
          className={cn(
            'flex min-h-0 flex-1 items-center justify-center',
            width === 'full' ? 'gap-12' : width === 'lg' ? 'gap-9' : 'gap-6'
          )}
        >
          <div className="relative aspect-square h-full max-h-[250px] min-h-[190px] shrink-0">
            <svg
              viewBox="0 0 200 200"
              className="h-full w-full overflow-visible"
              role="img"
              aria-label="Screenings by day radar chart"
            >
              {guidePoints.map((points, index) => (
                <polygon
                  key={index}
                  points={points}
                  fill="none"
                  stroke="var(--line-2)"
                  strokeWidth="1"
                />
              ))}
              {DAY_LABELS.map((_, index) => {
                const angle = -Math.PI / 2 + (index * Math.PI * 2) / 7
                return (
                  <line
                    key={index}
                    x1="100"
                    y1="100"
                    x2={100 + Math.cos(angle) * 88}
                    y2={100 + Math.sin(angle) * 88}
                    stroke="var(--line)"
                    strokeWidth="1"
                  />
                )
              })}
              <polygon
                points={radarPoints}
                fill="color-mix(in srgb, var(--amber) 26%, transparent)"
                stroke="var(--amber-bright)"
                strokeWidth="2"
              />
              {counts.map((count, index) => {
                const [x, y] = radarPoints.split(' ')[index].split(',').map(Number)
                return (
                  <circle
                    key={index}
                    cx={x}
                    cy={y}
                    r={index === peak ? 4 : 2.5}
                    fill={index === peak ? 'var(--amber-bright)' : 'var(--amber)'}
                  >
                    <title>
                      {DAY_LABELS[index]}: {count}
                    </title>
                  </circle>
                )
              })}
              {DAY_LABELS.map((label, index) => {
                const angle = -Math.PI / 2 + (index * Math.PI * 2) / 7
                return (
                  <text
                    key={label}
                    x={100 + Math.cos(angle) * 104}
                    y={104 + Math.sin(angle) * 104}
                    textAnchor="middle"
                    className="fill-paper-faint font-mono text-[9px] uppercase"
                  >
                    {label}
                  </text>
                )
              })}
            </svg>
            <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center pt-1">
              <span className="stat-num text-3xl">{DAY_LABELS[peak]}</span>
              <span className="font-mono text-[8px] uppercase tracking-[0.15em] text-paper-faint">
                peak night
              </span>
            </div>
          </div>

          {width !== 'sm' && (
            <div className={cn('min-w-0 flex-1', width === 'md' ? 'max-w-[210px]' : '')}>
              <div className="mb-5 grid grid-cols-2 gap-3">
                <div>
                  <span className="stat-num text-3xl">{total}</span>
                  <p className="font-mono text-[9px] uppercase tracking-widest text-paper-faint">
                    screenings
                  </p>
                </div>
                <div>
                  <span className="stat-num text-3xl">{Math.round((weekend / total) * 100)}%</span>
                  <p className="font-mono text-[9px] uppercase tracking-widest text-paper-faint">
                    weekend
                  </p>
                </div>
              </div>
              {width !== 'md' && (
                <div className="flex h-[132px] items-end gap-2 border-b border-[var(--line-2)]">
                  {counts.map((count, day) => (
                    <div key={day} className="flex h-full flex-1 flex-col justify-end gap-1">
                      <span className="text-center font-mono text-[9px] text-paper-faint">
                        {count}
                      </span>
                      <i
                        className="block w-full rounded-t-sm"
                        style={{
                          height: `${Math.max(5, (count / maxCount) * 82)}%`,
                          background: day === peak ? 'var(--amber-bright)' : 'var(--amber-deep)',
                          transformOrigin: 'bottom',
                          animation: COL_GROW_ANIMATION,
                          animationDelay: `${day * 50}ms`,
                        }}
                      />
                      <span className="text-center font-mono text-[8px] uppercase text-paper-faint">
                        {DAY_LABELS[day]}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </Panel>
  )
}
