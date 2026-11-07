import { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import {
  searchMedia, fetchTrending, fetchDiscover,
  searchPersons, fetchPersonCredits, searchCompanies, fetchCompanyTitles,
  MOVIE_GENRES, TV_GENRES,
  type SearchResult, type PersonResult, type CompanyResult,
} from 'src/lib/media'
import type { MediaType } from 'src/store/mockData'

export type FilterType = 'all' | MediaType
export type SearchMode = 'titles' | 'people' | 'studios'

function appendCatalogResults(previous: SearchResult[], incoming: SearchResult[]): SearchResult[] {
  const seen = new Set<string>()
  return [...previous, ...incoming].filter((result) => {
    const key = `${result.type}:${result.tmdbId}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/** Search, browse, and pagination state behind Discover's main carousel, across
 *  all three search modes. Kept as one hook because the request fences
 *  (`moreRequestRef`, `searchRequestRef`) span every query, mode, genre, and type
 *  change — splitting it would let a stale page or search land in the wrong view. */
export function useDiscoverCatalog() {
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

  const showBack = (searchMode === 'people' && !!selectedPerson) || (searchMode === 'studios' && !!selectedCompany)

  return {
    query, filterType, searchMode, selectedGenreId, genres, personResults, companyResults,
    loading, loadingMore, hasMore, inputRef, inPickerMode, displayResults, sectionLabel, showBack,
    handleSearch, handlePersonSelect, handleCompanySelect, handleSearchModeChange,
    clearSearch, handleGenreSelect, handleTypeChange, handleLoadMore,
  }
}
