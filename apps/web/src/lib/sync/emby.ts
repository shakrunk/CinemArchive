// Emby adapter. Runs in the browser against the user's own server. The user
// signs in with their Emby username/password, which is sent only to their own
// server to obtain an access token; neither is stored. The token is held in
// memory for the sync only.
//
// As with Plex, only play state is imported: played items and ratings.

import { parseGuids, ratingFromTen, toDateOnly, type SyncItem } from './core'

const AUTH_HEADER = 'MediaBrowser Client="CinemArchive", Device="Browser", DeviceId="cinemarchive-web", Version="1.0"'

export function normalizeEmbyUrl(input: string): string {
  const trimmed = input.trim().replace(/\/+$/, '')
  if (!trimmed) throw new Error('Enter your Emby server address.')
  const withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`
  return withScheme.replace(/\/emby$/i, '')
}

export interface EmbySession {
  baseUrl: string
  token: string
  userId: string
  username: string
}

export async function embySignIn(serverUrl: string, username: string, password: string): Promise<EmbySession> {
  const baseUrl = normalizeEmbyUrl(serverUrl)
  let res: Response
  try {
    res = await fetch(`${baseUrl}/emby/Users/AuthenticateByName`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Emby-Authorization': AUTH_HEADER },
      body: JSON.stringify({ Username: username, Pw: password }),
    })
  } catch {
    throw new Error('Could not reach that Emby server. It must be reachable over https from this browser.')
  }
  if (res.status === 401 || res.status === 403) throw new Error('Emby rejected that username or password.')
  if (!res.ok) throw new Error(`Emby returned ${res.status}.`)
  const body = await res.json()
  return { baseUrl, token: body.AccessToken, userId: body.User?.Id, username: body.User?.Name ?? username }
}

interface EmbyItem {
  Id: string
  Type: 'Movie' | 'Series'
  Name: string
  ProductionYear?: number
  ProviderIds?: Record<string, string>
  UserData?: { Played?: boolean; PlayCount?: number; LastPlayedDate?: string; Rating?: number }
}

export function mapEmbyItems(items: EmbyItem[]): SyncItem[] {
  const out: SyncItem[] = []
  for (const i of items) {
    if (i.Type !== 'Movie' && i.Type !== 'Series') continue
    const ud = i.UserData ?? {}
    const watched = ud.Played === true || (ud.PlayCount ?? 0) > 0
    // Emby's per-user Rating is on a 0–10 scale when set.
    const rating = ratingFromTen(ud.Rating)
    if (!watched && rating == null) continue
    const p = i.ProviderIds ?? {}
    const date = toDateOnly(ud.LastPlayedDate)
    out.push({
      provider: 'emby',
      externalId: i.Id,
      type: i.Type === 'Movie' ? 'movie' : 'tv',
      title: i.Name,
      year: i.ProductionYear,
      ids: parseGuids([
        p.Tmdb ? `tmdb://${p.Tmdb}` : undefined,
        p.Imdb ? `imdb://${p.Imdb}` : undefined,
        p.Tvdb ? `tvdb://${p.Tvdb}` : undefined,
      ]),
      status: watched ? 'watched' : 'watchlist',
      rating,
      watchedDates: watched && i.Type === 'Movie' && date ? [date] : [],
    })
  }
  return out
}

export async function fetchEmbyItems(session: EmbySession): Promise<SyncItem[]> {
  const params = new URLSearchParams({
    Recursive: 'true',
    IncludeItemTypes: 'Movie,Series',
    Fields: 'ProviderIds,ProductionYear',
    EnableUserData: 'true',
  })
  const res = await fetch(`${session.baseUrl}/emby/Users/${session.userId}/Items?${params}`, {
    headers: { 'X-Emby-Token': session.token },
  })
  if (!res.ok) throw new Error(`Emby returned ${res.status}.`)
  const body = await res.json()
  return mapEmbyItems(body.Items ?? [])
}
