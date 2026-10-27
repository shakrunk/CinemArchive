import { supabase } from '../lib/auth'
import { fetchAllTitlePins, fetchLedgerLayout, fetchListMemberships, fetchLists, fetchUserLibrary } from '../lib/db'
import { OfflineCoordinator, type DeliveryContext, type DeliveryResult, type ExclusiveLock, type OfflineCoordinatorState } from '../lib/offline/coordinator'
import { IndexedDbOfflineStore } from '../lib/offline/storage'
import { emptySnapshot, type OfflineSnapshot } from '../lib/offline/snapshot'
import type { Mutation, PendingCommand } from '../lib/offline/commands'
import { createCommand } from '../lib/offline/commands'
import { DEFAULT_NAV_ORDER } from '../lib/navigation'
import type { TicketCapture } from '../lib/tickets/types'
import { ticketRemote, mergeOwnedTickets } from '../lib/tickets/remote'
import { replayPending } from '../lib/offline/replay'
import { assertTicketBytes, sameTicketAttachment } from '../lib/tickets/validation'
import { fetchOwnedMoviegoingPreferences, mergeOwnedMoviegoingPreferences } from '../lib/moviegoingPreferences'
import type { VenueNoteReview } from '../lib/venueNotes'

/** Focus, visibility and reconnect wakes re-read the server snapshot at most
 * this often. A full owner snapshot is megabytes of JSON, so refetching it on
 * every alt-tab dominated the project's egress. Deliveries and explicit
 * refreshes still fetch whenever they run. */
export const WAKE_REFRESH_INTERVAL_MS = 10 * 60 * 1000

export const DEVICE_PREFERENCES_KEY = 'cinemarchive-device-preferences-v1'
export const LEGACY_LIBRARY_KEY = 'cinemarchive-library'

/** No partial refresh can be mistaken for an authoritative empty domain. The
 * caller holds the coordinator lock and checks its generation before applying. */
export async function fetchOwnerSnapshot(context: DeliveryContext, requireTickets = false): Promise<OfflineSnapshot> {
  if (!context.isCurrent()) throw new Error('Library owner changed')
  const userId = context.scope.userId
  const [library, lists, listMemberships, pins, ledgerWidgets, tickets, moviegoing] = await Promise.all([
    fetchUserLibrary(userId), fetchLists(userId), fetchListMemberships(userId),
    fetchAllTitlePins(userId), fetchLedgerLayout(userId),
    ticketRemote.descriptors(context),
    fetchOwnedMoviegoingPreferences(context),
  ])
  if (!context.isCurrent()) throw new Error('Library owner changed')
  if (requireTickets && tickets.support !== 'authoritative') throw new Error('Ticket sync requires the server ticket attachment update; your saved photo remains on this device')
  return mergeOwnedMoviegoingPreferences(mergeOwnedTickets({ ...library, lists, listMemberships, ledgerWidgets,
    rowRevisions: { ...library.rowRevisions, ...Object.fromEntries(lists.map((list) => [`lists:${list.id}`, list.updatedAt])) },
    pinnedModes: Object.fromEntries(pins.map((pin) => [`${pin.titleId}:${pin.easterEggKey}`, pin.pinnedVariant])),
  }, tickets), moviegoing)
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
  browserEvents?: boolean
  downloadTicket?: typeof ticketRemote.download
  now?: () => number
}

/** Anonymous data uses a separate database and never enters the delivery
 * coordinator. The old unscoped localStorage key is deliberately not read here:
 * its library owner is unknown, and it remains untouched for manual recovery. */
export class OfflineLibraryRuntime {
  private readonly coordinator: OfflineCoordinator
  private readonly options: RuntimeOptions
  private readonly anonymous: IndexedDbOfflineStore
  private readonly ownerStorage: IndexedDbOfflineStore
  private readonly anonymousScope: { projectId: string; userId: string }
  private ownerId: string | null = null
  private generation = 0
  private ready = false
  private pending = 0
  private quarantined = 0
  private removeListeners: (() => void) | undefined
  private channel: BroadcastChannel | undefined
  private syncing: Promise<void> | undefined
  private wakeRequested = false
  private broadcastRevision = -1
  private ticketRequests = new AbortController()
  /** When this owner's server base was last fetched; 0 forces the next wake to refresh. */
  private lastBaseAt = 0
  private refreshing: Promise<void> | undefined
  private readonly now: () => number

