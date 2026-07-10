// Supabase Edge Function: media-proxy
// Proxies TMDB and OMDb API requests with a KV caching layer.
// Deploy with: supabase functions deploy media-proxy

import { createClient } from 'jsr:@supabase/supabase-js@2'
import { buildCorsHeaders, handleOptions, errorMessage } from '../_shared/http.ts'

const TMDB_BASE = 'https://api.themoviedb.org/3'
const OMDB_BASE = 'https://www.omdbapi.com'
const CACHE_TTL_SECONDS = 60 * 60 * 24 // 24 hours

const TMDB_API_KEY = Deno.env.get('TMDB_API_KEY')!
const OMDB_API_KEY = Deno.env.get('OMDB_API_KEY')!
const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY)

const corsHeaders = buildCorsHeaders('GET, OPTIONS, POST')

async function getCached(key: string): Promise<unknown | null> {
  try {
    const { data } = await supabase
      .from('api_cache')
      .select('response, expires_at')
      .eq('cache_key', key)
      .single()

    if (data && new Date(data.expires_at) > new Date()) {
      return data.response
    }
  } catch {
    // Cache miss or table doesn't exist yet — fall through
  }
  return null
}

async function setCached(key: string, response: unknown): Promise<void> {
  try {
    const expiresAt = new Date(Date.now() + CACHE_TTL_SECONDS * 1000).toISOString()
    await supabase.from('api_cache').upsert(
      { cache_key: key, response, expires_at: expiresAt },
      { onConflict: 'cache_key' }
    )
  } catch {
    // Best-effort — don't fail if cache write fails
  }
}

