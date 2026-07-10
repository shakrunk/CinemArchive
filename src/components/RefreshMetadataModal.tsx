import { useState, useCallback, useRef } from 'react'
import { Search, RefreshCw, X } from 'lucide-react'
import { CinemaModal } from 'src/components/ui/cinema-modal'
import { Button } from 'src/components/ui/button'
import { Input } from 'src/components/ui/input'
import { PosterThumb } from 'src/components/ui/poster-thumb'
import { useAppStore, useSelectedTitle } from 'src/store/useAppStore'
import { searchMedia, fetchMediaDetails, fetchSeasonDetails, TMDB_STILL_BASE, type SearchResult } from 'src/lib/media'
import { upsertEpisodeMetadataInDb, bulkUpsertSeasonCastInDb, bulkUpsertEpisodeCrewInDb } from 'src/lib/db'
import type { Title, Episode, EpisodeCrew } from 'src/store/mockData'

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
    cast: t.cast,
    crew: t.crew,
    studios: t.studios,
    collectionId: t.collectionId,
    collectionName: t.collectionName,
  }
}

// Inner body is conditionally mounted only while the modal is open (see the
// wrapper below), so it unmounts on close and its local state resets cleanly
// on the next open — no reset effect needed.
function RefreshContent({ title, onClose }: { title: Title; onClose: () => void }) {
  const updateTitle = useAppStore((s) => s.updateTitle)
  const titles = useAppStore((s) => s.titles)
  const user = useAppStore((s) => s.user)

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
        releaseDate: result.releaseDate,
        originalLanguage: result.originalLanguage,
        contentRating: result.contentRating,
        imdbId: result.imdbId,
        rtUrl: result.rtUrl,
        imdbRating: result.imdbRating,
        rtScore: result.rtScore,
        metacriticScore: result.metacriticScore,
        cast: result.cast,
        crew: result.crew,
        studios: result.studios,
        collectionId: result.collectionId,
        collectionName: result.collectionName,
      }

      const EP_CREW_JOBS = new Set(['Director', 'Writer', 'Teleplay', 'Story'])

      // For TV shows, also refresh episode metadata for all seasons
      if (result.type === 'tv' && title.seasons && title.seasons.length > 0) {
        const settled = await Promise.allSettled(
          title.seasons.map((s) =>
            fetchSeasonDetails(result.tmdbId, s.seasonNumber).then(({ episodes, cast }) => ({
              season: s,
              tmdbEps: episodes,
              seasonCast: cast,
            }))
          )
        )

        const allEpisodeUpdates: Parameters<typeof upsertEpisodeMetadataInDb>[2] = []
        const allEpisodeCrew: Array<{ episodeId: string; crew: EpisodeCrew[] }> = []
        const allSeasonCast: Array<{ seasonId: string; cast: NonNullable<Title['cast']> }> = []

        const updatedSeasons = title.seasons.map((s) => {
          const match = settled.find(
            (r) => r.status === 'fulfilled' && r.value.season.seasonNumber === s.seasonNumber
          )
          if (!match || match.status !== 'fulfilled' || match.value.tmdbEps.length === 0) return s

          const { tmdbEps, seasonCast } = match.value
          const existingEpisodes = s.episodes || []
          let updatedEpisodes: Episode[]

          if (existingEpisodes.length === 0) {
            updatedEpisodes = tmdbEps.map((tmdbEp) => {
              const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
                .filter((c) => EP_CREW_JOBS.has(c.job))
                .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
              return {
                id: crypto.randomUUID(),
                episodeNumber: tmdbEp.episode_number,
                episodeName: tmdbEp.name || undefined,
                airDate: tmdbEp.air_date || undefined,
                runtime: tmdbEp.runtime || undefined,
                synopsis: tmdbEp.overview || undefined,
                stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : undefined,
                director: epCrew.find((c) => c.job === 'Director')?.name,
                writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
                crew: epCrew.length > 0 ? epCrew : undefined,
                watchEvents: [],
                ratings: [],
                reviews: [],
              }
            })
          } else {
            updatedEpisodes = existingEpisodes.map((ep) => {
              const tmdbEp = tmdbEps.find((e) => e.episode_number === ep.episodeNumber)
              if (!tmdbEp) return ep
              const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
                .filter((c) => EP_CREW_JOBS.has(c.job))
                .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
              return {
                ...ep,
                episodeName: tmdbEp.name || ep.episodeName,
                airDate: tmdbEp.air_date || ep.airDate,
                runtime: tmdbEp.runtime || ep.runtime,
                synopsis: tmdbEp.overview || ep.synopsis,
                stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : ep.stillUrl,
                director: epCrew.find((c) => c.job === 'Director')?.name ?? ep.director,
                writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
                crew: epCrew.length > 0 ? epCrew : ep.crew,
              }
            })
          }

          for (const ep of updatedEpisodes) {
            allEpisodeUpdates.push({
              id: ep.id,
              seasonNumber: s.seasonNumber,
              episodeNumber: ep.episodeNumber,
              episodeName: ep.episodeName,
              airDate: ep.airDate,
              runtime: ep.runtime,
              synopsis: ep.synopsis,
              stillUrl: ep.stillUrl,
            })
            if (ep.crew && ep.crew.length > 0) {
              allEpisodeCrew.push({ episodeId: ep.id, crew: ep.crew })
            }
          }

          if (seasonCast && seasonCast.length > 0) {
            allSeasonCast.push({ seasonId: s.id, cast: seasonCast })
          }

          return {
            ...s,
            episodes: updatedEpisodes,
            episodeCount: updatedEpisodes.length,
            cast: seasonCast && seasonCast.length > 0 ? seasonCast : s.cast,
          }
        })

        patch.seasons = updatedSeasons

        if (user) {
          if (allEpisodeUpdates.length > 0) {
            upsertEpisodeMetadataInDb(user.id, title.id, allEpisodeUpdates).catch((e) =>
              console.error('Episode metadata refresh DB write failed:', e)
            )
          }
          if (allSeasonCast.length > 0) {
            bulkUpsertSeasonCastInDb(user.id, title.id, allSeasonCast).catch((e) =>
              console.error('Season cast refresh DB write failed:', e)
            )
          }
          if (allEpisodeCrew.length > 0) {
            bulkUpsertEpisodeCrewInDb(user.id, title.id, allEpisodeCrew).catch((e) =>
              console.error('Episode crew refresh DB write failed:', e)
            )
          }
        }
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
        <PosterThumb src={title.posterUrl} alt={title.title} type={title.type} size="md" />
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
                className="w-full bg-amber hover:bg-amber-muted text-[color:var(--on-amber)] font-sans font-medium disabled:opacity-40"
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
                  aria-label="Search for the correct film or series"
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
                <div className="text-center py-6 text-muted-foreground text-sm flex flex-col items-center gap-3">
                  <div>No results for "{query}"</div>
                  <button
                    onClick={() => {
                      setQuery('')
                    }}
                    className="flex items-center gap-1.5 text-xs font-mono transition-colors text-amber-deep hover:text-amber"
                  >
                    <X className="w-3.5 h-3.5" />
                    Clear search
                  </button>
                </div>
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
                        <PosterThumb src={r.posterUrl} alt={r.title} type={title.type} />
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
