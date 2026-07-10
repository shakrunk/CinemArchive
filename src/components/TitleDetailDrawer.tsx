import { useState, useEffect, useRef, useMemo } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { StarRating } from 'src/components/ui/star-rating'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { PosterLightbox } from 'src/components/ui/poster-lightbox'
import { SeriesGraph } from 'src/components/ui/series-graph'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { CardTitle, BodyText, MetaBadge, StatNumber, StatLabel } from 'src/components/ui/typography'
import { useAppStore, useSelectedTitle } from 'src/store/useAppStore'
import { PersonDetailPanel, type PersonDetailTarget } from 'src/components/PersonDetailPanel'
import {
  avgSeasonRating,
  avgSeriesRating,
  episodesWatchedInSeason,
  totalEpisodesWatched,
  totalEpisodeCount,
  getUnlockedModes,
  getEarnedModes,
} from 'src/store/episodeUtils'
import { EpisodeCard, EpisodePanel } from 'src/components/ui/episode-card'
import {
  Calendar, Check, Clock, Film, Tv, Plus, FileText, Trash2, Star,
  ChevronLeft, ChevronRight, ChevronDown, RefreshCw, Tag, X, Send,
} from 'lucide-react'
import { cn, fmtDate, fmtReleaseDate, languageName } from 'src/lib/utils'
import type { Title, Viewing, WatchStatus, Season, Episode, CastMember, CrewMember, EpisodeCrew } from 'src/store/mockData'
import { fetchSeasonDetails, fetchTitleVideos, fetchTitleImages, fetchWatchProviders, TMDB_STILL_BASE, type TitleVideo, type WatchProviders } from 'src/lib/media'
import { upsertEpisodeMetadataInDb, bulkUpsertSeasonCastInDb, bulkUpsertEpisodeCrewInDb } from 'src/lib/db'
import { SendRecommendationPanel } from 'src/components/SendRecommendationPanel'
import { TitleCommentsPanel } from 'src/components/TitleCommentsPanel'
import SpiderWebOverlay from 'src/components/SpiderWebOverlay'
import { SpiderNoirModeSelector } from 'src/components/SpiderNoirModeSelector'
import { transitionSpiderNoir } from 'src/lib/theme'
import { MatrixPillModal } from 'src/components/MatrixPillModal'
import { MatrixDigitalRain } from 'src/components/MatrixDigitalRain'
import { MatrixPillSelector } from 'src/components/MatrixPillSelector'
import { HeroBackdrop } from 'src/components/ui/hero-backdrop'
import { TrailerRow } from 'src/components/ui/trailer-row'
import { WatchProvidersSection } from 'src/components/ui/watch-providers'
import { ReviewBadges, ExternalLinks } from 'src/components/ui/media-badges'
import { SPIDER_NOIR_TMDB_ID, THE_MATRIX_TMDB_ID } from 'src/lib/easterEggThemes'

type SelectorMode = 'normal' | 'bw' | 'color'
const EASTER_EGG_KEY = 'spider_noir_color'
const SPIDER_NOIR_LOGO_NORMAL = 'https://image.tmdb.org/t/p/original/o2D8loRUDlEuOf7BMRgulerNJ6p.png'
const SPIDER_NOIR_LOGO_COLOR  = 'https://image.tmdb.org/t/p/original/ubGbGqmbfWW1B7kNXDI1KrooK7S.png'
const SPIDER_NOIR_LOGO_BW     = 'https://image.tmdb.org/t/p/original/tcS3i7X9XvYcFGEEnjHG147sQrq.png'

function getSpiderNoirActiveMode(title: Title): 'bw' | 'color' | null {
  let lastMode: 'bw' | 'color' | null = null
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) {
      for (const we of ep.watchEvents) {
        if (we.colorMode) lastMode = we.colorMode
      }
      for (const rv of ep.reviews) {
        if (rv.colorMode) lastMode = rv.colorMode
      }
    }
  }
  return lastMode
}

// ─── Shared status options ────────────────────────────────────────────────────

const STATUS_OPTIONS: { value: WatchStatus; label: string }[] = [
  { value: 'watched', label: 'Watched' },
  { value: 'watchlist', label: 'Watchlist' },
  { value: 'watching', label: 'Watching' },
  { value: 'dropped', label: 'Dropped' },
]

// ─── Movie-only: viewing timeline ────────────────────────────────────────────

// Undated viewings are pre-platform (watched before joining) and order as oldest.
function viewingTime(v: Viewing): number {
  return v.date ? new Date(v.date).getTime() : -Infinity
}

function ViewingTimeline({
  viewings,
  onDeleteViewing,
  onLogViewing,
  isSharedView,
}: {
  viewings: Viewing[]
  onDeleteViewing?: (viewingId: string) => void
  onLogViewing?: () => void
  isSharedView?: boolean
}) {
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null)
  if (viewings.length === 0) {
    return (
      <div className="text-center py-6 text-muted-foreground text-sm font-sans flex flex-col items-center gap-3">
        <div>No viewings logged yet</div>
        {!isSharedView && onLogViewing && (
          <button
            onClick={onLogViewing}
            className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber"
          >
            <Plus className="w-3.5 h-3.5" />
            Log first viewing
          </button>
        )}
      </div>
    )
  }

  return (
    <div className="relative pl-6">
      <div className="absolute left-2 top-0 bottom-0 w-px bg-border" />
      <div className="space-y-4">
        {viewings
          .slice()
          // undated (pre-platform) viewings sort as oldest → bottom of the timeline
          .sort((a, b) => viewingTime(b) - viewingTime(a))
          .map((v) => {
            const formattedDate = v.date ? fmtDate(v.date) : 'Before CinemArchive'
            return (
            <div key={v.id} className="relative">
              <div className="absolute -left-[18px] top-1 w-3 h-3 rounded-full bg-amber/70 border-2 border-void" />
              <div className="bg-secondary/50 rounded-lg p-3">
                {pendingDeleteId === v.id ? (
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
                      Remove this viewing?
                    </span>
                    <div className="flex items-center gap-3">
                      <button
                        onClick={() => {
                          onDeleteViewing?.(v.id)
                          setPendingDeleteId(null)
                        }}
                        className="font-mono text-xs transition-opacity hover:opacity-80"
                        style={{ color: 'var(--ember)' }}
                        aria-label={`Delete forever: viewing from ${formattedDate}`}
                      >
                        Delete forever
                      </button>
                      <button
                        onClick={() => setPendingDeleteId(null)}
                        className="font-mono text-xs transition-opacity hover:opacity-80"
                        style={{ color: 'var(--paper-faint)' }}
                        aria-label={`Cancel deleting viewing from ${formattedDate}`}
                      >
                        Cancel
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="flex items-center justify-between mb-1">
                      <span className="font-mono text-xs text-amber">
                        {v.date
                          ? fmtDate(v.date)
                          : <span className="italic">Before CinemArchive</span>}
                      </span>
                      <div className="flex items-center gap-2">
                        {v.rating && (
                          <span className="font-mono text-xs text-amber">★ {v.rating}</span>
                        )}
                        {!isSharedView && onDeleteViewing && (
                          <button
                            onClick={() => setPendingDeleteId(v.id)}
                            style={{ color: 'var(--paper-faint)', opacity: 0.45 }}
                            onMouseEnter={(e) => (e.currentTarget.style.opacity = '1')}
                            onMouseLeave={(e) => (e.currentTarget.style.opacity = '0.45')}
                            aria-label={`Delete viewing from ${formattedDate}`}
                          >
                            <Trash2 className="w-3 h-3" />
                          </button>
                        )}
                      </div>
                    </div>
                    {v.notes && (
                      <p className="text-xs text-muted-foreground font-sans italic leading-relaxed">
                        "{v.notes}"
                      </p>
                    )}
                  </>
                )}
              </div>
            </div>
          )})}
      </div>
    </div>
  )
}

