import { useMemo, useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { Search, SlidersHorizontal, X, Film, User, Building2, Languages, LayoutGrid, List, Sparkles, Check } from 'lucide-react'
import { useAppStore, useAllGenres, useAllNetworks, useAllDecades, useAllTags, useAllLanguages } from 'src/store/useAppStore'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { Slider } from 'src/components/ui/slider'
import { BottomSheet } from 'src/components/ui/bottom-sheet'
import { Chip } from 'src/components/ui/chip'
import { EmptyState } from 'src/components/ui/empty-state'
import { cn, languageName, staggerDelays } from 'src/lib/utils'
import { useCopyFeedback } from 'src/lib/useCopyFeedback'
import { buildRecommendationPrompt } from 'src/lib/recommendationPrompt'
import type { Title, WatchStatus, MediaType } from 'src/store/mockData'
import type { SortField, SortDir, ViewMode } from 'src/store/useAppStore'

// ─── Status colors for the ledger list ───────────────────────────────────────

const STATUS_DOT: Record<WatchStatus, string> = {
  watched: 'bg-amber',
  watchlist: 'bg-moon',
  watching: 'bg-amber-bright',
  dropped: 'bg-ember',
}

// ─── Filter Panel ────────────────────────────────────────────────────────────

interface FilterPanelProps {
  open: boolean
  onClose: () => void
  activeFilterCount: number
}

const STATUS_OPTIONS: { value: WatchStatus | 'all'; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'watched', label: 'Watched' },
  { value: 'watchlist', label: 'Watchlist' },
  { value: 'watching', label: 'Watching' },
  { value: 'dropped', label: 'Dropped' },
]

const TYPE_OPTIONS: { value: MediaType | 'all'; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'movie', label: 'Movies' },
  { value: 'tv', label: 'TV Series' },
]

const SORT_OPTIONS: { value: SortField; label: string }[] = [
  { value: 'addedAt', label: 'Date Added' },
  { value: 'title', label: 'Title' },
  { value: 'year', label: 'Year' },
  { value: 'rating', label: 'Rating' },
  { value: 'director', label: 'Director' },
]

function FilterGroup({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <h4 className="font-mono text-[10px] uppercase tracking-[0.18em] text-paper-faint mb-3">
        {label}
      </h4>
      <div className="flex flex-wrap gap-2">{children}</div>
    </div>
  )
}

