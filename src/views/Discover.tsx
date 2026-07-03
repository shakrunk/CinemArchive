import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { Search, Compass, X, Film, Tv, Check, Plus, Info, User, Building2, ChevronLeft } from 'lucide-react'
import { useShallow } from 'zustand/react/shallow'
import { useAppStore } from 'src/store/useAppStore'
import {
  searchMedia, fetchTrending, fetchDiscover, fetchMediaDetails, fetchTitleImages,
  searchPersons, fetchPersonCredits, searchCompanies, fetchCompanyTitles,
  MOVIE_GENRES, TV_GENRES,
  type SearchResult, type PersonResult, type CompanyResult,
} from 'src/lib/media'
import type { MediaType } from 'src/store/mockData'
import { cn } from 'src/lib/utils'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { ReviewBadges, ExternalLinks } from 'src/components/ui/media-badges'

// ─── Types ────────────────────────────────────────────────────────────────────

type FilterType = 'all' | MediaType
type SearchMode = 'titles' | 'people' | 'studios'

const SEARCH_PLACEHOLDERS: Record<SearchMode, string> = {
  titles: 'Search movies & TV shows…',
  people: 'Search by actor, director, crew…',
  studios: 'Search by studio or company…',
}

// ─── DiscoverCard ─────────────────────────────────────────────────────────────

interface DiscoverCardProps {
  result: SearchResult
  isOwned: boolean
  style?: React.CSSProperties
  isSharedView: boolean
  onAdd: (result: SearchResult) => void
  onSelect: (result: SearchResult) => void
}

