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
  const causalReversal = (await readFile(new URL('supabase/migrations/20261008211455_causal_outing_revert.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(causalReversal.trim()))
  const viewingRevision = (await readFile(new URL('supabase/migrations/20261008213850_outing_completion_viewing_revision.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(viewingRevision.trim()))
  const outingRevision = (await readFile(new URL('supabase/migrations/20261008220210_outing_completion_outing_revision.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(outingRevision.trim()))
  await db.exec(schema)
  await db.exec('grant select,update,delete on public.cinema_outings,public.titles,public.viewings to authenticated; grant select on public.notifications to authenticated;')
  for (const id of [owner, other]) await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
}, { timeout: 60000 })
after(async () => db.close())

async function patchReceipt(table, id, values, operation = randomUUID()) {
  await db.query('select public.apply_library_command($1,$2::jsonb)', [operation, JSON.stringify([{ table, action: 'update', key: { id }, values }])])
  return operation
}
async function causalRevert(snapshot, outingOperation, viewingOperation = null, operation = randomUUID()) {
  return (await db.query('select public.revert_cinema_outing($1,$2,$3,$4,$5,$6,$7) as result', [
    snapshot.outing.id, operation, outingOperation ? null : snapshot.outing.updated_at,
    snapshot.canonicalViewingId, viewingOperation ? null : snapshot.viewing?.updated_at ?? null,
    outingOperation, viewingOperation,
  ])).rows[0].result
}

function viewingEffect(snapshot) { return snapshot.rows.find(row => row.table === 'viewings') }
function outingEffect(snapshot) { return snapshot.rows.find(row => row.table === 'cinema_outings') }
async function dependentOuting(snapshot, values = { venue: 'Pending native venue' }) {
  return db.query('select apply_library_command($1,$2::jsonb) as result', [randomUUID(), JSON.stringify([{
    table: 'cinema_outings', action: 'update', key: { id: snapshot.outingId }, values,
    expectedOperationId: snapshot.operationId,
  }])])
}
async function dependentViewing(snapshot, action = 'update', values = { notes: 'Native pending note' }) {
  return db.query('select apply_library_command($1,$2::jsonb) as result', [randomUUID(), JSON.stringify([{
    table: 'viewings', action, key: { id: snapshot.canonicalViewingId },
    ...(action === 'update' ? { values } : {}), expectedOperationId: snapshot.operationId,
  }])])
}
async function installCompletionDefinition(migration) {
  const sql = await readFile(new URL(`supabase/migrations/${migration}`, root), 'utf8')
  const start = sql.search(/create (?:or replace )?function cinemarchive_private\.complete_cinema_outing\(/)
  assert.notEqual(start, -1)
  const definition = sql.slice(start, sql.indexOf('\n$$;', start) + 4)
  await as('postgres')
  await db.exec(definition.replace(/^create function/, 'create or replace function'))
}

test('completion exposes only its immutable viewing identity and revision as a causal effect', async () => {
  const outing = await makeOuting(), first = await complete(outing)
  assert.deepEqual(viewingEffect(first), {
    table: 'viewings', key: { id: first.canonicalViewingId }, row: {
      id: first.canonicalViewingId, user_id: owner, title_id: outing.title_id,
      outing_id: outing.id, updated_at: first.viewing.updated_at,
    },
  })
  await dependentViewing(first)
  assert.equal((await db.query('select notes from viewings where id=$1', [first.canonicalViewingId])).rows[0].notes, 'Native pending note')
  const later = await complete(outing)
  assert.deepEqual(viewingEffect(later), viewingEffect(first))
  assert.notEqual(later.viewing.updated_at, viewingEffect(later).row.updated_at)
})

test('completion outing effect retains its original revision across later edits and receipt replay', async () => {
  const outing = await makeOuting(), operation = randomUUID(), provisional = randomUUID()
  const first = await complete(outing, { operation, provisional })
  assert.equal(first.completionOutingVersion, first.outing.updated_at)
  assert.deepEqual(outingEffect(first), { table: 'cinema_outings', key: { id: outing.id }, row: {
    id: outing.id, user_id: owner, title_id: outing.title_id, updated_at: first.completionOutingVersion,
  } })
  await dependentOuting(first)
  const replay = await complete(outing, { operation, provisional })
  assert.equal(replay.outing.venue, 'Pending native venue')
  assert.deepEqual(outingEffect(replay), outingEffect(first))
  assert.equal(replay.completionOutingVersion, first.completionOutingVersion)
  await assert.rejects(dependentOuting(replay), { code: '40001' })
})

test('web-first completion cannot authorize a pending native venue change over a newer edit', async () => {
  const outing = await makeOuting()
  await db.query("select * from complete_due_outings('UTC')")
  const version = (await db.query('select to_jsonb(o) as row from cinema_outings o where id=$1', [outing.id])).rows[0].row.updated_at
  await db.query("update cinema_outings set venue='Newer web venue' where id=$1", [outing.id])
  const native = await complete(outing)
  assert.equal(native.status, 'already_completed')
  assert.equal(native.completionOutingVersion, version)
  assert.equal(outingEffect(native).row.updated_at, version)
  assert.notEqual(native.outing.updated_at, version)
  await assert.rejects(dependentOuting(native), { code: '40001' })
  assert.equal((await db.query('select venue from cinema_outings where id=$1', [outing.id])).rows[0].venue, 'Newer web venue')
})

test('historical completion revisions remain unproven and old accepted receipts remain unchanged', async () => {
  let outing, first, operation, provisional
  try {
    await installCompletionDefinition('20261008213850_outing_completion_viewing_revision.sql')
    outing = await makeOuting(); operation = randomUUID(); provisional = randomUUID()
    first = await complete(outing, { operation, provisional })
    assert.equal(first.completionOutingVersion, undefined)
  } finally {
    await installCompletionDefinition('20261008220210_outing_completion_outing_revision.sql')
  }
  await as('authenticated', owner)
  assert.deepEqual(await complete(outing, { operation, provisional }), first)
  await db.query("update cinema_outings set notes='Keep later note' where id=$1", [outing.id])
  const later = await complete(outing)
  assert.equal(later.completionOutingVersion, null)
  assert.equal(outingEffect(later).row.updated_at, null)
  assert.equal(later.outing.notes, 'Keep later note')
  await assert.rejects(dependentOuting(later), { code: '40001' })
})

test('deleted outing keeps immutable completion effect without becoming editable through replay', async () => {
  const outing = await makeOuting(), operation = randomUUID(), provisional = randomUUID()
  const first = await complete(outing, { operation, provisional })
  await db.query('delete from cinema_outings where id=$1', [outing.id])
  const replay = await complete(outing, { operation, provisional })
  assert.equal(replay.outing, null)
  assert.deepEqual(outingEffect(replay), outingEffect(first))
  assert.equal(replay.completionOutingVersion, first.completionOutingVersion)
  await assert.rejects(dependentOuting(replay), { code: '40001' })
})

test('web-first completion retains its original baseline when native arrives after newer notes and ratings', async () => {
  const outing = await makeOuting()
  const web = (await db.query("select * from complete_due_outings('UTC')")).rows.find(row => row.outing_id === outing.id)
  const initialVersion = (await db.query('select to_jsonb(v) as row from viewings v where id=$1', [web.viewing_id])).rows[0].row.updated_at
  await db.query("update viewings set notes='Newer web note',rating=4.5 where id=$1", [web.viewing_id])
  const native = await complete(outing)
  assert.equal(native.status, 'already_completed')
  assert.equal(viewingEffect(native).row.updated_at, initialVersion)
  assert.equal(native.viewing.notes, 'Newer web note')
  assert.equal(native.viewing.rating, 4.5)
  await assert.rejects(dependentViewing(native), { code: '40001' })
  await assert.rejects(dependentViewing(native, 'delete'), { code: '40001' })
  assert.equal((await db.query('select notes,rating from viewings where id=$1', [web.viewing_id])).rows[0].notes, 'Newer web note')
  assert.equal(await countViewings(outing.id), 1)
})

test('deleted canonical viewing keeps immutable proof but dependent mutation cannot resurrect it', async () => {
  const outing = await makeOuting(), first = await complete(outing)
  await db.query('delete from viewings where id=$1', [first.canonicalViewingId])
  const native = await complete(outing)
  assert.equal(native.viewing, null)
  assert.deepEqual(viewingEffect(native), viewingEffect(first))
  await assert.rejects(dependentViewing(native), { code: '40001' })
  assert.equal(await countViewings(outing.id), 0)
  await db.query('delete from cinema_outings where id=$1', [outing.id])
  const replay = await complete(outing, {
    operation: native.operationId, provisional: native.request.provisionalViewingId,
  })
  assert.equal(replay.outing, null)
  assert.equal(replay.viewing, null)
  assert.deepEqual(viewingEffect(replay), viewingEffect(first))
})

test('new completion notifications identify the exact outing and canonical viewing', async () => {
  const outing = await makeOuting(), first = await complete(outing)
  const payload = (await db.query("select payload from notifications where title_id=$1 and type='outing_completed'", [outing.title_id])).rows[0].payload
  assert.deepEqual(payload, {
    outingId: outing.id, canonicalViewingId: first.canonicalViewingId,
    venue: 'Local cinema', companions: ['Legacy name', 'Linked name'],
  })
})

test('old accepted receipts and historical metadata never acquire an inferred viewing baseline', async () => {
  let outing, first, operation, provisional
  try {
    await installCompletionDefinition('20261008194606_canonical_outing_completion.sql')
    outing = await makeOuting()
    operation = randomUUID(); provisional = randomUUID()
    first = await complete(outing, { operation, provisional })
    assert.equal(viewingEffect(first), undefined)
  } finally {
    await installCompletionDefinition('20261008220210_outing_completion_outing_revision.sql')
  }
  await as('authenticated', owner)
  assert.deepEqual(await complete(outing, { operation, provisional }), first)
  await db.query("update viewings set notes='Historical edit' where id=$1", [provisional])
  const newAttempt = await complete(outing)
  assert.equal(newAttempt.status, 'already_completed')
  assert.equal(viewingEffect(newAttempt), undefined)
  assert.equal(newAttempt.viewing.notes, 'Historical edit')
  await assert.rejects(dependentViewing(newAttempt), { code: '40001' })
  const payload = (await db.query('select payload from notifications where title_id=$1', [outing.title_id])).rows[0].payload
  assert.equal(payload.outingId, undefined, 'historical notifications are not guessed or backfilled')
  assert.equal(payload.canonicalViewingId, undefined)
  await as('postgres')
  assert.equal((await db.query('select canonical_viewing_version from cinemarchive_private.outing_completions where outing_id=$1', [outing.id])).rows[0].canonical_viewing_version, null)
})

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

test('reversal follows exact outing and viewing edit receipts and retries without guessing revisions', async () => {
  const snapshot = await complete(await makeOuting()), operation = randomUUID()
  const outingEdit = await patchReceipt('cinema_outings', snapshot.outing.id, { notes: 'Our queued plan edit' })
  const viewingEdit = await patchReceipt('viewings', snapshot.canonicalViewingId, { notes: 'Our queued viewing edit' })
  const first = await causalRevert(snapshot, outingEdit, viewingEdit, operation)
  assert.equal(first.status, 'applied')
  assert.equal(first.outing.status, 'missed')
  assert.equal(first.viewing, null)
  assert.equal(first.request.expectedOperationId, outingEdit)
  assert.equal(first.request.expectedViewingOperationId, viewingEdit)
  assert.equal(first.request.expectedUpdatedAt, null)
  assert.equal(first.request.expectedViewingUpdatedAt, null)
  assert.deepEqual(await causalRevert(snapshot, outingEdit, viewingEdit, operation), first)
  await assert.rejects(causalRevert(snapshot, randomUUID(), viewingEdit, operation), { code: '22023' })
})

test('causal reversal preserves intervening edits and every rated viewing', async () => {
  const snapshot = await complete(await makeOuting())
  const outingEdit = await patchReceipt('cinema_outings', snapshot.outing.id, { venue: 'Queued venue' })
  const viewingEdit = await patchReceipt('viewings', snapshot.canonicalViewingId, { notes: 'Queued note' })
  await db.query("update viewings set notes='Newer device note' where id=$1", [snapshot.canonicalViewingId])
  assert.equal((await causalRevert(snapshot, outingEdit, viewingEdit)).status, 'conflict')
  const rated = await patchReceipt('viewings', snapshot.canonicalViewingId, { rating: 4 })
  assert.equal((await causalRevert(snapshot, outingEdit, rated)).status, 'conflict')
  assert.equal(await countViewings(snapshot.outing.id), 1)
})

test('causal reversal rejects another row, owner, deleted predecessor and ambiguous guards', async () => {
  const snapshot = await complete(await makeOuting()), unrelated = await complete(await makeOuting())
  const unrelatedEdit = await patchReceipt('cinema_outings', unrelated.outing.id, { notes: 'Other outing' })
  await assert.rejects(causalRevert(snapshot, unrelatedEdit), { code: '40001' })
  const edit = await patchReceipt('cinema_outings', snapshot.outing.id, { notes: 'Owned receipt' })
  await as('authenticated', other)
  await assert.rejects(causalRevert(snapshot, edit), { code: '40001' })
  await as('authenticated', owner)
  const deletion = randomUUID()
  await db.query('select apply_library_command($1,$2::jsonb)', [deletion, JSON.stringify([{ table: 'viewings', action: 'delete', key: { id: snapshot.canonicalViewingId } }])])
  await assert.rejects(causalRevert(snapshot, edit, deletion), { code: '40001' })
  const id = randomUUID()
  await assert.rejects(causalRevert(snapshot, id, null, id), { code: '22023' })
  await assert.rejects(db.query('select revert_cinema_outing($1,$2,$3,$4,$5,$6,$7)', [snapshot.outing.id, randomUUID(), snapshot.outing.updated_at, snapshot.canonicalViewingId, null, edit, null]), { code: '22023' })
  await assert.rejects(db.query('select revert_cinema_outing($1,$2,$3,$4,$5,$6,$7)', [snapshot.outing.id, randomUUID(), null, snapshot.canonicalViewingId, snapshot.viewing.updated_at, edit, deletion]), { code: '22023' })
})

test('five-argument receipts survive the causal reversal migration unchanged', async () => {
  await as('postgres')
  await db.exec('drop function public.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz,uuid,uuid); drop function cinemarchive_private.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz,uuid,uuid);')
  await db.exec(await readFile(new URL('supabase/migrations/20261008195605_canonical_outing_revert.sql', root), 'utf8'))
  const snapshot = await complete(await makeOuting()), id = randomUUID()
  const first = await revert(snapshot, id)
  await as('postgres')
  await db.exec(await readFile(new URL('supabase/migrations/20261008211455_causal_outing_revert.sql', root), 'utf8'))
  await as('authenticated', owner)
  assert.deepEqual(await revert(snapshot, id), first)
  await as('anon', owner)
  await assert.rejects(revert(snapshot), { code: '42501' })
})
