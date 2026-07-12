import { useState, useRef, useEffect, useCallback, useMemo } from 'react'
import { PlayCircle, Check, Undo2, Clock, Bookmark, Ticket, CalendarPlus, MoreVertical, Clapperboard, Star, X } from 'lucide-react'
import { useShallow } from 'zustand/react/shallow'
import { useUpNextShows, useUpcomingTitles, useAppStore } from 'src/store/useAppStore'
import { nextUnwatchedEpisode } from 'src/store/episodeUtils'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { SpiderNoirModeModal } from 'src/components/SpiderNoirModeModal'
import type { UpNextEntry, UpcomingEntry } from 'src/store/upNext'
import { computeMarqueeEntries, formatCompanions, type MarqueeEntry } from 'src/store/outings'
import { buildOutingIcs, outingIcsFilename, downloadIcsFile } from 'src/lib/ics'
import { ShareOutingPanel } from 'src/components/ShareOutingPanel'
import type { Title } from 'src/store/mockData'
import { SPIDER_NOIR_TMDB_ID } from 'src/lib/easterEggThemes'
import { cn, staggerDelays } from 'src/lib/utils'
import { useClickOutside } from 'src/lib/useClickOutside'
import { EmptyState } from 'src/components/ui/empty-state'

const UNDO_WINDOW_MS = 6000

type PendingUndo = { seasonNumber: number; episodeNumber: number; watchEventId: string; label: string }
type FinishedCard = { snapshot: UpNextEntry; undo: PendingUndo }

// ─── Shared frame (poster + clickable title) ─────────────────────────────────

function CardFrame({ title, onOpen, children, delayMs }: { title: Title; onOpen: () => void; children: React.ReactNode; delayMs?: number }) {
  return (
    <div
      className="flex gap-4 rounded-xl p-3 sm:p-4"
      style={{
        border: '1px solid var(--line)',
        background: 'linear-gradient(180deg, var(--ink-1), rgba(17,13,11,0.4))',
        ...(delayMs !== undefined ? { ['--poster-delay' as string]: `${delayMs}ms` } : {}),
      }}
    >
      <button onClick={onOpen} className="w-16 sm:w-20 shrink-0" aria-label={`Open ${title.title}`}>
        <DynamicPoster title={title} hideBadges />
      </button>
      <div className="flex-1 min-w-0 flex flex-col">
        <button onClick={onOpen} className="text-left">
          <h3 className="font-serif text-lg sm:text-xl font-medium text-paper truncate" style={{ fontVariationSettings: '"opsz" 30' }}>
            {title.title}
          </h3>
        </button>
        {children}
      </div>
    </div>
  )
}

function ProgressBar({ watched, total }: { watched: number; total: number }) {
  const pct = total > 0 ? Math.round((watched / total) * 100) : 0
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 h-1.5 rounded-full overflow-hidden" style={{ background: 'var(--wash)' }}>
        <div className="h-full rounded-full bg-amber transition-all" style={{ width: `${pct}%` }} />
      </div>
      <span className="font-mono text-[11px] text-paper-faint shrink-0">{watched}/{total}</span>
    </div>
  )
}

// ─── Empty state ─────────────────────────────────────────────────────────────

function UpNextEmptyState({ onBrowseLibrary }: { onBrowseLibrary: () => void }) {
  return (
    <EmptyState
      Icon={PlayCircle}
      title="Nothing in progress."
      subtext="Start a series and set it to “Watching” to see your next episode here."
      ctaLabel="Browse the Library"
      onCta={onBrowseLibrary}
      ctaClassName="mt-6"
    />
  )
}

// ─── Live (in-progress) card ─────────────────────────────────────────────────

