import { isCatalogTitleOwned, libraryCatalogKeys } from 'src/lib/catalogIdentity'
import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { Search, Compass, X, User, Building2, ChevronLeft, ChevronRight, ChevronDown, SlidersHorizontal, type LucideIcon } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import {
  searchMedia, fetchTrending, fetchDiscover,
  searchPersons, fetchPersonCredits, searchCompanies, fetchCompanyTitles, fetchRecommendations,
  MOVIE_GENRES, TV_GENRES,
  type SearchResult, type PersonResult, type CompanyResult,
} from 'src/lib/media'
import type { MediaType } from 'src/store/mockData'
import { cn, staggerDelays } from 'src/lib/utils'
import { DiscoverDetailModal } from 'src/components/DiscoverDetailModal'
import { Chip } from 'src/components/ui/chip'
import { useClickOutside } from 'src/lib/useClickOutside'
import { Eyebrow } from 'src/components/ui/typography'
import { DiscoverCarousel } from './discover/DiscoverCarousel'
import { CarouselPauseButton } from './discover/CarouselPauseButton'
import { TasteDropdown } from './discover/TasteDropdown'

// ─── Types ────────────────────────────────────────────────────────────────────

type FilterType = 'all' | MediaType
type SearchMode = 'titles' | 'people' | 'studios'

const SEARCH_PLACEHOLDERS: Record<SearchMode, string> = {
  titles: 'Search movies & TV shows…',
  people: 'Search by actor, director, crew…',
  studios: 'Search by studio or company…',
}

// ─── Empty state — no-results / prompt states across all three search modes ───

function DiscoverEmptyState({
  icon: Icon,
  message,
  onClearSearch,
  dim = false,
}: {
  icon: LucideIcon
  message: string
  onClearSearch?: () => void
  dim?: boolean
}) {
  return (
    <div className="py-16 text-center flex flex-col items-center">
      <Icon className={cn('w-10 h-10 mx-auto mb-3 text-paper-faint', dim ? 'opacity-20' : 'opacity-30')} />
      <p className={cn('font-mono text-sm text-paper-faint', onClearSearch && 'mb-4')}>{message}</p>
      {onClearSearch && (
        <button
          onClick={onClearSearch}
          className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber mx-auto rounded-sm focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
        >
          <X className="w-3.5 h-3.5" aria-hidden="true" />
          Clear search
        </button>
      )}
    </div>
  )
}

// ─── Loading skeleton ─────────────────────────────────────────────────────────

