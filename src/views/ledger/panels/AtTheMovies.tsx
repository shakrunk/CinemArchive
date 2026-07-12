// ─── At the movies (cinema outings ledger panel, plan §4.8) ───────────────────

import { useMemo } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, fmtCurrency, getInitials, maxOrOne, rankBarFill } from 'src/lib/utils'
import { deriveAtTheMovies } from 'src/store/outings'
import { describeLedgerSettings, settingsDepKey, type LedgerPanelWidth, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty, RowTitle, FOOTER_CAPTION, COL_GROW_ANIMATION } from '../PanelShell'

// How many of the trailing per-year bars fit before the strip crowds a card
// this narrow — mirrors TheRun.tsx's LABEL_BUDGET idea, but for whole years
// rather than months (a moviegoing history is usually a handful of years).
const YEAR_BUDGET: Record<LedgerPanelWidth, number> = { sm: 4, md: 6, lg: 8, full: 10 }
const MAX_VENUES_SHOWN = 4

export function AtTheMovies({
  className,
  settings,
  width = 'lg',
}: {
  className?: string
  settings?: LedgerWidgetSettings
  width?: LedgerPanelWidth
}) {
  const titles = useAppStore((s) => s.titles)
  const outings = useAppStore((s) => s.outings)
  const settingsKey = settingsDepKey(settings)

  // Source of truth is viewings, not outings — a "trip" is any viewing with a
  // venue, auto-logged or manually entered (plan §4.8). No time-range/scope
  // knobs: a lifetime moviegoing history rarely needs filtering down.
  const stats = useMemo(
    () => deriveAtTheMovies(titles, outings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, outings, settingsKey],
  )

  const years = stats.yearCounts.slice(-YEAR_BUDGET[width])
  const maxYearCount = maxOrOne(years.map((y) => y.count))
  const maxVenueCount = stats.venues[0]?.count ?? 1

  return (
    <Panel
      title={settings?.title || 'At the movies'}
      hint={`cinema trips, not couch rewatches${describeLedgerSettings(settings)}`}
      className={className}
    >
      {stats.tripsTotal === 0 ? (
        <PanelEmpty message="No cinema trips logged yet — get some tickets" />
      ) : (
        <div className="flex h-full min-h-0 flex-col gap-4">
          <div className="flex shrink-0 items-end gap-6">
            <StatBlock value={stats.tripsTotal} label="trips" />
            <StatBlock value={stats.tripsThisYear} label="this year" accent />
            {years.length > 1 && (
              <div
                className="flex h-10 min-w-0 flex-1 items-end gap-1.5"
                role="img"
                aria-label={`Trips by year: ${years.map((y) => `${y.year} — ${y.count}`).join(', ')}`}
              >
                {years.map((y) => (
                  <i
                    key={y.year}
                    className="block min-w-0 flex-1 rounded-t-sm bg-amber-deep"
                    style={{ height: `${Math.max(8, (y.count / maxYearCount) * 100)}%`, animation: COL_GROW_ANIMATION }}
                  />
                ))}
              </div>
            )}
          </div>

          <div className="grid min-h-0 flex-1 grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
            <div className="min-w-0">
              <p className="mb-2 font-mono text-[9px] uppercase tracking-widest text-paper-faint">Favorite theater</p>
              {stats.venues.length === 0 ? (
                <p className="text-sm text-paper-faint">No theater logged yet</p>
              ) : (
                <ol className="flex flex-col gap-1.5">
                  {stats.venues.slice(0, MAX_VENUES_SHOWN).map((v, i) => (
                    <li key={v.venue} className="flex items-center gap-2">
                      <RowTitle className="min-w-0 flex-1 truncate">{v.venue}</RowTitle>
                      <span className="h-[6px] w-16 shrink-0 overflow-hidden rounded-sm bg-[var(--wash)]">
                        <span className="block h-full rounded-sm bar-fill" style={rankBarFill(v.count / maxVenueCount, i === 0, i * 70)} />
                      </span>
                      <span className="w-5 shrink-0 text-right font-mono text-[10px] text-paper-faint">{v.count}</span>
                    </li>
                  ))}
                </ol>
              )}
            </div>

            <div className="flex min-w-0 flex-col gap-3">
              {stats.topCompanion && (
                <div>
                  <p className="mb-2 font-mono text-[9px] uppercase tracking-widest text-paper-faint">Usual companion</p>
                  <div className="flex items-center gap-2">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-[var(--wash)] font-serif text-[10px] text-paper-dim">
                      {getInitials(stats.topCompanion.name)}
                    </span>
                    <RowTitle className="min-w-0 truncate">{stats.topCompanion.name}</RowTitle>
                    <span className="shrink-0 font-mono text-[10px] text-paper-faint">×{stats.topCompanion.count}</span>
                  </div>
                </div>
              )}
              {stats.formats.length > 0 && (
                <div>
                  <p className="mb-2 font-mono text-[9px] uppercase tracking-widest text-paper-faint">Formats</p>
                  <div className="flex flex-wrap gap-1.5">
                    {stats.formats.map((f) => (
                      <span
                        key={f.format}
                        className="inline-flex items-center gap-1 rounded-full border border-amber/25 bg-amber/10 px-2 py-0.5 font-mono text-[10px] text-paper-dim"
                      >
                        {f.format} <span className="text-amber-deep">×{f.count}</span>
                      </span>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </div>

          {/* Hidden entirely when no outing logged a price (plan §4.8) — a
             *  zero-dollar sum would misleadingly imply every trip was free. */}
          {stats.pricedTripCount > 0 && (
            <p className={cn('shrink-0', FOOTER_CAPTION)}>
              Spent at the movies: {fmtCurrency(stats.totalSpent)} across {stats.pricedTripCount} priced trip
              {stats.pricedTripCount !== 1 ? 's' : ''}
            </p>
          )}
        </div>
      )}
    </Panel>
  )
}

function StatBlock({ value, label, accent }: { value: number; label: string; accent?: boolean }) {
  return (
    <div className="flex min-w-0 shrink-0 flex-col gap-0.5">
      <span className="stat-num text-[24px]" style={accent ? { color: 'var(--amber-bright)' } : undefined}>
        {value}
      </span>
      <span className="whitespace-nowrap font-mono text-[9px] uppercase tracking-[0.14em] text-paper-faint">{label}</span>
    </div>
  )
}
