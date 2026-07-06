// ─── Screening nights (viewings by day of week) ───────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { scopedTitles, dateInRange } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty } from '../PanelShell'

const DAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

export function ScreeningNights({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = JSON.stringify(settings ?? {})

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

  const maxCount = Math.max(...counts, 1)

  return (
    <Panel
      title={settings?.title || 'Screening nights'}
      hint={(total ? `by day of week · ${DAY_LABELS[peak]} is busiest` : 'by day of week') + describeLedgerSettings(settings)}
      className={className}
    >
      {total === 0 ? (
        <PanelEmpty message="No dated screenings yet" />
      ) : (
        <div className="flex items-end justify-between gap-2 sm:gap-4 h-[150px] px-1 pt-2">
          {counts.map((c, day) => (
            <div key={day} className="flex-1 flex flex-col items-center justify-end gap-1.5 h-full min-w-0">
              <span className="font-mono text-[10px] text-paper-dim">{c}</span>
              <div
                className="w-full max-w-[46px] rounded-t-md"
                style={{
                  height: `${Math.max((c / maxCount) * 100, 2)}%`,
                  background: day === peak
                    ? 'linear-gradient(180deg, var(--amber-bright), var(--amber-deep))'
                    : 'linear-gradient(180deg, rgba(128,115,95,0.55), rgba(128,115,95,0.25))',
                  boxShadow: day === peak ? '0 6px 18px -6px rgba(233,178,102,0.5)' : undefined,
                  transformOrigin: 'bottom',
                  transform: 'scaleY(0)',
                  animation: 'col-grow 0.7s var(--ease) forwards',
                  animationDelay: `${day * 60}ms`,
                }}
              />
              <span
                className={cn(
                  'font-mono text-[10px]',
                  day === peak ? 'text-amber-bright' : 'text-paper-faint',
                )}
              >
                {DAY_LABELS[day]}
              </span>
            </div>
          ))}
        </div>
      )}
    </Panel>
  )
}
