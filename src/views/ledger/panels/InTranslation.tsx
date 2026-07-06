// ─── In translation (original languages) ──────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { languageName } from 'src/lib/utils'
import { scopedTitles } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { useChartTip } from 'src/components/ChartTip'
import { Panel, PanelEmpty } from '../PanelShell'

export function InTranslation({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const settingsKey = JSON.stringify(settings ?? {})
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
        <div className="flex flex-col gap-1">
          {langs.map((l, i) => (
            <button
              key={l.code}
              onClick={() => {
                setFilter('languages', [l.code])
                requestView('library')
              }}
              className="w-full flex items-center gap-3 px-1.5 py-2 rounded-md transition-colors hover:bg-[var(--wash)] text-left cursor-pointer group"
              {...tip.bind({ label: l.name, value: `${l.count} title${l.count !== 1 ? 's' : ''}` })}
            >
              <span className="font-mono text-[10px] uppercase text-amber-deep w-7 shrink-0">{l.code}</span>
              <span
                className="font-serif text-sm font-medium text-paper truncate w-[34%] shrink-0 group-hover:underline decoration-amber/40"
                style={{ fontVariationSettings: '"opsz" 30' }}
              >
                {l.name}
              </span>
              <span className="flex-1 h-[10px] rounded-sm bg-[var(--wash)] overflow-hidden">
                <span
                  className="block h-full rounded-sm bar-fill"
                  style={{
                    width: `${(l.count / maxCount) * 100}%`,
                    background: i === 0
                      ? 'linear-gradient(90deg, var(--amber-deep), var(--amber-bright))'
                      : 'rgba(128,115,95,0.55)',
                    animationDelay: `${i * 70}ms`,
                  }}
                />
              </span>
              <span className="font-mono text-[10px] text-paper-faint w-12 text-right shrink-0">
                {Math.round((l.count / total) * 100)}%
              </span>
            </button>
          ))}
        </div>
      )}
      {tip.node}
    </Panel>
  )
}