function FilterPanel({ open, onClose, activeFilterCount }: FilterPanelProps) {
  // ⚡ Bolt: Prevent unnecessary re-renders by using useShallow
  const { filters, setFilter, resetFilters } = useAppStore(
    useShallow((s) => ({
      filters: s.filters,
      setFilter: s.setFilter,
      resetFilters: s.resetFilters,
    }))
  )
  const allGenres = useAllGenres()
  const allNetworks = useAllNetworks()
  const allDecades = useAllDecades()
  const allTags = useAllTags()
  const allLanguages = useAllLanguages()

  return (
    <BottomSheet open={open} onClose={onClose} side="right" title="Refine Collection">
      <div className="space-y-6">
        <div className="flex items-center gap-3 pb-4 border-b" style={{ borderColor: 'var(--line)' }}>
          <SlidersHorizontal className="w-4 h-4 text-amber shrink-0" />
          <span className="font-serif text-xl font-light text-paper">Refine Collection</span>
          {activeFilterCount > 0 && (
            <span className="ml-auto font-mono text-xs text-amber bg-amber/10 border border-amber/20 px-2 py-0.5 rounded-full shrink-0">
              {activeFilterCount} active
            </span>
          )}
        </div>

        <FilterGroup label="Type">
          {TYPE_OPTIONS.map((opt) => (
            <Chip key={opt.value} active={filters.type === opt.value} onClick={() => setFilter('type', opt.value)}>
              {opt.label}
            </Chip>
          ))}
        </FilterGroup>

        <FilterGroup label="Status">
          {STATUS_OPTIONS.map((opt) => (
            <Chip key={opt.value} active={filters.status === opt.value} onClick={() => setFilter('status', opt.value)}>
              {opt.label}
            </Chip>
          ))}
        </FilterGroup>

        <FilterGroup label="Group">
          <Chip active={!filters.groupByFranchise} onClick={() => setFilter('groupByFranchise', false)}>
            None
          </Chip>
          <Chip active={filters.groupByFranchise} onClick={() => setFilter('groupByFranchise', true)}>
            Franchise
          </Chip>
        </FilterGroup>

        <FilterGroup label="Sort By">
          {SORT_OPTIONS.map((opt) => (
            <Chip key={opt.value} active={filters.sortField === opt.value} onClick={() => setFilter('sortField', opt.value)}>
              {opt.label}
            </Chip>
          ))}
        </FilterGroup>
        <div className="flex gap-2 -mt-2">
          <Chip active={filters.sortDir === 'asc'} onClick={() => setFilter('sortDir', 'asc' as SortDir)}>
            Ascending
          </Chip>
          <Chip active={filters.sortDir === 'desc'} onClick={() => setFilter('sortDir', 'desc' as SortDir)}>
            Descending
          </Chip>
        </div>

        <div>
          <h4 className="font-mono text-[10px] uppercase tracking-[0.18em] text-paper-faint mb-3">
            Min Rating:{' '}
            <span className="text-amber">{filters.minRating > 0 ? `★ ${filters.minRating}` : 'Any'}</span>
          </h4>
          <Slider
            value={[filters.minRating]}
            onValueChange={([v]) => setFilter('minRating', v)}
            min={0}
            max={5}
            step={0.5}
            className="w-full"
            aria-label="Minimum rating"
          />
        </div>

        {allGenres.length > 0 && (
          <FilterGroup label="Genres">
            {allGenres.map((g) => (
              <Chip
                key={g}
                active={filters.genres.includes(g)}
                onClick={() => {
                  const next = filters.genres.includes(g)
                    ? filters.genres.filter((x) => x !== g)
                    : [...filters.genres, g]
                  setFilter('genres', next)
                }}
              >
                {g}
              </Chip>
            ))}
          </FilterGroup>
        )}

        {allDecades.length > 0 && (
          <FilterGroup label="Decade">
            {allDecades.map((d) => (
              <Chip
                key={d}
                active={filters.decades.includes(d)}
                onClick={() => {
                  const next = filters.decades.includes(d)
                    ? filters.decades.filter((x) => x !== d)
                    : [...filters.decades, d]
                  setFilter('decades', next)
                }}
              >
                {d}
              </Chip>
            ))}
          </FilterGroup>
        )}

        {allNetworks.length > 0 && (
          <FilterGroup label="Network">
            {allNetworks.map((n) => (
              <Chip
                key={n}
                active={filters.networks.includes(n)}
                onClick={() => {
                  const next = filters.networks.includes(n)
                    ? filters.networks.filter((x) => x !== n)
                    : [...filters.networks, n]
                  setFilter('networks', next)
                }}
              >
                {n}
              </Chip>
            ))}
          </FilterGroup>
        )}

        {allLanguages.length > 1 && (
          <FilterGroup label="Language">
            {allLanguages.map((code) => (
              <Chip
                key={code}
                active={filters.languages.includes(code)}
                onClick={() => {
                  const next = filters.languages.includes(code)
                    ? filters.languages.filter((x) => x !== code)
                    : [...filters.languages, code]
                  setFilter('languages', next)
                }}
              >
                {languageName(code)}
              </Chip>
            ))}
          </FilterGroup>
        )}

        {allTags.length > 0 && (
          <FilterGroup label="Tags">
            {allTags.map((tag) => (
              <Chip
                key={tag}
                active={filters.tags.includes(tag)}
                onClick={() => {
                  const next = filters.tags.includes(tag)
                    ? filters.tags.filter((x) => x !== tag)
                    : [...filters.tags, tag]
                  setFilter('tags', next)
                }}
              >
                {tag}
              </Chip>
            ))}
          </FilterGroup>
        )}

        <button
          onClick={resetFilters}
          className={cn(
            'w-full py-2.5 rounded-lg text-sm font-sans border transition-all focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-amber/60',
            activeFilterCount > 0
              ? 'border-amber/30 text-amber hover:bg-amber/10 hover:border-amber/50'
              : 'border-[var(--line)] text-paper-faint/60 cursor-default'
          )}
          disabled={activeFilterCount === 0}
        >
          {activeFilterCount > 0
            ? `Clear ${activeFilterCount} filter${activeFilterCount !== 1 ? 's' : ''}`
            : 'No active filters'}
        </button>
      </div>
    </BottomSheet>
  )
}

