import { useEffect, useState } from 'react'
import { Check, Film, Plus, Tv } from 'lucide-react'
import { fetchMediaDetails, fetchTitleImages, fetchTitleVideos, fetchWatchProviders, type SearchResult, type TitleVideo, type WatchProviders } from 'src/lib/media'
import { cn, fmtRuntime, fmtReleaseDate, languageName } from 'src/lib/utils'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { ReviewBadges, ExternalLinks } from 'src/components/ui/media-badges'
import { SubsectionLabel } from 'src/components/ui/typography'
import { TrailerRow } from 'src/components/ui/trailer-row'
import { WatchProviderListings } from 'src/components/ui/watch-providers'
import { CastCrewSection } from 'src/components/CastCrewSection'
import type { PersonDetailTarget } from 'src/components/PersonDetailPanel'
import { useScopedSmoothScroll } from 'src/lib/useSmoothScroll'

interface DiscoverDetailModalProps {
  result: SearchResult | null
  isOwned: boolean
  isSharedView: boolean
  onClose: () => void
  onAdd: (result: SearchResult) => void
  onBrowsePerson: (person: PersonDetailTarget) => void
}

export function DiscoverDetailModal({ result, ...props }: DiscoverDetailModalProps) {
  // Movies and series use separate TMDB IDs. Remount all preview state on either
  // identity change so late requests and an open trailer cannot leak across titles.
  return result ? <DiscoverTitlePreview key={`${result.type}:${result.tmdbId}`} result={result} {...props} /> : null
}

