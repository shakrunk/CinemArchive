import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { createStorageFixture } from './storage-fixture.mjs'

const db = new PGlite({ extensions: { pgcrypto } })
const owner = randomUUID(), friend = randomUUID(), stranger = randomUUID(), other = randomUUID()
const title = randomUUID(), outing = randomUUID()
const root = new URL('../../../', import.meta.url)
async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
async function share(op = randomUUID(), recipients = [friend], id = outing) {
  return (await db.query('select public.share_outing_plans($1,$2::uuid[],$3) as snapshot', [id, recipients, op])).rows[0].snapshot
}
async function count(table) {
  await as('postgres')
  return Number((await db.query(`select count(*) as n from ${table}`)).rows[0].n)
}
before(async () => {
  await db.exec(`create role anon; create role authenticated; create role service_role;
    create schema auth;
    create table auth.users(id uuid primary key, email text, raw_user_meta_data jsonb default '{}', raw_app_meta_data jsonb default '{}');
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to authenticated, anon;
    grant execute on function auth.uid() to authenticated, anon;
    set check_function_bodies = false;`)
  const schema = (await readFile(new URL('schema.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  const migration = (await readFile(new URL('supabase/migrations/20261008184253_outing_share_receipts.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(migration.trim()))
  await createStorageFixture(db)
  await db.exec(schema)
  // Migration is safe to apply again to the canonical schema fixture.
  await db.exec(migration)
  for (const id of [owner, friend, stranger, other]) {
    await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
  }
  await db.query("insert into public.titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,777,'movie','Plan film',2026,'watchlist')", [title, owner])
  await db.query(`insert into public.cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes,venue,booking_ref,notes,companions)
    values($1,$2,$3,now()+interval '1 day',now()+interval '1 day 2 hours',120,'Original venue','PRIVATE-BOOKING','Private note',$4)`,
    [outing,owner,title,JSON.stringify([{name:'Friend display',friendUserId:friend}])])
  const [a,b] = [owner,friend].sort()
  await db.query("insert into public.friendships(user_id_a,user_id_b,requested_by,status) values($1,$2,$3,'accepted')", [a,b,owner])
}, { timeout: 60000 })
after(async () => db.close())

test('unknown-outcome retry returns the original snapshot without another notification', async () => {
  const op = randomUUID()
  await as('authenticated',owner)
  const first = await share(op,[friend,friend])
  assert.equal(first.venue,'Original venue')
  assert.deepEqual(first.companions,['Friend display'])
  assert.deepEqual(Object.keys(first).sort(), ['tmdb_id','type','title','year','poster_url','showtime','ends_at','venue','format','seat','companions'].sort())
  await as('postgres')
  await db.query("update public.cinema_outings set venue='Updated venue' where id=$1", [outing])
  const n = await count('public.notifications')
  await as('authenticated',owner)
  assert.deepEqual(await share(op),first)
  assert.equal(await count('public.notifications'),n)
  await as('authenticated',owner)
  assert.equal((await share()).venue,'Updated venue')
  assert.equal(await count('public.notifications'),n+1)
})

test('operation identifiers cannot be rebound to different recipients or outing', async () => {
  const op = randomUUID()
  await as('authenticated',owner)
  await share(op)
  await assert.rejects(share(op,[stranger]), {code:'22023'})
  await assert.rejects(share(op,[friend],randomUUID()), {code:'22023'})
})

test('legacy string companions and name objects produce only ordered public display names', async () => {
  await as('postgres')
  await db.query('update public.cinema_outings set companions=$1 where id=$2',
    [JSON.stringify(['Native name',{name:'Web name',friendUserId:friend},null,42,{name:23},{name:' '},'',{email:'private@example.test'}]),outing])
  await as('authenticated',owner)
  assert.deepEqual((await share()).companions,['Native name','Web name'])
})

test('invalid recipient rolls back notifications and receipt, permitting safe corrected retry', async () => {
  const op = randomUUID(), n = await count('public.notifications'), receipts = await count('cinemarchive_private.outing_share_receipts')
  await as('authenticated',owner)
  await assert.rejects(share(op,[friend,stranger]), {code:'42501'})
  assert.equal(await count('public.notifications'),n)
  assert.equal(await count('cinemarchive_private.outing_share_receipts'),receipts)
  await as('authenticated',owner)
  assert.equal((await share(op)).venue,'Updated venue')
})

test('anonymous, missing identity and foreign owners cannot send or inspect receipts', async () => {
  for (const [role,id] of [['anon',''],['authenticated',''],['authenticated',stranger]]) {
    await as(role,id)
    await assert.rejects(share(), {code:'42501'})
    await assert.rejects(db.query('select * from cinemarchive_private.outing_share_receipts'), {code:'42501'})
  }
  await as('anon')
  await assert.rejects(db.query('select public.share_outing_plans($1,$2::uuid[])',[outing,[friend]]), {code:'42501'})
})

test('completed, ended, self-directed and empty plans are rejected without delivery', async () => {
  await as('authenticated',owner)
  await assert.rejects(share(randomUUID(),[owner]), {code:'42501'})
  for (const recipients of [[],null,[null]]) await assert.rejects(share(randomUUID(),recipients), {code:'22023'})
  await assert.rejects(share(null), {code:'22023'})
  await as('postgres')
  await db.query("update public.cinema_outings set status='completed' where id=$1",[outing])
  await as('authenticated',owner)
  await assert.rejects(share(), {code:'22023'})
  await as('postgres')
  await db.query("update public.cinema_outings set status='scheduled',showtime=now()-interval '2 hours',ends_at=now()-interval '1 hour' where id=$1",[outing])
  await as('authenticated',owner)
  await assert.rejects(share(), {code:'22023'})
  await as('postgres')
  await db.query("update public.cinema_outings set showtime=now()+interval '1 day',ends_at=now()+interval '1 day 2 hours' where id=$1",[outing])
})

test('blocked friendship is rejected and legacy callers retain fresh-send behavior', async () => {
  await as('postgres')
  await db.exec("update public.friendships set status='blocked'")
  await as('authenticated',owner)
  await assert.rejects(share(), {code:'42501'})
  await as('postgres')
  await db.exec("update public.friendships set status='accepted'")
  const n = await count('public.notifications')
  await as('authenticated',owner)
  for (let i=0;i<2;i++) await db.query('select public.share_outing_plans($1,$2::uuid[])',[outing,[friend]])
  assert.equal(await count('public.notifications'),n+2)
})

test('public wrappers are invokers and the unexposed implementation has an empty search path', async () => {
  await as('postgres')
  const rows = (await db.query(`select n.nspname,p.prosecdef,p.proconfig from pg_proc p join pg_namespace n on n.oid=p.pronamespace where p.proname='share_outing_plans'`)).rows
  assert.equal(rows.length,3)
  for (const row of rows) {
    assert.equal(row.prosecdef,row.nspname==='cinemarchive_private')
    assert.ok(row.proconfig.includes('search_path=""'))
  }
})
