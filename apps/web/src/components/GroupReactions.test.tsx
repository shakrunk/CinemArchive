import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import * as sharedOutings from 'src/lib/sharedOutings'
import { GroupReactions, OwnerGroupReactions } from './GroupReactions'
import { SharedWithYou } from './SharedWithYou'

vi.mock('src/lib/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/auth')>()),
  isSupabaseConfigured: true,
}))
vi.mock('src/lib/sharedOutings', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/sharedOutings')>()),
  listSharedOutings: vi.fn(),
  outingReactions: vi.fn(),
  shareOutingReaction: vi.fn(),
  withdrawOutingReaction: vi.fn(),
  sharedOutingIdFor: vi.fn(),
}))

const owner = '10000000-0000-4000-8000-000000000001'
const friendId = '20000000-0000-4000-8000-000000000001'
const shared = '30000000-0000-4000-8000-000000000001'
const outing = '40000000-0000-4000-8000-000000000001'

const reaction = (overrides: Partial<sharedOutings.OutingReaction> = {}): sharedOutings.OutingReaction => ({
  userId: friendId, name: 'Fran Friend', isMe: false, rating: 4.5, note: 'Loved it', updatedAt: '2026-11-01T22:00:00Z', ...overrides,
})

const initialState = useAppStore.getState()
beforeEach(() => useAppStore.setState({ user: { id: owner, email: 'a@b.test' } as never }))
afterEach(() => {
  cleanup()
  useAppStore.setState(initialState, true)
  vi.clearAllMocks()
})

it('shows what others shared and shares the caller\'s own, separate from the private prefill', async () => {
  vi.mocked(sharedOutings.outingReactions).mockResolvedValueOnce([reaction()])
    .mockResolvedValueOnce([reaction(), reaction({ userId: owner, name: 'You', isMe: true, rating: 4, note: 'Mine' })])
  vi.mocked(sharedOutings.shareOutingReaction).mockResolvedValue()
  render(<GroupReactions sharedOutingId={shared} initialRating={4} initialNote="Private words" />)
  expect(await screen.findByText(/Fran Friend/)).toBeInTheDocument()
  expect(screen.getByText('“Loved it”')).toBeInTheDocument()
  const note = screen.getByLabelText('A note for the group')
  expect(note).toHaveValue('Private words')
  const user = userEvent.setup()
  await user.clear(note)
  await user.type(note, 'Mine')
  await user.click(screen.getByRole('button', { name: 'Share with the group' }))
  expect(sharedOutings.shareOutingReaction).toHaveBeenCalledWith(shared, 4, 'Mine')
  expect(await screen.findByRole('button', { name: 'Update' })).toBeInTheDocument()
})

it('nothing is shared until the button is pressed', async () => {
  vi.mocked(sharedOutings.outingReactions).mockResolvedValue([])
  render(<GroupReactions sharedOutingId={shared} initialRating={5} initialNote="Private" />)
  expect(await screen.findByText('Nobody else has shared yet.')).toBeInTheDocument()
  expect(sharedOutings.shareOutingReaction).not.toHaveBeenCalled()
})

it('cannot share an empty reaction', async () => {
  vi.mocked(sharedOutings.outingReactions).mockResolvedValue([])
  render(<GroupReactions sharedOutingId={shared} />)
  expect(await screen.findByRole('button', { name: 'Share with the group' })).toBeDisabled()
})

it('takes the caller\'s reaction back', async () => {
  vi.mocked(sharedOutings.outingReactions).mockResolvedValueOnce([reaction({ userId: owner, isMe: true, name: 'You' })]).mockResolvedValueOnce([])
  vi.mocked(sharedOutings.withdrawOutingReaction).mockResolvedValue()
  render(<GroupReactions sharedOutingId={shared} />)
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Take mine back' }))
  expect(sharedOutings.withdrawOutingReaction).toHaveBeenCalledWith(shared)
  expect(await screen.findByRole('button', { name: 'Share with the group' })).toBeInTheDocument()
})

it('shows a short refusal from the server', async () => {
  vi.mocked(sharedOutings.outingReactions).mockResolvedValue([])
  vi.mocked(sharedOutings.shareOutingReaction).mockRejectedValue(new Error('Reactions open once the show has ended'))
  render(<GroupReactions sharedOutingId={shared} initialNote="Words" />)
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Share with the group' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Reactions open once the show has ended')
})

it('the owner\'s panel appears only for an outing that was shared', async () => {
  vi.mocked(sharedOutings.sharedOutingIdFor).mockResolvedValueOnce(shared).mockResolvedValueOnce(null)
  vi.mocked(sharedOutings.outingReactions).mockResolvedValue([])
  const { container, rerender } = render(<OwnerGroupReactions outingId={outing} />)
  expect(await screen.findByRole('region', { name: 'Group reactions' })).toBeInTheDocument()
  rerender(<OwnerGroupReactions outingId="50000000-0000-4000-8000-000000000001" />)
  await vi.waitFor(() => expect(container).toBeEmptyDOMElement())
})

it('the owner\'s panel renders nothing without an account', () => {
  useAppStore.setState({ user: null })
  const { container } = render(<OwnerGroupReactions outingId={outing} />)
  expect(container).toBeEmptyDOMElement()
  expect(sharedOutings.sharedOutingIdFor).not.toHaveBeenCalled()
})

const ended = (overrides: Partial<sharedOutings.SharedOuting> = {}): sharedOutings.SharedOuting => ({
  id: shared, status: 'scheduled', myStatus: 'accepted', ownerId: friendId, ownerName: 'Fran Friend',
  showtime: '2020-11-01T19:00:00Z', endsAt: '2020-11-01T21:00:00Z', title: 'Shared film', year: 2026,
  posterUrl: null, venue: 'AMC', format: null, companions: [], ...overrides,
})

it('a member\'s card offers reactions once the show has ended, but not before or when cancelled', async () => {
  vi.mocked(sharedOutings.outingReactions).mockResolvedValue([])
  vi.mocked(sharedOutings.listSharedOutings).mockResolvedValue([
    ended(),
    ended({ id: '60000000-0000-4000-8000-000000000001', title: 'Upcoming', showtime: '2099-11-01T19:00:00Z', endsAt: '2099-11-01T21:00:00Z' }),
    ended({ id: '70000000-0000-4000-8000-000000000001', title: 'Called off', status: 'cancelled' }),
  ])
  render(<SharedWithYou />)
  const toggles = await screen.findAllByRole('button', { name: 'How was it?' })
  expect(toggles).toHaveLength(1)
  await userEvent.setup().click(toggles[0])
  expect(await screen.findByRole('region', { name: 'Group reactions' })).toBeInTheDocument()
  expect(sharedOutings.outingReactions).toHaveBeenCalledWith(shared)
})
