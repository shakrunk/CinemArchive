import { create } from 'zustand'
import { isSupabaseConfigured } from './auth'
import { isDevMockUser } from './devAuth'
import { fetchMoviegoingPrefs, saveMoviegoingPrefs } from './db'
import { useAppStore } from 'src/store/useAppStore'
import {
  isValidBudgetAmount, isValidPlanCap, type BudgetPeriod, type MoviegoingBudget, type PlanPeriod, type SubscriptionPlan,
} from 'src/store/moviegoingRules'

// Owner-private moviegoing settings (budget #209, subscription plan #211) that follow a
// signed-in account across devices: one row of `moviegoing_prefs`. The payload, validation
// and last-write-wins rules are the contract in docs/android-contracts/moviegoing-prefs.md,
// which Android implements case for case.

export interface MoviegoingPrefs {
  budget: MoviegoingBudget | null
  plan: SubscriptionPlan | null
  updatedAt: string
  /** The whole payload, kept so keys this client doesn't know (written by a newer one)
   *  survive a local edit and the push that follows it. */
  raw: Record<string, unknown>
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value)

function parseBudget(value: unknown): MoviegoingBudget | null {
  if (!isRecord(value)) return null
  const { amount, period } = value
  if (typeof amount !== 'number' || !isValidBudgetAmount(amount)) return null
  if (period !== 'month' && period !== 'year') return null
  return { amount, period: period as BudgetPeriod }
}

function parsePlan(value: unknown): SubscriptionPlan | null {
  if (!isRecord(value)) return null
  const { cap, period } = value
  if (typeof cap !== 'number' || !isValidPlanCap(cap)) return null
  if (period !== 'week' && period !== 'month') return null
  return { cap, period: period as PlanPeriod }
}

/** Rejects the whole payload on a missing/unparseable `updatedAt` or an invalid `budget` or
 *  `plan` rather than half-applying it. An absent or null `budget`/`plan` is valid: it means
 *  "none set". */
export function parseMoviegoingPrefs(value: unknown): MoviegoingPrefs | null {
  if (!isRecord(value)) return null
  if (typeof value.updatedAt !== 'string' || Number.isNaN(Date.parse(value.updatedAt))) return null
  let budget: MoviegoingBudget | null = null
  if (value.budget != null) {
    budget = parseBudget(value.budget)
    if (!budget) return null
  }
  let plan: SubscriptionPlan | null = null
  if (value.plan != null) {
    plan = parsePlan(value.plan)
    if (!plan) return null
  }
  return { budget, plan, updatedAt: value.updatedAt, raw: value }
}

/** A copy of `base` (or an empty payload) with one key set, or explicitly nulled when
 *  clearing so the clear carries a fresh `updatedAt` and propagates, and every other key kept. */
function withKey(base: Record<string, unknown> | null, key: 'budget' | 'plan', value: unknown, updatedAt: string) {
  return { ...(base ?? {}), [key]: value ?? null, updatedAt }
}

export const moviegoingPrefsWithBudget = (base: Record<string, unknown> | null, budget: MoviegoingBudget | null, updatedAt: string) =>
  withKey(base, 'budget', budget && { amount: budget.amount, period: budget.period }, updatedAt)

export const moviegoingPrefsWithPlan = (base: Record<string, unknown> | null, plan: SubscriptionPlan | null, updatedAt: string) =>
  withKey(base, 'plan', plan && { cap: plan.cap, period: plan.period }, updatedAt)

export type Reconciliation =
  | { kind: 'keep' }
  | { kind: 'push-local' }
  | { kind: 'apply-remote'; prefs: MoviegoingPrefs }

/** `remoteRaw` is the fetched `prefs` object, or null when there is no row or it is still the
 *  empty default. Last write wins on `updatedAt`; a present-but-invalid remote copy is left alone
 *  (neither applied nor overwritten), as is an unparseable local one. */
