import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { createStorageFixture } from './storage-fixture.mjs'

const db = new PGlite({ extensions: { pgcrypto } })
const root = new URL('../../../', import.meta.url)
const migration = (await readFile(new URL('supabase/migrations/20261009004431_backup_graph_sync.sql', root), 'utf8')).replaceAll('\r\n', '\n')
const schema = (await readFile(new URL('schema.sql', root), 'utf8')).replaceAll('\r\n', '\n')
const owner = randomUUID(), other = randomUUID(), pagingOwner = randomUUID()
const ids = Object.fromEntries(['title', 'season', 'episode', 'titleCast', 'titleCrew', 'seasonCast', 'episodeCrew', 'watch', 'rating', 'review', 'viewing', 'outing', 'list', 'item', 'venue', 'otherTitle', 'otherCast', 'invalidSeasonCast'].map(key => [key, randomUUID()]))
const companions = [{ name: 'Same name', friendUserId: other }, { name: 'Same name' }]
const expectedTypes = ['title', 'season', 'episode', 'title_cast', 'title_crew', 'season_cast', 'episode_crew', 'viewing', 'episode_watch_event', 'episode_rating', 'episode_review', 'cinema_outing', 'list', 'list_item', 'venue_note', 'theater_interest'].sort()
let beforeRows
async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
async function rows(since = '1970-01-01', limit = 500) {
  return (await db.query('select * from public.sync_library_changes($1,$2)', [since, limit])).rows
}
const matching = (all, type, id) => all.find(row => row.entity_type === type && row.entity_id === id)

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
  await db.exec(schema.split('-- Backup graph sync (20261009004431).')[0])
  for (const user of [owner, other, pagingOwner]) await db.query('insert into auth.users(id,email) values($1,$2)', [user, `${user}@example.test`])
  await db.exec('begin')
  await db.query("insert into titles(id,user_id,tmdb_id,type,title,year,status,custom_watch_url,physical_media) values($1,$2,42,'tv','Archive graph',0,'watching','https://example.test/watch','[{\"id\":\"copy\",\"format\":\"Other\",\"notes\":\"Keep\"}]'),($3,$4,43,'tv','Other graph',0,'watchlist',null,'[]')", [ids.title, owner, ids.otherTitle, other])
  await db.query('insert into seasons(id,user_id,title_id,season_number,episode_count) values($1,$2,$3,0,1)', [ids.season, owner, ids.title])
  await db.query('insert into episodes(id,user_id,title_id,season_number,episode_number) values($1,$2,$3,0,1)', [ids.episode, owner, ids.title])
  await db.query("insert into title_cast(id,user_id,title_id,tmdb_person_id,name,episode_count,profile_url,cast_order) values($1,$2,$3,10,'Same name',0,'https://example.test/title.png',1),($4,$5,$6,11,'Private actor',8,'https://example.test/private.png',2)", [ids.titleCast, owner, ids.title, ids.otherCast, other, ids.otherTitle])
  await db.query("insert into title_crew(id,user_id,title_id,tmdb_person_id,name,job,department,profile_url) values($1,$2,$3,20,'Writer','Writer','Writing','https://example.test/crew.png')", [ids.titleCrew, owner, ids.title])
  await db.query("insert into season_cast(id,user_id,title_id,season_id,tmdb_person_id,name,episode_count,profile_url,cast_order) values($1,$2,$3,$4,30,'Same name',4,'https://example.test/season.png',3),($5,$6,$7,$4,31,'Bad parent',9,'https://example.test/invalid.png',4)", [ids.seasonCast, owner, ids.title, ids.season, ids.invalidSeasonCast, other, ids.otherTitle])
  await db.query("insert into episode_crew(id,user_id,title_id,episode_id,tmdb_person_id,name,job) values($1,$2,$3,$4,40,'Director','Director')", [ids.episodeCrew, owner, ids.title, ids.episode])
  await db.query("insert into episode_watch_events(id,user_id,episode_id,watched_at,notes,color_mode) values($1,$2,$3,null,'Undated watch','bw')", [ids.watch, owner, ids.episode])
  await db.query('insert into episode_ratings(id,user_id,episode_id,rating) values($1,$2,$3,4.5)', [ids.rating, owner, ids.episode])
  await db.query("insert into episode_reviews(id,user_id,episode_id,review_text,color_mode) values($1,$2,$3,'Independent review','color')", [ids.review, owner, ids.episode])
  await db.query('insert into viewings(id,user_id,title_id,viewed_at,companions) values($1,$2,$3,null,$4)', [ids.viewing, owner, ids.title, JSON.stringify(companions)])
  await db.query("insert into cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes,status,companions) values($1,$2,$3,'2026-01-01T20:00:00Z','2026-01-01T22:00:00Z',100,'scheduled',$4)", [ids.outing, owner, ids.title, JSON.stringify(companions)])
  await db.query("insert into lists(id,user_id,name,description) values($1,$2,'Archive list','Keep description')", [ids.list, owner])
  await db.query('insert into list_items(id,user_id,list_id,title_id) values($1,$2,$3,$4)', [ids.item, owner, ids.list, ids.title])
  await db.query("insert into venue_notes(id,user_id,venue,notes) values($1,$2,'Cinema','Private note')", [ids.venue, owner])
  await db.query('insert into theater_interest(id,title_id,user_id) values($1,$1,$2)', [ids.title, owner])
  await db.exec('commit')
  await as('authenticated', owner)
  beforeRows = await rows()
  assert.deepEqual(beforeRows.map(row => row.entity_type).sort(), expectedTypes)
  assert.equal(Object.hasOwn(matching(beforeRows, 'title', ids.title).payload, 'backupGraphVersion'), false)
  assert.equal(Object.hasOwn(matching(beforeRows, 'title_cast', ids.titleCast).payload, 'profileUrl'), false)
  assert.equal(Object.hasOwn(matching(beforeRows, 'episode_watch_event', ids.watch).payload, 'colorMode'), false)
  await as('postgres')
  await db.exec(migration)
  await as('authenticated', owner)
}, { timeout: 60000 })
after(async () => db.close())

