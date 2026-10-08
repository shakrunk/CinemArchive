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
const migrationName = '20261008222209_guarded_ticket_outing_dependencies.sql'
async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
async function rpc(name, args) {
  return (await db.query(`select public.${name}(${args.map((_, i) => `$${i + 1}`).join(',')}) as r`, args)).rows[0].r
}
async function outing() {
  await as('postgres')
  const id = randomUUID(), title = randomUUID()
  await db.query("insert into titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,$3,'movie','Ticket guard',2020,'watchlist')", [title, owner, Math.floor(Math.random() * 1e8)])
  await db.query("insert into cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes,venue) values($1,$2,$3,'2020-01-01T01:00:00Z','2020-01-01T03:00:00Z',120,'Original')", [id, owner, title])
  await as('authenticated', owner)
  return current(id)
}
async function current(id) { return (await db.query('select to_jsonb(o) as r from cinema_outings o where id=$1', [id])).rows[0].r }
async function photo(o) {
  const id = randomUUID(), metadata = { mimeType: 'image/png', byteLength: 8, sha256: 'a'.repeat(64), barcode: null }
  await rpc('prepare_ticket_attachment', [id, o.id, metadata])
  await db.query("insert into storage.objects(bucket_id,name,owner_id,metadata) values('ticket-attachments',$1,$2,$3)", [`${owner}/${id}/original`, owner, { size: 8, mimetype: 'image/png' }])
  return id
}
async function attach(o, id, { operation = randomUUID(), previous = null, version = o.updated_at, dependency = null } = {}) {
  return rpc('finalize_ticket_attachment', [operation, o.id, id, previous, version, dependency])
}
function patch(o, dependency, venue = 'Queued venue') {
  return [{ table: 'cinema_outings', action: 'update', key: { id: o.id }, values: { venue }, expectedOperationId: dependency }]
}
async function installCausalDefinition(name) {
  const text = await readFile(new URL(`supabase/migrations/${name}`, root), 'utf8')
  const start = text.indexOf('create or replace function cinemarchive_private.apply_causal_library_command(')
  assert.ok(start >= 0)
  await as('postgres')
  await db.exec(text.slice(start, text.indexOf('\n$$;', start) + 4))
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
  const migration = (await readFile(new URL(`supabase/migrations/${migrationName}`, root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(migration.trim()))
  await db.exec(schema)
  await db.exec('grant select,update,delete on cinema_outings,titles,viewings to authenticated')
  for (const id of [owner, other]) await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
}, { timeout: 60000 })
after(async () => db.close())

test('guarded ticket receipt authorizes its own following edit but never an intervening device change', async () => {
  const o = await outing(), id = await photo(o)
  const ticket = await attach(o, id)
  assert.equal(ticket.outingRevisionGuarded, true)
  assert.deepEqual(ticket.request, { kind: 'ticket.attach', outingId: o.id, attachmentId: id, expectedAttachmentId: null,
    expectedUpdatedAt: o.updated_at, expectedOperationId: null })
  const first = await rpc('apply_library_command', [randomUUID(), patch(o, ticket.operationId)])
  assert.equal(first.rows[0].row.venue, 'Queued venue')
  await assert.rejects(rpc('apply_library_command', [randomUUID(), patch(o, ticket.operationId, 'Stale edit')]), { code: '40001' })
  assert.equal((await current(o.id)).ticket_attachment_id, id)
})

test('unseen venue change conflicts before ticket publication and retains prepared bytes and original intent', async () => {
  const o = await outing(), id = await photo(o), operation = randomUUID()
  await db.query("update cinema_outings set venue='Other device' where id=$1", [o.id])
  await assert.rejects(attach(o, id, { operation }), { code: '40001' })
  assert.equal(await rpc('get_ticket_command_receipt', [operation]), null)
  assert.equal((await current(o.id)).ticket_attachment_id, null)
  assert.equal((await rpc('prepare_ticket_attachment', [id, o.id, { mimeType: 'image/png', byteLength: 8, sha256: 'a'.repeat(64), barcode: null }])).state, 'prepared')
  const accepted = await attach(await current(o.id), id)
  await db.query("update cinema_outings set venue='Later again' where id=$1", [o.id])
  assert.deepEqual(await attach(o, id, { operation: accepted.operationId, version: accepted.request.expectedUpdatedAt }), accepted)
  assert.equal((await current(o.id)).venue, 'Later again')
})

test('causal outing edit, ticket attach, completion and ticket detach retain exact receipt ordering', async () => {
  const o = await outing(), id = await photo(o), edit = randomUUID()
  await rpc('apply_library_command', [edit, [{ table: 'cinema_outings', action: 'update', key: { id: o.id }, values: { venue: 'Planned venue' }, expectedUpdatedAt: o.updated_at }]])
  const ticket = await attach(o, id, { version: null, dependency: edit })
  assert.equal(ticket.request.expectedOperationId, edit)
  const complete = await rpc('complete_cinema_outing', [o.id, randomUUID(), randomUUID(), null, 'UTC', ticket.operationId])
  assert.equal(complete.status, 'applied')
  assert.equal(complete.viewing.venue, 'Planned venue')
  const detached = await rpc('detach_ticket_attachment', [randomUUID(), o.id, id, null, complete.operationId])
  assert.equal(detached.outingRevisionGuarded, true)
  const reverted = await rpc('revert_cinema_outing', [o.id, randomUUID(), null, complete.canonicalViewingId, complete.viewing.updated_at, detached.operationId, null])
  assert.equal(reverted.status, 'applied')
  assert.equal(reverted.outing.status, 'missed')
})

test('legacy association-only tickets still retry but cannot authorize generic edits, completion or reversal', async () => {
  const o = await outing(), id = await photo(o), op = randomUUID()
  const legacy = await rpc('finalize_ticket_attachment', [op, o.id, id, null])
  assert.equal(legacy.outingRevisionGuarded, false)
  assert.equal(Object.hasOwn(legacy.request, 'expectedUpdatedAt'), false)
  await assert.rejects(rpc('apply_library_command', [randomUUID(), patch(o, op)]), { code: '40001' })
  await assert.rejects(rpc('complete_cinema_outing', [o.id, randomUUID(), randomUUID(), null, 'UTC', op]), { code: '40001' })
  await assert.rejects(rpc('revert_cinema_outing', [o.id, randomUUID(), null, null, null, op, null]), { code: '40001' })
  await assert.rejects(rpc('detach_ticket_attachment', [randomUUID(), o.id, id, null, op]), { code: '40001' })
  await db.query("update cinema_outings set venue='Keep newer venue' where id=$1", [o.id])
  assert.deepEqual(await rpc('finalize_ticket_attachment', [op, o.id, id, null]), legacy)
  assert.equal((await current(o.id)).venue, 'Keep newer venue')
})

test('changed guards cannot reuse an accepted operation and malformed or foreign dependencies fail', async () => {
  const o = await outing(), id = await photo(o), operation = randomUUID()
  const first = await attach(o, id, { operation })
  await assert.rejects(attach(o, id, { operation, version: first.outingUpdatedAt }), { code: '22023' })
  await assert.rejects(attach(o, id, { operation, version: null }), { code: '22023' })
  await assert.rejects(attach(o, id, { dependency: randomUUID() }), { code: '22023' })
  const self = randomUUID()
  await assert.rejects(attach(o, id, { operation: self, version: null, dependency: self }), { code: '22023' })
  await assert.rejects(attach(o, id, { version: 'infinity' }), { code: '22023' })
  const second = await outing(), secondId = await photo(second)
  await assert.rejects(attach(second, secondId, { version: null, dependency: operation }), { code: '40001' })
  await as('authenticated', other)
  await assert.rejects(attach(second, secondId, { version: null, dependency: operation }), { code: '40001' })
  await assert.rejects(db.query('select cinemarchive_private.library_causal_revision($1,$2,$3,true)', [operation, 'cinema_outings', { id: o.id }]), { code: '42501' })
  await as('anon', owner)
  await assert.rejects(attach(o, id), { code: '42501' })
})

test('already accepted old dependent commands retry unchanged while fresh and rebound commands are rejected', async () => {
  const o = await outing(), id = await photo(o), ticketOp = randomUUID(), editOp = randomUUID()
  const ticket = await rpc('finalize_ticket_attachment', [ticketOp, o.id, id, null])
  const operations = patch(o, ticketOp)
  let accepted
  try {
    await installCausalDefinition('20261008180456_library_command_import_capacity.sql')
    await as('authenticated', owner)
    accepted = await rpc('apply_library_command', [editOp, operations])
  } finally { await installCausalDefinition(migrationName) }
  await as('authenticated', owner)
  await db.query("update cinema_outings set venue='Latest owner venue' where id=$1", [o.id])
  assert.deepEqual(await rpc('apply_library_command', [editOp, operations]), accepted)
  await assert.rejects(rpc('apply_library_command', [randomUUID(), operations]), { code: '40001' })
  await assert.rejects(rpc('apply_library_command', [editOp, patch(o, ticketOp, 'Changed payload')]), { code: '22023' })
  assert.deepEqual(await rpc('finalize_ticket_attachment', [ticketOp, o.id, id, null]), ticket)
  assert.equal((await current(o.id)).venue, 'Latest owner venue')
})
