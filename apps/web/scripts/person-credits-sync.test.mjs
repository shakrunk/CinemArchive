import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { createStorageFixture } from './storage-fixture.mjs'

const db = new PGlite({ extensions: { pgcrypto } })
const root = new URL('../../../', import.meta.url)
const owner = randomUUID(), other = randomUUID()
const title = randomUUID(), otherTitle = randomUUID(), season = randomUUID(), episode = randomUUID()
const seasonCredit = randomUUID(), episodeCredit = randomUUID(), foreignCredit = randomUUID()
const migration = (await readFile(new URL('supabase/migrations/20261008193517_person_credits_sync.sql', root), 'utf8')).replaceAll('\r\n', '\n')
const schema = (await readFile(new URL('schema.sql', root), 'utf8')).replaceAll('\r\n', '\n')
async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
async function rows(limit = 500, since = '1970-01-01') {
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
  await db.exec(schema.split('-- Person credits sync (20261008193517)')[0])
  for (const id of [owner, other]) await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
  // Pre-existing, unchanged rows must be discoverable by an epoch backfill.
  await db.exec('begin')
  await db.query("insert into titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,42,'tv','Credit series',2026,'watching'),($3,$4,43,'tv','Other series',2026,'watching')", [title, owner, otherTitle, other])
  await db.query('insert into seasons(id,user_id,title_id,season_number,episode_count) values($1,$2,$3,0,1)', [season, owner, title])
  await db.query('insert into episodes(id,user_id,title_id,season_number,episode_number) values($1,$2,$3,0,1)', [episode, owner, title])
  await db.query("insert into season_cast(id,user_id,title_id,season_id,tmdb_person_id,name,character_name,cast_order) values($1,$2,$3,$4,10,'Shared name','Guest',21)", [seasonCredit, owner, title, season])
  await db.query("insert into episode_crew(id,user_id,title_id,episode_id,tmdb_person_id,name,job) values($1,$2,$3,$4,11,'Shared name','Teleplay')", [episodeCredit, owner, title, episode])
  // Legacy direct writes can have mismatched parent ownership: never expose them.
  await db.query("insert into season_cast(id,user_id,title_id,season_id,tmdb_person_id,name) values($1,$2,$3,$4,12,'Invalid parent')", [foreignCredit, other, otherTitle, season])
  await db.exec('commit')
  await as('authenticated', owner)
  assert.equal((await rows()).some(r => r.entity_type === 'season_cast'), false)
  assert.equal(Object.hasOwn((await rows()).find(r => r.entity_id === title).payload, 'personCreditsVersion'), false)
  await as('postgres')
  await db.exec(migration)
  await db.exec('grant select on public.titles,public.seasons,public.episodes to authenticated; grant select,delete,update on public.season_cast,public.episode_crew to authenticated; grant delete on public.seasons,public.episodes to authenticated;')
  await as('authenticated', owner)
}, { timeout: 60000 })
after(async () => db.close())

test('epoch backfill returns unchanged Specials credits with stable IDs and all filter fields', async () => {
  const all = await rows()
  assert.equal(all.find(r => r.entity_id === title).payload.personCreditsVersion, 1)
  const cast = all.find(r => r.entity_id === seasonCredit)
  assert.equal(cast.entity_type, 'season_cast')
  assert.equal(cast.parent_id, season)
  assert.deepEqual(cast.payload, { id: seasonCredit, titleId: title, seasonId: season, tmdbPersonId: 10, name: 'Shared name', characterName: 'Guest', castOrder: 21 })
  const crew = all.find(r => r.entity_id === episodeCredit)
  assert.equal(crew.entity_type, 'episode_crew')
  assert.equal(crew.parent_id, episode)
  assert.deepEqual(crew.payload, { id: episodeCredit, titleId: title, episodeId: episode, tmdbPersonId: 11, name: 'Shared name', job: 'Teleplay' })
})

test('pagination retains the entire shared timestamp group and strict next watermark excludes it', async () => {
  const first = await rows(1)
  assert.ok(first.length >= 5)
  assert.ok(first.some(r => r.entity_id === seasonCredit))
  assert.ok(first.some(r => r.entity_id === episodeCredit))
  assert.equal((await rows(500, first.at(-1).updated_at.toISOString())).length, 0)
})

test('owner isolation rejects foreign and inconsistent parent graphs', async () => {
  assert.equal((await rows()).some(r => r.entity_id === foreignCredit), false)
  await as('authenticated', other)
  assert.equal((await rows()).some(r => [seasonCredit, episodeCredit, foreignCredit].includes(r.entity_id)), false)
  await as('authenticated', owner)
})

test('incremental metadata update and exact deletion tombstones reach the owner', async () => {
  await db.query('update season_cast set character_name=null,cast_order=30 where id=$1', [seasonCredit])
  const cast = (await rows()).find(r => r.entity_id === seasonCredit)
  assert.equal(cast.payload.characterName, null)
  assert.equal(cast.payload.castOrder, 30)
  await db.query('delete from season_cast where id=$1', [seasonCredit])
  await db.query('delete from episode_crew where id=$1', [episodeCredit])
  const all = await rows()
  for (const [id, kind] of [[seasonCredit, 'season_cast'], [episodeCredit, 'episode_crew']]) {
    const matches = all.filter(r => r.entity_id === id)
    assert.equal(matches.length, 1)
    assert.equal(matches[0].entity_type, 'tombstone')
    assert.equal(matches[0].payload.entityType, kind)
  }
})

test('cascading parent deletion records credit tombstones without leaking to another owner', async () => {
  const cast = randomUUID(), crew = randomUUID()
  await as('postgres')
  await db.query("insert into season_cast(id,user_id,title_id,season_id,tmdb_person_id,name) values($1,$2,$3,$4,50,'Cascade actor')", [cast, owner, title, season])
  await db.query("insert into episode_crew(id,user_id,title_id,episode_id,tmdb_person_id,name,job) values($1,$2,$3,$4,51,'Cascade director','Director')", [crew, owner, title, episode])
  await as('authenticated', owner)
  await db.query('delete from seasons where id=$1', [season])
  await db.query('delete from episodes where id=$1', [episode])
  assert.equal((await rows()).filter(r => [cast, crew].includes(r.entity_id) && r.entity_type === 'tombstone').length, 2)
  await as('authenticated', other)
  assert.equal((await rows()).some(r => [cast, crew].includes(r.entity_id)), false)
  await as('authenticated', owner)
})

test('anonymous invocation is denied; wrappers retain invoker and fixed private definer boundaries', async () => {
  await as('anon', owner)
  await assert.rejects(rows(), { code: '42501' })
  await as('postgres')
  const functions = (await db.query("select n.nspname,p.prosecdef,p.proconfig from pg_proc p join pg_namespace n on n.oid=p.pronamespace where p.proname='sync_library_changes' order by n.nspname")).rows
  assert.deepEqual(functions.map(f => [f.nspname, f.prosecdef]), [['cinemarchive_private', true], ['public', false]])
  assert.ok(functions.every(f => f.proconfig.includes('search_path=""')))
  await db.exec(migration) // Idempotent trigger and function installation.
  await as('authenticated', owner)
  assert.ok((await rows()).find(r => r.entity_id === title).payload.personCreditsVersion === 1)
})

test('canonical schema contains the exact migration', () => {
  assert.ok(schema.includes(migration.trim()))
})
