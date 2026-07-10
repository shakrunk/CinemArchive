// ─── Coming attractions (the watchlist, weighed) ──────────────────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, SECONDARY_AMBER_BUTTON } from 'src/lib/utils'
import { deriveAttractions } from 'src/store/ledgerDerive'
import type { LedgerPanelWidth, LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty } from '../PanelShell'

export function ComingAttractions({
  className,
  settings,
  width = 'sm',
}: {
  className?: string
  settings?: LedgerWidgetSettings
  width?: LedgerPanelWidth
}) {
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

  // Below `lg` the stat block has nowhere to go but stack above the detail —
  // a narrow card can't afford to give up the width a side-by-side split
  // needs. At `lg`/`full` a two-column split puts the extra width to use
  // instead of leaving it as dead space to the right of a single column.
  const isSplit = width === 'lg' || width === 'full'

  return (
    <Panel title={panelTitle} hint={hint} className={className}>
      <div className={cn('flex py-1', isSplit ? 'flex-row items-center gap-10' : 'flex-col')}>
        <div className={cn('flex flex-col shrink-0', isSplit && 'min-w-[180px]')}>
          <span className="stat-num text-[clamp(44px,5vw,64px)]">{queue.count}</span>
          <span className="font-mono text-[10px] tracking-[0.18em] uppercase text-paper-faint mt-1">
            {queue.movies} film{queue.movies !== 1 ? 's' : ''} · {queue.series} series awaiting
          </span>
        </div>

        {isSplit && <div className="w-px self-stretch bg-[var(--line-2)]" />}

        <div className={cn('flex flex-col min-w-0', !isSplit && 'contents')}>
          {queue.hoursOwed > 0 && (
            <p className={cn('font-serif text-[15px] text-paper-dim', isSplit ? '' : 'mt-4')}>
              Roughly <strong className="text-paper font-semibold">{queue.hoursOwed} hours</strong> of features owed
              to the screen.
            </p>
          )}

          {queue.topGenres.length > 0 && (
            <div className={cn('flex flex-wrap gap-1.5', isSplit ? 'mt-3' : 'mt-4')}>
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
            className={cn(SECONDARY_AMBER_BUTTON, 'self-start', isSplit ? 'mt-5' : 'mt-6')}
          >
            View the watchlist
          </button>
        </div>
      </div>
    </Panel>
  )
}
