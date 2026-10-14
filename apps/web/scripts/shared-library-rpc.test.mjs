// Run: node apps/web/scripts/shared-library-rpc.test.mjs   (from the repo root)
// Local-only verification of supabase/migrations/20261008180000_shared_library_rpc.sql
// against PGlite. No network, no Supabase project.
import { after, before, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'

const db = new PGlite({ extensions: { pgcrypto } })
const root = new URL('../../../', import.meta.url)
const MIGRATION = 'supabase/migrations/20261008180000_shared_library_rpc.sql'
const APPEND = 'schema.sql'
const read = async (p) => (await readFile(new URL(p, root), 'utf8')).replaceAll('\r\n', '\n')

// Users (friendships need a < b ordering, so sort the friend pair).
const [A, F] = [randomUUID(), randomUUID()].sort() // owner A, friend F (A < F)
const B = randomUUID() // unrelated authenticated user
const C = randomUUID() // pending (not accepted) friend
const D = randomUUID() // owner of an empty library
const G = randomUUID() // second accepted friend with NO scope row

// Fixed ids so ties on added_at order deterministically by id ASC.
const T = {
  drama: '00000000-0000-0000-0000-0000000000a1',     // 2026-01-03 Drama watched (tv)
  horror: '00000000-0000-0000-0000-0000000000a2',    // 2026-01-02 Horror watchlist
  tieLow: '00000000-0000-0000-0000-0000000000a3',    // 2026-01-01 Drama,Comedy watching
  tieHigh: '00000000-0000-0000-0000-0000000000a4',   // 2026-01-01 Drama dropped
  bTitle: '00000000-0000-0000-0000-0000000000b1',
}
const TOK = { all: 'tok-all', scoped: 'tok-scoped', revoked: 'tok-revoked', expired: 'tok-expired',
  empty: 'tok-empty', scopedEmpty: 'tok-scoped-empty', never: 'tok-never-expires' }
const ids = {}

async function as(role, uid = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [uid])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
async function call(token, off, lim) {
  const args = [token]
  let sql = 'select public.get_shared_library($1'
  if (off !== undefined) { args.push(off); sql += ',$2' }
  if (lim !== undefined) { args.push(lim); sql += off === undefined ? ',p_limit=>$2' : ',$3' }
  return (await db.query(sql + ') as r', args)).rows[0].r
}
async function sql(text, params) { return (await db.query(text, params)).rows }
async function su(text, params) { await db.exec('reset role'); try { return await sql(text, params) } finally { await db.exec('set role anon') } }
const notifCount = async () => Number((await su("select count(*) n from public.notifications where type='share_link_used' and recipient_id=$1", [A]))[0].n)

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
  let schema = await read('schema.sql')
  // Test the migration on top of the pre-change baseline even after the parent
  // has folded the canonical blocks into schema.sql.
  for (const marker of ['-- Atomic library commands (20261008162831)\n', '-- Shared library RPC (20261008180000)\n']) {
    const i = schema.indexOf(marker)
    if (i >= 0) schema = schema.slice(0, i)
  }
  await db.exec(schema)
  await db.exec(await read('supabase/migrations/20261008162831_atomic_library_commands.sql'))
  await db.exec(await read(MIGRATION))
  // Supabase's default privileges: API roles can touch every public table, so RLS is the ONLY barrier.
  await db.exec(`grant usage on schema public to anon, authenticated;
    grant select on all tables in schema public to anon, authenticated;
    grant insert, update on public.shared_access_keys to authenticated;`)

  for (const [u, e] of [[A,'a'],[B,'b'],[C,'c'],[D,'d'],[F,'f'],[G,'g']])
    await db.query('insert into auth.users(id,email) values ($1,$2)', [u, `${e}@example.test`])
  const pair = (x, y) => [x < y ? x : y, x < y ? y : x]
  for (const [other, status] of [[F,'accepted'],[G,'accepted'],[C,'pending']]) {
    const [a, b] = pair(A, other)
    await db.query('insert into public.friendships(user_id_a,user_id_b,requested_by,status) values ($1,$2,$3,$4)', [a, b, A, status])
  }

  const title = (id, user, tmdb, type, name, genres, status, addedAt) => db.query(
    `insert into public.titles(id,user_id,tmdb_id,type,title,year,genres,status,added_at,notes)
     values ($1,$2,$3,$4,$5,2020,$6,$7,$8,'SECRET-NOTE')`, [id, user, tmdb, type, name, genres, status, addedAt])
  await title(T.drama, A, 1, 'tv', 'Drama Show', ['Drama'], 'watched', '2026-01-03T00:00:00Z')
  await title(T.horror, A, 2, 'movie', 'Horror Film', ['Horror'], 'watchlist', '2026-01-02T00:00:00Z')
  await title(T.tieLow, A, 3, 'movie', 'Tie Low', ['Drama', 'Comedy'], 'watching', '2026-01-01T00:00:00Z')
  await title(T.tieHigh, A, 4, 'movie', 'Tie High', ['Drama'], 'dropped', '2026-01-01T00:00:00Z')
  await title(T.bTitle, B, 5, 'movie', 'B Private Film', ['Drama'], 'watched', '2026-01-04T00:00:00Z')

  // Child graph for the Drama show and for the Horror film (so scope filtering of children is observable).
  for (const [tid, tag] of [[T.drama, 'D'], [T.horror, 'H']]) {
    const season = randomUUID(), ep = randomUUID()
    ids[tag] = { season, ep }
    await db.query('insert into public.seasons(id,title_id,user_id,season_number,episode_count) values ($1,$2,$3,1,1)', [season, tid, A])
    await db.query(`insert into public.episodes(id,title_id,user_id,season_number,episode_number,episode_name) values ($1,$2,$3,1,1,$4)`, [ep, tid, A, `Ep-${tag}`])
    await db.query('insert into public.episode_watch_events(episode_id,user_id,notes) values ($1,$2,$3)', [ep, A, `watch-${tag}`])
    await db.query('insert into public.episode_ratings(episode_id,user_id,rating) values ($1,$2,4)', [ep, A])
    await db.query('insert into public.episode_reviews(episode_id,user_id,review_text) values ($1,$2,$3)', [ep, A, `review-${tag}`])
    await db.query('insert into public.episode_crew(user_id,title_id,episode_id,tmdb_person_id,name,job) values ($1,$2,$3,10,$4,$5)', [A, tid, ep, `crew-${tag}`, 'Director'])
    await db.query('insert into public.season_cast(user_id,title_id,season_id,tmdb_person_id,name) values ($1,$2,$3,11,$4)', [A, tid, season, `scast-${tag}`])
    await db.query('insert into public.title_cast(user_id,title_id,tmdb_person_id,name,cast_order) values ($1,$2,12,$3,0)', [A, tid, `cast-${tag}`])
    await db.query('insert into public.title_crew(user_id,title_id,tmdb_person_id,name,job) values ($1,$2,13,$3,$4)', [A, tid, `tcrew-${tag}`, 'Director'])
    await db.query('insert into public.viewings(title_id,user_id,viewed_at,notes) values ($1,$2,$3,$4)', [tid, A, '2026-01-01', `view-${tag}`])
  }
  // A foreign-owner child pointing at A's title must never be returned.
  await db.query('insert into public.viewings(title_id,user_id,notes) values ($1,$2,$3)', [T.drama, B, 'ROGUE-VIEWING'])
  // Private data that must never appear.
  const out = randomUUID()
  await db.query(`insert into public.cinema_outings(id,user_id,title_id,showtime,runtime_minutes,ends_at,booking_ref,ticket_barcode_payload,seat)
    values ($1,$2,$3,now(),100,now() + interval '2 hours','SECRET-BOOKING','SECRET-BARCODE','SECRET-SEAT')`, [out, A, T.drama])
  await db.query("insert into public.lists(user_id,name) values ($1,'SECRET-LIST')", [A])
  await db.query("insert into public.user_prefs(user_id,ledger_layout) values ($1,'[{\"id\":\"w1\"}]')", [A])

  const key = (token, user, extra = '') => db.query(
    `insert into public.shared_access_keys(user_id,token,label${extra ? ',' + extra.split('=')[0] : ''}) values ($1,$2,$3${extra ? ',' + extra.split('=')[1] : ''}) returning id`, [user, token, token])
  ids.all = (await key(TOK.all, A)).rows[0].id
  ids.scoped = (await key(TOK.scoped, A)).rows[0].id
  ids.revoked = (await key(TOK.revoked, A, 'is_active=false')).rows[0].id
  ids.expired = (await key(TOK.expired, A, "expires_at=now() - interval '1 minute'")).rows[0].id
  ids.never = (await key(TOK.never, A, "expires_at=now() + interval '1 day'")).rows[0].id
  await key(TOK.empty, D)
  ids.scopedEmpty = (await key(TOK.scopedEmpty, A)).rows[0].id
  await db.query("insert into public.share_scopes(owner_user_id,shared_key_id,allowed_genres,allowed_statuses) values ($1,$2,'{Drama}','{watched,watching}')", [A, ids.scoped])
  await db.query("insert into public.share_scopes(owner_user_id,shared_key_id,allowed_genres) values ($1,$2,'{Western}')", [A, ids.scopedEmpty])
  await db.query("insert into public.share_scopes(owner_user_id,friend_user_id,allowed_genres) values ($1,$2,'{Drama}')", [A, F])
}, { timeout: 120000 })