  constructor(options: RuntimeOptions) {
    this.options = options
    this.anonymous = options.anonymousStorage ?? new IndexedDbOfflineStore({ databaseName: 'cinemarchive-anonymous-v1' })
    this.ownerStorage = options.ownerStorage ?? new IndexedDbOfflineStore()
    this.anonymousScope = { projectId: options.projectId, userId: 'anonymous-local-only' }
    this.now = options.now ?? Date.now
    this.coordinator = new OfflineCoordinator({
      store: this.ownerStorage,
      deliver: async (command, context) => {
        const generation = this.generation
        const result = await options.deliver(command, context)
        // A confirmed delivery carries a fresh server base; the wake that
        // follows it has nothing newer to fetch.
        if (result.kind === 'success' && result.canonicalBase && generation === this.generation) this.lastBaseAt = this.now()
        return result
      },
      lock: options.lock,
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
        if (state && state.document.revision !== this.broadcastRevision) {
          this.broadcastRevision = state.document.revision
          this.channel?.postMessage({ kind: 'changed' })
        }
      },
      onError: options.onError,
    })
  }

  async activate(userId: string): Promise<void> {
    this.ticketRequests.abort()
    this.ticketRequests = new AbortController()
    this.detachEvents()
    this.generation++
    this.ownerId = userId
    this.lastBaseAt = 0
    this.refreshing = undefined
    await this.coordinator.activate({ projectId: this.options.projectId, userId })
    if (this.ownerId === userId) {
      this.attachEvents()
      if (this.options.browserEvents !== false) this.wake()
    }
  }

  /** Synchronously clears the last owner's visible projection before awaiting
   * IndexedDB/auth/network. Also used before friend/shared browsing. */
  deactivate(): void {
    this.ticketRequests.abort()
    this.ticketRequests = new AbortController()
    this.detachEvents()
    this.generation++
    this.ownerId = null
    this.lastBaseAt = 0
    this.refreshing = undefined
    this.coordinator.deactivate()
  }

  async loadAnonymous(fallback = emptySnapshot()): Promise<void> {
    this.deactivate()
    const generation = this.generation
    let read = await this.anonymous.read(this.anonymousScope)
    if (generation !== this.generation || this.ownerId !== null) return
    if (read.document.revision === 0 && fallback.titles.length > 0) read = await this.anonymous.replaceBase(this.anonymousScope, fallback)
    if (generation !== this.generation || this.ownerId !== null) return
    this.ready = true
    this.quarantined = read.quarantined.length
    this.options.onSnapshot(read.document.revision > 0 ? read.document.base : fallback)
    this.options.onStatus({ ownerId: null, hydrated: true, commands: [], quarantined: read.quarantined })
    this.attachEvents()
  }

  async saveAnonymous(snapshot: OfflineSnapshot): Promise<void> {
    await this.anonymous.replaceBase(this.anonymousScope, snapshot)
  }

  /** Concurrent callers (startup load, a wake, a retry) share one in-flight
   * snapshot fetch instead of each downloading the whole library. */
  refresh(): Promise<void> {
    if (this.refreshing) return this.refreshing
    const refreshing = this.coordinator.refresh(this.fetchBase).finally(() => {
      if (this.refreshing === refreshing) this.refreshing = undefined
    })
    this.refreshing = refreshing
    return refreshing
  }
  runIdleRemote<T>(work: (context: DeliveryContext) => Promise<T>, changed?: (result: T) => boolean): Promise<T | undefined> {
    return this.coordinator.runIdleRemote(work, this.fetchBase, changed)
  }
  runSyncedRemote<T>(keys: readonly string[], work: (snapshot: OfflineSnapshot, context: DeliveryContext, assertReady: () => Promise<void>) => Promise<T>): Promise<T> {
    return this.coordinator.runSyncedRemote(keys, work, this.fetchBase)
  }
  private readonly fetchBase = async (context: DeliveryContext): Promise<OfflineSnapshot> => {
    const generation = this.generation
    const base = await (this.options.fetchBase ?? fetchOwnerSnapshot)(context)
    if (generation === this.generation) this.lastBaseAt = this.now()
    return base
  }
  reload(): Promise<void> { return this.coordinator.reload() }
  flush(): Promise<void> { return this.coordinator.flush() }
  /** Returns bytes only for this session's current association. A first online
   * open becomes available offline only after its cache transaction commits. */
  async readTicketPhoto(outingId: string, attachmentId: string): Promise<Blob> {
    if (!this.ready) throw new Error('Library is not ready')
    const generation = this.generation, ownerId = this.ownerId
    const scope = ownerId ? { projectId: this.options.projectId, userId: ownerId } : this.anonymousScope
    const store = ownerId ? this.ownerStorage : this.anonymous
    const context: DeliveryContext = { scope, signal: this.ticketRequests.signal,
      isCurrent: () => generation === this.generation && ownerId === this.ownerId && this.ready }
    const currentAttachment = async () => {
      const read = await store.read(scope)
      if (!context.isCurrent() || context.signal.aborted) throw new Error('Library account changed')
      const attachment = replayPending(read.document.base, read.document.commands).outings.find((outing) => outing.id === outingId)?.ticketAttachment
      if (!attachment || attachment.id !== attachmentId) throw new Error('This ticket was replaced or removed')
      return attachment
    }
    const attachment = await currentAttachment()
    const record = await store.readTicketBlob(scope, attachmentId)
    if (!context.isCurrent()) throw new Error('Library account changed')
    let blob: Blob
    if (record) {
      if (record.outingId !== outingId || !sameTicketAttachment(record.attachment, attachment)) throw new Error('Saved ticket metadata does not match this outing')
      await assertTicketBytes(record.blob, attachment)
      blob = record.blob
    } else {
      if (!ownerId) throw new Error('The original ticket photo is missing from this browser')
      blob = await (this.options.downloadTicket ?? ticketRemote.download)(context, attachment)
      if (!context.isCurrent()) throw new Error('Library account changed')
      if (!sameTicketAttachment(await currentAttachment(), attachment)) throw new Error('This ticket changed while downloading')
      await store.cacheTicketBlob(scope, outingId, attachment, blob)
    }
    if (!sameTicketAttachment(await currentAttachment(), attachment)) throw new Error('This ticket changed while opening')
    return blob
  }
  async attachTicket(outingId: string, capture: TicketCapture, blob: Blob, id = crypto.randomUUID()): Promise<void> {
    const generation = this.generation
    if (this.ownerId) await this.coordinator.attachTicket(outingId, capture, blob, id)
    else {
      if (!this.ready) throw new Error('Local library is not ready')
      const read = await this.anonymous.attachTicket(this.anonymousScope, outingId, capture, blob, { id, localOnly: true })
      if (generation !== this.generation) throw new Error('Library account changed')
      this.options.onSnapshot(read.document.base)
    }
    if (generation !== this.generation) throw new Error('Library account changed')
    this.channel?.postMessage({ kind: 'changed' })
    this.wake()
  }
  async detachTicket(outingId: string, id = crypto.randomUUID()): Promise<void> {
    const generation = this.generation
    if (this.ownerId) await this.coordinator.detachTicket(outingId, id)
    else {
      if (!this.ready) throw new Error('Local library is not ready')
      const read = await this.anonymous.detachTicket(this.anonymousScope, outingId, { id, localOnly: true })
      if (generation !== this.generation) throw new Error('Library account changed')
      this.options.onSnapshot(read.document.base)
    }
    if (generation !== this.generation) throw new Error('Library account changed')
    this.channel?.postMessage({ kind: 'changed' })
    this.wake()
  }
  submit(mutation: Mutation, options?: Parameters<OfflineCoordinator['submit']>[1]): Promise<PendingCommand> {
    return this.coordinator.submit(mutation, options).then((command) => {
      this.channel?.postMessage({ kind: 'changed' })
      this.wake()
      return command
    })
  }
  async submitAnonymous(mutation: Mutation): Promise<void> {
    if (this.ownerId !== null || !this.ready) throw new Error('Local library is not ready')
    const generation = this.generation
    // Use identical validation/explicit-clear rules without persisting an outbox.
    const command = createCommand(this.anonymousScope, mutation)
    const read = await this.anonymous.applyLocal(this.anonymousScope, command.mutation)
    if (generation !== this.generation || this.ownerId !== null) throw new Error('Account changed after local save')
    this.options.onSnapshot(read.document.base)
    this.channel?.postMessage({ kind: 'changed' })
  }

  private detachEvents(): void {
    this.removeListeners?.()
    this.removeListeners = undefined
    this.channel?.close()
    this.channel = undefined
    this.syncing = undefined
    this.wakeRequested = false
    this.broadcastRevision = -1
  }

  private attachEvents(): void {
    this.detachEvents()
    if (this.options.browserEvents === false || typeof window === 'undefined') return
    const generation = this.generation
    const resume = () => { if (document.visibilityState !== 'hidden') this.wake() }
    window.addEventListener('online', resume)
    window.addEventListener('focus', resume)
    document.addEventListener('visibilitychange', resume)
    this.removeListeners = () => {
      window.removeEventListener('online', resume)
      window.removeEventListener('focus', resume)
      document.removeEventListener('visibilitychange', resume)
    }
    if (window.BroadcastChannel) {
      this.channel = new window.BroadcastChannel(`cinemarchive-library:${JSON.stringify([this.options.projectId, this.ownerId])}`)
      this.channel.onmessage = () => {
        if (generation !== this.generation) return
        // A broadcast re-reads and may drain pending work; it must not refresh
        // the server base, which would create another revision/broadcast loop.
        if (this.ownerId) void this.reload().then(() => {
          if (generation === this.generation && this.ownerId) return this.flush()
        }).catch(this.options.onError)
        else void this.anonymous.read(this.anonymousScope).then((read) => {
          if (generation === this.generation && this.ownerId === null) this.options.onSnapshot(read.document.base)
        }).catch(this.options.onError)
      }
    }
  }

  /** Delivery precedes refresh so reconnect cannot overwrite queued optimism.
   * Coordinator locking excludes competing tabs, and repeated browser events
   * coalesce into one follow-up wake. Edits appended during refresh must get
   * another delivery pass after that refresh releases the coordinator lock.
   * The refresh itself only runs once the base is older than
   * WAKE_REFRESH_INTERVAL_MS, so that follow-up pass is usually delivery-only. */
  private wake(): void {
    if (!this.ownerId || (typeof navigator !== 'undefined' && navigator.onLine === false)) return
    if (this.syncing) {
      this.wakeRequested = true
      return
    }
    this.wakeRequested = false
    const generation = this.generation
    const sync = this.flush().then(async () => {
      if (generation === this.generation && this.ownerId && this.now() - this.lastBaseAt >= WAKE_REFRESH_INTERVAL_MS) await this.refresh()
    }).catch(this.options.onError).finally(() => {
      if (this.syncing !== sync) return
      this.syncing = undefined
      if (generation === this.generation && this.wakeRequested) this.wake()
    })
    this.syncing = sync
  }
  retry(commandId: string): Promise<void> { return this.coordinator.retry(commandId) }
  reviewVenue(commandId: string): Promise<VenueNoteReview> { return this.coordinator.reviewVenue(commandId, this.fetchBase) }
  async resolveVenue(review: VenueNoteReview, keepLocal: boolean): Promise<void> {
    await this.coordinator.resolveVenue(review, keepLocal, this.fetchBase)
    this.channel?.postMessage({ kind: 'changed' })
    this.wake()
  }
  discard(commandId: string): Promise<void> { return this.coordinator.discard(commandId) }
  async discardDamagedCache(): Promise<void> {
    const ownerId = this.ownerId
    const generation = this.generation
    if (ownerId) {
      await this.ownerStorage.discardQuarantine({ projectId: this.options.projectId, userId: ownerId })
      if (generation === this.generation && ownerId === this.ownerId) await this.activate(ownerId)
    } else {
      await this.anonymous.discardQuarantine(this.anonymousScope)
      if (generation === this.generation && this.ownerId === null) await this.loadAnonymous()
    }
  }

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
    theme: ['dark', 'light', 'noir', 'matrix', 'catppuccin-latte', 'catppuccin-frappe', 'catppuccin-macchiato', 'catppuccin-mocha'], themeMode: ['light', 'dark', 'system'],
  })) if (typeof state[key] === 'string' && allowed.includes(state[key])) result[key] = state[key]
  if (typeof state.pureBlack === 'boolean') result.pureBlack = state.pureBlack
  if (typeof state.themeSyncEnabled === 'boolean') result.themeSyncEnabled = state.themeSyncEnabled
  if (typeof state.spoilerProtection === 'boolean') result.spoilerProtection = state.spoilerProtection
  if (typeof state.themeUpdatedAt === 'string' && !Number.isNaN(Date.parse(state.themeUpdatedAt))) result.themeUpdatedAt = state.themeUpdatedAt
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
