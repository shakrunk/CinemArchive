// Supabase Edge Function: media-proxy
// Proxies TMDB and OMDb API requests with a KV caching layer.
// Deploy with: supabase functions deploy media-proxy

import { createClient } from 'jsr:@supabase/supabase-js@2'

const TMDB_BASE = 'https://api.themoviedb.org/3'
const OMDB_BASE = 'https://www.omdbapi.com'
const CACHE_TTL_SECONDS = 60 * 60 * 24 // 24 hours

const TMDB_API_KEY = Deno.env.get('TMDB_API_KEY')!
const OMDB_API_KEY = Deno.env.get('OMDB_API_KEY')!
const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!

const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY)

interface CorsHeaders {
  [key: string]: string
}

const corsHeaders: CorsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS, POST',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

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
  const cacheKey = `tmdb:search:${type}:${query}`
  const cached = await getCached(cacheKey)
  if (cached) return cached

  const url = `${TMDB_BASE}/search/${type}?api_key=${TMDB_API_KEY}&query=${encodeURIComponent(query)}&include_adult=false&language=en-US&page=1`
  const res = await fetch(url)
  const data = await res.json()

  await setCached(cacheKey, data)
  return data
}

async function getTMDBDetails(tmdbId: number, type: 'movie' | 'tv') {
  const cacheKey = `tmdb:details:${type}:${tmdbId}`
  const cached = await getCached(cacheKey)
  if (cached) return cached

  const appendix = type === 'movie' ? 'credits' : 'credits,seasons'
  const url = `${TMDB_BASE}/${type}/${tmdbId}?api_key=${TMDB_API_KEY}&append_to_response=${appendix}&language=en-US`
  const res = await fetch(url)
  const data = await res.json()

  await setCached(cacheKey, data)
  return data
}

async function getTMDBSeasonDetails(tmdbId: number, seasonNumber: number) {
  // Cache key bumped to v2 — v1 entries lack the credits field added by append_to_response
  const cacheKey = `tmdb:season:v2:${tmdbId}:${seasonNumber}`
  const cached = await getCached(cacheKey)
  if (cached) return cached

  const url = `${TMDB_BASE}/tv/${tmdbId}/season/${seasonNumber}?api_key=${TMDB_API_KEY}&language=en-US&append_to_response=credits`
  const res = await fetch(url)
  const data = await res.json()

  await setCached(cacheKey, data)
  return data
}

async function getOMDbRatings(imdbId: string) {
  const cacheKey = `omdb:${imdbId}`
  const cached = await getCached(cacheKey)
  if (cached) return cached

  const url = `${OMDB_BASE}/?apikey=${OMDB_API_KEY}&i=${imdbId}&tomatoes=true`
  const res = await fetch(url)
  const data = await res.json()

  await setCached(cacheKey, data)
  return data
}

async function getTMDBVideos(tmdbId: number, type: 'movie' | 'tv') {
  const cacheKey = `tmdb:videos:${type}:${tmdbId}`
  const cached = await getCached(cacheKey)
  if (cached) return cached

  const url = `${TMDB_BASE}/${type}/${tmdbId}/videos?api_key=${TMDB_API_KEY}&language=en-US`
  const res = await fetch(url)
  const data = await res.json()

  await setCached(cacheKey, data)
  return data
}

async function getTMDBImages(tmdbId: number, type: 'movie' | 'tv') {
  const cacheKey = `tmdb:images:${type}:${tmdbId}`
  const cached = await getCached(cacheKey)
  if (cached) return cached

  // include_image_language=en,null returns English-text logos plus textless ones.
  const url = `${TMDB_BASE}/${type}/${tmdbId}/images?api_key=${TMDB_API_KEY}&include_image_language=en,null`
  const res = await fetch(url)
  const data = await res.json()

  await setCached(cacheKey, data)
  return data
}

// ─── Router ──────────────────────────────────────────────────────────────────

Deno.serve(async (req: Request) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, { headers: corsHeaders })
  }

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
      case 'images': {
        const id = parseInt(url.searchParams.get('id') ?? '0', 10)
        const type = parseMediaType(url.searchParams.get('type'))
        if (!id) throw new Error('Missing id parameter')
        result = await getTMDBImages(id, type)
        break
      }
      default:
        throw new Error(`Unknown action: ${action}`)
    }

    return new Response(JSON.stringify(result), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    })
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Internal error'
    return new Response(JSON.stringify({ error: message }), {
      status: 400,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    })
  }
})