function LiveCard({ entry, onFinale, delayMs }: { entry: UpNextEntry; onFinale: (snapshot: UpNextEntry, undo: PendingUndo) => void; delayMs?: number }) {
  // ⚡ Bolt: Batch Zustand selectors to reduce store subscriptions
  const { openDetailDrawer, logNextEpisodeWatch, deleteEpisodeWatchEvent, isSharedView } = useAppStore(
    useShallow((s) => ({
      openDetailDrawer: s.openDetailDrawer,
      logNextEpisodeWatch: s.logNextEpisodeWatch,
      deleteEpisodeWatchEvent: s.deleteEpisodeWatchEvent,
      isSharedView: s.isSharedView
    }))
  )

  const { title, season, episode, watchedCount, totalCount } = entry
  const epName = episode.episodeName ?? `Episode ${episode.episodeNumber}`

  const [pendingUndo, setPendingUndo] = useState<PendingUndo | null>(null)
  const [showNoirModal, setShowNoirModal] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => { if (timerRef.current) clearTimeout(timerRef.current) }, [])

  const isSpiderNoir = title.tmdbId === SPIDER_NOIR_TMDB_ID

  function doMarkWatched(colorMode?: 'bw' | 'color') {
    const label = `S${season.seasonNumber} E${episode.episodeNumber}`
    const result = logNextEpisodeWatch(title.id, colorMode)
    if (!result) return
    const undo: PendingUndo = { ...result, label }
    const updated = useAppStore.getState().titles.find((t) => t.id === title.id)
    const isFinale = !updated?.seasons || nextUnwatchedEpisode(updated.seasons) === null
    if (isFinale) {
      // This card is about to unmount (the show leaves the live list); hand the
      // finished state to the parent so it can show a caught-up card.
      onFinale(entry, undo)
      return
    }
    setPendingUndo(undo)
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => setPendingUndo(null), UNDO_WINDOW_MS)
  }

  function handleMarkWatched() {
    if (isSpiderNoir) {
      setShowNoirModal(true)
    } else {
      doMarkWatched()
    }
  }

  function handleNoirSelect(mode: 'bw' | 'color') {
    setShowNoirModal(false)
    doMarkWatched(mode)
  }

  function handleNoirSkip() {
    setShowNoirModal(false)
    doMarkWatched()
  }

  function handleUndo() {
    if (!pendingUndo) return
    deleteEpisodeWatchEvent(title.id, pendingUndo.seasonNumber, pendingUndo.episodeNumber, pendingUndo.watchEventId)
    if (timerRef.current) clearTimeout(timerRef.current)
    setPendingUndo(null)
  }

  return (
    <CardFrame title={title} onOpen={() => openDetailDrawer(title.id)} delayMs={delayMs}>
      <p className="font-mono text-xs text-amber mt-0.5">S{season.seasonNumber} E{episode.episodeNumber} · Next</p>
      <p className="font-sans text-sm text-paper-dim truncate">{epName}</p>
      <div className="mt-auto pt-3">
        <ProgressBar watched={watchedCount} total={totalCount} />
        {!isSharedView && (
          pendingUndo ? (
            <div className="flex items-center justify-between mt-3">
              <span className="font-mono text-xs text-amber inline-flex items-center gap-1.5">
                <Check className="w-3.5 h-3.5" /> Watched {pendingUndo.label}
              </span>
              <button onClick={handleUndo} aria-label={`Undo marking ${title.title} as watched`} className="font-mono text-xs text-paper-faint hover:text-paper inline-flex items-center gap-1 transition-colors">
                <Undo2 className="w-3.5 h-3.5" /> Undo
              </button>
            </div>
          ) : (
            <button onClick={handleMarkWatched} className="btn-amber inline-flex items-center justify-center gap-2 rounded-md w-full mt-3 py-2 text-[13px] font-bold">
              <Check className="w-4 h-4" /> Mark watched
            </button>
          )
        )}
      </div>
      <SpiderNoirModeModal
        open={showNoirModal}
        onSelect={handleNoirSelect}
        onSkip={handleNoirSkip}
      />
    </CardFrame>
  )
}

// ─── Caught-up (just-finished) card ──────────────────────────────────────────