// ─── Empty state ─────────────────────────────────────────────────────────────

function LibraryEmptyState() {
  const isLibraryEmpty = useAppStore((s) => s.titles.length === 0)
  const resetFilters = useAppStore((s) => s.resetFilters)
  const openAddTitle = useAppStore((s) => s.openAddTitle)

  return isLibraryEmpty ? (
    <EmptyState
      Icon={Film}
      title="Your archive is empty."
      subtext="Add your first title to start your collection."
      subtextClassName="mb-6"
      ctaLabel="Add a title"
      onCta={openAddTitle}
    />
  ) : (
    <EmptyState
      Icon={Film}
      title="No titles match the bill."
      subtext="Try a different search or reset the filters."
      ctaLabel="Clear all filters"
      onCta={resetFilters}
      ctaClassName="mt-6"
    />
  )
}

// ─── Poster Wall ─────────────────────────────────────────────────────────────

function PosterWall({ titles }: { titles: Title[] }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)

  const delays = useMemo(() => staggerDelays(titles.length), [titles.length])

  if (titles.length === 0) return <LibraryEmptyState />

  return (
    <div className="poster-wall">
      {titles.map((title, i) => (
        <DynamicPoster
          key={title.id}
          title={title}
          rich
          onClick={() => openDetailDrawer(title.id)}
          style={{ ['--poster-delay' as string]: `${delays[i]}ms` }}
        />
      ))}
    </div>
  )
}

// ─── Ledger List ─────────────────────────────────────────────────────────────

