// Jellyfin adapter. Runs in the browser against the user's own server.
//
// Auth is a per-user username/password sign-in (AuthenticateByName), not a
// server API key, so every request is scoped to that one Jellyfin account by
// construction: the access token can only see that user's play state, never
// other users' watches. The password is sent only to the user's own server and
// never stored. The resulting access token is kept on this device (it is
// revocable from the Jellyfin dashboard, like any other client's session).
//
// Unlike the one-shot Emby/Plex imports, Jellyfin is also polled for plays that
// happen after connecting. Those are never written to the library on their
// own: they are queued as `JellyfinPlay`s for the user to confirm.

import { parseGuids, toDateOnly, type ExternalIds, type SyncItem } from './core'

const CLIENT_HEADER = 'MediaBrowser Client="CinemArchive", Device="Browser", DeviceId="cinemarchive-web", Version="1.0"'

export function normalizeJellyfinUrl(input: string): string {
  const trimmed = input.trim().replace(/\/+$/, '')
  if (!trimmed) throw new Error('Enter your Jellyfin server address.')
  return /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`
}

export interface JellyfinSession {
  baseUrl: string
  token: string
  userId: string
  username: string
}

function authHeader(token?: string): string {
  return token ? `${CLIENT_HEADER}, Token="${token}"` : CLIENT_HEADER
}

export async function jellyfinSignIn(serverUrl: string, username: string, password: string): Promise<JellyfinSession> {
  const baseUrl = normalizeJellyfinUrl(serverUrl)
  let res: Response
  try {
    res = await fetch(`${baseUrl}/Users/AuthenticateByName`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: authHeader() },
      body: JSON.stringify({ Username: username, Pw: password }),
    })
  } catch {
    throw new Error('Could not reach that Jellyfin server. It must be reachable over https from this browser.')
  }
  if (res.status === 401 || res.status === 403) throw new Error('Jellyfin rejected that username or password.')
  if (!res.ok) throw new Error(`Jellyfin returned ${res.status}.`)
  const body = await res.json()
  if (!body.AccessToken || !body.User?.Id) throw new Error('Jellyfin returned an unexpected sign-in response.')
  return { baseUrl, token: body.AccessToken, userId: body.User.Id, username: body.User.Name ?? username }
}

/** Best-effort token revocation on disconnect; the local copy is dropped regardless. */
export async function jellyfinSignOut(session: JellyfinSession): Promise<void> {
  try {
    await fetch(`${session.baseUrl}/Sessions/Logout`, { method: 'POST', headers: { Authorization: authHeader(session.token) } })
  } catch { /* offline or CORS: the token can still be revoked from the Jellyfin dashboard */ }
}

export class JellyfinAuthError extends Error {
  constructor() {
    super('Your Jellyfin session expired. Sign in again to keep checking for new watches.')
  }
}

async function jellyfinGet<T>(session: JellyfinSession, path: string, params: URLSearchParams): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${session.baseUrl}${path}?${params}`, { headers: { Authorization: authHeader(session.token) } })
  } catch {
    throw new Error('Could not reach your Jellyfin server.')
  }
  if (res.status === 401) throw new JellyfinAuthError()
  if (!res.ok) throw new Error(`Jellyfin returned ${res.status}.`)
  return res.json() as Promise<T>
}

interface JellyfinItem {
  Id: string
  Type: 'Movie' | 'Series' | 'Episode'
  Name: string
  ProductionYear?: number
  ProviderIds?: Record<string, string>
  RecursiveItemCount?: number
  SeriesId?: string
  SeriesName?: string
  ParentIndexNumber?: number
  IndexNumber?: number
  UserData?: { Played?: boolean; PlayCount?: number; LastPlayedDate?: string; Rating?: number; UnplayedItemCount?: number }
}

function idsFrom(p: Record<string, string> = {}): ExternalIds {
  // Jellyfin keys are case-inconsistent across versions/plugins ("Tmdb", "tmdb").
  const get = (k: string) => p[k] ?? p[k.toLowerCase()]
  return parseGuids([
    get('Tmdb') ? `tmdb://${get('Tmdb')}` : undefined,
    get('Imdb') ? `imdb://${get('Imdb')}` : undefined,
    get('Tvdb') ? `tvdb://${get('Tvdb')}` : undefined,
  ])
}

/** One finished watch reported by Jellyfin, waiting for the user to confirm it. */
export interface JellyfinPlay {
  /** `<itemId>@<lastPlayedDate>` — stable per play, so a re-watch (new date) prompts again. */
  key: string
  kind: 'movie' | 'episode'
  /** Movie title, or the series name for an episode. */
  title: string
  year?: number
  /** Ids of the movie, or of the series for an episode. */
  ids: ExternalIds
  seasonNumber?: number
  episodeNumber?: number
  episodeName?: string
  /** YYYY-MM-DD (UTC), the day Jellyfin recorded the play. */
  date: string
  /** Full ISO timestamp; drives the "newer than the cursor" check. */
  playedAt: string
}

