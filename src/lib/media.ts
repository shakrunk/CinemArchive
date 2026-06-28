// Shared TMDB/OMDb access used by both the Add-title workflow and the
// Refresh-metadata flow. All network calls go through the `media-proxy`
// Edge Function so API keys stay server-side; when Supabase isn't configured
// (local dev) we fall back to a small static result set.

import { supabase, isSupabaseConfigured } from './auth'
import type { CastMember, CrewMember, MediaType } from '../store/mockData'

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
  releaseDate?: string  // YYYY-MM-DD; populated when title hasn't released yet
  imdbRating?: number
  rtScore?: number
  metacriticScore?: number
  cast?: CastMember[]
  crew?: CrewMember[]
  studios?: string[]
}

export interface RawTmdbSeason {
  season_number: number
  episode_count: number
}

export interface RawTmdbEpisode {
  episode_number: number
  name: string
  overview?: string
  air_date?: string
  runtime?: number
  still_path?: string
  crew?: Array<{
    id: number
    name: string
    job: string
    department?: string
    profile_path?: string
  }>
}

export interface MediaDetails {
  /** Fully-hydrated metadata for the chosen entry. */
  result: SearchResult
  /** Raw TMDB seasons (tv only); empty for movies or when unavailable. */
  tmdbSeasons: RawTmdbSeason[]
}

export interface SeasonFetchResult {
  cast: CastMember[]
  episodes: RawTmdbEpisode[]
}

const TMDB_IMG = 'https://image.tmdb.org/t/p'
const TMDB_IMG_W185 = 'https://image.tmdb.org/t/p/w185'

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
    backdropUrl: item.backdrop_path ? `${TMDB_IMG}/w1280${item.backdrop_path}` : undefined,
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
 * genres, director/network, seasons, cast, crew, studios) plus OMDb critic scores.
 * Falls back to the passed-in `base` values for any field the detail call can't supply.
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

  const TITLE_CREW_JOBS = new Set([
    'Director', 'Screenplay', 'Writer', 'Producer',
    'Director of Photography', 'Original Music Composer',
  ])

  const cast: CastMember[] = (data.credits?.cast ?? [])
    .slice(0, 10)
    .map((c: any) => ({
      tmdbPersonId: c.id,
      name: c.name,
      character: c.character || undefined,
      profileUrl: c.profile_path ? `${TMDB_IMG_W185}${c.profile_path}` : undefined,
      order: c.order ?? 0,
    }))

  const seenCrewKey = new Set<string>()
  const crew: CrewMember[] = []
  for (const c of (data.credits?.crew ?? [])) {
    if (!TITLE_CREW_JOBS.has(c.job)) continue
    const key = `${c.id}:${c.job}`
    if (seenCrewKey.has(key)) continue
    seenCrewKey.add(key)
    crew.push({
      tmdbPersonId: c.id,
      name: c.name,
      job: c.job,
      department: c.department || undefined,
      profileUrl: c.profile_path ? `${TMDB_IMG_W185}${c.profile_path}` : undefined,
    })
  }

  if (base.type === 'tv') {
    for (const creator of (data.created_by ?? [])) {
      crew.push({
        tmdbPersonId: creator.id,
        name: creator.name,
        job: 'Creator',
        profileUrl: creator.profile_path ? `${TMDB_IMG_W185}${creator.profile_path}` : undefined,
      })
    }
  }

  const studios: string[] = (data.production_companies ?? []).map((c: any) => c.name as string)

  const director = crew.find((c) => c.job === 'Director')?.name
  const date = data.release_date || data.first_air_date
  const today = new Date().toISOString().slice(0, 10)
  const releaseDate = date && date > today ? date : undefined

  const result: SearchResult = {
    tmdbId: data.id,
    type: base.type,
    title: data.title || data.name,
    year: date ? new Date(date).getFullYear() : base.year,
    releaseDate,
    posterUrl: data.poster_path ? `${TMDB_IMG}/w500${data.poster_path}` : base.posterUrl,
    backdropUrl: data.backdrop_path ? `${TMDB_IMG}/w1280${data.backdrop_path}` : base.backdropUrl,
    director,
    genres: data.genres?.map((g: any) => g.name) ?? [],
    synopsis: data.overview,
    runtime: data.runtime,
    network: data.networks?.[0]?.name,
    seasonCount: data.number_of_seasons,
    imdbRating,
    rtScore,
    metacriticScore,
    cast,
    crew,
    studios,
  }

  return { result, tmdbSeasons: data.seasons ?? [] }
}