function DiscoverSkeleton() {
  return (
    <div className="-mx-4 sm:-mx-8 px-4 sm:px-8" aria-hidden>
      <div className="film-strip">
        <div className="reel-strip reel-strip--top" />
        <div className="reel-strip reel-strip--bottom" />
        <div className="discover-grid flex">
          {Array.from({ length: 10 }, (_, i) => (
            <div key={i} className="film-frame shrink-0 w-[38vw] sm:w-[170px] md:w-[185px]">
              <div className="film-frame__window aspect-2/3">
                <div className="w-full h-full rounded-[1px] animate-pulse" style={{ background: 'var(--inset)' }} />
              </div>
              <div className="film-frame__caption px-1.5 py-1.5">
                <div className="h-2.5 w-2/3 rounded animate-pulse" style={{ background: 'var(--inset)' }} />
              </div>
            </div>
          ))}
        </div>
      </div>
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

// ─── Discover view ────────────────────────────────────────────────────────────

function appendCatalogResults(previous: SearchResult[], incoming: SearchResult[]): SearchResult[] {
  const seen = new Set<string>()
  return [...previous, ...incoming].filter((result) => {
    const key = `${result.type}:${result.tmdbId}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

export function Discover() {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const titles = useAppStore((s) => s.titles)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const openAddTitlePreselected = useAppStore((s) => s.openAddTitlePreselected)

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
  const moreRequestRef = useRef<symbol | null>(null)
  const cancelLoadMore = useCallback(() => {
    moreRequestRef.current = null
    setLoadingMore(false)
  }, [])
  useEffect(() => () => { moreRequestRef.current = null }, [])
  const searchTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const searchRequestRef = useRef<symbol | null>(null)
  const cancelSearch = useCallback(() => {
    searchRequestRef.current = null
    if (searchTimerRef.current) clearTimeout(searchTimerRef.current)
    searchTimerRef.current = null
  }, [])
  useEffect(() => () => cancelSearch(), [cancelSearch])
  const inputRef = useRef<HTMLInputElement>(null)
  const [genresExpanded, setGenresExpanded] = useState(true)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const filterPanelRef = useRef<HTMLDivElement>(null)

  // Sticky pause per carousel, owned here so the toggle can live in each
  // section's header next to the title / View-more link (KP-044).
  const [mainPaused, setMainPaused] = useState(false)
  const [becauseWatchedPaused, setBecauseWatchedPaused] = useState(false)
  const [moreStarringPaused, setMoreStarringPaused] = useState(false)

  // Movie and television IDs are separate TMDB namespaces.
  const ownedCatalogKeys = useMemo(
    () => libraryCatalogKeys(titles),
    [titles]
  )

  // ── "Because You Watched" — real TMDB recommendations keyed off a library title ──
  const [becauseWatchedOverrideId, setBecauseWatchedOverrideId] = useState<string | null>(null)
  const [becauseWatchedResults, setBecauseWatchedResults] = useState<SearchResult[]>([])
  const [loadedBecauseWatchedId, setLoadedBecauseWatchedId] = useState<string | null>(null)

  // ── "More starring" — real TMDB filmography via fetchPersonCredits, keyed off library cast ──
  const [moreStarringOverridePersonId, setMoreStarringOverridePersonId] = useState<number | null>(null)
  const [moreStarringResults, setMoreStarringResults] = useState<SearchResult[]>([])
  const [loadedMoreStarringPersonId, setLoadedMoreStarringPersonId] = useState<number | null>(null)

  // Close the filter popover on outside click
  useClickOutside(filterPanelRef, () => setFiltersOpen(false), filtersOpen)

  // Basis defaults to the first library title unless the user picked one explicitly
  const becauseWatchedId = becauseWatchedOverrideId ?? (titles.length > 0 ? titles[0].id : null)

  // ⚡ Bolt: Memoize expensive O(N) array mapping to prevent unnecessary object allocations on every render
  const becauseWatchedOptions = useMemo(() => titles.map((t) => ({ id: t.id, label: t.title })), [titles])

  const castOptions = useMemo(() => {
    const seen = new Map<number, string>()
    for (const t of titles) {
      for (const c of t.cast ?? []) {
        if (!seen.has(c.tmdbPersonId)) seen.set(c.tmdbPersonId, c.name)
      }
    }
    return Array.from(seen, ([id, name]) => ({ id, name })).sort((a, b) => a.name.localeCompare(b.name))
  }, [titles])

  // Basis defaults to the first known cast member unless the user picked one explicitly
  const moreStarringPersonId = moreStarringOverridePersonId ?? (castOptions.length > 0 ? castOptions[0].id : null)

  // ⚡ Bolt: Memoize expensive O(N) array mapping to prevent unnecessary object allocations on every render
  const moreStarringOptions = useMemo(() => castOptions.map((c) => ({ id: String(c.id), label: c.name })), [castOptions])

  // Derived rather than tracked separately: loading exactly while we have a basis whose
  // credits haven't landed yet (mirrors DiscoverDetailModal's `hydrating` derivation above).
  const moreStarringLoading = moreStarringPersonId !== null && moreStarringPersonId !== loadedMoreStarringPersonId

  // Real filmography lookup — unlike "because you watched" this is fully wired to TMDB
  useEffect(() => {
    if (moreStarringPersonId === null) return
    let cancelled = false
    fetchPersonCredits(moreStarringPersonId)
      .then((credits) => {
        if (cancelled) return
        setMoreStarringResults(credits.filter((r) => !isCatalogTitleOwned(ownedCatalogKeys, r)))
        setLoadedMoreStarringPersonId(moreStarringPersonId)
      })
      .catch((err) => {
        console.error('more starring credits error:', err)
        if (cancelled) return
        setMoreStarringResults([])
        setLoadedMoreStarringPersonId(moreStarringPersonId)
      })
    return () => { cancelled = true }
  }, [moreStarringPersonId, ownedCatalogKeys])

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
      .then((data) => { if (!cancelled) { setTrending(appendCatalogResults([], data)); setPage(1); setHasMore(data.length > 0); setLoading(false) } })
      .catch((err) => { console.error('fetchTrending error:', err); if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [filterType, query, selectedGenreId, searchMode])

  // Genre results — titles mode, genre selected, no query
  useEffect(() => {
    if (searchMode !== 'titles' || selectedGenreId === null || query.trim()) return
    let cancelled = false
    fetchDiscover(filterType, selectedGenreId)
      .then((data) => { if (!cancelled) { setDiscoverResults(appendCatalogResults([], data)); setPage(1); setHasMore(data.length > 0); setLoading(false) } })
      .catch((err) => { console.error('fetchDiscover error:', err); if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [filterType, selectedGenreId, query, searchMode])

  // Company titles — re-fetch when type filter changes while a company is selected
  // (the loading flag for this case is set by handleTypeChange, which triggers this effect)
  useEffect(() => {
    if (searchMode !== 'studios' || !selectedCompany) return
    let cancelled = false
    fetchCompanyTitles(selectedCompany.id, filterType)
      .then((data) => { if (!cancelled) { setCompanyTitles(data); setLoading(false) } })
      .catch((err) => { console.error('company titles error:', err); if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [selectedCompany, filterType, searchMode])

  // ── Handlers ──

  const handleSearch = useCallback((value: string) => {
    cancelLoadMore()
    cancelSearch()
    setQuery(value)
    // Typing resets any active person/company selection back to picker mode
    setSelectedPerson(null)
    setAllPersonCredits([])
    setSelectedCompany(null)
    setCompanyTitles([])
    if (!value.trim()) {
      if (searchMode === 'titles') setLoading(true)
      setSearchResults([])
      setPersonResults([])
      setCompanyResults([])
      return
    }
    setLoading(true)
    const request = Symbol('search')
    searchRequestRef.current = request
    searchTimerRef.current = setTimeout(async () => {
      if (searchRequestRef.current !== request) return
      searchTimerRef.current = null
      try {
        if (searchMode === 'titles') {
          const all = await searchMedia(value)
          if (searchRequestRef.current !== request) return
          const filtered = filterType === 'all' ? all : all.filter((r) => r.type === filterType)
          setSearchResults(filtered)
        } else if (searchMode === 'people') {
          const results = await searchPersons(value)
          if (searchRequestRef.current !== request) return
          setPersonResults(results)
        } else {
          const results = await searchCompanies(value)
          if (searchRequestRef.current !== request) return
          setCompanyResults(results)
        }
      } catch (err) {
        if (searchRequestRef.current !== request) return
        console.error('search error:', err)
        setSearchResults([])
        setPersonResults([])
        setCompanyResults([])
      } finally {
        if (searchRequestRef.current === request) setLoading(false)
      }
    }, 400)
  }, [filterType, searchMode, cancelLoadMore, cancelSearch])

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
    cancelLoadMore()
    cancelSearch()
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
    cancelLoadMore()
    cancelSearch()
    if (searchMode === 'titles') setLoading(true)
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
    if (id === selectedGenreId && !query.trim()) return
    cancelLoadMore()
    cancelSearch()
    setLoading(true)
    setSelectedGenreId(id)
    setSearchResults([])
    setQuery('')
    setPage(1)
    setHasMore(false)
  }

  function handleTypeChange(type: FilterType) {
    if (type === filterType && (searchMode !== 'titles' || !query.trim())) return
    cancelLoadMore()
    cancelSearch()
    setFilterType(type)
    setSelectedGenreId(null)
    setPage(1)
    setHasMore(false)
    if (searchMode === 'titles') {
      setLoading(true)
      setSearchResults([])
      setQuery('')
    } else if (searchMode === 'studios' && selectedCompany) {
      setLoading(true)
    }
    // People: re-filters via personCredits memo
  }

  async function handleLoadMore() {
    if (moreRequestRef.current || loading || !hasMore || searchMode !== 'titles' || query.trim()) return
    const request = Symbol('browse-page')
    moreRequestRef.current = request
    setLoadingMore(true)
    const nextPage = page + 1
    try {
      let newResults: SearchResult[] = []
      if (selectedGenreId !== null) {
        newResults = await fetchDiscover(filterType, selectedGenreId, nextPage)
        if (moreRequestRef.current !== request) return
        setDiscoverResults((prev) => appendCatalogResults(prev, newResults))
      } else {
        newResults = await fetchTrending(filterType, nextPage)
        if (moreRequestRef.current !== request) return
        setTrending((prev) => appendCatalogResults(prev, newResults))
      }
      setPage(nextPage)
      setHasMore(newResults.length > 0)
    } catch (err) {
      console.error('load more error:', err)
    } finally {
      if (moreRequestRef.current === request) {
        moreRequestRef.current = null
        setLoadingMore(false)
      }
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

  const discoverDelays = useMemo(() => staggerDelays(displayResults.length), [displayResults.length])

  const becauseWatchedTitle = useMemo(
    () => titles.find((t) => t.id === becauseWatchedId) ?? null,
    [titles, becauseWatchedId]
  )

  // Titles without a TMDB link (tmdbId 0 on manual/migrated adds) have nothing
  // to seed the feed with — derived here rather than cleared from the effect.
  const becauseWatchedSeedable = Boolean(becauseWatchedTitle?.tmdbId)

  // Derived rather than tracked separately — mirrors moreStarringLoading above.
  const becauseWatchedLoading =
    becauseWatchedId !== null && becauseWatchedSeedable && becauseWatchedId !== loadedBecauseWatchedId

  // Real similar-title picks from TMDB's /movie|tv/{id}/recommendations, via the
  // media-proxy `recommendations` action.
  useEffect(() => {
    if (!becauseWatchedTitle?.tmdbId) return
    const { id, tmdbId, type } = becauseWatchedTitle
    let cancelled = false
    fetchRecommendations(tmdbId, type)
      .then((recs) => {
        if (cancelled) return
        setBecauseWatchedResults(recs.filter((r) => !isCatalogTitleOwned(ownedCatalogKeys, r)))
        setLoadedBecauseWatchedId(id)
      })
      .catch((err) => {
        console.error('recommendations error:', err)
        if (cancelled) return
        setBecauseWatchedResults([])
        setLoadedBecauseWatchedId(id)
      })
    return () => { cancelled = true }
  }, [becauseWatchedTitle, ownedCatalogKeys])

  // Hide stale results whenever the current basis isn't the loaded one (or can't
  // be seeded at all) — mirrors visibleMoreStarringResults below. Filtered by
  // filterType client-side (like personCredits above) since fetchRecommendations
  // is keyed to the seed title's own type, not the active type filter.
  const visibleBecauseWatchedResults = useMemo(() => {
    if (!(becauseWatchedSeedable && becauseWatchedId === loadedBecauseWatchedId)) return []
    return filterType === 'all' ? becauseWatchedResults : becauseWatchedResults.filter((r) => r.type === filterType)
  }, [becauseWatchedSeedable, becauseWatchedId, loadedBecauseWatchedId, becauseWatchedResults, filterType])

  const becauseWatchedDelays = useMemo(() => staggerDelays(visibleBecauseWatchedResults.length), [visibleBecauseWatchedResults.length])

  // Hide stale results the moment the basis reverts to no selection (e.g. an empty library)
  // rather than clearing them from an effect. Filtered by filterType client-side — same
  // reasoning as visibleBecauseWatchedResults above.
  const visibleMoreStarringResults = useMemo(() => {
    if (moreStarringPersonId === null) return []
    return filterType === 'all' ? moreStarringResults : moreStarringResults.filter((r) => r.type === filterType)
  }, [moreStarringPersonId, moreStarringResults, filterType])

  const moreStarringDelays = useMemo(() => staggerDelays(visibleMoreStarringResults.length), [visibleMoreStarringResults.length])

  const selectedIsOwned = isCatalogTitleOwned(ownedCatalogKeys, selectedResult)
  const showBack = (searchMode === 'people' && !!selectedPerson) || (searchMode === 'studios' && !!selectedCompany)

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 py-6 sm:py-8">
      {/* Hero heading — centered, same display-title brand identity as the other views */}
      <div className="text-center mb-7">
        <p className="kicker"><span className="dot" /> the acquisitions desk</p>
        <h1 className="display-title text-[clamp(32px,6vw,56px)] mt-3 max-w-xl mx-auto">
          Discover reels for the <em>vault</em>
        </h1>
      </div>

      {/* Search — centered, with filter toggle */}
      <div className="flex items-center gap-2 mb-5 max-w-xl mx-auto">
        <div className="relative flex-1">
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
            className="w-full h-12 pl-11 pr-10 rounded-xl border text-base font-sans text-paper placeholder:text-paper-faint focus:outline-hidden focus:ring-2 focus:ring-amber/30 transition-all duration-150"
            style={{
              background: 'var(--inset)',
              borderColor: isSearchFocused ? 'rgb(var(--amber-rgb) / 0.35)' : 'var(--line)',
            }}
          />
          {query && (
            <button
              onClick={clearSearch}
              aria-label="Clear search"
              className="absolute right-3.5 top-1/2 -translate-y-1/2 text-paper-faint hover:text-paper transition-colors focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm"
            >
              <X className="w-5 h-5" aria-hidden="true" />
            </button>
          )}
        </div>

        {/* Filter icon — toggles the Titles/People/Studios + Genres popover */}
        <div className="relative shrink-0" ref={filterPanelRef}>
          <button
            onClick={() => setFiltersOpen((v) => !v)}
            aria-label="Filters"
            aria-expanded={filtersOpen}
            className={cn(
              'w-12 h-12 rounded-xl border flex items-center justify-center transition-colors',
              filtersOpen || searchMode !== 'titles' || selectedGenreId !== null
                ? 'text-amber border-amber/40 bg-amber/10'
                : 'text-paper-faint border-(--line) hover:text-paper'
            )}
            style={{ background: filtersOpen ? undefined : 'var(--inset)' }}
          >
            <SlidersHorizontal className="w-5 h-5" />
          </button>

          {filtersOpen && (
            <div
              className="absolute right-0 top-[calc(100%+8px)] w-72 rounded-xl border shadow-2xl z-30 p-4 space-y-4 bg-card"
              style={{ borderColor: 'var(--line)' }}
            >
              <div>
                <Eyebrow as="h3" size="md" className="mb-2">Search by</Eyebrow>
                <div className="flex gap-1.5">
                  {([
                    { id: 'titles' as SearchMode, label: 'Titles' },
                    { id: 'people' as SearchMode, label: 'People' },
                    { id: 'studios' as SearchMode, label: 'Studios' },
                  ]).map(({ id, label }) => (
                    <Chip key={id} active={searchMode === id} onClick={() => handleSearchModeChange(id)} className="flex-1 text-center">
                      {label}
                    </Chip>
                  ))}
                </div>
              </div>

              {searchMode === 'titles' && !query.trim() && (
                <div>
                  <button
                    onClick={() => setGenresExpanded((v) => !v)}
                    className="w-full flex items-center justify-between text-paper-faint mb-2 focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm"
                  >
                    <Eyebrow size="md" tone="inherit">Genres</Eyebrow>
                    <ChevronDown className={cn('w-3.5 h-3.5 transition-transform', !genresExpanded && '-rotate-90')} />
                  </button>
                  {genresExpanded && (
                    <div className="space-y-0.5 max-h-56 overflow-y-auto scrollbar-thin" role="radiogroup" aria-label="Filter by genre">
                      <button
                        onClick={() => handleGenreSelect(null)}
                        role="radio"
                        aria-checked={selectedGenreId === null}
                        className="w-full flex items-center gap-2 py-1 text-left text-[13px] font-sans text-paper-faint hover:text-paper transition-colors focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm"
                      >
                        <span
                          className="w-3.5 h-3.5 rounded-full border shrink-0 flex items-center justify-center"
                          style={{ borderColor: selectedGenreId === null ? 'var(--amber)' : 'var(--line)' }}
                        >
                          {selectedGenreId === null && <span className="w-1.5 h-1.5 rounded-full" style={{ background: 'var(--amber)' }} />}
                        </span>
                        All
                      </button>
                      {genres.map((genre) => (
                        <button
                          key={genre.id}
                          onClick={() => handleGenreSelect(genre.id)}
                          role="radio"
                          aria-checked={selectedGenreId === genre.id}
                          className="w-full flex items-center gap-2 py-1 text-left text-[13px] font-sans text-paper-faint hover:text-paper transition-colors focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm"
                        >
                          <span
                            className="w-3.5 h-3.5 rounded-full border shrink-0 flex items-center justify-center"
                            style={{ borderColor: selectedGenreId === genre.id ? 'var(--amber)' : 'var(--line)' }}
                          >
                            {selectedGenreId === genre.id && <span className="w-1.5 h-1.5 rounded-full" style={{ background: 'var(--amber)' }} />}
                          </span>
                          {genre.name}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Format tabs — All / Movies / TV Shows, text style */}
      <div className="flex items-center justify-center gap-6 mb-8">
        {([
          { id: 'all' as FilterType, label: 'Both' },
          { id: 'movie' as FilterType, label: 'Movies' },
          { id: 'tv' as FilterType, label: 'TV Shows' },
        ] as const).map(({ id, label }) => (
          <button
            key={id}
            onClick={() => handleTypeChange(id)}
            className={cn(
              'pb-1.5 border-b-2 font-serif text-lg transition-colors focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60',
              filterType === id
                ? 'text-amber-bright border-amber font-semibold'
                : 'text-paper-faint border-transparent hover:text-paper'
            )}
          >
            {label}
          </button>
        ))}
      </div>

      {/* Main content */}
      <div className="w-full">
        {/* Section label */}
        <div className="flex items-baseline justify-between mb-3">
          <div className="flex items-center gap-1.5">
            {showBack && (
              <button
                onClick={clearSearch}
                aria-label="Back to search"
                className="text-paper-faint hover:text-paper transition-colors focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60 rounded-sm"
              >
                <ChevronLeft className="w-3.5 h-3.5" />
              </button>
            )}
            <h2 className="font-serif text-lg font-semibold text-paper">
              {sectionLabel}
            </h2>
            {!loading && !inPickerMode && displayResults.length > 0 && searchMode === 'titles' && !query.trim() && hasMore && (
              <button
                onClick={handleLoadMore}
                disabled={loadingMore}
                className="flex items-center gap-1 text-xs font-mono text-paper-faint hover:text-amber transition-colors disabled:opacity-50 rounded-sm focus-visible:outline-hidden focus-visible:ring-1 focus-visible:ring-amber/60"
              >
                {loadingMore ? 'Loading…' : 'View more'}
                <ChevronRight className="w-3.5 h-3.5" />
              </button>
            )}
          </div>
          {!loading && !inPickerMode && displayResults.length > 0 && (
            <div className="flex items-center gap-3">
              {!(searchMode === 'titles' && !query.trim() && hasMore) && (
                <span className="font-mono text-[10px] text-paper-faint/60">
                  {displayResults.length} title{displayResults.length !== 1 ? 's' : ''}
                </span>
              )}
              <CarouselPauseButton paused={mainPaused} onToggle={() => setMainPaused((p) => !p)} />
            </div>
          )}
        </div>

          {inPickerMode ? (
            query.trim() ? (
              loading ? (
                <div className="max-w-lg space-y-2">
                  {Array.from({ length: 4 }, (_, i) => (
                    <div key={i} className="h-16 rounded-lg animate-pulse" style={{ background: 'var(--inset)' }} />
                  ))}
                </div>
              ) : searchMode === 'people' && personResults.length > 0 ? (
                <PersonPicker persons={personResults} onSelect={handlePersonSelect} />
              ) : searchMode === 'studios' && companyResults.length > 0 ? (
                <CompanyPicker companies={companyResults} onSelect={handleCompanySelect} />
              ) : (
                <DiscoverEmptyState
                  icon={searchMode === 'people' ? User : Building2}
                  message="No results found."
                  onClearSearch={clearSearch}
                />
              )
            ) : (
              <DiscoverEmptyState
                icon={searchMode === 'people' ? User : Building2}
                message={searchMode === 'people'
                  ? 'Type a name to browse by actor, director, or crew.'
                  : 'Type a studio name to browse their catalog.'}
                dim
              />
            )
          ) : loading ? (
            <DiscoverSkeleton />
          ) : displayResults.length === 0 ? (
            <DiscoverEmptyState
              icon={Compass}
              message={query.trim() ? 'No results found.' : 'Nothing to show yet.'}
              onClearSearch={query.trim() ? clearSearch : undefined}
            />
          ) : (
            <DiscoverCarousel
              results={displayResults}
              ownedCatalogKeys={ownedCatalogKeys}
              isSharedView={isSharedView}
              onAdd={openAddTitlePreselected}
              onSelect={setSelectedResult}
              delays={discoverDelays}
              paused={mainPaused}
            />
          )}
        </div>

        {/* Because You Watched — real TMDB recommendations for a library title */}
        {searchMode === 'titles' && !query.trim() && selectedGenreId === null && titles.length > 0 && (
          <div className="mt-10">
            <div className="flex flex-wrap items-center gap-2.5 mb-3">
              <h2 className="font-serif text-lg font-semibold text-paper">Because You Watched</h2>
              <TasteDropdown
                options={becauseWatchedOptions}
                value={becauseWatchedId}
                onChange={setBecauseWatchedOverrideId}
                ariaLabel="Choose a title to base recommendations on"
              />
              {!becauseWatchedLoading && visibleBecauseWatchedResults.length > 0 && (
                <div className="ml-auto">
                  <CarouselPauseButton paused={becauseWatchedPaused} onToggle={() => setBecauseWatchedPaused((p) => !p)} />
                </div>
              )}
            </div>
            {becauseWatchedLoading ? (
              <div className="flex gap-3 overflow-hidden">
                {Array.from({ length: 6 }, (_, i) => (
                  <div key={i} className="shrink-0 w-[38vw] sm:w-[170px] md:w-[185px] aspect-2/3 rounded-lg animate-pulse" style={{ background: 'var(--inset)' }} />
                ))}
              </div>
            ) : visibleBecauseWatchedResults.length > 0 ? (
              <DiscoverCarousel
                results={visibleBecauseWatchedResults}
                ownedCatalogKeys={ownedCatalogKeys}
                isSharedView={isSharedView}
                onAdd={openAddTitlePreselected}
                onSelect={setSelectedResult}
                delays={becauseWatchedDelays}
                paused={becauseWatchedPaused}
              />
            ) : (
              <p className="font-mono text-xs text-paper-faint py-6">
                No recommendations found for this title — try picking another.
              </p>
            )}
          </div>
        )}

        {/* More starring — real TMDB filmography for a cast member from the library */}
        {searchMode === 'titles' && !query.trim() && selectedGenreId === null && castOptions.length > 0 && (
          <div className="mt-10">
            <div className="flex flex-wrap items-center gap-2.5 mb-3">
              <h2 className="font-serif text-lg font-semibold text-paper">More Starring</h2>
              <TasteDropdown
                options={moreStarringOptions}
                value={moreStarringPersonId != null ? String(moreStarringPersonId) : null}
                onChange={(id) => setMoreStarringOverridePersonId(Number(id))}
                ariaLabel="Choose an actor to see more of their titles"
              />
              {!moreStarringLoading && visibleMoreStarringResults.length > 0 && (
                <div className="ml-auto">
                  <CarouselPauseButton paused={moreStarringPaused} onToggle={() => setMoreStarringPaused((p) => !p)} />
                </div>
              )}
            </div>
            {moreStarringLoading ? (
              <div className="flex gap-3 overflow-hidden">
                {Array.from({ length: 6 }, (_, i) => (
                  <div key={i} className="shrink-0 w-[38vw] sm:w-[170px] md:w-[185px] aspect-2/3 rounded-lg animate-pulse" style={{ background: 'var(--inset)' }} />
                ))}
              </div>
            ) : visibleMoreStarringResults.length > 0 ? (
              <DiscoverCarousel
                results={visibleMoreStarringResults}
                ownedCatalogKeys={ownedCatalogKeys}
                isSharedView={isSharedView}
                onAdd={openAddTitlePreselected}
                onSelect={setSelectedResult}
                delays={moreStarringDelays}
                paused={moreStarringPaused}
              />
            ) : (
              <p className="font-mono text-xs text-paper-faint py-6">
                Nothing else to show — every title with this cast member is already in your archive.
              </p>
            )}
          </div>
        )}

      {/* Detail modal */}
      <DiscoverDetailModal
        result={selectedResult}
        isOwned={selectedIsOwned}
        isSharedView={isSharedView}
        onClose={() => setSelectedResult(null)}
        onAdd={openAddTitlePreselected}
        onBrowsePerson={(person) => {
          handleSearchModeChange('people')
          void handlePersonSelect({ id: person.tmdbPersonId, name: person.name, profileUrl: person.profileUrl, knownFor: '' })
        }}
      />
    </div>
  )
}
