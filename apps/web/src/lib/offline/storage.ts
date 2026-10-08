import { OFFLINE_VERSION, omitUndefined, sameScope, scopeKey, type Mutation, type OfflineScope, type PendingCommand } from './commands'
import { applyMutation } from './replay'
import { replayPending } from './replay'
import { mutationEntities } from './entities'
import { capturePreconditions } from './preconditions'
import { emptySnapshot, type OfflineSnapshot } from './snapshot'
import { assertCommand, assertMutation, assertSnapshot } from './validation'

const DOCUMENTS = 'owners'
const QUARANTINE = 'quarantine'
export const OFFLINE_DATABASE = 'cinemarchive-offline-v1'

export interface OwnerDocument {
  version: typeof OFFLINE_VERSION
  scope: OfflineScope
  revision: number
  nextSequence: number
  base: OfflineSnapshot
  commands: PendingCommand[]
}
export interface QuarantinedRecord { id: string; scopeKey: string; capturedAt: string; reason: string; raw: unknown }
export interface OfflineRead {
  document: OwnerDocument
  /** Quarantine survives reopening; never silently drop invalid/unknown records. */
  quarantined: QuarantinedRecord[]
}
export class OfflineStorageError extends Error {
  constructor(message: string, cause?: unknown) {
    super(message, { cause })
    this.name = 'OfflineStorageError'
  }
}

function emptyDocument(scope: OfflineScope): OwnerDocument {
  return { version: OFFLINE_VERSION, scope: { ...scope }, revision: 0, nextSequence: 1, base: emptySnapshot(), commands: [] }
}
function validateDocument(raw: unknown, scope: OfflineScope): asserts raw is OwnerDocument {
  if (!raw || typeof raw !== 'object') throw new Error('Offline owner record is not an object')
  const d = raw as OwnerDocument
  if (d.version !== OFFLINE_VERSION) throw new Error('Unsupported offline owner record version')
  if (!d.scope || !sameScope(d.scope, scope)) throw new Error('Offline owner record does not match its storage scope')
  if (!Number.isSafeInteger(d.revision) || d.revision < 0 || !Number.isSafeInteger(d.nextSequence) || d.nextSequence < 1 || !Array.isArray(d.commands)) {
    throw new Error('Invalid offline owner record counters')
  }
  assertSnapshot(d.base)
  const ids = new Set<string>()
  let previous = 0
  for (const command of d.commands) {
    assertCommand(command)
    if (!sameScope(command.scope, scope) || ids.has(command.id) || command.sequence <= previous || command.sequence >= d.nextSequence || command.dependsOn.some((id) => !ids.has(id))) {
      throw new Error('Invalid offline command ownership, identity, sequence, or dependency')
    }
    ids.add(command.id)
    previous = command.sequence
  }
}

/** The owner base and journal are one IndexedDB record. Each read/modify/write
 * uses one transaction, so two tabs cannot overwrite each other's appends and
 * an acknowledgment can never delete a command without advancing the base.
 * No external promises are awaited inside an active transaction. */
export class IndexedDbOfflineStore {
  private connection: Promise<IDBDatabase> | undefined
  private readonly options: { indexedDB?: IDBFactory; databaseName?: string }
  constructor(options: { indexedDB?: IDBFactory; databaseName?: string } = {}) { this.options = options }

  private open(): Promise<IDBDatabase> {
    if (this.connection) return this.connection
    this.connection = new Promise((resolve, reject) => {
      let factory: IDBFactory | undefined
      try { factory = this.options.indexedDB ?? globalThis.indexedDB } catch (error) {
        reject(new OfflineStorageError('Browser offline storage is unavailable', error)); return
      }
      if (!factory) { reject(new OfflineStorageError('Browser offline storage is unavailable')); return }
      const request = factory.open(this.options.databaseName ?? OFFLINE_DATABASE, 1)
      let abandoned = false
      request.onupgradeneeded = () => {
        const db = request.result
        if (!db.objectStoreNames.contains(DOCUMENTS)) db.createObjectStore(DOCUMENTS)
        if (!db.objectStoreNames.contains(QUARANTINE)) {
          db.createObjectStore(QUARANTINE, { keyPath: 'id' }).createIndex('scopeKey', 'scopeKey')
        }
      }
      request.onerror = () => { abandoned = true; reject(new OfflineStorageError('Could not open browser offline storage', request.error)) }
      request.onblocked = () => { abandoned = true; reject(new OfflineStorageError('Offline storage upgrade is blocked by another tab')) }
      request.onsuccess = () => {
        const db = request.result
        // A blocked open can finish after its caller has received a rejection
        // and retried. It must not leak a connection that blocks future upgrades.
        if (abandoned) { db.close(); return }
        db.onversionchange = () => { db.close(); this.connection = undefined }
        resolve(db)
      }
    })
    this.connection.catch(() => { this.connection = undefined })
    return this.connection
  }

  async close(): Promise<void> {
    const pending = this.connection
    this.connection = undefined
    if (pending) (await pending).close()
  }