beforeEach(async () => {
  await db.exec('reset role')
  await db.exec("delete from public.notifications; update public.shared_access_keys set last_used_at = null")
  await db.exec("select set_config('app.shared_token','',false)")
  await as('anon')
})
after(async () => db.close())

test('migration is idempotent and the append block mirrors it', async () => {
  await as('postgres')
  await db.exec(await read(MIGRATION))
  const migration = await read(MIGRATION)
  const append = await read(APPEND)
  assert.ok(append.includes(migration.trim()), 'schema append block contains the full migration text')
})

test('valid token returns owner, full nested graph in the web TITLE_SELECT shape, layout and hasMore', async () => {
  const r = await call(TOK.all)
  assert.deepEqual(Object.keys(r).sort(), ['hasMore', 'ledgerLayout', 'ownerUserId', 'titles'])
  assert.equal(r.ownerUserId, A)
  assert.deepEqual(r.ledgerLayout, [{ id: 'w1' }])
  assert.equal(r.hasMore, false)
  assert.deepEqual(r.titles.map(t => t.id), [T.drama, T.horror, T.tieLow, T.tieHigh])
  const d = r.titles[0]
  for (const k of ['id','user_id','tmdb_id','type','title','year','genres','status','added_at','updated_at','title_cast','title_crew','seasons','viewings','episodes'])
    assert.ok(k in d, `title has ${k}`)
  assert.equal(d.title_cast[0].name, 'cast-D')
  assert.equal(d.title_crew[0].name, 'tcrew-D')
  assert.equal(d.seasons[0].season_cast[0].name, 'scast-D')
  assert.equal(d.episodes[0].episode_crew[0].name, 'crew-D')
  assert.equal(d.episodes[0].episode_watch_events[0].notes, 'watch-D')
  assert.equal(Number(d.episodes[0].episode_ratings[0].rating), 4)
  assert.equal(d.episodes[0].episode_reviews[0].review_text, 'review-D')
  assert.deepEqual(d.viewings.map(v => v.notes), ['view-D'], 'foreign-owner child on the same title is excluded')
  assert.deepEqual(r.titles[2].seasons, [])
  assert.deepEqual(r.titles[2].episodes, [])
  assert.equal(r.titles.some(t => t.id === T.bTitle), false)
})

