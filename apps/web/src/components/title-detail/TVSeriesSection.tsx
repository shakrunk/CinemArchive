import { saveSucceeded } from 'src/lib/localSave'
import { scaledTextSize } from 'src/lib/textScale'
import { useState, useEffect, useRef } from 'react'
import { SeriesGraph } from 'src/components/ui/series-graph'
import { Eyebrow } from 'src/components/ui/typography'
import { useAppStore } from 'src/store/useAppStore'
import { CastGrid } from 'src/components/CastCrewSection'
import type { PersonDetailTarget } from 'src/components/PersonDetailPanel'
import {
  avgSeasonRating,
  avgSeriesRating,
  episodesWatchedInSeason,
  totalEpisodesWatched,
  totalEpisodeCount,
  nextScheduledEpisode,
  isEpisodeSpoilerHidden,
  mainSeasons,
  orderSeasonsForDisplay,
  seasonLabel,
  seasonShortLabel,
  seasonCastNewcomersFirst,
} from 'src/store/episodeUtils'
import { EpisodeCard, EpisodePanel } from 'src/components/ui/episode-card'
import { Clock, ChevronLeft, ChevronRight, ChevronDown } from 'lucide-react'
import { cn, fmtReleaseDate } from 'src/lib/utils'
import type { CastMember, Season } from 'src/store/mockData'

