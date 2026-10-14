import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { User } from '@supabase/supabase-js'
import { ShareOutingPanel } from './ShareOutingPanel'
import { useAppStore } from '../store/useAppStore'
import { listOutingShareFriends } from '../lib/db'
import { deferred, outing, title } from '../lib/offline/fixtures.test-support'
import type { FriendshipView } from '../lib/auth'
import type { SharedOutingSnapshot } from '../lib/outingSharing'

vi.mock('../lib/db', async (original) => ({ ...await original<typeof import('../lib/db')>(), listOutingShareFriends: vi.fn() }))
const initial = useAppStore.getState()
const upcoming = { ...outing, showtime: '2099-01-01T18:00:00Z', endsAt: '2099-01-01T20:00:00Z' }
const friend = { friend_user_id: 'friend-a', status: 'accepted', display_name: 'Alice' } as FriendshipView
const confirmation = { title: 'Actually sent title', showtime: upcoming.showtime, ends_at: upcoming.endsAt, companions: [], venue: 'Original cinema' } as unknown as SharedOutingSnapshot
const share = vi.fn(), notify = vi.fn()
beforeEach(() => {
  useAppStore.setState({ user: { id: 'owner-a' } as User, librarySession: 1, isSharedView: false, viewerContext: { kind: 'owner' }, titles: [title], outings: [upcoming], shareOutingPlans: share, pushNotification: notify })
  vi.mocked(listOutingShareFriends).mockResolvedValue([friend])
})
afterEach(() => { cleanup(); useAppStore.setState(initial, true); vi.resetAllMocks() })
const open = () => render(<ShareOutingPanel outing={upcoming} title={title} onClose={() => {}} />)

it('keeps one operation ID across unknown outcomes and makes Share again an explicit new send', async () => {
  const response = deferred<SharedOutingSnapshot>()
  share.mockReturnValueOnce(response.promise).mockResolvedValue(confirmation)
  open()
  const button = await screen.findByRole('button', { name: /Alice/ })
  fireEvent.click(button); fireEvent.click(button)
  expect(share).toHaveBeenCalledTimes(1)
  const operationId = share.mock.calls[0][2]
  await act(async () => response.reject(new Error('Response lost')))
  expect(screen.getByText('Not confirmed — retry same send')).toBeInTheDocument()
  fireEvent.click(button)
  await screen.findByText('Share again')
  expect(share.mock.calls[1][2]).toBe(operationId)
  expect(screen.getByText(/Shared Actually sent title/)).toHaveTextContent('Original cinema')
  fireEvent.click(button)
  await waitFor(() => expect(share).toHaveBeenCalledTimes(3))
  expect(share.mock.calls[2][2]).not.toBe(operationId)
})

it('never publishes a late friend list or send notification into the next account', async () => {
  const response = deferred<SharedOutingSnapshot>()
  share.mockReturnValue(response.promise)
  open()
  fireEvent.click(await screen.findByRole('button', { name: /Alice/ }))
  const nextFriends = deferred<FriendshipView[]>()
  vi.mocked(listOutingShareFriends).mockReturnValue(nextFriends.promise)
  act(() => useAppStore.setState({ user: { id: 'owner-b' } as User, librarySession: 2 }))
  expect(screen.queryByText('Alice')).not.toBeInTheDocument()
  await act(async () => response.resolve(confirmation))
  expect(notify).not.toHaveBeenCalled()
  await waitFor(() => expect(listOutingShareFriends).toHaveBeenCalledTimes(2))
  act(() => useAppStore.setState({ user: { id: 'owner-c' } as User, librarySession: 3, outings: [], titles: [] }))
  await act(async () => nextFriends.resolve([friend]))
  expect(screen.queryByText('Alice')).not.toBeInTheDocument()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('uses current owner data rather than stale props and disables ended or cancelled outings', async () => {
  useAppStore.setState({ outings: [{ ...upcoming, venue: 'Updated cinema', status: 'cancelled' }] })
  open()
  expect(screen.getByText(/Updated cinema/)).toBeInTheDocument()
  const button = await screen.findByRole('button', { name: /Alice/ })
  expect(button).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Copy / share text' })).toBeDisabled()
  fireEvent.click(button)
  expect(share).not.toHaveBeenCalled()
})
