// Plex adapter. Runs in the browser against the user's own server (a Supabase
// Edge Function can't reach a home LAN). Auth uses Plex's PIN flow, so the user
// never types credentials here; the token is held in memory for the sync only
// and is never persisted — a Plex token grants full account access.
//
// Only play state is imported (watched items and ratings): a Plex library is
// what the server owns, not what the user has chosen to track, so unwatched,
// unrated items are skipped.

import { parseGuids, ratingFromTen, toDateOnly, type SyncItem } from './core'

const PLEX_TV = 'https://plex.tv/api/v2'
const PRODUCT = 'CinemArchive'

function clientId(): string {
  try {
    let id = localStorage.getItem('cinemarchive:plex-client-id')
    if (!id) {
      id = crypto.randomUUID()
      localStorage.setItem('cinemarchive:plex-client-id', id)
    }
    return id
  } catch {
    return 'cinemarchive-web'
  }
}

function plexHeaders(token?: string): Record<string, string> {
  const h: Record<string, string> = {
    Accept: 'application/json',
    'X-Plex-Product': PRODUCT,
    'X-Plex-Client-Identifier': clientId(),
  }
  if (token) h['X-Plex-Token'] = token
  return h
}

export interface PlexPin {
  id: number
  code: string
  /** Open this in a new tab so the user can approve the app. */
  authUrl: string
}

export async function startPlexPin(): Promise<PlexPin> {
  const res = await fetch(`${PLEX_TV}/pins?strong=true`, { method: 'POST', headers: plexHeaders() })
  if (!res.ok) throw new Error('Could not start Plex sign-in.')
  const pin = await res.json()
  const params = new URLSearchParams({
    clientID: clientId(),
    code: pin.code,
    'context[device][product]': PRODUCT,
  })
  return { id: pin.id, code: pin.code, authUrl: `https://app.plex.tv/auth#?${params}` }
}

/** Returns the auth token once the user approves, otherwise null. */
export async function pollPlexPin(pin: PlexPin): Promise<string | null> {
  const res = await fetch(`${PLEX_TV}/pins/${pin.id}?code=${encodeURIComponent(pin.code)}`, { headers: plexHeaders() })
  if (!res.ok) throw new Error('Plex sign-in expired. Start again.')
  const body = await res.json()
  return body.authToken ?? null
}

export interface PlexServer {
  name: string
  /** Preferred https connection (plex.direct) so it works from an https page. */
  uri: string
}

export async function listPlexServers(token: string): Promise<PlexServer[]> {
  const res = await fetch(`${PLEX_TV}/resources?includeHttps=1&includeRelay=0`, { headers: plexHeaders(token) })
  if (!res.ok) throw new Error('Could not list your Plex servers.')
  const resources: any[] = await res.json()
  const servers: PlexServer[] = []
  for (const r of resources) {
    if (!String(r.provides ?? '').includes('server') || !r.owned) continue
    const conns: any[] = r.connections ?? []
    const best = conns.find((c) => c.protocol === 'https' && !c.local) ?? conns.find((c) => c.protocol === 'https')
    if (best) servers.push({ name: r.name, uri: best.uri })
  }
  return servers
}

interface PlexMetadata {
  ratingKey: string
  type: 'movie' | 'show'
  title: string
  year?: number
  userRating?: number
  viewCount?: number
  lastViewedAt?: number
  Guid?: Array<{ id: string }>
}

export function mapPlexItems(items: PlexMetadata[]): SyncItem[] {
  const out: SyncItem[] = []
  for (const m of items) {
    if (m.type !== 'movie' && m.type !== 'show') continue
    const watched = (m.viewCount ?? 0) > 0
    const rating = ratingFromTen(m.userRating)
    if (!watched && rating == null) continue
    const date = toDateOnly(m.lastViewedAt)
    out.push({
      provider: 'plex',
      externalId: m.ratingKey,
      type: m.type === 'movie' ? 'movie' : 'tv',
      title: m.title,
      year: m.year,
      ids: parseGuids((m.Guid ?? []).map((g) => g.id)),
      status: watched ? 'watched' : 'watchlist',
      rating,
      watchedDates: watched && m.type === 'movie' && date ? [date] : [],
    })
  }
  return out
}

export async function fetchPlexItems(serverUri: string, token: string): Promise<SyncItem[]> {
  const base = serverUri.replace(/\/+$/, '')
  const sectionsRes = await fetch(`${base}/library/sections`, { headers: plexHeaders(token) })
  if (!sectionsRes.ok) throw new Error('Could not reach your Plex server.')
  const sections: any[] = (await sectionsRes.json())?.MediaContainer?.Directory ?? []
  const all: PlexMetadata[] = []
  for (const s of sections) {
    if (s.type !== 'movie' && s.type !== 'show') continue
    const res = await fetch(`${base}/library/sections/${s.key}/all?includeGuids=1`, { headers: plexHeaders(token) })
    if (!res.ok) continue
    all.push(...((await res.json())?.MediaContainer?.Metadata ?? []))
  }
  return mapPlexItems(all)
}
