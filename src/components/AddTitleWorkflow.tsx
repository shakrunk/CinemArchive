import { useState, useCallback, useRef } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { Search, Film, Tv, Star, Calendar, FileText, ChevronRight, Check, Tag, X } from 'lucide-react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { StarRating } from 'src/components/ui/star-rating'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'
import type { Title, WatchStatus, Season, CastMember, EpisodeCrew } from 'src/store/mockData'
import { searchMedia, fetchMediaDetails, fetchSeasonDetails, type SearchResult, type RawTmdbSeason, type RawTmdbEpisode } from 'src/lib/media'

// ─── Search hook ─────────────────────────────────────────────────────────────

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
    timerRef.current = setTimeout(async () => {
      try {
        setResults(await searchMedia(query))
      } catch (err) {
        console.error('Error during media search:', err)
        setResults([])
      } finally {
        setLoading(false)
      }
    }, delay)
  }, [delay])

  return { results, loading, search }
}

const TMDB_STILL_BASE = 'https://image.tmdb.org/t/p/w300'

// ─── Season scaffolding ──────────────────────────────────────────────────────

// Build fresh (unwatched) season/episode structures for a selected series.
// Episode counts and metadata come from TMDB when available.
const EP_CREW_JOBS = new Set(['Director', 'Writer', 'Teleplay', 'Story'])

function buildSeasons(
  result: SearchResult,
  tmdbSeasons: RawTmdbSeason[],
  episodesBySeason?: Map<number, RawTmdbEpisode[]>,
  seasonCastBySeason?: Map<number, CastMember[]>
): Season[] {
  if (result.type !== 'tv' || !result.seasonCount) return []
  return Array.from({ length: result.seasonCount }, (_, i) => {
    const seasonNum = i + 1
    const tmdbSeason = tmdbSeasons.find((s) => s.season_number === seasonNum)
    const epCount = tmdbSeason?.episode_count || 10
    const tmdbEpisodes = episodesBySeason?.get(seasonNum) ?? []
    return {
      id: crypto.randomUUID(),
      seasonNumber: seasonNum,
      episodeCount: epCount,
      episodesWatched: 0,
      cast: seasonCastBySeason?.get(seasonNum),
      episodes: Array.from({ length: epCount }, (_, j) => {
        const epNum = j + 1
        const tmdbEp = tmdbEpisodes.find((e) => e.episode_number === epNum)
        const epCrew: EpisodeCrew[] = (tmdbEp?.crew ?? [])
          .filter((c) => EP_CREW_JOBS.has(c.job))
          .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
        return {
          id: crypto.randomUUID(),
          episodeNumber: epNum,
          episodeName: tmdbEp?.name || undefined,
          airDate: tmdbEp?.air_date || undefined,
          runtime: tmdbEp?.runtime || undefined,
          synopsis: tmdbEp?.overview || undefined,
          stillUrl: tmdbEp?.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : undefined,
          director: epCrew.find((c) => c.job === 'Director')?.name,
          writers: epCrew.filter((c) => ['Writer', 'Teleplay', 'Story'].includes(c.job)).map((c) => c.name),
          crew: epCrew.length > 0 ? epCrew : undefined,
          watchEvents: [],
          ratings: [],
          reviews: [],
        }
      }),
    }
  })
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
                type="button"
                onClick={() => toggleEpisodes(i, -1)}
                aria-label={`Decrease episodes watched for season ${s.seasonNumber}`}
                className="w-5 h-5 rounded bg-secondary text-muted-foreground hover:text-foreground font-mono text-xs flex items-center justify-center"
              >−</button>
              <button
                type="button"
                onClick={() => toggleEpisodes(i, 1)}
                aria-label={`Increase episodes watched for season ${s.seasonNumber}`}
                className="w-5 h-5 rounded bg-secondary text-muted-foreground hover:text-foreground font-mono text-xs flex items-center justify-center"
              >+</button>
              <button
                type="button"
                onClick={() => markSeasonComplete(i)}
                aria-label={complete ? `Mark season ${s.seasonNumber} incomplete` : `Mark season ${s.seasonNumber} complete`}
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
            ? 'bg-amber text-[color:var(--on-amber)] border-amber'
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
            ? 'bg-amber text-[color:var(--on-amber)] border-amber'
            : 'border-border text-muted-foreground'
        )}>
          2
        </div>
        <span className="text-xs font-mono">Log</span>
      </div>
    </div>
  )
}

