// ─── The marathon (screening streaks) ─────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { deriveStreaks } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty } from '../PanelShell'

export function TheMarathon({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = JSON.stringify(settings ?? {})

  const streaks = useMemo(
    () => deriveStreaks(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey],
  )

  const panelTitle = settings?.title || 'The marathon'
  const hint = `consecutive nights${describeLedgerSettings(settings)}`

  if (streaks.totalDays === 0) {
    return (
      <Panel title={panelTitle} hint={hint} className={className}>
        <PanelEmpty message="No dated screenings yet" />
      </Panel>
    )
  }

  return (
    <Panel title={panelTitle} hint={hint} className={className}>
      <div className="flex items-start gap-8 py-1">
        <div className="flex flex-col">
          <span className="stat-num text-[clamp(34px,4vw,52px)]">
            {streaks.longest}
            <span className="unit">d</span>
          </span>
          <span className="font-mono text-[9px] tracking-[0.16em] uppercase text-paper-faint mt-1">
            longest streak
          </span>
        </div>
        <div className="flex flex-col">
          <span
            className="stat-num text-[clamp(34px,4vw,52px)]"
            style={streaks.current === 0 ? { color: 'var(--paper-faint)' } : undefined}
          >
            {streaks.current}
            <span className="unit">d</span>
          </span>
          <span className="font-mono text-[9px] tracking-[0.16em] uppercase text-paper-faint mt-1">
            current streak
          </span>
        </div>
      </div>

      {/* Trailing 30 days, one dot per night, today last */}
      <div className="mt-5">
        <div className="flex flex-wrap gap-[5px]">
          {streaks.last30.map((active, i) => {
            const isToday = i === streaks.last30.length - 1
            return (
              <span
                key={i}
                className={cn('w-[9px] h-[9px] rounded-full', isToday && 'ring-1 ring-amber-bright/60 ring-offset-1 ring-offset-transparent')}
                style={{
                  background: active ? 'var(--amber)' : 'var(--wash)',
                  boxShadow: active ? '0 0 6px -1px rgba(233,178,102,0.5)' : undefined,
                }}
              />
            )
          })}
        </div>
        <p className="mt-2 font-mono text-[9px] tracking-[0.14em] uppercase text-paper-faint">last 30 nights</p>
      </div>

      <p className="mt-5 font-mono text-[10px] tracking-[0.16em] uppercase text-paper-faint">
        {streaks.totalDays} distinct screening night{streaks.totalDays !== 1 ? 's' : ''} on record
      </p>
    </Panel>
  )
}
