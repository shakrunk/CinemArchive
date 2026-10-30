import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { createStorageFixture } from './storage-fixture.mjs'

// Shared outings (#206): docs/android-contracts/shared-outings.md.
const db = new PGlite({ extensions: { pgcrypto } })
const root = new URL('../../../', import.meta.url)
const HEADER = '-- Shared outings (20261031000000).'
const owner = randomUUID(), friend = randomUUID(), friend2 = randomUUID(), stranger = randomUUID()
const title = randomUUID(), outing = randomUUID()

async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
const call = async (sql, params = []) => (await db.query(sql, params)).rows[0].r
const invite = (friends = [friend], id = outing) => call('select public.invite_friends_to_outing($1,$2::uuid[]) as r', [id, friends])
const respond = (shared, accept) => call('select public.respond_to_outing_invite($1,$2) as r', [shared, accept])
const sharedId = async () => { await as('postgres'); return (await db.query('select id from public.shared_outings where outing_id=$1', [outing])).rows[0]?.id }
async function count(sql, params = []) { await as('postgres'); return Number((await db.query(`select count(*) as n from ${sql}`, params)).rows[0].n) }
async function befriend(a, b, status = 'accepted') {
  const [x, y] = [a, b].sort()
  await db.query('insert into public.friendships(user_id_a,user_id_b,requested_by,status) values($1,$2,$3,$4)', [x, y, a, status])
}
async function newOuting(overrides = {}) {
  const id = randomUUID()
  await db.query(`insert into public.cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes,venue,booking_ref,notes,ticket_price,companions)
    values($1,$2,$3,${overrides.showtime ?? "now()+interval '1 day'"},${overrides.endsAt ?? "now()+interval '1 day 2 hours'"},120,'Original venue','PRIVATE-BOOKING','Private note',42,$4)`,
    [id, overrides.user ?? owner, title, JSON.stringify([{ name: 'Friend display', friendUserId: friend }, 'Plain name'])])
  return id
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
  const migration = (await readFile(new URL('supabase/migrations/20261031000000_shared_outings.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(migration.trim()), 'canonical schema includes the complete shared outings migration')
  await db.exec(schema.split(HEADER)[0])
  await db.exec(migration)
  for (const id of [owner, friend, friend2, stranger]) await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
  for (const [id, name] of [[owner, 'Olive Owner'], [friend, 'Fran Friend'], [friend2, 'Finn Friend']]) {
    await db.query(`insert into public.profiles(user_id,email,display_name) values($1,$2,$3)
      on conflict (user_id) do update set display_name=excluded.display_name`, [id, `${id}@example.test`, name])
  }
  await db.query("insert into public.titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,606,'movie','Shared film',2026,'watchlist')", [title, owner])
  await db.query(`insert into public.cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes,venue,booking_ref,notes,ticket_price,companions)
    values($1,$2,$3,now()+interval '1 day',now()+interval '1 day 2 hours',120,'Original venue','PRIVATE-BOOKING','Private note',42,$4)`,
    [outing, owner, title, JSON.stringify([{ name: 'Friend display', friendUserId: friend }, 'Plain name'])])
  await befriend(owner, friend)
  await befriend(owner, friend2)
  await db.exec('grant select,insert,update,delete on public.cinema_outings,public.friendships,public.notifications to authenticated')
}, { timeout: 60000 })
after(async () => db.close())

test('an owner invites a friend; only the minimal snapshot is shared and the private outing stays private', async () => {
  await as('authenticated', owner)
  const result = await invite()
  assert.deepEqual(result.members, [{ user_id: friend, status: 'invited' }])
  await as('postgres')
  const shared = (await db.query('select * from public.shared_outings')).rows[0]
  assert.equal(shared.owner_id, owner)
  assert.deepEqual(Object.keys(shared.snapshot).sort(), ['companions', 'format', 'poster_url', 'title', 'tmdb_id', 'type', 'venue', 'year'])
  assert.deepEqual(shared.snapshot.companions, ['Friend display', 'Plain name'])
  assert.ok(!JSON.stringify(shared.snapshot).includes('PRIVATE'))
  const note = (await db.query("select * from public.notifications where type='outing_invite'")).rows[0]
  assert.equal(note.recipient_id, friend)
  assert.equal(note.actor_id, owner)
  assert.equal(note.payload.shared_outing_id, shared.id)
  assert.ok(!JSON.stringify(note.payload).includes('PRIVATE') && !JSON.stringify(note.payload).includes(friend))
  await as('authenticated', friend)
  assert.equal((await db.query('select * from public.shared_outings')).rows.length, 1)
  assert.equal((await db.query('select * from public.cinema_outings')).rows.length, 0, 'the private outing is never readable by the friend')
  await as('authenticated', stranger)
  assert.equal((await db.query('select * from public.shared_outings')).rows.length, 0)
  assert.equal((await db.query('select * from public.shared_outing_members')).rows.length, 0)
})

test('inviting is idempotent and limited to accepted friends of an upcoming outing the caller owns', async () => {
  await as('authenticated', owner)
  const notes = await count("public.notifications where type='outing_invite'")
  await as('authenticated', owner)
  await invite([friend, friend])
  assert.equal(await count("public.notifications where type='outing_invite'"), notes, 'no second notification for an existing invite')
  for (const bad of [[stranger], [owner], [friend, stranger]]) {
    await as('authenticated', owner)
    await assert.rejects(invite(bad), { code: '42501' })
  }
  await as('authenticated', owner)
  await assert.rejects(invite([]), { code: '22023' })
  await assert.rejects(invite([friend, null]), { code: '22023' })
  await assert.rejects(invite([friend], randomUUID()), { code: '42501' })
  await as('authenticated', friend)
  await assert.rejects(invite([owner]), { code: '42501' })
  await as('authenticated', stranger)
  await assert.rejects(invite([friend]), { code: '42501' })
  await as('anon')
  await assert.rejects(invite([friend]), { code: '42501' })
  await as('postgres')
  const past = await newOuting({ showtime: "now()-interval '5 hours'", endsAt: "now()-interval '3 hours'" })
  await as('authenticated', owner)
  await assert.rejects(invite([friend], past), { code: '22023' })
  const tooMany = Array.from({ length: 9 }, () => randomUUID())
  await assert.rejects(invite(tooMany), { code: '22023' })
})

test('the invitee accepts, the owner is told, and repeating the answer changes nothing', async () => {
  const shared = await sharedId()
  await as('authenticated', stranger)
  await assert.rejects(respond(shared, true), { code: '42501' })
  await as('authenticated', friend)
  assert.deepEqual(await respond(shared, true), { status: 'accepted' })
  const notes = await count("public.notifications where type='outing_invite_response'")
  assert.equal(notes, 1)
  await as('authenticated', friend)
  assert.deepEqual(await respond(shared, true), { status: 'accepted' })
  assert.equal(await count("public.notifications where type='outing_invite_response'"), 1)
  await as('postgres')
  const note = (await db.query("select * from public.notifications where type='outing_invite_response'")).rows[0]
  assert.equal(note.recipient_id, owner)
  assert.equal(note.payload.accepted, true)
  await as('authenticated', friend)
  await assert.rejects(call('select public.respond_to_outing_invite($1,null) as r', [shared]), { code: '22023' })
})

test('lists shared outings for the invitee and members for the owner, with display names', async () => {
  await as('authenticated', friend)
  const mine = await call('select public.get_shared_outings() as r')
  assert.equal(mine.length, 1)
  assert.equal(mine[0].owner_name, 'Olive Owner')
  assert.equal(mine[0].my_status, 'accepted')
  assert.equal(mine[0].snapshot.title, 'Shared film')
  assert.ok(!JSON.stringify(mine).includes('PRIVATE'))
  await as('authenticated', owner)
  const members = await call('select public.get_outing_members($1) as r', [outing])
  assert.deepEqual(members.map((m) => [m.user_id, m.name, m.status]), [[friend, 'Fran Friend', 'accepted']])
  await as('authenticated', stranger)
  assert.deepEqual(await call('select public.get_outing_members($1) as r', [outing]), [])
  assert.deepEqual(await call('select public.get_shared_outings() as r'), [])
  await as('anon')
  await assert.rejects(call('select public.get_shared_outings() as r'), { code: '42501' })
})

test('members see only their own row, and the owner sees every member', async () => {
  await as('authenticated', owner)
  await invite([friend2])
  await as('authenticated', friend2)
  assert.deepEqual((await db.query('select user_id from public.shared_outing_members')).rows.map((r) => r.user_id), [friend2])
  await as('authenticated', owner)
  assert.equal((await db.query('select * from public.shared_outing_members')).rows.length, 2)
})

test('a declined member loses sight of the outing until invited again', async () => {
  const shared = await sharedId()
  await as('authenticated', friend2)
  assert.deepEqual(await respond(shared, false), { status: 'declined' })
  assert.equal((await db.query('select * from public.shared_outings')).rows.length, 0)
  assert.deepEqual(await call('select public.get_shared_outings() as r'), [])
  await assert.rejects(respond(shared, true), { code: '22023' })
  const before = await count("public.notifications where type='outing_invite' and recipient_id=$1", [friend2])
  await as('authenticated', owner)
  await invite([friend2])
  assert.equal(await count("public.notifications where type='outing_invite' and recipient_id=$1", [friend2]), before + 1)
  await as('authenticated', friend2)
  assert.equal((await db.query('select * from public.shared_outings')).rows.length, 1)
})

test('the owner\'s edits flow into the snapshot, and cancelling or deleting cancels the shared outing', async () => {
  await as('postgres')
  await db.query("update public.cinema_outings set venue='Moved venue', format='IMAX', showtime=showtime + interval '1 hour', ends_at=ends_at + interval '1 hour', notes='New private note' where id=$1", [outing])
  const row = (await db.query('select * from public.shared_outings where outing_id=$1', [outing])).rows[0]
  assert.equal(row.snapshot.venue, 'Moved venue')
  assert.equal(row.snapshot.format, 'IMAX')
  assert.equal(row.status, 'scheduled')
  const outingRow = (await db.query('select showtime, ends_at from public.cinema_outings where id=$1', [outing])).rows[0]
  assert.equal(+row.showtime, +outingRow.showtime)
  assert.ok(!JSON.stringify(row.snapshot).includes('private'))
  await db.query("update public.cinema_outings set status='cancelled' where id=$1", [outing])
  assert.equal((await db.query('select status from public.shared_outings where outing_id=$1', [outing])).rows[0].status, 'cancelled')
  await db.query("update public.cinema_outings set status='scheduled' where id=$1", [outing])
  assert.equal((await db.query('select status from public.shared_outings where outing_id=$1', [outing])).rows[0].status, 'scheduled')
  const doomed = await newOuting()
  await as('authenticated', owner)
  const result = await invite([friend], doomed)
  await as('postgres')
  await db.query('delete from public.cinema_outings where id=$1', [doomed])
  const kept = (await db.query('select status, outing_id from public.shared_outings where id=$1', [result.shared_outing_id])).rows[0]
  assert.deepEqual(kept, { status: 'cancelled', outing_id: null })
  await as('authenticated', friend)
  const listed = (await call('select public.get_shared_outings() as r')).find((s) => s.id === result.shared_outing_id)
  assert.equal(listed.status, 'cancelled')
  await assert.rejects(respond(result.shared_outing_id, false), { code: '22023' }).catch(() => {})
})

test('clients cannot write the tables directly', async () => {
  const shared = await sharedId()
  await as('authenticated', owner)
  await assert.rejects(db.query("insert into public.shared_outings(owner_id,showtime,ends_at,snapshot) values($1,now(),now(),'{}')", [owner]), { code: '42501' })
  await assert.rejects(db.query("update public.shared_outings set status='cancelled' where id=$1", [shared]), { code: '42501' })
  await assert.rejects(db.query('delete from public.shared_outings where id=$1', [shared]), { code: '42501' })
  await assert.rejects(db.query('insert into public.shared_outing_members(shared_outing_id,user_id) values($1,$2)', [shared, stranger]), { code: '42501' })
  await as('authenticated', friend)
  await assert.rejects(db.query("update public.shared_outing_members set status='accepted'"), { code: '42501' })
  await as('anon')
  await assert.rejects(db.query('select * from public.shared_outings'), { code: '42501' })
})

test('the owner can remove a member; nobody else can', async () => {
  const shared = await sharedId()
  await as('authenticated', friend)
  await assert.rejects(call('select public.remove_outing_member($1,$2) as r', [shared, friend2]), { code: '42501' })
  await as('authenticated', owner)
  await call('select public.remove_outing_member($1,$2) as r', [shared, friend2])
  assert.deepEqual((await db.query('select user_id from public.shared_outing_members where shared_outing_id=$1', [shared])).rows.map((r) => r.user_id).sort(), [friend].sort())
})

test('unfriending or blocking ends the shared outing between the two people', async () => {
  const shared = await sharedId()
  await as('postgres')
  assert.equal(await count('public.shared_outing_members where shared_outing_id=$1', [shared]), 1)
  const [a, b] = [owner, friend].sort()
  await db.query("update public.friendships set status='blocked', blocked_by=$3 where user_id_a=$1 and user_id_b=$2", [a, b, owner])
  assert.equal(await count('public.shared_outing_members where shared_outing_id=$1', [shared]), 0)
  await as('authenticated', owner)
  await assert.rejects(invite([friend]), { code: '42501' })
  await as('postgres')
  await db.query("update public.friendships set status='accepted', blocked_by=null where user_id_a=$1 and user_id_b=$2", [a, b])
  await as('authenticated', owner)
  await invite([friend])
  await as('postgres')
  assert.equal(await count('public.shared_outing_members where shared_outing_id=$1', [shared]), 1)
  await db.query('delete from public.friendships where user_id_a=$1 and user_id_b=$2', [a, b])
  assert.equal(await count('public.shared_outing_members where shared_outing_id=$1', [shared]), 0)
})
