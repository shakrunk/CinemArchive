import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { createStorageFixture } from './storage-fixture.mjs'

// Outing polls (#203): docs/android-contracts/outing-polls.md.
const db = new PGlite({ extensions: { pgcrypto } })
const root = new URL('../../../', import.meta.url)
const HEADER = '-- Outing polls (20261102000000).'
const owner = randomUUID(), sam = randomUUID(), pat = randomUUID(), stranger = randomUUID()
const filmA = randomUUID(), filmB = randomUUID(), filmC = randomUUID(), show = randomUUID(), foreign = randomUUID()
const future = (hours) => new Date(Date.now() + hours * 3600_000).toISOString()

async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
const call = async (sql, params = []) => (await db.query(sql, params)).rows[0].r
const options = (n = 2) => [
  { title_id: filmA, showtime: future(48), venue: 'AMC' },
  { title_id: filmB, showtime: future(72) },
  { title_id: filmC, showtime: future(96), venue: 'Alamo' },
  { title_id: filmA, showtime: future(120) },
].slice(0, n)
const create = (opts = options(), friends = [sam, pat], question = 'Friday or Saturday?') =>
  call('select public.create_outing_poll($1,$2::jsonb,$3::uuid[]) as r', [question, JSON.stringify(opts), friends])
const vote = (poll, ids) => call('select public.cast_poll_votes($1,$2::uuid[]) as r', [poll, ids])
const close = (poll, option = null) => call('select public.close_outing_poll($1,$2) as r', [poll, option])
const polls = () => call('select public.get_outing_polls() as r')
async function count(sql, params = []) { await as('postgres'); return Number((await db.query(`select count(*) as n from ${sql}`, params)).rows[0].n) }
async function befriend(a, b) {
  const [x, y] = [a, b].sort()
  await db.query("insert into public.friendships(user_id_a,user_id_b,requested_by,status) values($1,$2,$3,'accepted')", [x, y, a])
}
async function reset() {
  await as('postgres')
  await db.query('delete from public.outing_polls')
  await db.query('delete from public.notifications')
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
  const migration = (await readFile(new URL('supabase/migrations/20261102000000_outing_polls.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(migration.trim()), 'canonical schema includes the complete polls migration')
  await db.exec(schema.split(HEADER)[0])
  await db.exec(migration)
  for (const id of [owner, sam, pat, stranger]) await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
  for (const [id, name] of [[owner, 'Olive Owner'], [sam, 'Sam Friend'], [pat, 'Pat Friend']]) {
    await db.query(`insert into public.profiles(user_id,email,display_name) values($1,$2,$3)
      on conflict (user_id) do update set display_name=excluded.display_name`, [id, `${id}@example.test`, name])
  }
  for (const [id, tmdb, name] of [[filmA, 801, 'Film A'], [filmB, 802, 'Film B'], [filmC, 803, 'Film C']]) {
    await db.query("insert into public.titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,$3,'movie',$4,2026,'watchlist')", [id, owner, tmdb, name])
  }
  await db.query("insert into public.titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,809,'tv','A series',2020,'watchlist')", [show, owner])
  await db.query("insert into public.titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,810,'movie','Not yours',2020,'watchlist')", [foreign, sam])
  await befriend(owner, sam)
  await befriend(owner, pat)
  await db.exec('grant select,insert,update,delete on public.friendships,public.notifications to authenticated')
}, { timeout: 60000 })
after(async () => db.close())

test('an owner puts a poll to friends; they are told, and see only the candidates', async () => {
  await as('authenticated', owner)
  const { poll_id } = await create(options(3))
  await as('postgres')
  assert.equal(await count('public.outing_poll_options where poll_id=$1', [poll_id]), 3)
  const notes = (await db.query("select * from public.notifications where type='outing_poll' order by recipient_id")).rows
  assert.deepEqual(notes.map((n) => n.recipient_id).sort(), [sam, pat].sort())
  assert.equal(notes[0].actor_id, owner)
  assert.equal(notes[0].payload.poll_id, poll_id)
  await as('authenticated', sam)
  const seen = (await polls())[0]
  assert.equal(seen.question, 'Friday or Saturday?')
  assert.equal(seen.is_owner, false)
  assert.equal(seen.owner_name, 'Olive Owner')
  assert.deepEqual(seen.options.map((o) => [o.position, o.title, o.venue, o.votes, o.mine]), [[1, 'Film A', 'AMC', 0, false], [2, 'Film B', null, 0, false], [3, 'Film C', 'Alamo', 0, false]])
  assert.deepEqual(Object.keys(seen.options[0]).sort(), ['id', 'mine', 'position', 'poster_url', 'showtime', 'title', 'tmdb_id', 'venue', 'voters', 'votes', 'year'])
  await as('authenticated', stranger)
  assert.deepEqual(await polls(), [])
})

test('creating is validated: options, friends, titles, showtimes and limits', async () => {
  await reset()
  await as('authenticated', owner)
  const past = [{ title_id: filmA, showtime: future(-2) }, { title_id: filmB, showtime: future(5) }]
  const bad = [
    [options(1), [sam], '22023'], [[...options(4), options(1)[0]], [sam], '22023'], [options(2), [], '22023'], [options(2), [sam, null], '22023'],
    [options(2), Array.from({ length: 9 }, () => randomUUID()), '22023'], [past, [sam], '22023'],
    [[{ title_id: filmA, showtime: future(5) }, { title_id: filmA, showtime: future(5) }], [sam], '22023'],
    [[{ title_id: filmA, showtime: 'soon' }, { title_id: filmB, showtime: future(5) }], [sam], '22023'],
    [[{ title_id: show, showtime: future(5) }, { title_id: filmB, showtime: future(6) }], [sam], '22023'],
    [[{ title_id: foreign, showtime: future(5) }, { title_id: filmB, showtime: future(6) }], [sam], '42501'],
    [[{ title_id: filmA, showtime: future(5), venue: 'x'.repeat(101) }, { title_id: filmB, showtime: future(6) }], [sam], '22023'],
    [options(2), [stranger], '42501'], [options(2), [owner], '42501'],
  ]
  for (const [opts, friends, code] of bad) await assert.rejects(create(opts, friends), { code }, JSON.stringify(opts).slice(0, 80))
  await assert.rejects(create(options(2), [sam], 'x'.repeat(101)), { code: '22023' })
  assert.equal(await count('public.outing_polls'), 0)
  await as('anon')
  await assert.rejects(create(), { code: '42501' })
})

test('the question defaults, and an owner keeps at most five polls open', async () => {
  await reset()
  await as('authenticated', owner)
  await create(options(2), [sam], '   ')
  assert.equal((await polls())[0].question, 'Which one works?')
  for (let i = 0; i < 4; i++) await create(options(2), [sam])
  await assert.rejects(create(options(2), [sam]), { code: '22023' })
})

test('participants tick every option that works, and replacing or clearing changes their tally', async () => {
  await reset()
  await as('authenticated', owner)
  const { poll_id } = await create(options(3))
  const [a, b, c] = (await polls())[0].options.map((o) => o.id)
  await as('authenticated', sam)
  await vote(poll_id, [a, b, b])
  await as('authenticated', pat)
  await vote(poll_id, [b])
  await as('authenticated', owner)
  await vote(poll_id, [b, c])
  const tally = (await polls())[0].options
  assert.deepEqual(tally.map((o) => o.votes), [1, 3, 1])
  assert.deepEqual(tally[1].voters.sort(), ['Olive Owner', 'Pat Friend', 'Sam Friend'])
  assert.deepEqual(tally.map((o) => o.mine), [false, true, true])
  await as('authenticated', sam)
  await vote(poll_id, [c])
  assert.deepEqual((await polls())[0].options.map((o) => o.votes), [0, 2, 2])
  await vote(poll_id, [])
  assert.deepEqual((await polls())[0].options.map((o) => o.votes), [0, 2, 1])
})

test('only participants can vote, only for the poll\'s own future options, and never on a closed poll', async () => {
  await reset()
  await as('authenticated', owner)
  const { poll_id } = await create(options(2))
  const [a] = (await polls())[0].options.map((o) => o.id)
  await as('authenticated', stranger)
  await assert.rejects(vote(poll_id, [a]), { code: '42501' })
  await as('authenticated', sam)
  await assert.rejects(vote(randomUUID(), [a]), { code: '42501' })
  await assert.rejects(vote(poll_id, [randomUUID()]), { code: '22023' })
  await assert.rejects(vote(poll_id, [null]), { code: '22023' })
  await as('anon')
  await assert.rejects(vote(poll_id, [a]), { code: '42501' })
  await as('postgres')
  await db.query("update public.outing_poll_options set showtime = now() - interval '1 hour' where id=$1", [a])
  await as('authenticated', sam)
  await assert.rejects(vote(poll_id, [a]), { code: '22023' })
  await as('postgres')
  await db.query("update public.outing_poll_options set showtime = now() + interval '2 days' where id=$1", [a])
  await as('authenticated', owner)
  await close(poll_id, a)
  await as('authenticated', sam)
  await assert.rejects(vote(poll_id, [a]), { code: '22023' })
})

test('the owner closes on a winner, members are told, and a closed poll cannot be closed again', async () => {
  await reset()
  await as('authenticated', owner)
  const { poll_id } = await create(options(2))
  const [a, b] = (await polls())[0].options.map((o) => o.id)
  await as('authenticated', sam)
  await assert.rejects(close(poll_id, a), { code: '42501' })
  await as('authenticated', owner)
  await assert.rejects(close(poll_id, randomUUID()), { code: '22023' })
  assert.deepEqual(await close(poll_id, b), { status: 'closed', winner_option_id: b })
  const [seen] = await polls()
  assert.equal(seen.status, 'closed')
  assert.equal(seen.winner_option_id, b)
  await assert.rejects(close(poll_id, a), { code: '22023' })
  await as('postgres')
  const notes = (await db.query("select * from public.notifications where type='outing_poll_closed'")).rows
  assert.equal(notes.length, 2)
  assert.equal(notes[0].payload.cancelled, false)
  assert.equal(notes[0].payload.title, 'Film B')
  assert.deepEqual(notes.map((n) => n.recipient_id).sort(), [sam, pat].sort())
})

test('passing no option cancels the poll', async () => {
  await reset()
  await as('authenticated', owner)
  const { poll_id } = await create(options(2))
  assert.deepEqual(await close(poll_id), { status: 'cancelled', winner_option_id: null })
  await as('postgres')
  assert.equal((await db.query("select payload from public.notifications where type='outing_poll_closed' limit 1")).rows[0].payload.cancelled, true)
  await as('authenticated', sam)
  assert.equal((await polls())[0].status, 'cancelled')
})

test('polls older than thirty days drop out of the list', async () => {
  await reset()
  await as('authenticated', owner)
  await create(options(2))
  await as('postgres')
  await db.query("update public.outing_polls set created_at = now() - interval '31 days'")
  await as('authenticated', owner)
  assert.deepEqual(await polls(), [])
})

test('the tables are closed to clients', async () => {
  await reset()
  await as('authenticated', owner)
  await create(options(2))
  for (const table of ['outing_polls', 'outing_poll_options', 'outing_poll_members', 'outing_poll_votes']) {
    await assert.rejects(db.query(`select * from public.${table}`), { code: '42501' }, table)
  }
  await assert.rejects(db.query("update public.outing_polls set status='closed'"), { code: '42501' })
  await as('anon')
  await assert.rejects(db.query('select * from public.outing_polls'), { code: '42501' })
  await assert.rejects(polls(), { code: '42501' })
})

test('unfriending removes the person from the owner\'s polls along with their votes', async () => {
  await reset()
  await as('authenticated', owner)
  const { poll_id } = await create(options(2))
  const [a] = (await polls())[0].options.map((o) => o.id)
  await as('authenticated', sam)
  await vote(poll_id, [a])
  await as('postgres')
  assert.equal(await count('public.outing_poll_votes where user_id=$1', [sam]), 1)
  const [x, y] = [owner, sam].sort()
  await db.query("update public.friendships set status='blocked', blocked_by=$3 where user_id_a=$1 and user_id_b=$2", [x, y, owner])
  assert.equal(await count('public.outing_poll_votes where user_id=$1', [sam]), 0)
  assert.equal(await count('public.outing_poll_members where user_id=$1', [sam]), 0)
  await as('authenticated', sam)
  assert.deepEqual(await polls(), [])
  await as('authenticated', pat)
  assert.equal((await polls()).length, 1)
})