// ─── Tag input ───────────────────────────────────────────────────────────────

function TagInput({ tags, onChange }: { tags: string[]; onChange: (tags: string[]) => void }) {
  const [input, setInput] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)

  function commit() {
    const trimmed = input.trim().replace(/,+$/, '')
    if (trimmed && !tags.includes(trimmed)) {
      onChange([...tags, trimmed])
    }
    setInput('')
  }

  function handleKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      commit()
    } else if (e.key === 'Backspace' && input === '' && tags.length > 0) {
      onChange(tags.slice(0, -1))
    }
  }

  function removeTag(tag: string) {
    onChange(tags.filter((t) => t !== tag))
  }

  return (
    <div
      className="flex flex-wrap gap-1.5 min-h-[38px] bg-secondary/50 border border-border rounded-md px-2.5 py-1.5 cursor-text focus-within:ring-2 focus-within:ring-amber/30"
      onClick={() => inputRef.current?.focus()}
    >
      {tags.map((tag) => (
        <span
          key={tag}
          className="flex items-center gap-1 px-2 py-0.5 rounded-full bg-amber/10 border border-amber/20 font-mono text-xs text-amber"
        >
          {tag}
          <button
            type="button"
            onClick={(e) => { e.stopPropagation(); removeTag(tag) }}
            className="hover:text-amber-bright transition-colors"
            aria-label={`Remove tag ${tag}`}
          >
            <X className="w-2.5 h-2.5" />
          </button>
        </span>
      ))}
      <input
        ref={inputRef}
        aria-label="Add a tag"
        value={input}
        onChange={(e) => setInput(e.target.value)}
        onKeyDown={handleKey}
        onBlur={commit}
        placeholder={tags.length === 0 ? 'Add tags…  Enter or comma to add' : ''}
        className="flex-1 min-w-[120px] bg-transparent text-sm font-sans text-foreground placeholder:text-muted-foreground focus:outline-none"
      />
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
  tags: string[]
  seasons: Season[]
}

