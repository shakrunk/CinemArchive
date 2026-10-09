import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { listOutingShareFriends, shareOutingPlans } from './db'
import type { DeliveryContext } from './offline/coordinator'

const { getSession, rpc, send, setHeader, abortSignal } = vi.hoisted(() => ({ getSession: vi.fn(), rpc: vi.fn(), send: vi.fn(), setHeader: vi.fn(), abortSignal: vi.fn() }))
vi.mock('./auth', () => ({ supabase: { auth: { getSession }, rpc } }))
const context = (): DeliveryContext => ({ scope: { projectId: 'project', userId: 'owner' }, signal: new AbortController().signal, isCurrent: () => true })
const snapshot = { tmdb_id: 123, type: 'movie', year: 2027, poster_url: null, venue: null, format: null, seat: null, title: 'Original sent title', showtime: '2027-01-01T18:00:00Z', ends_at: '2027-01-01T20:00:00Z', companions: [] }
beforeEach(() => {
  const request = { setHeader, abortSignal, then: (resolve: (value: unknown) => unknown) => Promise.resolve(send()).then(resolve) }
  rpc.mockReturnValue(request); setHeader.mockReturnValue(request); abortSignal.mockReturnValue(request)
  send.mockReturnValue({ data: snapshot, error: null })
  getSession.mockResolvedValue({ data: { session: { user: { id: 'owner' }, access_token: 'owner-token' } }, error: null })
})
afterEach(() => vi.resetAllMocks())

it('pins the owner token and receipt ID and returns the committed snapshot on retry', async () => {
  const current = context(), ready = vi.fn(async () => {})
  expect(await shareOutingPlans('outing', ['friend'], 'same-operation', current, ready)).toEqual(snapshot)
  expect(await shareOutingPlans('outing', ['friend'], 'same-operation', current, ready)).toEqual(snapshot)
  expect(rpc).toHaveBeenLastCalledWith('share_outing_plans', { p_outing_id: 'outing', p_recipient_ids: ['friend'], p_operation_id: 'same-operation' })
  expect(setHeader).toHaveBeenCalledWith('Authorization', 'Bearer owner-token')
  expect(abortSignal).toHaveBeenCalledWith(current.signal)
  expect(ready).toHaveBeenCalledTimes(2)
})

it('does not send under another owner or after a pending-write guard rejects', async () => {
  await expect(shareOutingPlans('outing', ['friend'], 'op', context(), async () => { throw new Error('Pending edits') })).rejects.toThrow('Pending edits')
  getSession.mockResolvedValue({ data: { session: { user: { id: 'other' }, access_token: 'other' } }, error: null })
  await expect(shareOutingPlans('outing', ['friend'], 'op', context(), async () => {})).rejects.toThrow('account changed')
  expect(send).not.toHaveBeenCalled()
})

it('rejects a successful old-account response and a missing receipt snapshot', async () => {
  let current = true
  send.mockImplementation(() => { current = false; return { data: snapshot, error: null } })
  await expect(shareOutingPlans('outing', ['friend'], 'op', { ...context(), isCurrent: () => current }, async () => {})).rejects.toThrow('account changed')
  send.mockReturnValue({ data: null, error: null })
  await expect(shareOutingPlans('outing', ['friend'], 'op', context(), async () => {})).rejects.toThrow('did not confirm')
})

it('fetches only accepted friends through the checked owner token', async () => {
  send.mockReturnValue({ data: [{ friend_user_id: 'accepted', status: 'accepted' }, { friend_user_id: 'pending', status: 'pending' }], error: null })
  expect(await listOutingShareFriends(context())).toEqual([{ friend_user_id: 'accepted', status: 'accepted' }])
  expect(setHeader).toHaveBeenCalledWith('Authorization', 'Bearer owner-token')
})

it.each([{ venue: { private: 'unexpected object' } }, { tmdb_id: 0 }, { type: 'other' }, { year: null }, { poster_url: [] }, { companions: [{}] }])('rejects malformed receipt fields before displaying them: %j', async (invalid) => {
  send.mockReturnValue({ data: { ...snapshot, ...invalid }, error: null })
  await expect(shareOutingPlans('outing', ['friend'], 'op', context(), async () => {})).rejects.toThrow('did not confirm')
})
