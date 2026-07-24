// ─── Premieres & revivals (first watches vs. encores by month) ────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, maxOrOne } from 'src/lib/utils'
import { deriveRevivals } from 'src/store/ledgerDerive'
import { describeLedgerSettings, settingsDepKey, type LedgerPanelWidth, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { Panel, PanelEmpty, FOOTER_CAPTION, COL_GROW_ANIMATION } from '../PanelShell'
import { monthLabel } from '../labels'

export function PremieresRevivals({ className, settings, width = 'md' }: { className?: string; settings?: LedgerWidgetSettings; width?: LedgerPanelWidth }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = settingsDepKey(settings)
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
  const maxHalf = maxOrOne(months.flatMap((m) => [m.premieres, m.revivals]))
  const labelBudget = width === 'sm' ? 4 : width === 'md' ? 6 : width === 'lg' ? 9 : 12
  const labelStep = Math.max(1, Math.ceil(months.length / labelBudget))
  const halfHeight = width === 'sm' ? 70 : width === 'md' ? 82 : 92

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
      <div className="min-w-0 overflow-hidden">
        <div className="flex min-w-0 items-stretch gap-[clamp(2px,0.6vw,8px)]">
          {months.map((m, i) => (
            <div
              key={m.month}
              className="flex min-w-0 flex-1 flex-col items-center"
              {...tip.bind({
                label: `${monthLabel(m.month)} ${m.month.slice(0, 4)}`,
                value: `${m.premieres} premiere${m.premieres !== 1 ? 's' : ''} · ${m.revivals} revival${m.revivals !== 1 ? 's' : ''}`,
              })}
            >
              {/* Premieres grow up from the center axis */}
              <div className="w-full flex items-end justify-center" style={{ height: halfHeight }}>
                <div
                  className="w-full max-w-[22px] rounded-t-md"
                  style={{
                    height: `${(m.premieres / maxHalf) * 100}%`,
                    minHeight: m.premieres > 0 ? 3 : 0,
                    background: 'linear-gradient(180deg, var(--amber-bright), var(--amber-deep))',
                    transformOrigin: 'bottom',
                    transform: 'scaleY(0)',
                    animation: COL_GROW_ANIMATION,
                    animationDelay: `${i * 45}ms`,
                  }}
                />
              </div>
              {/* Center axis */}
              <div className="w-full h-px shrink-0 bg-[var(--line-2)]" />
              {/* Revivals hang down from the axis */}
              <div className="w-full flex items-start justify-center" style={{ height: halfHeight }}>
                <div
                  className="w-full max-w-[22px] rounded-b-md"
                  style={{
                    height: `${(m.revivals / maxHalf) * 100}%`,
                    minHeight: m.revivals > 0 ? 3 : 0,
                    background: 'linear-gradient(180deg, rgba(154,163,178,0.75), rgba(154,163,178,0.3))',
                    transformOrigin: 'top',
                    transform: 'scaleY(0)',
                    animation: COL_GROW_ANIMATION,
                    animationDelay: `${i * 45 + 60}ms`,
                  }}
                />
              </div>
              <span className="font-mono text-[9px] text-paper-faint mt-1 whitespace-nowrap">
                {(i % labelStep === 0 || i === months.length - 1) ? monthLabel(m.month) : '\u00a0'}
              </span>
            </div>
          ))}
        </div>
      </div>
      <p className={cn('mt-3 flex items-center gap-4', FOOTER_CAPTION)}>
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
