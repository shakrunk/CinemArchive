import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'

const database = new PGlite({ extensions: { pgcrypto } })
const owner = randomUUID()
const other = randomUUID()
const ownerTitle = randomUUID()
const episode = randomUUID()
const watched = randomUUID()
const undated = randomUUID()
const foreignWatch = randomUUID()
const migration = await readFile(new URL('../../../supabase/migrations/20261008183232_library_discovery_sync_fields.sql', import.meta.url), 'utf8')
const canonicalSchema = (await readFile(new URL('../../../schema.sql', import.meta.url), 'utf8')).replaceAll('\r\n', '\n')

before(async () => {
  await database.exec(`
    create role anon; create role authenticated; create role service_role;
    create schema auth;
    create table auth.users(id uuid primary key, email text, raw_user_meta_data jsonb default '{}', raw_app_meta_data jsonb default '{}');
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to authenticated, anon;
    grant execute on function auth.uid() to authenticated, anon;
    set check_function_bodies = false;
  `)
  // Load the shipped RPC before later append-only migrations, then prove this migration
  // enriches an existing undated row without needing a new server-side update.
  await database.exec(canonicalSchema.split('-- Atomic library commands (20261008162831)\n')[0])
  await database.exec(await readFile(new URL('../../../supabase/migrations/20261008162831_atomic_library_commands.sql', import.meta.url), 'utf8'))
  await database.query('insert into auth.users(id,email) values ($1,$2),($3,$4)', [owner, 'owner@example.test', other, 'other@example.test'])
  for (const [user, watch] of [[owner, watched], [other, foreignWatch]]) {
    const title = user === owner ? ownerTitle : randomUUID()
    const ep = user === owner ? episode : randomUUID()
    await database.query("insert into titles(id,user_id,tmdb_id,type,title,year,status) values ($1,$2,42,'tv','A show',2020,'watching')", [title, user])
    await database.query('insert into episodes(id,user_id,title_id,season_number,episode_number) values ($1,$2,$3,1,1)', [ep, user, title])
    await database.query("insert into episode_watch_events(id,user_id,episode_id,watched_at,notes) values ($1,$2,$3,'2026-01-03','With family')", [watch, user, ep])
  }
  await database.query("update titles set tags=array['family','rewatch'], studios=array['Example Studio'], collection_id=42, collection_name='Example Collection' where id=$1", [ownerTitle])
  await database.query('insert into episode_watch_events(id,user_id,episode_id,watched_at,notes) values ($1,$2,$3,null,null)', [undated, owner, episode])
  await database.query("select set_config('request.jwt.claim.sub',$1,false)", [owner])
  const old = await database.query("select payload from sync_library_changes('1970-01-01',500) where entity_id=$1", [watched])
  assert.equal(Object.hasOwn(old.rows[0].payload, 'notes'), false, 'pre-migration RPC drops watch notes')
  await database.exec(migration)
  await database.exec('grant select on all tables in schema public to authenticated; grant delete on public.episode_watch_events to authenticated; set role authenticated;')
}, { timeout: 60000 })
after(async () => database.close())

async function watchRows() {
  return (await database.query("select * from sync_library_changes('1970-01-01',500) where entity_type='episode_watch_event'")).rows
}

test('existing dated watch includes notes and stable identity after migration', async () => {
  const row = (await watchRows()).find((item) => item.entity_id === watched)
  assert.equal(row.payload.id, watched)
  assert.equal(row.payload.episodeId, episode)
  assert.equal(row.payload.watchedAt, '2026-01-03')
  assert.equal(row.payload.notes, 'With family')
})

test('pre-platform date and absent notes remain explicit null values', async () => {
  const row = (await watchRows()).find((item) => item.entity_id === undated)
  assert.equal(row.payload.watchedAt, null)
  assert.equal(row.payload.notes, null)
  assert.ok(Object.hasOwn(row.payload, 'notes'))
})

test('notes payload does not expose another account watch', async () => {
  assert.equal((await watchRows()).some((row) => row.entity_id === foreignWatch), false)
  await database.query("select set_config('request.jwt.claim.sub',$1,false)", [other])
  try {
    assert.deepEqual((await watchRows()).map((row) => row.entity_id), [foreignWatch])
  } finally {
    await database.query("select set_config('request.jwt.claim.sub',$1,false)", [owner])
  }
})

