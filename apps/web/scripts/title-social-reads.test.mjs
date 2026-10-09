import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'

const db = new PGlite({ extensions: { pgcrypto } })
const [owner, friend] = [randomUUID(), randomUUID()].sort()
const stranger = randomUUID(), title = randomUUID()
const root = new URL('../../../', import.meta.url)
async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
async function list(name) {
  return (await db.query(`select * from public.${name}($1)`, [title])).rows
}
before(async () => {
  await db.exec(`
    create role anon; create role authenticated; create role service_role;
    create schema auth;
    create table auth.users(id uuid primary key, email text, raw_user_meta_data jsonb default '{}', raw_app_meta_data jsonb default '{}');
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to authenticated, anon;
    grant execute on function auth.uid() to authenticated, anon;
    set check_function_bodies = false;
  `)
  const schema = (await readFile(new URL('schema.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  const migration = (await readFile(new URL('supabase/migrations/20261008180925_scoped_title_social_reads.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  const [baseline] = schema.split('-- Scoped title social reads (20261008180925)')
  assert.ok(schema.includes(migration.trim()))
  await db.exec(baseline)
  await db.query('insert into auth.users(id,email) values ($1,$2),($3,$4),($5,$6)',
    [owner, 'owner@example.test', friend, 'friend@example.test', stranger, 'stranger@example.test'])
  await db.query("update public.profiles set display_name='Private author' where user_id=$1", [owner])
  await db.query("insert into public.titles(id,user_id,tmdb_id,type,title,year,genres,status) values($1,$2,812345,'movie','Scoped film',2026,array['Drama'],'watched')", [title, owner])
  await db.query("insert into public.friendships(user_id_a,user_id_b,requested_by,status) values($1,$2,$1,'accepted')", [owner, friend])
  await db.query("insert into public.title_comments(title_id,author_id,body) values($1,$2,'Private comment')", [title, owner])
  await db.query("insert into public.title_reactions(title_id,author_id,emoji) values($1,$2,'👍')", [title, owner])
  // Reproduce the reaction NULL-auth bypass and the comment reader's unrelated
  // ambiguous output-column failure before applying the fix.
  await as('anon')
  await assert.rejects(list('list_title_comments'), { code: '42702' })
  assert.equal((await list('list_title_reactions')).length, 1)
  await as('postgres')
  await db.exec(migration)
}, { timeout: 60000 })
after(async () => db.close())

test('anonymous callers cannot execute either public social reader, even with a stale share token', async () => {
  await as('anon')
  await db.query("select set_config('app.shared_token','stale-token',false)")
  for (const fn of ['list_title_comments', 'list_title_reactions']) {
    await assert.rejects(list(fn), { code: '42501' })
    await assert.rejects(db.query(`select * from cinemarchive_private.${fn}($1)`, [title]), { code: '42501' })
  }
})

test('an authenticated role without an identity and an unrelated account receive no rows', async () => {
  for (const user of ['', stranger]) {
    await as('authenticated', user)
    assert.deepEqual(await list('list_title_comments'), [])
    assert.deepEqual(await list('list_title_reactions'), [])
  }
})

test('owner and accepted unrestricted friend retain the existing response fields', async () => {
  for (const user of [owner, friend]) {
    await as('authenticated', user)
    const comments = await list('list_title_comments'), reactions = await list('list_title_reactions')
    assert.equal(comments[0].body, 'Private comment')
    assert.equal(comments[0].display_name, 'Private author')
    assert.equal(reactions[0].emoji, '👍')
    assert.deepEqual(Object.keys(comments[0]).sort(), ['author_id','body','created_at','display_name','id','username'])
    assert.deepEqual(Object.keys(reactions[0]).sort(), ['author_id','display_name','emoji','username'])
  }
})

test('friend genre and status restrictions apply to comments and reactions immediately', async () => {
  await as('postgres')
  await db.query("insert into public.share_scopes(owner_user_id,friend_user_id,allowed_genres) values($1,$2,array['Horror'])", [owner,friend])
  for (const restriction of ['genre', 'status', 'allowed']) {
    await as('postgres')
    if (restriction !== 'genre') await db.query('update public.share_scopes set allowed_genres=array[$1]::text[], allowed_statuses=array[$2]::public.watch_status[] where owner_user_id=$3 and friend_user_id=$4',
      ['Drama', restriction === 'allowed' ? 'watched' : 'watchlist', owner, friend])
    await as('authenticated', friend)
    assert.equal((await list('list_title_comments')).length, restriction === 'allowed' ? 1 : 0)
    assert.equal((await list('list_title_reactions')).length, restriction === 'allowed' ? 1 : 0)
  }
  await as('postgres')
  await db.query('delete from public.share_scopes where owner_user_id=$1 and friend_user_id=$2', [owner,friend])
})

test('pending and blocked relationships cannot read social data; owners still can', async () => {
  for (const status of ['pending', 'blocked']) {
    await as('postgres')
    await db.query('update public.friendships set status=$1 where user_id_a=$2 and user_id_b=$3', [status,owner,friend])
    await as('authenticated', friend)
    assert.deepEqual(await list('list_title_comments'), [])
    assert.deepEqual(await list('list_title_reactions'), [])
    await as('authenticated', owner)
    assert.equal((await list('list_title_comments')).length, 1)
    assert.equal((await list('list_title_reactions')).length, 1)
  }
})

test('public API wrappers stay invokers and private implementations have fixed search paths', async () => {
  await as('postgres')
  const rows = (await db.query(`select n.nspname, p.prosecdef, p.proconfig from pg_proc p
    join pg_namespace n on n.oid=p.pronamespace
    where p.proname in ('list_title_comments','list_title_reactions')
      and n.nspname in ('public','cinemarchive_private')`)).rows
  assert.equal(rows.length, 4)
  for (const row of rows) {
    assert.equal(row.prosecdef, row.nspname === 'cinemarchive_private')
    assert.deepEqual(row.proconfig, ['search_path=""'])
  }
})
