import { supabase } from '../lib/auth'
import { fetchAllTitlePins, fetchLedgerLayout, fetchListMemberships, fetchLists, fetchUserLibrary } from '../lib/db'
import { OfflineCoordinator, type DeliveryContext, type DeliveryResult, type ExclusiveLock, type OfflineCoordinatorState } from '../lib/offline/coordinator'
import { IndexedDbOfflineStore } from '../lib/offline/storage'
import { emptySnapshot, type OfflineSnapshot } from '../lib/offline/snapshot'
import type { Mutation, PendingCommand } from '../lib/offline/commands'
import { DEFAULT_NAV_ORDER } from '../lib/navigation'

export const DEVICE_PREFERENCES_KEY = 'cinemarchive-device-preferences-v1'
export const LEGACY_LIBRARY_KEY = 'cinemarchive-library'

/** No partial refresh can be mistaken for an authoritative empty domain. The
 * caller holds the coordinator lock and checks its generation before applying. */
export async function fetchOwnerSnapshot(context: DeliveryContext): Promise<OfflineSnapshot> {
  if (!context.isCurrent()) throw new Error('Library owner changed')
  const userId = context.scope.userId
  const [library, lists, listMemberships, pins, ledgerWidgets] = await Promise.all([
    fetchUserLibrary(userId), fetchLists(userId), fetchListMemberships(userId),
    fetchAllTitlePins(userId), fetchLedgerLayout(userId),
  ])
  if (!context.isCurrent()) throw new Error('Library owner changed')
  return { ...library, lists, listMemberships, ledgerWidgets,
    pinnedModes: Object.fromEntries(pins.map((pin) => [`${pin.titleId}:${pin.easterEggKey}`, pin.pinnedVariant])),
  }
}

export interface OfflineLibraryStatus {
  ownerId: string | null
  hydrated: boolean
  commands: PendingCommand[]
  quarantined: OfflineCoordinatorState['quarantined']
}

interface RuntimeOptions {
  projectId: string
  onSnapshot: (snapshot: OfflineSnapshot | null) => void
  onStatus: (status: OfflineLibraryStatus) => void
  onError: (error: unknown) => void
  deliver: (command: PendingCommand, context: DeliveryContext) => Promise<DeliveryResult>
  isAuthenticated?: (userId: string) => Promise<boolean>
  fetchBase?: typeof fetchOwnerSnapshot
  ownerStorage?: IndexedDbOfflineStore
  anonymousStorage?: IndexedDbOfflineStore
  lock?: ExclusiveLock
}

/** Anonymous data uses a separate database and never enters the delivery
 * coordinator. The old unscoped localStorage key is deliberately not read here:
 * its library owner is unknown, and it remains untouched for manual recovery. */
export class OfflineLibraryRuntime {
  private readonly coordinator: OfflineCoordinator
  private readonly options: RuntimeOptions
  private readonly anonymous: IndexedDbOfflineStore
  private readonly anonymousScope: { projectId: string; userId: string }
  private ownerId: string | null = null
  private generation = 0
  private ready = false
  private pending = 0
  private quarantined = 0

  constructor(options: RuntimeOptions) {
    this.options = options
    this.anonymous = options.anonymousStorage ?? new IndexedDbOfflineStore({ databaseName: 'cinemarchive-anonymous-v1' })
    this.anonymousScope = { projectId: options.projectId, userId: 'anonymous-local-only' }
    this.coordinator = new OfflineCoordinator({
      store: options.ownerStorage ?? new IndexedDbOfflineStore(),
      deliver: options.deliver, lock: options.lock,
      isAuthenticated: async (scope) => {
        if (scope.projectId !== options.projectId) return false
        if (options.isAuthenticated) return options.isAuthenticated(scope.userId)
        if (!supabase) return false
        const { data, error } = await supabase.auth.getSession()
        return !error && data.session?.user.id === scope.userId
      },
      onState: (state) => {
        this.ready = state !== null
        this.pending = state?.document.commands.length ?? 0
        this.quarantined = state?.quarantined.length ?? 0
        options.onSnapshot(state?.snapshot ?? null)
        options.onStatus({ ownerId: this.ownerId, hydrated: this.ready,
          commands: state?.document.commands ?? [], quarantined: state?.quarantined ?? [],
        })
      },
      onError: options.onError,
    })
  }

  async activate(userId: string): Promise<void> {
    this.generation++
    this.ownerId = userId
    await this.coordinator.activate({ projectId: this.options.projectId, userId })
  }

  /** Synchronously clears the last owner's visible projection before awaiting
   * IndexedDB/auth/network. Also used before friend/shared browsing. */
  deactivate(): void {
    this.generation++
    this.ownerId = null
    this.coordinator.deactivate()
  }

