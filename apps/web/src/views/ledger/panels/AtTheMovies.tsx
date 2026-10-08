// ─── At the movies (cinema outings ledger panel, plan §4.8) ───────────────────

import { useMemo, useRef, useState } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { cn, fmtCurrency, maxOrOne } from 'src/lib/utils'
import { deriveAtTheMovies, type AtTheMoviesStats, type OutingSpendBreakdown } from 'src/store/outings'
import { describeLedgerSettings, settingsDepKey, type LedgerPanelWidth, type LedgerWidgetSettings } from 'src/lib/ledgerPanels'
import { Panel, PanelEmpty, RowTitle, FOOTER_CAPTION, COL_GROW_ANIMATION } from '../PanelShell'
import { Eyebrow } from 'src/components/ui/typography'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { Button } from 'src/components/ui/button'

// How many of the trailing per-year bars fit before the strip crowds a card
// this narrow — mirrors TheRun.tsx's LABEL_BUDGET idea, but for whole years
// rather than months (a moviegoing history is usually a handful of years).
const YEAR_BUDGET: Record<LedgerPanelWidth, number> = { sm: 4, md: 6, lg: 8, full: 10 }

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
  const isSharedView = useAppStore((s) => s.isSharedView || s.viewerContext.kind !== 'owner')
  const [detailsOpen, setDetailsOpen] = useState(false)
  const detailsTrigger = useRef<HTMLButtonElement>(null)
  const settingsKey = settingsDepKey(settings)

  // Source of truth is viewings, not outings — a "trip" is any viewing with a
  // venue, auto-logged or manually entered (plan §4.8). No time-range/scope
  // knobs: a lifetime moviegoing history rarely needs filtering down.
  const stats = useMemo(
    // Never join private outings in a friend/shared view, even if stale
    // owner rows remain in memory while the viewed library changes.
    () => deriveAtTheMovies(titles, isSharedView ? [] : outings),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [titles, outings, isSharedView, settingsKey],
  )

  const years = stats.yearCounts.slice(-YEAR_BUDGET[width])
  const maxYearCount = maxOrOne(years.map((y) => y.count))

  return (<>
    <Panel
      title={settings?.title || 'At the movies'}
      hint={`cinema trips, not couch rewatches${describeLedgerSettings(settings)}`}
      className={className}
    >
      {stats.tripsTotal === 0 ? (
        <PanelEmpty message="No cinema trips logged yet — get some tickets" />
      ) : (
        <div className="flex h-full min-h-0 flex-col gap-3">
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

          {/* Two compact summary rows leave room for the details action in a
              400px card. All rankings and unabridged labels live in the modal. */}
          <div className="grid min-h-0 flex-1 content-center grid-cols-2 gap-x-4 gap-y-3">
            <div className="col-span-2 min-w-0">
              <Eyebrow as="p" className="mb-1">Favorite theater</Eyebrow>
              {stats.venues.length === 0 ? (
                <p className="text-sm text-paper-faint">No theater logged yet</p>
              ) : (
                <div className="flex items-center gap-2">
                  <RowTitle className="min-w-0 flex-1 truncate">{stats.venues[0].venue}</RowTitle>
                  <span className="shrink-0 font-mono text-[10px] text-paper-faint">{stats.venues[0].count} trips</span>
                </div>
              )}
            </div>
            {stats.topCompanion && (
                <div className="min-w-0">
                  <Eyebrow as="p" className="mb-1">Usual companion</Eyebrow>
                  <div className="flex items-center gap-2">
                    <RowTitle className="min-w-0 truncate">{stats.topCompanion.name}</RowTitle>
                    <span className="shrink-0 font-mono text-[10px] text-paper-faint">×{stats.topCompanion.count}</span>
                  </div>
                </div>
              )}
            {stats.formats.length > 0 && (
                <div className="min-w-0">
                  <Eyebrow as="p" className="mb-1">Usual format</Eyebrow>
                  <div className="flex items-center gap-2">
                    <RowTitle className="min-w-0 truncate">{stats.formats[0].format}</RowTitle>
                    <span className="shrink-0 font-mono text-[10px] text-paper-faint">×{stats.formats[0].count}</span>
                  </div>
                </div>
              )}
          </div>

          {/* Hidden entirely when no outing logged a price (plan §4.8) — a
             *  zero-dollar sum would misleadingly imply every trip was free. */}
          <div className="flex shrink-0 flex-col items-start gap-1">
            {stats.pricedTripCount > 0 && (
              <p className={cn('min-w-0', FOOTER_CAPTION)}>
                {fmtCurrency(stats.totalSpent)} across {stats.pricedTripCount} priced trip
                {stats.pricedTripCount !== 1 ? 's' : ''}
              </p>
            )}
            <Button ref={detailsTrigger} variant="ghost" size="sm" onClick={() => setDetailsOpen(true)}>
              View moviegoing details
            </Button>
          </div>
        </div>
      )}
    </Panel>
    <CinemaModal
      open={detailsOpen && stats.tripsTotal > 0}
      onClose={() => {
        setDetailsOpen(false)
        // CinemaModal has no Radix Trigger child to restore focus to. Wait
        // until its focus trap has closed, then return to this card's action.
        requestAnimationFrame(() => detailsTrigger.current?.focus())
      }}
      title="Moviegoing details"
      description="Your complete cinema history breakdown. Ticket spending includes only trips with a recorded price."
      className="flex flex-col overflow-hidden"
    >
      <div className="shrink-0 px-6 pb-4 pt-6 pr-16">
        <h2 className="font-serif text-2xl text-paper">Moviegoing details</h2>
        <p className="mt-1 text-sm text-paper-faint">{stats.tripsTotal} cinema trips · {stats.tripsThisYear} this year</p>
      </div>
      <div className="min-h-0 overflow-y-auto px-6 pb-6" tabIndex={0} role="region" aria-label="Moviegoing breakdown">
        <MoviegoingDetails stats={stats} showPrivateDetails={!isSharedView} />
      </div>
    </CinemaModal>
  </>)
}

