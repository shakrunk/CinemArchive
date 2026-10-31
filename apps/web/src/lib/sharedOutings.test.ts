import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  canRespond, canShareReaction, cleanReactionNote, goingOf, inviteFriends, invitationsOf, isValidReactionRating, listSharedOutings,
  MAX_REACTION_NOTE, outingMembers, outingReactions, parseOutingMembers, parseOutingReactions, parseSharedOutings, reactionsOpen,
  removeOutingMember, respondToInvite, sharedOutingIdFor, shareOutingReaction, withdrawOutingReaction, type SharedOuting,
} from './sharedOutings'

const shared = '30000000-0000-4000-8000-000000000001'
const friend = '20000000-0000-4000-8000-000000000001'
const outing = '40000000-0000-4000-8000-000000000001'

const { client } = vi.hoisted(() => ({ client: { rpc: vi.fn() } }))
vi.mock('./auth', () => ({ isSupabaseConfigured: true, supabase: client }))

const row = (overrides: Record<string, unknown> = {}) => ({
  id: shared, status: 'scheduled', my_status: 'invited', owner_id: friend, owner_name: 'Fran Friend',
  showtime: '2099-11-01T19:00:00+00:00', ends_at: '2099-11-01T21:00:00+00:00',
  snapshot: { tmdb_id: 1, type: 'movie', title: 'Shared film', year: 2026, poster_url: null, venue: 'AMC', format: 'IMAX', companions: ['Sam', 'Pat'] },
  ...overrides,
})
const listed = (overrides: Partial<SharedOuting> = {}): SharedOuting => ({ ...parseSharedOutings([row()])[0], ...overrides })

// The same cases as Android's SharedOutingRulesTest and SharedOutingsRepositoryTest.
describe('shared outing rules', () => {
  const now = Date.parse('2026-11-01T12:00:00Z')

  it('an invitation can be answered only while scheduled and not ended', () => {
    expect(canRespond(listed({ showtime: '2026-11-02T19:00:00Z', endsAt: '2026-11-02T21:00:00Z' }), now)).toBe(true)
    expect(canRespond(listed({ status: 'cancelled', endsAt: '2026-11-02T21:00:00Z' }), now)).toBe(false)
    expect(canRespond(listed({ status: 'completed', endsAt: '2026-11-02T21:00:00Z' }), now)).toBe(false)
    expect(canRespond(listed({ endsAt: '2026-11-01T11:59:59Z' }), now)).toBe(false)
  })

  it('lists invitations soonest first and drops stale ones', () => {
    const list = [
      listed({ id: 'late', showtime: '2026-11-05T19:00:00Z', endsAt: '2026-11-05T21:00:00Z' }),
      listed({ id: 'soon', showtime: '2026-11-02T19:00:00Z', endsAt: '2026-11-02T21:00:00Z' }),
      listed({ id: 'cancelled', status: 'cancelled', endsAt: '2026-11-02T21:00:00Z' }),
      listed({ id: 'going', myStatus: 'accepted', endsAt: '2026-11-02T21:00:00Z' }),
    ]
    expect(invitationsOf(list, now).map((o) => o.id)).toEqual(['soon', 'late'])
  })

  it('going keeps a cancelled outing so the change is not silent', () => {
    const list = [
      listed({ id: 'b', myStatus: 'accepted', showtime: '2026-11-05T19:00:00Z' }),
      listed({ id: 'a', myStatus: 'accepted', status: 'cancelled', showtime: '2026-11-02T19:00:00Z' }),
      listed({ id: 'pending' }),
    ]
    expect(goingOf(list).map((o) => o.id)).toEqual(['a', 'b'])
  })
})

describe('shared outing parsing', () => {
  it('reads the minimal snapshot', () => {
    expect(parseSharedOutings([row()])[0]).toMatchObject({
      id: shared, status: 'scheduled', myStatus: 'invited', ownerName: 'Fran Friend', title: 'Shared film', year: 2026,
      venue: 'AMC', format: 'IMAX', companions: ['Sam', 'Pat'], posterUrl: null,
    })
  })

  it('skips rows with an unexpected shape or a declined membership', () => {
    const other = '50000000-0000-4000-8000-000000000001'
    const rows = [row(), { id: 'x' }, row({ id: other, my_status: 'declined' }), row({ status: 'bogus', id: '60000000-0000-4000-8000-000000000001' }), null]
    expect(parseSharedOutings(rows).map((o) => o.id)).toEqual([shared])
    expect(parseSharedOutings({ not: 'a list' })).toEqual([])
  })

  it('reads the owner\'s member list and skips malformed members', () => {
    const rows = [
      { shared_outing_id: shared, user_id: friend, name: 'Fran Friend', status: 'accepted' },
      { shared_outing_id: shared, user_id: 'nope', name: 'x', status: 'accepted' },
      { shared_outing_id: shared, user_id: friend, name: 'x', status: 'bogus' },
    ]
    expect(parseOutingMembers(rows)).toEqual([{ sharedOutingId: shared, userId: friend, name: 'Fran Friend', status: 'accepted' }])
  })
})