  async loadAnonymous(fallback = emptySnapshot()): Promise<void> {
    this.deactivate()
    const generation = this.generation
    const read = await this.anonymous.read(this.anonymousScope)
    if (generation !== this.generation || this.ownerId !== null) return
    this.ready = true
    this.quarantined = read.quarantined.length
    this.options.onSnapshot(read.document.revision > 0 ? read.document.base : fallback)
    this.options.onStatus({ ownerId: null, hydrated: true, commands: [], quarantined: read.quarantined })
  }

  async saveAnonymous(snapshot: OfflineSnapshot): Promise<void> {
    await this.anonymous.replaceBase(this.anonymousScope, snapshot)
  }

  refresh(): Promise<void> { return this.coordinator.refresh(this.options.fetchBase ?? fetchOwnerSnapshot) }
  reload(): Promise<void> { return this.coordinator.reload() }
  flush(): Promise<void> { return this.coordinator.flush() }
  submit(mutation: Mutation, options?: Parameters<OfflineCoordinator['submit']>[1]): Promise<PendingCommand> {
    return this.coordinator.submit(mutation, options)
  }
  retry(commandId: string): Promise<void> { return this.coordinator.retry(commandId) }
  discard(commandId: string): Promise<void> { return this.coordinator.discard(commandId) }

  get canReconcile(): boolean { return this.ownerId !== null && this.ready && this.pending === 0 && this.quarantined === 0 }
}

/** Carry only device presentation choices forward. Never copy an unknown
 * owner's titles, lists, outings, memberships, pins, or synced Ledger board. */
export function readLegacyDevicePreferences(storage: Pick<Storage, 'getItem'>): Record<string, unknown> {
  try {
    const raw = storage.getItem(LEGACY_LIBRARY_KEY)
    const state = raw ? JSON.parse(raw).state : null
    return pickDevicePreferences(state)
  } catch { return {} }
}

export function pickDevicePreferences(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const state = value as Record<string, unknown>
  const result: Record<string, unknown> = {}
  for (const [key, allowed] of Object.entries({ viewMode: ['grid', 'list'], gridSize: ['compact', 'default', 'large'],
    theme: ['dark', 'light', 'noir', 'matrix'], themeMode: ['light', 'dark', 'system'],
  })) if (typeof state[key] === 'string' && allowed.includes(state[key])) result[key] = state[key]
  if (Array.isArray(state.unlockedThemes)) result.unlockedThemes = [...new Set(['dark', 'light', ...state.unlockedThemes.filter((v) => v === 'noir' || v === 'matrix')])]
  if (state.navPrefs && typeof state.navPrefs === 'object') {
    const nav = state.navPrefs as Record<string, unknown>
    const valid = (v: unknown) => typeof v === 'string' && (DEFAULT_NAV_ORDER as readonly string[]).includes(v)
    if (Array.isArray(nav.order) && Array.isArray(nav.hidden)) result.navPrefs = {
      order: [...new Set([...nav.order.filter(valid), ...DEFAULT_NAV_ORDER])],
      hidden: nav.hidden.filter(valid), compact: nav.compact === true,
    }
  }
  if (state.filters && typeof state.filters === 'object' && !Array.isArray(state.filters)) {
    const input = state.filters as Record<string, unknown>
    const filters: Record<string, unknown> = {}
    if (typeof input.search === 'string') filters.search = input.search
    for (const key of ['genres', 'tags', 'networks', 'decades', 'languages']) {
      const values = input[key]
      if (Array.isArray(values) && values.every((v) => typeof v === 'string')) filters[key] = values
    }
    for (const [key, allowed] of Object.entries({ type: ['all', 'movie', 'tv'], status: ['all', 'watched', 'watchlist', 'watching', 'dropped'],
      sortField: ['title', 'year', 'rating', 'addedAt', 'director', 'lastInteraction'], sortDir: ['asc', 'desc'],
    })) if (typeof input[key] === 'string' && allowed.includes(input[key])) filters[key] = input[key]
    if (typeof input.minRating === 'number' && Number.isFinite(input.minRating)) filters.minRating = Math.max(0, Math.min(5, input.minRating))
    if (typeof input.groupByFranchise === 'boolean') filters.groupByFranchise = input.groupByFranchise
    if (typeof input.studio === 'string' || input.studio === null) filters.studio = input.studio
    if (input.person === null) filters.person = null
    else if (input.person && typeof input.person === 'object') {
      const person = input.person as Record<string, unknown>
      if (typeof person.id === 'number' && Number.isFinite(person.id) && typeof person.name === 'string') filters.person = { id: person.id, name: person.name }
    }
    result.filters = filters
  }
  return result
}

export function hasLegacyLibraryCache(storage: Pick<Storage, 'getItem'>): boolean {
  try { return storage.getItem(LEGACY_LIBRARY_KEY) !== null } catch { return false }
}
