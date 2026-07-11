// ─── Still rolling (series in progress) ───────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import { RadialRing } from 'src/components/LedgerCharts'
import { deriveProgress } from 'src/store/ledgerDerive'
import { settingsDepKey, type LedgerPanelWidth, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty, RowTitle, LIST_ROW_HOVER } from '../PanelShell'

export function StillRolling({ className, settings, width = 'md' }: { className?: string; settings?: LedgerWidgetSettings; width?: LedgerPanelWidth }) {
  const titles = useAppStore((s) => s.titles)
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const settingsKey = settingsDepKey(settings)

  const rows = useMemo(
    () => deriveProgress(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey],
  )

  const panelTitle = settings?.title || 'Still rolling'
  const hint = 'series in progress'

  if (rows.length === 0) {
    return (
      <Panel title={panelTitle} hint={hint} className={className}>
        <PanelEmpty message="No series mid-flight right now" />
      </Panel>
    )
  }

  return (
    <Panel title={panelTitle} hint={hint} className={className}>
      <ol className={width === 'lg' || width === 'full' ? 'grid grid-cols-2 gap-x-7 gap-y-1' : 'flex flex-col gap-1'}>
        {rows.map((r, i) => (
          <li key={r.id}>
            <button
              onClick={() => openDetailDrawer(r.id)}
              className={cn('w-full flex items-center gap-3', LIST_ROW_HOVER)}
            >
              <span className="relative w-10 h-10 shrink-0">
                <RadialRing
                  pct={r.pct}
                  size={40}
                  stroke={4}
                  color={r.pct >= 0.999 ? 'var(--amber-bright)' : i === 0 ? 'var(--amber)' : 'rgba(128,115,95,0.6)'}
                  delay={i * 60}
                />
                <span className="absolute inset-0 flex items-center justify-center font-mono text-[9px] text-paper-dim">
                  {Math.round(r.pct * 100)}%
                </span>
              </span>
              <span className="min-w-0 flex-1">
                <RowTitle className="truncate block group-hover:underline decoration-amber/40">
                  {r.title}
                </RowTitle>
                <span className="font-mono text-[10px] text-paper-faint">{r.year}</span>
              </span>
              <span className="font-mono text-[11px] text-paper-dim shrink-0">
                {r.watched}<span className="text-paper-faint">/{r.total} eps</span>
              </span>
            </button>
          </li>
        ))}
      </ol>
    </Panel>
  )
}
