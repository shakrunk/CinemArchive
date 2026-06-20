import { useState, useCallback, useRef } from 'react'
import { Search, Film, Tv, RefreshCw } from 'lucide-react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { useAppStore, useSelectedTitle } from 'src/store/useAppStore'
import { searchMedia, fetchMediaDetails, type SearchResult } from 'src/lib/media'
import type { Title } from 'src/store/mockData'

// Project an existing library Title back into a SearchResult so it can be
// re-hydrated through the same detail-fetch path as a fresh search pick.
function toSearchResult(t: Title): SearchResult {
  return {
    tmdbId: t.tmdbId,
    type: t.type,
    title: t.title,
    year: t.year,
    posterUrl: t.posterUrl,
    backdropUrl: t.backdropUrl,
    director: t.director,
    genres: t.genres,
    synopsis: t.synopsis,
    runtime: t.runtime,
    network: t.network,
    imdbRating: t.imdbRating,
    rtScore: t.rtScore,
    metacriticScore: t.metacriticScore,
  }
}

// Inner body is conditionally mounted only while the modal is open (see the
// wrapper below), so it unmounts on close and its local state resets cleanly
// on the next open — no reset effect needed.
function RefreshContent({ title, onClose }: { title: Title; onClose: () => void }) {
  const updateTitle = useAppStore((s) => s.updateTitle)
  const titles = useAppStore((s) => s.titles)

  const [query, setQuery] = useState('')
  const [results, setResults] = useState<SearchResult[]>([])
  const [searching, setSearching] = useState(false)
  const [applying, setApplying] = useState(false)
  const [showSearch, setShowSearch] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const runSearch = useCallback((q: string) => {
    if (timerRef.current) clearTimeout(timerRef.current)
    if (!q.trim()) {
      setResults([])
      setSearching(false)
      return
    }
    setSearching(true)
    timerRef.current = setTimeout(async () => {
      try {
        setResults(await searchMedia(q))
      } catch (err) {
        console.error('Error searching for replacement entry:', err)
        setResults([])
      } finally {
        setSearching(false)
      }
    }, 400)
  }, [])

  // Re-matching only makes sense within the same media type (keeps the DB
  // season/episode structure consistent with the title type).
  const typedResults = results.filter((r) => r.type === title.type)
  const TypeIcon = title.type === 'movie' ? Film : Tv

  async function applyFrom(base: SearchResult) {
    // The DB enforces a unique (user, tmdb_id, type) link; block re-pointing this
    // title at an entry another title already owns so local state can't drift
    // from a silently-rejected write.
    if (base.tmdbId) {
      const conflict = titles.find(
        (t) => t.id !== title.id && t.tmdbId === base.tmdbId && t.type === title.type
      )
      if (conflict) {
        setError(`"${conflict.title}" is already linked to this entry.`)
        return
      }
    }

    setApplying(true)
    setError(null)
    try {
      const { result } = await fetchMediaDetails(base)
      const patch: Partial<Title> = {
        tmdbId: result.tmdbId,
        title: result.title,
        year: result.year,
        director: result.director,
        genres: result.genres,
        posterUrl: result.posterUrl,
        backdropUrl: result.backdropUrl,
        synopsis: result.synopsis,
        runtime: result.runtime,
        network: result.network,
        imdbRating: result.imdbRating,
        rtScore: result.rtScore,
        metacriticScore: result.metacriticScore,
      }
      updateTitle(title.id, patch)
      onClose()
    } catch (err) {
      console.error('Error refreshing metadata:', err)
      setError('Could not fetch fresh metadata. Please try again.')
      setApplying(false)
    }
  }

  function startSearch() {
    setShowSearch(true)
    setQuery(title.title)
    runSearch(title.title)
  }

  return (
    <div className="overflow-y-auto flex-1 scrollbar-thin px-6 py-6 space-y-6">
      <div>
        <h2 className="font-serif text-xl font-light text-foreground mb-1">Refresh Metadata</h2>
        <p className="font-sans text-xs text-muted-foreground">
          Re-pull the poster, synopsis, and ratings — or find the correct entry if this one was mismatched.
          Your ratings, notes, and viewing history are kept.
        </p>
      </div>

      {/* Current entry */}
      <div className="flex gap-4 items-center">
        <div className="w-16 shrink-0">
          {title.posterUrl ? (
            <img src={title.posterUrl} alt={title.title} className="w-full aspect-[2/3] object-cover rounded" />
          ) : (
            <div className="w-full aspect-[2/3] bg-secondary rounded flex items-center justify-center">
              <TypeIcon className="w-5 h-5 text-muted-foreground" />
            </div>
          )}
        </div>
        <div className="min-w-0">
          <p className="font-serif text-lg text-foreground truncate">{title.title}</p>
          <p className="font-mono text-xs text-muted-foreground">
            {title.year} · {title.type === 'movie' ? 'Film' : 'Series'}
          </p>
          <p className="font-mono text-[11px] text-muted-foreground/70 mt-1">
            {title.tmdbId ? `Linked to TMDB #${title.tmdbId}` : 'Not linked to TMDB'}
          </p>
        </div>
      </div>

      {applying ? (
        <div className="text-center py-10 text-muted-foreground text-sm font-mono">
          Fetching fresh metadata…
        </div>
      ) : (
        <>
          {error && <p className="text-xs font-mono text-ember">{error}</p>}

          {!showSearch ? (
            <div className="space-y-3">
              <Button
                className="w-full bg-amber hover:bg-amber-muted text-void font-sans font-medium disabled:opacity-40"
                onClick={() => applyFrom(toSearchResult(title))}
                disabled={!title.tmdbId}
              >
                <RefreshCw className="w-4 h-4 mr-2" />
                Re-fetch current match
              </Button>
              {!title.tmdbId && (
                <p className="text-[11px] text-muted-foreground text-center">
                  This title isn't linked to TMDB yet — search below to link it.
                </p>
              )}
              <button
                onClick={startSearch}
                className="w-full text-xs font-mono text-muted-foreground hover:text-amber transition-colors py-1"
              >
                Wrong entry? Search for the correct one →
              </button>
            </div>
          ) : (
            <div className="space-y-4">
              <div className="relative">
                <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground pointer-events-none" />
                <Input
                  autoFocus
                  value={query}
                  onChange={(e) => {
                    setQuery(e.target.value)
                    runSearch(e.target.value)
                  }}
                  placeholder={`Search for the correct ${title.type === 'movie' ? 'film' : 'series'}…`}
                  className="pl-9 bg-secondary/50 border-border"
                />
              </div>

              {searching && (
                <div className="text-center py-6 text-muted-foreground text-sm font-mono">Searching…</div>
              )}

              {!searching && typedResults.length === 0 && query.trim().length > 1 && (
                <div className="text-center py-6 text-muted-foreground text-sm">No results for "{query}"</div>
              )}

              {typedResults.length > 0 && (
                <div className="space-y-2">
                  {typedResults.map((r) => {
                    const isCurrent = r.tmdbId === title.tmdbId
                    return (
                      <button
                        key={r.tmdbId}
                        onClick={() => applyFrom(r)}
                        className="w-full flex items-center gap-3 p-3 rounded-lg hover:bg-secondary/60 transition-colors text-left"
                      >
                        <div className="w-12 shrink-0">
                          {r.posterUrl ? (
                            <img src={r.posterUrl} alt={r.title} className="w-full aspect-[2/3] object-cover rounded" />
                          ) : (
                            <div className="w-full aspect-[2/3] bg-secondary rounded flex items-center justify-center">
                              <TypeIcon className="w-4 h-4 text-muted-foreground" />
                            </div>
                          )}
                        </div>
                        <div className="flex-1 min-w-0">
                          <p className="font-sans text-sm text-foreground truncate">{r.title}</p>
                          <p className="font-mono text-xs text-muted-foreground">
                            {r.year || '—'} · {r.type === 'movie' ? 'Film' : 'Series'}
                          </p>
                        </div>
                        {isCurrent && (
                          <span className="font-mono text-[10px] uppercase tracking-wider text-amber/70 border border-amber/30 rounded px-1.5 py-0.5 shrink-0">
                            Current
                          </span>
                        )}
                      </button>
                    )
                  })}
                </div>
              )}

              <button
                onClick={() => setShowSearch(false)}
                className="text-xs font-mono text-muted-foreground hover:text-foreground transition-colors"
              >
                ← Back
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}

export function RefreshMetadataModal() {
  const isOpen = useAppStore((s) => s.isRefreshMetadataOpen)
  const close = useAppStore((s) => s.closeRefreshMetadata)
  const title = useSelectedTitle()

  return (
    <CinemaModal
      open={isOpen}
      onClose={close}
      maxWidth="sm:max-w-lg"
      title="Refresh Metadata"
      description="Refresh the poster and metadata for this title."
    >
      {isOpen && title ? <RefreshContent title={title} onClose={close} /> : null}
    </CinemaModal>
  )
}
