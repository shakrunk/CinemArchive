import { useState, useEffect, useRef } from 'react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { StarRating } from 'src/components/ui/star-rating'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { SeriesGraph } from 'src/components/ui/series-graph'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { CardTitle, BodyText, MetaBadge, StatNumber, StatLabel } from 'src/components/ui/typography'
import { useAppStore, useSelectedTitle } from 'src/store/useAppStore'
import {
  avgEpisodeRating,
  avgSeasonRating,
  avgSeriesRating,
  episodesWatchedInSeason,
  totalEpisodesWatched,
  totalEpisodeCount,
} from 'src/store/episodeUtils'
import {
  Calendar, Clock, Film, Tv, Plus, FileText, Trash2, Star,
  ChevronDown, ChevronRight, Eye, MessageSquare, RefreshCw,
} from 'lucide-react'
import { cn } from 'src/lib/utils'
import type { Title, Viewing, WatchStatus, Season, Episode, CastMember, CrewMember, EpisodeCrew } from 'src/store/mockData'
import { fetchSeasonDetails } from 'src/lib/media'
import { upsertEpisodeMetadataInDb, upsertSeasonCastInDb, upsertEpisodeCrewInDb } from 'src/lib/db'
import { SpiderNoirModeModal } from 'src/components/SpiderNoirModeModal'

const TMDB_STILL_BASE = 'https://image.tmdb.org/t/p/w300'
const SPIDER_NOIR_TMDB_ID = 242484

function getSpiderNoirActiveMode(title: Title): 'bw' | 'color' | null {
  const allEvents: Array<{ date: string; colorMode: 'bw' | 'color' }> = []
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) {
      for (const we of ep.watchEvents) {
        if (we.colorMode) allEvents.push({ date: we.watchedAt, colorMode: we.colorMode })
      }
      for (const rv of ep.reviews) {
        if (rv.colorMode) allEvents.push({ date: rv.reviewedAt, colorMode: rv.colorMode })
      }
    }
  }
  if (allEvents.length === 0) return null
  allEvents.sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())
  return allEvents[0].colorMode
}

// ─── Shared status options ────────────────────────────────────────────────────

const STATUS_OPTIONS: { value: WatchStatus; label: string }[] = [
  { value: 'watched', label: 'Watched' },
  { value: 'watchlist', label: 'Watchlist' },
  { value: 'watching', label: 'Watching' },
  { value: 'dropped', label: 'Dropped' },
]

// ─── Movie-only: viewing timeline ────────────────────────────────────────────

function ViewingTimeline({ viewings }: { viewings: Viewing[] }) {
  if (viewings.length === 0) {
    return (
      <div className="text-center py-6 text-muted-foreground text-sm font-sans">
        No viewings logged yet
      </div>
    )
  }

  return (
    <div className="relative pl-6">
      <div className="absolute left-2 top-0 bottom-0 w-px bg-border" />
      <div className="space-y-4">
        {viewings
          .slice()
          .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())
          .map((v) => (
            <div key={v.id} className="relative">
              <div className="absolute -left-[18px] top-1 w-3 h-3 rounded-full bg-amber/70 border-2 border-void" />
              <div className="bg-secondary/50 rounded-lg p-3">
                <div className="flex items-center justify-between mb-1">
                  <span className="font-mono text-xs text-amber">
                    {new Date(v.date).toLocaleDateString('en-US', {
                      month: 'short', day: 'numeric', year: 'numeric',
                    })}
                  </span>
                  {v.rating && (
                    <span className="font-mono text-xs text-amber">★ {v.rating}</span>
                  )}
                </div>
                {v.notes && (
                  <p className="text-xs text-muted-foreground font-sans italic leading-relaxed">
                    "{v.notes}"
                  </p>
                )}
              </div>
            </div>
          ))}
      </div>
    </div>
  )
}

function ReviewBadges({ imdb, rt, meta }: { imdb?: number; rt?: number; meta?: number }) {
  return (
    <div className="flex flex-wrap gap-2">
      {imdb && (
        <div className="flex items-center gap-1.5 bg-secondary/60 rounded px-2.5 py-1.5">
          <span className="text-[#F5C518] font-mono font-bold text-xs">IMDb</span>
          <span className="font-mono text-sm text-foreground">{imdb}/10</span>
        </div>
      )}
      {rt && (
        <div className="flex items-center gap-1.5 bg-secondary/60 rounded px-2.5 py-1.5">
          <span className="text-[#FA320A] font-mono font-bold text-xs">RT</span>
          <span className="font-mono text-sm text-foreground">{rt}%</span>
        </div>
      )}
      {meta && (
        <div className="flex items-center gap-1.5 bg-secondary/60 rounded px-2.5 py-1.5">
          <span className="text-[#6ebc24] font-mono font-bold text-xs">MC</span>
          <span className="font-mono text-sm text-foreground">{meta}/100</span>
        </div>
      )}
    </div>
  )
}

// ─── Cast & Crew section ──────────────────────────────────────────────────────

const CREW_DISPLAY: Array<{ jobs: string[]; label: string }> = [
  { jobs: ['Creator'],                                     label: 'Created by' },
  { jobs: ['Director'],                                    label: 'Dir.' },
  { jobs: ['Screenplay', 'Writer', 'Teleplay', 'Story'],   label: 'Written by' },
  { jobs: ['Producer'],                                    label: 'Prod.' },
  { jobs: ['Director of Photography'],                     label: 'D.O.P.' },
  { jobs: ['Original Music Composer'],                     label: 'Composer' },
]

interface CastCrewSectionProps {
  cast?: CastMember[]
  crew?: CrewMember[]
  studios?: string[]
}

