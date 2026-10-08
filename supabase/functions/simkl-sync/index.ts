// Supabase Edge Function: simkl-sync
// Authenticated proxy to the Simkl API. Simkl's CORS policy is undocumented, so
// the browser never talks to it directly; tokens live in integration_secrets
// (service-role only) and never reach the client.
//
// Actions (POST JSON body { action, ... }):
//   start  { write?: boolean }          -> device code to show the user
//   poll   { device_code, write? }      -> { pending } | { connected }
//   items  {}                           -> normalized movies/shows for import
//   disconnect {}                       -> deletes the connection + tokens
// Always responds 200 with { error } on failure (functions.invoke() discards
// the body of non-2xx responses). Env: SIMKL_CLIENT_ID (public client id; the
// device flow needs no client secret).
// Deploy with: supabase functions deploy simkl-sync

import { createClient } from 'jsr:@supabase/supabase-js@2'
import { buildCorsHeaders, handleOptions, errorMessage } from '../_shared/http.ts'

const SIMKL_BASE = 'https://api.simkl.com'
const APP_NAME = 'cinemarchive'
const APP_VERSION = '1.0'
const UA = `${APP_NAME}/${APP_VERSION}`
const CLIENT_ID = Deno.env.get('SIMKL_CLIENT_ID') ?? ''
const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!
const SUPABASE_ANON_KEY = Deno.env.get('SUPABASE_ANON_KEY')!
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!

const admin = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY)
const corsHeaders = buildCorsHeaders('POST, OPTIONS')

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } })
}

