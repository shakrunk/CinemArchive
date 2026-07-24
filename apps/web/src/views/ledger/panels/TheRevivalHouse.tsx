// ─── The revival house (how old the pictures are at showtime) ─────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { toPercent } from 'src/lib/utils'
import { deriveTimewarp } from 'src/store/ledgerDerive'
import { describeLedgerSettings, settingsDepKey, type LedgerPanelWidth, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { Panel, PanelEmpty } from '../PanelShell'

/** Amber→ink ramp across the age buckets, newest first. */
const BUCKET_COLORS = [
  'var(--amber-bright)',
  'var(--amber)',
  'var(--amber-deep)',
  'rgba(128,115,95,0.7)',
  'rgba(128,115,95,0.4)',
]

export function TheRevivalHouse({ className, settings, width = 'md' }: { className?: string; settings?: LedgerWidgetSettings; width?: LedgerPanelWidth }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = settingsDepKey(settings)
  const tip = useChartTip()

  const { buckets, median, total } = useMemo(
    () => deriveTimewarp(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey],
  )

  const panelTitle = settings?.title || 'The revival house'
  const hint = `title age at showtime${describeLedgerSettings(settings)}`

  if (total === 0) {
    return (
      <Panel title={panelTitle} hint={hint} className={className}>
        <PanelEmpty message="No dated screenings yet" />
      </Panel>
    )
  }

  return (
    <Panel title={panelTitle} hint={hint} className={className}>
      {/* Proportional spectrum strip */}
      <div className={`flex rounded-lg overflow-hidden border border-[var(--line)] ${width === 'sm' ? 'h-12' : width === 'full' ? 'h-20' : 'h-14'}`}>
        {buckets.map(
          (b, i) =>
            b.count > 0 && (
              <div
                key={b.key}
                {...tip.bind({
                  label: `${b.label} (${b.range})`,
                  value: `${b.count} screening${b.count !== 1 ? 's' : ''}`,
                })}
                className="flex items-center justify-center min-w-[34px]"
                style={{ flexGrow: b.count, background: BUCKET_COLORS[i] }}
              >
                <span
                  className="font-mono text-[10px] font-medium"
                  style={{ color: i < 3 ? 'var(--on-amber)' : 'var(--paper)' }}
                >
                  {toPercent(b.count, total)}%
                </span>
              </div>
            ),
        )}
      </div>

      {/* Legend */}
      <div className={`grid gap-x-4 gap-y-2 mt-4 ${width === 'sm' ? 'grid-cols-2' : width === 'md' ? 'grid-cols-3' : 'grid-cols-5'}`}>
        {buckets.map((b, i) => (
          <div key={b.key} className="flex items-center gap-2">
            <i className="w-2.5 h-2.5 rounded-sm shrink-0" style={{ background: BUCKET_COLORS[i] }} />
            <span className="min-w-0">
              <span className="block text-[12px] text-paper leading-tight truncate">{b.label}</span>
              <span className="block font-mono text-[9px] text-paper-faint">
                {b.range} · {b.count}
              </span>
            </span>
          </div>
        ))}
      </div>

      <p className="mt-5 font-serif text-[15px] text-paper-dim">
        The median picture is{' '}
        <strong className="text-paper font-semibold">
          {median === 0 ? 'brand new' : `${median} year${median !== 1 ? 's' : ''} old`}
        </strong>{' '}
        at showtime.
      </p>
      {tip.node}
    </Panel>
  )
}