test('private tables and fields never appear', async () => {
  const text = JSON.stringify(await call(TOK.all))
  for (const secret of ['SECRET-BOOKING', 'SECRET-BARCODE', 'SECRET-SEAT', 'SECRET-LIST', 'ROGUE-VIEWING'])
    assert.equal(text.includes(secret), false, secret)
  for (const key of ['cinema_outings', 'list_items', '"lists"', 'notifications', 'ticket_barcode_payload', 'booking_ref'])
    assert.equal(text.includes(key), false, key)
})

test('link scope (genres + statuses) filters titles and every child', async () => {
  const r = await call(TOK.scoped)
  assert.deepEqual(r.titles.map(t => t.id), [T.drama, T.tieLow]) // Drama AND watched|watching
  const text = JSON.stringify(r)
  for (const leaked of ['-H"', 'watch-H', 'review-H', 'view-H', 'cast-H', 'crew-H', 'Ep-H'])
    assert.equal(text.includes(leaked), false, leaked)
  assert.ok(text.includes('watch-D') && text.includes('Ep-D'))
})

test('scope that matches nothing returns the owner and an empty list', async () => {
  const r = await call(TOK.scopedEmpty)
  assert.equal(r.ownerUserId, A); assert.deepEqual(r.titles, []); assert.equal(r.hasMore, false)
})