/** Plays newer than `sinceIso`, oldest first. `seriesIds` maps a Jellyfin series id to its provider ids. */
export function mapJellyfinPlays(
  items: JellyfinItem[],
  seriesIds: ReadonlyMap<string, ExternalIds>,
  sinceIso: string
): JellyfinPlay[] {
  const since = Date.parse(sinceIso)
  const out: JellyfinPlay[] = []
  for (const i of items) {
    if (i.Type !== 'Movie' && i.Type !== 'Episode') continue
    const ud = i.UserData
    if (!ud?.Played || !ud.LastPlayedDate) continue
    const playedAt = new Date(ud.LastPlayedDate)
    if (Number.isNaN(playedAt.getTime()) || playedAt.getTime() <= since) continue
    const date = toDateOnly(ud.LastPlayedDate)
    if (!date) continue
    const key = `${i.Id}@${ud.LastPlayedDate}`
    if (i.Type === 'Movie') {
      out.push({ key, kind: 'movie', title: i.Name, year: i.ProductionYear, ids: idsFrom(i.ProviderIds), date, playedAt: playedAt.toISOString() })
    } else {
      if (i.ParentIndexNumber == null || i.IndexNumber == null) continue
      out.push({
        key,
        kind: 'episode',
        title: i.SeriesName ?? i.Name,
        year: undefined,
        ids: i.SeriesId ? (seriesIds.get(i.SeriesId) ?? {}) : {},
        seasonNumber: i.ParentIndexNumber,
        episodeNumber: i.IndexNumber,
        episodeName: i.Name,
        date,
        playedAt: playedAt.toISOString(),
      })
    }
  }
  return out.sort((a, b) => a.playedAt.localeCompare(b.playedAt))
}

const RECENT_LIMIT = 100

/** Movies and episodes this account finished after `sinceIso`. */
export async function fetchJellyfinPlays(session: JellyfinSession, sinceIso: string): Promise<JellyfinPlay[]> {
  const body = await jellyfinGet<{ Items?: JellyfinItem[] }>(
    session,
    `/Users/${session.userId}/Items`,
    new URLSearchParams({
      Recursive: 'true',
      IncludeItemTypes: 'Movie,Episode',
      Filters: 'IsPlayed',
      SortBy: 'DatePlayed',
      SortOrder: 'Descending',
      Limit: String(RECENT_LIMIT),
      Fields: 'ProviderIds,ProductionYear',
      EnableUserData: 'true',
    })
  )
  const items = body.Items ?? []
  const wanted = [...new Set(items.filter((i) => i.Type === 'Episode' && i.SeriesId).map((i) => i.SeriesId as string))]
  const seriesIds = new Map<string, ExternalIds>()
  if (wanted.length > 0) {
    const series = await jellyfinGet<{ Items?: JellyfinItem[] }>(
      session,
      `/Users/${session.userId}/Items`,
      new URLSearchParams({ Ids: wanted.join(','), Fields: 'ProviderIds' })
    )
    for (const s of series.Items ?? []) seriesIds.set(s.Id, idsFrom(s.ProviderIds))
  }
  return mapJellyfinPlays(items, seriesIds, sinceIso)
}

/** Full-history import (movies and series), same shape the Emby/Plex imports use. */
export async function fetchJellyfinLibrary(session: JellyfinSession): Promise<SyncItem[]> {
  const body = await jellyfinGet<{ Items?: JellyfinItem[] }>(
    session,
    `/Users/${session.userId}/Items`,
    new URLSearchParams({
      Recursive: 'true',
      IncludeItemTypes: 'Movie,Series',
      Fields: 'ProviderIds,ProductionYear,RecursiveItemCount',
      EnableUserData: 'true',
    })
  )
  return mapJellyfinItems(body.Items ?? [])
}

export function mapJellyfinItems(items: JellyfinItem[]): SyncItem[] {
  const out: SyncItem[] = []
  for (const i of items) {
    if (i.Type !== 'Movie' && i.Type !== 'Series') continue
    const ud = i.UserData ?? {}
    const isMovie = i.Type === 'Movie'
    const watched = ud.Played === true || (isMovie && (ud.PlayCount ?? 0) > 0)
    const watching =
      !isMovie && !watched && ud.UnplayedItemCount != null && i.RecursiveItemCount != null && ud.UnplayedItemCount < i.RecursiveItemCount
    if (!watched && !watching) continue
    const date = toDateOnly(ud.LastPlayedDate)
    out.push({
      provider: 'jellyfin',
      externalId: i.Id,
      type: isMovie ? 'movie' : 'tv',
      title: i.Name,
      year: i.ProductionYear,
      ids: idsFrom(i.ProviderIds),
      status: watched ? 'watched' : 'watching',
      watchedDates: watched && isMovie && date ? [date] : [],
    })
  }
  return out
}