function LedgerList({ titles }: { titles: Title[] }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  if (titles.length === 0) return <LibraryEmptyState />

  return (
    <div
      className="rounded-xl overflow-x-auto"
      style={{
        border: '1px solid var(--line)',
        background: 'linear-gradient(180deg, var(--ink-1), rgba(17,13,11,0.4))',
      }}
    >
      <table className="w-full border-collapse sm:min-w-[640px]">
        <thead>
          <tr>
            {['No.', 'Title', 'Year', 'Status', 'Rating'].map((h, i) => (
              <th
                key={h}
                className={cn(
                  'text-left px-4 py-3.5 font-mono text-[10px] tracking-[0.14em] uppercase font-medium text-paper-faint whitespace-nowrap',
                  i === 2 && 'hidden sm:table-cell'
                )}
                style={{ borderBottom: '1px solid var(--line-2)', background: 'var(--inset)' }}
              >
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {titles.map((title, idx) => (
            <tr
              key={title.id}
              onClick={() => openDetailDrawer(title.id)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  openDetailDrawer(title.id)
                }
              }}
              role="button"
              tabIndex={0}
              aria-label={`View details for ${title.title}`}
              className="cursor-pointer transition-colors hover:bg-[rgba(233,178,102,0.06)] focus:outline-none focus-visible:bg-[rgba(233,178,102,0.1)]"
              style={{ borderBottom: '1px solid var(--line)' }}
            >
              <td className="px-4 py-3 font-mono text-[11px] text-paper-faint w-[52px]">
                {String(idx + 1).padStart(2, '0')}
              </td>
              <td className="px-4 py-3">
                <div className="font-serif text-[17px] font-medium text-paper" style={{ fontVariationSettings: '"opsz" 30' }}>
                  {title.title}
                </div>
                {title.director && (
                  <div className="font-sans text-xs text-paper-faint truncate max-w-[260px]">{title.director}</div>
                )}
              </td>
              <td className="hidden sm:table-cell px-4 py-3 font-mono text-xs text-paper-dim">{title.year}</td>
              <td className="px-4 py-3">
                <span className="inline-flex items-center gap-1.5 font-mono text-[10px] tracking-[0.08em] uppercase text-paper-dim">
                  <span aria-hidden="true" className={cn('w-[7px] h-[7px] rounded-full', STATUS_DOT[title.status])} />
                  {title.status}
                </span>
              </td>
              <td className="px-4 py-3 font-mono text-sm text-amber whitespace-nowrap">
                {title.rating ? `★ ${title.rating}` : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ─── Franchise Grouping ──────────────────────────────────────────────────────

interface FranchiseGroup {
  key: string
  name: string | null  // null = the trailing non-franchise remainder
  titles: Title[]
}

// TMDB collection names read like "The Lord of the Rings Collection" — trim
// the suffix for a cleaner section header.
function franchiseDisplayName(name: string): string {
  return name.replace(/\s+Collection$/i, '')
}

// Sections appear in order of each franchise's first title under the active
// sort; within a franchise, titles always run in release order.
function buildFranchiseGroups(titles: Title[]): FranchiseGroup[] {
  const groups = new Map<string, FranchiseGroup>()
  const standalone: Title[] = []

  for (const t of titles) {
    if (!t.collectionName) {
      standalone.push(t)
      continue
    }
    const key = t.collectionId != null ? String(t.collectionId) : t.collectionName
    const group = groups.get(key)
    if (group) {
      group.titles.push(t)
    } else {
      groups.set(key, { key, name: t.collectionName, titles: [t] })
    }
  }

  const releaseTime = (t: Title) =>
    t.releaseDate ? new Date(t.releaseDate).getTime() : new Date(t.year, 0, 1).getTime()
  for (const group of groups.values()) {
    group.titles.sort((a, b) => releaseTime(a) - releaseTime(b))
  }

  const result = [...groups.values()]
  if (standalone.length > 0) {
    result.push({ key: '__standalone__', name: null, titles: standalone })
  }
  return result
}

function FranchiseSections({ titles, viewMode }: { titles: Title[]; viewMode: ViewMode }) {
  const groups = useMemo(() => buildFranchiseGroups(titles), [titles])

  if (titles.length === 0) return <LibraryEmptyState />

  // Nothing in the current view belongs to a franchise — render flat.
  if (groups.length === 1 && groups[0].name === null) {
    return viewMode === 'grid' ? <PosterWall titles={titles} /> : <LedgerList titles={titles} />
  }

  return (
    <div className="space-y-10">
      {groups.map((g) => (
        <section key={g.key} aria-label={g.name ? franchiseDisplayName(g.name) : 'Standalone titles'}>
          <div className="flex items-baseline gap-3 mb-4">
            <h3 className="font-serif text-xl font-light text-paper whitespace-nowrap">
              {g.name ? franchiseDisplayName(g.name) : 'Standalone'}
            </h3>
            <span className="font-mono text-[11px] tracking-[0.06em] text-paper-faint whitespace-nowrap">
              {g.titles.length} title{g.titles.length !== 1 ? 's' : ''}
            </span>
            <div className="flex-1 h-px self-center" style={{ background: 'var(--line)' }} />
          </div>
          {viewMode === 'grid' ? <PosterWall titles={g.titles} /> : <LedgerList titles={g.titles} />}
        </section>
      ))}
    </div>
  )
}

// ─── Library View ─────────────────────────────────────────────────────────────

export function Library() {
  // ⚡ Bolt: Prevent unnecessary re-renders by using useShallow
  const { titles, filteredTitles, filters, viewMode, setFilter, setViewMode } = useAppStore(
    useShallow((s) => ({
      titles: s.titles,
      filteredTitles: s.filteredTitles,
      filters: s.filters,
      viewMode: s.viewMode,
      setFilter: s.setFilter,
      setViewMode: s.setViewMode,
    }))
  )
  const [filterOpen, setFilterOpen] = useState(false)
  const { copiedId, copy } = useCopyFeedback()
  const copied = copiedId === 'rec-prompt'

  const activeFilterCount = useMemo(() => {
    let count = 0
    if (filters.type !== 'all') count++
    if (filters.status !== 'all') count++
    if (filters.genres.length > 0) count++
    if (filters.networks.length > 0) count++
    if (filters.decades.length > 0) count++
    if (filters.languages.length > 0) count++
    if (filters.tags.length > 0) count++
    if (filters.minRating > 0) count++
    if (filters.person) count++
    if (filters.studio) count++
    if (filters.groupByFranchise) count++
    return count
  }, [filters])

  return (
    <div className="max-w-[1500px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      {/* Controls */}
      <div className="flex flex-wrap items-center gap-3 mb-5">
        <div className="search-field flex-1 min-w-[220px] max-w-[460px]">
          <Search className="w-[18px] h-[18px] text-paper-faint shrink-0" aria-hidden="true" />
          <input
            value={filters.search}
            onChange={(e) => setFilter('search', e.target.value)}
            placeholder="Search title, director, genre…"
            aria-label="Search"
            autoComplete="off"
            spellCheck={false}
          />
          {filters.search && (
            <button
              onClick={() => setFilter('search', '')}
              className="text-paper-faint hover:text-ember"
              aria-label="Clear search"
            >
              <X className="w-[15px] h-[15px]" aria-hidden="true" />
            </button>
          )}
        </div>

        {/* Status segmented control (desktop) */}
        <div className="seg hidden md:flex" role="group" aria-label="Status filter">
          {STATUS_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => setFilter('status', opt.value)}
              className={cn('seg__btn', filters.status === opt.value && 'is-active')}
            >
              {opt.label}
            </button>
          ))}
        </div>

        <div className="flex-1" />

        <div className="hidden sm:flex items-center gap-0.5 seg !p-1">
          <button
            onClick={() => setViewMode('grid')}
            className={cn('icon-btn w-8 h-8', viewMode === 'grid' && '!text-amber-bright bg-amber/12')}
            aria-label="Poster wall"
            title="Poster wall"
          >
            <LayoutGrid className="w-[17px] h-[17px]" />
          </button>
          <button
            onClick={() => setViewMode('list')}
            className={cn('icon-btn w-8 h-8', viewMode === 'list' && '!text-amber-bright bg-amber/12')}
            aria-label="Ledger list"
            title="Ledger list"
          >
            <List className="w-[17px] h-[17px]" />
          </button>
        </div>

        <button
          onClick={() => copy(buildRecommendationPrompt(titles), 'rec-prompt')}
          className={cn(
            'icon-btn h-11 px-3.5 gap-2 border text-sm',
            copied ? '!text-amber !border-amber/40' : 'border-[var(--line)] text-paper-dim'
          )}
          style={{ background: 'var(--inset)' }}
          title="Copy a recommendation prompt for an LLM"
          aria-label="Copy a recommendation prompt for an LLM"
        >
          {copied ? <Check className="w-4 h-4" aria-hidden="true" /> : <Sparkles className="w-4 h-4" aria-hidden="true" />}
          <span className="hidden sm:inline">{copied ? 'Copied!' : 'Get Recs'}</span>
        </button>

        <button
          onClick={() => setFilterOpen(true)}
          className={cn(
            'icon-btn h-11 px-3.5 gap-2 border text-sm',
            activeFilterCount > 0 ? '!text-amber !border-amber/40' : 'border-[var(--line)] text-paper-dim'
          )}
          style={{ background: 'var(--inset)' }}
          aria-label={activeFilterCount > 0 ? `Filters (${activeFilterCount} active)` : 'Filters'}
        >
          <SlidersHorizontal className="w-4 h-4" aria-hidden="true" />
          <span className="hidden sm:inline">Filters</span>
          {activeFilterCount > 0 && (
            <span className="bg-amber text-[color:var(--on-amber)] font-mono text-[11px] rounded-full w-[18px] h-[18px] flex items-center justify-center">
              {activeFilterCount}
            </span>
          )}
        </button>
      </div>

      {/* Status chips (mobile) */}
      <div className="flex md:hidden gap-1.5 overflow-x-auto scrollbar-none mb-4 -mx-1 px-1">
        {STATUS_OPTIONS.map((opt) => (
          <Chip key={opt.value} active={filters.status === opt.value} onClick={() => setFilter('status', opt.value)} className="shrink-0">
            {opt.label}
          </Chip>
        ))}
      </div>

      {/* Active person filter (set by clicking a name in a title's Cast & Crew) */}
      {filters.person && (
        <div className="mb-4">
          <span className="inline-flex items-center gap-2 font-sans text-sm text-amber bg-amber/10 border border-amber/25 rounded-full pl-3 pr-1.5 py-1">
            <User className="w-3.5 h-3.5 shrink-0" />
            <span>
              Featuring <b className="font-medium">{filters.person.name}</b>
            </span>
            <button
              onClick={() => setFilter('person', null)}
              aria-label={`Clear ${filters.person.name} filter`}
              className="rounded-full p-0.5 hover:bg-amber/20 transition-colors"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </span>
        </div>
      )}

      {/* Active language filter (also set by clicking a row in the Ledger's
          "In translation" panel) */}
      {filters.languages.length > 0 && (
        <div className="mb-4">
          <span className="inline-flex items-center gap-2 font-sans text-sm text-amber bg-amber/10 border border-amber/25 rounded-full pl-3 pr-1.5 py-1">
            <Languages className="w-3.5 h-3.5 shrink-0" />
            <span>
              In <b className="font-medium">{filters.languages.map(languageName).join(', ')}</b>
            </span>
            <button
              onClick={() => setFilter('languages', [])}
              aria-label="Clear language filter"
              className="rounded-full p-0.5 hover:bg-amber/20 transition-colors"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </span>
        </div>
      )}

      {/* Active studio filter */}
      {filters.studio && (
        <div className="mb-4">
          <span className="inline-flex items-center gap-2 font-sans text-sm text-amber bg-amber/10 border border-amber/25 rounded-full pl-3 pr-1.5 py-1">
            <Building2 className="w-3.5 h-3.5 shrink-0" />
            <span>
              From <b className="font-medium">{filters.studio}</b>
            </span>
            <button
              onClick={() => setFilter('studio', null)}
              aria-label={`Clear ${filters.studio} studio filter`}
              className="rounded-full p-0.5 hover:bg-amber/20 transition-colors"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </span>
        </div>
      )}

      {/* Result meta */}
      <p className="font-mono text-[11px] tracking-[0.06em] text-paper-faint mb-5">
        <b className="text-amber font-medium">{filteredTitles.length}</b> title
        {filteredTitles.length !== 1 ? 's' : ''} on the bill
      </p>

      {/* Content */}
      <div className="animate-view-in">
        {filters.groupByFranchise ? (
          <FranchiseSections titles={filteredTitles} viewMode={viewMode} />
        ) : viewMode === 'grid' ? (
          <PosterWall titles={filteredTitles} />
        ) : (
          <LedgerList titles={filteredTitles} />
        )}
      </div>

      <FilterPanel open={filterOpen} onClose={() => setFilterOpen(false)} activeFilterCount={activeFilterCount} />
    </div>
  )
}