function CaughtUpCard({ snapshot, undo, onDismiss, delayMs }: { snapshot: UpNextEntry; undo: PendingUndo; onDismiss: (titleId: string) => void; delayMs?: number }) {
  // ⚡ Bolt: Batch Zustand selectors to reduce store subscriptions
  const { openDetailDrawer, deleteEpisodeWatchEvent, updateTitle, isSharedView } = useAppStore(
    useShallow((s) => ({
      openDetailDrawer: s.openDetailDrawer,
      deleteEpisodeWatchEvent: s.deleteEpisodeWatchEvent,
      updateTitle: s.updateTitle,
      isSharedView: s.isSharedView
    }))
  )
  const title = snapshot?.title

  // Keep the latest onDismiss without resetting the dismissal timer each render.
  // The ref is updated in an effect (not during render) to satisfy the React
  // refs lint rule, while the timer effect below keys only on title?.id.
  const onDismissRef = useRef(onDismiss)
  useEffect(() => { onDismissRef.current = onDismiss })
  useEffect(() => {
    if (!title) return
    const id = setTimeout(() => onDismissRef.current(title.id), UNDO_WINDOW_MS)
    return () => clearTimeout(id)
  }, [title?.id])

  function handleUndo() {
    if (!title) return
    deleteEpisodeWatchEvent(title.id, undo.seasonNumber, undo.episodeNumber, undo.watchEventId)
    onDismiss(title.id)
  }
  function handleMarkSeriesWatched() {
    if (!title) return
    updateTitle(title.id, { status: 'watched' })
    onDismiss(title.id)
  }

  if (!title) return null

  return (
    <CardFrame title={title} onOpen={() => openDetailDrawer(title.id)} delayMs={delayMs}>
      <p className="font-mono text-xs text-amber mt-0.5 inline-flex items-center gap-1.5">
        <Check className="w-3.5 h-3.5" /> All caught up
      </p>
      <p className="font-sans text-sm text-paper-dim truncate">You finished {title.title}.</p>
      {!isSharedView && (
        <div className="mt-auto pt-3 flex items-center justify-between gap-2">
          <button onClick={handleMarkSeriesWatched} className="font-mono text-xs text-amber hover:opacity-80 transition-opacity">
            Mark series watched
          </button>
          <button onClick={handleUndo} aria-label={`Undo marking ${title.title} series as watched`} className="font-mono text-xs text-paper-faint hover:text-paper inline-flex items-center gap-1 transition-colors">
            <Undo2 className="w-3.5 h-3.5" /> Undo
          </button>
        </div>
      )}
    </CardFrame>
  )
}

// ─── Watchlist card (available now, or upcoming/unreleased) ──────────────────

function formatReleaseDate(iso: string): string {
  const [year, month, day] = iso.split('-').map(Number)
  return new Date(year, month - 1, day).toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' })
}

function UpcomingCard({ entry, delayMs }: { entry: UpcomingEntry; delayMs?: number }) {
  // ⚡ Bolt: Batch Zustand selectors to reduce store subscriptions
  const { openDetailDrawer, openOutingSchedule, isSharedView } = useAppStore(
    useShallow((s) => ({
      openDetailDrawer: s.openDetailDrawer,
      openOutingSchedule: s.openOutingSchedule,
      isSharedView: s.isSharedView,
    }))
  )
  const { title, releaseDate } = entry
  return (
    <div className="relative">
      <CardFrame title={title} onOpen={() => openDetailDrawer(title.id)} delayMs={delayMs}>
        {releaseDate ? (
          <>
            <p className="font-mono text-xs text-amber mt-0.5 inline-flex items-center gap-1.5">
              <Clock className="w-3.5 h-3.5" /> Upcoming
            </p>
            <p className="font-sans text-sm text-paper-dim">Releases {formatReleaseDate(releaseDate)}</p>
          </>
        ) : (
          <p className="font-mono text-xs text-amber mt-0.5 inline-flex items-center gap-1.5">
            <Bookmark className="w-3.5 h-3.5" /> On your watchlist
          </p>
        )}
      </CardFrame>
      {/* Cinema Outings entry point (plan §4.1) — movies only, v1 scope. */}
      {!isSharedView && title.type === 'movie' && (
        <button
          onClick={() => openOutingSchedule(title.id)}
          aria-label={`I've got tickets for ${title.title}`}
          className="absolute top-2 right-2 w-7 h-7 rounded-full flex items-center justify-center bg-black/50 backdrop-blur-sm text-amber/80 hover:text-amber hover:bg-black/70 transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
        >
          <Ticket className="w-3.5 h-3.5" />
        </button>
      )}
    </div>
  )
}

