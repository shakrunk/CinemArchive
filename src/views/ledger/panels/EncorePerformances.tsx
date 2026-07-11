// ─── Encore performances ──────────────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { scopedTitles } from 'src/store/ledgerDerive'
import { describeLedgerSettings, settingsDepKey, type LedgerPanelWidth, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty, RowTitle, RankBadge } from '../PanelShell'

export function EncorePerformances({
  className,
  settings,
  width = 'sm',
}: {
  className?: string
  settings?: LedgerWidgetSettings
  width?: LedgerPanelWidth
}) {
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
        <ol className={width === 'lg' || width === 'full' ? 'grid grid-cols-2 gap-x-6 gap-y-1' : 'flex flex-col gap-1'}>
          {encores.map((t, i) => (
            <li
              key={t.id}
              className="grid items-center gap-3 px-1.5 py-2.5 rounded-md transition-colors hover:bg-[var(--wash)]"
              style={{ gridTemplateColumns: width === 'sm' ? '22px minmax(0, 1fr) auto' : '26px minmax(0, 1fr) auto' }}
            >
              <RankBadge rank={i + 1} />
              <div className="min-w-0">
                <RowTitle className="truncate block">{t.title}</RowTitle>
                <span className="font-mono text-[10px] text-paper-faint">{t.year}</span>
              </div>
              <span className="flex items-center gap-0.5 shrink-0" aria-label={`${t.viewings.length} screenings`}>
                {Array.from({ length: Math.min(t.viewings.length, width === 'sm' ? 5 : 8) }, (_, k) => (
                  <i key={k} className="w-[5px] h-[5px] rounded-full bg-amber" />
                ))}
                {t.viewings.length > (width === 'sm' ? 5 : 8) && (
                  <span className="ml-1 font-mono text-[9px] text-paper-faint">{t.viewings.length}×</span>
                )}
              </span>
            </li>
          ))}
        </ol>
      )}
    </Panel>
  )
}
