import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  canVote, castPollVotes, closeOutingPoll, createOutingPoll, listOutingPolls, parseOutingPolls, pollLeaders, pollProblem,
  type OutingPoll, type PollCandidate,
} from './outingPolls'

const poll = '30000000-0000-4000-8000-000000000001'
const optionA = '40000000-0000-4000-8000-000000000001'
const optionB = '40000000-0000-4000-8000-000000000002'
const friend = '20000000-0000-4000-8000-000000000001'
const titleA = '50000000-0000-4000-8000-000000000001'
const titleB = '50000000-0000-4000-8000-000000000002'

const { client } = vi.hoisted(() => ({ client: { rpc: vi.fn() } }))
vi.mock('./auth', () => ({ isSupabaseConfigured: true, supabase: client }))

const row = (overrides: Record<string, unknown> = {}) => ({
  id: poll, question: 'Friday or Saturday?', status: 'open', is_owner: false, owner_name: 'Olive Owner',
  created_at: '2026-10-30T10:00:00+00:00', closed_at: null, winner_option_id: null,
  members: [{ user_id: friend, name: 'Fran' }],
  options: [
    { id: optionA, position: 1, tmdb_id: 1, title: 'Film A', year: 2026, poster_url: null, showtime: '2026-11-02T19:00:00+00:00', venue: 'AMC', votes: 2, mine: true, voters: ['Fran', 'Sam'] },
    { id: optionB, position: 2, tmdb_id: 2, title: 'Film B', year: null, poster_url: null, showtime: '2026-11-03T19:00:00+00:00', venue: null, votes: 0, mine: false, voters: [] },
  ],
  ...overrides,
})
const parsed = (overrides: Record<string, unknown> = {}): OutingPoll => parseOutingPolls([row(overrides)])[0]
const now = Date.parse('2026-11-01T12:00:00Z')
const candidates: PollCandidate[] = [
  { titleId: titleA, showtime: '2099-11-02T19:00:00Z', venue: ' AMC ' },
  { titleId: titleB, showtime: '2099-11-03T19:00:00Z', venue: null },
]

// The same cases as Android's PollRulesTest and OutingPollsRepositoryTest.
describe('poll rules', () => {
  it('takes votes while open and an option is still ahead', () => {
    expect(canVote(parsed(), now)).toBe(true)
    expect(canVote(parsed({ status: 'closed' }), now)).toBe(false)
    expect(canVote(parsed({ status: 'cancelled' }), now)).toBe(false)
    expect(canVote(parsed(), Date.parse('2026-11-04T00:00:00Z'))).toBe(false)
  })

  it('leaders are the most ticked upcoming options, and none without votes', () => {
    const tied = parsed({ options: [
      { ...row().options[0], votes: 2 }, { ...row().options[1], votes: 2 },
      { ...row().options[0], id: '40000000-0000-4000-8000-000000000003', showtime: '2026-10-31T19:00:00+00:00', votes: 9 },
    ] })
    expect(pollLeaders(tied, now).map((o) => o.id)).toEqual([optionA, optionB])
    expect(pollLeaders(parsed({ options: [{ ...row().options[0], votes: 0 }] }), now)).toEqual([])
  })

  it('names the problem before anything is sent', () => {
    const [a, b] = candidates
    expect(pollProblem('q', candidates, 2, Date.now())).toBeNull()
    expect(pollProblem('x'.repeat(101), candidates, 1, Date.now())).toContain('question')
    expect(pollProblem('q', [a], 1, Date.now())).toContain('options')
    expect(pollProblem('q', [a, b, a, b, a], 1, Date.now())).toContain('options')
    expect(pollProblem('q', [a, { ...b, showtime: '2020-01-01T00:00:00Z' }], 1, Date.now())).toContain('future')
    expect(pollProblem('q', [a, a], 1, Date.now())).toContain('same')
    expect(pollProblem('q', [a, { ...b, venue: 'v'.repeat(101) }], 1, Date.now())).toContain('venue')
    expect(pollProblem('q', candidates, 0, Date.now())).toContain('friends')
    expect(pollProblem('q', candidates, 9, Date.now())).toContain('friends')
  })
})