test('watch deletion still syncs an exact-event tombstone and preserves other watches', async () => {
  await database.query('delete from episode_watch_events where id=$1', [watched])
  const rows = await watchRows()
  assert.equal(rows.some((row) => row.entity_id === watched), false)
  const deleted = (await database.query("select * from sync_library_changes('1970-01-01',500) where entity_type='tombstone' and entity_id=$1", [watched])).rows[0]
  assert.equal(deleted.payload.entityType, 'episode_watch_event')
  assert.equal(rows.find((row) => row.entity_id === undated).payload.id, undated)
  await database.exec('reset role')
  await database.exec(migration) // repeatable deployment
  await database.exec('set role authenticated')
  assert.equal((await watchRows()).find((row) => row.entity_id === undated).payload.notes, null)
})

test('only authenticated can execute either function and private definer has fixed search path', async () => {
  for (const schema of ['public', 'cinemarchive_private']) {
    const name = `${schema}.sync_library_changes(timestamp with time zone,integer)`
    const rows = (await database.query(`select p.prosecdef,p.proconfig,
      has_function_privilege('authenticated',$1,'execute') as authenticated,
      has_function_privilege('anon',$1,'execute') as anon,
      exists(select 1 from aclexplode(p.proacl) where grantee=0 and privilege_type='EXECUTE') as public_execute
      from pg_proc p where p.oid=$1::regprocedure`, [name])).rows
    assert.equal(rows[0].prosecdef, schema === 'cinemarchive_private')
    assert.deepEqual(rows[0].proconfig, ['search_path=""'])
    assert.equal(rows[0].authenticated, true)
    assert.equal(rows[0].anon, false)
    assert.equal(rows[0].public_execute, false)
  }
})

test('anonymous RPC access is denied even when a subject setting is present', async () => {
  await database.exec('reset role; set role anon')
  try {
    await assert.rejects(database.query("select * from public.sync_library_changes('1970-01-01',500)"), { code: '42501' })
  } finally {
    await database.exec('reset role; set role authenticated')
  }
})

test('canonical schema contains the complete secured migration', () => {
  assert.ok(canonicalSchema.includes(migration.replaceAll('\r\n', '\n').trim()))
})

test('existing title sync includes tags, studios and franchise identity without rewriting rows', async () => {
  const row = (await database.query("select payload from sync_library_changes('1970-01-01',500) where entity_id=$1", [ownerTitle])).rows[0]
  assert.deepEqual(row.payload.tags, ['family', 'rewatch'])
  assert.deepEqual(row.payload.studios, ['Example Studio'])
  assert.equal(row.payload.collectionId, 42)
  assert.equal(row.payload.collectionName, 'Example Collection')
})

test('metadata remains owner scoped and unknown franchise fields are explicitly null', async () => {
  await database.query("select set_config('request.jwt.claim.sub',$1,false)", [other])
  try {
    const rows = (await database.query("select entity_id,payload from sync_library_changes('1970-01-01',500) where entity_type='title'")).rows
    assert.equal(rows.length, 1)
    assert.notEqual(rows[0].entity_id, ownerTitle)
    assert.deepEqual(rows[0].payload.tags, [])
    assert.deepEqual(rows[0].payload.studios, [])
    assert.equal(rows[0].payload.collectionId, null)
    assert.equal(rows[0].payload.collectionName, null)
  } finally { await database.query("select set_config('request.jwt.claim.sub',$1,false)", [owner]) }
})

test('clearing tags, studios and a franchise is visible on the next sync', async () => {
  await database.exec('reset role')
  await database.query("update titles set tags='{}', studios='{}', collection_id=null, collection_name=null where id=$1", [ownerTitle])
  await database.exec('set role authenticated')
  const row = (await database.query("select payload from sync_library_changes('1970-01-01',500) where entity_id=$1", [ownerTitle])).rows[0]
  assert.deepEqual(row.payload.tags, [])
  assert.deepEqual(row.payload.studios, [])
  assert.equal(row.payload.collectionId, null)
  assert.equal(row.payload.collectionName, null)
})
