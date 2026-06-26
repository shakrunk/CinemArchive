import { useState, useRef, useEffect, useCallback } from 'react'
import { PlayCircle, Check, Undo2, Clock } from 'lucide-react'
import { useUpNextShows, useUpcomingTitles, useAppStore } from 'src/store/useAppStore'
import { nextUnwatchedEpisode } from 'src/store/episodeUtils'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import type { UpNextEntry, UpcomingEntry } from 'src/store/upNext'
import type { Title } from 'src/store/mockData'

const UNDO_WINDOW_MS = 6000

type PendingUndo = { seasonNumber: number; episodeNumber: number; watchEventId: string; label: string }
type FinishedCard = { snapshot: UpNextEntry; undo: PendingUndo }

// ─── Shared frame (poster + clickable title) ─────────────────────────────────

function CardFrame({ title, onOpen, children }: { title: Title; onOpen: () => void; children: React.ReactNode }) {
  return (
    <div
      className="flex gap-4 rounded-xl p-3 sm:p-4"
      style={{ border: '1px solid var(--line)', background: 'linear-gradient(180deg, var(--ink-1), rgba(17,13,11,0.4))' }}
    >
      <button onClick={onOpen} className="w-16 sm:w-20 shrink-0" aria-label={`Open ${title.title}`}>
        <DynamicPoster title={title} />
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
      <div className="flex-1 h-1.5 rounded-full overflow-hidden" style={{ background: 'rgba(255,255,255,0.06)' }}>
        <div className="h-full rounded-full bg-amber transition-all" style={{ width: `${pct}%` }} />
      </div>
      <span className="font-mono text-[11px] text-paper-faint shrink-0">{watched}/{total}</span>
    </div>
  )
}

// ─── Empty state ─────────────────────────────────────────────────────────────

function EmptyState({ onBrowseLibrary }: { onBrowseLibrary: () => void }) {
  return (
    <div className="text-center py-24 px-5 text-paper-faint">
      <PlayCircle className="w-14 h-14 mx-auto mb-5 text-amber-deep opacity-50" />
      <p className="font-serif text-2xl text-paper-dim font-light">Nothing in progress.</p>
      <p className="font-sans text-sm mt-2 opacity-70">
        Start a series and set it to “Watching” to see your next episode here.
      </p>
      <button
        onClick={onBrowseLibrary}
        className="mt-6 inline-flex items-center gap-2 rounded-md px-4 py-2 text-sm font-sans border border-amber/30 text-amber hover:bg-amber/10 transition-colors"
      >
        Browse the Library
      </button>
    </div>
  )
}

// ─── Live (in-progress) card ─────────────────────────────────────────────────

function LiveCard({ entry, onFinale }: { entry: UpNextEntry; onFinale: (snapshot: UpNextEntry, undo: PendingUndo) => void }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const logNextEpisodeWatch = useAppStore((s) => s.logNextEpisodeWatch)
  const deleteEpisodeWatchEvent = useAppStore((s) => s.deleteEpisodeWatchEvent)
  const isSharedView = useAppStore((s) => s.isSharedView)

  const { title, season, episode, watchedCount, totalCount } = entry
  const epName = episode.episodeName ?? `Episode ${episode.episodeNumber}`

  const [pendingUndo, setPendingUndo] = useState<PendingUndo | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => { if (timerRef.current) clearTimeout(timerRef.current) }, [])

  function handleMarkWatched() {
    const label = `S${season.seasonNumber} E${episode.episodeNumber}`
    const result = logNextEpisodeWatch(title.id)
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

  function handleUndo() {
    if (!pendingUndo) return
    deleteEpisodeWatchEvent(title.id, pendingUndo.seasonNumber, pendingUndo.episodeNumber, pendingUndo.watchEventId)
    if (timerRef.current) clearTimeout(timerRef.current)
    setPendingUndo(null)
  }

  return (
    <CardFrame title={title} onOpen={() => openDetailDrawer(title.id)}>
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
              <button onClick={handleUndo} className="font-mono text-xs text-paper-faint hover:text-paper inline-flex items-center gap-1 transition-colors">
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
    </CardFrame>
  )
}

// ─── Caught-up (just-finished) card ──────────────────────────────────────────

