// @vitest-environment node
import { describe, expect, it, vi } from 'vitest'
import { assertMoviegoingSnapshot, createMoviegoingPreferencesRemote, isVenueNote, mergeOwnedMoviegoingPreferences } from './moviegoingPreferences'
import { emptySnapshot } from './offline/snapshot'
import type { DeliveryContext } from './offline/coordinator'

const owner = '10000000-0000-4000-8000-000000000001'
const id = '20000000-0000-4000-8000-000000000001'
const date = '2026-10-08T12:00:00.123456+00:00'
const note = { id, user_id: owner, venue: '__proto__', notes: '', created_at: date, updated_at: date }
const interest = { id, user_id: owner, title_id: id, created_at: date, updated_at: date }
const context = (): DeliveryContext => ({ scope: { projectId: 'https://owner.test', userId: owner }, signal: new AbortController().signal, isCurrent: () => true })
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })
function setup(notes: unknown = [note], interests: unknown = [interest]) {
  const fetch = vi.fn<typeof globalThis.fetch>().mockImplementation(async (url) => {
    const parsed = new URL(String(url))
    return response(parsed.searchParams.has('id') ? [] : parsed.pathname.endsWith('venue_notes') ? notes : interests)
  })
  const session = vi.fn().mockResolvedValue({ userId: owner, accessToken: 'captured' })
  const read = createMoviegoingPreferencesRemote({ projectId: context().scope.projectId, anonKey: 'public', session, fetch })
  return { read, fetch, session }
}

it('reads all owned pages with a captured token, exact natural names, canonical IDs and microsecond revisions', async () => {
  const { read, fetch } = setup()
  const result = await read(context())
  expect(result).toEqual({ support: 'authoritative', venueNotes: [{ id, userId: owner, venue: '__proto__', notes: '', createdAt: date, updatedAt: date }],
    theaterInterest: [{ id, userId: owner, titleId: id, createdAt: date, updatedAt: date }] })
  expect(fetch).toHaveBeenCalledTimes(4) // A short page still needs an empty next page: server caps can be smaller than requested.
  for (const [url, options] of fetch.mock.calls) {
    expect(new URL(String(url)).searchParams.get('user_id')).toBe(`eq.${owner}`)
    expect(options).toMatchObject({ cache: 'no-store', headers: { Authorization: 'Bearer captured' } })
  }
  if (result.support !== 'authoritative') throw new Error('Missing result')
  expect(() => mergeOwnedMoviegoingPreferences(emptySnapshot(), result)).toThrow('changed while loading')
  const noInterests = mergeOwnedMoviegoingPreferences(emptySnapshot(), { ...result, theaterInterest: [] })
  expect(noInterests.rowRevisions).toEqual({ [`venue_notes:${id}`]: date })
})

describe('strict projections', () => {
  it.each([
    { ...note, user_id: '30000000-0000-4000-8000-000000000001' },
    { ...note, id: 'invalid' }, { ...note, notes: null }, { ...note, venue: ' leading' },
    { ...note, venue: '' }, { ...note, venue: 'x'.repeat(513) }, { ...note, notes: 'x'.repeat(20_001) },
    { ...note, updated_at: '2026-10-08' }, { ...note, extra: true }, { ...note, notes: '\uD800' },
  ])('rejects malformed/cross-owner venue row %#', async (row) => {
    await expect(setup([row], []).read(context())).rejects.toThrow()
  })
  it('uses PostgreSQL code-point bounds, permits empty notes, and preserves case', () => {
    const local = { id, userId: owner, venue: '🎥'.repeat(512), notes: '🎥'.repeat(20_000), createdAt: date, updatedAt: date }
    expect(isVenueNote(local)).toBe(true)
    expect(isVenueNote({ ...local, notes: '\0' })).toBe(false)
    expect(() => assertMoviegoingSnapshot({ venueNotes: [{ ...local, venue: 'Cinema' }, { ...local, id: owner, venue: 'cinema' }] }, owner)).not.toThrow()
  })
  it('rejects duplicate identities/natural names, changed pages and mismatched title identity', async () => {
    await expect(setup([note, note], []).read(context())).rejects.toThrow('pagination')
    await expect(setup([{ ...note, id: owner }, note], []).read(context())).rejects.toThrow('venue notes')
    await expect(setup([], [{ ...interest, title_id: owner }]).read(context())).rejects.toThrow('theater interest')
  })
})

