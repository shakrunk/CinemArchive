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
  releaseDate?: string  // YYYY-MM-DD; the title's actual release/first-air date
  originalLanguage?: string  // ISO 639-1 code, e.g. "en"
  contentRating?: string  // age certification, e.g. "PG-13", "TV-MA"
  imdbId?: string  // e.g. "tt1375666"
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

// ─── Genre Lists (stable TMDB IDs — hardcoded to avoid an extra round-trip) ─

export interface Genre {
  id: number
  name: string
}

export const MOVIE_GENRES: Genre[] = [
  { id: 28, name: 'Action' },
  { id: 12, name: 'Adventure' },
  { id: 16, name: 'Animation' },
  { id: 35, name: 'Comedy' },
  { id: 80, name: 'Crime' },
  { id: 18, name: 'Drama' },
  { id: 14, name: 'Fantasy' },
  { id: 27, name: 'Horror' },
  { id: 9648, name: 'Mystery' },
  { id: 10749, name: 'Romance' },
  { id: 878, name: 'Sci-Fi' },
  { id: 53, name: 'Thriller' },
]

export const TV_GENRES: Genre[] = [
  { id: 10759, name: 'Action & Adventure' },
  { id: 16, name: 'Animation' },
  { id: 35, name: 'Comedy' },
  { id: 80, name: 'Crime' },
  { id: 18, name: 'Drama' },
  { id: 9648, name: 'Mystery' },
  { id: 10765, name: 'Sci-Fi & Fantasy' },
  { id: 10751, name: 'Family' },
  { id: 37, name: 'Western' },
]

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

/**
 * Pull the US content certification from a TMDB details payload.
 * Movies expose it under `release_dates.results[].release_dates[].certification`
 * — multiple entries (theatrical/digital) exist and only one carries a non-empty
 * value, so we scan for the first non-empty rather than taking [0].
 * TV exposes a single `content_ratings.results[].rating`.
 */