interface TVSeriesSectionProps {
  titleId: string
  tmdbId: number
  seasons: Season[]
  /** Series-level cast, used to lead the season cast with its newcomers. */
  seriesCast?: CastMember[]
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

export function TVSeriesSection({ titleId, tmdbId, seasons, seriesCast, isSharedView, isSpiderNoir, onPersonClick, onColorModeSelected }: TVSeriesSectionProps) {
  const markPrePlatformWatched = useAppStore((s) => s.markPrePlatformWatched)
  const [confirmPrePlatform, setConfirmPrePlatform] = useState<'series' | 'season' | null>(null)
  // Main seasons ascending, Specials last; series-level rollups use the main
  // seasons only (see the Specials note in episodeUtils).
  const orderedSeasons = orderSeasonsForDisplay(seasons)
  const seriesSeasons = mainSeasons(seasons)
  const [selectedSeason, setSelectedSeason] = useState(orderedSeasons[0]?.seasonNumber ?? 1)
  const [selectedEpId, setSelectedEpId] = useState<string | null>(null)
  const [showSeasonCast, setShowSeasonCast] = useState(false)
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
  const nextScheduled = nextScheduledEpisode(seasons)
  const spoilerProtection = useAppStore((s) => s.spoilerProtection)

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

  // Keep the selected card visible — a jump from the next-episode banner or the
  // series graph can land on a card scrolled out of the carousel. Horizontal
  // only, so the drawer itself doesn't move.
  useEffect(() => {
    const el = carouselRef.current
    if (!el || !selectedEpId) return
    const card = el.querySelector<HTMLElement>(`[data-episode-id="${selectedEpId}"]`)
    if (!card) return
    const cardLeft = card.offsetLeft - el.offsetLeft
    if (cardLeft < el.scrollLeft || cardLeft + card.offsetWidth > el.scrollLeft + el.clientWidth) {
      el.scrollTo({ left: cardLeft, behavior: 'smooth' })
    }
  }, [selectedEpId])

  function handleSeasonChange(seasonNumber: number) {
    setSelectedSeason(seasonNumber)
    setConfirmPrePlatform(null)
  }

  // Jump to an episode: same season selects it directly; otherwise the
  // season-change effect picks it up from the ref once the carousel resets.
  function jumpToEpisode(seasonNumber: number, episodeId: string) {
    if (seasonNumber === selectedSeason) {
      setSelectedEpId(episodeId)
      return
    }
    graphClickEpIdRef.current = episodeId
    handleSeasonChange(seasonNumber)
  }

  const seriesUnwatched = unwatchedEpisodeCount(seriesSeasons)
  const seasonUnwatched = season ? unwatchedEpisodeCount([season]) : 0

  function renderPrePlatformMark(scope: 'series' | 'season') {
    const count = scope === 'series' ? seriesUnwatched : seasonUnwatched
    if (isSharedView || count === 0) return null
    const label = scope === 'series'
      ? 'Watched entire series before joining'
      : `Watched ${season ? seasonLabel(season.seasonNumber).toLowerCase() : 'season'} before joining`
    return confirmPrePlatform === scope ? (
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <span className="font-mono text-xs" style={{ color: 'var(--paper-faint)' }}>
          Mark {count} episode{count === 1 ? '' : 's'} as watched (no date)?
        </span>
        <div className="flex items-center gap-3">
          <button type="button"
            onClick={async () => {
              if (!await saveSucceeded(markPrePlatformWatched(titleId, scope === 'season' ? season?.seasonNumber : undefined))) return
              setConfirmPrePlatform(null)
            }}
            className="font-mono text-xs transition-colors text-amber hover:text-amber/80"
            aria-label={`Confirm marking ${count} episodes as watched before joining`}
          >
            Confirm
          </button>
          <button type="button"
            onClick={() => setConfirmPrePlatform(null)}
            className="focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm font-mono text-xs transition-opacity hover:opacity-80"
            style={{ color: 'var(--paper-faint)' }}
            aria-label="Cancel marking as watched before joining"
          >
            Cancel
          </button>
        </div>
      </div>
    ) : (
      <button type="button"
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
          <Eyebrow as="div" className="mt-0.5">Episodes</Eyebrow>
        </div>
        <div className="rounded-lg px-3 py-1.5 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--amber)', fontVariationSettings: '"opsz" 30' }}>
            {seriesAvg !== null ? `★ ${seriesAvg.toFixed(1)}` : '—'}
          </div>
          <Eyebrow as="div" className="mt-0.5">Avg Rating</Eyebrow>
        </div>
        <div className="rounded-lg px-3 py-1.5 text-center" style={{ background: 'var(--inset)', border: '1px solid var(--line)' }}>
          <div className="font-serif text-xl" style={{ color: 'var(--paper)', fontVariationSettings: '"opsz" 30' }}>{seriesSeasons.length}</div>
          <Eyebrow as="div" className="mt-0.5">Seasons</Eyebrow>
        </div>
      </div>

      {/* Next scheduled (not yet aired) episode */}
      {nextScheduled && (
        <button
          type="button"
          onClick={() => jumpToEpisode(nextScheduled.season.seasonNumber, nextScheduled.episode.id)}
          aria-label={`Next episode, season ${nextScheduled.season.seasonNumber} episode ${nextScheduled.episode.episodeNumber}, airs ${fmtReleaseDate(nextScheduled.episode.airDate!)}`}
          className="w-full flex items-center gap-3 rounded-lg px-3 py-2 text-left border border-(--line) transition-colors hover:border-amber/40 focus:outline-hidden focus-visible:ring-2 focus-visible:ring-amber/60"
          style={{ background: 'var(--inset)' }}
        >
          <Clock className="w-4 h-4 shrink-0" style={{ color: 'var(--amber)' }} />
          <div className="min-w-0 flex-1">
            <Eyebrow as="div">Next episode</Eyebrow>
            <div className="font-sans text-sm truncate" style={{ color: 'var(--paper)' }}>
              <span className="font-mono text-xs mr-1.5" style={{ color: 'var(--amber)' }}>
                S{nextScheduled.season.seasonNumber} E{nextScheduled.episode.episodeNumber}
              </span>
              {(isEpisodeSpoilerHidden(nextScheduled.episode, spoilerProtection, isSharedView) ? null : nextScheduled.episode.episodeName) ?? `Episode ${nextScheduled.episode.episodeNumber}`}
            </div>
          </div>
          <div className="font-mono text-xs shrink-0 text-right" style={{ color: 'var(--amber)' }}>
            Airs {fmtReleaseDate(nextScheduled.episode.airDate!)}
          </div>
        </button>
      )}

      {/* Bulk pre-platform mark: series scope */}
      {renderPrePlatformMark('series')}

      {/* Series Graph heatmap */}
      {seriesSeasons.some(hasEpisodes) && (
        <div>
          <Eyebrow as="h4" size="md" className="mb-3">
            Series Graph
          </Eyebrow>
          <SeriesGraph
            seasons={seriesSeasons}
            spoilerProtection={spoilerProtection}
            isSharedView={isSharedView}
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
      {orderedSeasons.length <= 3 ? (
        <div className="flex gap-2 overflow-x-auto scrollbar-none">
          {orderedSeasons.map((s) => {
            const watched = episodesWatchedInSeason(s)
            const pct = s.episodeCount > 0 ? Math.round((watched / s.episodeCount) * 100) : 0
            const seasonAvg = avgSeasonRating(s)
            return (
              <button type="button"
                key={s.seasonNumber}
                onClick={() => handleSeasonChange(s.seasonNumber)}
                aria-label={seasonLabel(s.seasonNumber)}
                aria-current={selectedSeason === s.seasonNumber ? 'true' : undefined}
                className={cn(
                  'shrink-0 px-3.5 py-2.5 rounded-lg text-left transition-all border',
                  selectedSeason === s.seasonNumber
                    ? 'border-amber/40 bg-amber/10'
                    : 'border-transparent hover:border-(--line) hover:bg-(--wash)'
                )}
              >
                <div className="font-mono" style={{ fontSize: scaledTextSize('13px'), color: selectedSeason === s.seasonNumber ? 'var(--amber)' : 'var(--paper-dim)' }}>
                  {seasonShortLabel(s.seasonNumber)}
                </div>
                <div className="font-mono" style={{ fontSize: scaledTextSize('11px'), color: 'var(--paper-faint)' }}>
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
          className="font-mono text-sm rounded-lg px-3 py-2 bg-secondary border border-amber/30 focus:outline-hidden focus:border-amber/60"
          style={{ color: 'var(--amber)' }}
        >
          {orderedSeasons.map((s) => {
            const watched = episodesWatchedInSeason(s)
            const pct = s.episodeCount > 0 ? Math.round((watched / s.episodeCount) * 100) : 0
            const seasonAvg = avgSeasonRating(s)
            return (
              <option key={s.seasonNumber} value={s.seasonNumber}>
                {`${seasonLabel(s.seasonNumber)} · ${pct}%${seasonAvg !== null ? ` · ★${seasonAvg.toFixed(1)}` : ''}`}
              </option>
            )
          })}
        </select>
      )}

      {/* Bulk pre-platform mark: selected-season scope */}
      {renderPrePlatformMark('season')}

      {/* Episode carousel — the season's primary content, so it leads (#132) */}
      {season && hasEpisodes(season) && (
        <div>
          <div className="relative">
            {/* Left arrow */}
            {canScrollLeft && (
              <button
                type="button"
                onClick={() => scrollCarousel('left')}
                aria-label="Scroll episodes left"
                className="absolute left-0 top-1/2 -translate-y-1/2 z-10 -translate-x-2 rounded-full p-1.5 transition-opacity hover:opacity-100 opacity-80 focus:outline-hidden focus-visible:ring-2 focus-visible:ring-amber/60"
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
                className="absolute right-0 top-1/2 -translate-y-1/2 z-10 translate-x-2 rounded-full p-1.5 transition-opacity hover:opacity-100 opacity-80 focus:outline-hidden focus-visible:ring-2 focus-visible:ring-amber/60"
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
                tmdbId={tmdbId}
                isSharedView={isSharedView}
                isSpiderNoir={isSpiderNoir}
                onPersonClick={onPersonClick}
                onColorModeSelected={onColorModeSelected}
              />
            </div>
          )}
        </div>
      )}

      {/* Season cast — collapsed by default; it mostly repeats the series cast,
          so the people new to this season lead when it opens. */}
      {season?.cast && season.cast.length > 0 && (
        <div>
          <button
            type="button"
            onClick={() => setShowSeasonCast((v) => !v)}
            aria-expanded={showSeasonCast}
            className="flex items-center gap-1.5 group focus:outline-hidden"
          >
            <Eyebrow as="span" className="group-hover:text-amber group-focus-visible:text-amber transition-colors">
              {seasonLabel(season.seasonNumber)} Cast · {season.cast.length}
            </Eyebrow>
            <ChevronDown
              className={cn('w-3 h-3 text-paper-faint transition-transform group-hover:text-amber', showSeasonCast && 'rotate-180')}
            />
          </button>
          {showSeasonCast && (
            <div className="mt-2">
              <CastGrid cast={seasonCastNewcomersFirst(season.cast, seriesCast)} onPersonClick={onPersonClick} />
            </div>
          )}
        </div>
      )}

      {/* Fallback: coarse progress bar when season has no episode-level data */}
      {season && !hasEpisodes(season) && (() => {
        const pct = season.episodeCount > 0 ? (season.episodesWatched / season.episodeCount) * 100 : 0
        return (
          <div className="flex items-center gap-3 px-2 py-3">
            <span className="font-mono text-xs text-muted-foreground w-8 shrink-0">{seasonShortLabel(season.seasonNumber)}</span>
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