/**
 * Fetch episode-level details and season cast for one season from TMDB.
 * Returns empty arrays when Supabase isn't configured or the call fails.
 */
export async function fetchSeasonDetails(tmdbId: number, seasonNumber: number): Promise<SeasonFetchResult> {
  if (!(isSupabaseConfigured && supabase)) return { episodes: [], cast: [] }

  try {
    const { data, error } = await supabase.functions.invoke(
      `media-proxy?action=season&id=${tmdbId}&season=${seasonNumber}`
    )
    if (error) throw error

    const episodes = (data?.episodes ?? []) as RawTmdbEpisode[]

    const cast: CastMember[] = (data?.credits?.cast ?? [])
      .slice(0, 10)
      .map((c: any) => ({
        tmdbPersonId: c.id,
        name: c.name,
        character: c.character || undefined,
        profileUrl: c.profile_path ? `${TMDB_IMG_W185}${c.profile_path}` : undefined,
        order: c.order ?? 0,
      }))

    return { episodes, cast }
  } catch (e) {
    console.error(`Error fetching season ${seasonNumber} details for tmdbId ${tmdbId}:`, e)
    return { episodes: [], cast: [] }
  }
}

export interface TitleVideo {
  key: string
  name: string
  type: string
  official: boolean
}

export async function fetchTitleVideos(tmdbId: number, type: MediaType): Promise<TitleVideo[]> {
  if (!(isSupabaseConfigured && supabase)) return []

  try {
    const { data, error } = await supabase.functions.invoke(
      `media-proxy?action=videos&id=${tmdbId}&type=${type}`
    )
    if (error) throw error

    const results: TitleVideo[] = ((data?.results ?? []) as any[])
      .filter((v) => v.site === 'YouTube' && ['Trailer', 'Teaser'].includes(v.type))
      .map((v) => ({
        key: v.key as string,
        name: v.name as string,
        type: v.type as string,
        official: v.official ?? false,
      }))

    results.sort((a, b) => {
      const score = (v: TitleVideo) => (v.official ? 2 : 0) + (v.type === 'Trailer' ? 1 : 0)
      return score(b) - score(a)
    })

    return results.slice(0, 4)
  } catch (e) {
    console.error('Error fetching title videos:', e)
    return []
  }
}

/**
 * Fetch the title's logo image (the stylized name treatment, transparent PNG).
 * Returns an English-text logo URL, or null when none exists so callers can
 * fall back to plain text. Display-only — not persisted.
 */
export async function fetchTitleLogo(tmdbId: number, type: MediaType): Promise<string | null> {
  if (!(isSupabaseConfigured && supabase)) return null

  try {
    const { data, error } = await supabase.functions.invoke(
      `media-proxy?action=images&id=${tmdbId}&type=${type}`
    )
    if (error) throw error

    // Only English-language logos carry the readable title text; textless
    // (iso null) logos are dropped so we fall back to plain text instead.
    const englishLogos = ((data?.logos ?? []) as any[])
      .filter((l) => l.file_path && l.iso_639_1 === 'en')
    if (englishLogos.length === 0) return null

    const best = englishLogos.sort((a, b) => (b.vote_average ?? 0) - (a.vote_average ?? 0))[0]
    return `${TMDB_IMG}/w500${best.file_path}`
  } catch (e) {
    console.error('Error fetching title logo:', e)
    return null
  }
}
