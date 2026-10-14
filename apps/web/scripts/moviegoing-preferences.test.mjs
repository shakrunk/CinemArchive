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
const migration = (await readFile(new URL('supabase/migrations/20261008233351_moviegoing_preferences.sql', root), 'utf8')).replaceAll('\r\n', '\n')
const schema = (await readFile(new URL('schema.sql', root), 'utf8')).replaceAll('\r\n', '\n')

async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
async function command(operations, id = randomUUID()) {
  return (await db.query('select public.apply_library_command($1,$2) as result', [id, JSON.stringify(operations)])).rows[0].result
}
const venue = (action, name, notes, guard = {}) => ({ table: 'venue_notes', action, key: { venue: name }, ...(notes === undefined ? {} : { values: { notes } }), ...guard })
const interest = (action, id = title) => ({ table: 'theater_interest', action, key: { id }, ...(action === 'insert' ? { values: { title_id: id } } : {}) })
const feed = async () => (await db.query("select * from public.sync_library_changes('1970-01-01',500)")).rows

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
  await db.exec(schema.split('-- Private moviegoing preferences (20261008233351).')[0])
  await db.query('insert into auth.users(id,email) values($1,$2),($3,$4)', [owner, 'owner@example.test', other, 'other@example.test'])
  await db.query("insert into titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,42,'movie','Film',2026,'watchlist'),($3,$4,43,'movie','Other film',2026,'watchlist')", [title, owner, otherTitle, other])
  await db.exec(migration)
  await as('authenticated', owner)
}, { timeout: 60000 })
after(async () => db.close())

test('same-owner natural venue identity preserves case, accepts apostrophes and replays immutable receipts', async () => {
  const id = randomUUID(), op = venue('insert', "O'Brien Cinema", 'Park opposite')
  const first = await command([op], id)
  assert.equal(first.rows[0].row.notes, 'Park opposite')
  assert.equal(first.rows[0].row.user_id, owner)
  assert.deepEqual(await command([op], id), first)
  assert.equal((await command([op])).rows[0].row.id, first.rows[0].row.id)
  await command([venue('insert', "o'brien cinema", 'Different name')])
  assert.equal((await db.query('select count(*)::integer as n from venue_notes')).rows[0].n, 2)
  await assert.rejects(command([venue('insert', "O'Brien Cinema", 'Changed body')], id), /Operation ID reused/)
})

test('competing first-save notes require review and leave no accepted receipt', async () => {
  await command([venue('insert', 'Race venue', 'Device one')])
  const id = randomUUID()
  await assert.rejects(command([venue('insert', 'Race venue', 'Device two')], id), /compare before replacing/)
  assert.equal((await db.query("select notes from venue_notes where venue='Race venue'")).rows[0].notes, 'Device one')
  await as('postgres')
  assert.equal((await db.query('select count(*)::integer as n from cinemarchive_private.library_command_receipts where operation_id=$1', [id])).rows[0].n, 0)
  await as('authenticated', owner)
})

test('note edits require literal or causal guards and retain accepted history on retry', async () => {
  const firstId = randomUUID(), first = await command([venue('insert', 'Guarded venue', 'Old')], firstId)
  await assert.rejects(command([venue('update', 'Guarded venue', 'Blind')]), /observed revision/)
  await assert.rejects(command([venue('delete', 'Guarded venue')]), /observed revision/)
  const editId = randomUUID(), edit = venue('update', 'Guarded venue', '', { expectedOperationId: firstId })
  const updated = await command([edit], editId)
  assert.equal(updated.rows[0].row.notes, '')
  assert.equal(updated.rows[0].row.id, first.rows[0].row.id)
  await assert.rejects(command([venue('update', 'Guarded venue', 'Stale', { expectedUpdatedAt: first.rows[0].row.updated_at })]), /changed on another device/)
  await command([venue('delete', 'Guarded venue', undefined, { expectedOperationId: editId })])
  assert.deepEqual(await command([edit], editId), updated)
  assert.equal((await db.query("select count(*)::integer as n from venue_notes where venue='Guarded venue'")).rows[0].n, 0)
  const tombstone = (await feed()).find(r => r.entity_id === first.rows[0].row.id)
  assert.equal(tombstone.payload.entityType, 'venue_note')
})

test('private tables and sync expose only the authenticated owner; direct writes are denied', async () => {
  await command([venue('insert', 'Private venue', 'SECRET owner note'), interest('insert')])
  await as('authenticated', other)
  try {
    assert.equal((await db.query('select count(*)::integer as n from venue_notes')).rows[0].n, 0)
    assert.equal((await db.query('select count(*)::integer as n from theater_interest')).rows[0].n, 0)
    assert.equal((await feed()).some(r => ['venue_note', 'theater_interest'].includes(r.entity_type)), false)
    await command([venue('insert', 'Private venue', 'Other owner note'), interest('insert', otherTitle)])
    await assert.rejects(command([interest('insert')]), /Title ownership required/)
    await assert.rejects(command([{ ...venue('insert', 'Injected', 'No'), values: { notes: 'No', user_id: owner } }]), /Unsupported library fields/)
  } finally { await as('authenticated', owner) }
  await assert.rejects(db.query("insert into venue_notes(user_id,venue,notes) values($1,'Direct','No')", [owner]), /permission denied/)
  await assert.rejects(db.query("delete from theater_interest where id=$1", [title]), /permission denied/)
  assert.equal((await db.query("select notes from venue_notes where venue='Private venue'")).rows[0].notes, 'SECRET owner note')
})

