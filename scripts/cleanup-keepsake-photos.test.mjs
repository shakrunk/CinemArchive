import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanupKeepsakePhotos } from './cleanup-keepsake-photos.mjs'

const owner = '10000000-0000-4000-8000-000000000001'
const id = '20000000-0000-4000-8000-000000000001'
const claim = { object_key: `${owner}/${id}` }
function setup(responses) {
  const calls = []
  return {
    calls,
    run: () => cleanupKeepsakePhotos({
      url: 'https://project.example.test', serviceKey: 'test-only-key',
      fetchImpl: async (url, init) => {
        calls.push({ path: url.pathname, method: init.method, body: JSON.parse(init.body), headers: init.headers, redirect: init.redirect })
        const next = responses.shift()
        assert.ok(next, 'unexpected request')
        return new Response(next.body === undefined ? null : JSON.stringify(next.body), { status: next.status ?? 200 })
      },
    }),
  }
}

test('claims first, then deletes exactly the claimed bytes through Storage', async () => {
  const { calls, run } = setup([{ body: [claim] }, {}])
  assert.deepEqual(await run(), { claimed: 1, removed: 1, failed: 0 })
  assert.deepEqual(calls.map((c) => [c.method, c.path]), [
    ['POST', '/rest/v1/rpc/claim_keepsake_cleanup'], ['DELETE', '/storage/v1/object/outing-keepsakes'],
  ])
  assert.deepEqual(calls[1].body, { prefixes: [claim.object_key] })
  assert.ok(calls.every((c) => c.redirect === 'error' && c.headers.Authorization === 'Bearer test-only-key'))
})

test('a failed byte deletion is counted and left for the next run', async () => {
  const { calls, run } = setup([{ body: [claim] }, { status: 500, body: { secret: 'must not be returned' } }])
  assert.deepEqual(await run(), { claimed: 1, removed: 0, failed: 1 })
  assert.equal(calls.length, 2)
})

test('malformed claims never reach Storage', async () => {
  const { calls, run } = setup([{ body: [{ object_key: '../other-bucket/private' }, { object_key: `${owner}/${id}/original` }, {}, { object_key: 7 }] }])
  assert.deepEqual(await run(), { claimed: 4, removed: 0, failed: 4 })
  assert.equal(calls.length, 1)
})

test('empty and malformed claim responses have no deletion side effects', async () => {
  const empty = setup([{ body: [] }])
  assert.deepEqual(await empty.run(), { claimed: 0, removed: 0, failed: 0 })
  const invalid = setup([{ body: { objects: [claim] } }])
  await assert.rejects(invalid.run(), /Invalid keepsake cleanup claim/)
  assert.equal(invalid.calls.length, 1)
})

test('rejects missing credentials or an insecure remote target before any request', async () => {
  const fetchImpl = () => { throw new Error('must not fetch') }
  await assert.rejects(cleanupKeepsakePhotos({ url: 'https://example.test', fetchImpl }), /required/)
  await assert.rejects(cleanupKeepsakePhotos({ url: 'http://example.test', serviceKey: 'test', fetchImpl }), /HTTPS/)
  await assert.rejects(cleanupKeepsakePhotos({ url: 'https://example.test/unexpected', serviceKey: 'test', fetchImpl }), /origin/)
})
