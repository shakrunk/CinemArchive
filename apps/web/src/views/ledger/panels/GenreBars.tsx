// ─── Genre bars ───────────────────────────────────────────────────────────────

import { useId, useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, rankBarFill, rankBubbleAccent, toPercent } from 'src/lib/utils'
import { deriveTopGenres } from 'src/store/ledgerDerive'
import { describeLedgerSettings, settingsDepKey, type LedgerPanelWidth, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty, RowTitle, FOOTER_CAPTION } from '../PanelShell'

// Per-bubble flex-basis at the `lg` breakpoint and up, keyed by the widget's
// board-width preset — panels are always full-width below `lg` (see
// WIDTH_GRID_CLASSES), where the plain `basis-*`/`sm:`/`md:` classes below
// take over instead, independent of the preset. Flexbox (not CSS grid) is
// deliberate: `justify-center` centers a ragged last row automatically,
// where a grid would leave its trailing cells stranded on one side.
// calc() (not the bare `basis-1/N` fractions) because flex-basis percentages
// don't subtract the row's `gap` — at 3-up on a narrow card, three unadjusted
// 33.3% items plus two 4px gaps overflow the line by 8px and silently wrap to
// two-up instead.
const DESKTOP_BASIS: Record<LedgerPanelWidth, string> = {
  sm: 'lg:basis-[calc(33.333%-4px)]',
  md: 'lg:basis-[calc(25%-4px)]',
  lg: 'lg:basis-[calc(20%-4px)]',
  full: 'lg:basis-[calc(16.667%-4px)]',
}

// Column counts assumed for each tier, mirroring the basis maps above — used
// only to *estimate* row count so the bubble cap can shrink for a large topN
// (configurable 3–12; see LEDGER_TOP_N_MIN/MAX in lib/ledgerPanels.ts). The
// panel body's fixed height doesn't grow with row count, so an unshrunk cap
// at topN=12 would overflow the card.
const MOBILE_COLS = 3
const DESKTOP_COLS: Record<LedgerPanelWidth, number> = { sm: 3, md: 4, lg: 5, full: 6 }
const DESKTOP_MAX_CAP = 96
const MOBILE_MAX_CAP = 72
// Rough content-height budget left for the bubble/list body once the card's
// fixed 400px (LEDGER_PANEL_STANDARD_HEIGHT) header, padding, and footer
// caption are accounted for.
const CONTENT_BUDGET_PX = 232
const GAP_PX = 4
const MIN_BUBBLE_PX = 34

function bubbleCapPx(count: number, cols: number, maxCap: number): number {
  const rows = Math.max(1, Math.ceil(count / cols))
  const fit = Math.floor((CONTENT_BUDGET_PX - (rows - 1) * GAP_PX) / rows)
  return Math.max(MIN_BUBBLE_PX, Math.min(maxCap, fit))
}