function DiscoverCard({ result, isOwned, isSharedView, onAdd, onSelect, style }: DiscoverCardProps) {
  const [imgError, setImgError] = useState(false)
  const pushNotification = useAppStore((s) => s.pushNotification)

  return (
    <div
      className="discover-card group relative cursor-pointer"
      style={style}
      onClick={() => onSelect(result)}
      role="button"
      tabIndex={0}
      aria-label={`View details for ${result.title}`}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelect(result) } }}
    >
      <div className="relative aspect-[2/3] rounded-lg overflow-hidden border transition-transform duration-200 group-hover:scale-[1.02] group-hover:shadow-[0_8px_32px_rgba(0,0,0,0.6)]"
        style={{ background: 'var(--void)', borderColor: 'var(--line)' }}
      >
        {result.posterUrl && !imgError ? (
          <img
            src={result.posterUrl}
            alt={result.title}
            loading="lazy"
            onError={() => setImgError(true)}
            className="w-full h-full object-cover"
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center" style={{ background: 'var(--inset)' }}>
            {result.type === 'tv' ? (
              <Tv className="w-8 h-8 text-paper-faint opacity-30" />
            ) : (
              <Film className="w-8 h-8 text-paper-faint opacity-30" />
            )}
          </div>
        )}

        {/* Gradient + hover content */}
        <div className="absolute inset-0 bg-gradient-to-t from-void via-void/60 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-200" />
        <div className="absolute inset-0 flex flex-col justify-end p-2.5 opacity-0 group-hover:opacity-100 transition-opacity duration-200">
          <p className="font-serif text-[13px] font-semibold text-paper leading-snug line-clamp-2 mb-0.5">
            {result.title}
          </p>
          <p className="font-mono text-[10px] text-paper-faint mb-1.5">
            {result.year > 0 ? result.year : ''}
            {result.type === 'tv' && result.seasonCount ? ` · ${result.seasonCount}S` : ''}
          </p>
          {result.genres.length > 0 && (
            <p className="font-mono text-[9px] text-amber/70 truncate mb-2">
              {result.genres.slice(0, 2).join(' · ')}
            </p>
          )}
          {isOwned ? (
            <div className="flex items-center gap-1 text-amber text-[10px] font-mono">
              <Check className="w-3 h-3" />
              In your library
            </div>
          ) : !isSharedView ? (
            <div className="flex items-center gap-0 group-hover:gap-1.5 transition-all duration-300 delay-[1500ms]">
              <button
                onClick={(e) => { e.stopPropagation(); onAdd(result) }}
                aria-label={`Add ${result.title} to library`}
                className="flex items-center justify-center gap-1 flex-1 py-1.5 rounded text-[11px] font-bold transition-colors btn-amber"
              >
                <Plus className="w-3 h-3" />
                Add to Library
              </button>

              {/* Details hint — expands after a long hover, with tooltip */}
              <div
                className="relative group/detail shrink-0 w-0 group-hover:w-[30px] opacity-0 group-hover:opacity-100 transition-all duration-300 delay-[1500ms]"
              >
                {/* Tooltip — always visible once the wrapper fades in */}
                <div className="absolute bottom-full right-0 mb-1.5 pointer-events-none z-10">
                  <div
                    className="relative px-2 py-1 rounded shadow-lg"
                    style={{ background: 'var(--void)', border: '1px solid rgba(233,178,102,0.35)' }}
                  >
                    <p className="font-mono text-[9px] text-paper-faint whitespace-nowrap">Click for more details</p>
                    <div
                      className="absolute top-full right-2.5"
                      style={{
                        width: 0, height: 0,
                        borderLeft: '4px solid transparent',
                        borderRight: '4px solid transparent',
                        borderTop: '4px solid rgba(233,178,102,0.35)',
                      }}
                    />
                  </div>
                </div>

                <button
                  onClick={(e) => {
                    e.stopPropagation()
                    onSelect(result)
                    pushNotification({
                      message: 'Next time, click on the poster itself to see more details.',
                      kind: 'tip',
                      autoClose: 5000,
                    })
                  }}
                  aria-label={`See details for ${result.title}`}
                  className="w-full h-[30px] rounded flex items-center justify-center btn-amber"
                >
                  <Info className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
          ) : null}
        </div>

        {/* Always-visible "in library" badge */}
        {isOwned && (
          <div
            className="absolute top-1.5 right-1.5 w-5 h-5 rounded-full flex items-center justify-center shadow-md"
            style={{ background: 'var(--amber)' }}
            title="Already in your library"
          >
            <Check className="w-[11px] h-[11px] text-[color:var(--on-amber)]" strokeWidth={3} />
          </div>
        )}
      </div>

      {/* Mobile: title, year, and add button below card (hover doesn't work on touch) */}
      <div className="mt-1.5 sm:hidden px-0.5">
        <p className="font-serif text-[12px] text-paper leading-snug truncate">{result.title}</p>
        <div className="flex items-center justify-between gap-1 mt-0.5">
          <p className="font-mono text-[10px] text-paper-faint">
            {result.year > 0 ? result.year : ''}
            {result.type === 'tv' && result.seasonCount ? ` · ${result.seasonCount}S` : ''}
          </p>
          {isOwned ? (
            <Check className="w-3 h-3 text-amber shrink-0" />
          ) : !isSharedView ? (
            <button
              onClick={(e) => { e.stopPropagation(); onAdd(result) }}
              aria-label={`Add ${result.title} to library`}
              className="shrink-0 w-6 h-6 rounded-full flex items-center justify-center transition-colors btn-amber"
            >
              <Plus className="w-3.5 h-3.5" />
            </button>
          ) : null}
        </div>
      </div>
    </div>
  )
}

// ─── Loading skeleton ─────────────────────────────────────────────────────────

function DiscoverSkeleton() {
  return (
    <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6 xl:grid-cols-7 gap-3">
      {Array.from({ length: 14 }, (_, i) => (
        <div key={i} className="aspect-[2/3] rounded-lg animate-pulse" style={{ background: 'var(--inset)' }} />
      ))}
    </div>
  )
}

// ─── PersonPicker ─────────────────────────────────────────────────────────────

interface PersonPickerProps {
  persons: PersonResult[]
  onSelect: (person: PersonResult) => void
}

function PersonPicker({ persons, onSelect }: PersonPickerProps) {
  return (
    <div className="max-w-lg mx-auto space-y-2">
      {persons.map((p) => (
        <button
          key={p.id}
          onClick={() => onSelect(p)}
          className="w-full flex items-center gap-3 p-3 rounded-lg border text-left transition-colors hover:border-amber/30"
          style={{ background: 'var(--inset)', borderColor: 'var(--line)' }}
        >
          <div
            className="w-10 h-10 rounded-full overflow-hidden shrink-0 border flex items-center justify-center"
            style={{ borderColor: 'var(--line)', background: 'var(--void)' }}
          >
            {p.profileUrl ? (
              <img src={p.profileUrl} alt={p.name} className="w-full h-full object-cover" />
            ) : (
              <User className="w-4 h-4 text-paper-faint opacity-40" />
            )}
          </div>
          <div className="min-w-0">
            <p className="font-serif text-sm text-paper">{p.name}</p>
            {p.department && (
              <p className="font-mono text-[10px] text-amber/60 mt-0.5">{p.department}</p>
            )}
            {p.knownFor && (
              <p className="font-mono text-[10px] text-paper-faint truncate mt-0.5">{p.knownFor}</p>
            )}
          </div>
        </button>
      ))}
    </div>
  )
}