it('only downgrades genuine missing-table responses; other errors retain the entire old snapshot', async () => {
  for (const code of ['42P01', 'PGRST205']) {
    const { read, fetch } = setup([], [])
    fetch.mockResolvedValueOnce(response({ code }, 404))
    expect(await read(context())).toEqual({ support: 'unsupported' })
  }
  for (const status of [401, 403, 500]) {
    const { read, fetch } = setup([], [])
    fetch.mockResolvedValueOnce(response({ code: 'PGRST205', message: 'unavailable' }, status))
    await expect(read(context())).rejects.toThrow('unavailable')
  }
  const { read, fetch } = setup([], [])
  fetch.mockRejectedValueOnce(new Error('offline'))
  await expect(read(context())).rejects.toThrow('offline')
  const partial = setup([], [{ ...interest, title_id: owner }])
  partial.fetch.mockResolvedValueOnce(response({ code: 'PGRST205' }, 404))
  await expect(partial.read(context())).rejects.toThrow('theater interest')
})

it('reads beyond the default server limit and rejects a repeated page', async () => {
  const rows = Array.from({ length: 1001 }, (_, index) => ({ ...note,
    id: `20000000-0000-4000-8000-${String(index).padStart(12, '0')}`, venue: `Cinema ${index}` }))
  const { read, fetch } = setup([], [])
  fetch.mockImplementation(async (url) => {
    const parsed = new URL(String(url)), after = parsed.searchParams.get('id')?.slice(3) ?? ''
    return response(parsed.pathname.endsWith('venue_notes') ? rows.filter((row) => row.id > after).slice(0, 1000) : [])
  })
  const result = await read(context())
  expect(result.support === 'authoritative' && result.venueNotes.length).toBe(1001)
  fetch.mockImplementation(async (url) => response(String(url).includes('/venue_notes?') ? [note] : []))
  await expect(read(context())).rejects.toThrow('pagination')
})

it('does not skip the next row when an earlier page is deleted', async () => {
  const first = { ...note, id: owner, venue: 'First' }
  let rows = [first, note]
  const { read, fetch } = setup([], [])
  fetch.mockImplementation(async (url) => {
    const query = new URL(String(url))
    if (!query.pathname.endsWith('venue_notes')) return response([])
    const after = query.searchParams.get('id')?.slice(3) ?? ''
    const page = rows.filter((row) => row.id > after).slice(0, 1)
    rows = rows.filter((row) => row.id !== first.id)
    return response(page)
  })
  const result = await read(context())
  expect(result.support === 'authoritative' && result.venueNotes.map((row) => row.id)).toEqual([owner, id])
})

it('validates public snapshot-helper shapes and clears obsolete authoritative revisions', () => {
  for (const value of [null, { venueNotes: {} }, { theaterInterest: 'bad' }, { moviegoingPreferencesSupport: 'unknown' }]) {
    expect(() => assertMoviegoingSnapshot(value as Parameters<typeof assertMoviegoingSnapshot>[0])).toThrow('Invalid')
  }
  const merged = mergeOwnedMoviegoingPreferences({ ...emptySnapshot(), rowRevisions: { [`venue_notes:${id}`]: date, [`theater_interest:${id}`]: date, [`titles:${id}`]: date } },
    { support: 'authoritative', venueNotes: [], theaterInterest: [] })
  expect(merged.rowRevisions).toEqual({ [`titles:${id}`]: date })
})

it('rejects anonymous/wrong-owner sessions and stale scopes before requests or before returning', async () => {
  for (const sessionValue of [null, { userId: id, accessToken: 'wrong' }]) {
    const { read, fetch, session } = setup()
    session.mockResolvedValue(sessionValue)
    await expect(read(context())).rejects.toThrow('Sign in')
    expect(fetch).not.toHaveBeenCalled()
  }
  const { read, fetch } = setup([], [])
  await expect(read({ ...context(), scope: { ...context().scope, userId: 'anonymous-local-only' } })).rejects.toThrow('Sign in')
  await expect(read({ ...context(), scope: { ...context().scope, projectId: 'https://other.test' } })).rejects.toThrow('account changed')
  expect(fetch).not.toHaveBeenCalled()
  const lateSession = setup([], [])
  let sessionCurrent = true
  lateSession.session.mockImplementation(async () => { sessionCurrent = false; return { userId: owner, accessToken: 'late' } })
  await expect(lateSession.read({ ...context(), isCurrent: () => sessionCurrent })).rejects.toThrow('account changed')
  expect(lateSession.fetch).not.toHaveBeenCalled()
  let current = true
  fetch.mockImplementationOnce(async () => { current = false; return response([]) })
  await expect(read({ ...context(), isCurrent: () => current })).rejects.toThrow('account changed')
})
