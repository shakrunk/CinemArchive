import { IDBFactory } from 'fake-indexeddb'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createCommand, type PendingCommand, type OutingRevertMutation } from './offline/commands'
import { IndexedDbOfflineStore } from './offline/storage'
import { owner, outing, snapshot, title } from './offline/fixtures.test-support'
import { replayPending } from './offline/replay'
import { outingRevertBody, outingRevertReceiptStatus } from './outingRevert'
import { createLibraryCommandDelivery, libraryOperations } from './offlineRpc'

const { getSession } = vi.hoisted(() => ({ getSession: vi.fn() }))
vi.mock('./auth', () => ({ supabase: { auth: { getSession } } }))
const stamp = '2026-10-08T12:00:00.123456Z'
const mutation: OutingRevertMutation = { kind: 'outing.revert', outingId: outing.id, titleId: title.id, viewingId: 'viewing-a', viewingPresent: true }
const completed = () => ({ ...snapshot(), titles: [{ ...title, status: 'watched' as const, viewings: [{ id: 'viewing-a', titleId: title.id, outingId: outing.id }] }],
  outings: [{ ...outing, status: 'completed' as const, previousStatus: 'watchlist' as const, completedViewingId: 'viewing-a' }],
  rowRevisions: { [`cinema_outings:${outing.id}`]: stamp, 'viewings:viewing-a': stamp } })
const command = () => createCommand(owner, mutation, { preconditions: [
  { table: 'cinema_outings', id: outing.id, updatedAt: stamp }, { table: 'viewings', id: 'viewing-a', updatedAt: stamp },
] })
function receipt(pending: PendingCommand) {
  const body = outingRevertBody(pending)
  return { operationId: pending.id, outingId: outing.id, status: 'applied', canonicalViewingId: 'viewing-a',
    request: { kind: 'outing.revert', outingId: outing.id, expectedUpdatedAt: body.p_expected_updated_at,
      expectedViewingId: 'viewing-a', expectedViewingUpdatedAt: body.p_expected_viewing_updated_at,
      ...(body.p_expected_operation_id ? { expectedOperationId: body.p_expected_operation_id } : {}),
      ...(body.p_expected_viewing_operation_id ? { expectedViewingOperationId: body.p_expected_viewing_operation_id } : {}),
    }, rows: [
      { table: 'cinema_outings', key: { id: outing.id }, row: { id: outing.id, user_id: owner.userId, title_id: title.id, status: 'missed', completed_viewing_id: null } },
      { table: 'titles', key: { id: title.id }, row: { id: title.id, user_id: owner.userId } },
    ] }
}
beforeEach(() => {
  vi.stubEnv('VITE_SUPABASE_URL', owner.projectId)
  vi.stubEnv('VITE_SUPABASE_ANON_KEY', 'test-key')
  getSession.mockResolvedValue({ data: { session: { user: { id: owner.userId }, access_token: 'captured-owner-token' } }, error: null })
})
afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.clearAllMocks() })

it('persists exact outing and viewing receipt dependencies across restart and acknowledgment', async () => {
  const factory = new IDBFactory(), db = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'revert' })
  const later = new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'revert' })
  try {
    await db.replaceBase(owner, completed())
    const edit = createCommand(owner, { kind: 'outing.patch', outingId: outing.id, patch: { venue: 'Queued venue' } })
    const note = createCommand(owner, { kind: 'viewing.patch', titleId: title.id, viewingId: 'viewing-a', patch: { notes: 'Queued notes' } })
    await db.append(edit); await db.append(note)
    const revert = createCommand(owner, mutation)
    await db.append(revert)
    const queued = (await db.read(owner)).document.commands.at(-1)!
    expect(outingRevertBody(queued)).toMatchObject({ p_expected_updated_at: null, p_expected_operation_id: edit.id,
      p_expected_viewing_updated_at: null, p_expected_viewing_operation_id: note.id })
    await db.close()
    const reopened = (await later.read(owner)).document
    expect(reopened.commands.at(-1)).toEqual(queued)
    expect(replayPending(reopened.base, reopened.commands).titles[0]).toMatchObject({ status: 'watched', viewings: [] })
    await later.acknowledge(owner, edit.id); await later.acknowledge(owner, note.id)
    expect((await later.read(owner)).document.commands[0].preconditions).toEqual(queued.preconditions)
    expect((await later.read({ ...owner, userId: 'other-owner' })).document.commands).toEqual([])
  } finally { await db.close(); await later.close() }
})

