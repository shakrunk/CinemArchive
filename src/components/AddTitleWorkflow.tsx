import { useState, useCallback, useRef } from 'react'
import { Search, Film, Tv, Star, Calendar, FileText, ChevronRight, Check } from 'lucide-react'
import { BottomSheet } from 'src/components/ui/bottom-sheet'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { StarRating } from 'src/components/ui/star-rating'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import type { Title, MediaType, WatchStatus, Season } from 'src/store/mockData'

// ─── Types ───────────────────────────────────────────────────────────────────

interface SearchResult {
  tmdbId: number
  type: MediaType
  title: string
  year: number
  posterUrl?: string
  director?: string
  genres: string[]
  synopsis?: string
  runtime?: number
  network?: string
  seasonCount?: number
}

// ─── Mock search (will be replaced in Phase 3 with real Edge Function) ────────

const MOCK_RESULTS: SearchResult[] = [
  {
    tmdbId: 238,
    type: 'movie',
    title: 'The Godfather',
    year: 1972,
    posterUrl: 'https://image.tmdb.org/t/p/w500/3bhkrj58Vtu7enYsLLeHSSa1xZx.jpg',
    director: 'Francis Ford Coppola',
    genres: ['Crime', 'Drama'],
    synopsis: 'Spanning the years 1945 to 1955, a chronicle of the fictional Italian-American Corleone crime family.',
    runtime: 175,
  },
  {
    tmdbId: 372058,
    type: 'movie',
    title: 'Your Name',
    year: 2016,
    director: 'Makoto Shinkai',
    genres: ['Animation', 'Drama', 'Romance'],
    synopsis: 'Two teenagers share a profound, magical connection upon discovering they are swapping bodies.',
    runtime: 106,
  },
  {
    tmdbId: 60625,
    type: 'tv',
    title: 'Rick and Morty',
    year: 2013,
    genres: ['Animation', 'Comedy', 'Science Fiction'],
    synopsis: 'An animated series following a sociopathic scientist and his grandson.',
    network: 'Adult Swim',
    seasonCount: 7,
  },
]

function useDebouncedSearch(delay = 400) {
  const [results, setResults] = useState<SearchResult[]>([])
  const [loading, setLoading] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const search = useCallback((query: string) => {
    if (timerRef.current) clearTimeout(timerRef.current)
    if (!query.trim()) {
      setResults([])
      setLoading(false)
      return
    }
    setLoading(true)
    timerRef.current = setTimeout(() => {
      const q = query.toLowerCase()
      const filtered = MOCK_RESULTS.filter(
        (r) => r.title.toLowerCase().includes(q) || r.director?.toLowerCase().includes(q)
      )
      setResults(filtered)
      setLoading(false)
    }, delay)
  }, [delay])

  return { results, loading, search }
}

// ─── TV Season Editor ─────────────────────────────────────────────────────────

interface SeasonEditorProps {
  seasons: Season[]
  onChange: (seasons: Season[]) => void
}

