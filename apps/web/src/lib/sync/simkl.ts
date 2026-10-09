// Simkl adapter. All Simkl traffic goes through the `simkl-sync` Edge Function
// (tokens never reach the browser); this file maps its normalized payload to
// provider-agnostic SyncItems.

import { supabase, isSupabaseConfigured } from '../auth'
import { ratingFromTen, toDateOnly, type SyncItem } from './core'

export interface SimklDeviceCode {
  deviceCode: string
  userCode: string
  verificationUri: string
  verificationUriComplete?: string
  interval: number
  expiresIn: number
}

interface ProxyItem {
  externalId: string
  type: 'movie' | 'tv'
  title: string
  year?: number
  ids: { tmdb?: number; imdb?: string; tvdb?: number }
  status: SyncItem['status']
  rating?: number
  lastWatchedAt?: string
}

async function call<T>(body: Record<string, unknown>): Promise<T> {
  if (!(isSupabaseConfigured && supabase)) throw new Error('Simkl sync needs a connected Supabase project.')
  const { data, error } = await supabase.functions.invoke('simkl-sync', { body })
  if (error) throw error
  if (data?.error) throw new Error(data.error)
  return data as T
}

/** Begin device sign-in. `write` requests media:write — only for the explicit two-way opt-in. */
export async function startSimklAuth(write = false): Promise<SimklDeviceCode> {
  const d = await call<any>({ action: 'start', write })
  return {
    deviceCode: d.device_code,
    userCode: d.user_code,
    verificationUri: d.verification_uri,
    verificationUriComplete: d.verification_uri_complete,
    interval: d.interval,
    expiresIn: d.expires_in,
  }
}

export async function pollSimklAuth(
  deviceCode: string,
  write = false
): Promise<{ pending: true; slowDown?: boolean } | { connected: true; direction: 'import' | 'two_way' }> {
  return call({ action: 'poll', device_code: deviceCode, write })
}

export function mapSimklItems(items: ProxyItem[]): SyncItem[] {
  return items.map((i) => {
    const watched = i.status === 'watched'
    const date = toDateOnly(i.lastWatchedAt)
    return {
      provider: 'simkl' as const,
      externalId: i.externalId,
      type: i.type,
      title: i.title,
      year: i.year,
      ids: i.ids,
      status: i.status,
      rating: ratingFromTen(i.rating),
      // Simkl only exposes the latest watch date at list level; episode history is a follow-up.
      watchedDates: watched && i.type === 'movie' && date ? [date] : [],
    }
  })
}

export async function fetchSimklItems(): Promise<SyncItem[]> {
  const { items } = await call<{ items: ProxyItem[] }>({ action: 'items' })
  return mapSimklItems(items)
}

export async function disconnectSimkl(): Promise<void> {
  await call({ action: 'disconnect' })
}
