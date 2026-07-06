// ─── The ensemble (leading cast) ──────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { getInitials } from 'src/components/LedgerCharts'
import { deriveTopActors } from 'src/store/ledgerDerive'
import { describeLedgerSettings, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty } from '../PanelShell'

export function TheEnsemble({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const browseByPerson = useAppStore((s) => s.browseByPerson)
  const settingsKey = JSON.stringify(settings ?? {})
  const actors = useMemo(
    () => deriveTopActors(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey],
  )
  const maxCount = actors[0]?.count ?? 1
  const panelTitle = settings?.title || 'The ensemble'
  const hint = `most-billed leads${describeLedgerSettings(settings)}`
  if (actors.length === 0) {
    return (
      <Panel title={panelTitle} hint={hint} className={className}>
        <PanelEmpty message="No cast data yet" />
      </Panel>
    )
  }

  return (
    <Panel title={panelTitle} hint={hint} className={className}>
      <div className="flex flex-wrap items-start justify-center gap-x-5 gap-y-4 py-2">
        {actors.map((a, i) => {
          const t = a.count / maxCount
          const size = 56 + t * 40
          return (
            <button
              key={a.tmdbPersonId ?? a.actor}
              onClick={() => {
                // Person filter matches by TMDB id across all credits; fall
                // back to a name search when the id is missing.
                if (a.tmdbPersonId) browseByPerson({ id: a.tmdbPersonId, name: a.actor })
                else {
                  setFilter('search', a.actor)
                  requestView('library')
                }
              }}
              className="flex flex-col items-center gap-2 group cursor-pointer w-[104px] shrink-0"
            >
              <span
                className="rounded-full flex items-center justify-center font-serif font-medium transition-transform group-hover:scale-105 animate-[scaleIn_0.5s_ease-out_forwards]"
                style={{
                  width: size,
                  height: size,
                  fontSize: size * 0.32,
                  background:
                    i === 0
                      ? 'linear-gradient(155deg, var(--amber-bright), var(--amber-deep))'
                      : 'linear-gradient(155deg, var(--ink-3), var(--ink-1))',
                  color: i === 0 ? 'var(--on-amber)' : 'var(--paper-dim)',
                  border: i === 0 ? 'none' : '1px solid var(--line-2)',
                  boxShadow: i === 0 ? '0 8px 22px -8px rgba(233,178,102,0.55)' : 'var(--shadow)',
                  opacity: 0,
                  transform: 'scale(0)',
                  animationDelay: `${i * 70}ms`,
                }}
              >
                {getInitials(a.actor)}
              </span>
              <span className="text-[12.5px] text-center text-paper-dim group-hover:text-amber-bright transition-colors truncate max-w-full leading-tight">
                {a.actor}
              </span>
              <span className="font-mono text-[10px] text-paper-faint">{a.count}×</span>
            </button>
          )
        })}
      </div>
    </Panel>
  )
}
