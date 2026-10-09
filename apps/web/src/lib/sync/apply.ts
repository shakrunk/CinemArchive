// Persists a SyncOutcome: new titles, merge patches on existing titles, and the
// provenance links that keep re-syncs idempotent. Also reads/writes the
// owner-only integration_connections rows (no secrets live there).

import { supabase, isSupabaseConfigured } from '../auth'
import { useAppStore } from '../../store/useAppStore'
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
  setTitles: (titles: Title[]) => Promise<void>
  updateTitle: (id: string, patch: Partial<Title>) => Promise<void>
}): Promise<{ added: number; updated: number }> {
  if (useAppStore.getState().user?.id !== args.userId) throw new Error('Account changed during import')
  return useAppStore.getState().applySyncOutcome(args.outcome)
}
