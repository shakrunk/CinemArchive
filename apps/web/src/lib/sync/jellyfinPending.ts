// Device-local state for the Jellyfin watch prompt: the signed-in session, the
// "checked up to" cursor, and plays awaiting the user's yes/no. Namespaced by
// CinemArchive user id so switching accounts never shows or uses another
// account's Jellyfin session. Storage can be blocked or full, so every access
// is guarded and callers must tolerate an empty result.

import type { JellyfinPlay, JellyfinSession } from './jellyfin'

export interface JellyfinState {
  session: JellyfinSession | null
  /** ISO timestamp; only plays after this are prompted. Set to "now" on connect. */
  cursor: string
  pending: JellyfinPlay[]
}

const key = (userId: string) => `cinemarchive.jellyfin.${userId}`

export function loadJellyfinState(userId: string): JellyfinState | null {
  try {
    const raw = localStorage.getItem(key(userId))
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<JellyfinState>
    if (typeof parsed.cursor !== 'string' || !Array.isArray(parsed.pending)) return null
    return { session: parsed.session ?? null, cursor: parsed.cursor, pending: parsed.pending }
  } catch {
    return null
  }
}

export function saveJellyfinState(userId: string, state: JellyfinState): void {
  try { localStorage.setItem(key(userId), JSON.stringify(state)) } catch { /* storage blocked or full */ }
}

export function clearJellyfinState(userId: string): void {
  try { localStorage.removeItem(key(userId)) } catch { /* storage blocked */ }
}

/** Merge newly seen plays into the queue, skipping ones already queued. The cursor moves to the newest seen play. */
export function queuePlays(state: JellyfinState, plays: JellyfinPlay[]): JellyfinState {
  if (plays.length === 0) return state
  const known = new Set(state.pending.map((p) => p.key))
  const fresh = plays.filter((p) => !known.has(p.key))
  const newest = plays.reduce((max, p) => (p.playedAt > max ? p.playedAt : max), state.cursor)
  return { ...state, cursor: newest, pending: [...state.pending, ...fresh] }
}

export function resolvePlay(state: JellyfinState, playKey: string): JellyfinState {
  return { ...state, pending: state.pending.filter((p) => p.key !== playKey) }
}