const DEFAULT_LOG: LogFormState = {
  status: 'watched',
  rating: 0,
  date: new Date().toISOString().slice(0, 10),
  notes: '',
  tags: [],
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
  // ⚡ Bolt: Prevent unnecessary re-renders by using useShallow
  const { isAddTitleOpen, closeAddTitle, addTitle } = useAppStore(
    useShallow((s) => ({
      isAddTitleOpen: s.isAddTitleOpen,
      closeAddTitle: s.closeAddTitle,
      addTitle: s.addTitle,
    }))
  )
  const [step, setStep] = useState<Step>('search')
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<SearchResult | null>(null)
  const [log, setLog] = useState<LogFormState>(DEFAULT_LOG)
  const [loadingDetails, setLoadingDetails] = useState(false)
  const { results, loading, search } = useDebouncedSearch()

  function handleQueryChange(e: React.ChangeEvent<HTMLInputElement>) {
    setQuery(e.target.value)
    search(e.target.value)
  }

  async function selectResult(result: SearchResult) {
    setLoadingDetails(true)
    try {
      const { result: detailed, tmdbSeasons } = await fetchMediaDetails(result)
      let episodesBySeason: Map<number, RawTmdbEpisode[]> | undefined
      const seasonCastBySeason = new Map<number, CastMember[]>()
      if (detailed.type === 'tv' && detailed.tmdbId && tmdbSeasons.length > 0) {
        const settled = await Promise.allSettled(
          tmdbSeasons
            .filter((s) => s.season_number > 0)
            .map((s) =>
              fetchSeasonDetails(detailed.tmdbId, s.season_number).then(({ episodes, cast }) => ({
                seasonNumber: s.season_number,
                episodes,
                cast,
              }))
            )
        )
        episodesBySeason = new Map()
        for (const r of settled) {
          if (r.status === 'fulfilled') {
            episodesBySeason.set(r.value.seasonNumber, r.value.episodes)
            if (r.value.cast.length > 0) {
              seasonCastBySeason.set(r.value.seasonNumber, r.value.cast)
            }
          }
        }
      }
      setSelected(detailed)
      setLog({ ...DEFAULT_LOG, seasons: buildSeasons(detailed, tmdbSeasons, episodesBySeason, seasonCastBySeason) })
      setStep('log')
    } catch (err) {
      console.error('Error fetching details:', err)
      setSelected(result)
      setStep('log')
    } finally {
      setLoadingDetails(false)
    }
  }

  function handleSave() {
    if (!selected) return

    const id = crypto.randomUUID()

    const newTitle: Title = {
      id,
      tmdbId: selected.tmdbId,
      type: selected.type,
      title: selected.title,
      year: selected.year,
      releaseDate: selected.releaseDate,
      director: selected.director,
      genres: selected.genres,
      posterUrl: selected.posterUrl,
      synopsis: selected.synopsis,
      runtime: selected.runtime,
      network: selected.network,
      status: log.status,
      rating: log.rating > 0 ? log.rating : undefined,
      notes: log.notes || undefined,
      tags: log.tags,
      addedAt: new Date().toISOString().slice(0, 10),
      seasons: log.seasons.length > 0 ? log.seasons : undefined,
      viewings: log.status === 'watched' && log.date
        ? [{ id: crypto.randomUUID(), titleId: id, date: log.date, rating: log.rating || undefined, notes: log.notes || undefined }]
        : [],
      imdbRating: selected.imdbRating,
      rtScore: selected.rtScore,
      metacriticScore: selected.metacriticScore,
      cast: selected.cast,
      crew: selected.crew,
      studios: selected.studios,
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
    <CinemaModal
      open={isAddTitleOpen}
      onClose={handleClose}
      maxWidth="sm:max-w-lg"
      title="Add to Library"
      description="Search for a movie or series and log your viewing details."
    >
      <div className="overflow-y-auto flex-1 scrollbar-thin px-6 py-6">
        <h2 className="font-serif text-xl font-light text-foreground mb-5">Add to Library</h2>
        <StepIndicator step={step} />

      {/* Step 1: Search */}
      {step === 'search' && (
        <div className="space-y-4">
          {loadingDetails ? (
            <div className="text-center py-12 text-muted-foreground text-sm font-mono">
              Loading titles details…
            </div>
          ) : (
            <>
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground pointer-events-none" />
                <Input
                  autoFocus
                  aria-label="Search"
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
            </>
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
            <p className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
              Status
            </p>
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
            <p className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
              Your Rating
            </p>
            <StarRating
              value={log.rating}
              onChange={(r) => setLog((l) => ({ ...l, rating: r }))}
              size="lg"
            />
          </div>

          {/* Date */}
          {log.status === 'watched' && (
            <div>
              <label htmlFor="log-date" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
                <Calendar className="inline w-3 h-3 mr-1" />
                Date Watched
              </label>
              <Input
                id="log-date"
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
                <p className="font-sans text-xs uppercase tracking-widest text-muted-foreground">
                  Season Progress
                </p>
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

          {/* Tags */}
          <div>
            <p className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2">
              <Tag className="inline w-3 h-3 mr-1" />
              Tags
            </p>
            <TagInput
              tags={log.tags}
              onChange={(tags) => setLog((l) => ({ ...l, tags }))}
            />
          </div>

          {/* Notes */}
          <div>
            <label htmlFor="log-notes" className="block font-sans text-xs uppercase tracking-widest text-muted-foreground mb-2 cursor-pointer">
              <FileText className="inline w-3 h-3 mr-1" />
              Notes
            </label>
            <textarea
              id="log-notes"
              value={log.notes}
              onChange={(e) => setLog((l) => ({ ...l, notes: e.target.value }))}
              placeholder="Your thoughts…"
              rows={3}
              className="w-full bg-secondary/50 border border-border rounded-md px-3 py-2 text-sm font-sans text-foreground placeholder:text-muted-foreground resize-none focus:outline-none focus:ring-2 focus:ring-amber/30"
            />
          </div>

          {/* Save */}
          <Button
            className="w-full bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium"
            onClick={handleSave}
          >
            <Star className="w-4 h-4 mr-2" />
            Add to Library
          </Button>
        </div>
      )}
      </div>
    </CinemaModal>
  )
}
