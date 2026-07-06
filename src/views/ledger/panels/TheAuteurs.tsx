// ─── The auteurs (directors) ──────────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { RadialRing } from 'src/components/LedgerCharts'
import { deriveTopDirectors } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty } from '../PanelShell'

export function TheAuteurs({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const browseByPerson = useAppStore((s) => s.browseByPerson)
  const settingsKey = JSON.stringify(settings ?? {})
  const directors = useMemo(
    () => deriveTopDirectors(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey],
  )
  const maxCount = directors[0]?.count ?? 1
  const panelTitle = settings?.title || 'The auteurs'
  const hint = `most-watched directors${describeLedgerSettings(settings)}`
  if (directors.length === 0) {
    return (
      <Panel title={panelTitle} hint={hint} className={className}>
        <PanelEmpty message="No directors yet" />
      </Panel>
    )
  }

  return (
    <Panel title={panelTitle} hint={hint} className={className}>
      <ol className="flex flex-col gap-1">
        {directors.map((d, i) => {
          const pct = d.count / maxCount
          const color = i === 0 ? 'var(--amber-bright)' : 'rgba(128,115,95,0.6)'
          return (
            <li key={d.director}>
              <button
                onClick={() => {
                  // Person filter matches by TMDB id across all credits; fall
                  // back to a name search when the id is missing.
                  if (d.tmdbPersonId) browseByPerson({ id: d.tmdbPersonId, name: d.director })
                  else {
                    setFilter('search', d.director)
                    requestView('library')
                  }
                }}
                className="w-full flex items-center gap-3 px-1.5 py-2.5 rounded-md transition-colors hover:bg-[var(--wash)] text-left cursor-pointer group"
              >
                <span className="font-mono text-xs text-amber-deep w-5 shrink-0">{String(i + 1).padStart(2, '0')}</span>
                <span
                  className="font-serif text-base font-medium text-paper truncate flex-1 min-w-0 group-hover:underline decoration-amber/40"
                  style={{ fontVariationSettings: '"opsz" 30' }}
                >
                  {d.director}
                </span>
                <span className="relative w-9 h-9 shrink-0">
                  <RadialRing pct={pct} size={36} stroke={4} color={color} delay={i * 60} />
                  <span className="absolute inset-0 flex items-center justify-center font-mono text-[10px] text-paper-dim">
                    {d.count}
                  </span>
                </span>
              </button>
            </li>
          )
        })}
      </ol>
    </Panel>
  )
}
