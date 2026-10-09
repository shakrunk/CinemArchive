import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { completeDueOutings } from './db'
import type { DeliveryContext } from './offline/coordinator'

const { getSession, rpc, send, setHeader, abortSignal } = vi.hoisted(() => ({
  getSession: vi.fn(), rpc: vi.fn(), send: vi.fn(), setHeader: vi.fn(), abortSignal: vi.fn(),
}))
vi.mock('./auth', () => ({ supabase: { auth: { getSession }, rpc } }))
const context = (): DeliveryContext => ({ scope: { projectId: 'project', userId: 'owner' },
  signal: new AbortController().signal, isCurrent: () => true })
beforeEach(() => {
  const request = { setHeader, abortSignal, then: (resolve: (value: unknown) => unknown) => Promise.resolve(send()).then(resolve) }
  rpc.mockReturnValue(request)
  setHeader.mockReturnValue(request)
  abortSignal.mockReturnValue(request)
  send.mockReturnValue({ data: [], error: null })
  getSession.mockResolvedValue({ data: { session: { user: { id: 'owner' }, access_token: 'test-owner-token' } }, error: null })
})
afterEach(() => vi.resetAllMocks())

it('pins the checked owner session and cancellation signal on automatic completion', async () => {
  const current = context()
  await completeDueOutings('America/Denver', current)
  expect(rpc).toHaveBeenCalledWith('complete_due_outings', { p_tz: 'America/Denver' })
  expect(setHeader).toHaveBeenCalledWith('Authorization', 'Bearer test-owner-token')
  expect(abortSignal).toHaveBeenCalledWith(current.signal)
  expect(send).toHaveBeenCalledOnce()
})

it('does not send completion with another account session', async () => {
  getSession.mockResolvedValue({ data: { session: { user: { id: 'other' }, access_token: 'other' } }, error: null })
  await expect(completeDueOutings('UTC', context())).rejects.toThrow('owner changed')
  expect(send).not.toHaveBeenCalled()
})

it('does not send after leaving the owner view while authentication is checked', async () => {
  await expect(completeDueOutings('UTC', { ...context(), isCurrent: () => false })).rejects.toThrow('owner changed')
  expect(send).not.toHaveBeenCalled()
})
