// ─── Premieres & revivals (first watches vs. encores by month) ────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { deriveRevivals } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { Panel, PanelEmpty } from '../PanelShell'
import { monthLabel } from '../labels'

export function PremieresRevivals({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = JSON.stringify(settings ?? {})
  const tip = useChartTip()

  const months = useMemo(
    () => deriveRevivals(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey],
  )

  const totals = months.reduce(
    (acc, m) => ({ premieres: acc.premieres + m.premieres, revivals: acc.revivals + m.revivals }),
    { premieres: 0, revivals: 0 },
  )
  const maxHalf = Math.max(...months.flatMap((m) => [m.premieres, m.revivals]), 1)

  const panelTitle = settings?.title || 'Premieres & revivals'
  const hint = `first watches vs. encores${describeLedgerSettings(settings)}`

  if (totals.premieres + totals.revivals === 0) {
    return (
      <Panel title={panelTitle} hint={hint} className={className}>
        <PanelEmpty message="No dated screenings in this window" />
      </Panel>
    )
  }

  return (
    <Panel title={panelTitle} hint={hint} className={className}>
      <div className="overflow-x-auto overflow-y-hidden scrollbar-thin">
        <div className="flex items-stretch gap-2" style={{ minWidth: Math.max(months.length * 42, 320) }}>
          {months.map((m, i) => (
            <div
              key={m.month}
              className="flex-1 min-w-[30px] flex flex-col items-center"
              {...tip.bind({
                label: `${monthLabel(m.month)} ${m.month.slice(0, 4)}`,
                value: `${m.premieres} premiere${m.premieres !== 1 ? 's' : ''} · ${m.revivals} revival${m.revivals !== 1 ? 's' : ''}`,
              })}
            >
              {/* Premieres grow up from the center axis */}
              <div className="h-[92px] w-full flex items-end justify-center">
                <div
                  className="w-full max-w-[22px] rounded-t-md"
                  style={{
                    height: `${(m.premieres / maxHalf) * 100}%`,
                    minHeight: m.premieres > 0 ? 3 : 0,
                    background: 'linear-gradient(180deg, var(--amber-bright), var(--amber-deep))',
                    transformOrigin: 'bottom',
                    transform: 'scaleY(0)',
                    animation: 'col-grow 0.7s var(--ease) forwards',
                    animationDelay: `${i * 45}ms`,
                  }}
                />
              </div>
              {/* Center axis */}
              <div className="w-full h-px shrink-0 bg-[var(--line-2)]" />
              {/* Revivals hang down from the axis */}
              <div className="h-[92px] w-full flex items-start justify-center">
                <div
                  className="w-full max-w-[22px] rounded-b-md"
                  style={{
                    height: `${(m.revivals / maxHalf) * 100}%`,
                    minHeight: m.revivals > 0 ? 3 : 0,
                    background: 'linear-gradient(180deg, rgba(154,163,178,0.75), rgba(154,163,178,0.3))',
                    transformOrigin: 'top',
                    transform: 'scaleY(0)',
                    animation: 'col-grow 0.7s var(--ease) forwards',
                    animationDelay: `${i * 45 + 60}ms`,
                  }}
                />
              </div>
              <span className="font-mono text-[9px] text-paper-faint mt-1 whitespace-nowrap">
                {monthLabel(m.month)}
              </span>
            </div>
          ))}
        </div>
      </div>
      <p className="mt-3 font-mono text-[10px] tracking-[0.16em] uppercase text-paper-faint flex items-center gap-4">
        <span className="flex items-center gap-1.5">
          <i className="w-2 h-2 rounded-sm" style={{ background: 'var(--amber-bright)' }} /> {totals.premieres} premieres
        </span>
        <span className="flex items-center gap-1.5">
          <i className="w-2 h-2 rounded-sm" style={{ background: 'var(--moon)' }} /> {totals.revivals} revivals
        </span>
      </p>
      {tip.node}
    </Panel>
  )
}
