import { isCatalogTitleOwned, libraryCatalogKeys } from 'src/lib/catalogIdentity'
import { useState, useRef, useMemo } from 'react'
import { Search, Compass, X, User, Building2, ChevronLeft, ChevronRight, ChevronDown, SlidersHorizontal } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import type { SearchResult } from 'src/lib/media'
import { cn, staggerDelays } from 'src/lib/utils'
import { DiscoverDetailModal } from 'src/components/DiscoverDetailModal'
import { Chip } from 'src/components/ui/chip'
import { useClickOutside } from 'src/lib/useClickOutside'
import { Eyebrow } from 'src/components/ui/typography'
import { DiscoverCarousel } from './discover/DiscoverCarousel'
import { CarouselPauseButton } from './discover/CarouselPauseButton'
import { RecommendationRailSection } from './discover/RecommendationRailSection'
import { DiscoverEmptyState } from './discover/DiscoverEmptyState'
import { DiscoverSkeleton } from './discover/DiscoverSkeleton'
import { PersonPicker } from './discover/PersonPicker'
import { CompanyPicker } from './discover/CompanyPicker'
import { useDiscoverCatalog, type FilterType, type SearchMode } from './discover/useDiscoverCatalog'
import { useBecauseWatchedRail, useMoreStarringRail } from './discover/useRecommendationRails'

const SEARCH_PLACEHOLDERS: Record<SearchMode, string> = {
  titles: 'Search movies & TV shows…',
  people: 'Search by actor, director, crew…',
  studios: 'Search by studio or company…',
}

// ─── Discover view ────────────────────────────────────────────────────────────

export function Discover() {
  // ⚡ Bolt: Unbatch atomic selectors to remove useShallow overhead
  const titles = useAppStore((s) => s.titles)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const openAddTitlePreselected = useAppStore((s) => s.openAddTitlePreselected)

  const {
    query, filterType, searchMode, selectedGenreId, genres, personResults, companyResults,
    loading, loadingMore, hasMore, inputRef, inPickerMode, displayResults, sectionLabel, showBack,
    handleSearch, handlePersonSelect, handleCompanySelect, handleSearchModeChange,
    clearSearch, handleGenreSelect, handleTypeChange, handleLoadMore,
  } = useDiscoverCatalog()

  // ── Shared ──
  const [selectedResult, setSelectedResult] = useState<SearchResult | null>(null)
  const [isSearchFocused, setIsSearchFocused] = useState(false)
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

  // Close the filter popover on outside click
  useClickOutside(filterPanelRef, () => setFiltersOpen(false), filtersOpen)

  const moreStarring = useMoreStarringRail(titles, ownedCatalogKeys, filterType)

  const discoverDelays = useMemo(() => staggerDelays(displayResults.length), [displayResults.length])

  const becauseWatched = useBecauseWatchedRail(titles, ownedCatalogKeys, filterType)

  const selectedIsOwned = isCatalogTitleOwned(ownedCatalogKeys, selectedResult)

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

        {/* Taste rails — Because You Watched / More Starring, seeded from the library */}
        {searchMode === 'titles' && !query.trim() && selectedGenreId === null && (
          <>
            {becauseWatched.options.length > 0 && (
              <RecommendationRailSection
                heading="Because You Watched"
                rail={becauseWatched}
                ariaLabel="Choose a title to base recommendations on"
                emptyMessage="No recommendations found for this title — try picking another."
                paused={becauseWatchedPaused}
                onTogglePause={() => setBecauseWatchedPaused((p) => !p)}
                ownedCatalogKeys={ownedCatalogKeys}
                isSharedView={isSharedView}
                onAdd={openAddTitlePreselected}
                onSelect={setSelectedResult}
              />
            )}
            {moreStarring.options.length > 0 && (
              <RecommendationRailSection
                heading="More Starring"
                rail={moreStarring}
                ariaLabel="Choose an actor to see more of their titles"
                emptyMessage="Nothing else to show — every title with this cast member is already in your archive."
                paused={moreStarringPaused}
                onTogglePause={() => setMoreStarringPaused((p) => !p)}
                ownedCatalogKeys={ownedCatalogKeys}
                isSharedView={isSharedView}
                onAdd={openAddTitlePreselected}
                onSelect={setSelectedResult}
              />
            )}
          </>
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