export function resolveReconciliation(remoteRaw: unknown, local: MoviegoingPrefs | null): Reconciliation {
  const absent = remoteRaw == null || (isRecord(remoteRaw) && Object.keys(remoteRaw).length === 0)
  const remote = absent ? null : parseMoviegoingPrefs(remoteRaw)
  if (!absent && !remote) return { kind: 'keep' }
  if (!remote) return local ? { kind: 'push-local' } : { kind: 'keep' }
  if (!local) return { kind: 'apply-remote', prefs: remote }
  const remoteAt = Date.parse(remote.updatedAt)
  const localAt = Date.parse(local.updatedAt)
  if (remoteAt > localAt) return { kind: 'apply-remote', prefs: remote }
  if (localAt > remoteAt) return { kind: 'push-local' }
  return { kind: 'keep' }
}

// ─── Runtime ──────────────────────────────────────────────────────────────────

/** In-memory only, on purpose: this is one account's private data, and the device-preference
 *  store is shared by whoever signs in next. It is cleared on sign-out and refetched on
 *  sign-in, so a push that failed offline is retried by the next reconcile only while the
 *  tab stays open. */
interface MoviegoingPrefsState {
  prefs: MoviegoingPrefs | null
  setPrefs: (prefs: MoviegoingPrefs | null) => void
}

export const useMoviegoingPrefs = create<MoviegoingPrefsState>((set) => ({
  prefs: null,
  setPrefs: (prefs) => set({ prefs }),
}))

function syncUserId(): string | null {
  const { user } = useAppStore.getState()
  if (!isSupabaseConfigured || !user || isDevMockUser(user)) return null
  return user.id
}

async function push(userId: string, raw: Record<string, unknown>): Promise<void> {
  try {
    await saveMoviegoingPrefs(userId, raw)
  } catch {
    // Best effort: the next reconcile pushes again while local stays newer.
  }
}

/** Pull-then-decide. Safe to call any time; a failed fetch, a missing table or an invalid
 *  payload leaves both sides untouched. */
export async function reconcileMoviegoingPrefs(): Promise<void> {
  const userId = syncUserId()
  if (!userId) return
  let remoteRaw: unknown
  try {
    remoteRaw = await fetchMoviegoingPrefs(userId)
  } catch {
    return
  }
  // The account may have changed while the fetch was in flight.
  if (syncUserId() !== userId) return
  const local = useMoviegoingPrefs.getState().prefs
  const decision = resolveReconciliation(remoteRaw, local)
  if (decision.kind === 'apply-remote') useMoviegoingPrefs.getState().setPrefs(decision.prefs)
  else if (decision.kind === 'push-local' && local) await push(userId, local.raw)
}

async function update(change: (base: Record<string, unknown> | null, stamp: string) => Record<string, unknown>) {
  const state = useMoviegoingPrefs.getState()
  const next = parseMoviegoingPrefs(change(state.prefs?.raw ?? null, new Date().toISOString()))
  if (!next) return
  state.setPrefs(next)
  const userId = syncUserId()
  if (userId) await push(userId, next.raw)
}

/** Sets, replaces or (with null) clears the budget. An invalid amount is rejected. */
export async function setMoviegoingBudget(budget: MoviegoingBudget | null): Promise<void> {
  if (budget && !isValidBudgetAmount(budget.amount)) throw new Error('Invalid budget amount')
  await update((base, stamp) => moviegoingPrefsWithBudget(base, budget, stamp))
}

/** Sets, replaces or (with null) clears the subscription plan. An invalid cap is rejected. */
export async function setMoviegoingPlan(plan: SubscriptionPlan | null): Promise<void> {
  if (plan && !isValidPlanCap(plan.cap)) throw new Error('Invalid plan cap')
  await update((base, stamp) => moviegoingPrefsWithPlan(base, plan, stamp))
}

let started = false

/** Reconciles on sign-in and when the browser comes back online, and drops the previous
 *  account's copy on any account change. Idempotent; call once on app mount. */
export function startMoviegoingPrefsSync(): void {
  if (started) return
  started = true
  useAppStore.subscribe((s, prev) => {
    if (s.user?.id === prev.user?.id) return
    useMoviegoingPrefs.getState().setPrefs(null)
    void reconcileMoviegoingPrefs()
  })
  if (typeof window !== 'undefined') window.addEventListener('online', () => void reconcileMoviegoingPrefs())
  void reconcileMoviegoingPrefs()
}