test('empty library with a valid token returns owner + []', async () => {
  const r = await call(TOK.empty)
  assert.equal(r.ownerUserId, D); assert.deepEqual(r.titles, []); assert.equal(r.ledgerLayout, null); assert.equal(r.hasMore, false)
})

test('invalid, revoked, expired, NULL, empty and oversized tokens raise 42501 with a stable message', async () => {
  for (const token of ['nope', TOK.revoked, TOK.expired, null, '', 'x'.repeat(600)]) {
    await assert.rejects(call(token), (e) => e.code === '42501' && /Invalid or expired share link/.test(e.message), String(token))
  }
  // also as a signed-in user
  await as('authenticated', B)
  await assert.rejects(call(TOK.revoked), { code: '42501' })
  // revoking after use takes effect immediately
  await as('postgres')
  await db.query('update public.shared_access_keys set is_active=false where id=$1', [ids.never])
  await as('anon')
  await assert.rejects(call(TOK.never), { code: '42501' })
  await as('postgres')
  await db.query('update public.shared_access_keys set is_active=true where id=$1', [ids.never])
})

test('pagination is stable (added_at desc, id asc ties) with correct hasMore, and clamps inputs', async () => {
  const p1 = await call(TOK.all, 0, 3)
  const p2 = await call(TOK.all, 3, 3)
  assert.deepEqual(p1.titles.map(t => t.id), [T.drama, T.horror, T.tieLow]); assert.equal(p1.hasMore, true)
  assert.deepEqual(p2.titles.map(t => t.id), [T.tieHigh]); assert.equal(p2.hasMore, false)
  const exact = await call(TOK.all, 0, 4)
  assert.equal(exact.hasMore, false); assert.equal(exact.titles.length, 4)
  const tie = await call(TOK.all, 2, 1) // first of the tie pair
  assert.deepEqual(tie.titles.map(t => t.id), [T.tieLow]); assert.equal(tie.hasMore, true)
  const past = await call(TOK.all, 50, 10)
  assert.deepEqual(past.titles, []); assert.equal(past.hasMore, false); assert.equal(past.ownerUserId, A)
  assert.equal((await call(TOK.all, -5, 2)).titles.length, 2, 'negative offset clamps to 0')
  assert.equal((await call(TOK.all, 0, 0)).titles.length, 1, 'limit 0 clamps to 1')
  assert.equal((await call(TOK.all, 0, 100000)).titles.length, 4, 'huge limit clamps to 200')
  assert.equal((await sql('select public.get_shared_library($1,null,null) r', [TOK.all]))[0].r.titles.length, 4, 'nulls use defaults')
})

test('200-row cap really applies', async () => {
  await as('postgres')
  await db.query(`insert into public.titles(user_id,tmdb_id,type,title,year,added_at)
    select $1, 1000+g, 'movie', 'Bulk '||g, 2020, '2025-01-01'::timestamptz - g * interval '1 minute' from generate_series(1,230) g`, [D])
  await as('anon')
  const r = await call(TOK.empty, 0, 100000)
  assert.equal(r.titles.length, 200); assert.equal(r.hasMore, true)
  await as('postgres')
  await db.query('delete from public.titles where user_id=$1', [D])
})