// ─── CompanyPicker ────────────────────────────────────────────────────────────

interface CompanyPickerProps {
  companies: CompanyResult[]
  onSelect: (company: CompanyResult) => void
}

function CompanyPicker({ companies, onSelect }: CompanyPickerProps) {
  return (
    <div className="max-w-lg mx-auto space-y-2">
      {companies.map((c) => (
        <button
          key={c.id}
          onClick={() => onSelect(c)}
          className="w-full flex items-center gap-3 p-3 rounded-lg border text-left transition-colors hover:border-amber/30"
          style={{ background: 'var(--inset)', borderColor: 'var(--line)' }}
        >
          <div
            className="w-10 h-10 rounded-lg overflow-hidden shrink-0 border flex items-center justify-center"
            style={{ borderColor: 'var(--line)', background: 'var(--void)' }}
          >
            {c.logoUrl ? (
              <img src={c.logoUrl} alt={c.name} className="w-full h-full object-contain p-1" style={{ filter: 'invert(1)' }} />
            ) : (
              <Building2 className="w-4 h-4 text-paper-faint opacity-40" />
            )}
          </div>
          <div className="min-w-0">
            <p className="font-serif text-sm text-paper">{c.name}</p>
            {c.originCountry && (
              <p className="font-mono text-[10px] text-paper-faint mt-0.5">{c.originCountry}</p>
            )}
          </div>
        </button>
      ))}
    </div>
  )
}

// ─── Detail modal ─────────────────────────────────────────────────────────────

interface DiscoverDetailModalProps {
  result: SearchResult | null
  isOwned: boolean
  isSharedView: boolean
  onClose: () => void
  onAdd: (result: SearchResult) => void
}

