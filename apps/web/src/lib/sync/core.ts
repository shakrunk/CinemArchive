// Provider-agnostic core for third-party sync (Simkl, Plex, Emby, Letterboxd).
// Every adapter maps its payload to `SyncItem`s; this module resolves them to
// TMDB, merges them into the library without ever overwriting local data, and
// reports what to insert/update. Pure helpers are unit-tested; the resolve
// pipeline owns the network I/O. TV is title-level only (status/rating) —
// episode-level watch events are a follow-up.

import { searchMedia, fetchMediaDetails, findMediaByExternalId, type SearchResult } from '../media'
import { buildTitle, mapPool, pickBestMatch } from '../letterboxd-import'
import type { MediaType, Title, Viewing, WatchStatus } from '../../store/mockData'

export type SyncProvider = 'letterboxd' | 'simkl' | 'plex' | 'emby'

export interface ExternalIds {
  tmdb?: number
  imdb?: string
  tvdb?: number
}

export interface SyncItem {
  provider: SyncProvider
  /** Stable id within the provider — drives external_title_links dedupe. */
  externalId: string
  type: MediaType
  title: string
  year?: number
  ids: ExternalIds
  status: WatchStatus
  /** 0.5–5 half-star scale (this app's scale). */
  rating?: number
  /** YYYY-MM-DD, any order. */
  watchedDates: string[]
}

// ─── Pure helpers ────────────────────────────────────────────────────────────

/** Provider 1–10 rating -> app 0.5–5 half stars. 0/undefined/NaN -> undefined. */
export function ratingFromTen(value: number | null | undefined): number | undefined {
  if (value == null || !Number.isFinite(value) || value <= 0) return undefined
  const stars = Math.round(Math.min(value, 10)) / 2
  return Math.max(0.5, stars)
}

/** App 0.5–5 rating -> provider 1–10. */
export function ratingToTen(value: number | null | undefined): number | undefined {
  if (value == null || !Number.isFinite(value) || value <= 0) return undefined
  return Math.min(10, Math.max(1, Math.round(value * 2)))
}

/** Parse Plex/Emby-style guid strings: `tmdb://603`, `imdb://tt0133093`,
 *  `tvdb://78901`, or Plex's legacy `com.plexapp.agents.imdb://tt0133093?lang=en`. */
export function parseGuids(guids: Array<string | undefined | null>): ExternalIds {
  const ids: ExternalIds = {}
  for (const raw of guids) {
    if (!raw) continue
    const m = /(?:^|\.)(tmdb|themoviedb|imdb|tvdb|thetvdb):\/\/([^?/\s]+)/i.exec(raw)
    if (!m) continue
    const kind = m[1].toLowerCase()
    const value = m[2]
    if (kind === 'tmdb' || kind === 'themoviedb') {
      const n = parseInt(value, 10)
      if (Number.isFinite(n)) ids.tmdb = n
    } else if (kind === 'imdb') {
      if (/^tt\d+$/.test(value)) ids.imdb = value
    } else {
      const n = parseInt(value, 10)
      if (Number.isFinite(n)) ids.tvdb = n
    }
  }
  return ids
}

/** Accept ISO timestamps, epoch seconds or YYYY-MM-DD; return YYYY-MM-DD (UTC) or undefined. */
export function toDateOnly(value: string | number | null | undefined): string | undefined {
  if (value == null || value === '') return undefined
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) return value
  const d = typeof value === 'number' ? new Date(value * 1000) : new Date(value)
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString().slice(0, 10)
}

export interface MergePatch {
  status?: WatchStatus
  rating?: number
  viewings?: Viewing[]
}

/** Decide what an incoming item adds to an existing title. Never overwrites:
 *  only fills an empty rating, adds viewings on dates not yet logged, and
 *  promotes watchlist -> watched. Returns null when nothing would change. */
