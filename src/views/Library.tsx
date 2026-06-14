import { useMemo, useState } from 'react'
import { Search, SlidersHorizontal, X } from 'lucide-react'
import { useAppStore, useAllGenres, useAllNetworks, useAllDecades } from 'src/store/useAppStore'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { StarBadge } from 'src/components/ui/star-rating'
import { Input } from 'src/components/ui/input'
import { Button } from 'src/components/ui/button'
import { Slider } from 'src/components/ui/slider'
import { BottomSheet } from 'src/components/ui/bottom-sheet'
import { TitleDetailDrawer } from 'src/components/TitleDetailDrawer'
import { cn } from 'src/lib/utils'
import type { Title, WatchStatus, MediaType } from 'src/store/mockData'
import type { SortField, SortDir } from 'src/store/useAppStore'

// ─── Filter Panel ────────────────────────────────────────────────────────────

interface FilterPanelProps {
  open: boolean
  onClose: () => void
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
    <button
      onClick={onClick}
      className={cn(
        'px-3 py-1 rounded-full text-xs font-sans border transition-all',
        active
          ? 'bg-amber/20 border-amber/50 text-amber'
          : 'bg-secondary/50 border-border text-muted-foreground hover:text-foreground hover:border-border/80'
      )}
    >
      {children}
    </button>
  )
}

function FilterPanel({ open, onClose }: FilterPanelProps) {
  const { filters, setFilter, resetFilters } = useAppStore()
  const allGenres = useAllGenres()
  const allNetworks = useAllNetworks()
  const allDecades = useAllDecades()

  return (
    <BottomSheet open={open} onClose={onClose} title="Filter & Sort" side="right">
      <div className="space-y-6">
        {/* Type */}
        <div>
          <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">Type</h4>
          <div className="flex flex-wrap gap-2">
            {TYPE_OPTIONS.map((opt) => (
              <Chip
                key={opt.value}
                active={filters.type === opt.value}
                onClick={() => setFilter('type', opt.value)}
              >
                {opt.label}
              </Chip>
            ))}
          </div>
        </div>

        {/* Status */}
        <div>
          <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">Status</h4>
          <div className="flex flex-wrap gap-2">
            {STATUS_OPTIONS.map((opt) => (
              <Chip
                key={opt.value}
                active={filters.status === opt.value}
                onClick={() => setFilter('status', opt.value)}
              >
                {opt.label}
              </Chip>
            ))}
          </div>
        </div>

        {/* Sort */}
        <div>
          <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">Sort By</h4>
          <div className="flex flex-wrap gap-2">
            {SORT_OPTIONS.map((opt) => (
              <Chip
                key={opt.value}
                active={filters.sortField === opt.value}
                onClick={() => setFilter('sortField', opt.value)}
              >
                {opt.label}
              </Chip>
            ))}
          </div>
          <div className="flex gap-2 mt-2">
            <Chip active={filters.sortDir === 'asc'} onClick={() => setFilter('sortDir', 'asc' as SortDir)}>
              Ascending
            </Chip>
            <Chip active={filters.sortDir === 'desc'} onClick={() => setFilter('sortDir', 'desc' as SortDir)}>
              Descending
            </Chip>
          </div>
        </div>

        {/* Min Rating */}
        <div>
          <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">
            Min Rating: <span className="text-amber font-mono">{filters.minRating > 0 ? `★ ${filters.minRating}` : 'Any'}</span>
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

        {/* Genres */}
        {allGenres.length > 0 && (
          <div>
            <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">Genres</h4>
            <div className="flex flex-wrap gap-2">
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
            </div>
          </div>
        )}

        {/* Decades */}
        {allDecades.length > 0 && (
          <div>
            <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">Decade</h4>
            <div className="flex flex-wrap gap-2">
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
            </div>
          </div>
        )}

        {/* Networks */}
        {allNetworks.length > 0 && (
          <div>
            <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">Network</h4>
            <div className="flex flex-wrap gap-2">
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
            </div>
          </div>
        )}

        {/* Reset */}
        <Button
          variant="outline"
          className="w-full border-border text-muted-foreground hover:text-foreground"
          onClick={resetFilters}
        >
          Reset All Filters
        </Button>
      </div>
    </BottomSheet>
  )
}

// ─── Poster Grid ─────────────────────────────────────────────────────────────

