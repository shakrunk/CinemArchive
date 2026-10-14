import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { createStorageFixture } from './storage-fixture.mjs'

const db = new PGlite({ extensions: { pgcrypto } })
const root = new URL('../../../', import.meta.url)
const owner = randomUUID(), other = randomUUID(), title = randomUUID(), otherTitle = randomUUID()
const physical = [{ id: randomUUID(), format: 'blu-ray', edition: 'Steelbook', notes: 'Gift from family' }]
const migration = (await readFile(new URL('supabase/migrations/20261008230747_rich_title_metadata_sync.sql', root), 'utf8')).replaceAll('\r\n', '\n')
const schema = (await readFile(new URL('schema.sql', root), 'utf8')).replaceAll('\r\n', '\n')
let originalTimestamp

async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
async function rows(since = '1970-01-01', limit = 500) {
  return (await db.query('select * from public.sync_library_changes($1,$2)', [since, limit])).rows
}

before(async () => {
  await db.exec(`create role anon; create role authenticated; create role service_role;
    create schema auth;
    create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}',raw_app_meta_data jsonb default '{}');
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to authenticated,anon;
    grant execute on function auth.uid() to authenticated,anon;
    set check_function_bodies=false;`)
  await createStorageFixture(db)
  await db.exec(schema.split('-- Rich title metadata sync (20261008230747).')[0])
  await db.query('insert into auth.users(id,email) values($1,$2),($3,$4)', [owner, 'owner@example.test', other, 'other@example.test'])
  await db.query(`insert into titles(id,user_id,tmdb_id,type,title,year,status,content_rating,imdb_id,rt_url,
    rt_score,metacritic_score,custom_watch_url,in_home_collection,physical_media,awards_count,bechdel_outcome,bechdel_score)
    values($1,$2,42,'movie','Film',2026,'watched','PG-13','tt1375666','https://www.rottentomatoes.com/m/example',
    91,83,'https://example.test/watch/42',true,$3,4,'pass','3/3'),
    ($4,$5,43,'movie','Private other film',2025,'watchlist','R','tt0000002',null,0,0,'https://other.example.test/private',true,'[]',0,'fail','1/3')`,
  [title, owner, JSON.stringify(physical), otherTitle, other])
  await as('authenticated', owner)
  const before = (await rows()).find(r => r.entity_id === title)
  originalTimestamp = before.updated_at
  assert.equal(Object.hasOwn(before.payload, 'titleMetadataVersion'), false)
  assert.equal(Object.hasOwn(before.payload, 'physicalMedia'), false)
  await as('postgres')
  await db.exec(migration)
  await db.exec('grant select,update,delete on public.titles to authenticated;')
  await as('authenticated', owner)
}, { timeout: 60000 })
after(async () => db.close())

test('unchanged titles backfill every rich field without changing revision or identity', async () => {
  const result = (await rows()).find(r => r.entity_id === title)
  assert.deepEqual(result.updated_at, originalTimestamp)
  assert.equal(result.payload.id, title)
  assert.equal(result.payload.personCreditsVersion, 1)
  const expected = {
    titleMetadataVersion: 1, contentRating: 'PG-13', imdbId: 'tt1375666', rtUrl: 'https://www.rottentomatoes.com/m/example',
    rtScore: 91, metacriticScore: 83, customWatchUrl: 'https://example.test/watch/42', inHomeCollection: true,
    physicalMedia: physical, awardsCount: 4, bechdelOutcome: 'pass', bechdelScore: '3/3',
  }
  for (const [key, value] of Object.entries(expected)) assert.deepEqual(result.payload[key], value, key)
  assert.equal((await rows(originalTimestamp.toISOString())).some(r => r.entity_id === title), false,
    'old clients need epoch backfill; migration must not synthesize a user edit')
})

test('private watch links and physical-copy notes stay inside the authenticated owner feed', async () => {
  assert.equal((await rows()).some(r => r.entity_id === otherTitle), false)
  await as('authenticated', other)
  try {
    assert.equal((await rows()).some(r => r.entity_id === title), false)
    const row = (await rows()).find(r => r.entity_id === otherTitle).payload
    assert.equal(row.rtScore, 0)
    assert.equal(row.metacriticScore, 0)
    assert.equal(row.awardsCount, 0)
    assert.equal(row.bechdelOutcome, 'fail')
  } finally { await as('authenticated', owner) }
})

test('anonymous callers cannot read the public or private sync function', async () => {
  await as('anon')
  try {
    await assert.rejects(rows(), /permission denied/)
    await assert.rejects(db.query("select * from cinemarchive_private.sync_library_changes('1970-01-01',500)"), /permission denied/)
  } finally { await as('authenticated', owner) }
})

test('explicit metadata clears preserve null, false, and empty array semantics', async () => {
  await db.query(`update titles set content_rating=null,imdb_id=null,rt_url=null,rt_score=null,metacritic_score=null,
    custom_watch_url=null,in_home_collection=false,physical_media='[]',awards_count=null,bechdel_outcome=null,bechdel_score=null where id=$1`, [title])
  const payload = (await rows()).find(r => r.entity_id === title).payload
  for (const key of ['contentRating', 'imdbId', 'rtUrl', 'rtScore', 'metacriticScore', 'customWatchUrl', 'awardsCount', 'bechdelOutcome', 'bechdelScore']) {
    assert.ok(Object.hasOwn(payload, key), key)
    assert.equal(payload[key], null, key)
  }
  assert.equal(payload.inHomeCollection, false)
  assert.deepEqual(payload.physicalMedia, [])
  assert.equal(payload.titleMetadataVersion, 1)
})

test('deleting a rich title emits its tombstone without replaying old metadata', async () => {
  await db.query('delete from titles where id=$1', [title])
  const matching = (await rows()).filter(r => r.entity_id === title)
  assert.equal(matching.length, 1)
  assert.equal(matching[0].entity_type, 'tombstone')
  assert.deepEqual(matching[0].payload, { entityType: 'title' })
})

test('canonical schema contains the migration definition verbatim', () => {
  assert.ok(schema.includes(migration))
})
