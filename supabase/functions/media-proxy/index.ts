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

const WIKIDATA_SPARQL = 'https://query.wikidata.org/sparql'

// Wikidata: "Bechdel test" item and its "assessment outcome" (P9259) values.
// See Wikidata:WikiProject_Media_Representation/Model for how assessment (P5021) is modeled.
const BECHDEL_QID = 'Q4165246'
const BECHDEL_PASS_QID = 'Q105773168'
const BECHDEL_FAIL_QID = 'Q105773155'

async function wikidataSparql(sparql: string): Promise<any> {
  const url = `${WIKIDATA_SPARQL}?query=${encodeURIComponent(sparql)}&format=json`
  const res = await fetch(url, {
    headers: {
      'Accept': 'application/sparql-results+json',
      // Wikidata's query service requires an identifying User-Agent.
      'User-Agent': 'CinemArchive/1.0 (https://cinemarchive.kumarfamilynet.work/; media-proxy edge function)',
    },
  })
  return res.json()
}

// getCached/setCached can't distinguish "no cache row" from "cached value was null",
// since both come back as `null`. Wrap negative results in a sentinel so lookups
// that legitimately resolve to "no data" (most titles, for awards/Bechdel) still
// hit the cache on repeat instead of re-querying Wikidata every time.
const CACHE_NO_DATA = { __noData: true } as const

async function cachedWikidataLookup<T>(cacheKey: string, resolve: () => Promise<T | null>): Promise<T | null> {
  const cached = await getCached(cacheKey)
  if (cached !== null) {
    return (cached as any)?.__noData ? null : (cached as T)
  }

  let result: T | null = null
  try {
    result = await resolve()
  } catch {
    // Best-effort — Wikidata enrichment is a nice-to-have, not a critical path.
  }

  await setCached(cacheKey, result === null ? CACHE_NO_DATA : result)
  return result
}

// Resolves the canonical Rotten Tomatoes page for a title via Wikidata: finds the
// item with a matching IMDb ID (P345) and reads its Rotten Tomatoes ID (P1258),
// which already encodes the "m/" or "tv/" path segment (e.g. "m/titanic").
async function getWikidataRTUrl(imdbId: string): Promise<string | null> {
  return cachedWikidataLookup(`wikidata:rt:${imdbId}`, async () => {
    const sparql = `SELECT ?rt WHERE { ?item wdt:P345 "${imdbId}". ?item wdt:P1258 ?rt. } LIMIT 1`
    const data = await wikidataSparql(sparql)
    const rtId = data?.results?.bindings?.[0]?.rt?.value
    return rtId ? `https://www.rottentomatoes.com/${rtId}` : null
  })
}

// Counts "award received" (P166) statements on the matching Wikidata item. Returns
// null only when no Wikidata item matches the IMDb ID at all — a matched item with
// zero recorded awards legitimately returns 0 (Wikidata's award coverage is sparse
// outside high-profile titles, so 0 mostly means "untracked" rather than "none won").
async function getWikidataAwardsCount(imdbId: string): Promise<number | null> {
  return cachedWikidataLookup(`wikidata:awards:${imdbId}`, async () => {
    const sparql = `SELECT ?item (COUNT(?award) as ?c) WHERE {
      ?item wdt:P345 "${imdbId}".
      OPTIONAL { ?item wdt:P166 ?award. }
    } GROUP BY ?item`
    const data = await wikidataSparql(sparql)
    const binding = data?.results?.bindings?.[0]
    return binding ? parseInt(binding.c.value, 10) : null
  })
}

export interface BechdelResult {
  outcome: 'pass' | 'fail'
  score: string | null
}

// Reads the Bechdel test assessment (P5021 -> Bechdel test, qualified by assessment
// outcome P9259) from the Wikidata item matching the IMDb ID. bechdeltest.com's own
// API — the underlying source for this data — was discontinued, so Wikidata's
// mirror of it is the only viable route.
async function getWikidataBechdel(imdbId: string): Promise<BechdelResult | null> {
  return cachedWikidataLookup(`wikidata:bechdel:${imdbId}`, async () => {
    const sparql = `SELECT ?outcome ?score WHERE {
      ?item wdt:P345 "${imdbId}".
      ?item p:P5021 ?stmt.
      ?stmt ps:P5021 wd:${BECHDEL_QID}.
      ?stmt pq:P9259 ?outcome.
      OPTIONAL { ?stmt pq:P444 ?score. }
    } LIMIT 1`
    const data = await wikidataSparql(sparql)
    const binding = data?.results?.bindings?.[0]
    if (!binding) return null
    const outcomeUri: string = binding.outcome.value
    if (outcomeUri.endsWith(BECHDEL_PASS_QID)) return { outcome: 'pass', score: binding.score?.value ?? null }
    if (outcomeUri.endsWith(BECHDEL_FAIL_QID)) return { outcome: 'fail', score: binding.score?.value ?? null }
    return null
  })
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
      case 'rt_link': {
        const imdbId = url.searchParams.get('imdb') ?? ''
        if (!imdbId) throw new Error('Missing imdb parameter')
        result = { rtUrl: await getWikidataRTUrl(imdbId) }
        break
      }
      case 'accolades': {
        const imdbId = url.searchParams.get('imdb') ?? ''
        if (!imdbId) throw new Error('Missing imdb parameter')
        const [awardsCount, bechdel] = await Promise.all([
          getWikidataAwardsCount(imdbId),
          getWikidataBechdel(imdbId),
        ])
        result = { awardsCount, bechdel }
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
