import { createCommand, sameScope, scopeKey, type Mutation, type OfflineScope, type PendingCommand } from './commands'
import { replayPending } from './replay'
import { mutationEntities } from './entities'
import type { TicketCapture } from '../tickets/types'
import type { OfflineSnapshot } from './snapshot'
import { IndexedDbOfflineStore, type OfflineRead } from './storage'

export type DeliveryResult =
  | { kind: 'success'; canonicalEffect?: Mutation; canonicalBase?: OfflineSnapshot }
  | { kind: 'retry'; message: string; retryAfterMs?: number }
  | { kind: 'auth'; message: string }
  | { kind: 'failed' | 'conflict'; message: string }

export interface DeliveryContext {
  scope: OfflineScope
  signal: AbortSignal
  /** Writers must also honor this before each request in a multi-step adapter. */
  isCurrent: () => boolean
}
export type ExclusiveLock = <T>(name: string, work: () => Promise<T>) => Promise<T>

/** Fail closed when cross-tab exclusion is unavailable. Storage and local edits
 * still work; callers can inject another genuinely cross-tab lock adapter. */
export const browserExclusiveLock: ExclusiveLock = async (name, work) => {
  if (typeof navigator === 'undefined' || !navigator.locks) throw new Error('Cross-tab offline delivery coordination is unavailable')
  return navigator.locks.request(name, work)
}

export interface OfflineCoordinatorState extends OfflineRead { snapshot: OfflineSnapshot }
interface Session { scope: OfflineScope; generation: number; abort: AbortController }
interface CoordinatorOptions {
  store: IndexedDbOfflineStore
  deliver: (command: PendingCommand, context: DeliveryContext) => Promise<DeliveryResult>
  /** Must check the actual auth client, not just the displayed owner. */
  isAuthenticated: (scope: OfflineScope) => boolean | Promise<boolean>
  onState: (state: OfflineCoordinatorState | null) => void
  onError: (error: unknown) => void
  lock?: ExclusiveLock
  now?: () => number
  random?: () => number
}

/** Serializes delivery and refresh across tabs, while append transactions remain
 * available during slow network work. Every fetched base is replayed with the
 * latest journal; no acknowledgement can race an older fetch under this lock.
 * Dependencies preserve enqueue order for related records. A failed title
 * must not block an unrelated title, list, or board from reaching the server. */
export class OfflineCoordinator {
  private active: Session | undefined
  private generation = 0
  private retryTimer: ReturnType<typeof setTimeout> | undefined
  private publishedRevision = -1
  private publishedQuarantineIds = new Set<string>()
  private lock: ExclusiveLock
  private now: () => number
  private random: () => number
  private readonly options: CoordinatorOptions

  constructor(options: CoordinatorOptions) {
    this.options = options
    this.lock = options.lock ?? browserExclusiveLock
    this.now = options.now ?? Date.now
    this.random = options.random ?? Math.random
  }

  private current(session: Session): boolean { return this.active === session && !session.abort.signal.aborted }
  private context(session: Session): DeliveryContext {
    return { scope: { ...session.scope }, signal: session.abort.signal, isCurrent: () => this.current(session) }
  }
  private publish(session: Session, read: OfflineRead): void {
    if (!this.current(session)) return
    const quarantineIds = new Set(read.quarantined.map((entry) => entry.id))
    // Quarantine resets a damaged document's revision. Its durable records are
    // an epoch barrier: show the error despite that lower revision, and reject
    // any delayed response from before the quarantine, even with a higher one.
    if ([...this.publishedQuarantineIds].some((id) => !quarantineIds.has(id))) return
    const newQuarantine = quarantineIds.size > this.publishedQuarantineIds.size
    if (!newQuarantine && read.document.revision < this.publishedRevision) return
    this.publishedQuarantineIds = quarantineIds
    this.publishedRevision = read.document.revision
    this.options.onState({ ...read, snapshot: replayPending(read.document.base, read.document.commands) })
  }
  private capture(): Session {
    if (!this.active) throw new Error('No authenticated owner is active for offline changes')
    return this.active
  }