it('requires both observed revisions for present history and keeps reversal out of generic batches', () => {
  expect(() => outingRevertBody(createCommand(owner, mutation))).toThrow('confirmed revision')
  expect(() => createCommand(owner, { kind: 'batch', mutations: [mutation] } as never)).toThrow('unsupported')
  expect(() => libraryOperations(command())).toThrow('guarded delivery')
  expect(() => libraryOperations(createCommand(owner, { kind: 'batch', mutations: [
    { kind: 'viewing.delete', titleId: title.id, viewingId: 'viewing-a' },
    { kind: 'outing.patch', outingId: outing.id, patch: { status: 'missed' } },
  ] }))).toThrow('needs review')
})

it('does not hide newly rated or unrelated canonical history during rebase', () => {
  const base = completed()
  base.titles[0].viewings = [{ ...base.titles[0].viewings[0], rating: 4 } as never]
  expect(replayPending(base, [command()])).toEqual(base)
  const changed = completed()
  changed.outings[0].completedViewingId = 'another-viewing'
  expect(replayPending(changed, [command()])).toEqual(changed)
})

it('validates the exact request, owner, identities and microsecond precision in accepted receipts', () => {
  const pending = command(), body = outingRevertBody(pending), accepted = receipt(pending)
  expect(outingRevertReceiptStatus(pending, body, accepted)).toBe('applied')
  accepted.request.expectedUpdatedAt = '2026-10-08T06:00:00.123456-06:00'
  expect(outingRevertReceiptStatus(pending, body, accepted)).toBe('applied')
  accepted.request.expectedUpdatedAt = '2026-10-08T12:00:00.123457Z'
  expect(outingRevertReceiptStatus(pending, body, accepted)).toBeNull()
  accepted.request.expectedUpdatedAt = stamp
  accepted.rows[0].row.user_id = 'another-owner'
  expect(outingRevertReceiptStatus(pending, body, accepted)).toBeNull()
  expect(outingRevertReceiptStatus(pending, body, { ...receipt(pending), operationId: 'other-operation' })).toBeNull()
})

it('uses the dedicated RPC, then fresh state, and only confirms notifications after acceptance', async () => {
  const pending = command(), canonical = { ...snapshot(), titles: [{ ...title, status: 'watching' as const }] }
  const request = vi.fn().mockResolvedValue(new Response(JSON.stringify(receipt(pending))))
  vi.stubGlobal('fetch', request)
  const fetchBase = vi.fn().mockResolvedValue(canonical), confirmed = vi.fn()
  const context = { scope: owner, signal: new AbortController().signal, isCurrent: () => true }
  expect(await createLibraryCommandDelivery(fetchBase, undefined, confirmed)(pending, context)).toEqual({ kind: 'success', canonicalBase: canonical })
  expect(request.mock.calls[0][0]).toContain('/rpc/revert_cinema_outing')
  expect(JSON.parse(request.mock.calls[0][1].body)).toEqual(outingRevertBody(pending))
  expect(request.mock.calls[0][1].headers.Authorization).toBe('Bearer captured-owner-token')
  expect(confirmed).toHaveBeenCalledWith(outing.id)
})

it('retains conflict, unknown response and account-switch outcomes without confirming removal', async () => {
  const pending = command(), accepted = receipt(pending), confirmed = vi.fn(), fetchBase = vi.fn().mockResolvedValue(snapshot())
  const context = { scope: owner, signal: new AbortController().signal, isCurrent: () => true }
  const request = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...accepted, status: 'conflict' })))
  vi.stubGlobal('fetch', request)
  const deliver = createLibraryCommandDelivery(fetchBase, undefined, confirmed)
  expect((await deliver(pending, context)).kind).toBe('conflict')
  expect(fetchBase).not.toHaveBeenCalled()
  request.mockResolvedValue(new Response(JSON.stringify({ ...accepted, rows: [] })))
  expect((await deliver(pending, context)).kind).toBe('retry')
  let current = true
  request.mockResolvedValue(new Response(JSON.stringify(accepted)))
  fetchBase.mockImplementation(async () => { current = false; return snapshot() })
  expect((await deliver(pending, { ...context, isCurrent: () => current })).kind).toBe('auth')
  expect(confirmed).not.toHaveBeenCalled()
})