async function simklForm(path: string, params: Record<string, string>) {
  const res = await fetch(`${SIMKL_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'User-Agent': UA },
    body: new URLSearchParams(params),
  })
  let data: Record<string, unknown> = {}
  try { data = await res.json() } catch { /* empty body */ }
  return { status: res.status, data }
}

interface StoredSecrets {
  access_token: string
  refresh_token?: string
  expires_at?: number // epoch ms
}

async function getConnection(userId: string) {
  const { data } = await admin
    .from('integration_connections')
    .select('id, direction')
    .eq('user_id', userId)
    .eq('provider', 'simkl')
    .maybeSingle()
  return data as { id: string; direction: string } | null
}

async function loadSecrets(connectionId: string): Promise<StoredSecrets | null> {
  const { data } = await admin.from('integration_secrets').select('secrets').eq('connection_id', connectionId).maybeSingle()
  return (data?.secrets as StoredSecrets | undefined) ?? null
}

async function saveSecrets(connectionId: string, userId: string, secrets: StoredSecrets) {
  const { error } = await admin
    .from('integration_secrets')
    .upsert({ connection_id: connectionId, user_id: userId, secrets, updated_at: new Date().toISOString() }, { onConflict: 'connection_id' })
  if (error) throw error
}

function tokenToSecrets(data: Record<string, unknown>, prev?: StoredSecrets): StoredSecrets {
  const expiresIn = typeof data.expires_in === 'number' ? data.expires_in : 7 * 24 * 3600
  return {
    access_token: String(data.access_token),
    refresh_token: (data.refresh_token as string | undefined) ?? prev?.refresh_token,
    expires_at: Date.now() + expiresIn * 1000,
  }
}

/** Returns a usable access token, refreshing within a day of expiry. */
async function accessToken(connectionId: string, userId: string): Promise<string> {
  const secrets = await loadSecrets(connectionId)
  if (!secrets) throw new Error('Simkl is not connected. Reconnect it from Settings.')
  const stale = secrets.expires_at != null && secrets.expires_at - Date.now() < 24 * 3600 * 1000
  if (!stale) return secrets.access_token
  if (!secrets.refresh_token) throw new Error('Simkl session expired. Reconnect it from Settings.')
  const { status, data } = await simklForm('/oauth2/token', {
    grant_type: 'refresh_token',
    client_id: CLIENT_ID,
    refresh_token: secrets.refresh_token,
  })
  if (status !== 200 || !data.access_token) throw new Error('Simkl session expired. Reconnect it from Settings.')
  const next = tokenToSecrets(data, secrets)
  await saveSecrets(connectionId, userId, next)
  return next.access_token
}

type SimklStatus = 'completed' | 'plantowatch' | 'watching' | 'hold' | 'dropped'
const STATUS_MAP: Record<SimklStatus, 'watched' | 'watchlist' | 'watching' | 'dropped'> = {
  completed: 'watched',
  plantowatch: 'watchlist',
  watching: 'watching',
  hold: 'watching',
  dropped: 'dropped',
}

interface NormalizedItem {
  externalId: string
  type: 'movie' | 'tv'
  title: string
  year?: number
  ids: { tmdb?: number; imdb?: string; tvdb?: number }
  status: 'watched' | 'watchlist' | 'watching' | 'dropped'
  rating?: number // Simkl 1–10; the client converts
  lastWatchedAt?: string
}

function normalize(entry: any, kind: 'movie' | 'show'): NormalizedItem | null {
  const media = entry?.[kind]
  if (!media?.title) return null
  const ids = media.ids ?? {}
  const tmdb = parseInt(ids.tmdb, 10)
  const tvdb = parseInt(ids.tvdb, 10)
  return {
    externalId: String(ids.simkl ?? ids.imdb ?? media.title),
    type: kind === 'movie' ? 'movie' : 'tv',
    title: media.title,
    year: typeof media.year === 'number' ? media.year : undefined,
    ids: {
      tmdb: Number.isFinite(tmdb) ? tmdb : undefined,
      imdb: typeof ids.imdb === 'string' && /^tt\d+$/.test(ids.imdb) ? ids.imdb : undefined,
      tvdb: Number.isFinite(tvdb) ? tvdb : undefined,
    },
    status: STATUS_MAP[entry.status as SimklStatus] ?? 'watchlist',
    rating: typeof entry.user_rating === 'number' ? entry.user_rating : undefined,
    lastWatchedAt: entry.last_watched_at ?? undefined,
  }
}

async function fetchAllItems(token: string, type: 'movies' | 'shows', dateFrom?: string): Promise<any[]> {
  const params = new URLSearchParams({ client_id: CLIENT_ID, 'app-name': APP_NAME, 'app-version': APP_VERSION })
  if (dateFrom) params.set('date_from', dateFrom)
  const res = await fetch(`${SIMKL_BASE}/sync/all-items/${type}?${params}`, {
    headers: { 'User-Agent': UA, Authorization: `Bearer ${token}` },
  })
  if (res.status === 401) throw new Error('Simkl session expired. Reconnect it from Settings.')
  if (res.status === 204) return []
  if (!res.ok) throw new Error(`Simkl returned ${res.status}`)
  const body = await res.json()
  return Array.isArray(body?.[type]) ? body[type] : []
}

Deno.serve(async (req: Request) => {
  const preflight = handleOptions(req, corsHeaders)
  if (preflight) return preflight

  try {
    if (!CLIENT_ID) return json({ error: 'Simkl sync is not configured on this server.' })

    const jwt = (req.headers.get('Authorization') ?? '').replace(/^Bearer\s+/i, '')
    const userClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY)
    const { data: userData } = await userClient.auth.getUser(jwt)
    const userId = userData.user?.id
    if (!userId) return json({ error: 'Not signed in.' })

    const body = await req.json().catch(() => ({}))
    switch (body.action) {
      case 'start': {
        // Omitting scope grants read-only; media:write only after explicit two-way opt-in.
        const params: Record<string, string> = { client_id: CLIENT_ID }
        if (body.write === true) params.scope = 'media:read media:write'
        const { status, data } = await simklForm('/oauth2/device', params)
        if (status !== 200) return json({ error: 'Could not start Simkl sign-in.' })
        return json({
          device_code: data.device_code,
          user_code: data.user_code,
          verification_uri: data.verification_uri,
          verification_uri_complete: data.verification_uri_complete,
          interval: data.interval ?? 5,
          expires_in: data.expires_in ?? 900,
        })
      }
      case 'poll': {
        if (typeof body.device_code !== 'string') return json({ error: 'Missing device_code.' })
        const { status, data } = await simklForm('/oauth2/token', {
          grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
          client_id: CLIENT_ID,
          device_code: body.device_code,
        })
        if (status === 400 && data.error === 'authorization_pending') return json({ pending: true })
        if (status === 400 && data.error === 'slow_down') return json({ pending: true, slowDown: true })
        if (status !== 200 || !data.access_token) return json({ error: 'Simkl sign-in expired. Start again.' })

        const direction = body.write === true && String(data.scope ?? '').includes('media:write') ? 'two_way' : 'import'
        const { data: conn, error } = await admin
          .from('integration_connections')
          .upsert({ user_id: userId, provider: 'simkl', direction }, { onConflict: 'user_id,provider' })
          .select('id')
          .single()
        if (error) throw error
        await saveSecrets(conn.id, userId, tokenToSecrets(data))
        return json({ connected: true, direction })
      }
      case 'items': {
        const conn = await getConnection(userId)
        if (!conn) return json({ error: 'Simkl is not connected.' })
        const token = await accessToken(conn.id, userId)
        // Sequential on purpose: Simkl only allows parallel calls on cached endpoints.
        const movies = await fetchAllItems(token, 'movies')
        const shows = await fetchAllItems(token, 'shows')
        const items = [
          ...movies.map((m) => normalize(m, 'movie')),
          ...shows.map((s) => normalize(s, 'show')),
        ].filter((i): i is NormalizedItem => i !== null)
        await admin.from('integration_connections').update({ last_synced_at: new Date().toISOString() }).eq('id', conn.id)
        return json({ items, direction: conn.direction })
      }
      case 'disconnect': {
        // integration_secrets cascades from the connection row.
        await admin.from('integration_connections').delete().eq('user_id', userId).eq('provider', 'simkl')
        return json({ disconnected: true })
      }
      default:
        return json({ error: `Unknown action: ${body.action}` })
    }
  } catch (err) {
    console.error('simkl-sync failed:', err)
    return json({ error: errorMessage(err) })
  }
})