function extractCertification(data: any, type: MediaType): string | undefined {
  if (type === 'movie') {
    const us = (data.release_dates?.results ?? []).find((r: any) => r.iso_3166_1 === 'US')
    const cert = (us?.release_dates ?? [])
      .map((rd: any) => rd.certification)
      .find((c: string) => c && c.trim() !== '')
    return cert ? cert.trim() : undefined
  }
  const us = (data.content_ratings?.results ?? []).find((r: any) => r.iso_3166_1 === 'US')
  const rating = us?.rating
  return rating && rating.trim() !== '' ? rating.trim() : undefined
}

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

  // Movie details carry imdb_id at the top level; TV exposes it via external_ids.
  const imdbId: string | undefined = data.imdb_id || data.external_ids?.imdb_id || undefined

  let imdbRating: number | undefined
  let rtScore: number | undefined
  let metacriticScore: number | undefined

  if (imdbId) {
    try {
      const { data: ratingsData } = await supabase.functions.invoke(
        `media-proxy?action=ratings&imdb=${imdbId}`
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
      episodeCount: c.episode_count ?? undefined,
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

  const result: SearchResult = {
    tmdbId: data.id,
    type: base.type,
    title: data.title || data.name,
    year: date ? new Date(date).getFullYear() : base.year,
    releaseDate: date || undefined,
    originalLanguage: data.original_language || undefined,
    contentRating: extractCertification(data, base.type),
    imdbId,
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
        episodeCount: c.episode_count ?? undefined,
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

const MOCK_TRENDING: SearchResult[] = [
  {
    tmdbId: 533535,
    type: 'movie',
    title: 'Deadpool & Wolverine',
    year: 2024,
    posterUrl: `${TMDB_IMG}/w500/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg`,
    genres: ['Action', 'Comedy', 'Science Fiction'],
    synopsis: 'Deadpool and a variant of Wolverine must team up against a common threat.',
    runtime: 127,
  },
  {
    tmdbId: 202555,
    type: 'tv',
    title: 'Severance',
    year: 2022,
    posterUrl: `${TMDB_IMG}/w500/6eMmJtjE86h8m9s5v23B7Zgg1eJ.jpg`,
    genres: ['Drama', 'Mystery', 'Science Fiction'],
    synopsis: "Mark leads a team whose memories are surgically divided between their work and personal lives.",
    network: 'Apple TV+',
    seasonCount: 2,
  },
  {
    tmdbId: 945961,
    type: 'movie',
    title: 'Alien: Romulus',
    year: 2024,
    posterUrl: `${TMDB_IMG}/w500/b33nnKl1GSFbao4l3fZDDqsMx0F.jpg`,
    genres: ['Horror', 'Science Fiction'],
    synopsis: 'A group of young colonists face the most terrifying life form in the universe.',
    runtime: 119,
  },
  {
    tmdbId: 209867,
    type: 'tv',
    title: 'The Last of Us',
    year: 2023,
    posterUrl: `${TMDB_IMG}/w500/uKvVjHNqB5VmOrdxqAt2F7J78ED.jpg`,
    genres: ['Drama', 'Science Fiction'],
    synopsis: 'Joel and Ellie travel across a post-pandemic America.',
    network: 'HBO',
    seasonCount: 2,
  },
  {
    tmdbId: 1091602,
    type: 'movie',
    title: 'Nosferatu',
    year: 2024,
    posterUrl: `${TMDB_IMG}/w500/5qGIxdEO841C0tdY8vOdLoRVrr0.jpg`,
    genres: ['Horror', 'Drama'],
    synopsis: 'A remake of the 1922 German Expressionist horror film.',
    runtime: 133,
  },
  {
    tmdbId: 76479,
    type: 'tv',
    title: 'The Boys',
    year: 2019,
    posterUrl: `${TMDB_IMG}/w500/2zmTngn1tYC1AvfnrFLhxeD82hz.jpg`,
    genres: ['Action & Adventure', 'Science Fiction'],
    synopsis: 'A group of vigilantes fight corrupt superheroes.',
    network: 'Prime Video',
    seasonCount: 4,
  },
]

/**
 * Fetch this week's trending movies or TV shows. Falls back to a curated
 * static list in local dev (no Supabase configured).
 */
export async function fetchTrending(type: MediaType | 'all'): Promise<SearchResult[]> {
  if (!(isSupabaseConfigured && supabase)) {
    if (type === 'all') return MOCK_TRENDING
    return MOCK_TRENDING.filter((r) => r.type === type)
  }

  // Cache non-null reference so TypeScript narrowing carries into the nested async map.
  const client = supabase
  const types: MediaType[] = type === 'all' ? ['movie', 'tv'] : [type]
  const fetched = await Promise.all(
    types.map(async (t) => {
      const { data, error } = await client.functions.invoke(
        `media-proxy?action=trending&type=${t}`
      )
      if (error) throw error
      return (data?.results ?? []).map((i: any) => mapSearchItem(i, t)) as SearchResult[]
    })
  )

  if (type === 'all') {
    const [movies, tv] = fetched
    const combined: SearchResult[] = []
    const maxLen = Math.max(movies.length, tv.length)
    for (let i = 0; i < maxLen && combined.length < 20; i++) {
      if (i < movies.length) combined.push(movies[i])
      if (i < tv.length) combined.push(tv[i])
    }
    return combined
  }

  return fetched[0].slice(0, 20)
}

/**
 * Fetch popular titles for a given media type, optionally filtered by genre.
 * Falls back to mock data in local dev.
 */
export async function fetchDiscover(type: MediaType, genreId?: number, page = 1): Promise<SearchResult[]> {
  if (!(isSupabaseConfigured && supabase)) {
    return MOCK_TRENDING.filter((r) => r.type === type).slice(0, 10)
  }

  const params = new URLSearchParams({ action: 'discover', type, page: String(page) })
  if (genreId) params.set('genre', String(genreId))

  const { data, error } = await supabase.functions.invoke(`media-proxy?${params.toString()}`)
  if (error) throw error
  return (data?.results ?? []).map((i: any) => mapSearchItem(i, type)) as SearchResult[]
}

export interface TitleImages {
  logoUrl: string | null
  backdropUrl: string | null
}

/**
 * Fetch the logo and backdrop for a title. TMDB returns images in its own
 * preferred order, so we always take the first valid entry.
 * Display-only — neither value is persisted.
 */
export async function fetchTitleImages(tmdbId: number, type: MediaType): Promise<TitleImages> {
  if (!(isSupabaseConfigured && supabase)) return { logoUrl: null, backdropUrl: null }

  try {
    const { data, error } = await supabase.functions.invoke(
      `media-proxy?action=images&id=${tmdbId}&type=${type}`
    )
    if (error) throw error

    // Logo — take the first English logo; fall back to first textless (iso null) logo.
    const allLogos = ((data?.logos ?? []) as any[]).filter((l) => l.file_path)
    const englishLogos = allLogos.filter((l) => l.iso_639_1 === 'en')
    const logo = englishLogos[0] ?? allLogos.filter((l) => l.iso_639_1 === null)[0]
    const logoUrl = logo ? `${TMDB_IMG}/original${logo.file_path}` : null

    // Backdrop — take the first available image.
    const backdrop = ((data?.backdrops ?? []) as any[]).find((b) => b.file_path)
    const backdropUrl = backdrop ? `${TMDB_IMG}/original${backdrop.file_path}` : null

    return { logoUrl, backdropUrl }
  } catch (e) {
    console.error('Error fetching title images:', e)
    return { logoUrl: null, backdropUrl: null }
  }
}
