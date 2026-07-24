// ─── The ensemble (leading cast) ──────────────────────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, getInitials, rankBubbleAccent } from 'src/lib/utils'
import { deriveTopActors } from 'src/store/ledgerDerive'
import {
  describeLedgerSettings,
  settingsDepKey,
  type LedgerPanelWidth,
  type LedgerWidgetSettings,
} from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty, RankBadge, RowTitle, LIST_ROW_HOVER } from '../PanelShell'

export function TheEnsemble({
  className,
  settings,
  width = 'md',
}: {
  className?: string
  settings?: LedgerWidgetSettings
  width?: LedgerPanelWidth
}) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)
  const browseByPerson = useAppStore((s) => s.browseByPerson)
  const settingsKey = settingsDepKey(settings)
  const actors = useMemo(
    () => deriveTopActors(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey]
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
      {width === 'sm' ? (
        <ol className="flex flex-col justify-center gap-1">
          {actors.map((actor, index) => (
            <li key={actor.tmdbPersonId ?? actor.actor}>
              <button
                onClick={() =>
                  actor.tmdbPersonId
                    ? browseByPerson({ id: actor.tmdbPersonId, name: actor.actor })
                    : (setFilter('search', actor.actor), requestView('library'))
                }
                className={cn(
                  'grid w-full grid-cols-[24px_36px_minmax(0,1fr)_auto] items-center gap-2',
                  LIST_ROW_HOVER
                )}
              >
                <RankBadge rank={index + 1} />
                <span className="flex aspect-square items-center justify-center rounded-full bg-[var(--wash)] font-serif text-xs text-paper-dim">
                  {getInitials(actor.actor)}
                </span>
                <RowTitle className="truncate">{actor.actor}</RowTitle>
                <span className="font-mono text-[10px] text-paper-faint">{actor.count}×</span>
              </button>
            </li>
          ))}
        </ol>
      ) : (
        <div
          className="grid items-start justify-items-center gap-x-3 gap-y-4 py-2"
          style={{
            gridTemplateColumns: `repeat(${width === 'md' ? 3 : width === 'lg' ? 4 : 5}, minmax(0, 1fr))`,
          }}
        >
          {actors.map((a, i) => {
            const t = a.count / maxCount
            const maxSize = width === 'md' ? 84 : 96
            const size = Math.round(maxSize * (0.68 + t * 0.32))
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
                className="flex w-full min-w-0 flex-col items-center gap-2 group cursor-pointer"
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
                    ...rankBubbleAccent(i === 0, 22, i * 70),
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
      )}
    </Panel>
  )
}
