import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import * as sharedOutings from 'src/lib/sharedOutings'
import { OutingInvites } from './OutingInvites'
import { SharedWithYou } from './SharedWithYou'
import type { FriendshipView } from 'src/lib/auth'

vi.mock('src/lib/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/auth')>()),
  isSupabaseConfigured: true,
}))
vi.mock('src/lib/sharedOutings', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/sharedOutings')>()),
  listSharedOutings: vi.fn(),
  respondToInvite: vi.fn(),
  outingMembers: vi.fn(),
  inviteFriends: vi.fn(),
  removeOutingMember: vi.fn(),
}))

const owner = '10000000-0000-4000-8000-000000000001'
const friendId = '20000000-0000-4000-8000-000000000001'
const shared = '30000000-0000-4000-8000-000000000001'
const outing = '40000000-0000-4000-8000-000000000001'

const upcoming = (overrides: Partial<sharedOutings.SharedOuting> = {}): sharedOutings.SharedOuting => ({
  id: shared, status: 'scheduled', myStatus: 'invited', ownerId: friendId, ownerName: 'Fran Friend',
  showtime: '2099-11-01T19:00:00Z', endsAt: '2099-11-01T21:00:00Z', title: 'Shared film', year: 2026,
  posterUrl: null, venue: 'AMC', format: 'IMAX', companions: ['Sam'], ...overrides,
})

const initialState = useAppStore.getState()
beforeEach(() => useAppStore.setState({ user: { id: owner, email: 'a@b.test' } as never }))
afterEach(() => {
  cleanup()
  useAppStore.setState(initialState, true)
  vi.clearAllMocks()
})

it('shows an invitation and accepts it, then refreshes the list', async () => {
  vi.mocked(sharedOutings.listSharedOutings).mockResolvedValueOnce([upcoming()])
    .mockResolvedValueOnce([upcoming({ myStatus: 'accepted' })])
  vi.mocked(sharedOutings.respondToInvite).mockResolvedValue()
  render(<SharedWithYou />)
  expect(await screen.findByText('Fran Friend invited you')).toBeInTheDocument()
  expect(screen.getByText('With Sam')).toBeInTheDocument()
  await userEvent.setup().click(screen.getByRole('button', { name: 'Accept invitation to Shared film' }))
  expect(sharedOutings.respondToInvite).toHaveBeenCalledWith(shared, true)
  expect(await screen.findByText("You're going with Fran Friend")).toBeInTheDocument()
})

it('declines an invitation', async () => {
  vi.mocked(sharedOutings.listSharedOutings).mockResolvedValueOnce([upcoming()]).mockResolvedValueOnce([])
  vi.mocked(sharedOutings.respondToInvite).mockResolvedValue()
  render(<SharedWithYou />)
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Decline invitation to Shared film' }))
  expect(sharedOutings.respondToInvite).toHaveBeenCalledWith(shared, false)
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Shared with you' })).not.toBeInTheDocument())
})

it('shows a cancelled outing as cancelled with no way to answer it', async () => {
  vi.mocked(sharedOutings.listSharedOutings).mockResolvedValue([upcoming({ myStatus: 'accepted', status: 'cancelled' })])
  render(<SharedWithYou />)
  expect(await screen.findByText('Cancelled by Fran Friend')).toBeInTheDocument()
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
})

it('shows an error without hiding the section when the answer fails', async () => {
  vi.mocked(sharedOutings.listSharedOutings).mockResolvedValue([upcoming()])
  vi.mocked(sharedOutings.respondToInvite).mockRejectedValue(new Error('This outing is no longer open'))
  render(<SharedWithYou />)
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Accept invitation to Shared film' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('This outing is no longer open')
  expect(screen.getByRole('button', { name: 'Accept invitation to Shared film' })).toBeEnabled()
})

it('renders nothing without an account or when there is nothing to show', async () => {
  vi.mocked(sharedOutings.listSharedOutings).mockResolvedValue([])
  const { container, rerender } = render(<SharedWithYou />)
  expect(container).toBeEmptyDOMElement()
  useAppStore.setState({ user: null })
  rerender(<SharedWithYou />)
  expect(container).toBeEmptyDOMElement()
  expect(sharedOutings.listSharedOutings).toHaveBeenCalledTimes(1)
})

const friend: FriendshipView = {
  friend_user_id: friendId, display_name: 'Fran Friend', username: 'fran', status: 'accepted',
} as FriendshipView
const member = (status: sharedOutings.OutingMember['status']): sharedOutings.OutingMember => ({ sharedOutingId: shared, userId: friendId, name: 'Fran Friend', status })

it('invites a friend to an outing and shows their answer', async () => {
  vi.mocked(sharedOutings.outingMembers).mockResolvedValue([])
  vi.mocked(sharedOutings.inviteFriends).mockResolvedValue([member('invited')])
  render(<OutingInvites outingId={outing} friends={[friend]} enabled />)
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Invite Fran Friend to this outing' }))
  expect(sharedOutings.inviteFriends).toHaveBeenCalledWith(outing, [friendId])
  expect(await screen.findByText('Invited · waiting for an answer')).toBeInTheDocument()
})

it('takes an invitation back, and invites a friend who declined again', async () => {
  vi.mocked(sharedOutings.outingMembers).mockResolvedValueOnce([member('accepted')]).mockResolvedValueOnce([member('declined')])
  vi.mocked(sharedOutings.removeOutingMember).mockResolvedValue()
  render(<OutingInvites outingId={outing} friends={[friend]} enabled />)
  expect(await screen.findByText('Going')).toBeInTheDocument()
  await userEvent.setup().click(screen.getByRole('button', { name: 'Remove Fran Friend from this outing' }))
  expect(sharedOutings.removeOutingMember).toHaveBeenCalledWith(shared, friendId)
  expect(await screen.findByRole('button', { name: 'Invite Fran Friend to this outing' })).toHaveTextContent('Invite again')
})

it('cannot invite when the outing is not upcoming', async () => {
  vi.mocked(sharedOutings.outingMembers).mockResolvedValue([])
  render(<OutingInvites outingId={outing} friends={[friend]} enabled={false} />)
  expect(await screen.findByRole('button', { name: 'Invite Fran Friend to this outing' })).toBeDisabled()
})
