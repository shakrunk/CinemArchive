import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  canRespond, goingOf, inviteFriends, invitationsOf, listSharedOutings, outingMembers, parseOutingMembers, parseSharedOutings,
  removeOutingMember, respondToInvite, type SharedOuting,
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
