// ─── In translation (original languages) ──────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, languageName, toPercent, rankBarFill } from 'src/lib/utils'
import { scopedTitles } from 'src/store/ledgerDerive'
import { describeLedgerSettings, settingsDepKey, type LedgerPanelWidth, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { Panel, PanelEmpty, RowTitle, LIST_ROW_HOVER } from '../PanelShell'

export function InTranslation({ className, settings, width = 'sm' }: { className?: string; settings?: LedgerWidgetSettings; width?: LedgerPanelWidth }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const settingsKey = settingsDepKey(settings)
  const tip = useChartTip()

  const langs = useMemo(() => {
    const { titles: scoped, topN } = scopedTitles('languages', titles, settings)
    const counts = new Map<string, number>()
    for (const t of scoped) {
      if (t.originalLanguage) counts.set(t.originalLanguage, (counts.get(t.originalLanguage) ?? 0) + 1)
    }
    return [...counts.entries()]
      .sort((a, b) => b[1] - a[1])
      .slice(0, topN)
      .map(([code, count]) => ({ code, name: languageName(code), count }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [titles, settingsKey])

  const total = langs.reduce((sum, l) => sum + l.count, 0)
  const maxCount = langs[0]?.count ?? 1

  return (
    <Panel
      title={settings?.title || 'In translation'}
      hint={`original languages${describeLedgerSettings(settings)}`}
      className={className}
    >
      {langs.length === 0 ? (
        <PanelEmpty message="No language data yet" />
      ) : (
        <div className={width === 'lg' || width === 'full' ? 'grid grid-cols-2 gap-x-7 gap-y-1' : 'flex flex-col gap-1'}>
          {langs.map((l, i) => (
            <button
              key={l.code}
              onClick={() => {
                setFilter('languages', [l.code])
                requestView('library')
              }}
              className={cn('w-full flex items-center gap-3', LIST_ROW_HOVER)}
              {...tip.bind({ label: l.name, value: `${l.count} title${l.count !== 1 ? 's' : ''}` })}
            >
              <span className="font-mono text-[10px] uppercase text-amber-deep w-7 shrink-0">{l.code}</span>
              <RowTitle className={cn('truncate shrink-0 group-hover:underline decoration-amber/40', width === 'sm' ? 'w-[31%]' : 'w-[34%]')}>
                {l.name}
              </RowTitle>
              <span className="flex-1 h-[10px] rounded-sm bg-[var(--wash)] overflow-hidden">
                <span
                  className="block h-full rounded-sm bar-fill"
                  style={rankBarFill(l.count / maxCount, i === 0, i * 70)}
                />
              </span>
              <span className="font-mono text-[10px] text-paper-faint w-9 text-right shrink-0">
                {toPercent(l.count, total)}%
              </span>
            </button>
          ))}
        </div>
      )}
      {tip.node}
    </Panel>
  )
}
