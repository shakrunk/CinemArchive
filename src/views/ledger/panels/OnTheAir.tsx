// ─── On the air (TV networks) ─────────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, rankBarFill } from 'src/lib/utils'
import { scopedTitles } from 'src/store/ledgerDerive'
import { settingsDepKey, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty, RowTitle, LIST_ROW_HOVER } from '../PanelShell'

export function OnTheAir({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const settingsKey = settingsDepKey(settings)

  const networks = useMemo(() => {
    const { topN } = scopedTitles('networks', titles, settings)
    const counts = new Map<string, number>()
    for (const t of titles) {
      if (t.type === 'tv' && t.network) counts.set(t.network, (counts.get(t.network) ?? 0) + 1)
    }
    return [...counts.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, topN)
      .map(([network, count]) => ({ network, count }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [titles, settingsKey])

  const maxCount = networks[0]?.count ?? 1

  return (
    <Panel title={settings?.title || 'On the air'} hint="where the series live" className={className}>
      {networks.length === 0 ? (
        <PanelEmpty message="No series with a network yet" />
      ) : (
        <div className="flex flex-col gap-1">
          {networks.map((n, i) => (
            <button
              key={n.network}
              onClick={() => {
                setFilter('networks', [n.network])
                requestView('library')
              }}
              className={cn('w-full flex items-center gap-3', LIST_ROW_HOVER)}
            >
              <RowTitle className="truncate w-[38%] shrink-0 group-hover:underline decoration-amber/40">
                {n.network}
              </RowTitle>
              <span className="flex-1 h-[12px] rounded-sm bg-[var(--wash)] overflow-hidden">
                <span
                  className="block h-full rounded-sm bar-fill"
                  style={rankBarFill(n.count / maxCount, i === 0, i * 70)}
                />
              </span>
              <span className="font-mono text-[11px] text-paper-dim w-6 text-right shrink-0">{n.count}</span>
            </button>
          ))}
        </div>
      )}
    </Panel>
  )
}