// ─── On the Marquee (scheduled/now-showing cinema outings, plan §4.5) ────────

function MarqueeCard({ entry, delayMs }: { entry: MarqueeEntry; delayMs?: number }) {
  // ⚡ Bolt: Batch Zustand selectors to reduce store subscriptions
  const { openDetailDrawer, openOutingSchedule, cancelOuting, isSharedView } = useAppStore(
    useShallow((s) => ({
      openDetailDrawer: s.openDetailDrawer,
      openOutingSchedule: s.openOutingSchedule,
      cancelOuting: s.cancelOuting,
      isSharedView: s.isSharedView,
    }))
  )
  const { outing, title, presentation } = entry

  const [menuOpen, setMenuOpen] = useState(false)
  const [confirmingCancel, setConfirmingCancel] = useState(false)
  const [sharePanelOpen, setSharePanelOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)
  useClickOutside(menuRef, () => setMenuOpen(false), menuOpen, { escape: true })

  const companionLabel = formatCompanions(outing.companions)
  const detailLine = [outing.venue, companionLabel && `with ${companionLabel}`].filter(Boolean).join(' · ')

  function handleAddToCalendar() {
    const ics = buildOutingIcs(outing, title.title)
    downloadIcsFile(outingIcsFilename(title.title, outing.showtime), ics)
  }

  return (
    <div className="relative">
      <CardFrame title={title} onOpen={() => openDetailDrawer(title.id)} delayMs={delayMs}>
        <p
          className={cn(
            'font-mono text-xs mt-0.5 inline-flex items-center gap-1.5 text-amber',
            presentation.kind === 'now-showing' && 'marquee-now-showing'
          )}
        >
          <Ticket className="w-3.5 h-3.5" aria-hidden="true" />
          <span aria-label={presentation.ariaLabel}>{presentation.label}</span>
        </p>
        {detailLine && <p className="font-sans text-sm text-paper-dim truncate">{detailLine}</p>}
        {!isSharedView && (
          <div className="mt-auto pt-3 flex items-center justify-between gap-2">
            {confirmingCancel ? (
              <span className="flex items-center gap-2">
                <span className="font-mono text-xs text-paper-faint">Cancel these tickets?</span>
                <button onClick={() => cancelOuting(outing.id)} className="font-mono text-xs" style={{ color: 'var(--ember)' }}>
                  Yes
                </button>
                <button onClick={() => setConfirmingCancel(false)} className="font-mono text-xs text-paper-faint hover:text-paper transition-colors">
                  No
                </button>
              </span>
            ) : (
              <>
                <button
                  onClick={handleAddToCalendar}
                  className="flex items-center gap-1.5 font-mono text-xs text-amber/80 hover:text-amber transition-colors"
                >
                  <CalendarPlus className="w-3.5 h-3.5" />
                  Add to calendar
                </button>
                <div className="relative" ref={menuRef}>
                  <button
                    onClick={() => setMenuOpen((v) => !v)}
                    aria-label={`More options for ${title.title}'s tickets`}
                    aria-expanded={menuOpen}
                    className="w-7 h-7 rounded-full flex items-center justify-center text-paper-faint hover:text-amber transition-colors"
                  >
                    <MoreVertical className="w-4 h-4" />
                  </button>
                  {menuOpen && (
                    <div
                      role="menu"
                      aria-label="Outing options"
                      className="absolute right-0 bottom-full mb-1 w-44 rounded-lg overflow-hidden z-10 shadow-xl py-1"
                      style={{ background: 'rgb(var(--ink-1-rgb))', border: '1px solid var(--line)' }}
                    >
                      <button
                        role="menuitem"
                        onClick={() => { setMenuOpen(false); setSharePanelOpen(true) }}
                        className="w-full text-left px-3 py-2 font-mono text-xs text-paper-faint hover:text-amber hover:bg-secondary/30 transition-colors"
                      >
                        Share plans
                      </button>
                      <button
                        role="menuitem"
                        onClick={() => { setMenuOpen(false); openOutingSchedule(title.id, outing.id) }}
                        className="w-full text-left px-3 py-2 font-mono text-xs text-paper-faint hover:text-amber hover:bg-secondary/30 transition-colors"
                      >
                        Edit tickets
                      </button>
                      <button
                        role="menuitem"
                        onClick={() => { setMenuOpen(false); setConfirmingCancel(true) }}
                        className="w-full text-left px-3 py-2 font-mono text-xs text-paper-faint hover:text-ember transition-colors"
                      >
                        Cancel outing
                      </button>
                    </div>
                  )}
                </div>
              </>
            )}
          </div>
        )}
      </CardFrame>
      {sharePanelOpen && (
        <ShareOutingPanel outing={outing} title={title} onClose={() => setSharePanelOpen(false)} />
      )}
    </div>
  )
}

