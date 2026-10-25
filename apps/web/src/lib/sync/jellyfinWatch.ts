// Runtime for the Jellyfin watch prompt: polls the user's server for new plays,
// queues them in device storage, and exposes the queue to the UI. A play only
// leaves the queue when the user answers it (see JellyfinWatchPrompt).

import { useEffect, useSyncExternalStore } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import { fetchJellyfinPlays, JellyfinAuthError, type JellyfinPlay, type JellyfinSession } from './jellyfin'
import {
  clearJellyfinState,
  loadJellyfinState,
  queuePlays,
  resolvePlay,
  saveJellyfinState,
  type JellyfinState,
} from './jellyfinPending'

export const JELLYFIN_POLL_MS = 5 * 60 * 1000

let activeUser: string | null = null
let state: JellyfinState | null = null
let authExpired = false
let checking = false
let snapshot: { pending: JellyfinPlay[]; authExpired: boolean } = { pending: [], authExpired: false }
const listeners = new Set<() => void>()

function publish() {
  snapshot = { pending: state?.pending ?? [], authExpired }
  listeners.forEach((l) => l())
}

function persist() {
  if (activeUser && state) saveJellyfinState(activeUser, state)
  publish()
}

function load(userId: string | null) {
  if (userId === activeUser) return
  activeUser = userId
  authExpired = false
  state = userId ? loadJellyfinState(userId) : null
  publish()
}

/** Ask the server for plays since the cursor and queue them. Safe to call often. */
export async function checkJellyfin(): Promise<void> {
  const userId = activeUser
  const session = state?.session
  if (!userId || !state || !session || checking) return
  checking = true
  try {
    const plays = await fetchJellyfinPlays(session, state.cursor)
    // The account may have changed while the request was in flight.
    if (activeUser !== userId || !state) return
    authExpired = false
    state = queuePlays(state, plays)
    persist()
  } catch (err) {
    if (err instanceof JellyfinAuthError && activeUser === userId) {
      authExpired = true
      publish()
    }
    // Server unreachable (away from home network, asleep): try again next tick.
  } finally {
    checking = false
  }
}

/** Start watching from "now": history before connecting is never prompted for. */
export function startJellyfinWatch(userId: string, session: JellyfinSession): void {
  load(userId)
  state = { session, cursor: new Date().toISOString(), pending: state?.pending ?? [] }
  authExpired = false
  persist()
}

/** Replace an expired session without moving the cursor, so missed plays still prompt. */
export function refreshJellyfinSession(userId: string, session: JellyfinSession): void {
  load(userId)
  if (!state) return startJellyfinWatch(userId, session)
  state = { ...state, session }
  authExpired = false
  persist()
  void checkJellyfin()
}

export function stopJellyfinWatch(userId: string): JellyfinSession | null {
  load(userId)
  const session = state?.session ?? null
  clearJellyfinState(userId)
  state = null
  authExpired = false
  publish()
  return session
}

export function dismissJellyfinPlay(playKey: string): void {
  if (!state) return
  state = resolvePlay(state, playKey)
  persist()
}

export function hasJellyfinSession(userId: string): boolean {
  load(userId)
  return !!state?.session
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

export function useJellyfinQueue() {
  return useSyncExternalStore(subscribe, () => snapshot)
}

/** Mount once near the app root: loads the signed-in user's state and polls while visible. */
export function useJellyfinPolling(): void {
  const userId = useAppStore((s) => s.user?.id ?? null)
  useEffect(() => {
    load(userId)
    if (!userId) return
    void checkJellyfin()
    const timer = setInterval(() => { if (document.visibilityState === 'visible') void checkJellyfin() }, JELLYFIN_POLL_MS)
    const onVisible = () => { if (document.visibilityState === 'visible') void checkJellyfin() }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [userId])
}