test('stale app.shared_token GUC grants NOTHING to anon or another user', async () => {
  for (const [role, uid] of [['anon', ''], ['authenticated', B]]) {
    await as(role, uid)
    await db.query("select set_config('app.shared_token',$1,false)", [TOK.all])
    for (const t of ['seasons', 'viewings', 'episodes', 'episode_watch_events', 'episode_ratings', 'episode_reviews', 'title_cast', 'title_crew', 'season_cast', 'episode_crew', 'user_prefs'])
      assert.equal((await sql(`select 1 from public.${t} where user_id=$1`, [A])).length, 0, `${role} sees ${t}`)
    assert.equal((await sql('select 1 from public.titles where user_id=$1', [A])).length, 0, `${role} sees titles`)
    assert.equal((await sql('select 1 from public.shared_access_keys')).length, 0)
  }
  await as('authenticated', B)
  assert.equal((await sql('select 1 from public.titles where user_id=$1', [B])).length, 1, 'B still sees own titles')
})

test('no policy or function body consults app.shared_token any more', async () => {
  await as('postgres')
  assert.deepEqual(await sql("select policyname from pg_policies where coalesce(qual,'')||coalesce(with_check,'') ilike '%shared_token%'"), [])
  assert.deepEqual(await sql("select proname from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname in ('public','cinemarchive_sharing','cinemarchive_private') and prosrc ilike '%app.shared_token%'"), [])
  assert.deepEqual(await sql("select policyname from pg_policies where tablename='user_prefs' order by 1"),
    [{ policyname: 'user_prefs: friend read' }, { policyname: 'user_prefs: owner full access' }])
})

test('owner access is unchanged', async () => {
  await as('authenticated', A)
  assert.equal((await sql('select 1 from public.titles')).length, 4)
  assert.equal((await sql('select 1 from public.user_prefs')).length, 1)
  assert.equal((await sql('select 1 from public.cinema_outings')).length, 1)
  assert.equal((await sql('select 1 from public.shared_access_keys')).length, 6)
})

test('friend access via share_scopes works as before; pending friends and strangers see nothing', async () => {
  await as('authenticated', F) // scoped to Drama
  assert.deepEqual((await sql('select id from public.titles where user_id=$1 order by id', [A])).map(r => r.id), [T.drama, T.tieLow, T.tieHigh])
  assert.equal((await sql('select 1 from public.seasons where user_id=$1', [A])).length, 1) // Drama show only
  assert.equal((await sql('select 1 from public.episodes where user_id=$1', [A])).length, 1)
  assert.equal((await sql('select 1 from public.viewings where user_id=$1 and notes like $2', [A, 'view-%'])).length, 1)
  assert.equal((await sql('select 1 from public.user_prefs where user_id=$1', [A])).length, 1, 'friend read of prefs preserved')
  assert.equal((await sql('select 1 from public.cinema_outings')).length, 0)
  await as('authenticated', G) // accepted, no scope row => unrestricted
  assert.equal((await sql('select 1 from public.titles where user_id=$1', [A])).length, 4)
  await as('authenticated', C) // pending
  assert.equal((await sql('select 1 from public.titles where user_id=$1', [A])).length, 0)
  await as('authenticated', B)
  assert.equal((await sql('select 1 from public.titles where user_id=$1', [A])).length, 0)
})

test('usage touch + throttled share_link_used notification (once per hour per key)', async () => {
  await call(TOK.all)
  assert.equal(await notifCount(), 1)
  const [row] = await su('select last_used_at from public.shared_access_keys where id=$1', [ids.all])
  assert.ok(row.last_used_at)
  await call(TOK.all); await call(TOK.all)
  assert.equal(await notifCount(), 1, 'repeat calls inside the hour do not notify')
  await as('postgres')
  await db.query("update public.shared_access_keys set last_used_at = now() - interval '2 hours' where id=$1", [ids.all])
  await as('anon')
  await call(TOK.all)
  assert.equal(await notifCount(), 2, 'a new window notifies again')
  const [n] = await su("select payload from public.notifications order by created_at desc limit 1")
  assert.equal(n.payload.label, TOK.all)
})

// PGlite serializes queries on one connection; this checks repeated delivery,
// not multi-connection lock contention on a running Postgres server.
test('queued first-page calls on one local connection create one notification', async () => {
  await Promise.all([call(TOK.all), call(TOK.all), call(TOK.all, 0, 1)])
  assert.equal(await notifCount(), 1)
})