function DiscoverDetailModal({ result, isOwned, isSharedView, onClose, onAdd }: DiscoverDetailModalProps) {
  const [details, setDetails] = useState<SearchResult | null>(null)
  const [hydrating, setHydrating] = useState(false)
  const [logoUrl, setLogoUrl] = useState<string | null>(null)

  useEffect(() => {
    if (!result) { setDetails(null); return }
    setDetails(null)
    setHydrating(true)
    fetchMediaDetails(result)
      .then(({ result: r }) => setDetails(r))
      .catch(() => setDetails(result))
      .finally(() => setHydrating(false))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [result?.tmdbId])

  useEffect(() => {
    if (!result?.tmdbId) { setLogoUrl(null); return }
    let cancelled = false
    fetchTitleImages(result.tmdbId, result.type).then(({ logoUrl: logo }) => {
      if (!cancelled) setLogoUrl(logo)
    })
    return () => { cancelled = true }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [result?.tmdbId])

  const data = details ?? result
  if (!data) return null

  function handleAdd() {
    onClose()
    onAdd(data!)
  }

  const hasScores = data.imdbRating != null || data.rtScore != null || data.metacriticScore != null
  const hasBackdrop = !!data.backdropUrl

  return (
    <CinemaModal
      open={!!result}
      onClose={onClose}
      title={data.title}
      description={data.synopsis}
      maxWidth="sm:max-w-2xl"
    >
      <div className="overflow-y-auto max-h-[90vh]">
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
                  {data.runtime ? ` · ${data.runtime}m` : ''}
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

          {/* Scores */}
          {hydrating && !hasScores ? (
            <div className="flex gap-2 mb-4">
              {[1, 2, 3].map((i) => (
                <div key={i} className="h-8 w-20 rounded animate-pulse" style={{ background: 'var(--inset)' }} />
              ))}
            </div>
          ) : hasScores ? (
            <div className="mb-4">
              <ReviewBadges imdb={data.imdbRating} rt={data.rtScore} meta={data.metacriticScore} />
            </div>
          ) : null}

          {/* Cast */}
          {hydrating && !data.cast ? (
            <div className="mb-5">
              <h3 className="font-mono text-[10px] uppercase tracking-[0.1em] text-paper-faint mb-2">Cast</h3>
              <div className="flex gap-2.5 overflow-x-auto pb-1 -mx-5 px-5">
                {[1, 2, 3, 4, 5].map((i) => (
                  <div
                    key={i}
                    className="shrink-0 w-[88px] rounded-lg overflow-hidden animate-pulse"
                    style={{ border: '1px solid var(--line)' }}
                  >
                    <div
                      className="w-full flex items-center justify-center"
                      style={{ background: 'var(--card)', aspectRatio: '2/3' }}
                    >
                      <User className="w-10 h-10" style={{ color: 'var(--line)' }} />
                    </div>
                    <div className="p-1.5 space-y-1.5" style={{ background: 'var(--inset)' }}>
                      <div className="h-2.5 rounded" style={{ background: 'var(--line)', width: '80%' }} />
                      <div className="h-2 rounded" style={{ background: 'var(--line)', width: '55%' }} />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : data.cast && data.cast.length > 0 ? (
            <div className="mb-5">
              <h3 className="font-mono text-[10px] uppercase tracking-[0.1em] text-paper-faint mb-2">Cast</h3>
              <div className="flex gap-2.5 overflow-x-auto pb-1 scrollbar-none -mx-5 px-5">
                {data.cast.slice(0, 10).map((c) => (
                  <div
                    key={c.tmdbPersonId}
                    className="shrink-0 w-[88px] rounded-lg overflow-hidden"
                    style={{ border: '1px solid var(--line)', background: 'var(--inset)' }}
                  >
                    <div className="overflow-hidden" style={{ aspectRatio: '2/3' }}>
                      {c.profileUrl ? (
                        <img src={c.profileUrl} alt={c.name} className="w-full h-full object-cover" />
                      ) : (
                        <div className="w-full h-full flex items-center justify-center" style={{ background: 'var(--card)' }}>
                          <span className="font-serif text-2xl" style={{ color: 'var(--paper-faint)', opacity: 0.4 }}>
                            {c.name.charAt(0).toUpperCase()}
                          </span>
                        </div>
                      )}
                    </div>
                    <div className="p-1.5">
                      <p className="font-sans font-semibold text-[11px] leading-tight line-clamp-2" style={{ color: 'var(--paper)' }}>
                        {c.name}
                      </p>
                      <p className="font-mono text-[9px] line-clamp-1 mt-0.5" style={{ color: 'var(--paper-faint)', opacity: c.character ? 0.6 : 0 }}>
                        {c.character || ' '}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          {/* External links */}
          <div className="mb-5">
            <ExternalLinks media={data} />
          </div>

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
      </div>
    </CinemaModal>
  )
}

// ─── Discover view ────────────────────────────────────────────────────────────

export function Discover() {
  const { titles, isSharedView, openAddTitlePreselected } = useAppStore(
    useShallow((s) => ({
      titles: s.titles,
      isSharedView: s.isSharedView,
      openAddTitlePreselected: s.openAddTitlePreselected,
    }))
  )

  // ── Core ──
  const [query, setQuery] = useState('')
  const [filterType, setFilterType] = useState<FilterType>('all')
  const [searchMode, setSearchMode] = useState<SearchMode>('titles')

  // ── Titles mode ──
  const [selectedGenreId, setSelectedGenreId] = useState<number | null>(null)
  const [trending, setTrending] = useState<SearchResult[]>([])
  const [discoverResults, setDiscoverResults] = useState<SearchResult[]>([])
  const [searchResults, setSearchResults] = useState<SearchResult[]>([])

  // ── People mode ──
  const [personResults, setPersonResults] = useState<PersonResult[]>([])
  const [selectedPerson, setSelectedPerson] = useState<PersonResult | null>(null)
  const [allPersonCredits, setAllPersonCredits] = useState<SearchResult[]>([])

  // ── Studios mode ──
  const [companyResults, setCompanyResults] = useState<CompanyResult[]>([])
  const [selectedCompany, setSelectedCompany] = useState<CompanyResult | null>(null)
  const [companyTitles, setCompanyTitles] = useState<SearchResult[]>([])

  // ── Shared ──
  const [selectedResult, setSelectedResult] = useState<SearchResult | null>(null)
  const [isSearchFocused, setIsSearchFocused] = useState(false)
  // Start loading so skeleton shows while the first trending fetch runs.
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(false)
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  // Fast owned-title lookup by tmdbId
  const libraryTmdbIds = useMemo(
    () => new Set(titles.map((t) => t.tmdbId).filter((id): id is number => id != null)),
    [titles]
  )

  const genres = filterType === 'tv' ? TV_GENRES : MOVIE_GENRES

  // Person credits filtered client-side by type — no extra fetch when filter changes
  const personCredits = useMemo(
    () => filterType === 'all' ? allPersonCredits : allPersonCredits.filter((r) => r.type === filterType),
    [allPersonCredits, filterType]
  )

  // ── Effects ──

  // Trending — titles mode, no query, no genre
  useEffect(() => {
    if (searchMode !== 'titles' || query.trim() || selectedGenreId !== null) return
    let cancelled = false
    const type: MediaType | 'all' = filterType
    fetchTrending(type)
      .then((data) => { if (!cancelled) { setTrending(data); setPage(1); setHasMore(data.length > 0); setLoading(false) } })
      .catch((err) => { console.error('fetchTrending error:', err); if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [filterType, query, selectedGenreId, searchMode])

  // Genre results — titles mode, genre selected, no query
  useEffect(() => {
    if (searchMode !== 'titles' || selectedGenreId === null || query.trim()) return
    let cancelled = false
    const mediaType: MediaType = filterType === 'all' ? 'movie' : filterType
    fetchDiscover(mediaType, selectedGenreId)
      .then((data) => { if (!cancelled) { setDiscoverResults(data); setPage(1); setHasMore(data.length > 0); setLoading(false) } })
      .catch((err) => { console.error('fetchDiscover error:', err); if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [filterType, selectedGenreId, query, searchMode])

  // Company titles — re-fetch when type filter changes while a company is selected
  useEffect(() => {
    if (searchMode !== 'studios' || !selectedCompany) return
    let cancelled = false
    setLoading(true)
    const type: MediaType = filterType === 'all' ? 'movie' : filterType
    fetchCompanyTitles(selectedCompany.id, type)
      .then((data) => { if (!cancelled) { setCompanyTitles(data); setLoading(false) } })
      .catch((err) => { console.error('company titles error:', err); if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [selectedCompany, filterType, searchMode])

  // ── Handlers ──

  const handleSearch = useCallback((value: string) => {
    setQuery(value)
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current)
    // Typing resets any active person/company selection back to picker mode
    setSelectedPerson(null)
    setAllPersonCredits([])
    setSelectedCompany(null)
    setCompanyTitles([])
    if (!value.trim()) {
      setSearchResults([])
      setPersonResults([])
      setCompanyResults([])
      return
    }
    setLoading(true)
    searchTimerRef.current = setTimeout(async () => {
      try {
        if (searchMode === 'titles') {
          const all = await searchMedia(value)
          const filtered = filterType === 'all' ? all : all.filter((r) => r.type === filterType)
          setSearchResults(filtered)
        } else if (searchMode === 'people') {
          const results = await searchPersons(value)
          setPersonResults(results)
        } else {
          const results = await searchCompanies(value)
          setCompanyResults(results)
        }
      } catch (err) {
        console.error('search error:', err)
        setSearchResults([])
        setPersonResults([])
        setCompanyResults([])
      } finally {
        setLoading(false)
      }
    }, 400)
  }, [filterType, searchMode])

  async function handlePersonSelect(person: PersonResult) {
    setPersonResults([])
    setSelectedPerson(person)
    setQuery(person.name)
    setLoading(true)
    try {
      const credits = await fetchPersonCredits(person.id)
      setAllPersonCredits(credits)
    } catch (err) {
      console.error('person credits error:', err)
      setAllPersonCredits([])
    } finally {
      setLoading(false)
    }
  }

  async function handleCompanySelect(company: CompanyResult) {
    setCompanyResults([])
    setSelectedCompany(company)
    setQuery(company.name)
    setLoading(true)
    try {
      const type: MediaType = filterType === 'all' ? 'movie' : filterType
      const results = await fetchCompanyTitles(company.id, type)
      setCompanyTitles(results)
    } catch (err) {
      console.error('company titles error:', err)
      setCompanyTitles([])
    } finally {
      setLoading(false)
    }
  }

  function handleSearchModeChange(mode: SearchMode) {
    setSearchMode(mode)
    setQuery('')
    setSearchResults([])
    setPersonResults([])
    setSelectedPerson(null)
    setAllPersonCredits([])
    setCompanyResults([])
    setSelectedCompany(null)
    setCompanyTitles([])
    setSelectedGenreId(null)
    setLoading(mode === 'titles')
    inputRef.current?.focus()
  }

  function clearSearch() {
    setQuery('')
    setSearchResults([])
    setPersonResults([])
    setSelectedPerson(null)
    setAllPersonCredits([])
    setCompanyResults([])
    setSelectedCompany(null)
    setCompanyTitles([])
    inputRef.current?.focus()
  }

  function handleGenreSelect(id: number | null) {
    if (id !== null) setLoading(true)
    setSelectedGenreId(id)
    setSearchResults([])
    setQuery('')
    setPage(1)
    setHasMore(false)
  }

  function handleTypeChange(type: FilterType) {
    setFilterType(type)
    setSelectedGenreId(null)
    setPage(1)
    setHasMore(false)
    if (searchMode === 'titles') {
      setLoading(true)
      setSearchResults([])
      setQuery('')
    }
    // People: re-filters via personCredits memo; Studios: re-fetches via effect
  }

  async function handleLoadMore() {
    setLoadingMore(true)
    const nextPage = page + 1
    try {
      let newResults: SearchResult[] = []
      if (selectedGenreId !== null) {
        const mediaType: MediaType = filterType === 'all' ? 'movie' : filterType
        newResults = await fetchDiscover(mediaType, selectedGenreId, nextPage)
        setDiscoverResults((prev) => [...prev, ...newResults])
      } else {
        newResults = await fetchTrending(filterType, nextPage)
        setTrending((prev) => [...prev, ...newResults])
      }
      setPage(nextPage)
      setHasMore(newResults.length > 0)
    } catch (err) {
      console.error('load more error:', err)
    } finally {
      setLoadingMore(false)
    }
  }

  // ── Derived display state ──

  const inPickerMode =
    (searchMode === 'people' && !selectedPerson) ||
    (searchMode === 'studios' && !selectedCompany)

  const displayResults = (() => {
    if (searchMode === 'titles') {
      if (query.trim()) return searchResults
      if (selectedGenreId !== null) return discoverResults
      return trending
    }
    if (searchMode === 'people') return selectedPerson ? personCredits : []
    return selectedCompany ? companyTitles : []
  })()

  const sectionLabel = (() => {
    if (searchMode === 'people') {
      if (selectedPerson) return `${selectedPerson.name} · Filmography`
      if (query.trim()) return `People matching "${query}"`
      return 'Search for a person'
    }
    if (searchMode === 'studios') {
      if (selectedCompany) return selectedCompany.name
      if (query.trim()) return `Studios matching "${query}"`
      return 'Search for a studio'
    }
    if (query.trim()) return `Results for "${query}"`
    if (selectedGenreId !== null) return genres.find((g) => g.id === selectedGenreId)?.name ?? 'Genre'
    return 'Trending This Week'
  })()

  const discoverDelays = (() => {
    const MAX = 24
    const n = Math.min(displayResults.length, MAX)
    const slots = Array.from({ length: n }, (_, i) => i * 15)
    for (let i = slots.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [slots[i], slots[j]] = [slots[j], slots[i]]
    }
    return [...slots, ...new Array(Math.max(0, displayResults.length - MAX)).fill(0)]
  })()

  const selectedIsOwned = selectedResult?.tmdbId != null && libraryTmdbIds.has(selectedResult.tmdbId)
  const showBack = (searchMode === 'people' && !!selectedPerson) || (searchMode === 'studios' && !!selectedCompany)

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 py-6 sm:py-8">
      {/* Page header — centered */}
      <div className="flex items-center justify-center gap-3 mb-5">
        <Compass className="w-5 h-5 text-amber shrink-0" />
        <div>
          <h1 className="font-serif text-2xl font-light text-paper leading-none">Discover</h1>
          <p className="font-mono text-[11px] text-paper-faint mt-1 tracking-wide">
            Explore movies &amp; shows not in your library
          </p>
        </div>
      </div>

      {/* Search — centered */}
      <div className="relative mb-3 max-w-xl mx-auto">
        {loading && query.trim() ? (
          <div className="absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none">
            <div className="w-5 h-5 border-2 border-amber/20 border-t-amber/70 rounded-full animate-spin" />
          </div>
        ) : (
          <Search
            className={cn(
              'absolute left-3.5 top-1/2 -translate-y-1/2 w-5 h-5 pointer-events-none transition-colors duration-150',
              isSearchFocused ? 'text-amber/60' : 'text-paper-faint',
            )}
          />
        )}
        <input
          ref={inputRef}
          type="text"
          aria-label={SEARCH_PLACEHOLDERS[searchMode]}
          placeholder={SEARCH_PLACEHOLDERS[searchMode]}
          value={query}
          onChange={(e) => handleSearch(e.target.value)}
          onFocus={() => setIsSearchFocused(true)}
          onBlur={() => setIsSearchFocused(false)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              if (query) clearSearch()
              else inputRef.current?.blur()
            }
          }}
          className="w-full h-12 pl-11 pr-10 rounded-xl border text-base font-sans text-paper placeholder:text-paper-faint focus:outline-none focus:ring-2 focus:ring-amber/30 transition-all duration-150"
          style={{
            background: 'var(--inset)',
            borderColor: isSearchFocused ? 'rgba(233,178,102,0.35)' : 'var(--line)',
          }}
        />
        {query && (
          <button
            onClick={clearSearch}
            aria-label="Clear search"
            className="absolute right-3.5 top-1/2 -translate-y-1/2 text-paper-faint hover:text-paper transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        )}
      </div>

      {/* Search mode: Titles / People / Studios */}
      <div className="flex gap-1 mb-3 justify-center">
        {([
          { id: 'titles' as SearchMode, label: 'Titles', Icon: Film },
          { id: 'people' as SearchMode, label: 'People', Icon: User },
          { id: 'studios' as SearchMode, label: 'Studios', Icon: Building2 },
        ]).map(({ id, label, Icon }) => (
          <button
            key={id}
            onClick={() => handleSearchModeChange(id)}
            className={cn(
              'flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-mono border transition-colors',
              searchMode === id
                ? 'bg-amber/15 border-amber/40 text-amber-bright'
                : 'border-[var(--line)] text-paper-faint hover:text-paper hover:border-paper-faint/30'
            )}
          >
            <Icon className="w-3 h-3" />
            {label}
          </button>
        ))}
      </div>

      {/* Movie / TV / All toggle — centered */}
      <div className="flex gap-1 mb-4 justify-center">
        {([
          { id: 'all' as FilterType, label: 'All' },
          { id: 'movie' as FilterType, label: 'Movies' },
          { id: 'tv' as FilterType, label: 'TV Shows' },
        ] as const).map(({ id, label }) => (
          <button
            key={id}
            onClick={() => handleTypeChange(id)}
            className={cn(
              'px-3 py-1.5 rounded-full text-xs font-mono border transition-colors',
              filterType === id
                ? 'bg-amber/15 border-amber/40 text-amber-bright'
                : 'border-[var(--line)] text-paper-faint hover:text-paper hover:border-paper-faint/30'
            )}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Genre chips — titles mode only, hidden when search is active */}
      {searchMode === 'titles' && !query.trim() && (
        <div
          className="flex gap-2 overflow-x-auto pb-2 mb-6 scrollbar-thin"
          role="group"
          aria-label="Filter by genre"
        >
          <button
            onClick={() => handleGenreSelect(null)}
            className={cn(
              'shrink-0 px-3 py-1.5 rounded-full text-xs font-mono border transition-colors',
              selectedGenreId === null
                ? 'bg-amber/15 border-amber/40 text-amber-bright'
                : 'border-[var(--line)] text-paper-faint hover:text-paper hover:border-paper-faint/30'
            )}
          >
            All
          </button>
          {genres.map((genre) => (
            <button
              key={genre.id}
              onClick={() => handleGenreSelect(genre.id)}
              className={cn(
                'shrink-0 px-3 py-1.5 rounded-full text-xs font-mono border transition-colors',
                selectedGenreId === genre.id
                  ? 'bg-amber/15 border-amber/40 text-amber-bright'
                  : 'border-[var(--line)] text-paper-faint hover:text-paper hover:border-paper-faint/30'
              )}
            >
              {genre.name}
            </button>
          ))}
        </div>
      )}

      {/* Section label */}
      <div className="flex items-baseline justify-between mb-4">
        <div className="flex items-center gap-1.5">
          {showBack && (
            <button
              onClick={clearSearch}
              aria-label="Back to search"
              className="text-paper-faint hover:text-paper transition-colors"
            >
              <ChevronLeft className="w-3.5 h-3.5" />
            </button>
          )}
          <h2 className="font-mono text-[11px] tracking-[0.12em] uppercase text-paper-faint">
            {sectionLabel}
          </h2>
        </div>
        {!loading && !inPickerMode && displayResults.length > 0 && (
          <span className="font-mono text-[10px] text-paper-faint/60">
            {displayResults.length} title{displayResults.length !== 1 ? 's' : ''}
          </span>
        )}
      </div>

      {/* Main content */}
      {inPickerMode ? (
        query.trim() ? (
          loading ? (
            <div className="max-w-lg mx-auto space-y-2">
              {Array.from({ length: 4 }, (_, i) => (
                <div key={i} className="h-16 rounded-lg animate-pulse" style={{ background: 'var(--inset)' }} />
              ))}
            </div>
          ) : searchMode === 'people' && personResults.length > 0 ? (
            <PersonPicker persons={personResults} onSelect={handlePersonSelect} />
          ) : searchMode === 'studios' && companyResults.length > 0 ? (
            <CompanyPicker companies={companyResults} onSelect={handleCompanySelect} />
          ) : (
            <div className="py-16 text-center flex flex-col items-center">
              {searchMode === 'people'
                ? <User className="w-10 h-10 text-paper-faint/30 mx-auto mb-3" />
                : <Building2 className="w-10 h-10 text-paper-faint/30 mx-auto mb-3" />}
              <p className="font-mono text-sm text-paper-faint mb-4">No results found.</p>
              <button
                onClick={clearSearch}
                className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber mx-auto"
              >
                <X className="w-3.5 h-3.5" />
                Clear search
              </button>
            </div>
          )
        ) : (
          <div className="py-16 text-center">
            {searchMode === 'people' ? (
              <>
                <User className="w-10 h-10 text-paper-faint/20 mx-auto mb-3" />
                <p className="font-mono text-sm text-paper-faint">Type a name to browse by actor, director, or crew.</p>
              </>
            ) : (
              <>
                <Building2 className="w-10 h-10 text-paper-faint/20 mx-auto mb-3" />
                <p className="font-mono text-sm text-paper-faint">Type a studio name to browse their catalog.</p>
              </>
            )}
          </div>
        )
      ) : loading ? (
        <DiscoverSkeleton />
      ) : displayResults.length === 0 ? (
        <div className="py-16 text-center flex flex-col items-center">
          <Compass className="w-10 h-10 text-paper-faint/30 mx-auto mb-3" />
          <p className="font-mono text-sm text-paper-faint mb-4">
            {query.trim() ? 'No results found.' : 'Nothing to show yet.'}
          </p>
          {query.trim() && (
            <button
              onClick={clearSearch}
              className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber mx-auto"
            >
              <X className="w-3.5 h-3.5" />
              Clear search
            </button>
          )}
        </div>
      ) : (
        <div className="discover-grid grid grid-cols-3 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6 xl:grid-cols-7 gap-3">
          {displayResults.map((result, i) => (
            <DiscoverCard
              key={`${result.type}-${result.tmdbId}`}
              result={result}
              isOwned={result.tmdbId != null && libraryTmdbIds.has(result.tmdbId)}
              isSharedView={isSharedView}
              onAdd={openAddTitlePreselected}
              onSelect={setSelectedResult}
              style={{ ['--poster-delay' as string]: `${discoverDelays[i]}ms` }}
            />
          ))}
        </div>
      )}

      {/* Load more */}
      {searchMode === 'titles' && !query.trim() && !loading && displayResults.length > 0 && hasMore && (
        <div className="flex justify-center mt-8">
          <button
            onClick={handleLoadMore}
            disabled={loadingMore}
            className="px-6 py-2.5 rounded-lg font-mono text-sm border transition-colors hover:border-paper-faint/40 hover:text-paper disabled:opacity-50 disabled:cursor-not-allowed"
            style={{ borderColor: 'var(--line)', color: 'var(--paper-faint)' }}
          >
            {loadingMore ? (
              <span className="flex items-center gap-2">
                <span className="inline-block w-3.5 h-3.5 border-2 border-current border-t-transparent rounded-full animate-spin" />
                Loading…
              </span>
            ) : (
              'Load more'
            )}
          </button>
        </div>
      )}

      {/* Detail modal */}
      <DiscoverDetailModal
        result={selectedResult}
        isOwned={selectedIsOwned}
        isSharedView={isSharedView}
        onClose={() => setSelectedResult(null)}
        onAdd={openAddTitlePreselected}
      />
    </div>
  )
}