function SeasonEditor({ seasons, onChange }: SeasonEditorProps) {
  function markSeasonComplete(idx: number) {
    const next = seasons.map((s, i) =>
      i === idx ? { ...s, episodesWatched: s.episodeCount } : s
    )
    onChange(next)
  }

  function toggleEpisodes(idx: number, delta: number) {
    const next = seasons.map((s, i) => {
      if (i !== idx) return s
      const n = Math.max(0, Math.min(s.episodeCount, s.episodesWatched + delta))
      return { ...s, episodesWatched: n }
    })
    onChange(next)
  }

  return (
    <div className="space-y-2">
      {seasons.map((s, i) => {
        const complete = s.episodesWatched === s.episodeCount
        return (
          <div key={s.id} className="flex items-center gap-3 bg-secondary/40 rounded-lg px-3 py-2">
            <span className="font-mono text-xs text-muted-foreground w-12 shrink-0">
              Season {s.seasonNumber}
            </span>
            <div className="flex-1 h-1.5 bg-muted rounded-full overflow-hidden">
              <div
                className="h-full bg-amber rounded-full transition-all"
                style={{ width: `${(s.episodesWatched / s.episodeCount) * 100}%` }}
              />
            </div>
            <span className="font-mono text-xs text-muted-foreground w-10 text-center shrink-0">
              {s.episodesWatched}/{s.episodeCount}
            </span>
            <div className="flex gap-1 shrink-0">
              <button
                onClick={() => toggleEpisodes(i, -1)}
                className="w-5 h-5 rounded bg-secondary text-muted-foreground hover:text-foreground font-mono text-xs flex items-center justify-center"
              >−</button>
              <button
                onClick={() => toggleEpisodes(i, 1)}
                className="w-5 h-5 rounded bg-secondary text-muted-foreground hover:text-foreground font-mono text-xs flex items-center justify-center"
              >+</button>
              <button
                onClick={() => markSeasonComplete(i)}
                className={cn(
                  'w-5 h-5 rounded flex items-center justify-center transition-colors',
                  complete ? 'bg-amber/20 text-amber' : 'bg-secondary text-muted-foreground hover:text-amber'
                )}
              >
                <Check className="w-3 h-3" />
              </button>
            </div>
          </div>
        )
      })}
    </div>
  )
}

// ─── Step Progress Indicator ──────────────────────────────────────────────────

function StepIndicator({ step }: { step: 'search' | 'log' }) {
  return (
    <div className="flex items-center gap-2 mb-6">
      {/* Step 1 */}
      <div className={cn(
        'flex items-center gap-1.5 transition-colors',
        step === 'search' ? 'text-amber' : 'text-muted-foreground'
      )}>
        <div className={cn(
          'w-5 h-5 rounded-full flex items-center justify-center text-xs font-mono border transition-all',
          step === 'search'
            ? 'bg-amber text-void border-amber'
            : 'bg-amber/15 border-amber/40 text-amber'
        )}>
          {step === 'log' ? <Check className="w-3 h-3" /> : '1'}
        </div>
        <span className="text-xs font-mono">Search</span>
      </div>

      {/* Connector */}
      <div className={cn(
        'flex-1 h-px transition-colors',
        step === 'log' ? 'bg-amber/30' : 'bg-border'
      )} />

      {/* Step 2 */}
      <div className={cn(
        'flex items-center gap-1.5 transition-colors',
        step === 'log' ? 'text-amber' : 'text-muted-foreground/40'
      )}>
        <div className={cn(
          'w-5 h-5 rounded-full flex items-center justify-center text-xs font-mono border transition-all',
          step === 'log'
            ? 'bg-amber text-void border-amber'
            : 'border-border text-muted-foreground'
        )}>
          2
        </div>
        <span className="text-xs font-mono">Log</span>
      </div>
    </div>
  )
}

// ─── Steps ───────────────────────────────────────────────────────────────────

type Step = 'search' | 'log'

interface LogFormState {
  status: WatchStatus
  rating: number
  date: string
  notes: string
  seasons: Season[]
}

const DEFAULT_LOG: LogFormState = {
  status: 'watched',
  rating: 0,
  date: new Date().toISOString().slice(0, 10),
  notes: '',
  seasons: [],
}

const STATUS_OPTIONS: { value: WatchStatus; label: string }[] = [
  { value: 'watched', label: 'Watched' },
  { value: 'watchlist', label: 'Watchlist' },
  { value: 'watching', label: 'Watching' },
  { value: 'dropped', label: 'Dropped' },
]

// ─── AddTitleWorkflow Component ───────────────────────────────────────────────