// ─── Details sidebar ──────────────────────────────────────────────────────────

function DetailRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="font-mono text-xs uppercase tracking-wider text-muted-foreground shrink-0">{label}</dt>
      <dd className="font-sans text-sm text-foreground text-right min-w-0 break-words">{value}</dd>
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
  onPersonClick: (person: PersonDetailTarget) => void
  onStudioClick: (studio: string) => void
}

function CastCrewSection({ cast, crew, studios, onPersonClick, onStudioClick }: CastCrewSectionProps) {
  const [expanded, setExpanded] = useState(true)
  const hasCast = cast && cast.length > 0
  const hasCrew = crew && crew.length > 0
  const hasStudios = studios && studios.length > 0
  if (!hasCast && !hasCrew && !hasStudios) return null

  return (
    <div>
      <button
        type="button"
        onClick={() => setExpanded((e) => !e)}
        aria-expanded={expanded}
        className="flex items-center gap-2 mb-4 group focus:outline-none"
      >
        <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim group-hover:text-amber transition-colors">
          Cast &amp; Crew
        </h4>
        <ChevronDown
          className={cn('w-3.5 h-3.5 text-paper-faint transition-transform group-hover:text-amber', expanded ? 'rotate-180' : '')}
        />
      </button>
      {expanded && (
      <div className="space-y-4">
      {hasCast && (
        <div>
          <div
            className="font-mono mb-2"
            style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
          >
            Main Cast
          </div>
          <div className="flex gap-2.5 overflow-x-auto scrollbar-none pb-1 -mx-6 px-6">
            {cast.map((member) => (
              <button
                key={member.tmdbPersonId}
                type="button"
                onClick={() => onPersonClick({ tmdbPersonId: member.tmdbPersonId, name: member.name, profileUrl: member.profileUrl, character: member.character })}
                aria-label={`View details for ${member.name}`}
                className="group shrink-0 w-[110px] overflow-hidden rounded-lg text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 transition-all"
                style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}
              >
                <div className="aspect-[2/3] overflow-hidden">
                  {member.profileUrl ? (
                    <img
                      src={member.profileUrl}
                      alt={member.name}
                      className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
                    />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center" style={{ background: 'var(--card)' }}>
                      <span className="font-mono text-3xl" style={{ color: 'var(--paper-faint)' }}>
                        {member.name.charAt(0).toUpperCase()}
                      </span>
                    </div>
                  )}
                </div>
                <div className="p-2">
                  <div
                    className="font-sans font-semibold line-clamp-1 transition-colors group-hover:text-amber"
                    style={{ fontSize: '12px', color: 'var(--paper)', lineHeight: 1.3 }}
                    title={member.name}
                  >
                    {member.name}
                  </div>
                  <div
                    className="font-mono line-clamp-1 mt-0.5"
                    style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: member.character ? 0.6 : 0 }}
                    title={member.character}
                  >
                    {member.character || ' '}
                  </div>
                  {member.episodeCount != null && (
                    <div
                      className="font-mono mt-0.5"
                      style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: 0.7 }}
                    >
                      {member.episodeCount} ep{member.episodeCount !== 1 ? 's' : ''}
                    </div>
                  )}
                </div>
              </button>
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
                  {members.map((m, i) => (
                    <span key={m.tmdbPersonId}>
                      {i > 0 && ' · '}
                      <button
                        type="button"
                        onClick={() => onPersonClick({ tmdbPersonId: m.tmdbPersonId, name: m.name, profileUrl: m.profileUrl, job: m.job })}
                        aria-label={`View details for ${m.name}`}
                        className="text-paper transition-colors hover:text-amber focus-visible:text-amber focus:outline-none"
                      >
                        {m.name}
                      </button>
                    </span>
                  ))}
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
                {studios!.map((s, i) => (
                  <span key={s}>
                    {i > 0 && ', '}
                    <button
                      type="button"
                      onClick={() => onStudioClick(s)}
                      aria-label={`Browse titles from ${s}`}
                      className="text-paper transition-colors hover:text-amber focus-visible:text-amber focus:outline-none"
                    >
                      {s}
                    </button>
                  </span>
                ))}
              </span>
            </div>
          )}
        </div>
      )}
      </div>
      )}
    </div>
  )
}

// ─── TV: Season selector + episode carousel ───────────────────────────────────

interface TVSeriesSectionProps {
  titleId: string
  seasons: Season[]
  isSharedView: boolean
  isSpiderNoir: boolean
  onPersonClick: (person: PersonDetailTarget) => void
  onColorModeSelected?: (mode: 'bw' | 'color') => void
}

// Episodes in the given seasons with no watch event yet — the set a
// pre-platform bulk mark would touch.
function unwatchedEpisodeCount(seasons: Season[]): number {
  return seasons.reduce(
    (sum, s) => sum + (s.episodes?.filter((e) => e.watchEvents.length === 0).length ?? 0),
    0
  )
}

