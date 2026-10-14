// @vitest-environment node
import { IDBFactory } from 'fake-indexeddb'
import { expect, it } from 'vitest'
import { IndexedDbOfflineStore } from './storage'
import { emptySnapshot, type OfflineSnapshot } from './snapshot'
import { assertSnapshot } from './validation'
import { createCommand } from './commands'

const scope = { projectId: 'https://owner.test', userId: '10000000-0000-4000-8000-000000000001' }
const id = '20000000-0000-4000-8000-000000000001'
const date = '2026-10-08T12:00:00.123456Z'
function snapshot(): OfflineSnapshot {
  return { ...emptySnapshot(), moviegoingPreferencesSupport: 'authoritative',
    venueNotes: [{ id, userId: scope.userId, venue: '__proto__', notes: '', createdAt: date, updatedAt: date }],
    theaterInterest: [{ id, userId: scope.userId, titleId: id, createdAt: date, updatedAt: date }],
    rowRevisions: { [`venue_notes:${id}`]: date, [`theater_interest:${id}`]: date, [`titles:${id}`]: date },
  }
}

it('reopens private preferences and preserves both domains and revisions across unsupported refresh and ACK', async () => {
  const factory = new IDBFactory()
  let store = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'preferences' })
  try {
    await store.replaceBase(scope, snapshot())
    await store.close()
    store = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'preferences' })
    expect((await store.read(scope)).document.base).toEqual(snapshot())
    await store.replaceBase(scope, { ...emptySnapshot(), moviegoingPreferencesSupport: 'unsupported', rowRevisions: { other: date } })
    const current = (await store.read(scope)).document.base
    expect(current.venueNotes).toEqual(snapshot().venueNotes)
    expect(current.theaterInterest).toEqual(snapshot().theaterInterest)
    expect(current.rowRevisions).toEqual({ [`venue_notes:${id}`]: date, [`theater_interest:${id}`]: date, other: date })
    const command = createCommand(scope, { kind: 'ledger.set', widgets: [] })
    await store.append(command)
    await store.acknowledge(scope, command.id, undefined, emptySnapshot()) // Older reader has no capability field.
    expect((await store.read(scope)).document.base.venueNotes).toEqual(snapshot().venueNotes)
    await store.replaceBase(scope, { ...emptySnapshot(), moviegoingPreferencesSupport: 'authoritative', venueNotes: [], theaterInterest: [] })
    const cleared = (await store.read(scope)).document.base
    expect(cleared.venueNotes).toEqual([])
    expect(cleared.theaterInterest).toEqual([])
    expect(cleared.rowRevisions).toBeUndefined()
  } finally { await store.close() }
})

it('rejects cross-owner/anonymous persistence without changing the old cache', async () => {
  const store = new IndexedDbOfflineStore({ indexedDB: new IDBFactory() })
  try {
    await store.replaceBase(scope, snapshot())
    for (const userId of ['30000000-0000-4000-8000-000000000001', 'anonymous-local-only']) {
      expect(() => store.replaceBase({ ...scope, userId }, snapshot())).toThrow('another owner')
      expect((await store.read({ ...scope, userId })).document.base.venueNotes).toBeUndefined()
    }
    expect((await store.read(scope)).document.base).toEqual(snapshot())
  } finally { await store.close() }
})

it('accepts old caches but rejects partial authoritative, malformed and mixed-owner snapshot fields', () => {
  expect(() => assertSnapshot(emptySnapshot())).not.toThrow()
  expect(() => assertSnapshot({ ...emptySnapshot(), moviegoingPreferencesSupport: 'authoritative' })).toThrow('Incomplete')
  expect(() => assertSnapshot({ ...snapshot(), venueNotes: [{ ...snapshot().venueNotes![0], unrecognized: true }] })).toThrow()
  expect(() => assertSnapshot({ ...snapshot(), theaterInterest: [{ ...snapshot().theaterInterest![0], userId: id }] })).toThrow('another owner')
  expect(() => assertSnapshot({ ...snapshot(), venueNotes: [snapshot().venueNotes![0], snapshot().venueNotes![0]] })).toThrow('venue notes')
})