export function AddTitleWorkflow() {
  const { isAddTitleOpen, closeAddTitle, addTitle } = useAppStore()
  const [step, setStep] = useState<Step>('search')
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<SearchResult | null>(null)
  const [log, setLog] = useState<LogFormState>(DEFAULT_LOG)
  const { results, loading, search } = useDebouncedSearch()

  function handleQueryChange(e: React.ChangeEvent<HTMLInputElement>) {
    setQuery(e.target.value)
    search(e.target.value)
  }

  function selectResult(result: SearchResult) {
    setSelected(result)
    const seasons: Season[] = result.type === 'tv' && result.seasonCount
      ? Array.from({ length: result.seasonCount }, (_, i) => ({
          id: `new-s${i + 1}`,
          seasonNumber: i + 1,
          episodeCount: 10,
          episodesWatched: 0,
        }))
      : []
    setLog({ ...DEFAULT_LOG, seasons })
    setStep('log')
  }

  function handleSave() {
    if (!selected) return

    const newTitle: Title = {
      id: `local-${Date.now()}`,
      tmdbId: selected.tmdbId,
      type: selected.type,
      title: selected.title,
      year: selected.year,
      director: selected.director,
      genres: selected.genres,
      posterUrl: selected.posterUrl,
      synopsis: selected.synopsis,
      runtime: selected.runtime,
      network: selected.network,
      status: log.status,
      rating: log.rating > 0 ? log.rating : undefined,
      notes: log.notes || undefined,
      tags: [],
      addedAt: new Date().toISOString().slice(0, 10),
      seasons: log.seasons.length > 0 ? log.seasons : undefined,
      viewings: log.status === 'watched' && log.date
        ? [{ id: `v-${Date.now()}`, titleId: `local-${Date.now()}`, date: log.date, rating: log.rating || undefined, notes: log.notes || undefined }]
        : [],
    }

    addTitle(newTitle)
    handleClose()
  }

  function handleClose() {
    closeAddTitle()
    setTimeout(() => {
      setStep('search')
      setQuery('')
      setSelected(null)
      setLog(DEFAULT_LOG)
    }, 300)
  }

  return (
    <BottomSheet
      open={isAddTitleOpen}
      onClose={handleClose}
      title="Add to Library"
      side="right"
    >
      {/* Visual step progress indicator */}
      <StepIndicator step={step} />

      {/* Step 1: Search */}
      {step === 'search' && (
        <div className="space-y-4">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground pointer-events-none" />
            <Input
              autoFocus
              value={query}
              onChange={handleQueryChange}
              placeholder="Search for a movie or series…"
              className="pl-9 bg-secondary/50 border-border"
            />
          </div>

          {loading && (
            <div className="text-center py-6 text-muted-foreground text-sm font-mono">
              Searching…
            </div>
          )}

          {!loading && results.length === 0 && query.length > 1 && (
            <div className="text-center py-6 text-muted-foreground text-sm">
              No results for "{query}"
            </div>
          )}

          {results.length > 0 && (
            <div className="space-y-2">
              {results.map((r) => (
                <button
                  key={r.tmdbId}
                  onClick={() => selectResult(r)}
                  className="w-full flex items-center gap-3 p-3 rounded-lg hover:bg-secondary/60 transition-colors text-left"
                >
                  <div className="w-12 shrink-0">
                    {r.posterUrl ? (
                      <img src={r.posterUrl} alt={r.title} className="w-full aspect-[2/3] object-cover rounded" />
                    ) : (
                      <div className="w-full aspect-[2/3] bg-secondary rounded flex items-center justify-center">
                        {r.type === 'movie' ? (
                          <Film className="w-4 h-4 text-muted-foreground" />
                        ) : (
                          <Tv className="w-4 h-4 text-muted-foreground" />
                        )}
                      </div>
                    )}
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="font-sans text-sm text-foreground truncate">{r.title}</p>
                    <p className="font-mono text-xs text-muted-foreground">
                      {r.year} · {r.type === 'movie' ? 'Film' : 'Series'}
                    </p>
                    {r.director && (
                      <p className="font-sans text-xs text-muted-foreground truncate">
                        {r.director}
                      </p>
                    )}
                  </div>
                  <ChevronRight className="w-4 h-4 text-muted-foreground shrink-0" />
                </button>
              ))}
            </div>
          )}

          {!query && (
            <div className="text-center py-12 text-muted-foreground">
              <div className="w-12 h-12 rounded-full bg-secondary/40 flex items-center justify-center mx-auto mb-3">
                <Search className="w-6 h-6 text-muted-foreground/40" />
              </div>
              <p className="font-serif text-base">Search for a title</p>
              <p className="font-sans text-xs mt-1 opacity-60">
                Movies, TV series, directors
              </p>
            </div>
          )}
        </div>
      )}

      {/* Step 2: Log Form */}
      {step === 'log' && selected && (
        <div className="space-y-6">
          <button
            onClick={() => setStep('search')}
            className="text-xs font-mono text-muted-foreground hover:text-foreground transition-colors"
          >
            ← Back to search
          </button>

          {/* Preview */}
          <div className="flex gap-4">
            <div className="w-20 shrink-0">
              <DynamicPoster title={{
                ...selected,
                id: 'preview',
                status: 'watched',
                tags: [],
                addedAt: '',
                viewings: [],
              }} />
            </div>
            <div>
              <p className="font-serif text-lg text-foreground">{selected.title}</p>
              <p className="font-mono text-xs text-muted-foreground mt-1">
                {selected.year} · {selected.director ?? selected.network}
              </p>
              <div className="flex flex-wrap gap-1 mt-2">
                {selected.genres.map((g) => (
                  <span key={g} className="font-mono text-xs text-muted-foreground border border-border rounded px-1.5 py-0.5">
                    {g}
                  </span>
                ))}
              </div>
            </div>
          </div>

          {/* Status */}
          <div>
            <label className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
              Status
            </label>
            <div className="flex flex-wrap gap-2">
              {STATUS_OPTIONS.map((opt) => (
                <button
                  key={opt.value}
                  onClick={() => setLog((l) => ({ ...l, status: opt.value }))}
                  className={cn(
                    'px-3 py-1.5 rounded-md text-xs font-sans border transition-all',
                    log.status === opt.value
                      ? 'bg-amber/20 border-amber/50 text-amber'
                      : 'bg-secondary/50 border-border text-muted-foreground hover:text-foreground'
                  )}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          {/* Rating */}
          <div>
            <label className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
              Your Rating
            </label>
            <StarRating
              value={log.rating}
              onChange={(r) => setLog((l) => ({ ...l, rating: r }))}
              size="lg"
            />
          </div>

          {/* Date */}
          {log.status === 'watched' && (
            <div>
              <label className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
                <Calendar className="inline w-3 h-3 mr-1" />
                Date Watched
              </label>
              <Input
                type="date"
                value={log.date}
                onChange={(e) => setLog((l) => ({ ...l, date: e.target.value }))}
                className="bg-secondary/50 border-border font-mono"
              />
            </div>
          )}

          {/* TV Season Editor */}
          {selected.type === 'tv' && log.seasons.length > 0 && (
            <div>
              <div className="flex items-center justify-between mb-3">
                <label className="font-sans text-xs uppercase tracking-widest text-muted-foreground">
                  Season Progress
                </label>
                <button
                  onClick={() => {
                    setLog((l) => ({
                      ...l,
                      seasons: l.seasons.map((s) => ({ ...s, episodesWatched: s.episodeCount })),
                    }))
                  }}
                  className="text-xs font-mono text-amber/70 hover:text-amber transition-colors"
                >
                  Mark All Complete
                </button>
              </div>
              <SeasonEditor
                seasons={log.seasons}
                onChange={(seasons) => setLog((l) => ({ ...l, seasons }))}
              />
            </div>
          )}

          {/* Notes */}
          <div>
            <label className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
              <FileText className="inline w-3 h-3 mr-1" />
              Notes
            </label>
            <textarea
              value={log.notes}
              onChange={(e) => setLog((l) => ({ ...l, notes: e.target.value }))}
              placeholder="Your thoughts…"
              rows={3}
              className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-2 focus:ring-amber/30"
            />
          </div>

          {/* Save */}
          <Button
            className="w-full bg-amber hover:bg-amber-muted text-void font-sans font-medium"
            onClick={handleSave}
          >
            <Star className="w-4 h-4 mr-2" />
            Add to Library
          </Button>
        </div>
      )}
    </BottomSheet>
  )
}