function TVSeriesSection({ titleId, seasons, isSharedView, isSpiderNoir, onPersonClick, onColorModeSelected }: TVSeriesSectionProps) {
  const markPrePlatformWatched = useAppStore((s) => s.markPrePlatformWatched)
  const [confirmPrePlatform, setConfirmPrePlatform] = useState<'series' | 'season' | null>(null)
  const [selectedSeason, setSelectedSeason] = useState(seasons[0]?.seasonNumber ?? 1)
  const [selectedEpId, setSelectedEpId] = useState<string | null>(null)
  const [castExpanded, setCastExpanded] = useState(true)
  const [canScrollLeft, setCanScrollLeft] = useState(false)
  const [canScrollRight, setCanScrollRight] = useState(false)
  const carouselRef = useRef<HTMLDivElement>(null)
  const graphClickEpIdRef = useRef<string | null>(null)

  const season = seasons.find((s) => s.seasonNumber === selectedSeason)
  const hasEpisodes = (s: Season) => (s.episodes?.length ?? 0) > 0
  const selectedEp = season?.episodes?.find((e) => e.id === selectedEpId) ?? null

  const totalWatched = totalEpisodesWatched(seasons)
  const totalCount = totalEpisodeCount(seasons)
  const seriesAvg = avgSeriesRating(seasons)

  const CARD_WIDTH = 252 // 240px card + 12px gap

  function handleCarouselScroll() {
    const el = carouselRef.current
    if (!el) return
    setCanScrollLeft(el.scrollLeft > 4)
    setCanScrollRight(el.scrollLeft + el.clientWidth < el.scrollWidth - 4)
  }

  function scrollCarousel(dir: 'left' | 'right') {
    carouselRef.current?.scrollBy({ left: dir === 'right' ? CARD_WIDTH : -CARD_WIDTH, behavior: 'smooth' })
  }

  // Reset carousel + selection on season change (honours graph-click ref)
  useEffect(() => {
    const pendingEpId = graphClickEpIdRef.current
    graphClickEpIdRef.current = null
    const el = carouselRef.current
    if (el) el.scrollLeft = 0
    setTimeout(() => {
      setSelectedEpId(pendingEpId)
      setCanScrollLeft(false)
    }, 0)
    const t = setTimeout(() => {
      const el2 = carouselRef.current
      if (el2) setCanScrollRight(el2.scrollWidth > el2.clientWidth + 4)
    }, 50)
    return () => clearTimeout(t)
  }, [selectedSeason])

  function handleSeasonChange(seasonNumber: number) {
    setSelectedSeason(seasonNumber)
    setConfirmPrePlatform(null)
  }

  const seriesUnwatched = unwatchedEpisodeCount(seasons)
  const seasonUnwatched = season ? unwatchedEpisodeCount([season]) : 0

  function renderPrePlatformMark(scope: 'series' | 'season') {
    const count = scope === 'series' ? seriesUnwatched : seasonUnwatched
    if (isSharedView || count === 0) return null
    const label = scope === 'series'
      ? 'Watched entire series before joining'
      : `Watched season ${season?.seasonNumber} before joining`
    return confirmPrePlatform === scope ? (
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
          Mark {count} episode{count === 1 ? '' : 's'} as watched (no date)?
        </span>
        <div className="flex items-center gap-3">
          <button
            onClick={() => {
              markPrePlatformWatched(titleId, scope === 'season' ? season?.seasonNumber : undefined)
              setConfirmPrePlatform(null)
            }}
            className="font-mono text-xs transition-colors text-amber hover:text-amber/80"
            aria-label={`Confirm marking ${count} episodes as watched before joining`}
          >
            Confirm
          </button>
          <button
            onClick={() => setConfirmPrePlatform(null)}
            className="font-mono text-xs transition-opacity hover:opacity-80"
            style={{ color: 'var(--paper-faint)' }}
            aria-label="Cancel marking as watched before joining"
          >
            Cancel
          </button>
        </div>
      </div>
    ) : (
      <button
        onClick={() => setConfirmPrePlatform(scope)}
        className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber"
      >
        <Clock className="w-3 h-3" />
        {label}
      </button>
    )
  }

  return (
    <div className="space-y-5">
      {/* Series-level stats */}
      <div className="grid grid-cols-3 gap-4">
        <div className="rounded-lg px-3 py-1.5 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--paper)', fontVariationSettings: '"opsz" 30' }}>
            {totalWatched}<span className="text-sm font-mono ml-0.5" style={{ color: 'var(--paper-faint)' }}>/{totalCount}</span>
          </div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Episodes</div>
        </div>
        <div className="rounded-lg px-3 py-1.5 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--amber)', fontVariationSettings: '"opsz" 30' }}>
            {seriesAvg !== null ? `★ ${seriesAvg.toFixed(1)}` : '—'}
          </div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Avg Rating</div>
        </div>
        <div className="rounded-lg px-3 py-1.5 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--paper)', fontVariationSettings: '"opsz" 30' }}>{seasons.length}</div>
          <div className="font-mono mt-0.5" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>Seasons</div>
        </div>
      </div>

      {/* Bulk pre-platform mark: series scope */}
      {renderPrePlatformMark('series')}

      {/* Series Graph heatmap */}
      {seasons.some(hasEpisodes) && (
        <div>
          <h4 className="font-mono mb-3" style={{ fontSize: '10px', letterSpacing: '0.18em', textTransform: 'uppercase', color: 'var(--paper-faint)' }}>
            Series Graph
          </h4>
          <SeriesGraph
            seasons={seasons}
            onCellClick={(seasonNumber, episodeNumber) => {
              const targetSeason = seasons.find((s) => s.seasonNumber === seasonNumber)
              const ep = targetSeason?.episodes?.find((e) => e.episodeNumber === episodeNumber)
              if (ep) graphClickEpIdRef.current = ep.id
              handleSeasonChange(seasonNumber)
            }}
          />
        </div>
      )}

      {/* Smart season selector */}
      {seasons.length <= 3 ? (
        <div className="flex gap-2 overflow-x-auto scrollbar-none">
          {seasons.map((s) => {
            const watched = episodesWatchedInSeason(s)
            const pct = s.episodeCount > 0 ? Math.round((watched / s.episodeCount) * 100) : 0
            const seasonAvg = avgSeasonRating(s)
            return (
              <button
                key={s.seasonNumber}
                onClick={() => handleSeasonChange(s.seasonNumber)}
                aria-label={`Season ${s.seasonNumber}`}
                aria-current={selectedSeason === s.seasonNumber ? 'true' : undefined}
                className={cn(
                  'shrink-0 px-3.5 py-2.5 rounded-lg text-left transition-all border',
                  selectedSeason === s.seasonNumber
                    ? 'border-amber/40 bg-amber/10'
                    : 'border-transparent hover:border-[var(--line)] hover:bg-[var(--wash)]'
                )}
              >
                <div className="font-mono" style={{ fontSize: '13px', color: selectedSeason === s.seasonNumber ? 'var(--amber)' : 'var(--paper-dim)' }}>
                  S{s.seasonNumber}
                </div>
                <div className="font-mono" style={{ fontSize: '11px', color: 'var(--paper-faint)' }}>
                  {pct}%{seasonAvg !== null ? ` · ★${seasonAvg.toFixed(1)}` : ''}
                </div>
              </button>
            )
          })}
        </div>
      ) : (
        <select
          value={selectedSeason}
          onChange={(e) => handleSeasonChange(parseInt(e.target.value, 10))}
          aria-label="Select season"
          className="font-mono text-sm rounded-lg px-3 py-2 bg-secondary border border-amber/30 focus:outline-none focus:border-amber/60"
          style={{ color: 'var(--amber)' }}
        >
          {seasons.map((s) => {
            const watched = episodesWatchedInSeason(s)
            const pct = s.episodeCount > 0 ? Math.round((watched / s.episodeCount) * 100) : 0
            const seasonAvg = avgSeasonRating(s)
            return (
              <option key={s.seasonNumber} value={s.seasonNumber}>
                {`Season ${s.seasonNumber} · ${pct}%${seasonAvg !== null ? ` · ★${seasonAvg.toFixed(1)}` : ''}`}
              </option>
            )
          })}
        </select>
      )}

      {/* Bulk pre-platform mark: selected-season scope */}
      {renderPrePlatformMark('season')}

      {/* Season cast */}
      {season?.cast && season.cast.length > 0 && (
        <div className="pl-3 border-l-2" style={{ borderColor: 'var(--line)' }}>
          <button
            type="button"
            onClick={() => setCastExpanded((e) => !e)}
            aria-expanded={castExpanded}
            className="flex items-center gap-1.5 mb-2 group focus:outline-none"
          >
            <span className="font-mono group-hover:text-amber transition-colors" style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}>
              Season {season.seasonNumber} Cast
            </span>
            <ChevronDown
              className={cn('w-3 h-3 text-paper-faint transition-transform group-hover:text-amber', castExpanded ? 'rotate-180' : '')}
            />
          </button>
          {castExpanded && (
          <div className="flex gap-2.5 overflow-x-auto scrollbar-none pb-1 -mx-6 px-6">
            {season.cast.map((member) => (
              <button
                key={member.tmdbPersonId}
                type="button"
                onClick={() => onPersonClick({ tmdbPersonId: member.tmdbPersonId, name: member.name, profileUrl: member.profileUrl, character: member.character })}
                aria-label={`View details for ${member.name}`}
                className="group shrink-0 w-[110px] overflow-hidden rounded-lg text-left focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60 transition-all"
                style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}
              >
                <div className="aspect-[2/3] overflow-hidden">
                  {member.profileUrl ? (
                    <img src={member.profileUrl} alt={member.name} className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105" />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center" style={{ background: 'var(--card)' }}>
                      <span className="font-mono text-3xl" style={{ color: 'var(--paper-faint)' }}>{member.name.charAt(0).toUpperCase()}</span>
                    </div>
                  )}
                </div>
                <div className="p-2">
                  <div className="font-sans font-semibold line-clamp-1 transition-colors group-hover:text-amber" style={{ fontSize: '12px', color: 'var(--paper)', lineHeight: 1.3 }} title={member.name}>{member.name}</div>
                  <div className="font-mono line-clamp-1 mt-0.5" style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: member.character ? 0.6 : 0 }} title={member.character}>{member.character || ' '}</div>
                  {member.episodeCount != null && (
                    <div className="font-mono mt-0.5" style={{ fontSize: '10px', color: 'var(--paper-faint)', lineHeight: 1.3, opacity: 0.7 }}>
                      {member.episodeCount} ep{member.episodeCount !== 1 ? 's' : ''}
                    </div>
                  )}
                </div>
              </button>
            ))}
          </div>
          )}
        </div>
      )}

      {/* Episode carousel */}
      {season && hasEpisodes(season) && (
        <div>
          <div className="relative">
            {/* Left arrow */}
            {canScrollLeft && (
              <button
                type="button"
                onClick={() => scrollCarousel('left')}
                aria-label="Scroll episodes left"
                className="absolute left-0 top-1/2 -translate-y-1/2 z-10 -translate-x-2 rounded-full p-1.5 transition-opacity hover:opacity-100 opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                style={{ background: 'var(--card)', border: '1px solid var(--line)' }}
              >
                <ChevronLeft className="w-4 h-4" style={{ color: 'var(--paper)' }} />
              </button>
            )}

            {/* Carousel */}
            <div
              ref={carouselRef}
              onScroll={handleCarouselScroll}
              className="flex gap-3 overflow-x-auto scrollbar-none pb-1"
            >
              {season.episodes!.map((ep) => (
                <EpisodeCard
                  key={ep.id}
                  episode={ep}
                  season={season}
                  titleId={titleId}
                  isSelected={selectedEpId === ep.id}
                  onSelect={() => setSelectedEpId(selectedEpId === ep.id ? null : ep.id)}
                  isSharedView={isSharedView}
                  isSpiderNoir={isSpiderNoir}
                />
              ))}
            </div>

            {/* Right arrow */}
            {canScrollRight && (
              <button
                type="button"
                onClick={() => scrollCarousel('right')}
                aria-label="Scroll episodes right"
                className="absolute right-0 top-1/2 -translate-y-1/2 z-10 translate-x-2 rounded-full p-1.5 transition-opacity hover:opacity-100 opacity-80 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                style={{ background: 'var(--card)', border: '1px solid var(--line)' }}
              >
                <ChevronRight className="w-4 h-4" style={{ color: 'var(--paper)' }} />
              </button>
            )}
          </div>

          {/* Inline logging panel — renders below the carousel when a card is selected */}
          {selectedEp && (
            <div className="mt-3">
              <EpisodePanel
                episode={selectedEp}
                season={season}
                titleId={titleId}
                isSharedView={isSharedView}
                isSpiderNoir={isSpiderNoir}
                onColorModeSelected={onColorModeSelected}
              />
            </div>
          )}
        </div>
      )}

      {/* Fallback: coarse progress bar when season has no episode-level data */}
      {season && !hasEpisodes(season) && (() => {
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
      })()}
    </div>
  )
}

