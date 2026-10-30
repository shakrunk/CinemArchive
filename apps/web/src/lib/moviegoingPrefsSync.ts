import { create } from 'zustand'
import { isSupabaseConfigured } from './auth'
import { isDevMockUser } from './devAuth'
import { fetchMoviegoingPrefs, saveMoviegoingPrefs } from './db'
import { useAppStore } from 'src/store/useAppStore'
import {
  isValidBudgetAmount, isValidPlanCap, type BudgetPeriod, type MoviegoingBudget, type PlanPeriod, type SubscriptionPlan,
} from 'src/store/moviegoingRules'
import { isValidRegion } from 'src/store/moviegoingRegion'
import {
  createOutingTemplate, isValidTemplateTime, isValidTemplateWeekday, templateFormat, MAX_TEMPLATES, type OutingTemplate,
} from 'src/store/outingTemplates'

// Owner-private moviegoing settings (budget #209, subscription plan #211) that follow a
// signed-in account across devices: one row of `moviegoing_prefs`. The payload, validation
// and last-write-wins rules are the contract in docs/android-contracts/moviegoing-prefs.md,
// which Android implements case for case.

export interface MoviegoingPrefs {
  budget: MoviegoingBudget | null
  plan: SubscriptionPlan | null
  templates: OutingTemplate[]
  /** Default now-playing country (#202), or null when none is saved. */
  region: string | null
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

const isAbsent = (value: unknown) => value === undefined || value === null

function parseTemplate(value: unknown): OutingTemplate | null {
  if (!isRecord(value)) return null
  const { id, name, venue, companions, format, weekday, time } = value
  if (typeof id !== 'string' || typeof name !== 'string') return null
  if (!isAbsent(venue) && typeof venue !== 'string') return null
  if (!isAbsent(companions) && (!Array.isArray(companions) || companions.some((c) => typeof c !== 'string'))) return null
  if (!isAbsent(format) && !templateFormat(format)) return null
  if (!isAbsent(weekday) && !isValidTemplateWeekday(weekday)) return null
  if (!isAbsent(time) && !isValidTemplateTime(time)) return null
  return createOutingTemplate({
    id,
    name,
    venue: venue as string | null | undefined,
    companions: (companions as string[] | null | undefined) ?? [],
    format: isAbsent(format) ? null : templateFormat(format),
    weekday: weekday as number | null | undefined,
    time: time as string | null | undefined,
  })
}

/** Invalid entries are skipped, not fatal, so one bad entry never blocks the budget and plan;
 *  a repeated id keeps its first entry, and the list is capped. */
function parseTemplates(list: unknown[]): OutingTemplate[] {
  const seen = new Set<string>()
  const templates: OutingTemplate[] = []
  for (const entry of list) {
    const template = parseTemplate(entry)
    if (!template || seen.has(template.id)) continue
    seen.add(template.id)
    templates.push(template)
    if (templates.length === MAX_TEMPLATES) break
  }
  return templates
}

/** Rejects the whole payload on a missing/unparseable `updatedAt`, an invalid `budget` or
 *  `plan`, a `templates` that is not an array, or a `region` that is not two uppercase letters,
 *  rather than half-applying it. An absent or
 *  null `budget`/`plan`/`templates` is valid: it means "none set". */
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
  let templates: OutingTemplate[] = []
  if (value.templates != null) {
    if (!Array.isArray(value.templates)) return null
    templates = parseTemplates(value.templates)
  }
  let region: string | null = null
  if (value.region != null) {
    if (!isValidRegion(value.region)) return null
    region = value.region
  }
  return { budget, plan, templates, region, updatedAt: value.updatedAt, raw: value }
}

/** A copy of `base` (or an empty payload) with one key set, or explicitly nulled when
 *  clearing so the clear carries a fresh `updatedAt` and propagates, and every other key kept. */
function withKey(base: Record<string, unknown> | null, key: 'budget' | 'plan' | 'templates' | 'region', value: unknown, updatedAt: string) {
  return { ...(base ?? {}), [key]: value ?? null, updatedAt }
}

export const moviegoingPrefsWithRegion = (base: Record<string, unknown> | null, region: string | null, updatedAt: string) =>
  withKey(base, 'region', region, updatedAt)

export const moviegoingPrefsWithTemplates = (base: Record<string, unknown> | null, templates: OutingTemplate[], updatedAt: string) =>
  withKey(base, 'templates', templates.map((t) => ({
    id: t.id,
    name: t.name,
    venue: t.venue ?? null,
    companions: t.companions,
    format: t.format ?? null,
    weekday: t.weekday ?? null,
    time: t.time ?? null,
  })), updatedAt)

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

/** Sets, replaces or (with null) clears the default now-playing region. Anything but two
 *  uppercase letters is rejected. */
export async function setMoviegoingRegion(region: string | null): Promise<void> {
  if (region !== null && !isValidRegion(region)) throw new Error('Invalid region')
  await update((base, stamp) => moviegoingPrefsWithRegion(base, region, stamp))
}

/** Adds a template, or replaces the one with the same id. Throws when it would be the
 *  `MAX_TEMPLATES`+1st, so the synced payload stays small. */
export async function saveOutingTemplate(template: OutingTemplate): Promise<void> {
  const current = useMoviegoingPrefs.getState().prefs?.templates ?? []
  const next = current.some((t) => t.id === template.id)
    ? current.map((t) => (t.id === template.id ? template : t))
    : [...current, template]
  if (next.length > MAX_TEMPLATES) throw new Error('Too many templates')
  await update((base, stamp) => moviegoingPrefsWithTemplates(base, next, stamp))
}

export async function deleteOutingTemplate(id: string): Promise<void> {
  const current = useMoviegoingPrefs.getState().prefs?.templates ?? []
  await update((base, stamp) => moviegoingPrefsWithTemplates(base, current.filter((t) => t.id !== id), stamp))
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