  private async transact(scope: OfflineScope, change?: (document: OwnerDocument) => void): Promise<OfflineRead> {
    const key = scopeKey(scope)
    const db = await this.open()
    return new Promise((resolve, reject) => {
      const tx = db.transaction([DOCUMENTS, QUARANTINE], 'readwrite')
      const owners = tx.objectStore(DOCUMENTS)
      const quarantine = tx.objectStore(QUARANTINE)
      let result: OfflineRead | undefined
      let error: unknown
      const request = owners.get(key)
      request.onsuccess = () => {
        try {
          let document = request.result as OwnerDocument | undefined
          if (document !== undefined) {
            try { validateDocument(document, scope) } catch (invalid) {
              const record: QuarantinedRecord = {
                id: crypto.randomUUID(), scopeKey: key, capturedAt: new Date().toISOString(),
                reason: invalid instanceof Error ? invalid.message : 'Invalid offline data', raw: document,
              }
              quarantine.add(record)
              document = undefined
              owners.delete(key)
            }
          }
          document ??= emptyDocument(scope)
          if (change) {
            change(document)
            document.revision++
            validateDocument(document, scope)
            owners.put(document, key)
          }
          const records = quarantine.index('scopeKey').getAll(key)
          records.onsuccess = () => { result = { document, quarantined: records.result as QuarantinedRecord[] } }
        } catch (caught) { error = caught; tx.abort() }
      }
      tx.oncomplete = () => {
        if (result) resolve(result)
        else reject(new OfflineStorageError('Offline transaction completed without a result'))
      }
      tx.onabort = () => reject(new OfflineStorageError('Could not persist offline changes; they are not saved on this device', error ?? tx.error))
      tx.onerror = () => { error ??= tx.error }
    })
  }

  read(scope: OfflineScope): Promise<OfflineRead> { return this.transact(scope) }

  append(command: PendingCommand): Promise<OfflineRead> {
    assertCommand(command)
    const captured: PendingCommand = JSON.parse(JSON.stringify(command))
    return this.transact(captured.scope, (d) => {
      const command = captured
      const existing = d.commands.find((c) => c.id === command.id)
      if (existing) {
        if (JSON.stringify(existing.mutation) !== JSON.stringify(command.mutation) || command.dependsOn.some((id) => !existing.dependsOn.includes(id)) ||
            (command.preconditions && JSON.stringify(existing.preconditions) !== JSON.stringify(command.preconditions))) {
          throw new Error('An operation ID cannot be reused for a different command')
        }
        return
      }
      // A dependency must already exist in this owner's journal. Once it is
      // acknowledged it is removed from dependent commands atomically below.
      if (command.dependsOn.some((id) => !d.commands.some((c) => c.id === id))) throw new Error('Unknown command dependency')
      const projection = replayPending(d.base, d.commands)
      const entities = mutationEntities(command.mutation, projection)
      const prerequisites = d.commands.filter((pending) => [...mutationEntities(pending.mutation, projection)].some((key) => entities.has(key)))
      const preconditions = command.preconditions ?? capturePreconditions(command.mutation, d.base, d.commands)
      d.commands.push({ ...command, ...(preconditions.length ? { preconditions } : {}),
        dependsOn: [...new Set([...command.dependsOn, ...prerequisites.map((pending) => pending.id)])],
        sequence: d.nextSequence++, state: 'pending', attempts: 0, nextAttemptAt: 0 })
    })
  }

  replaceBase(scope: OfflineScope, base: OfflineSnapshot): Promise<OfflineRead> {
    const captured = omitUndefined(base)
    assertSnapshot(captured)
    return this.transact(scope, (d) => { d.base = captured })
  }

  /** Anonymous edits update their own snapshot atomically, without ever creating
   * an authenticated delivery record. Concurrent tabs cannot lose each other's
   * disjoint edits through a read/replace race. */
  applyLocal(scope: OfflineScope, mutation: Mutation): Promise<OfflineRead> {
    const captured = omitUndefined(mutation)
    assertMutation(captured)
    return this.transact(scope, (d) => { d.base = applyMutation(d.base, captured) })
  }

  /** Only invoke under the coordinator's cross-tab delivery/refresh lock. */
  acknowledge(scope: OfflineScope, commandId: string, canonicalEffect?: Mutation, canonicalBase?: OfflineSnapshot): Promise<OfflineRead> {
    if (canonicalEffect) assertMutation(canonicalEffect)
    const captured: Mutation | undefined = canonicalEffect ? JSON.parse(JSON.stringify(canonicalEffect)) : undefined
    const base = canonicalBase === undefined ? undefined : omitUndefined(canonicalBase)
    if (base !== undefined) assertSnapshot(base)
    return this.transact(scope, (d) => {
      const command = d.commands.find((c) => c.id === commandId)
      if (!command) return
      d.base = base ?? applyMutation(d.base, captured ?? command.mutation)
      d.commands = d.commands.filter((c) => c.id !== commandId).map((c) => ({ ...c, dependsOn: c.dependsOn.filter((id) => id !== commandId) }))
    })
  }

  recordFailure(scope: OfflineScope, commandId: string, failure: { state: PendingCommand['state']; message: string; nextAttemptAt?: number }): Promise<OfflineRead> {
    return this.transact(scope, (d) => {
      d.commands = d.commands.map((c) => c.id === commandId ? { ...c,
        state: failure.state, attempts: c.attempts + 1, lastError: failure.message, nextAttemptAt: failure.nextAttemptAt ?? 0,
      } : c)
    })
  }

  retry(scope: OfflineScope, commandId: string): Promise<OfflineRead> {
    return this.transact(scope, (d) => {
      d.commands = d.commands.map((c) => c.id === commandId ? { ...c, state: 'pending', nextAttemptAt: 0 } : c)
    })
  }

  /** Discard requires an explicit UI action; dependencies cannot silently vanish. */
  discard(scope: OfflineScope, commandId: string): Promise<OfflineRead> {
    return this.transact(scope, (d) => {
      if (d.commands.some((c) => c.dependsOn.includes(commandId))) throw new Error('Resolve dependent commands before discarding their prerequisite')
      d.commands = d.commands.filter((c) => c.id !== commandId)
    })
  }
}