test('migration changes only declared fields, preserving every arm, parent, identity and revision', async () => {
  const all = await rows()
  assert.deepEqual(all.map(row => row.entity_type).sort(), expectedTypes)
  const additions = { title: ['backupGraphVersion'], title_cast: ['profileUrl', 'episodeCount'], title_crew: ['profileUrl'], season_cast: ['profileUrl', 'episodeCount'], episode_watch_event: ['colorMode'], episode_review: ['colorMode'] }
  for (const old of beforeRows) {
    const current = structuredClone(matching(all, old.entity_type, old.entity_id))
    for (const field of additions[old.entity_type] ?? []) delete current.payload[field]
    assert.deepEqual(current, old, old.entity_type)
  }
  assert.equal((await rows(beforeRows.at(-1).updated_at.toISOString())).length, 0, 'migration cannot invent user edits; upgraded clients need epoch replay')
})

test('epoch replay returns complete credited-person fields, zero counts, watch/review modes and unchanged companion identities', async () => {
  const all = await rows()
  const title = matching(all, 'title', ids.title).payload
  for (const field of ['backupGraphVersion', 'personCreditsVersion', 'titleMetadataVersion', 'moviegoingPreferencesVersion']) assert.equal(title[field], 1)
  for (const [type, id, profileUrl, episodeCount] of [
    ['title_cast', ids.titleCast, 'https://example.test/title.png', 0],
    ['season_cast', ids.seasonCast, 'https://example.test/season.png', 4],
  ]) {
    const payload = matching(all, type, id).payload
    assert.equal(payload.profileUrl, profileUrl); assert.equal(payload.episodeCount, episodeCount)
  }
  assert.equal(matching(all, 'title_crew', ids.titleCrew).payload.profileUrl, 'https://example.test/crew.png')
  assert.equal(matching(all, 'episode_watch_event', ids.watch).payload.colorMode, 'bw')
  assert.equal(matching(all, 'episode_review', ids.review).payload.colorMode, 'color')
  assert.equal(matching(all, 'episode_watch_event', ids.watch).payload.watchedAt, null)
  assert.deepEqual(matching(all, 'viewing', ids.viewing).payload.companions, companions)
  assert.deepEqual(matching(all, 'cinema_outing', ids.outing).payload.companions, companions)
})

test('owner feed does not expose another account or inconsistent credited parent', async () => {
  assert.ok((await rows()).every(row => ![ids.otherTitle, ids.otherCast, ids.invalidSeasonCast].includes(row.entity_id)))
  await as('authenticated', other)
  try {
    assert.deepEqual((await rows()).map(row => row.entity_id).sort(), [ids.otherTitle, ids.otherCast].sort())
    assert.equal(matching(await rows(), 'title_cast', ids.otherCast).payload.profileUrl, 'https://example.test/private.png')
  } finally { await as('authenticated', owner) }
})

