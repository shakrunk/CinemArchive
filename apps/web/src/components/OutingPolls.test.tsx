import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import type { Title } from 'src/store/mockData'
import * as outingPolls from 'src/lib/outingPolls'
import { listFriendships, type FriendshipView } from 'src/lib/auth'
import { OutingPollsButton } from './OutingPolls'

vi.mock('src/lib/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/auth')>()),
  isSupabaseConfigured: true,
  listFriendships: vi.fn(),
}))
vi.mock('src/lib/outingPolls', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/outingPolls')>()),
  listOutingPolls: vi.fn(),
  castPollVotes: vi.fn(),
  closeOutingPoll: vi.fn(),
  createOutingPoll: vi.fn(),
}))

const owner = '10000000-0000-4000-8000-000000000001'
const friendId = '20000000-0000-4000-8000-000000000001'
const poll = '30000000-0000-4000-8000-000000000001'
const optionA = '40000000-0000-4000-8000-000000000001'
const optionB = '40000000-0000-4000-8000-000000000002'
const film = (id: string, tmdbId: number, name: string): Title => ({
  id, tmdbId, type: 'movie', title: name, year: 2026, runtime: 100, genres: [], tags: [], status: 'watchlist', addedAt: '2026-01-01', viewings: [],
})
const titleA = '50000000-0000-4000-8000-000000000001'
const titleB = '50000000-0000-4000-8000-000000000002'

const option = (id: string, position: number, name: string, tmdbId: number, over: Partial<outingPolls.PollOption> = {}): outingPolls.PollOption => ({
  id, position, tmdbId, title: name, year: 2026, posterUrl: null, showtime: '2099-11-02T19:00:00Z', venue: 'AMC', votes: 0, mine: false, voters: [], ...over,
})
const openPoll = (over: Partial<outingPolls.OutingPoll> = {}): outingPolls.OutingPoll => ({
  id: poll, question: 'Friday or Saturday?', status: 'open', isOwner: false, ownerName: 'Fran Friend', createdAt: '2026-10-30T10:00:00Z',
  winnerOptionId: null, members: [], options: [option(optionA, 1, 'Film A', 801, { votes: 1, voters: ['Sam'] }), option(optionB, 2, 'Film B', 802)], ...over,
})

const initialState = useAppStore.getState()
beforeEach(() => useAppStore.setState({
  user: { id: owner, email: 'a@b.test' } as never,
  titles: [film(titleA, 801, 'Film A'), film(titleB, 802, 'Film B')],
}))
afterEach(() => {
  cleanup()
  useAppStore.setState(initialState, true)
  vi.clearAllMocks()
})

async function openPolls() {
  render(<OutingPollsButton />)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Polls' }))
  return user
}

it('lists a poll from a friend and ticks an option, sending the whole set', async () => {
  vi.mocked(outingPolls.listOutingPolls).mockResolvedValue([openPoll()])
  vi.mocked(outingPolls.castPollVotes).mockResolvedValue()
  const user = await openPolls()
  expect(await screen.findByText('From Fran Friend')).toBeInTheDocument()
  expect(screen.getByText('1 can make it: Sam')).toBeInTheDocument()
  await user.click(screen.getByRole('checkbox', { name: 'Works for me: Film B' }))
  expect(outingPolls.castPollVotes).toHaveBeenCalledWith(poll, [optionB])
})

it('unticks an option while keeping the others', async () => {
  vi.mocked(outingPolls.listOutingPolls).mockResolvedValue([openPoll({ options: [option(optionA, 1, 'Film A', 801, { mine: true }), option(optionB, 2, 'Film B', 802, { mine: true })] })])
  vi.mocked(outingPolls.castPollVotes).mockResolvedValue()
  const user = await openPolls()
  await user.click(await screen.findByRole('checkbox', { name: 'Works for me: Film A' }))
  expect(outingPolls.castPollVotes).toHaveBeenCalledWith(poll, [optionB])
})

it('lets the owner pick a winner, which closes the poll', async () => {
  vi.mocked(outingPolls.listOutingPolls).mockResolvedValue([openPoll({ isOwner: true, members: [{ userId: friendId, name: 'Fran' }] })])
  vi.mocked(outingPolls.closeOutingPoll).mockResolvedValue()
  const user = await openPolls()
  expect(await screen.findByText('Your poll · 1 invited')).toBeInTheDocument()
  await user.click(screen.getAllByRole('button', { name: /^Pick Film B/ })[0])
  expect(outingPolls.closeOutingPoll).toHaveBeenCalledWith(poll, optionB)
})

it('turns a closed poll\'s winner into an outing prefilled with the friends who ticked it', async () => {
  const winner = option(optionB, 2, 'Film B', 802, { voters: ['Fran', 'Sam'], votes: 2 })
  vi.mocked(outingPolls.listOutingPolls).mockResolvedValue([openPoll({ isOwner: true, status: 'closed', winnerOptionId: optionB, options: [option(optionA, 1, 'Film A', 801), winner] })])
  const user = await openPolls()
  await user.click(await screen.findByRole('button', { name: 'Schedule this' }))
  const state = useAppStore.getState()
  expect(state.isOutingScheduleOpen).toBe(true)
  expect(state.outingScheduleTitleId).toBe(titleB)
  expect(state.outingSchedulePrefill).toMatchObject({ showtime: winner.showtime, venue: 'AMC', companions: ['Fran', 'Sam'] })
})

it('starts a poll from the library and sends it to the ticked friends', async () => {
  vi.mocked(outingPolls.listOutingPolls).mockResolvedValue([])
  vi.mocked(outingPolls.createOutingPoll).mockResolvedValue(poll)
  vi.mocked(listFriendships).mockResolvedValue([{ friend_user_id: friendId, display_name: 'Fran Friend', username: 'fran', status: 'accepted' } as FriendshipView])
  const user = await openPolls()
  await user.click(await screen.findByRole('button', { name: 'New poll' }))
  const send = screen.getByRole('button', { name: 'Send poll' })
  expect(send).toBeDisabled()
  for (const [index, [titleId, date]] of [[titleA, '2099-11-02'], [titleB, '2099-11-03']].entries()) {
    await user.selectOptions(screen.getByLabelText(`Movie for option ${index + 1}`), titleId)
    await user.type(screen.getByLabelText(`Date for option ${index + 1}`), date)
  }
  await user.click(await screen.findByRole('checkbox', { name: 'Ask Fran Friend' }))
  expect(send).toBeEnabled()
  await user.click(send)
  const [question, candidates, friends] = vi.mocked(outingPolls.createOutingPoll).mock.calls[0]
  expect(question).toBe('')
  expect(candidates.map((c) => c.titleId)).toEqual([titleA, titleB])
  expect(friends).toEqual([friendId])
})

it('shows a retry-able error when polls cannot be loaded', async () => {
  vi.mocked(outingPolls.listOutingPolls).mockRejectedValue(new Error('offline'))
  await openPolls()
  expect(await screen.findByRole('alert')).toHaveTextContent('offline')
})

it('offers no polls entry without an account', () => {
  useAppStore.setState({ user: null })
  const { container } = render(<OutingPollsButton />)
  expect(container).toBeEmptyDOMElement()
})
