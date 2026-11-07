import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { createStorageFixture } from './storage-fixture.mjs'

// Outing keepsake photos (#208): docs/android-contracts/outing-keepsakes.md.
const db = new PGlite({ extensions: { pgcrypto } })
const root = new URL('../../../', import.meta.url)
const owner = randomUUID(), other = randomUUID(), title = randomUUID(), outing = randomUUID(), otherOuting = randomUUID()
const HEADER = '-- Outing keepsake photos (20261030000000).'

async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
const key = (id, user = owner) => `${user}/${id}`
async function upload(id, { user = owner, size = 1234, mime = 'image/jpeg', name = key(id, user) } = {}) {
  await db.query("insert into storage.objects(bucket_id,name,owner_id,metadata) values('outing-keepsakes',$1,$2,$3)",
    [name, user, { size, mimetype: mime }])
}
async function add(id, { user = owner, outingId = outing, size = 1234, mime = 'image/jpeg', objectKey = key(id, user) } = {}) {
  return (await db.query('insert into public.outing_keepsakes(id,user_id,outing_id,object_key,mime_type,byte_length) values($1,$2,$3,$4,$5,$6) returning *',
    [id, user, outingId, objectKey, mime, size])).rows[0]
}
async function photo(options) {
  const id = randomUUID()
  await upload(id, options)
  return add(id, options)
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
  const schema = (await readFile(new URL('schema.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  const migration = (await readFile(new URL('supabase/migrations/20261030000000_outing_keepsakes.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(migration.trim()), 'canonical schema includes the complete keepsake migration')
  await db.exec(schema.split(HEADER)[0])
  await db.exec(migration)
  await db.exec('grant select,insert,update,delete on public.cinema_outings to authenticated; grant usage on schema cinemarchive_private to service_role')
  for (const id of [owner, other]) await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
  await db.query("insert into public.titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,901,'movie','Keepsake film',2026,'watchlist')", [title, owner])
  await db.query(`insert into public.cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes,venue)
    values($1,$2,$3,now()+interval '1 day',now()+interval '1 day 2 hours',120,'Cinema')`, [outing, owner, title])
  await db.query("insert into public.titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,902,'movie','Other film',2026,'watchlist')", [randomUUID(), other])
  const otherTitle = (await db.query('select id from public.titles where user_id=$1', [other])).rows[0].id
  await db.query(`insert into public.cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes,venue)
    values($1,$2,$3,now()+interval '1 day',now()+interval '1 day 2 hours',120,'Other cinema')`, [otherOuting, other, otherTitle])
}, { timeout: 60000 })
after(async () => db.close())

test('an owner uploads, adds and reads their own keepsake; nobody else sees the row or the bytes', async () => {
  await as('authenticated', owner)
  const row = await photo()
  assert.equal(row.outing_id, outing)
  assert.equal((await db.query('select * from public.outing_keepsakes')).rows.length, 1)
  assert.equal((await db.query("select * from storage.objects where bucket_id='outing-keepsakes'")).rows.length, 1)
  await as('authenticated', other)
  assert.equal((await db.query('select * from public.outing_keepsakes')).rows.length, 0)
  assert.equal((await db.query("select * from storage.objects where bucket_id='outing-keepsakes'")).rows.length, 0)
  await as('anon')
  await assert.rejects(db.query('select * from public.outing_keepsakes'), { code: '42501' })
  assert.equal((await db.query("select * from storage.objects where bucket_id='outing-keepsakes'")).rows.length, 0)
})

test('uploads are limited to the caller\'s own folder with a UUID name, and never overwrite', async () => {
  await as('authenticated', owner)
  const id = randomUUID()
  await assert.rejects(upload(id, { user: other }), { code: '42501' })
  await assert.rejects(upload(id, { name: `${owner}/not-a-uuid` }), { code: '42501' })
  await assert.rejects(upload(id, { name: `${owner}/${id}/extra` }), { code: '42501' })
  await assert.rejects(upload(id, { name: `${id}` }), { code: '42501' })
  await upload(id)
  await assert.rejects(upload(id), { code: '23505' })
  assert.equal((await db.query("update storage.objects set metadata='{}' where name=$1 returning id", [key(id)])).rows.length, 0)
  assert.equal((await db.query('delete from storage.objects where name=$1 returning id', [key(id)])).rows.length, 0)
  await as('anon')
  await assert.rejects(upload(randomUUID()), { code: '42501' })
})

test('a row needs a matching stored object, an owned outing and a well-formed key', async () => {
  await as('authenticated', owner)
  const missing = randomUUID()
  await assert.rejects(add(missing), { code: '22023' })
  const wrongSize = randomUUID(); await upload(wrongSize, { size: 10 })
  await assert.rejects(add(wrongSize, { size: 11 }), { code: '22023' })
  const wrongType = randomUUID(); await upload(wrongType, { mime: 'image/png' })
  await assert.rejects(add(wrongType, { mime: 'image/jpeg' }), { code: '22023' })
  const badKey = randomUUID(), elsewhere = randomUUID(); await upload(elsewhere)
  await assert.rejects(add(badKey, { objectKey: key(elsewhere) }), { code: '23514' })
  for (const invalid of [{ size: 0 }, { size: 10485761 }, { mime: 'text/html' }]) {
    const id = randomUUID(); await upload(id, invalid)
    await assert.rejects(add(id, invalid), { code: '23514' })
  }
  const foreignOuting = randomUUID(); await upload(foreignOuting)
  await assert.rejects(add(foreignOuting, { outingId: otherOuting }), { code: '42501' })
  const asOther = randomUUID(); await upload(asOther)
  await assert.rejects(add(asOther, { user: other }), { code: '42501' })
})

test('a row cannot point at another user\'s bytes', async () => {
  await as('authenticated', other)
  const theirs = randomUUID(); await upload(theirs, { user: other })
  await as('authenticated', owner)
  await assert.rejects(add(randomUUID(), { objectKey: key(theirs, other) }), { code: '23514' })
})

test('an outing keeps at most eight photos', async () => {
  await db.query('reset role')
  await db.query('delete from public.outing_keepsakes')
  await as('authenticated', owner)
  for (let i = 0; i < 8; i++) await photo()
  const ninth = randomUUID(); await upload(ninth)
  await assert.rejects(add(ninth), { code: '54000' })
})

test('rows are immutable, and only the owner can delete their own', async () => {
  await db.query('reset role')
  await db.query('delete from public.outing_keepsakes')
  await as('authenticated', owner)
  const row = await photo()
  await assert.rejects(db.query("update public.outing_keepsakes set mime_type='image/png' where id=$1", [row.id]), { code: '42501' })
  await as('authenticated', other)
  assert.equal((await db.query('delete from public.outing_keepsakes where id=$1 returning id', [row.id])).rows.length, 0)
  await as('authenticated', owner)
  assert.equal((await db.query('delete from public.outing_keepsakes where id=$1 returning id', [row.id])).rows.length, 1)
  // The row is gone, so the bytes are unreadable at once, but only cleanup may remove them.
  assert.equal((await db.query('select * from storage.objects where name=$1', [row.object_key])).rows.length, 0)
  await db.query('reset role')
  assert.equal((await db.query('select * from storage.objects where name=$1', [row.object_key])).rows.length, 1)
})

test('the 500th object fills the folder', async () => {
  const filler = (await db.query("select count(*)::int as n from storage.objects where bucket_id='outing-keepsakes' and split_part(name,'/',1)=$1", [owner])).rows[0].n
  await db.query("insert into storage.objects(bucket_id,name,owner_id,metadata) select 'outing-keepsakes',$1||'/'||gen_random_uuid(),$1,'{}' from generate_series(1,$2)", [owner, 500 - filler])
  await as('authenticated', owner)
  await assert.rejects(upload(randomUUID()), { code: '42501' })
  await as('authenticated', other)
  await upload(randomUUID(), { user: other })
})

test('cleanup claims only old objects with no row, and only for the service role', async () => {
  await db.query('reset role')
  await db.query("delete from storage.objects where bucket_id='outing-keepsakes'")
  await db.query('delete from public.outing_keepsakes')
  await as('authenticated', owner)
  const kept = await photo()
  const orphan = randomUUID(); await upload(orphan)
  const removed = await photo()
  await db.query('delete from public.outing_keepsakes where id=$1', [removed.id])
  await db.query('reset role')
  assert.deepEqual((await db.query('select * from public.claim_keepsake_cleanup()')).rows, [], 'a fresh orphan waits a day')
  await db.query("update storage.objects set created_at = now() - interval '2 days' where bucket_id='outing-keepsakes'")
  await as('service_role')
  const claimed = (await db.query('select object_key from public.claim_keepsake_cleanup(100)')).rows.map((r) => r.object_key).sort()
  assert.deepEqual(claimed, [key(orphan), removed.object_key].sort())
  assert.ok(!claimed.includes(kept.object_key))
  await as('authenticated', owner)
  await assert.rejects(db.query('select * from public.claim_keepsake_cleanup()'), { code: '42501' })
  await as('anon')
  await assert.rejects(db.query('select * from public.claim_keepsake_cleanup()'), { code: '42501' })
})

test('deleting an outing removes its rows and leaves the bytes for cleanup', async () => {
  await db.query('reset role')
  await db.query('delete from public.outing_keepsakes')
  const lonely = randomUUID()
  await db.query(`insert into public.cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes,venue)
    values($1,$2,$3,now()+interval '2 days',now()+interval '2 days 2 hours',120,'Temp')`, [lonely, owner, title])
  await as('authenticated', owner)
  await db.query("delete from storage.objects where bucket_id='outing-keepsakes' and split_part(name,'/',1)=$1", [owner]).catch(() => {})
  await db.query('reset role')
  await db.query("delete from storage.objects where bucket_id='outing-keepsakes'")
  await as('authenticated', owner)
  const row = await photo({ outingId: lonely })
  await db.query('delete from public.cinema_outings where id=$1', [lonely])
  assert.equal((await db.query('select * from public.outing_keepsakes where id=$1', [row.id])).rows.length, 0)
  await db.query('reset role')
  assert.equal((await db.query('select * from storage.objects where name=$1', [row.object_key])).rows.length, 1)
})