describe('shared outing requests', () => {
  beforeEach(() => client.rpc.mockReset())

  it('lists, and reads members for the owner', async () => {
    client.rpc.mockResolvedValueOnce({ data: [row()], error: null })
    expect((await listSharedOutings()).map((o) => o.id)).toEqual([shared])
    expect(client.rpc).toHaveBeenCalledWith('get_shared_outings')
    client.rpc.mockResolvedValueOnce({ data: [{ shared_outing_id: shared, user_id: friend, name: 'Fran', status: 'invited' }], error: null })
    expect((await outingMembers(outing)).map((m) => m.status)).toEqual(['invited'])
    expect(client.rpc).toHaveBeenLastCalledWith('get_outing_members', { p_outing_id: outing })
  })

  it('invites, then reads the members back', async () => {
    client.rpc.mockResolvedValueOnce({ data: {}, error: null })
      .mockResolvedValueOnce({ data: [{ shared_outing_id: shared, user_id: friend, name: 'Fran', status: 'invited' }], error: null })
    const members = await inviteFriends(outing, [friend])
    expect(client.rpc).toHaveBeenNthCalledWith(1, 'invite_friends_to_outing', { p_outing_id: outing, p_friend_ids: [friend] })
    expect(members).toHaveLength(1)
  })

  it('responds and removes with the right arguments, and surfaces a short server message', async () => {
    client.rpc.mockResolvedValue({ data: {}, error: null })
    await respondToInvite(shared, true)
    expect(client.rpc).toHaveBeenLastCalledWith('respond_to_outing_invite', { p_shared_outing_id: shared, p_accept: true })
    await removeOutingMember(shared, friend)
    expect(client.rpc).toHaveBeenLastCalledWith('remove_outing_member', { p_shared_outing_id: shared, p_user_id: friend })
    client.rpc.mockResolvedValueOnce({ data: null, error: { message: 'This outing is no longer open' } })
    await expect(respondToInvite(shared, true)).rejects.toThrow('This outing is no longer open')
    client.rpc.mockResolvedValueOnce({ data: null, error: { message: 'x'.repeat(500) } })
    await expect(respondToInvite(shared, true)).rejects.toThrow('Could not send your answer.')
  })

  it('rejects empty invites and malformed ids before any request', async () => {
    client.rpc.mockReset()
    await expect(inviteFriends(outing, [])).rejects.toThrow('Choose at least one friend.')
    await expect(inviteFriends('1&x', [friend])).rejects.toThrow('Choose at least one friend.')
    await expect(inviteFriends(outing, ['not-a-uuid'])).rejects.toThrow('Choose at least one friend.')
    await expect(respondToInvite('nope', true)).rejects.toThrow('Invalid outing')
    await expect(outingMembers('nope')).rejects.toThrow('Invalid outing')
    await expect(removeOutingMember(shared, 'nope')).rejects.toThrow('Invalid id')
    expect(client.rpc).not.toHaveBeenCalled()
  })
})

// Group post-show reactions (#207): the same cases as Android's ReactionRulesTest and SharedOutingsRepositoryTest.
describe('reaction rules', () => {
  const now = Date.parse('2026-11-01T12:00:00Z')

  it('opens after the show and stays closed when cancelled', () => {
    expect(reactionsOpen({ status: 'scheduled', endsAt: '2026-11-01T12:00:01Z' }, now)).toBe(false)
    expect(reactionsOpen({ status: 'scheduled', endsAt: '2026-11-01T12:00:00Z' }, now)).toBe(true)
    expect(reactionsOpen({ status: 'completed', endsAt: '2026-11-02T12:00:00Z' }, now)).toBe(true)
    expect(reactionsOpen({ status: 'cancelled', endsAt: '2026-10-01T12:00:00Z' }, now)).toBe(false)
  })

  it('ratings are half stars from 0.5 to 5', () => {
    for (const ok of [0.5, 1, 3.5, 5]) expect(isValidReactionRating(ok)).toBe(true)
    for (const bad of [0, 0.4, 2.3, 5.5, -1]) expect(isValidReactionRating(bad)).toBe(false)
  })

  it('a reaction needs a rating, a note within the limit, or both', () => {
    expect(canShareReaction(4, null)).toBe(true)
    expect(canShareReaction(null, 'Loved it')).toBe(true)
    expect(canShareReaction(2.5, '  ok  ')).toBe(true)
    expect(canShareReaction(null, null)).toBe(false)
    expect(canShareReaction(null, '   ')).toBe(false)
    expect(canShareReaction(2.3, 'ok')).toBe(false)
    expect(canShareReaction(null, 'x'.repeat(MAX_REACTION_NOTE))).toBe(true)
    expect(canShareReaction(null, 'x'.repeat(MAX_REACTION_NOTE + 1))).toBe(false)
    expect(cleanReactionNote('  hi ')).toBe('hi')
    expect(cleanReactionNote('   ')).toBeNull()
  })
})