function FreshFromLobbyCard({ entry, delayMs }: { entry: MarqueeEntry; delayMs?: number }) {
  // ⚡ Bolt: Batch Zustand selectors to reduce store subscriptions
  const { openDetailDrawer, openPostShowSheet, dismissOutingFollowUp, isSharedView } = useAppStore(
    useShallow((s) => ({
      openDetailDrawer: s.openDetailDrawer,
      openPostShowSheet: s.openPostShowSheet,
      dismissOutingFollowUp: s.dismissOutingFollowUp,
      isSharedView: s.isSharedView,
    }))
  )
  const { outing, title } = entry

  return (
    <div className="relative">
      <CardFrame title={title} onOpen={() => openDetailDrawer(title.id)} delayMs={delayMs}>
        <p className="font-mono text-xs text-amber mt-0.5 inline-flex items-center gap-1.5">
          <Clapperboard className="w-3.5 h-3.5" /> Fresh from the lobby
        </p>
        <p className="font-sans text-sm text-paper-dim truncate">Just let out — how was it?</p>
        {!isSharedView && (
          <div className="mt-auto pt-3">
            <button
              onClick={() => openPostShowSheet(outing.id)}
              className="btn-amber inline-flex items-center justify-center gap-2 rounded-md w-full py-2 text-[13px] font-bold"
            >
              <Star className="w-4 h-4" /> Rate it
            </button>
          </div>
        )}
      </CardFrame>
      {!isSharedView && (
        <button
          onClick={() => dismissOutingFollowUp(outing.id)}
          aria-label={`Dismiss the "how was it" prompt for ${title.title}`}
          className="absolute top-2 right-2 w-7 h-7 rounded-full flex items-center justify-center bg-black/50 backdrop-blur-sm text-paper-faint hover:text-paper hover:bg-black/70 transition-colors"
        >
          <X className="w-3.5 h-3.5" />
        </button>
      )}
    </div>
  )
}

// ─── Up Next view ────────────────────────────────────────────────────────────