export function GenreBars({
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
  const settingsKey = settingsDepKey(settings)
  const genres = useMemo(
    () => deriveTopGenres(titles, settings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, settingsKey],
  )
  const maxCount = genres[0]?.count ?? 1
  const totalCount = useMemo(() => genres.reduce((sum, g) => sum + g.count, 0), [genres])
  const rawId = useId().replace(/[^a-zA-Z0-9]/g, '')
  const scopeClass = `gb-${rawId}`

  const mobileCap = bubbleCapPx(genres.length, MOBILE_COLS, MOBILE_MAX_CAP)
  const desktopCap = bubbleCapPx(genres.length, DESKTOP_COLS[width], DESKTOP_MAX_CAP)

  const onSelect = (genre: string) => {
    setFilter('genres', [genre])
    requestView('library')
  }

  return (
    <Panel
      title={settings?.title || 'By the genre'}
      hint={`top of the marquee${describeLedgerSettings(settings)}`}
      className={cn(className, 'flex flex-col')}
    >
      {genres.length === 0 ? (
        <PanelEmpty message="No genres yet" />
      ) : (
        <div className={cn('flex-1 min-h-0 flex flex-col', scopeClass)}>
          {/* topN is user-configurable (3–12); the bubble cap shrinks as the
           *  count rises so a busy widget still fits the fixed card height
           *  instead of overflowing into a scrollbar. */}
          <style>{`.${scopeClass}{--bubble-cap:${mobileCap}px}@media (min-width:1024px){.${scopeClass}{--bubble-cap:${desktopCap}px}}`}</style>
          {/* Bubble cloud — the default presentation everywhere, and the only
           *  one below `lg` since every preset renders full-width there. */}
          <div
            className={cn(
              'flex-1 min-h-0 flex flex-wrap content-center justify-center gap-1',
              width === 'sm' && 'lg:hidden',
            )}
          >
            {genres.map((g, i) => {
              const t = Math.sqrt(g.count / maxCount)
              const sizePct = 60 + t * 36
              return (
                <button
                  key={g.genre}
                  onClick={() => onSelect(g.genre)}
                  className={cn(
                    'flex items-center justify-center p-0.5 min-w-0',
                    'basis-[calc(33.333%-4px)] sm:basis-[calc(25%-4px)] md:basis-[calc(20%-4px)]',
                    DESKTOP_BASIS[width],
                  )}
                >
                  <span
                    className="rounded-full flex flex-col items-center justify-center text-center shrink-0 transition-transform hover:scale-105 cursor-pointer animate-[scaleIn_0.5s_ease-out_forwards] overflow-hidden"
                    style={{
                      width: `min(${sizePct}%, var(--bubble-cap))`,
                      aspectRatio: '1',
                      background:
                        i === 0
                          ? 'radial-gradient(circle at 32% 28%, var(--amber-bright), var(--amber-deep))'
                          : 'radial-gradient(circle at 32% 28%, var(--ink-3), var(--ink-1))',
                      ...rankBubbleAccent(i === 0, 24, i * 55),
                    }}
                  >
                    <span
                      className="font-serif font-medium leading-tight px-1.5 line-clamp-2 break-words max-w-full"
                      style={{ fontSize: `${8 + t * 4}px`, color: i === 0 ? 'var(--on-amber)' : 'var(--paper)' }}
                    >
                      {g.genre}
                    </span>
                    <span
                      className="font-mono text-[9px] mt-0.5"
                      style={{ color: i === 0 ? 'var(--on-amber)' : 'var(--paper-faint)', opacity: i === 0 ? 0.75 : 1 }}
                    >
                      {g.count}
                    </span>
                  </span>
                </button>
              )
            })}
          </div>

          {/* Ranked list — swaps in for the `sm` preset at `lg` and up, where
           *  a bubble cloud would crowd into a ~4-of-12-column card. Rows are
           *  flex-1 (not a fixed row height) so topN=12 compresses to fit the
           *  same fixed card height that topN=3 stretches to fill. */}
          {width === 'sm' && (
            <div className="hidden lg:flex flex-1 min-h-0 flex-col">
              {genres.map((g, i) => (
                <button
                  key={g.genre}
                  onClick={() => onSelect(g.genre)}
                  className="w-full flex-1 min-h-0 flex items-center gap-3 px-1.5 rounded-md transition-colors hover:bg-[var(--wash)] cursor-pointer group"
                >
                  <span className="font-mono text-[10px] text-amber-deep w-4 shrink-0">{String(i + 1).padStart(2, '0')}</span>
                  <RowTitle className="truncate flex-1 min-w-0 text-left">{g.genre}</RowTitle>
                  <span className="flex-[1.4] h-[10px] rounded-sm bg-[var(--wash)] overflow-hidden shrink-0">
                    <span className="block h-full rounded-sm bar-fill" style={rankBarFill(g.count / maxCount, i === 0, i * 70)} />
                  </span>
                  <span className="font-mono text-[11px] text-paper-dim w-6 text-right shrink-0">{g.count}</span>
                </button>
              ))}
            </div>
          )}

          <p className={cn('mt-3 shrink-0', FOOTER_CAPTION)}>
            {genres[0].genre} leads at {toPercent(genres[0].count, totalCount)}% of {totalCount} genre tag{totalCount !== 1 ? 's' : ''}
          </p>
        </div>
      )}
    </Panel>
  )
}
