// Shared TMDB/OMDb access used by both the Add-title workflow and the
// Refresh-metadata flow. All network calls go through the `media-proxy`
// Edge Function so API keys stay server-side; when Supabase isn't configured
// (local dev) we fall back to a small static result set.

import { supabase, isSupabaseConfigured } from './auth'
import type { MediaType } from '../store/mockData'

// ─── Types ───────────────────────────────────────────────────────────────────

export interface SearchResult {
  tmdbId: number
  type: MediaType
  title: string
  year: number
  posterUrl?: string
  backdropUrl?: string
  director?: string
  genres: string[]
  synopsis?: string
  runtime?: number
  network?: string
  seasonCount?: number
  imdbRating?: number
  rtScore?: number
  metacriticScore?: number
}

export interface RawTmdbSeason {
  season_number: number
  episode_count: number
}

export interface MediaDetails {
  /** Fully-hydrated metadata for the chosen entry. */
  result: SearchResult
  /** Raw TMDB seasons (tv only); empty for movies or when unavailable. */
  tmdbSeasons: RawTmdbSeason[]
}

const TMDB_IMG = 'https://image.tmdb.org/t/p'

// ─── Local-dev fallback (no Supabase configured) ─────────────────────────────

const MOCK_RESULTS: SearchResult[] = [
  {
    tmdbId: 238,
    type: 'movie',
    title: 'The Godfather',
    year: 1972,
    posterUrl: `${TMDB_IMG}/w500/3bhkrj58Vtu7enYsLLeHSSa1xZx.jpg`,
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

// ─── Mapping helpers ─────────────────────────────────────────────────────────

function mapSearchItem(item: any, type: MediaType): SearchResult {
  const date = type === 'movie' ? item.release_date : item.first_air_date
  return {
    tmdbId: item.id,
    type,
    title: type === 'movie' ? item.title : item.name,
    year: date ? new Date(date).getFullYear() : 0,
    posterUrl: item.poster_path ? `${TMDB_IMG}/w500${item.poster_path}` : undefined,
    backdropUrl: item.backdrop_path ? `${TMDB_IMG}/w780${item.backdrop_path}` : undefined,
    synopsis: item.overview,
    genres: [],
  }
}

// ─── Public API ──────────────────────────────────────────────────────────────

/**
 * Search TMDB for both movies and series, interleaving the two lists so the
 * top of each kind stays visible. Returns up to 15 results.
 */
export async function searchMedia(query: string): Promise<SearchResult[]> {
  if (!query.trim()) return []

  if (isSupabaseConfigured && supabase) {
    const [movieRes, tvRes] = await Promise.all([
      supabase.functions.invoke(`media-proxy?action=search&q=${encodeURIComponent(query)}&type=movie`),
      supabase.functions.invoke(`media-proxy?action=search&q=${encodeURIComponent(query)}&type=tv`),
    ])

    if (movieRes.error) throw movieRes.error
    if (tvRes.error) throw tvRes.error

    const movies = (movieRes.data?.results || []).map((i: any) => mapSearchItem(i, 'movie'))
    const tv = (tvRes.data?.results || []).map((i: any) => mapSearchItem(i, 'tv'))

    const combined: SearchResult[] = []
    const maxLength = Math.max(movies.length, tv.length)
    for (let i = 0; i < maxLength; i++) {
      if (i < movies.length) combined.push(movies[i])
      if (i < tv.length) combined.push(tv[i])
    }
    return combined.slice(0, 15)
  }

  const q = query.toLowerCase()
  return MOCK_RESULTS.filter(
    (r) => r.title.toLowerCase().includes(q) || r.director?.toLowerCase().includes(q)
  )
}

/**
 * Hydrate a search result into full metadata: TMDB details (poster, synopsis,
 * genres, director/network, seasons) plus OMDb critic scores. Falls back to the
 * passed-in `base` values for any field the detail call can't supply.
 */
export async function fetchMediaDetails(base: SearchResult): Promise<MediaDetails> {
  if (!(isSupabaseConfigured && supabase)) {
    return { result: base, tmdbSeasons: [] }
  }

  const { data, error } = await supabase.functions.invoke(
    `media-proxy?action=details&id=${base.tmdbId}&type=${base.type}`
  )
  if (error) throw error

  let imdbRating: number | undefined
  let rtScore: number | undefined
  let metacriticScore: number | undefined

  if (data.imdb_id) {
    try {
      const { data: ratingsData } = await supabase.functions.invoke(
        `media-proxy?action=ratings&imdb=${data.imdb_id}`
      )
      if (ratingsData) {
        imdbRating =
          ratingsData.imdbRating && ratingsData.imdbRating !== 'N/A'
            ? parseFloat(ratingsData.imdbRating)
            : undefined
        const rt = ratingsData.Ratings?.find((r: any) => r.Source === 'Rotten Tomatoes')?.Value
        rtScore = rt ? parseInt(rt.replace('%', ''), 10) : undefined
        const meta = ratingsData.Metascore
        metacriticScore = meta && meta !== 'N/A' ? parseInt(meta, 10) : undefined
      }
    } catch (e) {
      console.error('Error fetching ratings:', e)
    }
  }

  const director = data.credits?.crew?.find((c: any) => c.job === 'Director')?.name
  const date = data.release_date || data.first_air_date

  const result: SearchResult = {
    tmdbId: data.id,
    type: base.type,
    title: data.title || data.name,
    year: date ? new Date(date).getFullYear() : base.year,
    posterUrl: data.poster_path ? `${TMDB_IMG}/w500${data.poster_path}` : base.posterUrl,
    backdropUrl: data.backdrop_path ? `${TMDB_IMG}/w780${data.backdrop_path}` : base.backdropUrl,
    director,
    genres: data.genres?.map((g: any) => g.name) ?? [],
    synopsis: data.overview,
    runtime: data.runtime,
    network: data.networks?.[0]?.name,
    seasonCount: data.number_of_seasons,
    imdbRating,
    rtScore,
    metacriticScore,
  }

  return { result, tmdbSeasons: data.seasons ?? [] }
}
