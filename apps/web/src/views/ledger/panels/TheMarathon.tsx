// ─── The marathon (screening streaks) ─────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { deriveStreaks } from 'src/store/ledgerDerive'
import { describeLedgerSettings, settingsDepKey, type LedgerPanelWidth, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty, FOOTER_CAPTION } from '../PanelShell'

export function TheMarathon({ className, settings, width = 'sm' }: { className?: string; settings?: LedgerWidgetSettings; width?: LedgerPanelWidth }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = settingsDepKey(settings)

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
      <div className={cn('flex items-start py-1', width === 'sm' ? 'justify-between gap-4' : 'justify-center gap-16')}>
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
      <div className={cn('mt-5', width !== 'sm' && 'mx-auto max-w-[520px]')}>
        <div className="grid gap-[clamp(3px,0.5vw,7px)]" style={{ gridTemplateColumns: 'repeat(15, minmax(0, 1fr))' }}>
          {streaks.last30.map((active, i) => {
            const isToday = i === streaks.last30.length - 1
            return (
              <span
                key={i}
                className={cn('w-full max-w-[13px] aspect-square rounded-full justify-self-center', isToday && 'ring-1 ring-amber-bright/60 ring-offset-1 ring-offset-transparent')}
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

      <p className={cn('mt-5', FOOTER_CAPTION)}>
        {streaks.totalDays} distinct screening night{streaks.totalDays !== 1 ? 's' : ''} on record
      </p>
    </Panel>
  )
}