async function cachedFetch(cacheKey: string, url: string): Promise<unknown> {
  const cached = await getCached(cacheKey)
  if (cached) return cached

  const res = await fetch(url)
  const data = await res.json()

  await setCached(cacheKey, data)
  return data
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function parseMediaType(value: string | null): 'movie' | 'tv' {
  const type = value ?? 'movie'
  if (type !== 'movie' && type !== 'tv') {
    throw new Error(`Invalid type parameter: ${type}`)
  }
  return type
}

// ─── Handlers ────────────────────────────────────────────────────────────────

async function searchTMDB(query: string, type: 'movie' | 'tv') {
  const url = `${TMDB_BASE}/search/${type}?api_key=${TMDB_API_KEY}&query=${encodeURIComponent(query)}&include_adult=false&language=en-US&page=1`
  return cachedFetch(`tmdb:search:${type}:${query}`, url)
}

async function getTMDBDetails(tmdbId: number, type: 'movie' | 'tv') {
  // TV: aggregate_credits gives total_episode_count per cast member (standard credits doesn't).
  // credits is still needed for crew. Movies don't have aggregate_credits.
  const appendix = type === 'movie' ? 'credits,release_dates' : 'credits,aggregate_credits,seasons,content_ratings,external_ids'
  const url = `${TMDB_BASE}/${type}/${tmdbId}?api_key=${TMDB_API_KEY}&append_to_response=${appendix}&language=en-US`
  // Cache key bumped to v3 — v3 adds aggregate_credits for TV (episode counts per cast member).
  return cachedFetch(`tmdb:details:v3:${type}:${tmdbId}`, url)
}

async function getTMDBSeasonDetails(tmdbId: number, seasonNumber: number) {
  const url = `${TMDB_BASE}/tv/${tmdbId}/season/${seasonNumber}?api_key=${TMDB_API_KEY}&language=en-US&append_to_response=credits`
  // Cache key bumped to v3 — ensures episode_count is present in season credits cast
  return cachedFetch(`tmdb:season:v3:${tmdbId}:${seasonNumber}`, url)
}

async function getOMDbRatings(imdbId: string) {
  const url = `${OMDB_BASE}/?apikey=${OMDB_API_KEY}&i=${imdbId}&tomatoes=true`
  return cachedFetch(`omdb:${imdbId}`, url)
}

async function getTMDBVideos(tmdbId: number, type: 'movie' | 'tv') {
  const url = `${TMDB_BASE}/${type}/${tmdbId}/videos?api_key=${TMDB_API_KEY}&language=en-US`
  return cachedFetch(`tmdb:videos:${type}:${tmdbId}`, url)
}

async function getTMDBWatchProviders(tmdbId: number, type: 'movie' | 'tv') {
  const url = `${TMDB_BASE}/${type}/${tmdbId}/watch/providers?api_key=${TMDB_API_KEY}`
  return cachedFetch(`tmdb:watch:${type}:${tmdbId}`, url)
}

async function getTMDBImages(tmdbId: number, type: 'movie' | 'tv') {
  // include_image_language=en,null returns English-text logos plus textless ones.
  const url = `${TMDB_BASE}/${type}/${tmdbId}/images?api_key=${TMDB_API_KEY}&include_image_language=en,null`
  return cachedFetch(`tmdb:images:${type}:${tmdbId}`, url)
}

async function getTMDBTrending(type: 'movie' | 'tv', page = 1) {
  const url = `${TMDB_BASE}/trending/${type}/week?api_key=${TMDB_API_KEY}&language=en-US&page=${page}`
  return cachedFetch(`tmdb:trending:${type}:week:p${page}`, url)
}

async function getTMDBDiscover(type: 'movie' | 'tv', genreId?: number, page = 1, companyId?: number) {
  let url = `${TMDB_BASE}/discover/${type}?api_key=${TMDB_API_KEY}&language=en-US&sort_by=popularity.desc&include_adult=false&page=${page}`
  if (genreId) url += `&with_genres=${genreId}`
  if (companyId) url += `&with_companies=${companyId}`
  return cachedFetch(`tmdb:discover:${type}:${genreId ?? 'all'}:${companyId ?? 'all'}:${page}`, url)
}

async function searchPersonTMDB(query: string) {
  const url = `${TMDB_BASE}/search/person?api_key=${TMDB_API_KEY}&query=${encodeURIComponent(query)}&include_adult=false&language=en-US&page=1`
  return cachedFetch(`tmdb:person_search:${query}`, url)
}

async function getPersonCredits(personId: number) {
  const url = `${TMDB_BASE}/person/${personId}/combined_credits?api_key=${TMDB_API_KEY}&language=en-US`
  return cachedFetch(`tmdb:person_credits:${personId}`, url)
}

async function searchCompanyTMDB(query: string) {
  const url = `${TMDB_BASE}/search/company?api_key=${TMDB_API_KEY}&query=${encodeURIComponent(query)}&page=1`
  return cachedFetch(`tmdb:company_search:${query}`, url)
}

// ─── Router ──────────────────────────────────────────────────────────────────

Deno.serve(async (req: Request) => {
  const optionsResponse = handleOptions(req, corsHeaders)
  if (optionsResponse) return optionsResponse

  try {
    const url = new URL(req.url)
    const action = url.searchParams.get('action')

    let result: unknown

    switch (action) {
      case 'search': {
        const query = url.searchParams.get('q') ?? ''
        const type = parseMediaType(url.searchParams.get('type'))
        if (!query) throw new Error('Missing query parameter')
        result = await searchTMDB(query, type)
        break
      }
      case 'details': {
        const id = parseInt(url.searchParams.get('id') ?? '0', 10)
        const type = parseMediaType(url.searchParams.get('type'))
        if (!id) throw new Error('Missing id parameter')
        result = await getTMDBDetails(id, type)
        break
      }
      case 'season': {
        const id = parseInt(url.searchParams.get('id') ?? '0', 10)
        const seasonNum = parseInt(url.searchParams.get('season') ?? '0', 10)
        if (!id || !seasonNum) throw new Error('Missing id or season parameter')
        result = await getTMDBSeasonDetails(id, seasonNum)
        break
      }
      case 'ratings': {
        const imdbId = url.searchParams.get('imdb') ?? ''
        if (!imdbId) throw new Error('Missing imdb parameter')
        result = await getOMDbRatings(imdbId)
        break
      }
      case 'videos': {
        const id = parseInt(url.searchParams.get('id') ?? '0', 10)
        const type = parseMediaType(url.searchParams.get('type'))
        if (!id) throw new Error('Missing id parameter')
        result = await getTMDBVideos(id, type)
        break
      }
      case 'watch_providers': {
        const id = parseInt(url.searchParams.get('id') ?? '0', 10)
        const type = parseMediaType(url.searchParams.get('type'))
        if (!id) throw new Error('Missing id parameter')
        result = await getTMDBWatchProviders(id, type)
        break
      }
      case 'images': {
        const id = parseInt(url.searchParams.get('id') ?? '0', 10)
        const type = parseMediaType(url.searchParams.get('type'))
        if (!id) throw new Error('Missing id parameter')
        result = await getTMDBImages(id, type)
        break
      }
      case 'trending': {
        const type = parseMediaType(url.searchParams.get('type'))
        const page = parseInt(url.searchParams.get('page') ?? '1', 10)
        result = await getTMDBTrending(type, page)
        break
      }
      case 'discover': {
        const type = parseMediaType(url.searchParams.get('type'))
        const rawGenre = url.searchParams.get('genre')
        const genreId = rawGenre ? parseInt(rawGenre, 10) : undefined
        const rawCompany = url.searchParams.get('company')
        const companyId = rawCompany ? parseInt(rawCompany, 10) : undefined
        const page = parseInt(url.searchParams.get('page') ?? '1', 10)
        result = await getTMDBDiscover(type, genreId, page, companyId)
        break
      }
      case 'person_search': {
        const query = url.searchParams.get('q') ?? ''
        if (!query) throw new Error('Missing query parameter')
        result = await searchPersonTMDB(query)
        break
      }
      case 'person_credits': {
        const id = parseInt(url.searchParams.get('id') ?? '0', 10)
        if (!id) throw new Error('Missing id parameter')
        result = await getPersonCredits(id)
        break
      }
      case 'company_search': {
        const query = url.searchParams.get('q') ?? ''
        if (!query) throw new Error('Missing query parameter')
        result = await searchCompanyTMDB(query)
        break
      }
      default:
        throw new Error(`Unknown action: ${action}`)
    }

    return new Response(JSON.stringify(result), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    })
  } catch (err) {
    return new Response(JSON.stringify({ error: errorMessage(err) }), {
      status: 400,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    })
  }
})