test('page floor retains the complete transaction timestamp and strict watermark never loses its tail', async () => {
  const first = await rows('1970-01-01', 1)
  assert.equal(first.length, beforeRows.length)
  assert.ok(first.every(row => row.updated_at.toISOString() === first[0].updated_at.toISOString()))
  assert.equal((await rows(first.at(-1).updated_at.toISOString(), 1)).length, 0)
})

test('page limit remains clamped to 500 for distinct timestamps including null default', async () => {
  await as('postgres')
  await db.query("insert into titles(user_id,tmdb_id,type,title,year,updated_at) select $1,n,'movie','Page '||n,0,'2000-01-01'::timestamptz+n*interval '1 second' from generate_series(1,501) n", [pagingOwner])
  await as('authenticated', pagingOwner)
  try {
    for (const limit of [500, 9999, null]) {
      const first = await rows('1970-01-01', limit)
      assert.equal(first.length, 500)
      const last = await rows(first.at(-1).updated_at.toISOString(), limit)
      assert.equal(last.length, 1)
      assert.notEqual(last[0].entity_id, first.at(-1).entity_id)
    }
  } finally { await as('authenticated', owner) }
})

test('explicit null clears survive incremental delivery as present keys', async () => {
  const since = beforeRows.at(-1).updated_at.toISOString()
  await as('postgres')
  await db.query('update title_cast set profile_url=null,episode_count=null where id=$1', [ids.titleCast])
  await db.query('update season_cast set profile_url=null,episode_count=null where id=$1', [ids.seasonCast])
  await db.query('update title_crew set profile_url=null where id=$1', [ids.titleCrew])
  await db.query('update episode_watch_events set color_mode=null where id=$1', [ids.watch])
  await db.query('update episode_reviews set color_mode=null where id=$1', [ids.review])
  await as('authenticated', owner)
  const all = await rows(since)
  for (const [type, id, fields] of [
    ['title_cast', ids.titleCast, ['profileUrl', 'episodeCount']], ['season_cast', ids.seasonCast, ['profileUrl', 'episodeCount']],
    ['title_crew', ids.titleCrew, ['profileUrl']], ['episode_watch_event', ids.watch, ['colorMode']], ['episode_review', ids.review, ['colorMode']],
  ]) for (const key of fields) {
    const payload = matching(all, type, id).payload
    assert.ok(Object.hasOwn(payload, key), key); assert.equal(payload[key], null, key)
  }
})

test('direct and cascading deletions retain exact owner tombstones with no former payload', async () => {
  await as('postgres')
  await db.query('delete from title_cast where id=$1', [ids.titleCast])
  await db.query('delete from episodes where id=$1', [ids.episode])
  await as('authenticated', owner)
  const all = await rows()
  for (const [id, type] of [[ids.titleCast, 'title_cast'], [ids.episode, 'episode'], [ids.watch, 'episode_watch_event'], [ids.review, 'episode_review'], [ids.rating, 'episode_rating'], [ids.episodeCrew, 'episode_crew']]) {
    const matches = all.filter(row => row.entity_id === id)
    assert.equal(matches.length, 1)
    assert.equal(matches[0].entity_type, 'tombstone'); assert.equal(matches[0].parent_id, null)
    assert.deepEqual(matches[0].payload, { entityType: type })
  }
  await as('authenticated', other)
  try { assert.equal((await rows()).some(row => row.entity_type === 'tombstone'), false) }
  finally { await as('authenticated', owner) }
})

test('anonymous and missing identities cannot read; invoker/private-definer privileges stay fixed', async () => {
  await as('anon', owner)
  await assert.rejects(rows(), { code: '42501' })
  await assert.rejects(db.query("select * from cinemarchive_private.sync_library_changes('1970-01-01',500)"), { code: '42501' })
  await as('authenticated')
  assert.deepEqual(await rows(), [])
  await as('postgres')
  const functions = (await db.query("select n.nspname,p.prosecdef,p.proconfig,has_function_privilege('anon',p.oid,'execute') as anon_execute from pg_proc p join pg_namespace n on n.oid=p.pronamespace where p.proname='sync_library_changes' order by n.nspname")).rows
  assert.deepEqual(functions.map(f => [f.nspname, f.prosecdef, f.anon_execute]), [['cinemarchive_private', true, false], ['public', false, false]])
  assert.ok(functions.every(f => f.proconfig.includes('search_path=""')))
  await db.exec(migration)
  await as('authenticated', owner)
  assert.equal(matching(await rows(), 'title', ids.title).payload.backupGraphVersion, 1)
})

test('canonical schema contains the exact generated migration', () => assert.ok(schema.includes(migration.trim())))
