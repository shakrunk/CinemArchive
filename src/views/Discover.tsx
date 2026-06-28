import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { Search, Compass, X, Film, Tv, Check, Plus } from 'lucide-react'
import { useShallow } from 'zustand/react/shallow'
import { useAppStore } from 'src/store/useAppStore'
import { searchMedia, fetchTrending, fetchDiscover, MOVIE_GENRES, TV_GENRES, type SearchResult } from 'src/lib/media'
import type { MediaType } from 'src/store/mockData'
import { cn } from 'src/lib/utils'

// ─── Types ────────────────────────────────────────────────────────────────────

type FilterType = 'all' | MediaType

// ─── DiscoverCard ─────────────────────────────────────────────────────────────

interface DiscoverCardProps {
  result: SearchResult
  isOwned: boolean
  isSharedView: boolean
  onAdd: (result: SearchResult) => void
}

function DiscoverCard({ result, isOwned, isSharedView, onAdd }: DiscoverCardProps) {
  const [imgError, setImgError] = useState(false)

  return (
    <div className="group relative cursor-default">
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
            <button
              onClick={() => onAdd(result)}
              aria-label={`Add ${result.title} to library`}
              className="flex items-center justify-center gap-1 w-full py-1.5 rounded text-[11px] font-bold transition-colors btn-amber"
            >
              <Plus className="w-3 h-3" />
              Add to Library
            </button>
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
              onClick={() => onAdd(result)}
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

// ─── Discover view ────────────────────────────────────────────────────────────

export function Discover() {
  const { titles, isSharedView, openAddTitlePreselected } = useAppStore(
    useShallow((s) => ({
      titles: s.titles,
      isSharedView: s.isSharedView,
      openAddTitlePreselected: s.openAddTitlePreselected,
    }))
  )

  const [query, setQuery] = useState('')
  const [filterType, setFilterType] = useState<FilterType>('all')
  const [selectedGenreId, setSelectedGenreId] = useState<number | null>(null)
  const [trending, setTrending] = useState<SearchResult[]>([])
  const [discoverResults, setDiscoverResults] = useState<SearchResult[]>([])
  const [searchResults, setSearchResults] = useState<SearchResult[]>([])
  // Start loading so skeleton shows while the first trending fetch runs.
  const [loading, setLoading] = useState(true)
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  // Fast owned-title lookup by tmdbId
  const libraryTmdbIds = useMemo(
    () => new Set(titles.map((t) => t.tmdbId).filter((id): id is number => id != null)),
    [titles]
  )

  const genres = filterType === 'tv' ? TV_GENRES : MOVIE_GENRES

  // Load trending when not searching or filtering by genre.
  // setState is only called inside async .then()/.catch() callbacks — not
  // synchronously in the effect body — to satisfy react-hooks/set-state-in-effect.
  useEffect(() => {
    if (query.trim() || selectedGenreId !== null) return
    let cancelled = false
    const type: MediaType | 'all' = filterType
    fetchTrending(type)
      .then((data) => { if (!cancelled) { setTrending(data); setLoading(false) } })
      .catch((err) => { console.error('fetchTrending error:', err); if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [filterType, query, selectedGenreId])

  // Load genre results when a genre is selected.
  useEffect(() => {
    if (selectedGenreId === null || query.trim()) return
    let cancelled = false
    const mediaType: MediaType = filterType === 'all' ? 'movie' : filterType
    fetchDiscover(mediaType, selectedGenreId)
      .then((data) => { if (!cancelled) { setDiscoverResults(data); setLoading(false) } })
      .catch((err) => { console.error('fetchDiscover error:', err); if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [filterType, selectedGenreId, query])

  // Debounced search — called from input onChange (event handler, not effect)
  const handleSearch = useCallback((value: string) => {
    setQuery(value)
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current)
    if (!value.trim()) {
      setSearchResults([])
      return
    }
    setLoading(true)
    searchTimerRef.current = setTimeout(async () => {
      try {
        const all = await searchMedia(value)
        const filtered = filterType === 'all' ? all : all.filter((r) => r.type === filterType)
        setSearchResults(filtered)
      } catch (err) {
        console.error('search error:', err)
        setSearchResults([])
      } finally {
        setLoading(false)
      }
    }, 400)
  }, [filterType])

  function clearSearch() {
    setQuery('')
    setSearchResults([])
    inputRef.current?.focus()
  }

  // Event handlers — setting loading here (not in effects) is allowed.
  function handleGenreSelect(id: number | null) {
    if (id !== null) setLoading(true)
    setSelectedGenreId(id)
    setSearchResults([])
    setQuery('')
  }

  function handleTypeChange(type: FilterType) {
    setLoading(true)
    setFilterType(type)
    setSelectedGenreId(null)
    setSearchResults([])
    setQuery('')
  }

  const displayResults = query.trim()
    ? searchResults
    : selectedGenreId !== null
    ? discoverResults
    : trending

  const sectionLabel = query.trim()
    ? `Results for "${query}"`
    : selectedGenreId !== null
    ? (genres.find((g) => g.id === selectedGenreId)?.name ?? 'Genre')
    : 'Trending This Week'

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 py-6 sm:py-8">
      {/* Page header */}
      <div className="flex items-center gap-3 mb-6">
        <Compass className="w-5 h-5 text-amber shrink-0" />
        <div>
          <h1 className="font-serif text-2xl font-light text-paper leading-none">Discover</h1>
          <p className="font-mono text-[11px] text-paper-faint mt-1 tracking-wide">
            Explore movies &amp; shows not in your library
          </p>
        </div>
      </div>

      {/* Search */}
      <div className="relative mb-4 max-w-lg">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-paper-faint pointer-events-none" />
        <input
          ref={inputRef}
          type="text"
          aria-label="Search movies and TV shows"
          placeholder="Search movies & TV shows…"
          value={query}
          onChange={(e) => handleSearch(e.target.value)}
          className="w-full h-10 pl-9 pr-9 rounded-lg border text-sm font-sans text-paper placeholder:text-paper-faint focus:outline-none focus:ring-2 focus:ring-amber/30 transition-colors"
          style={{ background: 'var(--inset)', borderColor: 'var(--line)' }}
        />
        {query && (
          <button
            onClick={clearSearch}
            aria-label="Clear search"
            className="absolute right-3 top-1/2 -translate-y-1/2 text-paper-faint hover:text-paper transition-colors"
          >
            <X className="w-4 h-4" />
          </button>
        )}
      </div>

      {/* Movie / TV / All toggle */}
      <div className="flex gap-1 mb-4">
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

      {/* Genre chips — hidden when search is active */}
      {!query.trim() && (
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
        <h2 className="font-mono text-[11px] tracking-[0.12em] uppercase text-paper-faint">
          {sectionLabel}
        </h2>
        {!loading && displayResults.length > 0 && (
          <span className="font-mono text-[10px] text-paper-faint/60">
            {displayResults.length} title{displayResults.length !== 1 ? 's' : ''}
          </span>
        )}
      </div>

      {/* Grid */}
      {loading ? (
        <DiscoverSkeleton />
      ) : displayResults.length === 0 ? (
        <div className="py-16 text-center">
          <Compass className="w-10 h-10 text-paper-faint/30 mx-auto mb-3" />
          <p className="font-mono text-sm text-paper-faint">
            {query.trim() ? 'No results found.' : 'Nothing to show yet.'}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6 xl:grid-cols-7 gap-3">
          {displayResults.map((result) => (
            <DiscoverCard
              key={`${result.type}-${result.tmdbId}`}
              result={result}
              isOwned={result.tmdbId != null && libraryTmdbIds.has(result.tmdbId)}
              isSharedView={isSharedView}
              onAdd={openAddTitlePreselected}
            />
          ))}
        </div>
      )}
    </div>
  )
}