describe('poll parsing', () => {
  it('reads the tallies, voters and members', () => {
    const p = parsed()
    expect(p.status).toBe('open')
    expect(p.ownerName).toBe('Olive Owner')
    expect(p.options.map((o) => o.votes)).toEqual([2, 0])
    expect(p.options[0].voters).toEqual(['Fran', 'Sam'])
    expect(p.options[0].mine).toBe(true)
    expect(p.options[1].year).toBeNull()
    expect(p.members.map((m) => m.name)).toEqual(['Fran'])
  })

  it('reads a winner and skips malformed polls', () => {
    expect(parsed({ status: 'closed', winner_option_id: optionB }).winnerOptionId).toBe(optionB)
    expect(parseOutingPolls([{ id: 'x' }, row({ status: 'bogus', id: '60000000-0000-4000-8000-000000000001' }), null, row({ options: [] })])).toEqual([])
    expect(parseOutingPolls({ not: 'a list' })).toEqual([])
  })
})

describe('poll requests', () => {
  beforeEach(() => client.rpc.mockReset())

  it('lists polls', async () => {
    client.rpc.mockResolvedValueOnce({ data: [row()], error: null })
    expect((await listOutingPolls()).map((p) => p.id)).toEqual([poll])
    expect(client.rpc).toHaveBeenCalledWith('get_outing_polls')
  })

  it('creates from the owner\'s titles with trimmed fields and unique friends, and returns the id', async () => {
    client.rpc.mockResolvedValueOnce({ data: { poll_id: poll }, error: null })
    expect(await createOutingPoll('  Friday?  ', candidates, [friend, friend])).toBe(poll)
    const [name, args] = client.rpc.mock.calls[0]
    expect(name).toBe('create_outing_poll')
    expect(args.p_question).toBe('Friday?')
    expect(args.p_friend_ids).toEqual([friend])
    expect(args.p_options[0]).toEqual({ title_id: titleA, showtime: '2099-11-02T19:00:00.000Z', venue: 'AMC' })
    expect(args.p_options[1].venue).toBeNull()
  })

  it('refuses a poll that breaks a rule before any request', async () => {
    await expect(createOutingPoll('q', candidates.slice(0, 1), [friend])).rejects.toThrow('options')
    await expect(createOutingPoll('q', candidates, [])).rejects.toThrow('friends')
    await expect(createOutingPoll('q', candidates.map((c) => ({ ...c, titleId: 'nope' })), [friend])).rejects.toThrow('Invalid id')
    expect(client.rpc).not.toHaveBeenCalled()
  })

  it('votes replace the caller\'s ticks and an empty list clears them', async () => {
    client.rpc.mockResolvedValue({ data: null, error: null })
    await castPollVotes(poll, [optionA, optionB, optionA])
    expect(client.rpc).toHaveBeenLastCalledWith('cast_poll_votes', { p_poll_id: poll, p_option_ids: [optionA, optionB] })
    await castPollVotes(poll, [])
    expect(client.rpc).toHaveBeenLastCalledWith('cast_poll_votes', { p_poll_id: poll, p_option_ids: [] })
  })

  it('closes on a winner or cancels, and surfaces a short server refusal', async () => {
    client.rpc.mockResolvedValue({ data: {}, error: null })
    await closeOutingPoll(poll, optionB)
    expect(client.rpc).toHaveBeenLastCalledWith('close_outing_poll', { p_poll_id: poll, p_option_id: optionB })
    await closeOutingPoll(poll, null)
    expect(client.rpc).toHaveBeenLastCalledWith('close_outing_poll', { p_poll_id: poll, p_option_id: null })
    client.rpc.mockResolvedValueOnce({ data: null, error: { message: 'This poll is already closed' } })
    await expect(closeOutingPoll(poll, optionA)).rejects.toThrow('This poll is already closed')
  })

  it('rejects malformed ids before any request', async () => {
    client.rpc.mockReset()
    await expect(castPollVotes('nope', [])).rejects.toThrow('Invalid id')
    await expect(castPollVotes(poll, ['1&x'])).rejects.toThrow('Invalid id')
    await expect(closeOutingPoll(poll, 'nope')).rejects.toThrow('Invalid id')
    expect(client.rpc).not.toHaveBeenCalled()
  })
})
