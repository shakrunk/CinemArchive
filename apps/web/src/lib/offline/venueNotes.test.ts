// @vitest-environment node
import { IDBFactory, IDBObjectStore } from 'fake-indexeddb'
import { expect, it, vi } from 'vitest'
import { IndexedDbOfflineStore } from './storage'
import { OfflineCoordinator } from './coordinator'
import { createCommand } from './commands'
import { emptySnapshot, type OfflineSnapshot } from './snapshot'
import { replayPending } from './replay'
import { captureVenueDraft, normalizeVenue, venueChange } from '../venueNotes'
import { libraryOperations } from '../offlineRpc'
import type { VenueNote } from '../moviegoingPreferences'

const scope = { projectId: 'https://owner.test', userId: '10000000-0000-4000-8000-000000000001' }
const date = '2026-10-08T12:00:00.123456Z'
const venue = "O'Brien Cinema"
const note: VenueNote = { id: '20000000-0000-4000-8000-000000000001', userId: scope.userId, venue, notes: 'Parking', createdAt: date, updatedAt: date }
const base = (notes: VenueNote[] = []): OfflineSnapshot => ({ ...emptySnapshot(), venueNotes: notes, theaterInterest: [], moviegoingPreferencesSupport: 'authoritative' })
async function setup(initial = base()) {
  const factory = new IDBFactory()
  const store = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'venues' })
  await store.replaceBase(scope, initial)
  const coordinator = new OfflineCoordinator({ store, isAuthenticated: async () => true, lock: async (_name, work) => work(),
    deliver: vi.fn(), onState: vi.fn(), onError: vi.fn() })
  await coordinator.activate(scope)
  const save = async (text: string | null) => {
    const read = (await store.read(scope)).document
    const draft = captureVenueDraft(replayPending(read.base, read.commands), read.commands, scope.userId, venue)
    const command = createCommand(scope, venueChange(draft, text))
    await store.append(command)
    return command
  }
  return { store, factory, coordinator, save }
}

it('freezes exact opening revisions, preserves empty notes and orders causal edits over a natural identity', async () => {
  const { store, coordinator, save } = await setup(base([note]))
  try {
    const first = await save(''), second = await save('New parking')
    expect(libraryOperations(first)).toEqual([{ table: 'venue_notes', action: 'update', key: { venue }, values: { notes: '' }, expectedUpdatedAt: date }])
    expect(libraryOperations(second)[0]).toMatchObject({ expectedOperationId: first.id, values: { notes: 'New parking' } })
    const read = (await store.read(scope)).document
    expect(read.commands[1].dependsOn).toEqual([first.id])
    expect(replayPending(read.base, read.commands).venueNotes?.[0].notes).toBe('New parking')
    const oldDraft = captureVenueDraft(base([note]), [], scope.userId, venue)
    await expect(store.append(createCommand(scope, venueChange(oldDraft, 'Stale editor')))).rejects.toThrow('changed while the editor')
    expect((await store.read(scope)).document.commands).toHaveLength(2)
  } finally { coordinator.deactivate(); await store.close() }
})

it('adopts the canonical ID after an equal-text concurrent create and preserves later remote text', async () => {
  const { store, factory, coordinator, save } = await setup()
  let reopened: IndexedDbOfflineStore | undefined
  try {
    const first = await save('Parking')
    const original = JSON.stringify(first.mutation)
    await store.close()
    reopened = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'venues' })
    expect(JSON.stringify((await reopened.read(scope)).document.commands[0].mutation)).toBe(original)
    await reopened.acknowledge(scope, first.id, undefined, base([{ ...note, notes: 'Newer remote note' }]))
    expect((await reopened.read(scope)).document.base.venueNotes).toEqual([{ ...note, notes: 'Newer remote note' }])
  } finally { coordinator.deactivate(); await reopened?.close(); await store.close() }
})

it('distinguishes explicit removal from empty text and safely creates after a queued deletion', async () => {
  const { store, coordinator, save } = await setup(base([note]))
  try {
    const remove = await save(null), recreate = await save('')
    expect(libraryOperations(remove)).toEqual([{ table: 'venue_notes', action: 'delete', key: { venue }, expectedUpdatedAt: date }])
    expect(libraryOperations(recreate)).toEqual([{ table: 'venue_notes', action: 'insert', key: { venue }, values: { notes: '' } }])
    expect((await store.read(scope)).document.commands[1].dependsOn).toEqual([remove.id])
  } finally { coordinator.deactivate(); await store.close() }
})

it('requires definitive rejection, clears only that evidence on exact retry and prevents generic discard', async () => {
  const { store, coordinator, save } = await setup()
  try {
    const command = await save('My note'), frozen = JSON.stringify(command.mutation)
    await expect(coordinator.reviewVenue(command.id, async () => base([note]))).rejects.toThrow('not known to be rejected')
    await expect(store.discard(scope, command.id)).rejects.toThrow('compare and resolve')
    await store.recordFailure(scope, command.id, { state: 'conflict', message: 'Exists', venueRejection: true })
    await store.retry(scope, command.id)
    const retried = (await store.read(scope)).document.commands[0]
    expect(retried.venueRejection).toBeUndefined()
    expect(JSON.stringify(retried.mutation)).toBe(frozen)
    await expect(coordinator.reviewVenue(command.id, async () => base([note]))).rejects.toThrow('not known to be rejected')
  } finally { coordinator.deactivate(); await store.close() }
})