// ─── Inline tag editor ───────────────────────────────────────────────────────

function DrawerTagEditor({
  tags,
  isSharedView,
  onChange,
}: {
  tags: string[]
  isSharedView: boolean
  onChange: (tags: string[]) => void
}) {
  const [input, setInput] = useState('')
  const [editing, setEditing] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  function commit() {
    const trimmed = input.trim().replace(/,+$/, '')
    if (trimmed && !tags.includes(trimmed)) {
      onChange([...tags, trimmed])
    }
    setInput('')
  }

  function handleKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      commit()
    } else if (e.key === 'Backspace' && input === '' && tags.length > 0) {
      onChange(tags.slice(0, -1))
    } else if (e.key === 'Escape') {
      commit()
      setEditing(false)
    }
  }

  function removeTag(tag: string) {
    onChange(tags.filter((t) => t !== tag))
  }

  if (isSharedView) {
    if (tags.length === 0) return null
    return (
      <div className="flex flex-wrap gap-1.5">
        {tags.map((t) => (
          <MetaBadge key={t} className="border-amber/30 text-amber/80">
            <Tag className="w-2.5 h-2.5 mr-1 inline" />{t}
          </MetaBadge>
        ))}
      </div>
    )
  }

  return (
    <div>
      <div className="flex items-center gap-2 mb-2">
        <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground">Tags</h4>
        {!editing && (
          <button
            onClick={() => { setEditing(true); setTimeout(() => inputRef.current?.focus(), 0) }}
            className="text-xs font-mono text-amber/50 hover:text-amber transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm px-1"
          >
            + add
          </button>
        )}
      </div>
      <div className="flex flex-wrap gap-1.5">
        {tags.map((t) => (
          <span
            key={t}
            className="flex items-center gap-1 px-2 py-0.5 rounded-full bg-amber/10 border border-amber/20 font-mono text-xs text-amber"
          >
            {t}
            <button
              type="button"
              onClick={() => removeTag(t)}
              className="hover:text-amber-bright transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60 rounded-full"
              aria-label={`Remove tag ${t}`}
              title={`Remove tag ${t}`}
            >
              <X className="w-2.5 h-2.5" />
            </button>
          </span>
        ))}
        {editing && (
          <input
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKey}
            onBlur={() => { commit(); setEditing(false) }}
            placeholder="tag name…"
            aria-label="New tag name"
            className="px-2 py-0.5 rounded-full bg-secondary border border-amber/30 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-amber/60 w-28"
          />
        )}
        {!editing && tags.length === 0 && (
          <button
            onClick={() => { setEditing(true); setTimeout(() => inputRef.current?.focus(), 0) }}
            className="flex items-center gap-1 px-2 py-0.5 rounded-full border border-dashed border-amber/20 font-mono text-xs text-muted-foreground hover:border-amber/40 hover:text-amber/70 transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
          >
            <Tag className="w-2.5 h-2.5" /> add tag
          </button>
        )}
      </div>
    </div>
  )
}

