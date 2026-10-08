// Persists a SyncOutcome: new titles, merge patches on existing titles, and the
// provenance links that keep re-syncs idempotent. Also reads/writes the
// owner-only integration_connections rows (no secrets live there).

import { supabase, isSupabaseConfigured } from '../auth'
import { insertTitleToDb } from '../db'
import type { Title } from '../../store/mockData'
import type { SyncOutcome, SyncProvider } from './core'

export type SyncDirection = 'import' | 'two_way'

export interface IntegrationConnection {
  provider: SyncProvider
  direction: SyncDirection
  serverUrl?: string
  accountLabel?: string
  lastSyncedAt?: string
}

export async function fetchConnections(): Promise<IntegrationConnection[]> {
  if (!(isSupabaseConfigured && supabase)) return []
  const { data, error } = await supabase
    .from('integration_connections')
    .select('provider, direction, server_url, account_label, last_synced_at')
  if (error) throw error
  return (data ?? []).map((r: any) => ({
    provider: r.provider,
    direction: r.direction,
    serverUrl: r.server_url ?? undefined,
    accountLabel: r.account_label ?? undefined,
    lastSyncedAt: r.last_synced_at ?? undefined,
  }))
}

/** Create/refresh a connection row (Plex/Emby/Letterboxd; Simkl's is written server-side). */
export async function recordConnection(
  userId: string,
  provider: SyncProvider,
  fields: { serverUrl?: string; accountLabel?: string } = {}
): Promise<void> {
  if (!(isSupabaseConfigured && supabase)) return
  const { error } = await supabase.from('integration_connections').upsert(
    {
      user_id: userId,
      provider,
      server_url: fields.serverUrl ?? null,
      account_label: fields.accountLabel ?? null,
      last_synced_at: new Date().toISOString(),
    },
    { onConflict: 'user_id,provider' }
  )
  if (error) throw error
}

export async function removeConnection(userId: string, provider: SyncProvider): Promise<void> {
  if (!(isSupabaseConfigured && supabase)) return
  const { error } = await supabase.from('integration_connections').delete().eq('user_id', userId).eq('provider', provider)
  if (error) throw error
}

/** Only the import -> two_way flip; the Simkl write token is requested separately at sign-in. */
export async function setConnectionDirection(userId: string, provider: SyncProvider, direction: SyncDirection): Promise<void> {
  if (!(isSupabaseConfigured && supabase)) return
  const { error } = await supabase
    .from('integration_connections')
    .update({ direction })
    .eq('user_id', userId)
    .eq('provider', provider)
  if (error) throw error
}

export async function applySyncOutcome(args: {
  userId: string
  outcome: SyncOutcome
  titles: Title[]
  setTitles: (titles: Title[]) => void
  updateTitle: (id: string, patch: Partial<Title>) => void
}): Promise<{ added: number; updated: number }> {
  const { userId, outcome, titles, setTitles, updateTitle } = args

  if (outcome.inserts.length > 0) {
    setTitles([...outcome.inserts, ...titles])
    await Promise.all(outcome.inserts.map((t) => insertTitleToDb(userId, t)))
  }

  // Several patches can target one title; fold them so viewings accumulate.
  const byTitle = new Map<string, { status?: Title['status']; rating?: number; viewings: Title['viewings'] }>()
  for (const { titleId, patch } of outcome.updates) {
    const acc = byTitle.get(titleId) ?? { viewings: [] }
    if (patch.status) acc.status = patch.status
    if (patch.rating != null && acc.rating == null) acc.rating = patch.rating
    acc.viewings = [...acc.viewings, ...(patch.viewings ?? [])]
    byTitle.set(titleId, acc)
  }
  for (const [titleId, acc] of byTitle) {
    const existing = titles.find((t) => t.id === titleId)
    const patch: Partial<Title> = {}
    if (acc.status) patch.status = acc.status
    if (acc.rating != null) patch.rating = acc.rating
    if (acc.viewings.length > 0) patch.viewings = [...(existing?.viewings ?? []), ...acc.viewings]
    updateTitle(titleId, patch)
  }

  // Links reference titles(id), so they go in after the inserts above.
  if (outcome.links.length > 0 && isSupabaseConfigured && supabase) {
    // One row per (provider, external id): a duplicate in a single upsert errors.
    const unique = new Map(outcome.links.map((l) => [`${l.provider}:${l.externalId}`, l]))
    const rows = Array.from(unique.values(), (l) => ({
      user_id: userId,
      title_id: l.titleId,
      provider: l.provider,
      external_id: l.externalId,
    }))
    const { error } = await supabase.from('external_title_links').upsert(rows, { onConflict: 'user_id,provider,external_id' })
    if (error) console.error('Failed to record sync links:', error)
  }

  return { added: outcome.inserts.length, updated: byTitle.size }
}
