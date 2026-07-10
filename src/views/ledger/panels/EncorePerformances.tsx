// ─── Encore performances ──────────────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { scopedTitles } from 'src/store/ledgerDerive'
import { describeLedgerSettings, settingsDepKey, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty, RowTitle, RankBadge } from '../PanelShell'

export function EncorePerformances({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const settingsKey = settingsDepKey(settings)

  const encores = useMemo(() => {
    const { titles: scoped, topN } = scopedTitles('encores', titles, settings)
    return scoped
      .filter((t) => t.viewings.length >= 2)
      .sort((a, b) => b.viewings.length - a.viewings.length)
      .slice(0, topN)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [titles, settingsKey])

  return (
    <Panel
      title={settings?.title || 'Encore performances'}
      hint={`most revisited${describeLedgerSettings(settings)}`}
      className={className}
    >
      {encores.length === 0 ? (
        <PanelEmpty message="No title has screened twice yet" />
      ) : (
        <ol className="flex flex-col gap-1">
          {encores.map((t, i) => (
            <li
              key={t.id}
              className="grid items-center gap-3 px-1.5 py-2.5 rounded-md transition-colors hover:bg-[var(--wash)]"
              style={{ gridTemplateColumns: '26px 1fr auto' }}
            >
              <RankBadge rank={i + 1} />
              <div className="min-w-0">
                <RowTitle className="truncate block">{t.title}</RowTitle>
                <span className="font-mono text-[10px] text-paper-faint">{t.year}</span>
              </div>
              <span className="flex gap-0.5 shrink-0">
                {Array.from({ length: Math.min(t.viewings.length, 6) }, (_, k) => (
                  <i key={k} className="w-[5px] h-[5px] rounded-full bg-amber" />
                ))}
              </span>
            </li>
          ))}
        </ol>
      )}
    </Panel>
  )
}