test('later pages do not count as a use; expired / revoked / invalid keys never notify or touch', async () => {
  await call(TOK.all, 3, 3)
  assert.equal(await notifCount(), 0)
  for (const t of [TOK.expired, TOK.revoked, 'nope']) await assert.rejects(call(t), { code: '42501' })
  assert.equal(await notifCount(), 0, 'expired-but-active link no longer notifies')
  const [e] = await su('select last_used_at from public.shared_access_keys where id=$1', [ids.expired])
  assert.equal(e.last_used_at, null)
})

test('set_shared_token compatibility stub raises 42501 and never sets the session GUC', async () => {
  for (const role of ['anon', 'authenticated']) {
    await as(role, role === 'anon' ? '' : B)
    await assert.rejects(db.query('select public.set_shared_token($1)', [TOK.all]), (e) => e.code === '42501' && /retired/.test(e.message))
    const [g] = await sql("select current_setting('app.shared_token', true) as v")
    assert.ok(!g.v, `GUC untouched for ${role}`)
  }
  await as('postgres')
  const [src] = await sql("select prosrc, prosecdef from pg_proc where proname='set_shared_token'")
  assert.equal(src.prosecdef, false); assert.ok(!/set_config|shared_access_keys|notifications/.test(src.prosrc))
})

test('shared_key_owner stays stateless and checks expiry/revocation', async () => {
  assert.equal((await sql('select public.shared_key_owner($1) o', [TOK.all]))[0].o, A)
  for (const t of [TOK.expired, TOK.revoked, 'nope']) assert.equal((await sql('select public.shared_key_owner($1) o', [t]))[0].o, null)
})

test('createSharedKey: user_id defaults to the caller; cannot mint a key for someone else', async () => {
  await as('authenticated', B)
  const [k] = await sql("insert into public.shared_access_keys(label) values ('web') returning user_id")
  assert.equal(k.user_id, B)
  await assert.rejects(db.query("insert into public.shared_access_keys(user_id,label) values ($1,'evil')", [A]), { code: '42501' })
})

test('privileges: anon can execute only the public wrapper (and the definer it calls); nothing else leaks', async () => {
  await as('postgres')
  const can = async (role, fn) => (await sql('select has_function_privilege($1,$2,\'execute\') ok', [role, fn]))[0].ok
  assert.equal(await can('anon', 'public.get_shared_library(text,integer,integer)'), true)
  assert.equal(await can('authenticated', 'public.get_shared_library(text,integer,integer)'), true)
  assert.equal(await can('anon', 'public.is_valid_shared_token(text,uuid)'), false)
  assert.equal(await can('authenticated', 'public.is_valid_shared_token(text,uuid)'), false)
  assert.equal(await can('anon', 'cinemarchive_private.apply_library_command(uuid,jsonb)'), false)
  // no PUBLIC (grantee 0) execute on any of our functions
  const pub = await sql(`select p.oid::regprocedure::text f from pg_proc p where p.proname in ('get_shared_library','set_shared_token','shared_key_owner')
     and exists (select 1 from aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a where a.grantee = 0 and a.privilege_type = 'EXECUTE')`)
  assert.deepEqual(pub, [])
  // wrapper is INVOKER, implementation is DEFINER with an empty search_path
  const [w] = await sql("select prosecdef, proconfig from pg_proc where oid='public.get_shared_library(text,integer,integer)'::regprocedure")
  assert.equal(w.prosecdef, false); assert.deepEqual(w.proconfig, ['search_path=""'])
  const [i] = await sql("select prosecdef, proconfig from pg_proc where oid='cinemarchive_sharing.get_shared_library(text,integer,integer)'::regprocedure")
  assert.equal(i.prosecdef, true); assert.deepEqual(i.proconfig, ['search_path=""'])
  // anon cannot create objects in or read tables of the private schema
  await as('anon')
  await assert.rejects(db.query('create function cinemarchive_sharing.evil() returns int language sql as $$select 1$$'), { code: '42501' })
  await assert.rejects(db.query('select * from cinemarchive_private.library_command_receipts'), { code: '42501' })
})

test('wrapper invoked through the Data-API roles with named args (PostgREST style)', async () => {
  const [r] = await sql('select public.get_shared_library(p_token => $1, p_offset => 1, p_limit => 1) r', [TOK.all])
  assert.equal(r.r.titles.length, 1); assert.equal(r.r.titles[0].id, T.horror); assert.equal(r.r.hasMore, true)
})
