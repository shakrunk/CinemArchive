import { useMemo, useState } from 'react'
import { Search, SlidersHorizontal, X, Film, User, Building2 } from 'lucide-react'
import { useAppStore, useAllGenres, useAllNetworks, useAllDecades, useAllTags } from 'src/store/useAppStore'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { Slider } from 'src/components/ui/slider'
import { BottomSheet } from 'src/components/ui/bottom-sheet'
import { cn } from 'src/lib/utils'
import type { Title, WatchStatus, MediaType } from 'src/store/mockData'
import type { SortField, SortDir } from 'src/store/useAppStore'

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

function Chip({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button onClick={onClick} className={cn('chip', active && 'is-active')}>
      {children}
    </button>
  )
}

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
  const { filters, setFilter, resetFilters } = useAppStore()
  const allGenres = useAllGenres()
  const allNetworks = useAllNetworks()
  const allDecades = useAllDecades()
  const allTags = useAllTags()

  return (
    <BottomSheet open={open} onClose={onClose} side="right">
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
            'w-full py-2.5 rounded-lg text-sm font-sans border transition-all',
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

function EmptyState() {
  const isLibraryEmpty = useAppStore((s) => s.titles.length === 0)

  return (
    <div className="text-center py-24 px-5 text-paper-faint">
      <Film className="w-14 h-14 mx-auto mb-5 text-amber-deep opacity-50" />
      {isLibraryEmpty ? (
        <>
          <p className="font-serif text-2xl text-paper-dim font-light">Your archive is empty.</p>
          <p className="font-sans text-sm mt-2 opacity-70">Add your first title to start your collection.</p>
        </>
      ) : (
        <>
          <p className="font-serif text-2xl text-paper-dim font-light">No titles match the bill.</p>
          <p className="font-sans text-sm mt-2 opacity-70">Try a different search or reset the filters.</p>
        </>
      )}
    </div>
  )
}

// ─── Poster Wall ─────────────────────────────────────────────────────────────

function PosterWall({ titles }: { titles: Title[] }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  if (titles.length === 0) return <EmptyState />

  return (
    <div className="poster-wall">
      {titles.map((title) => (
        <DynamicPoster key={title.id} title={title} rich onClick={() => openDetailDrawer(title.id)} />
      ))}
    </div>
  )
}

// ─── Ledger List ─────────────────────────────────────────────────────────────

function LedgerList({ titles }: { titles: Title[] }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  if (titles.length === 0) return <EmptyState />

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
                style={{ borderBottom: '1px solid var(--line-2)', background: 'rgba(0,0,0,0.25)' }}
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
                  <i className={cn('w-[7px] h-[7px] rounded-full', STATUS_DOT[title.status])} />
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

// ─── Quick Status Filters ─────────────────────────────────────────────────────

const QUICK_STATUS_FILTERS: { value: WatchStatus | 'all'; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'watched', label: 'Watched' },
  { value: 'watching', label: 'Watching' },
  { value: 'watchlist', label: 'Watchlist' },
  { value: 'dropped', label: 'Dropped' },
]

// ─── Library View ─────────────────────────────────────────────────────────────

export function Library() {
  const { filteredTitles, filters, viewMode, setFilter } = useAppStore()
  const [filterOpen, setFilterOpen] = useState(false)

  const activeFilterCount = useMemo(() => {
    let count = 0
    if (filters.type !== 'all') count++
    if (filters.status !== 'all') count++
    if (filters.genres.length > 0) count++
    if (filters.networks.length > 0) count++
    if (filters.decades.length > 0) count++
    if (filters.tags.length > 0) count++
    if (filters.minRating > 0) count++
    if (filters.person) count++
    if (filters.studio) count++
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
          {QUICK_STATUS_FILTERS.map((opt) => (
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

        <button
          onClick={() => setFilterOpen(true)}
          className={cn(
            'icon-btn h-11 px-3.5 gap-2 border text-sm',
            activeFilterCount > 0 ? '!text-amber !border-amber/40' : 'border-[var(--line)] text-paper-dim'
          )}
          style={{ background: 'rgba(0,0,0,0.3)' }}
          aria-label={activeFilterCount > 0 ? `Filters (${activeFilterCount} active)` : 'Filters'}
        >
          <SlidersHorizontal className="w-4 h-4" aria-hidden="true" />
          <span className="hidden sm:inline">Filters</span>
          {activeFilterCount > 0 && (
            <span className="bg-amber text-void font-mono text-[11px] rounded-full w-[18px] h-[18px] flex items-center justify-center">
              {activeFilterCount}
            </span>
          )}
        </button>
      </div>

      {/* Status chips (mobile) */}
      <div className="flex md:hidden gap-1.5 overflow-x-auto scrollbar-none mb-4 -mx-1 px-1">
        {QUICK_STATUS_FILTERS.map((opt) => (
          <button
            key={opt.value}
            onClick={() => setFilter('status', opt.value)}
            className={cn('chip shrink-0', filters.status === opt.value && 'is-active')}
          >
            {opt.label}
          </button>
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
        {viewMode === 'grid' ? <PosterWall titles={filteredTitles} /> : <LedgerList titles={filteredTitles} />}
      </div>

      <FilterPanel open={filterOpen} onClose={() => setFilterOpen(false)} activeFilterCount={activeFilterCount} />
    </div>
  )
}
