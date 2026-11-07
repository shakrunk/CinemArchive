import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { createStorageFixture } from './storage-fixture.mjs'

// Group post-show reactions (#207): docs/android-contracts/shared-outing-reactions.md.
const db = new PGlite({ extensions: { pgcrypto } })
const root = new URL('../../../', import.meta.url)
const HEADER = '-- Shared outing reactions (20261101000000).'
const owner = randomUUID(), going = randomUUID(), pending = randomUUID(), stranger = randomUUID()
const title = randomUUID(), outing = randomUUID()
let shared

async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
const call = async (sql, params = []) => (await db.query(sql, params)).rows[0].r
const react = (rating, note, id = shared) => call('select public.share_outing_reaction($1,$2,$3) as r', [id, rating, note])
const reactions = (id = shared) => call('select public.get_outing_reactions($1) as r', [id])
const withdraw = (id = shared) => call('select public.withdraw_outing_reaction($1) as r', [id])
async function endShow(hoursAgo = 1) {
  await as('postgres')
  await db.query(`update public.cinema_outings set showtime=now()-interval '${hoursAgo + 2} hours', ends_at=now()-interval '${hoursAgo} hours' where id=$1`, [outing])
}
async function befriend(a, b) {
  const [x, y] = [a, b].sort()
  await db.query("insert into public.friendships(user_id_a,user_id_b,requested_by,status) values($1,$2,$3,'accepted')", [x, y, a])
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
  const migration = (await readFile(new URL('supabase/migrations/20261101000000_shared_outing_reactions.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(migration.trim()), 'canonical schema includes the complete reactions migration')
  await db.exec(schema.split(HEADER)[0])
  await db.exec(migration)
  for (const id of [owner, going, pending, stranger]) await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
  for (const [id, name] of [[owner, 'Olive Owner'], [going, 'Gus Going'], [pending, 'Pia Pending']]) {
    await db.query(`insert into public.profiles(user_id,email,display_name) values($1,$2,$3)
      on conflict (user_id) do update set display_name=excluded.display_name`, [id, `${id}@example.test`, name])
  }
  await db.query("insert into public.titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,707,'movie','Reaction film',2026,'watchlist')", [title, owner])
  await db.query(`insert into public.cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes,venue)
    values($1,$2,$3,now()+interval '1 day',now()+interval '1 day 2 hours',120,'Cinema')`, [outing, owner, title])
  await befriend(owner, going)
  await befriend(owner, pending)
  await db.exec('grant select,insert,update,delete on public.cinema_outings,public.friendships to authenticated')
  await as('authenticated', owner)
  await call('select public.invite_friends_to_outing($1,$2::uuid[]) as r', [outing, [going, pending]])
  await as('postgres')
  shared = (await db.query('select id from public.shared_outings where outing_id=$1', [outing])).rows[0].id
  await as('authenticated', going)
  await call('select public.respond_to_outing_invite($1,true) as r', [shared])
}, { timeout: 60000 })
after(async () => db.close())

test('reactions stay closed until the show has ended, and for a cancelled outing', async () => {
  await as('authenticated', going)
  await assert.rejects(react(4, 'Early'), { code: '22023' })
  await endShow()
  await as('authenticated', going)
  assert.equal(Number((await react(4, 'Great')).rating), 4)
  await as('postgres')
  await db.query("update public.cinema_outings set status='cancelled' where id=$1", [outing])
  await as('authenticated', going)
  await assert.rejects(react(3, 'Nope'), { code: '22023' })
  await as('postgres')
  await db.query("update public.cinema_outings set status='scheduled' where id=$1", [outing])
})

test('only the owner and accepted members can share or read reactions', async () => {
  for (const [who, code] of [[stranger, '42501'], [pending, '42501']]) {
    await as('authenticated', who)
    await assert.rejects(react(5, 'Hi'), { code })
    await assert.rejects(reactions(), { code })
  }
  await as('anon')
  await assert.rejects(react(5, 'Hi'), { code: '42501' })
  await as('authenticated', owner)
  assert.equal((await react(5, 'Loved it')).note, 'Loved it')
  await assert.rejects(react(5, 'x', randomUUID()), { code: '42501' })
})

test('ratings and notes are validated; a reaction needs one or the other', async () => {
  await as('authenticated', going)
  for (const [rating, note] of [[0, 'x'], [0.4, 'x'], [5.5, 'x'], [2.3, 'x'], [null, null], [null, '   '], [3, 'x'.repeat(501)]]) {
    await assert.rejects(react(rating, note), { code: '22023' }, `${rating} ${note?.length}`)
  }
  const noteOnly = await react(null, 'Just a note')
  assert.equal(noteOnly.rating, null)
  assert.equal(noteOnly.note, 'Just a note')
  assert.equal(Number((await react(3.5, null)).rating), 3.5)
  assert.equal((await react(4, '  trimmed  ')).note, 'trimmed')
})

test('everyone on the outing sees every reaction, with names, and their own marked', async () => {
  await as('authenticated', going)
  const mine = await reactions()
  assert.deepEqual(mine.map((r) => [r.name, r.is_me, Number(r.rating), r.note]), [
    ['Olive Owner', false, 5, 'Loved it'],
    ['Gus Going', true, 4, 'trimmed'],
  ])
  await as('authenticated', owner)
  const theirs = await reactions()
  assert.equal(theirs.length, 2)
  assert.deepEqual(theirs.map((r) => r.is_me).sort(), [false, true])
})

test('sharing again updates in place, and withdrawing removes only the caller\'s own', async () => {
  await as('authenticated', going)
  await react(2, 'Changed my mind')
  assert.equal((await reactions()).length, 2)
  await withdraw()
  await withdraw()
  const left = await reactions()
  assert.deepEqual(left.map((r) => r.name), ['Olive Owner'])
  assert.equal(Number((await react(4.5, 'Back again')).rating), 4.5)
})

test('clients cannot touch the table directly', async () => {
  await as('authenticated', going)
  await assert.rejects(db.query('select * from public.shared_outing_reactions'), { code: '42501' })
  await assert.rejects(db.query("insert into public.shared_outing_reactions(shared_outing_id,user_id,rating) values($1,$2,5)", [shared, going]), { code: '42501' })
  await assert.rejects(db.query("update public.shared_outing_reactions set rating=1"), { code: '42501' })
  await as('anon')
  await assert.rejects(db.query('select * from public.shared_outing_reactions'), { code: '42501' })
})

test('declining, being removed or being unfriended takes the reaction with it', async () => {
  const reactionCount = async (user) => {
    await as('postgres')
    return Number((await db.query('select count(*) as n from public.shared_outing_reactions where user_id=$1', [user])).rows[0].n)
  }
  const restore = async () => {
    await as('postgres')
    await db.query(`insert into public.shared_outing_members(shared_outing_id,user_id,status) values($1,$2,'accepted')
      on conflict (shared_outing_id,user_id) do update set status='accepted'`, [shared, going])
    await as('authenticated', going)
    await react(3, 'Back')
  }
  assert.equal(await reactionCount(going), 1)
  // A member who declines (the status change a leave makes) takes the reaction with them.
  await as('postgres')
  await db.query("update public.shared_outing_members set status='declined' where shared_outing_id=$1 and user_id=$2", [shared, going])
  assert.equal(await reactionCount(going), 0)
  await as('authenticated', going)
  await assert.rejects(reactions(), { code: '42501' })
  await restore()
  assert.equal(await reactionCount(going), 1)
  // The owner removes a member.
  await as('authenticated', owner)
  await call('select public.remove_outing_member($1,$2) as r', [shared, going])
  assert.equal(await reactionCount(going), 0)
  await as('authenticated', owner)
  assert.deepEqual((await reactions()).map((r) => r.name), ['Olive Owner'])
  // Unfriending removes the member, and so the reaction.
  await restore()
  await as('postgres')
  const [a, b] = [owner, going].sort()
  await db.query('delete from public.friendships where user_id_a=$1 and user_id_b=$2', [a, b])
  assert.equal(await reactionCount(going), 0)
})

test("an accepted member's card outlasts the screening by a week", async () => {
  await as('postgres')
  await befriend(owner, going)
  await db.query(`insert into public.shared_outing_members(shared_outing_id,user_id,status) values($1,$2,'accepted')
    on conflict (shared_outing_id,user_id) do update set status='accepted'`, [shared, going])
  await endShow(24 * 5)
  await as('authenticated', going)
  assert.equal((await call('select public.get_shared_outings() as r')).length, 1)
  await endShow(24 * 8)
  await as('authenticated', going)
  assert.equal((await call('select public.get_shared_outings() as r')).length, 0)
})
