// ─── Still rolling (series in progress) ───────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { RadialRing } from 'src/components/LedgerCharts'
import { deriveProgress } from 'src/store/ledgerDerive'
import type { LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty } from '../PanelShell'

export function StillRolling({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const settingsKey = JSON.stringify(settings ?? {})

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
      <ol className="flex flex-col gap-1">
        {rows.map((r, i) => (
          <li key={r.id}>
            <button
              onClick={() => openDetailDrawer(r.id)}
              className="w-full flex items-center gap-3 px-1.5 py-2 rounded-md transition-colors hover:bg-[var(--wash)] text-left cursor-pointer group"
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
                <span
                  className="font-serif text-sm font-medium text-paper truncate block group-hover:underline decoration-amber/40"
                  style={{ fontVariationSettings: '"opsz" 30' }}
                >
                  {r.title}
                </span>
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
