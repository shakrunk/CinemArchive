import { useState } from 'react'
import { Eye, Check, Plus, Trash2 } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { avgEpisodeRating } from 'src/store/episodeUtils'
import { StarRating } from 'src/components/ui/star-rating'
import { Input } from 'src/components/ui/input'
import { SpiderNoirModeModal } from 'src/components/SpiderNoirModeModal'
import { cn, fmtDate, fmtDateTime, fmtRuntime } from 'src/lib/utils'
import { TMDB_STILL_BASE } from 'src/lib/media'
import type { Episode, Season } from 'src/store/mockData'

function stillSrcFor(episode: Episode): string | null {
  if (!episode.stillUrl) return null
  return episode.stillUrl.startsWith('http') ? episode.stillUrl : `${TMDB_STILL_BASE}${episode.stillUrl}`
}

function ColorModePill({ mode, className }: { mode: 'bw' | 'color'; className?: string }) {
  return (
    <span
      className={cn('font-mono px-1 rounded', className)}
      style={{
        fontSize: '9px',
        letterSpacing: '0.06em',
        background: mode === 'bw' ? 'rgba(200,200,200,0.12)' : 'rgba(233,178,102,0.15)',
        color: mode === 'bw' ? 'var(--paper-dim)' : 'var(--amber)',
        border: `1px solid ${mode === 'bw' ? 'rgba(200,200,200,0.2)' : 'rgba(233,178,102,0.3)'}`,
      }}
    >
      {mode === 'bw' ? '◐ B&W' : '◈ Color'}
    </span>
  )
}

// ─── EpisodeCard ─────────────────────────────────────────────────────────────

export interface EpisodeCardProps {
  episode: Episode
  season: Season
  titleId: string
  isSelected: boolean
  onSelect: () => void
  isSharedView: boolean
  isSpiderNoir: boolean
}

export function EpisodeCard({
  episode,
  season,
  titleId,
  isSelected,
  onSelect,
  isSharedView,
}: EpisodeCardProps) {
  const logEpisode = useAppStore((s) => s.logEpisode)
  const watched = episode.watchEvents.length > 0

  function handleQuickWatch(e: React.MouseEvent) {
    e.stopPropagation()
    if (isSharedView) return
    logEpisode(titleId, season.seasonNumber, episode.episodeNumber, {
      watchedAt: new Date().toISOString().slice(0, 10),
    })
  }

  const stillSrc = stillSrcFor(episode)

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={onSelect}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect() } }}
      aria-expanded={isSelected}
      aria-label={`${episode.episodeName ?? `Episode ${episode.episodeNumber}`} — click to ${isSelected ? 'collapse' : 'expand'} details`}
      className={cn(
        'group shrink-0 w-[240px] text-left rounded-lg overflow-hidden border transition-all focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 cursor-pointer',
        isSelected ? 'border-amber/50' : 'border-[var(--line)] hover:border-amber/30',
      )}
      style={{ background: 'var(--inset)' }}
    >
      {/* Still image area */}
      <div className="relative w-full" style={{ paddingBottom: '56.25%' /* 16:9 */ }}>
        {stillSrc ? (
          <img
            src={stillSrc}
            alt={episode.episodeName ?? `Episode ${episode.episodeNumber}`}
            className="absolute inset-0 w-full h-full object-cover"
          />
        ) : (
          <div
            className="absolute inset-0 flex items-center justify-center"
            style={{ background: 'var(--wash)' }}
          >
            <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
              No preview
            </span>
          </div>
        )}

        {/* Episode number badge */}
        <div
          className="absolute top-1.5 left-1.5 font-mono px-1.5 py-0.5 rounded text-white"
          style={{ fontSize: '10px', background: 'rgba(0,0,0,0.65)' }}
        >
          E{String(episode.episodeNumber).padStart(2, '0')}
        </div>

        {/* Watch eye icon */}
        {!isSharedView && (
          <button
            type="button"
            onClick={handleQuickWatch}
            aria-label={watched ? 'Mark as watched again' : 'Mark as watched'}
            className="absolute top-1.5 right-1.5 rounded-full p-1 transition-all hover:opacity-80 focus:outline-none"
            style={{
              background: watched ? 'rgba(233,178,102,0.22)' : 'rgba(0,0,0,0.55)',
              border: watched ? '1px solid rgba(233,178,102,0.4)' : '1px solid transparent',
            }}
          >
            <Eye
              className="w-3.5 h-3.5"
              style={{ color: watched ? 'var(--amber)' : 'rgba(255,255,255,0.55)' }}
            />
          </button>
        )}

        {/* Watched progress bar */}
        {watched && (
          <div
            className="absolute bottom-0 left-0 right-0 h-0.5"
            style={{ background: 'var(--amber)' }}
          />
        )}
      </div>

      {/* Card body */}
      <div className="px-2.5 py-2 space-y-0.5">
        <div
          className="font-sans font-medium line-clamp-1"
          style={{ fontSize: '13px', color: 'var(--paper)' }}
        >
          {episode.episodeName ?? `Episode ${episode.episodeNumber}`}
        </div>
        {(episode.airDate || episode.runtime) && (
          <div className="font-mono" style={{ fontSize: '10px', color: 'var(--paper-faint)' }}>
            {episode.airDate ? new Date(episode.airDate).getFullYear() : ''}
            {episode.airDate && episode.runtime ? ' · ' : ''}
            {episode.runtime ? fmtRuntime(episode.runtime) : ''}
          </div>
        )}
        {episode.synopsis && (
          <p
            className="font-sans line-clamp-2"
            style={{ fontSize: '12px', color: 'var(--paper-dim)', lineHeight: 1.5 }}
          >
            {episode.synopsis}
          </p>
        )}
      </div>
    </div>
  )
}