function MoviegoingDetails({ stats, showPrivateDetails }: { stats: AtTheMoviesStats; showPrivateDetails: boolean }) {
  return (
    <div className="space-y-6">
      {showPrivateDetails && stats.bestValueVenue && (
        <section aria-label="Best value venue" className="rounded-lg border border-amber/25 bg-amber/10 p-4">
          <h3 className="font-serif text-lg text-paper">Best value venue</h3>
          <p className="mt-1 text-sm text-paper">{stats.bestValueVenue.label} · {fmtCurrency(stats.bestValueVenue.perTrip)} per priced trip</p>
          <p className="mt-1 text-xs text-paper-faint">Lowest average among venues with at least two priced trips.</p>
        </section>
      )}
      <CountList title="Trips by year" entries={stats.yearCounts.map((entry) => ({ label: String(entry.year), count: entry.count }))} />
      <CountList title="Theaters" entries={stats.venues.map((entry) => ({ label: entry.venue, count: entry.count }))} />
      <CountList title="Companions" entries={stats.companions.map((entry) => ({ label: entry.name, count: entry.count }))} />
      {showPrivateDetails && (<>
        <CountList title="Formats" entries={stats.formats.map((entry) => ({ label: entry.format, count: entry.count }))} />
        {stats.pricedTripCount > 0 && (
          <p className="text-sm text-paper">Total ticket spending: {fmtCurrency(stats.totalSpent)} across {stats.pricedTripCount} priced trips. Unpriced trips are excluded.</p>
        )}
        <SpendTable title="Spend by venue" entries={stats.venueSpend} />
        <SpendTable title="Cost per outing by format" entries={stats.formatSpend} />
        {stats.milestoneBadges.length > 0 && (
          <section aria-label="Moviegoing milestones">
            <h3 className="mb-2 font-serif text-lg text-paper">Moviegoing milestones</h3>
            <ul className="space-y-2">
              {stats.milestoneBadges.map((badge) => (
                <li key={`${badge.kind}:${badge.title}`} className="flex flex-wrap justify-between gap-x-4 text-sm text-paper">
                  <span>{badge.title}</span><span className="text-amber-deep">{badge.detail}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
      </>)}
    </div>
  )
}

function CountList({ title, entries }: { title: string; entries: { label: string; count: number }[] }) {
  if (entries.length === 0) return null
  return (
    <section aria-label={title}>
      <h3 className="mb-2 font-serif text-lg text-paper">{title}</h3>
      <ul className="space-y-2">
        {entries.map((entry) => (
          <li key={entry.label} className="flex justify-between gap-4 text-sm text-paper">
            <span className="min-w-0 break-words">{entry.label}</span>
            <span className="shrink-0 font-mono text-paper-faint">{entry.count} {entry.count === 1 ? 'trip' : 'trips'}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}

function SpendTable({ title, entries }: { title: string; entries: OutingSpendBreakdown[] }) {
  if (entries.length === 0) return null
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm text-paper">
        <caption className="mb-2 text-left font-serif text-lg">{title}</caption>
        <thead><tr className="text-xs text-paper-faint">
          <th scope="col" className="py-2 pr-3">{title === 'Spend by venue' ? 'Venue' : 'Format'}</th>
          <th scope="col" className="p-2 text-right">Priced trips</th>
          <th scope="col" className="p-2 text-right">Total</th>
          <th scope="col" className="py-2 pl-2 text-right">Per trip</th>
        </tr></thead>
        <tbody>{entries.map((entry) => (
          <tr key={entry.label} className="border-t border-border">
            <th scope="row" className="py-2 pr-3 font-normal">{entry.label}</th>
            <td className="p-2 text-right font-mono">{entry.pricedTripCount}</td>
            <td className="p-2 text-right font-mono">{fmtCurrency(entry.totalSpent)}</td>
            <td className="py-2 pl-2 text-right font-mono">{fmtCurrency(entry.perTrip)}</td>
          </tr>
        ))}</tbody>
      </table>
    </div>
  )
}

function StatBlock({ value, label, accent }: { value: number; label: string; accent?: boolean }) {
  return (
    <div className="flex min-w-0 shrink-0 flex-col gap-0.5">
      <span className="stat-num text-[24px]" style={accent ? { color: 'var(--amber-bright)' } : undefined}>
        {value}
      </span>
      <Eyebrow as="span" className="whitespace-nowrap">{label}</Eyebrow>
    </div>
  )
}