export function planMerge(existing: Title, item: SyncItem): MergePatch | null {
  const patch: MergePatch = {}
  if (item.status === 'watched' && existing.status === 'watchlist') patch.status = 'watched'
  if (existing.rating == null && item.rating != null) patch.rating = item.rating

  if (existing.type === 'movie' && item.watchedDates.length > 0) {
    const have = new Set((existing.viewings ?? []).map((v) => v.date).filter(Boolean))
    const fresh = Array.from(new Set(item.watchedDates)).filter((d) => !have.has(d)).sort()
    if (fresh.length > 0) {
      patch.viewings = fresh.map((date) => ({ id: crypto.randomUUID(), titleId: existing.id, date }))
      if (existing.status !== 'watched' && patch.status == null) patch.status = 'watched'
    }
  }
  return Object.keys(patch).length > 0 ? patch : null
}

// ─── Resolution pipeline ─────────────────────────────────────────────────────

export interface SyncOutcome {
  inserts: Title[]
  updates: Array<{ titleId: string; patch: MergePatch }>
  /** provider id -> title id, for external_title_links. */
  links: Array<{ provider: SyncProvider; externalId: string; titleId: string }>
  unmatched: string[]
  unchanged: number
}

async function resolveTmdb(item: SyncItem): Promise<SearchResult | undefined> {
  if (item.ids.tmdb != null) {
    return { tmdbId: item.ids.tmdb, type: item.type, title: item.title, year: item.year ?? 0, genres: [] }
  }
  if (item.ids.imdb) {
    const hit = await findMediaByExternalId('imdb_id', item.ids.imdb, item.type)
    if (hit) return hit
  }
  if (item.ids.tvdb != null) {
    const hit = await findMediaByExternalId('tvdb_id', String(item.ids.tvdb), item.type)
    if (hit) return hit
  }
  if (item.type === 'movie') return pickBestMatch(await searchMedia(item.title), item.title, item.year)
  const tv = (await searchMedia(item.title)).filter((c) => c.type === 'tv')
  return tv.find((c) => c.title.toLowerCase() === item.title.toLowerCase() && (!item.year || Math.abs(c.year - item.year) <= 1))
}

export async function resolveSyncItems(
  items: SyncItem[],
  opts: {
    library: Title[]
    onProgress?: (done: number, total: number) => void
    isCancelled?: () => boolean
  }
): Promise<SyncOutcome> {
  const out: SyncOutcome = { inserts: [], updates: [], links: [], unmatched: [], unchanged: 0 }
  const byKey = new Map<string, Title>()
  for (const t of opts.library) if (t.tmdbId != null) byKey.set(`${t.type}:${t.tmdbId}`, t)
  const claimed = new Set<string>()
  let done = 0

  await mapPool(items, 3, async (item) => {
    if (opts.isCancelled?.()) return
    const label = item.year ? `${item.title} (${item.year})` : item.title
    try {
      const match = await resolveTmdb(item)
      if (!match) { out.unmatched.push(label); return }
      const key = `${match.type}:${match.tmdbId}`
      const existing = byKey.get(key)
      if (existing) {
        const patch = planMerge(existing, item)
        if (patch) {
          out.updates.push({ titleId: existing.id, patch })
          // Apply in-memory so a second item for the same title merges against it.
          byKey.set(key, { ...existing, ...patch, viewings: [...(existing.viewings ?? []), ...(patch.viewings ?? [])] })
        } else out.unchanged++
        out.links.push({ provider: item.provider, externalId: item.externalId, titleId: existing.id })
        return
      }
      if (claimed.has(key)) { out.unchanged++; return }
      claimed.add(key)
      const { result: detailed } = await fetchMediaDetails(match)
      const built = buildTitle(
        detailed,
        { name: item.title, year: item.year, rating: item.rating, watchedDates: [...item.watchedDates].sort() },
        item.status
      )
      // TV is title-level only for now; per-episode events are a follow-up.
      const title = item.type === 'tv' ? { ...built, viewings: [] } : built
      out.inserts.push(title)
      out.links.push({ provider: item.provider, externalId: item.externalId, titleId: title.id })
    } catch (err) {
      console.error(`Sync: failed to resolve "${label}":`, err)
      out.unmatched.push(label)
    } finally {
      done++
      opts.onProgress?.(done, items.length)
    }
  })
  return out
}