// ─── EpisodePanel ─────────────────────────────────────────────────────────────
// Extracted from TitleDetailDrawer.tsx — full logging panel shown below the carousel.

interface EpLogState {
  includeWatch: boolean
  watchedAt: string
  prePlatform: boolean // watched before joining CinemArchive — no date
  watchNotes: string
  rating: number
  reviewText: string
}

const EMPTY_EP_LOG: EpLogState = {
  includeWatch: true,
  watchedAt: new Date().toISOString().slice(0, 10),
  prePlatform: false,
  rating: 0,
  watchNotes: '',
  reviewText: '',
}

export interface EpisodePanelProps {
  episode: Episode
  season: Season
  titleId: string
  isSharedView: boolean
  isSpiderNoir: boolean
  /** Called when the user picks a Spider-Noir colour mode while logging. Lets the
   *  drawer reflect the freshly-chosen mode immediately (web overlay + theme). */
  onColorModeSelected?: (mode: 'bw' | 'color') => void
}

export function EpisodePanel({ episode, season, titleId, isSharedView, isSpiderNoir, onColorModeSelected }: EpisodePanelProps) {
  const logEpisode = useAppStore((s) => s.logEpisode)
  const deleteEpisodeWatchEvent = useAppStore((s) => s.deleteEpisodeWatchEvent)
  const [pendingDeleteWeId, setPendingDeleteWeId] = useState<string | null>(null)
  const [log, setLog] = useState<EpLogState>(EMPTY_EP_LOG)
  const [showForm, setShowForm] = useState(false)
  const [showSaved, setShowSaved] = useState(false)
  const [pendingLog, setPendingLog] = useState<EpLogState | null>(null)
  const [showNoirModal, setShowNoirModal] = useState(false)

  const avg = avgEpisodeRating(episode)
  const watched = episode.watchEvents.length > 0
  const hasRatings = episode.ratings.length > 0
  const hasReviews = episode.reviews.length > 0
  const histCols = [watched, hasRatings, hasReviews].filter(Boolean).length

  function doSave(epLog: EpLogState, colorMode?: 'bw' | 'color') {
    if (!epLog.includeWatch && epLog.rating === 0 && !epLog.reviewText.trim()) return
    logEpisode(titleId, season.seasonNumber, episode.episodeNumber, {
      watchedAt: epLog.includeWatch && !epLog.prePlatform ? epLog.watchedAt : undefined,
      prePlatform: epLog.includeWatch && epLog.prePlatform ? true : undefined,
      watchNotes: epLog.includeWatch ? epLog.watchNotes : undefined,
      rating: epLog.rating > 0 ? epLog.rating : undefined,
      reviewText: epLog.reviewText.trim() || undefined,
      colorMode,
    })
    setLog(EMPTY_EP_LOG)
    setShowForm(false)
    setShowSaved(true)
    setTimeout(() => setShowSaved(false), 1500)
  }

  function handleSubmit() {
    if (!log.includeWatch && log.rating === 0 && !log.reviewText.trim()) return
    const hasWatchOrReview = (log.includeWatch && (!!log.watchedAt || log.prePlatform)) || !!log.reviewText.trim()
    if (isSpiderNoir && hasWatchOrReview) {
      setPendingLog(log)
      setShowNoirModal(true)
    } else {
      doSave(log)
    }
  }

  function handleNoirSelect(mode: 'bw' | 'color') {
    setShowNoirModal(false)
    if (pendingLog) doSave(pendingLog, mode)
    setPendingLog(null)
    // Surface the chosen mode to the drawer so the web overlay + theme fire
    // immediately on the first log, without needing a close/reopen.
    onColorModeSelected?.(mode)
  }

  function handleNoirSkip() {
    setShowNoirModal(false)
    if (pendingLog) doSave(pendingLog)
    setPendingLog(null)
  }

  const stillSrc = stillSrcFor(episode)

  return (
    <div className="ep-panel px-3 py-3 space-y-3 rounded-lg" style={{ borderTop: '1px solid var(--line)', background: 'var(--inset)' }}>
      {/* Director / writers */}
      {(episode.director || (episode.writers && episode.writers.length > 0)) && (
        <div className="flex flex-wrap gap-x-3 gap-y-0.5" style={{ fontSize: '11px' }}>
          {episode.director && (
            <span>
              <span className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '9px' }}>Dir. </span>
              <span className="font-sans" style={{ color: 'var(--paper-dim)' }}>{episode.director}</span>
            </span>
          )}
          {episode.writers && episode.writers.length > 0 && (
            <span>
              <span className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '9px' }}>Written by </span>
              <span className="font-sans" style={{ color: 'var(--paper-dim)' }}>{episode.writers.join(', ')}</span>
            </span>
          )}
        </div>
      )}

      {/* Still + full synopsis */}
      {(stillSrc || episode.synopsis) && (
        <div className="flex gap-3">
          {stillSrc && (
            <img
              src={stillSrc}
              alt={episode.episodeName ?? `Episode ${episode.episodeNumber}`}
              className="rounded-md object-cover shrink-0"
              style={{ width: '120px', height: '68px', objectPosition: 'center' }}
            />
          )}
          {episode.synopsis && (
            <p className="font-sans text-xs leading-relaxed flex-1 min-w-0" style={{ color: 'var(--paper-dim)' }}>
              {episode.synopsis}
            </p>
          )}
        </div>
      )}

      {/* History: watch events / ratings / reviews */}
      {(episode.watchEvents.length > 0 || episode.ratings.length > 0 || episode.reviews.length > 0) && (
        <div className={cn('grid gap-2 text-xs', histCols === 1 ? 'grid-cols-1' : histCols === 2 ? 'grid-cols-2' : 'grid-cols-3')}>
          {watched && (
            <div>
              <div className="font-mono mb-1.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>
                Watched
              </div>
              {episode.watchEvents.map((we) => (
                <div key={we.id}>
                  {pendingDeleteWeId === we.id ? (
                    <div>
                      <div className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>Remove?</div>
                      <div className="flex gap-2 mt-0.5">
                        <button
                          onClick={() => { deleteEpisodeWatchEvent(titleId, season.seasonNumber, episode.episodeNumber, we.id); setPendingDeleteWeId(null) }}
                          className="font-mono transition-opacity hover:opacity-80"
                          style={{ color: 'var(--ember)', fontSize: '10px' }}
                          aria-label="Confirm delete watch event"
                        >Delete</button>
                        <button onClick={() => setPendingDeleteWeId(null)} className="font-mono transition-opacity hover:opacity-80" style={{ color: 'var(--paper-faint)', fontSize: '10px' }} aria-label="Cancel delete watch event">Cancel</button>
                      </div>
                    </div>
                  ) : (
                    <div className="flex items-start gap-1">
                      <div className="flex-1 font-mono" style={{ color: 'var(--amber)', fontSize: '11px' }}>
                        {we.watchedAt ? fmtDate(we.watchedAt) : <span className="italic">Before CinemArchive</span>}
                        {we.colorMode && <ColorModePill mode={we.colorMode} className="ml-1.5" />}
                        {we.notes && <div className="font-sans italic mt-0.5" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>"{we.notes}"</div>}
                      </div>
                      {!isSharedView && (
                        <button onClick={() => setPendingDeleteWeId(we.id)} style={{ color: 'var(--paper-faint)', flexShrink: 0, marginTop: '1px' }} className="opacity-[0.45] hover:opacity-100 transition-opacity focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm" aria-label="Delete watch event" title="Delete watch event">
                          <Trash2 className="w-2.5 h-2.5" />
                        </button>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {hasRatings && (
            <div>
              <div className="font-mono mb-1.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Ratings</div>
              {episode.ratings.map((er) => (
                <div key={er.id} className="font-mono" style={{ color: 'var(--amber)', fontSize: '11px' }}>
                  ★ {er.rating}
                  <div className="mt-0.5">
                    <div className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>{fmtDateTime(er.ratedAt).date}</div>
                    <div className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '9px' }}>{fmtDateTime(er.ratedAt).time}</div>
                  </div>
                </div>
              ))}
              {episode.ratings.length > 1 && avg !== null && (
                <div className="font-mono mt-1" style={{ color: 'var(--amber-deep)', fontSize: '10px' }}>avg ★ {avg.toFixed(1)}</div>
              )}
            </div>
          )}

          {hasReviews && (
            <div>
              <div className="font-mono mb-1.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Reviews</div>
              {episode.reviews.map((rv) => (
                <div key={rv.id}>
                  <div className="font-sans italic leading-snug" style={{ color: 'var(--paper-dim)', fontSize: '11px' }}>"{rv.reviewText}"</div>
                  <div className="mt-0.5" style={{ color: 'var(--paper-faint)' }}>
                    <div className="font-mono flex items-center gap-1.5" style={{ fontSize: '10px' }}>
                      <span>{fmtDateTime(rv.reviewedAt).date}</span>
                      {rv.colorMode && <ColorModePill mode={rv.colorMode} />}
                    </div>
                    <div className="font-mono" style={{ fontSize: '9px' }}>{fmtDateTime(rv.reviewedAt).time}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Log form */}
      {!isSharedView && (
        showForm ? (
          <div className="space-y-3 pt-2" style={{ borderTop: episode.watchEvents.length > 0 || episode.ratings.length > 0 || episode.reviews.length > 0 ? '1px solid var(--line)' : 'none' }}>
            <label className="flex items-center gap-2 cursor-pointer">
              <input type="checkbox" checked={log.includeWatch} onChange={(e) => setLog((l) => ({ ...l, includeWatch: e.target.checked }))} className="accent-amber w-3.5 h-3.5" />
              <span className="font-sans text-xs" style={{ color: 'var(--paper-dim)' }}>Log a watch event</span>
            </label>
            {log.includeWatch && (
              <div className="space-y-2 pl-5">
                {!log.prePlatform && (
                  <Input aria-label="Date watched" type="date" value={log.watchedAt} onChange={(e) => setLog((l) => ({ ...l, watchedAt: e.target.value }))} className="h-8 text-xs font-mono bg-secondary/50 border-border" />
                )}
                <label className="flex items-center gap-2 cursor-pointer">
                  <input type="checkbox" checked={log.prePlatform} onChange={(e) => setLog((l) => ({ ...l, prePlatform: e.target.checked }))} className="accent-amber w-3.5 h-3.5" />
                  <span className="font-sans text-xs" style={{ color: 'var(--paper-faint)' }}>Watched before joining CinemArchive (no date)</span>
                </label>
                <Input aria-label="Watch notes" value={log.watchNotes} onChange={(e) => setLog((l) => ({ ...l, watchNotes: e.target.value }))} placeholder="Watch notes (optional)" className="h-8 text-xs bg-secondary/50 border-border" />
              </div>
            )}
            <div>
              <div className="font-sans text-xs mb-1.5" style={{ color: 'var(--paper-faint)' }}>Rating <span style={{ fontSize: '10px' }}>(optional · logged independently)</span></div>
              <StarRating value={log.rating} onChange={(r) => setLog((l) => ({ ...l, rating: r }))} size="sm" />
            </div>
            <div>
              <div className="font-sans text-xs mb-1.5" style={{ color: 'var(--paper-faint)' }}>Review <span style={{ fontSize: '10px' }}>(optional · logged independently)</span></div>
              <textarea aria-label="Episode review" value={log.reviewText} onChange={(e) => setLog((l) => ({ ...l, reviewText: e.target.value }))} placeholder="Your thoughts on this episode…" rows={2} className="w-full text-xs font-sans resize-none rounded-md px-2.5 py-2 focus:outline-none focus:ring-1 focus:ring-amber/40" style={{ background: 'var(--inset)', border: '1px solid var(--line)', color: 'var(--paper)' }} />
            </div>
            <div className="flex gap-2">
              <button onClick={handleSubmit} disabled={!log.includeWatch && log.rating === 0 && !log.reviewText.trim()} className="flex-1 h-8 rounded-md text-xs font-sans font-medium transition-all disabled:opacity-40 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60" style={{ background: 'var(--amber)', color: '#1a0e06' }}>Save</button>
              <button onClick={() => { setShowForm(false); setLog(EMPTY_EP_LOG) }} className="h-8 px-3 rounded-md text-xs font-sans border transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60" style={{ borderColor: 'var(--line)', color: 'var(--paper-faint)' }}>Cancel</button>
            </div>
          </div>
        ) : (
          <button
            onClick={() => { if (showSaved) return; setLog((l) => ({ ...l, watchedAt: new Date().toISOString().slice(0, 10), prePlatform: false })); setShowForm(true) }}
            className="flex items-center gap-1.5 text-xs font-mono transition-colors"
            style={{ color: showSaved ? 'var(--amber)' : 'var(--amber-deep)' }}
            onMouseEnter={(e) => { if (!showSaved) e.currentTarget.style.color = 'var(--amber)' }}
            onMouseLeave={(e) => { if (!showSaved) e.currentTarget.style.color = 'var(--amber-deep)' }}
          >
            {showSaved ? <><Check className="w-3 h-3" />Logged</> : <><Plus className="w-3 h-3" />{watched ? 'Add rating, review, or re-watch' : 'Log watch event, rating, or review'}</>}
          </button>
        )
      )}
      <SpiderNoirModeModal open={showNoirModal} onSelect={handleNoirSelect} onSkip={handleNoirSkip} />
    </div>
  )
}
