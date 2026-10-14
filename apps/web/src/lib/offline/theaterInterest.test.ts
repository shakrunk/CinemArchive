// @vitest-environment node
import { IDBFactory } from 'fake-indexeddb'
import { expect, it } from 'vitest'
import { IndexedDbOfflineStore } from './storage'
import { createCommand, type TrackingMutation } from './commands'
import { emptySnapshot } from './snapshot'
import { replayPending, applyMutation } from './replay'
import { title } from './fixtures.test-support'
import { libraryOperations } from '../offlineRpc'

const scope = { projectId: 'https://owner.test', userId: '10000000-0000-4000-8000-000000000001' }
const movie = { ...title, id: '20000000-0000-4000-8000-000000000001', type: 'movie' as const }
const date = '2026-10-08T12:00:00Z'
const base = () => ({ ...emptySnapshot(), titles: [movie], moviegoingPreferencesSupport: 'authoritative' as const, venueNotes: [], theaterInterest: [] })
const mutation = (present: boolean): TrackingMutation => ({ kind: 'theaterInterest.set', titleId: movie.id, userId: scope.userId, present, createdAt: date })

it('persists quick desired-presence changes in order across restart without changing payload timestamps', async () => {
  const factory = new IDBFactory()
  let store = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'interest' })
  try {
    await store.replaceBase(scope, base())
    const first = createCommand(scope, mutation(true)), second = createCommand(scope, mutation(false))
    await store.append(first); await store.append(second)
    const initial = (await store.read(scope)).document
    expect(initial.commands[1].dependsOn).toEqual([first.id])
    expect(replayPending(initial.base, initial.commands).theaterInterest).toEqual([])
    const frozen = JSON.stringify(initial.commands)
    await store.close()
    store = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'interest' })
    const reopened = (await store.read(scope)).document
    expect(JSON.stringify(reopened.commands)).toBe(frozen)
    expect(libraryOperations(reopened.commands[0])).toEqual([{ table: 'theater_interest', action: 'insert', key: { id: movie.id }, values: { title_id: movie.id, created_at: date } }])
    expect(libraryOperations(reopened.commands[1])).toEqual([{ table: 'theater_interest', action: 'delete', key: { id: movie.id } }])
    const accepted = applyMutation(base(), first.mutation)
    await store.acknowledge(scope, first.id, undefined, accepted)
    const afterAck = (await store.read(scope)).document
    expect(replayPending(afterAck.base, afterAck.commands).theaterInterest).toEqual([])
    expect(afterAck.commands[0].mutation).toEqual(second.mutation)
  } finally { await store.close() }
})

it('orders a new title before interest and title deletion after it without resurrecting either', async () => {
  const store = new IndexedDbOfflineStore({ indexedDB: new IDBFactory() })
  try {
    await store.replaceBase(scope, { ...base(), titles: [] })
    const create = createCommand(scope, { kind: 'title.create', title: movie })
    const interest = createCommand(scope, mutation(true))
    const remove = createCommand(scope, { kind: 'title.delete', titleId: movie.id })
    await store.append(create); await store.append(interest); await store.append(remove)
    const read = (await store.read(scope)).document
    expect(read.commands.map((command) => command.dependsOn)).toEqual([[], [create.id], [interest.id]])
    expect(replayPending(read.base, read.commands)).toMatchObject({ titles: [], theaterInterest: [] })
    expect(applyMutation({ ...base(), titles: [] }, interest.mutation).theaterInterest).toEqual([])
  } finally { await store.close() }
})

it('rejects unsupported, missing or TV parents before committing a command or optimism', async () => {
  const store = new IndexedDbOfflineStore({ indexedDB: new IDBFactory() })
  try {
    for (const snapshot of [{ ...base(), moviegoingPreferencesSupport: 'unsupported' as const }, { ...base(), titles: [] }, { ...base(), titles: [{ ...movie, type: 'tv' as const }] }]) {
      await store.replaceBase(scope, snapshot)
      await expect(store.append(createCommand(scope, mutation(true)))).rejects.toThrow()
      const read = (await store.read(scope)).document
      expect(read.commands).toEqual([])
      expect(read.base.theaterInterest).toEqual([])
    }
  } finally { await store.close() }
})

it('validates immutable owner identity and refuses anonymous, malformed or revision-bound interest', () => {
  for (const userId of ['anonymous-local-only', '30000000-0000-4000-8000-000000000001']) {
    expect(() => createCommand({ ...scope, userId }, mutation(true))).toThrow()
  }
  expect(() => createCommand(scope, { ...mutation(true), createdAt: 'not a date' } as TrackingMutation)).toThrow()
  expect(() => createCommand(scope, mutation(false), { baseRevision: date })).toThrow()
})

it('never exposes owner A interest through owner B cache reads', async () => {
  const store = new IndexedDbOfflineStore({ indexedDB: new IDBFactory() })
  try {
    await store.replaceBase(scope, base())
    await store.append(createCommand(scope, mutation(true)))
    const other = (await store.read({ ...scope, userId: '30000000-0000-4000-8000-000000000001' })).document
    expect(other.commands).toEqual([])
    expect(other.base.theaterInterest).toBeUndefined()
    const own = (await store.read(scope)).document
    expect(replayPending(own.base, own.commands).theaterInterest?.[0].userId).toBe(scope.userId)
  } finally { await store.close() }
})