function CaughtUpCard({ snapshot, undo, onDismiss }: { snapshot: UpNextEntry; undo: PendingUndo; onDismiss: (titleId: string) => void }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const deleteEpisodeWatchEvent = useAppStore((s) => s.deleteEpisodeWatchEvent)
  const updateTitle = useAppStore((s) => s.updateTitle)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const { title } = snapshot

  // Keep the latest onDismiss without resetting the dismissal timer each render.
  // The ref is updated in an effect (not during render) to satisfy the React
  // refs lint rule, while the timer effect below keys only on title.id.
  const onDismissRef = useRef(onDismiss)
  useEffect(() => { onDismissRef.current = onDismiss })
  useEffect(() => {
    const id = setTimeout(() => onDismissRef.current(title.id), UNDO_WINDOW_MS)
    return () => clearTimeout(id)
  }, [title.id])

  function handleUndo() {
    deleteEpisodeWatchEvent(title.id, undo.seasonNumber, undo.episodeNumber, undo.watchEventId)
    onDismiss(title.id)
  }
  function handleMarkSeriesWatched() {
    updateTitle(title.id, { status: 'watched' })
    onDismiss(title.id)
  }

  return (
    <CardFrame title={title} onOpen={() => openDetailDrawer(title.id)}>
      <p className="font-mono text-xs text-amber mt-0.5 inline-flex items-center gap-1.5">
        <Check className="w-3.5 h-3.5" /> All caught up
      </p>
      <p className="font-sans text-sm text-paper-dim truncate">You finished {title.title}.</p>
      {!isSharedView && (
        <div className="mt-auto pt-3 flex items-center justify-between gap-2">
          <button onClick={handleMarkSeriesWatched} className="font-mono text-xs text-amber hover:opacity-80 transition-opacity">
            Mark series watched
          </button>
          <button onClick={handleUndo} className="font-mono text-xs text-paper-faint hover:text-paper inline-flex items-center gap-1 transition-colors">
            <Undo2 className="w-3.5 h-3.5" /> Undo
          </button>
        </div>
      )}
    </CardFrame>
  )
}

// ─── Upcoming (unreleased watchlist) card ────────────────────────────────────

function formatReleaseDate(iso: string): string {
  const [year, month, day] = iso.split('-').map(Number)
  return new Date(year, month - 1, day).toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' })
}

function UpcomingCard({ entry }: { entry: UpcomingEntry }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const { title, releaseDate } = entry
  return (
    <CardFrame title={title} onOpen={() => openDetailDrawer(title.id)}>
      <p className="font-mono text-xs text-amber mt-0.5 inline-flex items-center gap-1.5">
        <Clock className="w-3.5 h-3.5" /> Upcoming
      </p>
      <p className="font-sans text-sm text-paper-dim">Releases {formatReleaseDate(releaseDate)}</p>
    </CardFrame>
  )
}

// ─── Up Next view ────────────────────────────────────────────────────────────

export function UpNext({ onBrowseLibrary }: { onBrowseLibrary: () => void }) {
  const shows = useUpNextShows()
  const upcoming = useUpcomingTitles()
  const [finished, setFinished] = useState<FinishedCard[]>([])

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

  const isEmpty = shows.length === 0 && finishedToShow.length === 0 && upcoming.length === 0

  return (
    <div className="max-w-[1100px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <header className="mb-6">
        <p className="kicker"><span className="dot" /> continue watching</p>
        <h1 className="display-title text-[clamp(32px,6vw,56px)] mt-3">Up Next</h1>
      </header>
      {isEmpty ? (
        <EmptyState onBrowseLibrary={onBrowseLibrary} />
      ) : (
        <div className="space-y-3">
          {shows.map((entry) => (
            <LiveCard key={entry.title.id} entry={entry} onFinale={handleFinale} />
          ))}
          {finishedToShow.map((c) => (
            <CaughtUpCard key={c.snapshot.title.id} snapshot={c.snapshot} undo={c.undo} onDismiss={dismissFinished} />
          ))}
          {upcoming.length > 0 && (
            <>
              {(shows.length > 0 || finishedToShow.length > 0) && (
                <p className="font-mono text-[11px] text-paper-faint uppercase tracking-widest pt-2 pb-1">Coming soon</p>
              )}
              {upcoming.map((entry) => (
                <UpcomingCard key={entry.title.id} entry={entry} />
              ))}
            </>
          )}
        </div>
      )}
    </div>
  )
}
