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
const stale = '2000-01-01T00:00:00Z'
async function as(user, role = 'authenticated') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  await db.exec(`set role ${role}`)
}
async function rpc(name, args) {
  return (await db.query(`select public.${name}(${args.map((_, i) => `$${i + 1}`).join(',')}) as r`, args)).rows[0].r
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
  const migration = (await readFile(new URL('supabase/migrations/20261014022000_http_revision_conflicts.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(migration.trim()))
  await db.exec(schema)
  await db.exec('grant select on titles,cinema_outings to authenticated')
  for (const id of [owner, other]) await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
}, { timeout: 60000 })
after(async () => db.close())

async function fixture() {
  await as(owner)
  const title = randomUUID(), outing = randomUUID()
  const request = [{ table: 'titles', action: 'insert', key: { id: title }, values: {
    tmdb_id: Math.floor(Math.random() * 1e8), type: 'movie', title: 'HTTP conflict', year: 2026, status: 'watchlist',
  } }, { table: 'cinema_outings', action: 'insert', key: { id: outing }, values: {
    title_id: title, showtime: '2020-01-01T01:00:00Z', ends_at: '2020-01-01T03:00:00Z', runtime_minutes: 120, venue: 'Original',
  } }]
  const operation = randomUUID()
  const receipt = await rpc('apply_library_command', [operation, request])
  return { title, outing, request, operation, receipt }
}

test('public CAS returns PT409, rolls back earlier effects and preserves accepted retries', async () => {
  const f = await fixture(), operation = randomUUID()
  const ops = [{ table: 'titles', action: 'update', key: { id: f.title }, values: { notes: 'Must roll back' } },
    { table: 'cinema_outings', action: 'update', key: { id: f.outing }, values: { venue: 'Stale' }, expectedUpdatedAt: stale }]
  await assert.rejects(rpc('apply_library_command', [operation, ops]), { code: 'PT409', message: 'Library record changed on another device' })
  assert.equal((await db.query('select notes from titles where id=$1', [f.title])).rows[0].notes, null)
  assert.equal(await rpc('get_ticket_command_receipt', [operation]), null)
  assert.deepEqual(await rpc('apply_library_command', [f.operation, f.request]), f.receipt)
  await assert.rejects(db.query('select cinemarchive_private.apply_causal_library_command($1,$2)', [operation, JSON.stringify(ops)]), { code: '40001' })
})

test('outing resolution, completion and reversal translate stale revisions without mutating the graph', async () => {
  const f = await fixture()
  const outing = f.receipt.rows[1].row
  assert.equal((await rpc('resolve_outing_fields', [f.outing, stale, { venue: 'Stale' }, randomUUID()])).status, 'conflict')
  assert.equal((await rpc('complete_cinema_outing', [f.outing, randomUUID(), randomUUID(), stale, 'UTC', null])).status, 'conflict')
  await assert.rejects(rpc('complete_cinema_outing', [f.outing, randomUUID(), randomUUID(), null, 'UTC', randomUUID()]), { code: 'PT409' })
  const completed = await rpc('complete_cinema_outing', [f.outing, randomUUID(), randomUUID(), outing.updated_at, 'UTC', null])
  await assert.rejects(rpc('revert_cinema_outing', [f.outing, randomUUID(), null, completed.canonicalViewingId, completed.viewing.updated_at, randomUUID(), null]), { code: 'PT409' })
  assert.equal((await db.query('select status from cinema_outings where id=$1', [f.outing])).rows[0].status, 'completed')
})

test('ticket association and detach translate stale guards while retaining preparation and no receipt', async () => {
  const f = await fixture(), attachment = randomUUID(), operation = randomUUID()
  const metadata = { mimeType: 'image/png', byteLength: 8, sha256: 'a'.repeat(64), barcode: null }
  const prepared = await rpc('prepare_ticket_attachment', [attachment, f.outing, metadata])
  await assert.rejects(rpc('finalize_ticket_attachment', [operation, f.outing, attachment, null, stale, null]), { code: 'PT409' })
  await assert.rejects(rpc('detach_ticket_attachment', [randomUUID(), f.outing, null, stale, null]), { code: 'PT409' })
  assert.equal(await rpc('get_ticket_command_receipt', [operation]), null)
  assert.deepEqual(await rpc('prepare_ticket_attachment', [attachment, f.outing, metadata]), prepared)
})

test('unrelated validation, owner boundaries and anonymous grants retain their original errors', async () => {
  const f = await fixture()
  await assert.rejects(rpc('apply_library_command', [f.operation, []]), { code: '22023' })
  await as(other)
  await assert.rejects(rpc('apply_library_command', [randomUUID(), [{ table: 'viewings', action: 'insert', key: { id: randomUUID() }, values: { title_id: f.title } }]]), { code: '42501' })
  await as('', 'anon')
  await assert.rejects(rpc('apply_library_command', [randomUUID(), []]), { code: '42501' })
})