function PosterGrid({ titles }: { titles: Title[] }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)

  if (titles.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-24 text-center">
        <div className="text-5xl mb-4">🎬</div>
        <p className="font-serif text-xl text-muted-foreground">No titles found</p>
        <p className="font-sans text-sm text-muted-foreground/60 mt-1">
          Try adjusting your filters
        </p>
      </div>
    )
  }

  return (
    <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-5 lg:grid-cols-6 xl:grid-cols-7 gap-3 md:gap-4">
      {titles.map((title) => (
        <div key={title.id} className="group relative">
          <DynamicPoster
            title={title}
            onClick={() => openDetailDrawer(title.id)}
          />
          {title.rating && (
            <div className="absolute bottom-1.5 left-1.5 z-10 opacity-0 group-hover:opacity-100 transition-opacity">
              <StarBadge rating={title.rating} />
            </div>
          )}
          <div className="mt-1.5 px-0.5">
            <p className="font-sans text-xs text-foreground/80 truncate leading-tight">
              {title.title}
            </p>
            <p className="font-mono text-xs text-muted-foreground">
              {title.year}
            </p>
          </div>
        </div>
      ))}
    </div>
  )
}

// ─── Ledger List ─────────────────────────────────────────────────────────────

const STATUS_COLORS: Record<string, string> = {
  watched: 'text-green-400',
  watchlist: 'text-blue-400',
  watching: 'text-amber',
  dropped: 'text-red-400',
}

function LedgerList({ titles }: { titles: Title[] }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)

  if (titles.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-24 text-center">
        <div className="text-5xl mb-4">🎬</div>
        <p className="font-serif text-xl text-muted-foreground">No titles found</p>
      </div>
    )
  }

  return (
    <div className="divide-y divide-border">
      {/* Header */}
      <div className="grid grid-cols-[2fr_1fr_1fr_1fr] gap-4 px-4 py-2 text-xs font-mono uppercase tracking-widest text-muted-foreground">
        <span>Title</span>
        <span className="hidden sm:block">Year</span>
        <span>Status</span>
        <span>Rating</span>
      </div>
      {titles.map((title) => (
        <button
          key={title.id}
          className="w-full grid grid-cols-[2fr_1fr_1fr_1fr] gap-4 px-4 py-3 items-center hover:bg-secondary/30 transition-colors text-left"
          onClick={() => openDetailDrawer(title.id)}
        >
          <div className="min-w-0">
            <p className="font-sans text-sm text-foreground truncate">{title.title}</p>
            {title.director && (
              <p className="font-sans text-xs text-muted-foreground truncate">{title.director}</p>
            )}
          </div>
          <span className="hidden sm:block font-mono text-sm text-muted-foreground">{title.year}</span>
          <span className={cn('font-mono text-xs capitalize', STATUS_COLORS[title.status] ?? 'text-muted-foreground')}>
            {title.status}
          </span>
          <span className="font-mono text-sm text-amber">
            {title.rating ? `★ ${title.rating}` : '—'}
          </span>
        </button>
      ))}
    </div>
  )
}

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
    if (filters.minRating > 0) count++
    return count
  }, [filters])

  return (
    <div className="flex flex-col h-full">
      {/* Search + Filter Bar */}
      <div className="sticky top-14 z-40 bg-void/80 backdrop-blur-md border-b border-border px-4 py-3">
        <div className="max-w-7xl mx-auto flex items-center gap-2">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground pointer-events-none" />
            <Input
              value={filters.search}
              onChange={(e) => setFilter('search', e.target.value)}
              placeholder="Search titles, directors, genres…"
              className="pl-9 bg-secondary/50 border-border text-foreground placeholder:text-muted-foreground focus-visible:ring-amber/30"
            />
            {filters.search && (
              <button
                onClick={() => setFilter('search', '')}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                <X className="w-4 h-4" />
              </button>
            )}
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setFilterOpen(true)}
            className={cn(
              'gap-1.5 border-border shrink-0',
              activeFilterCount > 0 ? 'text-amber border-amber/40' : 'text-muted-foreground'
            )}
          >
            <SlidersHorizontal className="w-4 h-4" />
            <span className="hidden sm:inline">Filter</span>
            {activeFilterCount > 0 && (
              <span className="bg-amber text-void font-mono text-xs rounded-full w-4 h-4 flex items-center justify-center">
                {activeFilterCount}
              </span>
            )}
          </Button>
        </div>
      </div>

      {/* Count line */}
      <div className="max-w-7xl mx-auto w-full px-4 pt-3 pb-1">
        <p className="font-mono text-xs text-muted-foreground">
          {filteredTitles.length} title{filteredTitles.length !== 1 ? 's' : ''}
        </p>
      </div>

      {/* Content */}
      <div className="flex-1 max-w-7xl mx-auto w-full px-4 pb-24">
        {viewMode === 'grid' ? (
          <PosterGrid titles={filteredTitles} />
        ) : (
          <LedgerList titles={filteredTitles} />
        )}
      </div>

      <FilterPanel open={filterOpen} onClose={() => setFilterOpen(false)} />
      <TitleDetailDrawer />
    </div>
  )
}