  async activate(scope: OfflineScope): Promise<void> {
    scopeKey(scope) // Validate before changing the current session.
    this.deactivate()
    const session = { scope: { ...scope }, generation: ++this.generation, abort: new AbortController() }
    this.active = session
    this.publish(session, await this.options.store.read(session.scope))
  }

  /** Call before logout, account switching, or entering a shared/friend view.
   * Pending private data remains durable under its original owner. */
  deactivate(): void {
    this.active?.abort.abort()
    this.active = undefined
    this.generation++
    this.publishedRevision = -1
    this.publishedQuarantineIds = new Set()
    clearTimeout(this.retryTimer)
    this.retryTimer = undefined
    this.options.onState(null)
  }

  /** Resolves only after the command transaction commits. Integrating UI must
   * not announce "saved offline" until this promise resolves. */
  async submit(mutation: Mutation, options?: Parameters<typeof createCommand>[2]): Promise<PendingCommand> {
    const session = this.capture()
    const command = createCommand(session.scope, mutation, options)
    const read = await this.options.store.append(command)
    this.publish(session, read)
    return read.document.commands.find((entry) => entry.id === command.id)!
  }

  async attachTicket(outingId: string, capture: TicketCapture, blob: Blob, id = crypto.randomUUID()): Promise<PendingCommand> {
    const session = this.capture()
    const read = await this.options.store.attachTicket(session.scope, outingId, capture, blob, { id })
    this.publish(session, read)
    if (!this.current(session)) throw new Error('The ticket was saved for the previous account; the account has now changed')
    return read.document.commands.find((command) => command.id === id)!
  }

  async detachTicket(outingId: string, id = crypto.randomUUID()): Promise<PendingCommand> {
    const session = this.capture()
    const read = await this.options.store.detachTicket(session.scope, outingId, { id })
    this.publish(session, read)
    if (!this.current(session)) throw new Error('The ticket change was saved for the previous account; the account has now changed')
    return read.document.commands.find((command) => command.id === id)!
  }

  /** Re-read after another tab's broadcast/storage event. No network needed. */
  async reload(): Promise<void> {
    const session = this.capture()
    this.publish(session, await this.options.store.read(session.scope))
  }

  async refresh(fetchBase: (context: DeliveryContext) => Promise<OfflineSnapshot>): Promise<void> {
    const session = this.capture()
    await this.lock(`cinemarchive-offline:${scopeKey(session.scope)}`, async () => {
      if (!this.current(session) || !await this.options.isAuthenticated(session.scope) || !this.current(session)) return
      const base = await fetchBase(this.context(session))
      if (!this.current(session)) return
      this.publish(session, await this.options.store.replaceBase(session.scope, base))
    })
  }

  async retry(commandId: string): Promise<void> {
    const session = this.capture()
    this.publish(session, await this.options.store.retry(session.scope, commandId))
    if (this.current(session)) await this.flush()
  }

  /** Server-maintained changes (for example due outings) share the delivery
   * lock. Recheck the durable journal here: another tab may have appended work
   * since the UI last reported an empty queue. New local edits during the
   * request remain journaled and are replayed over the refreshed base. */
  async runIdleRemote<T>(work: (context: DeliveryContext) => Promise<T>, fetchBase: (context: DeliveryContext) => Promise<OfflineSnapshot>): Promise<T | undefined> {
    const session = this.capture()
    return this.lock(`cinemarchive-offline:${scopeKey(session.scope)}`, async () => {
      if (!this.current(session) || !await this.options.isAuthenticated(session.scope) || !this.current(session)) return
      const read = await this.options.store.read(session.scope)
      this.publish(session, read)
      if (!this.current(session) || read.document.commands.length || read.quarantined.length) return
      const result = await work(this.context(session))
      if (!this.current(session)) return
      const base = await fetchBase(this.context(session))
      if (!this.current(session)) return
      this.publish(session, await this.options.store.replaceBase(session.scope, base))
      return this.current(session) ? result : undefined
    })
  }

