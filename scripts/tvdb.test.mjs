import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createTvdb } from '../supabase/functions/media-proxy/tvdb.ts'

const show = { id: 42, name: 'TMDB title', overview: 'TMDB overview', external_ids: { tvdb_id: 99 }, poster_path: '/poster.jpg' }
const season = { episodes: [{ id: 7, episode_number: 1, name: 'Old', air_date: '2020-01-01', crew: [] }] }
const ok = (data, links) => Response.json({ status: 'success', data, links })
function setup(handler, apiKey = 'test-key') {
  const cache = new Map()
  const calls = []
  const tvdb = createTvdb({
    apiKey, pin: 'test-pin',
    getCached: async key => cache.get(key) ?? null,
    setCached: async (key, value) => { cache.set(key, value) },
    fetcher: async (url, init) => {
      calls.push({ url, init })
      if (url.endsWith('/login')) return ok({ token: 'private-token' })
      assert.equal(init.headers.Authorization, 'Bearer private-token')
      return handler(url)
    },
  })
  return { tvdb, calls, cache }
}

test('TVDB translations win, identities/artwork survive, successful requests are cached', async () => {
  const { tvdb, calls, cache } = setup(() => ok({ id: 99, firstAired: '2019-01-01', translations: {
    nameTranslations: [{ language: 'eng', name: 'TVDB title' }],
    overviewTranslations: [{ language: 'eng', overview: 'TVDB overview' }],
  } }))
  const result = await tvdb.details(show)
  assert.equal(result.name, 'TVDB title')
  assert.equal(result.overview, 'TVDB overview')
  assert.equal(result.id, 42)
  assert.equal(result.poster_path, '/poster.jpg')
  assert.equal(result.metadata_source, 'tvdb')
  assert.deepEqual(await tvdb.details(show), result)
  assert.equal(calls.length, 2)
  assert.deepEqual(JSON.parse(calls[0].init.body), { apikey: 'test-key', pin: 'test-pin' })
  assert.ok(!JSON.stringify([...cache]).includes('private-token'))
})

test('no credentials or cross-reference makes no TVDB calls', async () => {
  const { tvdb, calls } = setup(() => { throw Error('unexpected') }, '')
  assert.equal(await tvdb.details(show), show)
  assert.equal(await tvdb.season(season, show, 1), season)
  assert.equal(calls.length, 0)
  const configured = setup(() => { throw Error('unexpected') })
  const unmatched = { id: 42 }
  assert.equal(await configured.tvdb.details(unmatched), unmatched)
  assert.equal(configured.calls.length, 0)
})

test('failures fall back without poisoning cache; missing fields keep TMDB values', async () => {
  const { tvdb, cache } = setup(() => Response.json({ status: 'failure' }, { status: 503 }))
  assert.equal(await tvdb.details(show), show)
  assert.equal(cache.size, 0)
  const sparse = setup(() => ok({ id: 99, name: 'Foreign title', originalLanguage: 'fra' }))
  assert.equal((await sparse.tvdb.details(show)).name, show.name)
  assert.equal((await sparse.tvdb.details(show)).overview, show.overview)
})

test('401 renews authentication once', async () => {
  let requests = 0
  const { tvdb, calls } = setup(() => ++requests === 1
    ? new Response('', { status: 401 }) : ok({ id: 99, originalLanguage: 'eng', name: 'Renewed' }))
  assert.equal((await tvdb.details(show)).name, 'Renewed')
  assert.equal(calls.filter(call => call.url.endsWith('/login')).length, 2)
})

test('episode metadata prefers TVDB and preserves TMDB episode IDs/credits', async () => {
  const { tvdb } = setup(() => ok({ episodes: [{ id: 900, seasonNumber: 1, number: 1, name: 'TVDB episode', aired: '2020-01-01', runtime: 45 }] }))
  const result = await tvdb.season(season, show, 1)
  assert.equal(result.episodes[0].name, 'TVDB episode')
  assert.equal(result.episodes[0].id, 7)
  assert.equal(result.episodes[0].runtime, 45)
  assert.deepEqual(result.episodes[0].crew, [])
})

test('numbering or known air-date disagreement preserves the original season', async () => {
  for (const episode of [
    { seasonNumber: 1, number: 2 },
    { seasonNumber: 2, number: 1 },
    { seasonNumber: 1, number: 1, aired: '2021-01-01' },
  ]) {
    const { tvdb } = setup(() => ok({ episodes: [episode] }))
    assert.equal(await tvdb.season(season, show, 1), season)
  }
})

test('pagination is exhausted before applying season metadata', async () => {
  const { tvdb, calls } = setup(url => url.endsWith('page=0')
    ? ok({ episodes: [] }, { next: 'https://api4.thetvdb.com/v4/next' })
    : ok({ episodes: [{ seasonNumber: 1, number: 1, name: 'Page two' }] }))
  assert.equal((await tvdb.season(season, show, 1)).episodes[0].name, 'Page two')
  assert.equal(calls.length, 3)
})

test('a failed login is not retried on every request', async () => {
  let logins = 0
  const tvdb = createTvdb({
    apiKey: 'bad-key',
    getCached: async () => null,
    setCached: async () => {},
    fetcher: async () => { logins++; return Response.json({ status: 'failure' }, { status: 401 }) },
  })
  assert.equal(await tvdb.details(show), show)
  assert.equal(await tvdb.details(show), show)
  assert.equal(logins, 1)
})
