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
async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
async function makeOuting({ status = 'scheduled', showtime = '2020-01-02T01:00:00Z', titleStatus = 'watchlist' } = {}) {
  await as('postgres')
  const id = randomUUID(), title = randomUUID()
  await db.query("insert into titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,$3,'movie','Completion fixture',2020,$4)", [title, owner, Math.floor(Math.random() * 1e8), titleStatus])
  await db.query(`insert into cinema_outings(id,user_id,title_id,showtime,previews_minutes,runtime_minutes,ends_at,venue,companions,status)
    values($1,$2,$3,$4,20,100,$4::timestamptz+interval '120 minutes','Local cinema','["Legacy name",{"name":"Linked name"}]',$5)`, [id, owner, title, showtime, status])
  const row = (await db.query('select to_jsonb(o) as row from cinema_outings o where id=$1', [id])).rows[0].row
  await as('authenticated', owner)
  return row
}
async function complete(outing, { operation = randomUUID(), provisional = randomUUID(), timezone = 'America/Denver' } = {}) {
  return (await db.query('select public.complete_cinema_outing($1,$2,$3,$4,$5) as result', [outing.id, operation, provisional, outing.updated_at, timezone])).rows[0].result
}
async function countViewings(outingId) { return Number((await db.query('select count(*) as n from viewings where outing_id=$1', [outingId])).rows[0].n) }
async function revert(snapshot, operation = randomUUID()) {
  return (await db.query('select public.revert_cinema_outing($1,$2,$3,$4,$5) as result',
    [snapshot.outing.id, operation, snapshot.outing.updated_at, snapshot.canonicalViewingId, snapshot.viewing?.updated_at ?? null])).rows[0].result
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
  const migration = (await readFile(new URL('supabase/migrations/20261008194606_canonical_outing_completion.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(migration.trim()))
  const reversal = (await readFile(new URL('supabase/migrations/20261008195605_canonical_outing_revert.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(reversal.trim()))
  await db.exec(schema)
  await db.exec('grant select,update,delete on public.cinema_outings,public.titles,public.viewings to authenticated; grant select on public.notifications to authenticated;')
  for (const id of [owner, other]) await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
}, { timeout: 60000 })
after(async () => db.close())

test('native-first completion uses the stable provisional identity and captured local show date', async () => {
  const outing = await makeOuting(), operation = randomUUID(), provisional = randomUUID()
  const applied = await complete(outing, { operation, provisional })
  assert.equal(applied.status, 'applied')
  assert.equal(applied.canonicalViewingId, provisional)
  assert.equal(applied.viewing.viewed_at, '2020-01-01')
  assert.equal(applied.title.status, 'watched')
  assert.equal(applied.outing.previous_status, 'watchlist')
  assert.equal(await countViewings(outing.id), 1)
  assert.deepEqual(await complete(outing, { operation, provisional }), applied)
  const second = await complete(outing)
  assert.equal(second.status, 'already_completed')
  assert.equal(second.canonicalViewingId, provisional)
  assert.equal(await countViewings(outing.id), 1)
  assert.equal((await db.query("select payload from notifications where title_id=$1 and type='outing_completed'", [outing.title_id])).rows.length, 1)
  assert.deepEqual((await db.query('select payload from notifications where title_id=$1', [outing.title_id])).rows[0].payload.companions, ['Legacy name', 'Linked name'])
  assert.equal((await db.query("select * from complete_due_outings('America/Denver')")).rows.some(r => r.outing_id === outing.id), false)
})

test('web-first completion and native retry converge without another event or notification', async () => {
  const outing = await makeOuting()
  const web = (await db.query("select * from complete_due_outings('America/Denver')")).rows.find(r => r.outing_id === outing.id)
  assert.ok(web)
  const native = await complete(outing)
  assert.equal(native.status, 'already_completed')
  assert.equal(native.canonicalViewingId, web.viewing_id)
  assert.equal(await countViewings(outing.id), 1)
})

test('later viewing edits remain current in receipt replay and exact deletion never resurrects them', async () => {
  const outing = await makeOuting(), operation = randomUUID(), provisional = randomUUID()
  const first = await complete(outing, { operation, provisional })
  await db.query("update viewings set notes='Later notes',rating=4.5 where id=$1", [provisional])
  const replay = await complete(outing, { operation, provisional })
  assert.equal(replay.viewing.notes, 'Later notes')
  assert.equal(replay.viewing.rating, 4.5)
  assert.equal(replay.canonicalViewingId, first.canonicalViewingId)
  await db.query('delete from viewings where id=$1', [provisional])
  const removed = await complete(outing, { operation, provisional })
  assert.equal(removed.canonicalViewingId, provisional)
  assert.equal(removed.viewing, null)
  assert.equal(removed.outing.completed_viewing_id, null)
  const otherDevice = await complete(outing)
  assert.equal(otherDevice.canonicalViewingId, provisional)
  assert.equal(otherDevice.viewing, null)
  assert.equal(await countViewings(outing.id), 0)
})

test('receipt replay after outing deletion reports absence without recreating a plan or event', async () => {
  const outing = await makeOuting(), operation = randomUUID(), provisional = randomUUID()
  await complete(outing, { operation, provisional })
  await db.query('delete from cinema_outings where id=$1', [outing.id])
  const result = await complete(outing, { operation, provisional })
  assert.equal(result.canonicalViewingId, provisional)
  assert.equal(result.outing, null)
  assert.equal(result.viewing, null)
  assert.equal(result.title, null)
  assert.equal((await complete(outing)).status, 'missing')
})

test('rescheduled, future, cancelled and missed plans conflict without logging', async () => {
  const stale = await makeOuting()
  await db.query("update cinema_outings set venue='New revision' where id=$1", [stale.id])
  assert.equal((await complete(stale)).status, 'conflict')
  for (const options of [{ status: 'cancelled' }, { status: 'missed' }, { showtime: '2099-01-01T00:00:00Z' }]) {
    const outing = await makeOuting(options)
    assert.equal((await complete(outing)).status, 'conflict')
    assert.equal(await countViewings(outing.id), 0)
  }
})

test('ambiguous historical viewing is retained and never heuristically adopted or duplicated', async () => {
  const outing = await makeOuting(), existing = randomUUID()
  await as('postgres')
  await db.query("insert into viewings(id,user_id,title_id,outing_id,viewed_at,notes) values($1,$2,$3,$4,'2020-01-01','Keep historical event')", [existing, owner, outing.title_id, outing.id])
  await as('authenticated', owner)
  assert.equal((await complete(outing)).status, 'conflict')
  assert.equal(await countViewings(outing.id), 1)
  assert.equal((await db.query('select notes from viewings where id=$1', [existing])).rows[0].notes, 'Keep historical event')
})

test('legacy completed plan with deleted event stays complete and does not invent an identity', async () => {
  const outing = await makeOuting({ status: 'completed' })
  const result = await complete(outing)
  assert.equal(result.status, 'already_completed')
  assert.equal(result.canonicalViewingId, null)
  assert.equal(result.viewing, null)
  assert.equal(await countViewings(outing.id), 0)
})

test('operation collisions and invalid timezones cannot change an applied completion', async () => {
  const outing = await makeOuting(), operation = randomUUID(), provisional = randomUUID()
  await complete(outing, { operation, provisional })
  await assert.rejects(complete(outing, { operation, provisional, timezone: 'UTC' }), { code: '22023' })
  await assert.rejects(complete(outing, { operation, provisional: randomUUID() }), { code: '22023' })
  await assert.rejects(complete(outing, { timezone: 'Invalid/Zone' }), { code: '22023' })
  assert.equal(await countViewings(outing.id), 1)
})

test('provisional UUID collision rolls back outing, title and receipt', async () => {
  const first = await makeOuting(), second = await makeOuting(), provisional = randomUUID(), operation = randomUUID()
  await complete(first, { provisional })
  await assert.rejects(complete(second, { operation, provisional }), { code: '23505' })
  assert.equal(await countViewings(second.id), 0)
  assert.equal((await db.query('select status from cinema_outings where id=$1', [second.id])).rows[0].status, 'scheduled')
  const corrected = await complete(second, { operation })
  assert.equal(corrected.status, 'applied')
})

test('only authenticated owner can complete or inspect, and internal snapshot helper is inaccessible', async () => {
  const outing = await makeOuting()
  await as('authenticated', other)
  const foreign = await complete(outing)
  assert.equal(foreign.status, 'missing')
  assert.equal(foreign.outing, null)
  await as('anon', owner)
  await assert.rejects(complete(outing), { code: '42501' })
  await assert.rejects(db.query("select * from complete_due_outings('UTC')"), { code: '42501' })
  await as('authenticated')
  await assert.rejects(complete(outing), { code: '42501' })
  await as('authenticated', owner)
  await assert.rejects(db.query('select cinemarchive_private.outing_completion_current($1)', [{ outingId: outing.id }]), { code: '42501' })
  await assert.rejects(db.query('select * from cinemarchive_private.outing_completions'), { code: '42501' })
})

test('guarded revert removes only the canonical event and safely restores untouched title status', async () => {
  const outing = await makeOuting(), completed = await complete(outing), operation = randomUUID()
  const result = await revert(completed, operation)
  assert.equal(result.status, 'applied')
  assert.equal(result.outing.status, 'missed')
  assert.equal(result.viewing, null)
  assert.equal(result.title.status, 'watchlist')
  assert.equal(result.titleStatusRestored, true)
  assert.equal(await countViewings(outing.id), 0)
  assert.deepEqual(await revert(completed, operation), result)
  assert.equal((await complete(outing)).status, 'conflict')
})

test('stale outing or viewing revisions preserve newer plans, notes and ratings', async () => {
  const outing = await makeOuting(), completed = await complete(outing)
  await db.query("update viewings set notes='Written on another device' where id=$1", [completed.canonicalViewingId])
  const conflict = await revert(completed)
  assert.equal(conflict.status, 'conflict')
  assert.equal(conflict.viewing.notes, 'Written on another device')
  await db.query('update viewings set rating=4 where id=$1', [completed.canonicalViewingId])
  const refreshed = await complete(outing)
  assert.equal((await revert(refreshed)).status, 'conflict', 'even a fresh rated event cannot be silently removed')
  await db.query('update viewings set rating=null where id=$1', [completed.canonicalViewingId])
  const beforeVenueEdit = await complete(outing)
  await db.query("update cinema_outings set venue='Edited after review' where id=$1", [outing.id])
  assert.equal((await revert(beforeVenueEdit)).status, 'conflict')
  assert.equal(await countViewings(outing.id), 1)
})

test('later intentional same-status write prevents title restoration without blocking exact event removal', async () => {
  const outing = await makeOuting(), completed = await complete(outing)
  await db.query("update titles set status='watched' where id=$1", [outing.title_id])
  const result = await revert(completed)
  assert.equal(result.status, 'applied')
  assert.equal(result.title.status, 'watched')
  assert.equal(result.titleStatusRestored, false)
  assert.equal(await countViewings(outing.id), 0)
})

test('another completed trip and its history prevent an older reversal from changing watched status', async () => {
  const outing = await makeOuting(), completed = await complete(outing), secondId = randomUUID()
  await as('postgres')
  await db.query(`insert into cinema_outings(id,user_id,title_id,showtime,runtime_minutes,ends_at)
    values($1,$2,$3,'2020-01-03T01:00:00Z',100,'2020-01-03T03:00:00Z')`, [secondId, owner, outing.title_id])
  const second = (await db.query('select to_jsonb(o) as row from cinema_outings o where id=$1', [secondId])).rows[0].row
  await as('authenticated', owner)
  const otherCompletion = await complete(second)
  const result = await revert(completed)
  assert.equal(result.title.status, 'watched')
  assert.equal(result.titleStatusRestored, false)
  assert.equal(await countViewings(secondId), 1)
  assert.equal((await db.query('select id from viewings where id=$1', [otherCompletion.canonicalViewingId])).rows.length, 1)
})

test('revert handles an explicitly removed event without recreating it and rejects mismatched identity', async () => {
  const outing = await makeOuting(), completed = await complete(outing)
  assert.equal((await revert({ ...completed, canonicalViewingId: randomUUID() })).status, 'conflict')
  await db.query('delete from viewings where id=$1', [completed.canonicalViewingId])
  const current = await complete(outing)
  const result = await revert(current)
  assert.equal(result.status, 'applied')
  assert.equal(result.viewing, null)
  assert.equal(result.canonicalViewingId, completed.canonicalViewingId)
})

test('revert receipt cannot be rebound and owner/auth boundaries retain the plan', async () => {
  const outing = await makeOuting(), completed = await complete(outing), operation = randomUUID()
  await as('authenticated', other)
  assert.equal((await revert(completed)).status, 'missing')
  await as('anon', owner)
  await assert.rejects(revert(completed), { code: '42501' })
  await as('authenticated', owner)
  await revert(completed, operation)
  await assert.rejects(revert({ ...completed, canonicalViewingId: randomUUID() }, operation), { code: '22023' })
})

test('queued completion can depend on its own preceding outing command without a guessed server revision', async () => {
  const outing = await makeOuting(), prior = randomUUID(), operation = randomUUID(), provisional = randomUUID()
  const mutations = [{ table: 'cinema_outings', action: 'update', key: { id: outing.id }, values: { venue: 'Queued venue' }, expectedUpdatedAt: outing.updated_at }]
  await db.query('select apply_library_command($1,$2)', [prior, mutations])
  const invoke = () => db.query('select complete_cinema_outing($1,$2,$3,null,$4,$5) as result', [outing.id, operation, provisional, 'America/Denver', prior])
  const first = (await invoke()).rows[0].result
  assert.equal(first.status, 'applied')
  assert.equal(first.viewing.venue, 'Queued venue')
  assert.equal(first.request.expectedOperationId, prior)
  assert.equal(first.request.expectedUpdatedAt, null)
  assert.deepEqual((await invoke()).rows[0].result, first)
  await assert.rejects(db.query('select complete_cinema_outing($1,$2,$3,null,$4,$5)', [outing.id, operation, provisional, 'America/Denver', randomUUID()]), { code: '22023' })
})

test('a causal receipt cannot hide an intervening edit or supply another outing baseline', async () => {
  const outing = await makeOuting(), prior = randomUUID()
  await db.query('select apply_library_command($1,$2)', [prior, [{ table: 'cinema_outings', action: 'update', key: { id: outing.id }, values: { venue: 'Our edit' }, expectedUpdatedAt: outing.updated_at }]])
  await db.query("update cinema_outings set venue='Intervening device' where id=$1", [outing.id])
  const result = (await db.query('select complete_cinema_outing($1,$2,$3,null,$4,$5) as result', [outing.id, randomUUID(), randomUUID(), 'UTC', prior])).rows[0].result
  assert.equal(result.status, 'conflict')
  assert.equal(result.outing.venue, 'Intervening device')
  const unrelated = await makeOuting()
  await assert.rejects(db.query('select complete_cinema_outing($1,$2,$3,null,$4,$5)', [unrelated.id, randomUUID(), randomUUID(), 'UTC', prior]), { code: '40001' })
  await assert.rejects(db.query('select complete_cinema_outing($1,$2,$3,null,$4,$5)', [outing.id, randomUUID(), randomUUID(), 'UTC', randomUUID()]), { code: '40001' })
  assert.equal(await countViewings(outing.id), 0)
})