// ─── Main drawer ─────────────────────────────────────────────────────────────

export function TitleDetailDrawer() {
  // ⚡ Bolt: Prevent unnecessary re-renders by using useShallow
  const { isDetailDrawerOpen, closeDetailDrawer, updateTitle, removeTitle, removeViewing, openRefreshMetadata, isSharedView, viewerContext } = useAppStore(
    useShallow((s) => ({
      isDetailDrawerOpen: s.isDetailDrawerOpen,
      closeDetailDrawer: s.closeDetailDrawer,
      updateTitle: s.updateTitle,
      removeTitle: s.removeTitle,
      removeViewing: s.removeViewing,
      openRefreshMetadata: s.openRefreshMetadata,
      isSharedView: s.isSharedView,
      viewerContext: s.viewerContext,
    }))
  )
  const browseByStudio = useAppStore((s) => s.browseByStudio)
  const title = useSelectedTitle()
  const user = useAppStore((s) => s.user)

  const isSpiderNoir = title?.tmdbId === SPIDER_NOIR_TMDB_ID
  const isMatrix = title?.tmdbId === THE_MATRIX_TMDB_ID

  const [showMatrixModal, setShowMatrixModal] = useState(false)
  const [showMatrixRain, setShowMatrixRain] = useState(false)

  const [sendPanelOpen, setSendPanelOpen] = useState(false)

  const pinnedModes = useAppStore((s) => s.pinnedModes)
  const setPinnedMode = useAppStore((s) => s.setPinnedMode)
  const unlockTheme = useAppStore((s) => s.unlockTheme)

  const unlockedModes = useMemo(
    () => (isSpiderNoir && title ? getUnlockedModes(title) : new Set<'bw' | 'color'>()),
    [isSpiderNoir, title]
  )
  const earnedModes = useMemo(
    () => (isSpiderNoir && title ? getEarnedModes(title) : new Set<'bw' | 'color'>()),
    [isSpiderNoir, title]
  )

  // Global theme easter eggs: earning the Spider-Noir black & white mode, or
  // taking the Matrix red pill, unlocks the matching app-wide theme (Settings
  // → Appearance). Keyed on the "earned"/"rain shown" signals so it fires
  // regardless of whether the title was already watched before this session.
  useEffect(() => {
    if (earnedModes.has('bw')) unlockTheme('noir')
  }, [earnedModes, unlockTheme])

  useEffect(() => {
    if (showMatrixRain) unlockTheme('matrix')
  }, [showMatrixRain, unlockTheme])

  const prevNoirModeRef = useRef<'bw' | 'color' | null | undefined>(undefined)
  const [noirAnim, setNoirAnim] = useState<'bw' | 'color' | null>(null)
  const noirAnimTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [manualMode, setManualMode] = useState<SelectorMode>('normal')

  // closeDetailDrawer only sets isDetailDrawerOpen=false, not selectedTitleId=null,
  // so title and isSpiderNoir remain valid here even when the drawer is closed.
  const pinnedModeRaw = (title ? (pinnedModes[`${title.id}:${EASTER_EGG_KEY}`] ?? null) : null) as 'bw' | 'color' | null

  // Seed manualMode from pinned → last watch event → normal when the drawer opens.
  // setTimeout(0) defers the setState out of the effect body to satisfy react-hooks/set-state-in-effect.
  useEffect(() => {
    if (!isSpiderNoir || !title || !isDetailDrawerOpen) return
    const derived = getSpiderNoirActiveMode(title)
    const seeded = pinnedModeRaw ?? derived ?? 'normal'
    const t = setTimeout(() => setManualMode(seeded as SelectorMode), 0)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [title?.id, isDetailDrawerOpen])

  // While drawer is open: use manual selection. When closed: use pinned (if any).
  const effectiveNoirMode: 'bw' | 'color' | null = isSpiderNoir
    ? (isDetailDrawerOpen ? (manualMode !== 'normal' ? (manualMode as 'bw' | 'color') : null) : pinnedModeRaw)
    : null

  useEffect(() => {
    const ALL = ['spider-noir-bw', 'spider-noir-color', 'spider-noir-bw-enter', 'spider-noir-color-enter'] as const
    const prevMode = prevNoirModeRef.current

    // Skip the transition on the very first render (prev === undefined) —
    // the drawer isn't open yet so there's nothing to animate between.
    // Also skip if the mode hasn't actually changed.
    const isVisualChange = prevMode !== undefined && prevMode !== effectiveNoirMode

    function applyClasses() {
      document.body.classList.remove(...ALL)
      if (effectiveNoirMode) {
        document.body.classList.add(effectiveNoirMode === 'bw' ? 'spider-noir-bw' : 'spider-noir-color')
        document.body.classList.add(effectiveNoirMode === 'bw' ? 'spider-noir-bw-enter' : 'spider-noir-color-enter')
      }
    }

    if (isVisualChange) {
      // The View Transition must capture the current (pre-change) DOM state
      // before applyClasses runs. We pass applyClasses as the commit so the
      // API snapshots before/after correctly — this works for both the enter
      // (normal → noir) and exit (noir → normal) directions.
      transitionSpiderNoir(applyClasses)
      if (noirAnimTimerRef.current) clearTimeout(noirAnimTimerRef.current)
      if (effectiveNoirMode) {
        // Entering or switching: cast the web overlay on top of the VT reveal.
        // Deferred so it runs as an async callback (not synchronously in the
        // effect body) — avoids the react-hooks/set-state-in-effect lint rule.
        noirAnimTimerRef.current = setTimeout(() => {
          setNoirAnim(effectiveNoirMode)
          noirAnimTimerRef.current = setTimeout(() => {
            setNoirAnim(null)
            document.body.classList.remove('spider-noir-bw-enter', 'spider-noir-color-enter')
          }, 2100)
        }, 0)
      } else {
        // Exiting: dismiss any lingering web overlay so it doesn't hang over
        // the returned normal view.
        noirAnimTimerRef.current = setTimeout(() => setNoirAnim(null), 0)
      }
    } else {
      applyClasses()
    }

    prevNoirModeRef.current = effectiveNoirMode

    // Do NOT remove body classes in the cleanup — the cleanup fires before the
    // next effect run, which would cause the exit View Transition to capture
    // no-noir → no-noir (invisible). Class removal is handled by applyClasses
    // in the next run. The timer must still be cleared on unmount.
    return () => {
      if (noirAnimTimerRef.current) clearTimeout(noirAnimTimerRef.current)
    }
  }, [effectiveNoirMode])

  const [showLogForm, setShowLogForm] = useState(false)
  const [logDate, setLogDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [logPrePlatform, setLogPrePlatform] = useState(false)
  const [logRating, setLogRating] = useState(0)
  const [logNotes, setLogNotes] = useState('')
  const [showMovieSaved, setShowMovieSaved] = useState(false)
  const [pendingDeleteTitle, setPendingDeleteTitle] = useState(false)
  const [posterLightboxOpen, setPosterLightboxOpen] = useState(false)
  const [activePerson, setActivePerson] = useState<PersonDetailTarget | null>(null)
  const [videos, setVideos] = useState<TitleVideo[]>([])
  const [watchProviders, setWatchProviders] = useState<WatchProviders | null>(null)
  const [logoUrl, setLogoUrl] = useState<string | null>(null)
  const [heroBackdropUrl, setHeroBackdropUrl] = useState<string | null>(null)

  const displayLogoUrl = isSpiderNoir
    ? (effectiveNoirMode === 'color' ? SPIDER_NOIR_LOGO_COLOR : effectiveNoirMode === 'bw' ? SPIDER_NOIR_LOGO_BW : SPIDER_NOIR_LOGO_NORMAL)
    : logoUrl

  useEffect(() => {
    if (!isDetailDrawerOpen || !title?.tmdbId) {
      // Deferred to satisfy react-hooks/set-state-in-effect (same pattern as manualMode seeding above).
      const t = setTimeout(() => setVideos([]), 0)
      return () => clearTimeout(t)
    }
    let cancelled = false
    fetchTitleVideos(title.tmdbId, title.type).then((v) => {
      if (!cancelled) setVideos(v)
    })
    return () => { cancelled = true }
  }, [isDetailDrawerOpen, title?.tmdbId, title?.type])

  useEffect(() => {
    if (!isDetailDrawerOpen || !title?.tmdbId) {
      const t = setTimeout(() => setWatchProviders(null), 0)
      return () => clearTimeout(t)
    }
    let cancelled = false
    fetchWatchProviders(title.tmdbId, title.type).then((wp) => {
      if (!cancelled) setWatchProviders(wp)
    })
    return () => { cancelled = true }
  }, [isDetailDrawerOpen, title?.tmdbId, title?.type])

  // Images: best logo + best backdrop at original resolution, fetched together.
  // No backdropUrl guard — we fetch for any title with a tmdbId so titles
  // without a stored backdrop can still get a cinematic hero + logo.
  useEffect(() => {
    if (!isDetailDrawerOpen || !title?.tmdbId) {
      const t = setTimeout(() => { setLogoUrl(null); setHeroBackdropUrl(null) }, 0)
      return () => clearTimeout(t)
    }
    let cancelled = false
    fetchTitleImages(title.tmdbId, title.type).then(({ logoUrl: logo, backdropUrl: backdrop }) => {
      if (!cancelled) {
        setLogoUrl(logo)
        setHeroBackdropUrl(backdrop)
      }
    })
    return () => { cancelled = true }
  }, [isDetailDrawerOpen, title?.tmdbId, title?.type])

  function onClose() {
    setPendingDeleteTitle(false)
    setPosterLightboxOpen(false)
    setActivePerson(null)
    closeDetailDrawer()
  }

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
      (s.episodes && s.episodes.length > 0 && s.episodes.every((ep) => !ep.episodeName)) ||
      // Case (c): episodes exist but season cast was never fetched
      (s.episodes && s.episodes.length > 0 && (!s.cast || s.cast.length === 0))
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
          if (allSeasonCast.length > 0) {
            bulkUpsertSeasonCastInDb(snapshotUser.id, snapshotTitle.id, allSeasonCast).catch((e) =>
              console.error('Season cast backfill DB write failed:', e)
            )
          }
          if (allEpisodeCrew.length > 0) {
            bulkUpsertEpisodeCrewInDb(snapshotUser.id, snapshotTitle.id, allEpisodeCrew).catch((e) =>
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
    if (!title || (!logPrePlatform && !logDate)) return
    const viewing: Viewing = {
      id: crypto.randomUUID(),
      titleId: title.id,
      date: logPrePlatform ? undefined : logDate,
      rating: logRating > 0 ? logRating : undefined,
      notes: logNotes || undefined,
    }
    updateTitle(title.id, {
      viewings: [...title.viewings, viewing],
      status: 'watched',
      rating: logRating > 0 ? logRating : title.rating,
    })
    setShowMovieSaved(true)
    setTimeout(() => {
      setShowMovieSaved(false)
      setShowLogForm(false)
      setLogDate(new Date().toISOString().slice(0, 10))
      setLogPrePlatform(false)
      setLogRating(0)
      setLogNotes('')
    }, 1500)
  }

  function handleDelete() {
    if (!title) return
    closeDetailDrawer()
    removeTitle(title.id)
  }

  function handleModeSelect(mode: SelectorMode) {
    setManualMode(mode)
  }

  function handleTogglePin(mode: 'bw' | 'color') {
    if (!title) return
    const newVariant = pinnedModeRaw === mode ? null : mode
    setPinnedMode(title.id, EASTER_EGG_KEY, newVariant)
  }

  function handleSaveViewing() {
    if (isMatrix) {
      setShowMatrixModal(true)
    } else {
      logViewing()
    }
  }

  function handleMatrixBlue() {
    setShowMatrixModal(false)
    logViewing()
  }

  function handleMatrixRed() {
    setShowMatrixModal(false)
    logViewing()
    setShowMatrixRain(true)
  }

  return (
    <>
    {noirAnim && <SpiderWebOverlay mode={noirAnim} />}
    {showMatrixRain && <MatrixDigitalRain onDone={() => setShowMatrixRain(false)} />}
    <MatrixPillModal open={showMatrixModal} onBlue={handleMatrixBlue} onRed={handleMatrixRed} />
    <CinemaModal
      open={isDetailDrawerOpen}
      onClose={onClose}
      maxWidth="sm:max-w-2xl"
      title={title.title}
      description={title.synopsis ?? `Details and viewing history for ${title.title}.`}
      expanded
    >
      {/* Poster lightbox — rendered above the dialog content via z-[215] */}
      {posterLightboxOpen && title.posterUrl && (
        <PosterLightbox
          src={title.posterUrl}
          alt={title.title}
          onClose={() => setPosterLightboxOpen(false)}
        />
      )}

      {/* Person detail panel */}
      {activePerson && (
        <PersonDetailPanel
          person={activePerson}
          onClose={() => setActivePerson(null)}
        />
      )}

      {/* Send to a friend */}
      {sendPanelOpen && (
        <SendRecommendationPanel
          title={title}
          onClose={() => setSendPanelOpen(false)}
        />
      )}

      <div className="overflow-y-auto flex-1 scrollbar-thin pb-16 sm:pb-0">
        {/* Hero: cinematic backdrop (stored or fetched) or blurred-poster fallback */}
        {(title.backdropUrl || heroBackdropUrl) ? (
          <HeroBackdrop title={title} backdropOverride={heroBackdropUrl ?? undefined} onPosterClick={() => setPosterLightboxOpen(true)}>
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
            {displayLogoUrl ? (
              <img
                src={displayLogoUrl}
                alt={title.title}
                className="object-contain object-left max-h-20 sm:max-h-28 max-w-[90%] drop-shadow-lg"
              />
            ) : (
              <CardTitle className="text-xl leading-tight">{title.title}</CardTitle>
            )}
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span className="font-mono text-sm text-amber">{title.year}</span>
              {title.director && title.type === 'movie' && (
                <span className="text-xs text-muted-foreground font-sans">dir. {title.director}</span>
              )}
              {title.runtime && title.type === 'movie' && (
                <div className="flex items-center gap-1 text-xs text-muted-foreground">
                  <Clock className="w-3 h-3" />
                  <span className="font-mono">{title.runtime}m</span>
                </div>
              )}
            </div>
            {isSpiderNoir && (
              <SpiderNoirModeSelector
                unlockedModes={unlockedModes}
                earnedModes={earnedModes}
                selected={manualMode}
                pinned={pinnedModeRaw}
                onSelect={handleModeSelect}
                onTogglePin={handleTogglePin}
              />
            )}
            {isMatrix && (title.viewings.length > 0 || title.status === 'watched') && (
              <MatrixPillSelector onRedPill={() => setShowMatrixRain(true)} />
            )}
            <StarRating
              value={title.rating ?? 0}
              size="sm"
              onChange={isSharedView ? undefined : (rating) => updateTitle(title.id, { rating })}
            />
          </HeroBackdrop>
        ) : (
          <div className="relative overflow-hidden shrink-0">
            {(title.backdropUrl ?? title.posterUrl) && (
              <div
                className="absolute inset-0"
                style={{
                  backgroundImage: `url(${title.backdropUrl ?? title.posterUrl})`,
                  backgroundSize: 'cover',
                  backgroundPosition: 'center top',
                  filter: 'blur(20px)',
                  transform: 'scale(1.3)',
                  opacity: 0.18,
                }}
              />
            )}
            <div className="absolute inset-0" style={{ background: 'linear-gradient(to bottom, hsl(var(--card) / 0.55) 0%, hsl(var(--card) / 0.92) 45%, hsl(var(--card)) 65%)' }} />
            <div className="relative z-10 flex gap-5 px-6 pt-10 pb-6">
              <div className="w-28 sm:w-36 shrink-0">
                {title.posterUrl ? (
                  <button
                    type="button"
                    onClick={() => setPosterLightboxOpen(true)}
                    aria-label={`View full poster for ${title.title}`}
                    className="block w-full rounded-lg overflow-hidden transition-opacity hover:opacity-90 focus:outline-none focus-visible:ring-2 focus-visible:ring-amber/60"
                    title="View full poster"
                  >
                    <DynamicPoster title={title} />
                  </button>
                ) : (
                  <DynamicPoster title={title} />
                )}
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
                    <span className="text-xs text-muted-foreground font-sans">dir. {title.director}</span>
                  )}
                  {title.runtime && title.type === 'movie' && (
                    <div className="flex items-center gap-1 text-xs text-muted-foreground">
                      <Clock className="w-3 h-3" />
                      <span className="font-mono">{title.runtime}m</span>
                    </div>
                  )}
                </div>
                {isSpiderNoir && (
                  <SpiderNoirModeSelector
                    unlockedModes={unlockedModes}
                    earnedModes={earnedModes}
                    selected={manualMode}
                    pinned={pinnedModeRaw}
                    onSelect={handleModeSelect}
                    onTogglePin={handleTogglePin}
                  />
                )}
                {isMatrix && (title.viewings.length > 0 || title.status === 'watched') && (
                  <MatrixPillSelector onRedPill={() => setShowMatrixRain(true)} />
                )}
                <StarRating
                  value={title.rating ?? 0}
                  size="sm"
                  onChange={isSharedView ? undefined : (rating) => updateTitle(title.id, { rating })}
                />
              </div>
            </div>
          </div>
        )}

        {/* Scrollable body */}
        <div className="px-6 pb-6 space-y-5">
          {/* Upper info — two columns on desktop so the right side is used */}
          <div className="lg:grid lg:grid-cols-[1fr_300px] lg:gap-8 lg:items-start space-y-5 lg:space-y-0">
            {/* Left column — status, synopsis, genres */}
            <div className="space-y-5 min-w-0">
              {/* Status */}
              <div>
                <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim mb-4">Status</h4>
                <div className="relative inline-block">
                  <select
                    value={title.status}
                    onChange={isSharedView ? undefined : (e) => updateTitle(title.id, { status: e.target.value as WatchStatus })}
                    disabled={isSharedView}
                    aria-label="Title status"
                    className={cn(
                      'appearance-none font-sans text-sm rounded-lg pl-3 pr-9 py-2 bg-secondary border border-amber/30 focus:outline-none focus:border-amber/60',
                      isSharedView && 'opacity-60 cursor-default'
                    )}
                    style={{ color: 'var(--amber)' }}
                  >
                    {STATUS_OPTIONS.map((opt) => (
                      <option key={opt.value} value={opt.value}>
                        {opt.label}
                      </option>
                    ))}
                  </select>
                  <ChevronDown
                    className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4"
                    style={{ color: 'var(--amber)' }}
                  />
                </div>
              </div>

              {/* Synopsis */}
              {title.synopsis && (
                <div>
                  <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim mb-4">Synopsis</h4>
                  <BodyText className="text-sm leading-relaxed max-w-2xl">{title.synopsis}</BodyText>
                </div>
              )}

              {/* Genres */}
              {title.genres.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  {title.genres.map((g) => (
                    <MetaBadge key={g} className="border-amber/20 text-amber/70">{g}</MetaBadge>
                  ))}
                </div>
              )}
            </div>

            {/* Right column — details + critical reception */}
            <div className="space-y-5">
              <div>
                <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim mb-4">Details</h4>
                <dl className="space-y-2 rounded-lg bg-secondary/30 p-3">
                  {title.network && <DetailRow label="Network" value={title.network} />}
                  {title.type === 'movie' && title.director && <DetailRow label="Director" value={title.director} />}
                  {title.runtime ? <DetailRow label="Runtime" value={`${title.runtime} min`} /> : null}
                  {title.contentRating && <DetailRow label="Rated" value={title.contentRating} />}
                  {title.originalLanguage && <DetailRow label="Language" value={languageName(title.originalLanguage)} />}
                  {title.releaseDate && (
                    <DetailRow
                      label={title.releaseDate > new Date().toISOString().slice(0, 10) ? 'Releases' : 'Released'}
                      value={fmtReleaseDate(title.releaseDate)}
                    />
                  )}
                  {title.studios && title.studios.length > 0 && (
                    <DetailRow label="Studio" value={title.studios.join(', ')} />
                  )}
                  {title.collectionName && (
                    <DetailRow label="Franchise" value={title.collectionName.replace(/\s+Collection$/i, '')} />
                  )}
                  <DetailRow label="Added" value={fmtDate(title.addedAt)} />
                </dl>
              </div>

              {/* Critical Reception */}
              {(title.imdbRating || title.rtScore || title.metacriticScore) && (
                <div>
                  <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim mb-4">
                    Critical Reception
                  </h4>
                  <ReviewBadges imdb={title.imdbRating} rt={title.rtScore} meta={title.metacriticScore} />
                </div>
              )}

              {/* External links */}
              <ExternalLinks media={title} />

              {/* Where to watch */}
              <WatchProvidersSection
                providers={watchProviders}
                customUrl={title.customWatchUrl}
                isSharedView={isSharedView}
                onSaveCustomUrl={(url) => updateTitle(title.id, { customWatchUrl: url })}
              />
            </div>
          </div>

          {/* Tags — editable when not in shared view */}
          {(!isSharedView || title.tags.length > 0) && (
            <DrawerTagEditor
              tags={title.tags}
              isSharedView={isSharedView}
              onChange={(tags) => updateTitle(title.id, { tags })}
            />
          )}

          {/* Cast & Crew */}
          {(title.cast?.length || title.crew?.length || title.studios?.length) ? (
            <CastCrewSection
              cast={title.cast}
              crew={title.crew}
              studios={title.studios}
              onPersonClick={setActivePerson}
              onStudioClick={browseByStudio}
            />
          ) : null}

          {/* ── TV Series section ───────────────────────────────────── */}
          {title.type === 'tv' && title.seasons && title.seasons.length > 0 && (
            <div>
              <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim mb-4">
                Season &amp; Episodes
              </h4>
              <TVSeriesSection
                titleId={title.id}
                seasons={title.seasons}
                isSharedView={isSharedView}
                isSpiderNoir={title.tmdbId === SPIDER_NOIR_TMDB_ID}
                onPersonClick={setActivePerson}
                onColorModeSelected={handleModeSelect}
              />
            </div>
          )}

          {/* ── Movie section (and TV without seasons) ─────────────── */}
          {title.type === 'movie' && (
            <>
              {/* Viewing Stats */}
              <div className="grid grid-cols-2 gap-4">
                <div className="bg-secondary/40 rounded-lg px-3 py-1.5 text-center">
                  <StatNumber className="text-xl">{title.viewings.length}</StatNumber>
                  <div className="mt-0.5">
                    <StatLabel>Viewings</StatLabel>
                  </div>
                </div>
                <div className="bg-secondary/40 rounded-lg px-3 py-1.5 text-center">
                  <div className="flex items-center justify-center gap-1 mb-0.5">
                    <Calendar className="w-3.5 h-3.5 text-muted-foreground" />
                    <StatNumber className="text-base leading-tight">
                      {(() => {
                        if (title.viewings.length === 0) return '—'
                        const latest = title.viewings.slice().sort((a, b) => viewingTime(b) - viewingTime(a))[0]
                        return latest.date ? fmtDate(latest.date) : 'Before joining'
                      })()}
                    </StatNumber>
                  </div>
                  <StatLabel>Last Seen</StatLabel>
                </div>
              </div>

              {/* Viewing History */}
              <div>
                <div className="flex items-center justify-between mb-3">
                  <h4 className="font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim">
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
                  <div className="border-t pt-3 mb-4 space-y-3" style={{ borderColor: 'var(--line)' }}>
                    <div>
                      <label htmlFor="viewing-date" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
                        <Calendar className="inline w-3 h-3 mr-1" />
                        Date Watched
                      </label>
                      {!logPrePlatform && (
                        <Input
                          id="viewing-date"
                          type="date"
                          value={logDate}
                          onChange={(e) => setLogDate(e.target.value)}
                          className="bg-secondary/50 border-border font-mono"
                        />
                      )}
                      <label className="flex items-center gap-2 cursor-pointer mt-2">
                        <input
                          type="checkbox"
                          checked={logPrePlatform}
                          onChange={(e) => setLogPrePlatform(e.target.checked)}
                          className="accent-amber w-3.5 h-3.5"
                        />
                        <span className="font-sans text-xs text-muted-foreground">
                          Watched before joining CinemArchive (no date)
                        </span>
                      </label>
                    </div>
                    <div>
                      <p className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                        Rating
                      </p>
                      <StarRating value={logRating} onChange={setLogRating} size="md" />
                    </div>
                    <div>
                      <label htmlFor="viewing-notes" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
                        <FileText className="inline w-3 h-3 mr-1" />
                        Notes
                      </label>
                      <textarea
                        id="viewing-notes"
                        value={logNotes}
                        onChange={(e) => setLogNotes(e.target.value)}
                        placeholder="Your thoughts…"
                        rows={2}
                        className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-2 focus:ring-amber/30"
                      />
                    </div>
                    <div className="flex gap-2">
                      <Button
                        className="flex-1 bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
                        onClick={handleSaveViewing}
                        disabled={showMovieSaved}
                      >
                        {showMovieSaved ? (
                          <>
                            <Check className="w-4 h-4 mr-2" />
                            Saved
                          </>
                        ) : (
                          <>
                            <Star className="w-4 h-4 mr-2" />
                            Save Viewing
                          </>
                        )}
                      </Button>
                      <Button variant="outline" onClick={() => setShowLogForm(false)}>
                        Cancel
                      </Button>
                    </div>
                  </div>
                )}

                <ViewingTimeline
                  viewings={title.viewings}
                  isSharedView={isSharedView}
                  onDeleteViewing={(viewingId) => removeViewing(title.id, viewingId)}
                  onLogViewing={() => setShowLogForm(true)}
                />
              </div>
            </>
          )}

          {/* Trailers */}
          <TrailerRow videos={videos} />

          {/* Comments & reactions — friends-only, hidden for anonymous share-link visitors */}
          {viewerContext.kind !== 'shared-link' && <TitleCommentsPanel titleId={title.id} />}

          {/* Maintenance actions */}
          {!isSharedView && (
            <div
              className="pt-2 border-t"
              style={{ borderColor: 'var(--line)' }}
            >
              {pendingDeleteTitle ? (
                <div className="flex items-center justify-between">
                  <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
                    Remove from library forever?
                  </span>
                  <div className="flex items-center gap-3">
                    <button
                      onClick={handleDelete}
                      className="font-mono text-xs transition-opacity hover:opacity-80"
                      style={{ color: 'var(--ember)' }}
                      aria-label="Delete forever: title from library"
                    >
                      Delete forever
                    </button>
                    <button
                      onClick={() => setPendingDeleteTitle(false)}
                      className="font-mono text-xs transition-opacity hover:opacity-80"
                      style={{ color: 'var(--paper-faint)' }}
                      aria-label="Cancel deleting title from library"
                    >
                      Cancel
                    </button>
                  </div>
                </div>
              ) : (
                <div className="flex flex-wrap items-center gap-2">
                  <button
                    onClick={openRefreshMetadata}
                    className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-[var(--line)] text-muted-foreground hover:text-amber hover:border-amber/40 hover:bg-amber/5 transition-all focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
                  >
                    <RefreshCw className="w-3.5 h-3.5" />
                    Refresh poster &amp; metadata
                  </button>
                  {user && (
                    <button
                      onClick={() => setSendPanelOpen(true)}
                      className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-[var(--line)] text-muted-foreground hover:text-amber hover:border-amber/40 hover:bg-amber/5 transition-all focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60"
                    >
                      <Send className="w-3.5 h-3.5" />
                      Send to a friend
                    </button>
                  )}
                  <button
                    onClick={() => setPendingDeleteTitle(true)}
                    className="flex items-center gap-2 text-xs font-mono rounded-full px-3 py-1.5 border border-[var(--line)] text-muted-foreground hover:text-ember hover:border-ember/30 hover:bg-ember/5 transition-all focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ember/60"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                    Remove from library
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </CinemaModal>
    </>
  )
}