export function UpNext({ onBrowseLibrary }: { onBrowseLibrary: () => void }) {
  const shows = useUpNextShows()
  const upcoming = useUpcomingTitles()
  const [finished, setFinished] = useState<FinishedCard[]>([])

  // Owner-only data (plan §4.5: "not rendered in shared/friend views") — the
  // marquee is skipped entirely in a shared/friend session rather than
  // trusting `outings` to already be empty there.
  const { outings, titles, isSharedView } = useAppStore(
    useShallow((s) => ({ outings: s.outings, titles: s.titles, isSharedView: s.isSharedView }))
  )

  // A single shared "now" tick for the whole section (not per-card timers) —
  // countdown labels re-derive once a minute; completion itself is driven by
  // the reconciler, never by this cosmetic tick (plan §4.5).
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 60_000)
    return () => window.clearInterval(id)
  }, [])

  const marqueeEntries = useMemo(
    () => (isSharedView ? [] : computeMarqueeEntries(outings, titles, now)),
    [isSharedView, outings, titles, now]
  )

  const dismissFinished = useCallback((titleId: string) => {
    setFinished((f) => f.filter((c) => c.snapshot.title.id !== titleId))
  }, [])

  const handleFinale = useCallback((snapshot: UpNextEntry, undo: PendingUndo) => {
    setFinished((f) => [...f.filter((c) => c.snapshot.title.id !== snapshot.title.id), { snapshot, undo }])
  }, [])

  // A title can't be both live and finished; if a finished show reappears live
  // (e.g. after Undo) drop its caught-up card so it isn't rendered twice.
  const liveIds = new Set(shows.map((s) => s.title.id))
  const finishedToShow = finished.filter((c) => !liveIds.has(c.snapshot.title.id))

  const availableWatchlist = upcoming.filter((e) => !e.releaseDate)
  const comingSoon = upcoming.filter((e) => e.releaseDate)
  const hasLiveSection = shows.length > 0 || finishedToShow.length > 0
  const hasMarquee = marqueeEntries.length > 0

  // Scheduled tickets are content too (plan §4.5: "marquee counts toward the
  // app's smart-landing check") — Up Next isn't "empty" just because nothing's
  // mid-episode or on the watchlist yet.
  const isEmpty = shows.length === 0 && finishedToShow.length === 0 && upcoming.length === 0 && !hasMarquee

  const totalCards = shows.length + finishedToShow.length + marqueeEntries.length + availableWatchlist.length + comingSoon.length
  const delays = useMemo(() => staggerDelays(totalCards), [totalCards])
  let cardIndex = 0

  return (
    <div className="max-w-[1100px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <header className="mb-6">
        <p className="kicker"><span className="dot" /> continue watching</p>
        <h1 className="display-title text-[clamp(32px,6vw,56px)] mt-3">Up Next</h1>
      </header>
      {isEmpty ? (
        <UpNextEmptyState onBrowseLibrary={onBrowseLibrary} />
      ) : (
        <div className="upnext-grid grid grid-cols-1 sm:grid-cols-2 gap-3">
          {shows.map((entry) => (
            <LiveCard key={entry.title.id} entry={entry} onFinale={handleFinale} delayMs={delays[cardIndex++]} />
          ))}
          {finishedToShow.map((c) => (
            <CaughtUpCard key={c.snapshot.title.id} snapshot={c.snapshot} undo={c.undo} onDismiss={dismissFinished} delayMs={delays[cardIndex++]} />
          ))}
          {hasMarquee && (
            <>
              {hasLiveSection && (
                <p className="col-span-full font-mono text-[11px] text-paper-faint uppercase tracking-widest pt-2 pb-1">On the Marquee</p>
              )}
              {marqueeEntries.map((entry) =>
                entry.outing.status === 'completed' ? (
                  <FreshFromLobbyCard key={entry.outing.id} entry={entry} delayMs={delays[cardIndex++]} />
                ) : (
                  <MarqueeCard key={entry.outing.id} entry={entry} delayMs={delays[cardIndex++]} />
                )
              )}
            </>
          )}
          {availableWatchlist.length > 0 && (
            <>
              {(hasLiveSection || hasMarquee) && (
                <p className="col-span-full font-mono text-[11px] text-paper-faint uppercase tracking-widest pt-2 pb-1">On your watchlist</p>
              )}
              {availableWatchlist.map((entry) => (
                <UpcomingCard key={entry.title.id} entry={entry} delayMs={delays[cardIndex++]} />
              ))}
            </>
          )}
          {comingSoon.length > 0 && (
            <>
              {(hasLiveSection || hasMarquee || availableWatchlist.length > 0) && (
                <p className="col-span-full font-mono text-[11px] text-paper-faint uppercase tracking-widest pt-2 pb-1">Coming soon</p>
              )}
              {comingSoon.map((entry) => (
                <UpcomingCard key={entry.title.id} entry={entry} delayMs={delays[cardIndex++]} />
              ))}
            </>
          )}
        </div>
      )}
    </div>
  )
}