describe('reaction requests', () => {
  beforeEach(() => client.rpc.mockReset())
  const reaction = (overrides: Record<string, unknown> = {}) => ({
    user_id: friend, is_me: false, name: 'Fran Friend', rating: 4.5, note: 'Loved it', updated_at: '2026-11-01T22:00:00+00:00', ...overrides,
  })

  it("reads everyone's reactions, marking the caller's own, and skips what has nothing to show", async () => {
    client.rpc.mockResolvedValueOnce({ data: [reaction(), reaction({ is_me: true, name: 'You', rating: null, note: 'Just words' }), reaction({ rating: null, note: null }), { user_id: 'x' }], error: null })
    const list = await outingReactions(shared)
    expect(client.rpc).toHaveBeenCalledWith('get_outing_reactions', { p_shared_outing_id: shared })
    expect(list.map((r) => [r.name, r.isMe, r.rating])).toEqual([['Fran Friend', false, 4.5], ['You', true, null]])
  })

  it('an invalid rating is dropped but its note is kept', () => {
    const [parsed] = parseOutingReactions([reaction({ rating: 2.3 })])
    expect(parsed.rating).toBeNull()
    expect(parsed.note).toBe('Loved it')
  })

  it('shares the trimmed note and rating, refuses an empty one, and withdraws', async () => {
    client.rpc.mockResolvedValue({ data: {}, error: null })
    await shareOutingReaction(shared, 3.5, '  fun  ')
    expect(client.rpc).toHaveBeenLastCalledWith('share_outing_reaction', { p_shared_outing_id: shared, p_rating: 3.5, p_note: 'fun' })
    await shareOutingReaction(shared, null, 'words only')
    expect(client.rpc).toHaveBeenLastCalledWith('share_outing_reaction', { p_shared_outing_id: shared, p_rating: null, p_note: 'words only' })
    const calls = client.rpc.mock.calls.length
    for (const [rating, note] of [[null, null], [null, '  '], [2.3, null], [null, 'x'.repeat(501)]] as const) {
      await expect(shareOutingReaction(shared, rating, note)).rejects.toThrow('Add a rating, a note, or both.')
    }
    expect(client.rpc).toHaveBeenCalledTimes(calls)
    await withdrawOutingReaction(shared)
    expect(client.rpc).toHaveBeenLastCalledWith('withdraw_outing_reaction', { p_shared_outing_id: shared })
  })

  it('surfaces a short server refusal', async () => {
    client.rpc.mockResolvedValueOnce({ data: null, error: { message: 'Reactions open once the show has ended' } })
    await expect(shareOutingReaction(shared, 4, null)).rejects.toThrow('Reactions open once the show has ended')
  })

  it("finds the owner's shared outing through its members, or null", async () => {
    client.rpc.mockResolvedValueOnce({ data: [{ shared_outing_id: shared, user_id: friend, name: 'Fran', status: 'accepted' }], error: null })
    expect(await sharedOutingIdFor(outing)).toBe(shared)
    client.rpc.mockResolvedValueOnce({ data: [], error: null })
    expect(await sharedOutingIdFor(outing)).toBeNull()
  })

  it('rejects malformed ids before any request', async () => {
    client.rpc.mockReset()
    await expect(outingReactions('nope')).rejects.toThrow('Invalid outing')
    await expect(shareOutingReaction('nope', 4, null)).rejects.toThrow('Invalid outing')
    await expect(withdrawOutingReaction('nope')).rejects.toThrow('Invalid outing')
    expect(client.rpc).not.toHaveBeenCalled()
  })
})