function DiscoverTitlePreview({ result, isOwned, isSharedView, onClose, onAdd, onBrowsePerson }: Omit<DiscoverDetailModalProps, 'result'> & { result: SearchResult }) {
  const [details, setDetails] = useState<SearchResult | null>(null)
  const [logoUrl, setLogoUrl] = useState<string | null>(null)
  const [videos, setVideos] = useState<TitleVideo[] | null>(null)
  const [providers, setProviders] = useState<WatchProviders | null | undefined>(undefined)
  const [expanded, setExpanded] = useState(false)
  const [scrollBody, setScrollBody] = useState<HTMLDivElement | null>(null)
  useScopedSmoothScroll(scrollBody, true)
  const hydrating = details === null

  useEffect(() => {
    let cancelled = false
    fetchMediaDetails(result)
      .then(({ result: r }) => { if (!cancelled) setDetails(r) })
      .catch(() => { if (!cancelled) setDetails(result) })
    fetchTitleImages(result.tmdbId, result.type).then(({ logoUrl: logo }) => {
      if (!cancelled) setLogoUrl(logo)
    }).catch(() => { /* The poster and title remain available. */ })
    fetchTitleVideos(result.tmdbId, result.type)
      .then((items) => { if (!cancelled) setVideos(items) })
      .catch(() => { if (!cancelled) setVideos([]) })
    fetchWatchProviders(result.tmdbId, result.type)
      .then((items) => { if (!cancelled) setProviders(items) })
      .catch(() => { if (!cancelled) setProviders(null) })
    return () => { cancelled = true }
  }, [result])

  const data = details ?? result

  function handleAdd() {
    onClose()
    onAdd(data)
  }

  const hasScores = [data.imdbRating, data.rtScore, data.metacriticScore, data.awardsCount, data.bechdelOutcome].some((value) => value != null)
  const hasBackdrop = !!data.backdropUrl
  const hasProviders = providers && [providers.flatrate, providers.free, providers.ads, providers.rent, providers.buy].some((items) => items.length > 0)

  return (
    <CinemaModal
      open
      onClose={onClose}
      title={data.title}
      description={data.synopsis || `Preview ${data.title}, watch trailers, and explore its cast.`}
      maxWidth="sm:max-w-2xl"
      expanded={expanded}
      onToggleExpand={() => setExpanded((value) => !value)}
    >
      <div ref={setScrollBody} className="overflow-y-auto flex-1 min-h-0 scrollbar-thin">
        {/* Backdrop */}
        {hasBackdrop ? (
          <div className="relative aspect-[16/8] overflow-hidden shrink-0">
            <img
              src={data.backdropUrl}
              alt=""
              aria-hidden
              className="absolute inset-0 w-full h-full object-cover object-center"
              style={{
                maskImage: 'linear-gradient(to bottom, #000 0%, #000 63%, transparent 100%)',
                WebkitMaskImage: 'linear-gradient(to bottom, #000 0%, #000 63%, transparent 100%)',
              }}
            />
            <div
              className="absolute inset-0"
              style={{
                background: 'linear-gradient(to bottom, hsl(var(--card) / 0.05) 0%, hsl(var(--card) / 0.3) 45%, hsl(var(--card) / 0.75) 68%, hsl(var(--card)) 88%)',
              }}
            />
          </div>
        ) : (
          <div className="h-14" />
        )}

        {/* Content */}
        <div className={cn('px-5 pb-6', hasBackdrop ? '-mt-20 relative z-10' : 'pt-2')}>
          {/* Poster + title */}
          <div className="flex gap-4 items-end mb-4">
            <div
              className="w-24 shrink-0 rounded-lg overflow-hidden shadow-xl border"
              style={{ borderColor: 'var(--line)' }}
            >
              {data.posterUrl ? (
                <img src={data.posterUrl} alt={data.title} className="w-full aspect-[2/3] object-cover" />
              ) : (
                <div className="aspect-[2/3] flex items-center justify-center" style={{ background: 'var(--inset)' }}>
                  {data.type === 'tv'
                    ? <Tv className="w-6 h-6 text-paper-faint opacity-30" />
                    : <Film className="w-6 h-6 text-paper-faint opacity-30" />}
                </div>
              )}
            </div>

            <div className="flex-1 min-w-0 pb-1">
              {logoUrl ? (
                <img
                  src={logoUrl}
                  alt={data.title}
                  className="object-contain object-left max-h-20 max-w-[90%] drop-shadow-lg mb-1.5"
                />
              ) : (
                <h2 className="font-serif text-xl font-semibold text-paper leading-tight mb-1.5">
                  {data.title}
                </h2>
              )}
              <div className="flex flex-wrap items-center gap-1.5 mb-1">
                <span className="font-mono text-xs text-paper-faint">
                  {data.year > 0 ? data.year : ''}
                  {data.runtime ? ` · ${fmtRuntime(data.runtime)}` : ''}
                  {data.type === 'tv' && data.seasonCount
                    ? ` · ${data.seasonCount} season${data.seasonCount !== 1 ? 's' : ''}`
                    : ''}
                </span>
                {data.contentRating && (
                  <span
                    className="font-mono text-[9px] px-1.5 py-0.5 rounded border text-paper-faint"
                    style={{ borderColor: 'var(--line)' }}
                  >
                    {data.contentRating}
                  </span>
                )}
                <span
                  className="font-mono text-[9px] px-1.5 py-0.5 rounded"
                  style={{ background: 'var(--inset)', color: 'var(--paper-faint)' }}
                >
                  {data.type === 'tv' ? 'TV' : 'Movie'}
                </span>
              </div>
              {data.director && (
                <p className="font-mono text-[11px] text-paper-faint">
                  {data.type === 'tv' ? 'Created by ' : 'Dir. '}{data.director}
                </p>
              )}
              {data.network && (
                <p className="font-mono text-[11px] text-amber/70 mt-0.5">{data.network}</p>
              )}
            </div>
          </div>

          {/* Genres */}
          {data.genres.length > 0 && (
            <div className="flex flex-wrap gap-1.5 mb-4">
              {data.genres.map((g) => (
                <span
                  key={g}
                  className="font-mono text-[10px] px-2 py-0.5 rounded-full border text-paper-faint"
                  style={{ borderColor: 'var(--line)', background: 'var(--inset)' }}
                >
                  {g}
                </span>
              ))}
            </div>
          )}

          {/* Synopsis */}
          {hydrating && !data.synopsis ? (
            <div className="space-y-1.5 mb-4">
              <div className="h-3 rounded animate-pulse w-full" style={{ background: 'var(--inset)' }} />
              <div className="h-3 rounded animate-pulse w-5/6" style={{ background: 'var(--inset)' }} />
              <div className="h-3 rounded animate-pulse w-4/6" style={{ background: 'var(--inset)' }} />
            </div>
          ) : data.synopsis ? (
            <p className="font-sans text-sm text-paper/75 leading-relaxed mb-4">{data.synopsis}</p>
          ) : null}

          {/* Preview media comes before credits, while deciding what to watch. */}
          <div className="mb-5">
            {videos === null ? (
              <p role="status" className="text-sm text-paper-faint">Loading trailers…</p>
            ) : videos.length > 0 ? (
              <TrailerRow videos={videos} />
            ) : (
              <p className="text-sm text-paper-faint">No trailers available for this title.</p>
            )}
          </div>

          <div className="mb-5">
            <SubsectionLabel>Where to Watch</SubsectionLabel>
            {providers === undefined ? (
              <p role="status" className="text-sm text-paper-faint">Loading watch options…</p>
            ) : providers && hasProviders ? (
              <WatchProviderListings providers={providers} />
            ) : (
              <p className="text-sm text-paper-faint">No watch options are available right now.</p>
            )}
          </div>

          {/* Scores */}
          {hydrating && !hasScores ? (
            <div className="flex gap-2 mb-4">
              {[1, 2, 3].map((i) => (
                <div key={i} className="h-8 w-20 rounded animate-pulse" style={{ background: 'var(--inset)' }} />
              ))}
            </div>
          ) : hasScores ? (
            <div className="mb-4">
              <ReviewBadges
                imdb={data.imdbRating}
                rt={data.rtScore}
                meta={data.metacriticScore}
                awardsCount={data.awardsCount}
                bechdelOutcome={data.bechdelOutcome}
                bechdelScore={data.bechdelScore}
              />
            </div>
          ) : null}

          <div className="mb-5">
            {hydrating && !data.cast && !data.crew ? (
              <p role="status" className="text-sm text-paper-faint">Loading cast and crew…</p>
            ) : (
              <CastCrewSection
                cast={data.cast}
                crew={data.crew}
                studios={data.studios}
                onPersonClick={(person) => { onClose(); onBrowsePerson(person) }}
              />
            )}
          </div>

          {(data.releaseDate || data.originalLanguage || data.collectionName) && (
            <div className="mb-5">
              <SubsectionLabel>Details</SubsectionLabel>
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm text-paper-dim">
                {data.releaseDate && <><dt>Release</dt><dd>{fmtReleaseDate(data.releaseDate)}</dd></>}
                {data.originalLanguage && <><dt>Language</dt><dd>{languageName(data.originalLanguage)}</dd></>}
                {data.collectionName && <><dt>Franchise</dt><dd>{data.collectionName.replace(/\s+Collection$/i, '')}</dd></>}
              </dl>
            </div>
          )}
          {/* External links */}
          <div className="mb-5">
            <ExternalLinks media={data} />
          </div>


        </div>
      </div>
      <div className="shrink-0 border-t border-[var(--line)] px-5 py-3">
          {/* Add / In Library */}
          {isOwned ? (
            <div className="flex items-center gap-2 text-amber font-mono text-sm py-2.5">
              <Check className="w-4 h-4" />
              Already in your library
            </div>
          ) : !isSharedView ? (
            <button
              onClick={handleAdd}
              className="w-full py-2.5 rounded-lg font-bold text-sm flex items-center justify-center gap-2 transition-colors btn-amber mt-2"
            >
              <Plus className="w-4 h-4" />
              Add to Library
            </button>
          ) : null}
      </div>
    </CinemaModal>
  )
}