it('compares a rejected create and blocked successors, then atomically saves the latest intent with a fresh guard', async () => {
  const { store, coordinator, save } = await setup()
  try {
    const first = await save('Original'), second = await save('Latest')
    await store.recordFailure(scope, first.id, { state: 'conflict', message: 'Concurrent create', venueRejection: true })
    const review = await coordinator.reviewVenue(first.id, async () => base([note]))
    expect(review.notes).toBe('Latest')
    expect(review.current).toEqual(note)
    await expect(coordinator.resolveVenue(review, true, async () => base([{ ...note, notes: 'Changed again' }]))).rejects.toThrow('changed again')
    expect((await store.read(scope)).document.commands).toHaveLength(2)
    await coordinator.resolveVenue(review, true, async () => base([note]))
    const read = (await store.read(scope)).document
    expect(read.commands).toHaveLength(1)
    expect([first.id, second.id]).not.toContain(read.commands[0].id)
    expect(libraryOperations(read.commands[0])[0]).toMatchObject({ action: 'update', expectedUpdatedAt: date, values: { notes: 'Latest' } })
    expect(replayPending(read.base, read.commands).venueNotes?.[0].id).toBe(note.id)
  } finally { coordinator.deactivate(); await store.close() }
})

it('can keep server data, but rejects changed chains and uncertain successors without deleting drafts', async () => {
  const { store, coordinator, save } = await setup()
  try {
    const first = await save('Draft')
    await store.recordFailure(scope, first.id, { state: 'conflict', message: 'Exists', venueRejection: true })
    const review = await coordinator.reviewVenue(first.id, async () => base([note]))
    const next = await save('Later draft')
    await expect(coordinator.resolveVenue(review, false, async () => base([note]))).rejects.toThrow('changed again')
    await store.recordFailure(scope, next.id, { state: 'pending', message: 'Unknown outcome' })
    await expect(coordinator.reviewVenue(first.id, async () => base([note]))).rejects.toThrow('uncertain')
    expect((await store.read(scope)).document.commands).toHaveLength(2)
  } finally { coordinator.deactivate(); await store.close() }
  const fresh = await setup()
  try {
    const first = await fresh.save('Draft')
    await fresh.store.recordFailure(scope, first.id, { state: 'conflict', message: 'Exists', venueRejection: true })
    const review = await fresh.coordinator.reviewVenue(first.id, async () => base([note]))
    await fresh.coordinator.resolveVenue(review, false, async () => base([note]))
    expect((await fresh.store.read(scope)).document).toMatchObject({ commands: [], base: { venueNotes: [note] } })
  } finally { fresh.coordinator.deactivate(); await fresh.store.close() }
})

it('validates owner, code-point bounds, exact btrim names and rejects unsupported admission', async () => {
  expect(normalizeVenue('  \tCinema\t  ')).toBe('\tCinema\t')
  expect(() => createCommand(scope, venueChange({ venue: '__proto__', userId: scope.userId, baseline: null }, '🎥'.repeat(20_000)))).not.toThrow()
  expect(() => createCommand(scope, venueChange({ venue, userId: note.id, baseline: null }, 'private'))).toThrow()
  expect(() => createCommand(scope, venueChange({ venue, userId: scope.userId, baseline: null }, 'x'.repeat(20_001)))).toThrow()
  const { store, coordinator } = await setup({ ...base(), moviegoingPreferencesSupport: 'unsupported' })
  try {
    await expect(store.append(createCommand(scope, venueChange({ venue, userId: scope.userId, baseline: null }, 'Draft')))).rejects.toThrow('updated server')
    expect((await store.read(scope)).document.commands).toEqual([])
  } finally { coordinator.deactivate(); await store.close() }
})

it('rolls back comparison resolution on quota failure and blocks dispatch if retry cannot clear rejection proof', async () => {
  const { store, coordinator, save } = await setup()
  try {
    const command = await save('Keep my draft')
    await store.recordFailure(scope, command.id, { state: 'conflict', message: 'Concurrent create', venueRejection: true })
    const before = (await store.read(scope)).document
    const review = await coordinator.reviewVenue(command.id, async () => base([note]))
    const put = vi.spyOn(IDBObjectStore.prototype, 'put').mockImplementation(() => { throw new DOMException('Full', 'QuotaExceededError') })
    try {
      await expect(coordinator.resolveVenue(review, true, async () => base([note]))).rejects.toThrow('not saved')
      await expect(coordinator.retry(command.id)).rejects.toThrow('not saved')
    } finally { put.mockRestore() }
    expect((await store.read(scope)).document).toEqual(before)
    expect((await store.read(scope)).document.commands[0].venueRejection).toBe(true)
  } finally { coordinator.deactivate(); await store.close() }
})

it('fences account changes while reviewing server notes and never clears either owner journal', async () => {
  const { store, coordinator, save } = await setup()
  try {
    const command = await save('Owner A draft')
    await store.recordFailure(scope, command.id, { state: 'conflict', message: 'Exists', venueRejection: true })
    const review = await coordinator.reviewVenue(command.id, async () => base([note]))
    await expect(coordinator.resolveVenue(review, false, async () => {
      await coordinator.activate({ ...scope, userId: note.id })
      return base([note])
    })).rejects.toThrow('account changed')
    expect((await store.read(scope)).document.commands[0].id).toBe(command.id)
    expect((await store.read({ ...scope, userId: note.id })).document.commands).toEqual([])
    await coordinator.activate(scope)
    await expect(coordinator.reviewVenue(command.id, async () => base([{ ...note, userId: note.id }]))).rejects.toThrow()
  } finally { coordinator.deactivate(); await store.close() }
})
