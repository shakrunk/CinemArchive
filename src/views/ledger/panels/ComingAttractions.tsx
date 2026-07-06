// ─── Coming attractions (the watchlist, weighed) ──────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { deriveAttractions } from 'src/store/ledgerDerive'
import type { LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty } from '../PanelShell'

export function ComingAttractions({ className, settings }: { className?: string; settings?: LedgerWidgetSettings }) {
  const titles = useAppStore((s) => s.titles)
  const setFilter = useAppStore((s) => s.setFilter)
  const requestView = useAppStore((s) => s.requestView)

  const queue = useMemo(() => deriveAttractions(titles), [titles])

  const panelTitle = settings?.title || 'Coming attractions'
  const hint = 'the watchlist, weighed'

  if (queue.count === 0) {
    return (
      <Panel title={panelTitle} hint={hint} className={className}>
        <PanelEmpty message="The watchlist is empty — nothing owed" />
      </Panel>
    )
  }

  return (
    <Panel title={panelTitle} hint={hint} className={className}>
      <div className="flex flex-col py-1">
        <span className="stat-num text-[clamp(44px,5vw,64px)]">{queue.count}</span>
        <span className="font-mono text-[10px] tracking-[0.18em] uppercase text-paper-faint mt-1">
          {queue.movies} film{queue.movies !== 1 ? 's' : ''} · {queue.series} series awaiting
        </span>

        {queue.hoursOwed > 0 && (
          <p className="mt-4 font-serif text-[15px] text-paper-dim">
            Roughly <strong className="text-paper font-semibold">{queue.hoursOwed} hours</strong> of features owed
            to the screen.
          </p>
        )}

        {queue.topGenres.length > 0 && (
          <div className="flex flex-wrap gap-1.5 mt-4">
            {queue.topGenres.map((g) => (
              <span
                key={g.genre}
                className="rounded-full border border-[var(--line-2)] px-2.5 py-1 font-mono text-[10px] text-paper-dim"
              >
                {g.genre} · {g.count}
              </span>
            ))}
          </div>
        )}

        <button
          onClick={() => {
            setFilter('status', 'watchlist')
            requestView('library')
          }}
          className="mt-6 self-start text-xs font-mono text-amber border border-amber/30 rounded-md px-3 py-1.5 hover:bg-amber/10 transition-colors"
        >
          View the watchlist
        </button>
      </div>
    </Panel>
  )
}