test('anonymous callers cannot read tables or invoke either command entrypoint', async () => {
  await as('anon')
  try {
    await assert.rejects(db.query('select * from venue_notes'), /permission denied/)
    await assert.rejects(db.query('select * from theater_interest'), /permission denied/)
    await assert.rejects(command([interest('insert')]), /permission denied/)
    await assert.rejects(db.query('select cinemarchive_private.apply_library_command($1,$2)', [randomUUID(), JSON.stringify([interest('insert')])]), /permission denied/)
  } finally { await as('authenticated', owner) }
})

test('interest is desired presence at the exact title identity, with retry and tombstones', async () => {
  const insertId = randomUUID(), inserted = await command([interest('insert')], insertId)
  assert.equal(inserted.rows[0].row.id, title)
  assert.equal(inserted.rows[0].row.title_id, title)
  assert.equal((await command([interest('insert')])).rows[0].row.id, title)
  await assert.rejects(command([{ ...interest('insert'), values: { title_id: otherTitle } }]), /exact owned title identity/)
  await assert.rejects(command([{ ...interest('insert'), action: 'update' }]), /exact owned title identity/)
  const deleteId = randomUUID(), removed = await command([interest('delete')], deleteId)
  assert.deepEqual(await command([interest('delete')], deleteId), removed)
  assert.equal((await db.query('select count(*)::integer as n from theater_interest')).rows[0].n, 0)
  assert.deepEqual(await command([interest('insert')], insertId), inserted, 'historical receipt must not resurrect removed interest')
  assert.equal((await db.query('select count(*)::integer as n from theater_interest')).rows[0].n, 0)
  const deleted = (await feed()).find(r => r.entity_id === title && r.entity_type === 'tombstone')
  assert.equal(deleted.payload.entityType, 'theater_interest')
})

test('owner feed carries capability, exact names and empty notes without changing title revision', async () => {
  const before = (await feed()).find(r => r.entity_type === 'title' && r.entity_id === title)
  const receipt = await command([venue('insert', 'Empty note', ''), interest('insert')])
  const rows = await feed()
  const note = rows.find(r => r.entity_id === receipt.rows[0].row.id)
  assert.equal(note.entity_type, 'venue_note')
  assert.equal(note.payload.venue, 'Empty note')
  assert.equal(note.payload.notes, '')
  assert.equal(note.payload.moviegoingPreferencesVersion, 1)
  const intent = rows.find(r => r.entity_type === 'theater_interest' && r.entity_id === title)
  assert.equal(intent.parent_id, title)
  assert.equal(intent.payload.titleId, title)
  assert.equal(intent.payload.moviegoingPreferencesVersion, 1)
  const after = rows.find(r => r.entity_type === 'title' && r.entity_id === title)
  assert.equal(after.payload.moviegoingPreferencesVersion, 1)
  assert.deepEqual(after.updated_at, before.updated_at)
})

test('invalid note data rolls back a whole compound command', async () => {
  await assert.rejects(command([venue('insert', 'Should roll back', 'first'), venue('insert', ' ', 'second')]), /check constraint/)
  assert.equal((await db.query("select count(*)::integer as n from venue_notes where venue='Should roll back'")).rows[0].n, 0)
  await assert.rejects(command([venue('insert', 'Too long', 'x'.repeat(20001))]), /check constraint/)
  await assert.rejects(command([venue('insert', 'Null', null)]), /require text/)
  await assert.rejects(command([{ ...venue('insert', 'Unsupported', 'x'), action: 'put' }]), /require text/)
})

test('title removal cascades interest with owner-private deletion evidence', async () => {
  await as('postgres')
  await db.query('delete from titles where id=$1', [title])
  await as('authenticated', owner)
  assert.equal((await db.query('select count(*)::integer as n from theater_interest')).rows[0].n, 0)
  const deleted = (await feed()).filter(r => r.entity_id === title && r.entity_type === 'tombstone')
  assert.ok(deleted.some(r => r.payload.entityType === 'theater_interest'))
  assert.ok(deleted.some(r => r.payload.entityType === 'title'))
  assert.ok((await db.query("select * from venue_notes where venue='Private venue'")).rows.length > 0)
})

test('canonical schema includes the deployable migration verbatim', () => assert.ok(schema.includes(migration)))
