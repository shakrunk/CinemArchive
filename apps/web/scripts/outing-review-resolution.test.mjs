import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { createStorageFixture } from './storage-fixture.mjs'

const db = new PGlite({ extensions: { pgcrypto } })
const root = new URL('../../../', import.meta.url)
const owner = randomUUID(), other = randomUUID(), title = randomUUID(), outing = randomUUID(), friend = randomUUID()
async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
async function current() { return (await db.query('select to_jsonb(o) as row from public.cinema_outings o where id=$1', [outing])).rows[0]?.row }
async function resolve(patch, version, op = randomUUID(), id = outing) {
  return (await db.query('select public.resolve_outing_fields($1,$2,$3,$4) as result', [id, version, patch, op])).rows[0].result
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
  const migration = (await readFile(new URL('supabase/migrations/20261008194039_outing_review_resolution.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(migration.trim()))
  await db.exec(schema)
  await db.exec('grant select,update,delete on public.cinema_outings to authenticated; grant select on public.titles to authenticated;')
  for (const id of [owner, other]) await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
  await db.query("insert into titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,42,'movie','Review film',2026,'watchlist')", [title, owner])
  await db.query(`insert into cinema_outings(id,user_id,title_id,showtime,previews_minutes,runtime_minutes,ends_at,
    venue,notes,companions,ticket_image_path,ticket_barcode_payload)
    values($1,$2,$3,'2026-10-09T18:00Z',20,120,'2026-10-09T20:20Z','Current venue','Current notes',$4,'/private/legacy.jpg','sensitive code')`,
  [outing, owner, title, [{ name: 'Linked friend', friendUserId: friend }]])
  await as('authenticated', owner)
}, { timeout: 60000 })
after(async () => db.close())

test('selected fields apply atomically with explicit clears and preserve unselected/private state', async () => {
  const old = await current()
  const result = await resolve({ notes: null, venue: 'Reviewed venue' }, old.updated_at)
  assert.equal(result.status, 'applied')
  assert.equal(result.outing.notes, null)
  assert.equal(result.outing.venue, 'Reviewed venue')
  for (const key of ['id','user_id','title_id','companions','status','created_at','ticket_image_path','ticket_barcode_payload','ticket_attachment_id','ticket_attachment_managed']) {
    assert.deepEqual(result.outing[key], old[key], key)
  }
  assert.equal(result.rows[0].row.updated_at, result.outing.updated_at)
})

test('applied receipt survives later edits and forbids operation identity reuse', async () => {
  const old = await current(), op = randomUUID(), patch = { notes: 'Chosen notes' }
  const first = await resolve(patch, old.updated_at, op)
  await db.query("update cinema_outings set notes='Later edit' where id=$1", [outing])
  assert.deepEqual(await resolve(patch, old.updated_at, op), first)
  assert.equal((await current()).notes, 'Later edit')
  await assert.rejects(resolve({ notes: 'Different intent' }, old.updated_at, op), { code: '22023' })
})

test('stale comparison returns current row without overwriting and fresh operation can reapply', async () => {
  const old = await current()
  await db.query("update cinema_outings set venue='Other device' where id=$1", [outing])
  const conflict = await resolve({ venue: 'Stale choice' }, old.updated_at)
  assert.equal(conflict.status, 'conflict')
  assert.equal(conflict.outing.venue, 'Other device')
  const confirmed = await resolve({ venue: 'Reviewed again' }, conflict.outing.updated_at)
  assert.equal(confirmed.status, 'applied')
})

test('schedule edits recompute end time and invalid coupled values roll back with receipt', async () => {
  const before = await current()
  const scheduled = await resolve({ showtime: '2026-10-09T19:00:00+00:00', previews_minutes: 15, runtime_minutes: 90 }, before.updated_at)
  assert.equal(Date.parse(scheduled.outing.ends_at), Date.parse('2026-10-09T20:45:00Z'))
  const op = randomUUID()
  await assert.rejects(resolve({ venue: 'Must roll back', runtime_minutes: 0 }, scheduled.outing.updated_at, op), { code: '23514' })
  assert.deepEqual(await current(), scheduled.outing)
  const corrected = await resolve({ runtime_minutes: 100 }, scheduled.outing.updated_at, op)
  assert.equal(corrected.status, 'applied')
})

test('private, lifecycle, ownership and unsupported patch fields are rejected', async () => {
  const before = await current()
  for (const patch of [{ status: 'completed' }, { completed_viewing_id: randomUUID() }, { user_id: other },
    { ticket_image_path: '/stale/file' }, { ticket_barcode_payload: 'old code' }, { ticket_attachment_id: randomUUID() },
    { ends_at: '2026-10-10T00:00Z' }, { created_at: '2026-01-01' }, { venue: 8 }, { runtime_minutes: 1.5 },
    { seats: [12] }, { companions: ['Name only'] }, { companions: [{ name: 'Name', friendUserId: 'not-a-uuid' }] },
    { companions: [{ name: 'Name', hidden: true }] }]) {
    await assert.rejects(resolve(patch, before.updated_at), { code: '22023' })
  }
  assert.deepEqual(await current(), before)
})

test('explicit companion replacement preserves provided IDs and supports name-only recovery', async () => {
  const linked = [{ name: 'Same name', friendUserId: friend }, { name: 'Same name' }]
  const result = await resolve({ companions: linked }, (await current()).updated_at)
  assert.deepEqual(result.outing.companions, linked)
  const cleared = await resolve({ companions: [] }, result.outing.updated_at)
  assert.deepEqual(cleared.outing.companions, [])
})

test('no-op selection returns a retryable receipt without bumping the row version', async () => {
  const before = await current(), op = randomUUID()
  const result = await resolve({}, before.updated_at, op)
  assert.deepEqual(result.outing, before)
  assert.deepEqual(await resolve({}, before.updated_at, op), result)
})

test('foreign, missing and anonymous callers cannot discover or create a plan', async () => {
  const before = await current()
  await as('authenticated', other)
  assert.equal((await resolve({ notes: 'Foreign' }, before.updated_at)).status, 'missing')
  assert.equal((await resolve({}, before.updated_at)).outing, null)
  await as('authenticated', owner)
  assert.equal((await resolve({}, before.updated_at, randomUUID(), randomUUID())).status, 'missing')
  await as('anon', owner)
  await assert.rejects(resolve({}, before.updated_at), { code: '42501' })
  await as('authenticated')
  await assert.rejects(resolve({}, before.updated_at), { code: '42501' })
  await as('authenticated', owner)
  assert.deepEqual(await current(), before)
})

test('deleted plans stay absent while an earlier receipt remains recoverable', async () => {
  const before = await current(), op = randomUUID(), patch = { notes: 'Last reviewed notes' }
  const applied = await resolve(patch, before.updated_at, op)
  await db.query('delete from cinema_outings where id=$1', [outing])
  assert.deepEqual(await resolve(patch, before.updated_at, op), applied)
  assert.equal((await resolve(patch, before.updated_at)).status, 'missing')
  assert.equal(await current(), undefined)
})