function CastCrewSection({ cast, crew, studios }: CastCrewSectionProps) {
  const hasCast = cast && cast.length > 0
  const hasCrew = crew && crew.length > 0
  const hasStudios = studios && studios.length > 0
  if (!hasCast && !hasCrew && !hasStudios) return null

  return (
    <div className="space-y-4">
      {hasCast && (
        <div>
          <div
            className="font-mono mb-2"
            style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
          >
            Cast
          </div>
          <div className="flex gap-3 overflow-x-auto scrollbar-none pb-1 -mx-1 px-1">
            {cast.map((member) => (
              <div key={member.tmdbPersonId} className="shrink-0 w-14 text-center">
                <div
                  className="w-14 h-14 rounded-full overflow-hidden mb-1 mx-auto flex items-center justify-center"
                  style={{ background: 'rgba(0,0,0,0.3)', border: '1px solid var(--line)' }}
                >
                  {member.profileUrl ? (
                    <img src={member.profileUrl} alt={member.name} className="w-full h-full object-cover" />
                  ) : (
                    <span className="font-mono text-lg" style={{ color: 'var(--paper-faint)' }}>
                      {member.name.charAt(0).toUpperCase()}
                    </span>
                  )}
                </div>
                <div
                  className="font-sans truncate"
                  style={{ fontSize: '10px', color: 'var(--paper)', lineHeight: 1.3 }}
                  title={member.name}
                >
                  {member.name}
                </div>
                {member.character && (
                  <div
                    className="font-mono truncate"
                    style={{ fontSize: '9px', color: 'var(--paper-faint)', lineHeight: 1.3 }}
                    title={member.character}
                  >
                    {member.character}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {(hasCrew || hasStudios) && (
        <div className="space-y-1.5">
          {hasCrew && CREW_DISPLAY.map(({ jobs, label }) => {
            const members = crew!.filter((c) => jobs.includes(c.job))
            if (members.length === 0) return null
            return (
              <div key={label} className="flex gap-3" style={{ fontSize: '12px' }}>
                <span
                  className="font-mono shrink-0 text-right"
                  style={{ width: '80px', color: 'var(--paper-faint)', fontSize: '10px', paddingTop: '1px' }}
                >
                  {label}
                </span>
                <span className="font-sans" style={{ color: 'var(--paper)' }}>
                  {members.map((m) => m.name).join(' · ')}
                </span>
              </div>
            )
          })}
          {hasStudios && (
            <div className="flex gap-3" style={{ fontSize: '12px' }}>
              <span
                className="font-mono shrink-0 text-right"
                style={{ width: '80px', color: 'var(--paper-faint)', fontSize: '10px', paddingTop: '1px' }}
              >
                Studio
              </span>
              <span className="font-sans" style={{ color: 'var(--paper)' }}>
                {studios!.join(', ')}
              </span>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ─── TV: Episode log form (watch event + optional rating + optional review) ───

interface EpLogState {
  includeWatch: boolean
  watchedAt: string
  watchNotes: string
  rating: number
  reviewText: string
}

const EMPTY_EP_LOG: EpLogState = {
  includeWatch: true,
  watchedAt: new Date().toISOString().slice(0, 10),
  watchNotes: '',
  rating: 0,
  reviewText: '',
}

interface EpisodePanelProps {
  episode: Episode
  season: Season
  titleId: string
  isSharedView: boolean
  isSpiderNoir: boolean
}

function EpisodePanel({ episode, season, titleId, isSharedView, isSpiderNoir }: EpisodePanelProps) {
  const logEpisode = useAppStore((s) => s.logEpisode)
  const [log, setLog] = useState<EpLogState>(EMPTY_EP_LOG)
  const [showForm, setShowForm] = useState(false)
  const [pendingLog, setPendingLog] = useState<EpLogState | null>(null)
  const [showNoirModal, setShowNoirModal] = useState(false)

  const avg = avgEpisodeRating(episode)
  const watched = episode.watchEvents.length > 0

  function doSave(epLog: EpLogState, colorMode?: 'bw' | 'color') {
    if (!epLog.includeWatch && epLog.rating === 0 && !epLog.reviewText.trim()) return
    logEpisode(titleId, season.seasonNumber, episode.episodeNumber, {
      watchedAt: epLog.includeWatch ? epLog.watchedAt : undefined,
      watchNotes: epLog.includeWatch ? epLog.watchNotes : undefined,
      rating: epLog.rating > 0 ? epLog.rating : undefined,
      reviewText: epLog.reviewText.trim() || undefined,
      colorMode,
    })
    setLog(EMPTY_EP_LOG)
    setShowForm(false)
  }

  function handleSubmit() {
    if (!log.includeWatch && log.rating === 0 && !log.reviewText.trim()) return
    if (isSpiderNoir) {
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
  }

  function handleNoirSkip() {
    setShowNoirModal(false)
    if (pendingLog) doSave(pendingLog)
    setPendingLog(null)
  }

  const fmtDate = (iso: string) =>
    new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
  const fmtDateTime = (iso: string) =>
    new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })

  return (
    <div className="ep-panel px-3 py-3 space-y-3" style={{ borderTop: '1px solid var(--line)' }}>
      {/* Episode crew — director / writers */}
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

      {/* Episode still + synopsis */}
      {(episode.stillUrl || episode.synopsis) && (
        <div className="flex gap-3">
          {episode.stillUrl && (
            <img
              src={episode.stillUrl}
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

      {/* Existing history */}
      {(episode.watchEvents.length > 0 || episode.ratings.length > 0 || episode.reviews.length > 0) && (
        <div className="grid grid-cols-3 gap-2 text-xs">
          {/* Watch events */}
          <div>
            <div
              className="font-mono mb-1.5"
              style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
            >
              Watched
            </div>
            {episode.watchEvents.length === 0 ? (
              <span style={{ color: 'var(--paper-faint)' }}>—</span>
            ) : (
              episode.watchEvents.map((we) => (
                <div key={we.id} className="font-mono" style={{ color: 'var(--amber)', fontSize: '11px' }}>
                  {fmtDate(we.watchedAt)}
                  {we.colorMode && (
                    <span
                      className="font-mono ml-1.5 px-1 rounded"
                      style={{
                        fontSize: '9px',
                        letterSpacing: '0.06em',
                        background: we.colorMode === 'bw' ? 'rgba(200,200,200,0.12)' : 'rgba(233,178,102,0.15)',
                        color: we.colorMode === 'bw' ? '#aaa' : 'var(--amber)',
                        border: `1px solid ${we.colorMode === 'bw' ? 'rgba(200,200,200,0.2)' : 'rgba(233,178,102,0.3)'}`,
                      }}
                    >
                      {we.colorMode === 'bw' ? '◐ B&W' : '◈ Color'}
                    </span>
                  )}
                  {we.notes && (
                    <div className="font-sans italic mt-0.5" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>
                      {we.notes}
                    </div>
                  )}
                </div>
              ))
            )}
          </div>

          {/* Ratings */}
          <div>
            <div
              className="font-mono mb-1.5"
              style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
            >
              Ratings
            </div>
            {episode.ratings.length === 0 ? (
              <span style={{ color: 'var(--paper-faint)', fontSize: '11px' }}>—</span>
            ) : (
              episode.ratings.map((er) => (
                <div key={er.id} className="font-mono" style={{ color: 'var(--amber)', fontSize: '11px' }}>
                  ★ {er.rating}
                  <div className="font-sans mt-0.5" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>
                    {fmtDateTime(er.ratedAt)}
                  </div>
                </div>
              ))
            )}
            {episode.ratings.length > 1 && avg !== null && (
              <div className="font-mono mt-1" style={{ color: 'var(--amber-deep)', fontSize: '10px' }}>
                avg ★ {avg.toFixed(1)}
              </div>
            )}
          </div>

          {/* Reviews */}
          <div>
            <div
              className="font-mono mb-1.5"
              style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
            >
              Reviews
            </div>
            {episode.reviews.length === 0 ? (
              <span style={{ color: 'var(--paper-faint)', fontSize: '11px' }}>—</span>
            ) : (
              episode.reviews.map((rv) => (
                <div key={rv.id}>
                  <div className="font-sans italic leading-snug" style={{ color: 'var(--paper-dim)', fontSize: '11px' }}>
                    "{rv.reviewText}"
                  </div>
                  <div className="font-mono mt-0.5 flex items-center gap-1.5" style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>
                    {fmtDateTime(rv.reviewedAt)}
                    {rv.colorMode && (
                      <span
                        className="font-mono px-1 rounded"
                        style={{
                          fontSize: '9px',
                          letterSpacing: '0.06em',
                          background: rv.colorMode === 'bw' ? 'rgba(200,200,200,0.12)' : 'rgba(233,178,102,0.15)',
                          color: rv.colorMode === 'bw' ? '#aaa' : 'var(--amber)',
                          border: `1px solid ${rv.colorMode === 'bw' ? 'rgba(200,200,200,0.2)' : 'rgba(233,178,102,0.3)'}`,
                        }}
                      >
                        {rv.colorMode === 'bw' ? '◐ B&W' : '◈ Color'}
                      </span>
                    )}
                  </div>
                </div>
              ))
            )}
          </div>
        </div>
      )}

      {/* Log form */}
      {!isSharedView && (
        showForm ? (
          <div className="space-y-3 pt-2" style={{ borderTop: episode.watchEvents.length > 0 || episode.ratings.length > 0 || episode.reviews.length > 0 ? '1px solid var(--line)' : 'none' }}>
            {/* Watch event toggle */}
            <label className="flex items-center gap-2 cursor-pointer">
              <input
                type="checkbox"
                checked={log.includeWatch}
                onChange={(e) => setLog((l) => ({ ...l, includeWatch: e.target.checked }))}
                className="accent-amber w-3.5 h-3.5"
              />
              <span className="font-sans text-xs" style={{ color: 'var(--paper-dim)' }}>
                Log a watch event
              </span>
            </label>

            {log.includeWatch && (
              <div className="space-y-2 pl-5">
                <Input
                  type="date"
                  value={log.watchedAt}
                  onChange={(e) => setLog((l) => ({ ...l, watchedAt: e.target.value }))}
                  className="h-8 text-xs font-mono bg-secondary/50 border-border"
                />
                <Input
                  value={log.watchNotes}
                  onChange={(e) => setLog((l) => ({ ...l, watchNotes: e.target.value }))}
                  placeholder="Watch notes (optional)"
                  className="h-8 text-xs bg-secondary/50 border-border"
                />
              </div>
            )}

            {/* Rating (independent) */}
            <div>
              <div className="font-sans text-xs mb-1.5" style={{ color: 'var(--paper-faint)' }}>
                Rating <span style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>(optional · logged independently)</span>
              </div>
              <StarRating value={log.rating} onChange={(r) => setLog((l) => ({ ...l, rating: r }))} size="sm" />
            </div>

            {/* Review (independent) */}
            <div>
              <div className="font-sans text-xs mb-1.5" style={{ color: 'var(--paper-faint)' }}>
                Review <span style={{ color: 'var(--paper-faint)', fontSize: '10px' }}>(optional · logged independently)</span>
              </div>
              <textarea
                value={log.reviewText}
                onChange={(e) => setLog((l) => ({ ...l, reviewText: e.target.value }))}
                placeholder="Your thoughts on this episode…"
                rows={2}
                className="w-full text-xs font-sans resize-none rounded-md px-2.5 py-2 focus:outline-none focus:ring-1 focus:ring-amber/40"
                style={{
                  background: 'rgba(0,0,0,0.25)',
                  border: '1px solid var(--line)',
                  color: 'var(--paper)',
                }}
              />
            </div>

            <div className="flex gap-2">
              <button
                onClick={handleSubmit}
                disabled={!log.includeWatch && log.rating === 0 && !log.reviewText.trim()}
                className="flex-1 h-8 rounded-md text-xs font-sans font-medium transition-all disabled:opacity-40"
                style={{ background: 'var(--amber)', color: '#1a0e06' }}
              >
                Save
              </button>
              <button
                onClick={() => { setShowForm(false); setLog(EMPTY_EP_LOG) }}
                className="h-8 px-3 rounded-md text-xs font-sans border transition-colors"
                style={{ borderColor: 'var(--line)', color: 'var(--paper-faint)' }}
              >
                Cancel
              </button>
            </div>
          </div>
        ) : (
          <button
            onClick={() => setShowForm(true)}
            className="flex items-center gap-1.5 text-xs font-mono transition-colors"
            style={{ color: 'var(--amber-deep)' }}
            onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--amber)')}
            onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--amber-deep)')}
          >
            <Plus className="w-3 h-3" />
            {watched ? 'Add rating, review, or re-watch' : 'Log watch event, rating, or review'}
          </button>
        )
      )}
      <SpiderNoirModeModal
        open={showNoirModal}
        onSelect={handleNoirSelect}
        onSkip={handleNoirSkip}
      />
    </div>
  )
}

// ─── TV: Episode row ──────────────────────────────────────────────────────────

interface EpisodeRowProps {
  episode: Episode
  season: Season
  titleId: string
  expanded: boolean
  onToggle: () => void
  isSharedView: boolean
  isSpiderNoir: boolean
}

function EpisodeRow({ episode, season, titleId, expanded, onToggle, isSharedView, isSpiderNoir }: EpisodeRowProps) {
  const avg = avgEpisodeRating(episode)
  const watched = episode.watchEvents.length > 0

  return (
    <div>
      <div
        role="button"
        tabIndex={0}
        onClick={onToggle}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onToggle() } }}
        className={cn('episode-row', expanded && 'is-expanded', watched && 'is-watched')}
      >
        {/* Episode number */}
        <span
          className="ep-num font-mono shrink-0 w-8 text-right"
          style={{ fontSize: '11px', color: watched ? 'var(--amber)' : 'var(--paper-faint)' }}
        >
          E{String(episode.episodeNumber).padStart(2, '0')}
        </span>

        {/* Name + meta */}
        <div className="flex-1 min-w-0">
          <div
            className="font-sans truncate"
            style={{ fontSize: '13px', color: 'var(--paper)' }}
          >
            {episode.episodeName ?? `Episode ${episode.episodeNumber}`}
          </div>
          {episode.airDate && (
            <div className="font-mono" style={{ fontSize: '10px', color: 'var(--paper-faint)' }}>
              {new Date(episode.airDate).getFullYear()}
              {episode.runtime ? ` · ${episode.runtime}m` : ''}
            </div>
          )}
        </div>

        {/* Watch + rating indicators */}
        <div className="flex items-center gap-2 shrink-0">
          {watched && (
            <Eye className="w-3 h-3" style={{ color: 'var(--amber)', opacity: 0.8 }} />
          )}
          {episode.reviews.length > 0 && (
            <MessageSquare className="w-3 h-3" style={{ color: 'var(--paper-faint)', opacity: 0.7 }} />
          )}
          {avg !== null ? (
            <span className="font-mono" style={{ fontSize: '11px', color: 'var(--amber)' }}>
              ★ {avg.toFixed(1)}
            </span>
          ) : (
            <span className="font-mono" style={{ fontSize: '11px', color: 'var(--paper-faint)' }}>—</span>
          )}
          {expanded
            ? <ChevronDown className="w-3.5 h-3.5" style={{ color: 'var(--paper-faint)' }} />
            : <ChevronRight className="w-3.5 h-3.5" style={{ color: 'var(--paper-faint)' }} />
          }
        </div>
      </div>

      {expanded && (
        <EpisodePanel
          episode={episode}
          season={season}
          titleId={titleId}
          isSharedView={isSharedView}
          isSpiderNoir={isSpiderNoir}
        />
      )}
    </div>
  )
}

// ─── TV: Season tab bar + episode list ───────────────────────────────────────

interface TVSeriesSectionProps {
  titleId: string
  seasons: Season[]
  isSharedView: boolean
  isSpiderNoir: boolean
}

function TVSeriesSection({ titleId, seasons, isSharedView, isSpiderNoir }: TVSeriesSectionProps) {
  const [selectedSeason, setSelectedSeason] = useState(seasons[0]?.seasonNumber ?? 1)
  const [expandedEpId, setExpandedEpId] = useState<string | null>(null)

  const season = seasons.find((s) => s.seasonNumber === selectedSeason)
  const hasEpisodes = (s: Season) => (s.episodes?.length ?? 0) > 0

  // Rollup stats
  const totalWatched = totalEpisodesWatched(seasons)
  const totalCount = totalEpisodeCount(seasons)
  const seriesAvg = avgSeriesRating(seasons)

  return (
    <div className="space-y-5">
      {/* Series-level stats */}
      <div className="grid grid-cols-3 gap-3">
        <div className="rounded-lg p-3 text-center" style={{ background: 'rgba(0,0,0,0.25)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--paper)', fontVariationSettings: '"opsz" 30' }}>
            {totalWatched}<span className="text-sm font-mono ml-0.5" style={{ color: 'var(--paper-faint)' }}>/{totalCount}</span>
          </div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>
            Episodes
          </div>
        </div>
        <div className="rounded-lg p-3 text-center" style={{ background: 'rgba(0,0,0,0.25)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--amber)', fontVariationSettings: '"opsz" 30' }}>
            {seriesAvg !== null ? `★ ${seriesAvg.toFixed(1)}` : '—'}
          </div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>
            Avg Rating
          </div>
        </div>
        <div className="rounded-lg p-3 text-center" style={{ background: 'rgba(0,0,0,0.25)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--paper)', fontVariationSettings: '"opsz" 30' }}>
            {seasons.length}
          </div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>
            Seasons
          </div>
        </div>
      </div>

      {/* Series Graph heatmap — only when at least one season has episode data */}
      {seasons.some(hasEpisodes) && (
        <div>
          <h4
            className="font-mono mb-3"
            style={{ fontSize: '10px', letterSpacing: '0.18em', textTransform: 'uppercase', color: 'var(--paper-faint)' }}
          >
            Series Graph
          </h4>
          <div
            className="rounded-xl p-4"
            style={{ background: 'rgba(0,0,0,0.3)', border: '1px solid var(--line)' }}
          >
            <SeriesGraph
              seasons={seasons}
              onCellClick={(sn, en) => {
                setSelectedSeason(sn)
                const ep = seasons.find((s) => s.seasonNumber === sn)?.episodes?.find((e) => e.episodeNumber === en)
                if (ep) setExpandedEpId(ep.id)
              }}
            />
          </div>
        </div>
      )}

      {/* Season tabs */}
      <div>
        <div className="flex gap-1.5 overflow-x-auto scrollbar-none mb-3 -mx-1 px-1">
          {seasons.map((s) => {
            const watched = episodesWatchedInSeason(s)
            const pct = s.episodeCount > 0 ? Math.round((watched / s.episodeCount) * 100) : 0
            const seasonAvg = avgSeasonRating(s)
            return (
              <button
                key={s.seasonNumber}
                onClick={() => { setSelectedSeason(s.seasonNumber); setExpandedEpId(null) }}
                className={cn(
                  'shrink-0 px-3 py-2 rounded-lg text-left transition-all border',
                  selectedSeason === s.seasonNumber
                    ? 'border-amber/40 bg-amber/10'
                    : 'border-transparent hover:border-[var(--line)] hover:bg-[rgba(255,255,255,0.03)]'
                )}
              >
                <div
                  className="font-mono"
                  style={{ fontSize: '11px', color: selectedSeason === s.seasonNumber ? 'var(--amber)' : 'var(--paper-dim)' }}
                >
                  S{s.seasonNumber}
                </div>
                <div className="font-mono" style={{ fontSize: '9px', color: 'var(--paper-faint)' }}>
                  {pct}%{seasonAvg !== null ? ` · ★${seasonAvg.toFixed(1)}` : ''}
                </div>
              </button>
            )
          })}
        </div>

        {/* Season cast — shown when the selected season has cast data */}
        {season?.cast && season.cast.length > 0 && (
          <div className="mb-4">
            <div
              className="font-mono mb-2"
              style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
            >
              Season Cast
            </div>
            <div className="flex gap-3 overflow-x-auto scrollbar-none pb-1 -mx-1 px-1">
              {season.cast.map((member) => (
                <div key={member.tmdbPersonId} className="shrink-0 w-12 text-center">
                  <div
                    className="w-12 h-12 rounded-full overflow-hidden mb-1 mx-auto flex items-center justify-center"
                    style={{ background: 'rgba(0,0,0,0.3)', border: '1px solid var(--line)' }}
                  >
                    {member.profileUrl ? (
                      <img src={member.profileUrl} alt={member.name} className="w-full h-full object-cover" />
                    ) : (
                      <span className="font-mono text-base" style={{ color: 'var(--paper-faint)' }}>
                        {member.name.charAt(0).toUpperCase()}
                      </span>
                    )}
                  </div>
                  <div
                    className="font-sans truncate"
                    style={{ fontSize: '9px', color: 'var(--paper)', lineHeight: 1.3 }}
                    title={member.name}
                  >
                    {member.name}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Episode list for selected season */}
        {season && (
          <div>
            {hasEpisodes(season) ? (
              <div className="space-y-0.5">
                {season.episodes!.map((ep) => (
                  <EpisodeRow
                    key={ep.id}
                    episode={ep}
                    season={season}
                    titleId={titleId}
                    expanded={expandedEpId === ep.id}
                    onToggle={() => setExpandedEpId(expandedEpId === ep.id ? null : ep.id)}
                    isSharedView={isSharedView}
                    isSpiderNoir={isSpiderNoir}
                  />
                ))}
              </div>
            ) : (
              /* Fallback: coarse progress bar when this season has no episode-level data */
              (() => {
                const pct = season.episodeCount > 0 ? (season.episodesWatched / season.episodeCount) * 100 : 0
                return (
                  <div className="flex items-center gap-3 px-2 py-3">
                    <span className="font-mono text-xs text-muted-foreground w-8 shrink-0">S{season.seasonNumber}</span>
                    <div className="flex-1 h-1.5 bg-secondary rounded-full overflow-hidden">
                      <div className="h-full bg-amber rounded-full transition-all" style={{ width: `${pct}%` }} />
                    </div>
                    <span className="font-mono text-xs text-muted-foreground w-12 text-right shrink-0">
                      {season.episodesWatched}/{season.episodeCount}
                    </span>
                  </div>
                )
              })()
            )}
          </div>
        )}
      </div>
    </div>
  )
}

// ─── Main drawer ─────────────────────────────────────────────────────────────

export function TitleDetailDrawer() {
  const { isDetailDrawerOpen, closeDetailDrawer, updateTitle, removeTitle, openRefreshMetadata, isSharedView } = useAppStore()
  const title = useSelectedTitle()
  const user = useAppStore((s) => s.user)

  const isSpiderNoir = title?.tmdbId === SPIDER_NOIR_TMDB_ID
  const activeSpiderNoirMode = isSpiderNoir && title ? getSpiderNoirActiveMode(title) : null

  useEffect(() => {
    document.body.classList.remove('spider-noir-bw', 'spider-noir-color')
    if (isSpiderNoir && activeSpiderNoirMode) {
      document.body.classList.add(
        activeSpiderNoirMode === 'bw' ? 'spider-noir-bw' : 'spider-noir-color'
      )
    }
    return () => {
      document.body.classList.remove('spider-noir-bw', 'spider-noir-color')
    }
  }, [isSpiderNoir, activeSpiderNoirMode])

  const [showLogForm, setShowLogForm] = useState(false)
  const [logDate, setLogDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [logRating, setLogRating] = useState(0)
  const [logNotes, setLogNotes] = useState('')

  // Track which title IDs have already been backfilled this session to avoid repeat calls.
  const backfilledRef = useRef<Set<string>>(new Set())

  // When a TV show is opened and episode metadata is missing, fetch season details
  // from TMDB and hydrate them in-place, then persist to DB. Handles two cases:
  // (a) season rows exist but no episode rows were ever inserted, and
  // (b) episode rows exist but none have names yet.
  useEffect(() => {
    if (!title || title.type !== 'tv' || !title.seasons || title.tmdbId <= 0 || isSharedView) return

    const seasonsNeedingBackfill = title.seasons.filter((s) =>
      // Case (a): season exists but no episodes were persisted to DB yet
      (s.episodeCount > 0 && (!s.episodes || s.episodes.length === 0)) ||
      // Case (b): episode rows exist but none have a name (pre-feature data)
      (s.episodes && s.episodes.length > 0 && s.episodes.every((ep) => !ep.episodeName))
      // Season cast is captured as a bonus of episode backfills above;
      // shows with complete episode data get cast via "Refresh metadata" instead.
    )
    if (seasonsNeedingBackfill.length === 0) return

    const cacheKey = `${title.id}:${title.tmdbId}`
    if (backfilledRef.current.has(cacheKey)) return
    backfilledRef.current.add(cacheKey)

    const snapshotTitle = title
    const snapshotUser = user

    const EP_CREW_JOBS_BF = new Set(['Director', 'Writer', 'Teleplay', 'Story'])

    async function backfill() {
      const settled = await Promise.allSettled(
        seasonsNeedingBackfill.map(async (season) => {
          const { episodes: tmdbEps, cast: seasonCast } = await fetchSeasonDetails(snapshotTitle.tmdbId, season.seasonNumber)
          return { season, tmdbEps, seasonCast }
        })
      )

      let updatedSeasons = [...snapshotTitle.seasons!]
      const allUpdatedEpisodes: Parameters<typeof upsertEpisodeMetadataInDb>[2] = []
      const allEpisodeCrew: Array<{ episodeId: string; crew: EpisodeCrew[] }> = []
      const allSeasonCast: Array<{ seasonId: string; cast: CastMember[] }> = []

      for (const result of settled) {
        if (result.status !== 'fulfilled' || result.value.tmdbEps.length === 0) continue
        const { season, tmdbEps, seasonCast } = result.value

        const existingEpisodes = season.episodes || []
        let updatedEpisodes: Episode[]

        if (existingEpisodes.length === 0) {
          updatedEpisodes = tmdbEps.map((tmdbEp) => {
            const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
              .filter((c) => EP_CREW_JOBS_BF.has(c.job))
              .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
            return {
              id: crypto.randomUUID(),
              episodeNumber: tmdbEp.episode_number,
              episodeName: tmdbEp.name || undefined,
              airDate: tmdbEp.air_date || undefined,
              runtime: tmdbEp.runtime || undefined,
              synopsis: tmdbEp.overview || undefined,
              stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : undefined,
              director: epCrew.find((c) => c.job === 'Director')?.name,
              writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
              crew: epCrew.length > 0 ? epCrew : undefined,
              watchEvents: [],
              ratings: [],
              reviews: [],
            }
          })
          updatedSeasons = updatedSeasons.map((s) =>
            s.seasonNumber === season.seasonNumber
              ? { ...s, episodes: updatedEpisodes, episodeCount: updatedEpisodes.length, cast: seasonCast.length > 0 ? seasonCast : s.cast }
              : s
          )
        } else {
          updatedEpisodes = existingEpisodes.map((ep) => {
            const tmdbEp = tmdbEps.find((e) => e.episode_number === ep.episodeNumber)
            if (!tmdbEp) return ep
            const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
              .filter((c) => EP_CREW_JOBS_BF.has(c.job))
              .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
            return {
              ...ep,
              episodeName: tmdbEp.name || ep.episodeName,
              airDate: tmdbEp.air_date || ep.airDate,
              runtime: tmdbEp.runtime || ep.runtime,
              synopsis: tmdbEp.overview || ep.synopsis,
              stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : ep.stillUrl,
              director: epCrew.find((c) => c.job === 'Director')?.name ?? ep.director,
              writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
              crew: epCrew.length > 0 ? epCrew : ep.crew,
            }
          })
          updatedSeasons = updatedSeasons.map((s) =>
            s.seasonNumber === season.seasonNumber
              ? { ...s, episodes: updatedEpisodes, cast: seasonCast.length > 0 ? seasonCast : s.cast }
              : s
          )
        }

        for (const ep of updatedEpisodes) {
          allUpdatedEpisodes.push({
            id: ep.id,
            seasonNumber: season.seasonNumber,
            episodeNumber: ep.episodeNumber,
            episodeName: ep.episodeName,
            airDate: ep.airDate,
            runtime: ep.runtime,
            synopsis: ep.synopsis,
            stillUrl: ep.stillUrl,
          })
          if (ep.crew && ep.crew.length > 0) {
            allEpisodeCrew.push({ episodeId: ep.id, crew: ep.crew })
          }
        }

        if (seasonCast.length > 0) {
          allSeasonCast.push({ seasonId: season.id, cast: seasonCast })
        }
      }

      if (allUpdatedEpisodes.length > 0 || allSeasonCast.length > 0) {
        updateTitle(snapshotTitle.id, { seasons: updatedSeasons })
        if (snapshotUser) {
          if (allUpdatedEpisodes.length > 0) {
            upsertEpisodeMetadataInDb(snapshotUser.id, snapshotTitle.id, allUpdatedEpisodes).catch((e) =>
              console.error('Episode metadata backfill DB write failed:', e)
            )
          }
          for (const { seasonId, cast } of allSeasonCast) {
            upsertSeasonCastInDb(snapshotUser.id, snapshotTitle.id, seasonId, cast).catch((e) =>
              console.error('Season cast backfill DB write failed:', e)
            )
          }
          for (const { episodeId, crew } of allEpisodeCrew) {
            upsertEpisodeCrewInDb(snapshotUser.id, snapshotTitle.id, episodeId, crew).catch((e) =>
              console.error('Episode crew backfill DB write failed:', e)
            )
          }
        }
      }
    }

    backfill().catch((e) => console.error('Episode metadata backfill failed:', e))
  }, [title?.id, title?.tmdbId]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!title) return null

  function logViewing() {
    if (!title || !logDate) return
    const viewing: Viewing = {
      id: crypto.randomUUID(),
      titleId: title.id,
      date: logDate,
      rating: logRating > 0 ? logRating : undefined,
      notes: logNotes || undefined,
    }
    updateTitle(title.id, {
      viewings: [...title.viewings, viewing],
      status: 'watched',
      rating: logRating > 0 ? logRating : title.rating,
    })
    setShowLogForm(false)
    setLogDate(new Date().toISOString().slice(0, 10))
    setLogRating(0)
    setLogNotes('')
  }

  function handleDelete() {
    if (!title) return
    const ok = window.confirm(`Remove "${title.title}" from your library? This can't be undone.`)
    if (!ok) return
    closeDetailDrawer()
    removeTitle(title.id)
  }

  return (
    <CinemaModal
      open={isDetailDrawerOpen}
      onClose={closeDetailDrawer}
      maxWidth="sm:max-w-2xl"
      title={title.title}
      description={title.synopsis ?? `Details and viewing history for ${title.title}.`}
    >
      <div className="overflow-y-auto flex-1 scrollbar-thin">
        {/* Hero: blurred poster background + title info */}
        <div className="relative overflow-hidden shrink-0">
          {title.posterUrl && (
            <div
              className="absolute inset-0"
              style={{
                backgroundImage: `url(${title.posterUrl})`,
                backgroundSize: 'cover',
                backgroundPosition: 'center top',
                filter: 'blur(20px)',
                transform: 'scale(1.3)',
                opacity: 0.18,
              }}
            />
          )}
          <div className="absolute inset-0 bg-gradient-to-b from-secondary/30 via-card/70 to-card" />
          <div className="relative z-10 flex gap-5 px-6 pt-10 pb-6">
            <div className="w-28 sm:w-36 shrink-0">
              <DynamicPoster title={title} />
            </div>
            <div className="flex-1 min-w-0 space-y-2 pt-6">
              <div className="flex items-center gap-2">
                {title.type === 'movie' ? (
                  <Film className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                ) : (
                  <Tv className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
                )}
                <span className="font-mono text-xs text-muted-foreground uppercase tracking-wider">
                  {title.type === 'tv' ? 'Series' : 'Film'}
                </span>
                {title.network && (
                  <span className="font-mono text-xs text-muted-foreground">· {title.network}</span>
                )}
              </div>
              <CardTitle className="text-xl leading-tight">{title.title}</CardTitle>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="font-mono text-sm text-amber">{title.year}</span>
                {title.director && (
                  <span className="text-xs text-muted-foreground font-sans">
                    dir. {title.director}
                  </span>
                )}
                {title.runtime && title.type === 'movie' && (
                  <div className="flex items-center gap-1 text-xs text-muted-foreground">
                    <Clock className="w-3 h-3" />
                    <span className="font-mono">{title.runtime}m</span>
                  </div>
                )}
              </div>
              <StarRating
                value={title.rating ?? 0}
                size="sm"
                onChange={isSharedView ? undefined : (rating) => updateTitle(title.id, { rating })}
              />
            </div>
          </div>
        </div>

        {/* Scrollable body */}
        <div className="px-6 pb-6 space-y-5">
          {/* Status */}
          <div>
            <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">Status</h4>
            <div className="flex flex-wrap gap-2">
              {STATUS_OPTIONS.map((opt) => (
                <button
                  key={opt.value}
                  onClick={isSharedView ? undefined : () => updateTitle(title.id, { status: opt.value })}
                  className={cn(
                    'px-3 py-1.5 rounded-md text-xs font-sans border transition-all',
                    title.status === opt.value
                      ? 'bg-amber/20 border-amber/50 text-amber'
                      : 'bg-secondary/50 border-border text-muted-foreground hover:text-foreground',
                    isSharedView && 'opacity-60 cursor-default pointer-events-none'
                  )}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          {/* Synopsis */}
          {title.synopsis && (
            <div>
              <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">Synopsis</h4>
              <BodyText className="text-sm leading-relaxed">{title.synopsis}</BodyText>
            </div>
          )}

          {/* Genres + Tags */}
          {(title.genres.length > 0 || title.tags.length > 0) && (
            <div className="flex flex-wrap gap-1.5">
              {title.genres.map((g) => (
                <MetaBadge key={g} className="border-amber/20 text-amber/70">{g}</MetaBadge>
              ))}
              {title.tags.map((t) => (
                <MetaBadge key={t}>{t}</MetaBadge>
              ))}
            </div>
          )}

          {/* Cast & Crew */}
          {(title.cast?.length || title.crew?.length || title.studios?.length) ? (
            <div>
              <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">
                Cast &amp; Crew
              </h4>
              <CastCrewSection cast={title.cast} crew={title.crew} studios={title.studios} />
            </div>
          ) : null}

          {/* Critical Reception */}
          {(title.imdbRating || title.rtScore || title.metacriticScore) && (
            <div>
              <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                Critical Reception
              </h4>
              <ReviewBadges imdb={title.imdbRating} rt={title.rtScore} meta={title.metacriticScore} />
            </div>
          )}

          {/* ── TV Series section ───────────────────────────────────── */}
          {title.type === 'tv' && title.seasons && title.seasons.length > 0 && (
            <div>
              <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-4">
                Season &amp; Episodes
              </h4>
              <TVSeriesSection
                titleId={title.id}
                seasons={title.seasons}
                isSharedView={isSharedView}
                isSpiderNoir={title.tmdbId === SPIDER_NOIR_TMDB_ID}
              />
            </div>
          )}

          {/* ── Movie section (and TV without seasons) ─────────────── */}
          {title.type === 'movie' && (
            <>
              {/* Viewing Stats */}
              <div className="grid grid-cols-2 gap-4">
                <div className="bg-secondary/40 rounded-lg p-3 text-center">
                  <StatNumber className="text-xl">{title.viewings.length}</StatNumber>
                  <div className="mt-0.5">
                    <StatLabel>Viewings</StatLabel>
                  </div>
                </div>
                <div className="bg-secondary/40 rounded-lg p-3 text-center">
                  <div className="flex items-center justify-center gap-1 mb-0.5">
                    <Calendar className="w-3.5 h-3.5 text-muted-foreground" />
                    <StatNumber className="text-xl">
                      {title.viewings.length > 0
                        ? new Date(
                            title.viewings
                              .slice()
                              .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())[0]
                              .date
                          ).getFullYear()
                        : '—'}
                    </StatNumber>
                  </div>
                  <StatLabel>Last Seen</StatLabel>
                </div>
              </div>

              {/* Viewing History */}
              <div>
                <div className="flex items-center justify-between mb-3">
                  <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground">
                    Viewing History
                  </h4>
                  {!showLogForm && !isSharedView && (
                    <button
                      onClick={() => setShowLogForm(true)}
                      className="flex items-center gap-1 text-xs font-mono text-amber/70 hover:text-amber transition-colors"
                    >
                      <Plus className="w-3.5 h-3.5" />
                      Log a viewing
                    </button>
                  )}
                </div>

                {showLogForm && (
                  <div className="bg-secondary/40 rounded-lg p-3 mb-4 space-y-3">
                    <div>
                      <label className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                        <Calendar className="inline w-3 h-3 mr-1" />
                        Date Watched
                      </label>
                      <Input
                        type="date"
                        value={logDate}
                        onChange={(e) => setLogDate(e.target.value)}
                        className="bg-secondary/50 border-border font-mono"
                      />
                    </div>
                    <div>
                      <label className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                        Rating
                      </label>
                      <StarRating value={logRating} onChange={setLogRating} size="md" />
                    </div>
                    <div>
                      <label className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                        <FileText className="inline w-3 h-3 mr-1" />
                        Notes
                      </label>
                      <textarea
                        value={logNotes}
                        onChange={(e) => setLogNotes(e.target.value)}
                        placeholder="Your thoughts…"
                        rows={2}
                        className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-2 focus:ring-amber/30"
                      />
                    </div>
                    <div className="flex gap-2">
                      <Button
                        className="flex-1 bg-amber hover:bg-amber-muted text-void font-sans font-medium"
                        onClick={logViewing}
                      >
                        <Star className="w-4 h-4 mr-2" />
                        Save Viewing
                      </Button>
                      <Button variant="outline" onClick={() => setShowLogForm(false)}>
                        Cancel
                      </Button>
                    </div>
                  </div>
                )}

                <ViewingTimeline viewings={title.viewings} />
              </div>
            </>
          )}

          {/* Maintenance actions */}
          {!isSharedView && (
            <div
              className="pt-2 border-t flex flex-wrap items-center gap-x-5 gap-y-2"
              style={{ borderColor: 'var(--line)' }}
            >
              <button
                onClick={openRefreshMetadata}
                className="flex items-center gap-2 text-xs font-mono text-muted-foreground hover:text-amber transition-colors"
              >
                <RefreshCw className="w-3.5 h-3.5" />
                Refresh poster &amp; metadata
              </button>
              <button
                onClick={handleDelete}
                className="flex items-center gap-2 text-xs font-mono text-muted-foreground hover:text-ember transition-colors"
              >
                <Trash2 className="w-3.5 h-3.5" />
                Remove from library
              </button>
            </div>
          )}
        </div>
      </div>
    </CinemaModal>
  )
}