  /** User-triggered remote effects use a fresh owner snapshot under the delivery
   * lock. Unlike reconciliation there is no fallible refresh AFTER the effect:
   * a successful send must not be presented as failed because a later read fails.
   * Recheck relevant pending writes after refresh and immediately before dispatch. */
  async runSyncedRemote<T>(keys: readonly string[], work: (snapshot: OfflineSnapshot, context: DeliveryContext, assertReady: () => Promise<void>) => Promise<T>, fetchBase: (context: DeliveryContext) => Promise<OfflineSnapshot>): Promise<T> {
    const session = this.capture()
    return this.lock(`cinemarchive-offline:${scopeKey(session.scope)}`, async () => {
      const assertOwner = async () => {
        if (!this.current(session) || !await this.options.isAuthenticated(session.scope) || !this.current(session)) throw new Error('Library account changed')
      }
      const readReady = async () => {
        await assertOwner()
        const read = await this.options.store.read(session.scope)
        if (!this.current(session)) throw new Error('Library account changed')
        this.publish(session, read)
        const snapshot = replayPending(read.document.base, read.document.commands)
        if (read.quarantined.length || read.document.commands.some((command) => {
          const entities = mutationEntities(command.mutation, snapshot)
          return keys.some((key) => entities.has(key))
        })) throw new Error('Sync this title and outing before sharing your plans. Resolve any pending changes, then retry.')
        return snapshot
      }
      await readReady()
      const base = await fetchBase(this.context(session))
      await assertOwner()
      this.publish(session, await this.options.store.replaceBase(session.scope, base))
      const snapshot = await readReady()
      const result = await work(snapshot, this.context(session), async () => { await readReady() })
      if (!this.current(session)) throw new Error('Library account changed')
      return result
    })
  }

  async discard(commandId: string): Promise<void> {
    const session = this.capture()
    this.publish(session, await this.options.store.discard(session.scope, commandId))
  }

  private schedule(session: Session, delay: number): void {
    clearTimeout(this.retryTimer)
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined
      if (this.current(session)) void this.flush().catch(this.options.onError)
    }, Math.max(1, delay))
  }

  async flush(): Promise<void> {
    const session = this.capture()
    await this.lock(`cinemarchive-offline:${scopeKey(session.scope)}`, async () => {
      clearTimeout(this.retryTimer)
      this.retryTimer = undefined
      while (this.current(session)) {
        if (!await this.options.isAuthenticated(session.scope) || !this.current(session)) return
        const read = await this.options.store.read(session.scope)
        this.publish(session, read)
        if (!this.current(session)) return
        // Corrupt work may include a prerequisite of a valid command. Require
        // recovery before making any remote writes for this owner.
        if (read.quarantined.length > 0) return
        const ready = read.document.commands.filter((entry) => entry.state === 'pending' && entry.dependsOn.length === 0)
        const command = ready.find((entry) => entry.nextAttemptAt <= this.now())
        if (!command) {
          const nextAttempt = Math.min(...ready.map((entry) => entry.nextAttemptAt))
          if (Number.isFinite(nextAttempt)) this.schedule(session, nextAttempt - this.now())
          return
        }
        if (!sameScope(command.scope, session.scope)) throw new Error('Offline command scope mismatch')
        let result: DeliveryResult
        try { result = await this.options.deliver(command, this.context(session)) } catch (error) {
          // Adapters classify known permanent/auth errors; unexpected transport
          // exceptions retain the command instead of turning it into success.
          result = { kind: 'retry', message: error instanceof Error ? error.message : 'Delivery failed' }
        }
        if (!this.current(session)) return // Unknown outcome: next session retries the same operation ID.
        if (result.kind === 'success') {
          this.publish(session, await this.options.store.acknowledge(session.scope, command.id, result.canonicalEffect, result.canonicalBase))
          continue
        }
        if (result.kind === 'retry') {
          const delay = Math.min(300_000, result.retryAfterMs ?? 1000 * 2 ** Math.min(command.attempts, 8) * (0.75 + this.random() / 2))
          this.publish(session, await this.options.store.recordFailure(session.scope, command.id, {
            state: 'pending', message: result.message, nextAttemptAt: this.now() + Math.ceil(Math.max(1, delay)),
          }))
          continue // Related work waits on this receipt; unrelated work may proceed.
        }
        this.publish(session, await this.options.store.recordFailure(session.scope, command.id, {
          state: result.kind === 'auth' ? 'pending' : result.kind, message: result.message,
        }))
        if (result.kind === 'auth') return // Resume on auth/reconnect, never a tight timer loop.
      }
    })
  }
}
