import { after, before, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'

const database = new PGlite({ extensions: { pgcrypto } })
const owner = randomUUID()
const other = randomUUID()
let number = 90000
const migrationUrl = new URL('../../../supabase/migrations/20261008162831_atomic_library_commands.sql', import.meta.url)

before(async () => {
  await database.exec(`
    create role anon; create role authenticated; create role service_role;
    create schema auth;
    create table auth.users(id uuid primary key, email text, raw_user_meta_data jsonb default '{}', raw_app_meta_data jsonb default '{}');
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to authenticated, anon;
    grant execute on function auth.uid() to authenticated, anon;
    set check_function_bodies = false;
  `)
  // Use the canonical schema, including actual constraints, triggers and RLS.
  const schema = (await readFile(new URL('../../../schema.sql', import.meta.url), 'utf8')).replaceAll('\r\n', '\n')
  const migration = (await readFile(migrationUrl, 'utf8')).replaceAll('\r\n', '\n')
  const [baseline, following] = schema.split('-- Atomic library commands (20261008162831)\n')
  assert.ok(following.trimStart().startsWith(migration.trim()), 'canonical schema includes the complete atomic command migration')
  await database.exec(baseline)
  await database.exec(migration)
  const causal = (await readFile(new URL('../../../supabase/migrations/20261008171852_causal_library_commands.sql', import.meta.url),'utf8')).replaceAll('\r\n','\n')
  assert.ok(schema.includes(causal.trim()), 'canonical schema includes causal command migration')
  await database.exec(causal)
  const imports = (await readFile(new URL('../../../supabase/migrations/20261008174436_durable_import_links.sql', import.meta.url),'utf8')).replaceAll('\r\n','\n')
  assert.ok(schema.includes(imports.trim()), 'canonical schema includes durable import links')
  await database.exec(imports)
  const capacity = (await readFile(new URL("../../../supabase/migrations/20261008180456_library_command_import_capacity.sql", import.meta.url), "utf8")).replaceAll("\r\n", "\n")
  assert.ok(schema.includes(capacity.trim()), "canonical schema includes import capacity")
  await database.exec(capacity)
  const credits = (await readFile(new URL('../../../supabase/migrations/20261008201710_credit_refresh_commands.sql', import.meta.url), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(credits.trim()), 'canonical schema includes credit refresh commands')
  await database.exec(credits)
  const ensure = (await readFile(new URL('../../../supabase/migrations/20261008214554_ensure_episode_catalog_parents.sql', import.meta.url), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(ensure.trim()), 'canonical schema includes episode catalog ensure')
  await database.exec(ensure)
  await database.query('insert into auth.users(id,email) values ($1,$2),($3,$4)', [owner,'owner@example.test',other,'other@example.test'])
  await database.exec('grant select on all tables in schema public to authenticated;')
}, { timeout: 60000 })
beforeEach(async () => {
  await database.exec('reset role')
  await database.query("select set_config('request.jwt.claim.sub',$1,false)", [owner])
  await database.exec('set role authenticated')
})
after(async () => database.close())

async function command(operations, id = randomUUID()) {
  const result = await database.query('select public.apply_library_command($1,$2::jsonb) as result', [id, JSON.stringify(operations)])
  return result.rows[0].result
}
function titleOperation(id = randomUUID()) {
  return { table:'titles', action:'insert', key:{id}, values:{tmdb_id:++number,type:'movie',title:'A film',year:2026,status:'watchlist'} }
}
async function rows(table) { return (await database.query(`select * from public.${table}`)).rows }

test('an imported title and external identity commit atomically and retry safely', async () => {
  const title = titleOperation(), id = randomUUID()
  const link = { table: 'external_title_links', action: 'insert', key: { provider: 'letterboxd', external_id: randomUUID() }, values: { title_id: title.key.id } }
  const first = await command([title, link], id)
  assert.deepEqual(await command([title, link], id), first)
  await command([link])
  assert.equal((await rows('external_title_links')).filter(row => row.external_id === link.key.external_id).length, 1)
  const second = titleOperation()
  await assert.rejects(command([second, { ...link, values: { title_id: second.key.id } }]), { code: '23505' })
  assert.equal((await rows('titles')).some(row => row.id === second.key.id), false)
})

test('external identities cannot attach another owner title or change their owner', async () => {
  const title = titleOperation()
  await command([title])
  await database.query("select set_config('request.jwt.claim.sub',$1,false)", [other])
  const link = { table: 'external_title_links', action: 'insert', key: { provider: 'plex', external_id: randomUUID() }, values: { title_id: title.key.id } }
  await assert.rejects(command([link]), { code: '42501' })
  await assert.rejects(command([{ ...link, values: { ...link.values, user_id: owner } }]), { code: '22023' })
})

test('creates a title and viewing in one transaction and returns canonical rows', async () => {
  const title = titleOperation()
  const viewing = {table:'viewings',action:'insert',key:{id:randomUUID()},values:{title_id:title.key.id,rating:4}}
  const result = await command([title,viewing])
  assert.equal(result.rows.length,2)
  assert.equal(result.rows[0].row.user_id,owner)
  assert.equal(result.rows[1].row.title_id,title.key.id)
})

test('a failed child rolls back its parent and the operation receipt', async () => {
  const title = titleOperation()
  const id = randomUUID()
  const invalid = {table:'viewings',action:'insert',key:{id:randomUUID()},values:{title_id:title.key.id,rating:7}}
  await assert.rejects(command([title,invalid],id), {code:'23514'})
  assert.equal((await rows('titles')).some(row => row.id===title.key.id),false)
  invalid.values.rating=4
  await command([title,invalid],id) // the rolled-back ID is available for corrected work
})

test('retry returns the receipt without reverting a later edit', async () => {
  const title = titleOperation()
  const id = randomUUID()
  const first = await command([title],id)
  await command([{table:'titles',action:'update',key:title.key,values:{notes:'Later edit'}}])
  assert.deepEqual(await command([title],id),first)
  assert.equal((await rows('titles')).find(row=>row.id===title.key.id).notes,'Later edit')
  await assert.rejects(command([{...title,values:{...title.values,title:'Different'}}],id),{code:'22023'})
})

test('new insert operation with existing identity does not overwrite fields', async () => {
  const title = titleOperation()
  await command([title])
  await command([{table:'titles',action:'update',key:title.key,values:{rating:5}}])
  await command([{...title,values:{...title.values,rating:1}}])
  assert.equal(Number((await rows('titles')).find(row=>row.id===title.key.id).rating),5)
})

test('field patches preserve omission and transmit explicit null', async () => {
  const title=titleOperation()
  await command([{...title,values:{...title.values,rating:4,notes:'Keep'}}])
  await command([{table:'titles',action:'update',key:title.key,values:{rating:null}}])
  const row=(await rows('titles')).find(row=>row.id===title.key.id)
  assert.equal(row.rating,null)
  assert.equal(row.notes,'Keep')
})

test('stale revision aborts the whole command', async () => {
  const title=titleOperation()
  await command([title])
  const otherTitle=titleOperation()
  await assert.rejects(command([otherTitle,{table:'titles',action:'update',key:title.key,values:{rating:1},expectedUpdatedAt:'2000-01-01T00:00:00Z'}]),{code:'40001'})
  assert.equal((await rows('titles')).some(row=>row.id===otherTitle.key.id),false)
})

test('owner cannot mutate or attach records to another owner graph', async () => {
  const title=titleOperation()
  await command([title])
  await database.query("select set_config('request.jwt.claim.sub',$1,false)",[other])
  await assert.rejects(command([{table:'titles',action:'update',key:title.key,values:{notes:'Intrusion'}}]),{code:'P0002'})
  await assert.rejects(command([{table:'viewings',action:'insert',key:{id:randomUUID()},values:{title_id:title.key.id}}]),{code:'42501'})
  assert.equal((await rows('titles')).some(row=>row.id===title.key.id),false)
})

test('anonymous and signed-out callers cannot execute commands', async () => {
  await database.query("select set_config('request.jwt.claim.sub','',false)")
  await assert.rejects(command([titleOperation()]),{code:'42501'})
  await database.exec('reset role; set role anon')
  await assert.rejects(command([titleOperation()]),{code:'42501'})
})

test('receipt records and arbitrary fields/tables cannot be forged', async () => {
  await assert.rejects(database.query('select * from cinemarchive_private.library_command_receipts'),{code:'42501'})
  const title=titleOperation()
  await assert.rejects(command([{...title,values:{...title.values,user_id:other}}]),{code:'22023'})
  await assert.rejects(command([{...title,table:'profiles'}]),{code:'22023'})
  await assert.rejects(command([{...title,key:{}}]),{code:'22023'})
})

test('duplicate natural identity reports conflict rather than acknowledging a different title', async () => {
  const title=titleOperation()
  await command([title])
  await assert.rejects(command([{...title,key:{id:randomUUID()}}]),{code:'23505'})
})

test('pin and layout puts update their real singleton columns', async () => {
  const title=titleOperation()
  await command([title])
  const pin={table:'user_title_pins',action:'put',key:{title_id:title.key.id,easter_egg_key:'spider-noir'},values:{pinned_variant:'bw'}}
  await command([pin,{table:'user_prefs',action:'put',key:{},values:{ledger_layout:[]}}])
  await command([{...pin,values:{pinned_variant:'color'}},{table:'user_prefs',action:'put',key:{},values:{ledger_layout:[{id:'widget'}]}}])
  assert.equal((await rows('user_title_pins')).find(row=>row.title_id===title.key.id).pinned_variant,'color')
  assert.deepEqual((await rows('user_prefs'))[0].ledger_layout,[{id:'widget'}])
})

test('an existing ID cannot stand for another media identity', async () => {
  const title=titleOperation()
  await command([title])
  await assert.rejects(command([{...title,values:{...title.values,tmdb_id:++number}}]),{code:'23505'})
})

test('equivalent timestamp representations satisfy the revision precondition', async () => {
  const title=titleOperation()
  const result=await command([title])
  const stamp=new Date(result.rows[0].row.updated_at).toISOString()
  await command([{table:'titles',action:'update',key:title.key,values:{notes:'Revision checked'},expectedUpdatedAt:stamp}])
})

test('episode graph, independent logs and credits are committed together', async () => {
  const title=titleOperation()
  title.values.type='tv'
  const season=randomUUID(), episode=randomUUID(), watch=randomUUID()
  await command([title,
    {table:'seasons',action:'insert',key:{id:season},values:{title_id:title.key.id,season_number:1,episode_count:1,episodes_watched:0}},
    {table:'episodes',action:'insert',key:{id:episode},values:{title_id:title.key.id,season_number:1,episode_number:1}},
    {table:'episode_watch_events',action:'insert',key:{id:watch},values:{episode_id:episode,watched_at:null}},
    {table:'episode_ratings',action:'insert',key:{id:randomUUID()},values:{episode_id:episode,rating:4}},
    {table:'episode_reviews',action:'insert',key:{id:randomUUID()},values:{episode_id:episode,review_text:'Pilot'}},
    {table:'season_cast',action:'insert',key:{season_id:season,tmdb_person_id:123},values:{title_id:title.key.id,name:'Actor'}},
    {table:'episode_crew',action:'insert',key:{episode_id:episode,tmdb_person_id:456,job:'Director'},values:{title_id:title.key.id,name:'Director'}},
  ])
  assert.equal((await rows('episode_watch_events')).find(row=>row.id===watch).watched_at,null)
  await command([{table:'episode_crew',action:'delete',key:{episode_id:episode}}])
  assert.equal((await rows('episode_crew')).some(row=>row.episode_id===episode),false)
})

test('membership uses natural identity and cascading title deletion removes it', async () => {
  const title=titleOperation(), list=randomUUID()
  const membership={table:'list_items',action:'insert',key:{list_id:list,title_id:title.key.id},values:{}}
  await command([title,{table:'lists',action:'insert',key:{id:list},values:{name:'Favorites'}},membership])
  await command([membership])
  assert.equal((await rows('list_items')).filter(row=>row.list_id===list).length,1)
  await command([{table:'titles',action:'delete',key:title.key}])
  assert.equal((await rows('list_items')).filter(row=>row.list_id===list).length,0)
})

test('cross-owner list and mismatched episode parent links are denied', async () => {
  const title=titleOperation(), second=titleOperation(), list=randomUUID(), season=randomUUID(), episode=randomUUID()
  await command([title,second,{table:'lists',action:'insert',key:{id:list},values:{name:'Private'}},
    {table:'seasons',action:'insert',key:{id:season},values:{title_id:title.key.id,season_number:1,episode_count:1}},
    {table:'episodes',action:'insert',key:{id:episode},values:{title_id:title.key.id,season_number:1,episode_number:1}}])
  await assert.rejects(command([{table:'episode_crew',action:'insert',key:{episode_id:episode,tmdb_person_id:1,job:'Director'},values:{title_id:second.key.id,name:'Wrong parent'}}]),{code:'42501'})
  await database.query("select set_config('request.jwt.claim.sub',$1,false)",[other])
  const ownTitle=titleOperation()
  await command([ownTitle])
  await assert.rejects(command([{table:'list_items',action:'insert',key:{list_id:list,title_id:ownTitle.key.id},values:{}}]),{code:'42501'})
})

test('invalid operation syntax cannot escape the API allowlist', async () => {
  const title=titleOperation()
  for (const operation of [
    {...title,table:'titles; drop table public.titles'},
    {...title,key:{'id) or true --':title.key.id}},
    {...title,values:{'title = null --':'x'}},
    {...title,unexpected:true},
  ]) await assert.rejects(command([operation]),{code:'22023'})
  await assert.rejects(command([]),{code:'22023'})
})

test('receipts with the same UUID remain independent between owners', async () => {
  const id=randomUUID(), first=titleOperation(), second=titleOperation()
  await command([first],id)
  await database.query("select set_config('request.jwt.claim.sub',$1,false)",[other])
  const result=await command([second],id)
  assert.equal(result.rows[0].row.user_id,other)
  assert.equal(result.rows[0].row.id,second.key.id)
})

test('queued edits use the preceding immutable receipt revision and retry safely', async () => {
  const title=titleOperation(), first=randomUUID(), second=randomUUID()
  await command([title],first)
  const patch={table:'titles',action:'update',key:title.key,values:{notes:'Second queued edit'},expectedOperationId:first}
  await command([patch],second)
  await command([{table:'titles',action:'update',key:title.key,values:{rating:4},expectedOperationId:second}])
  await command([patch],second)
  const row=(await rows('titles')).find(row=>row.id===title.key.id)
  assert.equal(row.notes,'Second queued edit')
  assert.equal(Number(row.rating),4)
})

test('an intervening device edit conflicts with a causal queued patch', async () => {
  const title=titleOperation(), first=randomUUID()
  await command([title],first)
  await command([{table:'titles',action:'update',key:title.key,values:{notes:'Other device'}}])
  await assert.rejects(command([{table:'titles',action:'update',key:title.key,values:{notes:'Stale offline edit'},expectedOperationId:first}]),{code:'40001'})
  assert.equal((await rows('titles')).find(row=>row.id===title.key.id).notes,'Other device')
})

test('causal references cannot borrow another row or another owner receipt', async () => {
  const title=titleOperation(), second=titleOperation(), receipt=randomUUID()
  await command([title],receipt)
  await command([second])
  await assert.rejects(command([{table:'titles',action:'update',key:second.key,values:{rating:1},expectedOperationId:receipt}]),{code:'40001'})
  await database.query("select set_config('request.jwt.claim.sub',$1,false)",[other])
  const ownTitle=titleOperation()
  await command([ownTitle])
  await assert.rejects(command([{table:'titles',action:'update',key:ownTitle.key,values:{rating:1},expectedOperationId:receipt}]),{code:'40001'})
})

test('a compound receipt supplies its final row revision for the next queued delete', async () => {
  const title=titleOperation(), receipt=randomUUID()
  await command([title,
    {table:'titles',action:'update',key:title.key,values:{notes:'Second effect'}},
  ],receipt)
  await command([{table:'titles',action:'delete',key:title.key,expectedOperationId:receipt}])
  assert.equal((await rows('titles')).some(row=>row.id===title.key.id),false)
  await assert.rejects(command([{table:'titles',action:'update',key:title.key,values:{notes:'Resurrect'},expectedOperationId:receipt}]),{code:'40001'})
})

test('causal preconditions reject ambiguous, self-referencing, or deleted predecessors', async () => {
  const title=titleOperation(), receipt=randomUUID(), deletion=randomUUID(), current=randomUUID()
  await command([title],receipt)
  const patch={table:'titles',action:'update',key:title.key,values:{notes:'Invalid'}}
  await assert.rejects(command([{...patch,expectedOperationId:receipt,expectedUpdatedAt:'2000-01-01T00:00:00Z'}]),{code:'22023'})
  await assert.rejects(command([{...patch,expectedOperationId:current}],current),{code:'22023'})
  await command([{table:'titles',action:'delete',key:title.key}],deletion)
  await assert.rejects(command([{...patch,expectedOperationId:deletion}]),{code:'40001'})
})


test("an imported TV graph beyond 2048 operations remains atomic and replay-safe", async () => {
  const title = titleOperation(), receipt = randomUUID()
  title.values.type = "tv"
  const graph = [title, { table: "seasons", action: "insert", key: { id: randomUUID() }, values: { title_id: title.key.id, season_number: 1, episode_count: 2100 } }, ...Array.from({ length: 2100 }, (_, index) => ({
    table: "episodes", action: "insert", key: { id: randomUUID() },
    values: { title_id: title.key.id, season_number: 1, episode_number: index + 1 },
  }))]
  const first = await command(graph, receipt)
  assert.equal(first.rows.length, 2102)
  assert.deepEqual(await command(graph, receipt), first)
  assert.equal((await rows("episodes")).filter(row => row.title_id === title.key.id).length, 2100)
  const failed = titleOperation()
  const badGraph = [failed, ...graph.slice(1).map(op => ({ ...op, key: { id: randomUUID() }, values: { ...op.values, title_id: failed.key.id } })),
    { table: "viewings", action: "insert", key: { id: randomUUID() }, values: { title_id: failed.key.id, rating: 7 } }]
  await assert.rejects(command(badGraph), { code: "23514" })
  assert.equal((await rows("titles")).some(row => row.id === failed.key.id), false)
  assert.equal((await rows("episodes")).some(row => row.title_id === failed.key.id), false)
}, { timeout: 60000 })

test("oversized imports are rejected before creating any rows or receipts", async () => {
  const title = titleOperation(), receipt = randomUUID()
  await assert.rejects(command(Array(50001).fill(title), receipt), { code: "22023" })
  await assert.rejects(command([{ ...title, values: { ...title.values, notes: "x".repeat(16777216) } }], receipt), { code: "22023" })
  assert.equal((await rows("titles")).some(row => row.id === title.key.id), false)
  await command([title], receipt)
}, { timeout: 60000 })

async function creditGraph() {
  const title = titleOperation(), season = randomUUID(), episode = randomUUID()
  title.values.type = 'tv'
  await command([title,
    { table: 'seasons', action: 'insert', key: { id: season }, values: { title_id: title.key.id, season_number: 0, episode_count: 1 } },
    { table: 'episodes', action: 'insert', key: { id: episode }, values: { title_id: title.key.id, season_number: 0, episode_number: 1 } },
  ])
  return [
    { table: 'title_cast', action: 'put', key: { title_id: title.key.id, tmdb_person_id: 100 }, values: { name: 'Actor', character_name: 'Role', cast_order: 30 } },
    { table: 'title_crew', action: 'put', key: { title_id: title.key.id, tmdb_person_id: 200, job: 'Director' }, values: { name: 'Director', department: 'Directing' } },
    { table: 'season_cast', action: 'put', key: { season_id: season, tmdb_person_id: 300 }, values: { title_id: title.key.id, name: 'Special guest', character_name: 'Guest', cast_order: 40 } },
    { table: 'episode_crew', action: 'put', key: { episode_id: episode, tmdb_person_id: 400, job: 'Writer' }, values: { title_id: title.key.id, name: 'Writer' } },
  ]
}

test('provider refresh puts insert and update all four credit types without replacing canonical IDs', async () => {
  const ops = await creditGraph()
  const first = await command(ops)
  const second = await command(ops.map(op => ({ ...op, values: { ...op.values, name: 'Refreshed' } })))
  for (let i = 0; i < ops.length; i++) {
    assert.equal(second.rows[i].row.id, first.rows[i].row.id)
    assert.equal(second.rows[i].row.user_id, owner)
    assert.equal(second.rows[i].row.name, 'Refreshed')
    assert.deepEqual(second.rows[i].key, ops[i].key)
  }
})

test('credit refresh preserves omitted metadata and clears explicit null without changing identity', async () => {
  const [cast] = await creditGraph()
  const first = await command([cast])
  const second = await command([{ ...cast, values: { character_name: null } }])
  assert.equal(second.rows[0].row.id, first.rows[0].row.id)
  assert.equal(second.rows[0].row.character_name, null)
  assert.equal(second.rows[0].row.name, 'Actor')
  assert.equal(second.rows[0].row.cast_order, 30)
})

test('retry of an accepted credit refresh cannot roll back later metadata or resurrect deleted credits', async () => {
  const ops = await creditGraph(), id = randomUUID()
  const first = await command(ops, id)
  await command([{ ...ops[0], values: { name: 'Later refresh' } }, { table: ops[1].table, action: 'delete', key: ops[1].key }])
  assert.deepEqual(await command(ops, id), first)
  assert.equal((await rows('title_cast')).find(r => r.id === first.rows[0].row.id).name, 'Later refresh')
  assert.equal((await rows('title_crew')).some(r => r.id === first.rows[1].row.id), false)
  await assert.rejects(command([{ ...ops[0], values: { name: 'Changed retry' } }], id), { code: '22023' })
})

test('a new refresh can recreate an independently removed credit and returns its new canonical identity', async () => {
  const ops = await creditGraph()
  const first = await command(ops)
  await command(ops.map(op => ({ table: op.table, action: 'delete', key: op.key })))
  const second = await command(ops)
  second.rows.forEach((row, i) => assert.notEqual(row.row.id, first.rows[i].row.id))
})

test('credit puts reject foreign graphs and immutable parent changes atomically', async () => {
  const ops = await creditGraph()
  await command(ops)
  const alternate = titleOperation()
  await command([alternate])
  for (const op of ops.slice(2)) {
    await assert.rejects(command([{ ...op, values: { ...op.values, title_id: alternate.key.id } }]), { code: '22023' })
    await assert.rejects(command([{ ...op, key: { ...op.key, tmdb_person_id: 999 }, values: { ...op.values, title_id: alternate.key.id } }]), { code: '42501' })
  }
  await database.query("select set_config('request.jwt.claim.sub',$1,false)", [other])
  for (const op of ops) await assert.rejects(command([op]), { code: '42501' })
})

test('legacy foreign credit occupying an owned natural identity cannot be adopted or modified', async () => {
  const [cast] = await creditGraph()
  const foreignId = randomUUID()
  await database.exec('reset role')
  await database.query('insert into title_cast(id,user_id,title_id,tmdb_person_id,name) values($1,$2,$3,$4,$5)', [foreignId, other, cast.key.title_id, cast.key.tmdb_person_id, 'Foreign legacy row'])
  await database.exec('set role authenticated')
  await assert.rejects(command([cast]), { code: '23505' })
  await database.exec('reset role')
  const row = (await database.query('select * from title_cast where id=$1', [foreignId])).rows[0]
  assert.equal(row.user_id, other)
  assert.equal(row.name, 'Foreign legacy row')
})

test('credit put remains restricted and a bad child rolls back the entire refresh', async () => {
  const ops = await creditGraph(), id = randomUUID()
  const bad = { ...ops[3], values: { ...ops[3].values, user_id: other } }
  await assert.rejects(command([...ops.slice(0, 3), bad], id), { code: '22023' })
  assert.equal((await rows('title_cast')).some(r => r.title_id === ops[0].key.title_id), false)
  await command(ops, id)
  for (const table of ['titles', 'viewings', 'cinema_outings']) {
    await assert.rejects(command([{ table, action: 'put', key: { id: randomUUID() }, values: {} }]), { code: '22023' })
  }
})

test('a credit refresh receipt still supports exact causal revision checks', async () => {
  const [cast] = await creditGraph(), id = randomUUID()
  await command([cast], id)
  const patch = { table: cast.table, action: 'update', key: cast.key, values: { name: 'Causal edit' }, expectedOperationId: id }
  const next = randomUUID()
  const receipt = await command([patch], next)
  assert.deepEqual(await command([patch], next), receipt)
  await assert.rejects(command([patch]), { code: '40001' })
})

async function catalogOperations() {
  const title = titleOperation()
  title.values.type = 'tv'
  await command([title])
  return [
    { table: 'seasons', action: 'ensure', key: { title_id: title.key.id, season_number: 0 }, values: { episode_count: 2, air_year: 2020 } },
    ...[2, 10].map(episode_number => ({ table: 'episodes', action: 'ensure', key: { title_id: title.key.id, season_number: 0, episode_number },
      values: { episode_name: `Special ${episode_number}`, air_date: '2020-01-01', runtime: 45, synopsis: 'Provider details', still_url: null } })),
  ]
}

test('catalog ensure creates canonical parents with noncontiguous Specials and unchanged retry receipts', async () => {
  const ops = await catalogOperations(), id = randomUUID()
  const first = await command(ops, id)
  assert.equal(first.rows.length, 3)
  assert.equal(first.rows[0].row.episodes_watched, 0)
  assert.deepEqual(first.rows.slice(1).map(r => r.row.episode_number), [2, 10])
  assert.equal(new Set(first.rows.map(r => r.row.id)).size, 3)
  assert.ok(first.rows.every(r => r.row.user_id === owner && r.row.title_id === ops[0].key.title_id))
  assert.deepEqual(await command(ops, id), first)
})

test('a second device adopts existing natural identities without changing progress, metadata or history', async () => {
  const ops = await catalogOperations()
  const first = await command(ops)
  const season = first.rows[0].row, episode = first.rows[1].row, watch = randomUUID()
  await command([
    { table: 'seasons', action: 'update', key: { id: season.id }, values: { episodes_watched: 2, episode_count: 9 } },
    { table: 'episodes', action: 'update', key: { id: episode.id }, values: { episode_name: 'Existing custom name', synopsis: 'Keep metadata' } },
    { table: 'episode_watch_events', action: 'insert', key: { id: watch }, values: { episode_id: episode.id, notes: 'Keep my history' } },
  ])
  const before = (await rows('episodes')).find(r => r.id === episode.id)
  const second = await command(ops.map(op => ({ ...op, values: { ...op.values, ...(op.table === 'episodes' ? { episode_name: 'New provider title' } : { episode_count: 1 }) } })))
  assert.deepEqual(second.rows.map(r => r.row.id), first.rows.map(r => r.row.id))
  assert.equal(second.rows[0].row.episodes_watched, 2)
  assert.equal(second.rows[0].row.episode_count, 9)
  assert.equal(second.rows[1].row.episode_name, 'Existing custom name')
  assert.equal(second.rows[1].row.synopsis, 'Keep metadata')
  assert.equal(new Date(second.rows[1].row.updated_at).getTime(), before.updated_at.getTime())
  assert.equal((await rows('episode_watch_events')).find(r => r.id === watch).notes, 'Keep my history')
})

test('an old accepted ensure receipt cannot recreate a remotely deleted parent', async () => {
  const ops = await catalogOperations(), id = randomUUID()
  const first = await command(ops, id)
  await command([{ table: 'episodes', action: 'delete', key: { id: first.rows[1].row.id } }])
  assert.deepEqual(await command(ops, id), first)
  assert.equal((await rows('episodes')).some(r => r.id === first.rows[1].row.id), false)
  const next = await command([ops[1]])
  assert.notEqual(next.rows[0].row.id, first.rows[1].row.id)
})

test('catalog ensure is restricted to provider fields and rolls back missing-parent graphs', async () => {
  const ops = await catalogOperations()
  await assert.rejects(command([ops[1]]), { code: '23503' })
  const id = randomUUID()
  await assert.rejects(command([ops[0], { ...ops[1], values: { ...ops[1].values, episode_id: randomUUID() } }], id), { code: '22023' })
  assert.equal((await rows('seasons')).some(r => r.title_id === ops[0].key.title_id), false)
  await assert.rejects(command([{ ...ops[0], values: { ...ops[0].values, episodes_watched: 2 } }]), { code: '22023' })
  await assert.rejects(command([{ ...ops[0], expectedUpdatedAt: '2000-01-01T00:00:00Z' }]), { code: '22023' })
  await assert.rejects(command([{ ...ops[0], expectedOperationId: randomUUID() }]), { code: '22023' })
  await assert.rejects(command([{ table: 'viewings', action: 'ensure', key: { id: randomUUID() }, values: {} }]), { code: '22023' })
  await command(ops, id)
})

test('catalog ensure rejects foreign parents and foreign legacy rows occupying the natural identity', async () => {
  const ops = await catalogOperations()
  await database.query("select set_config('request.jwt.claim.sub',$1,false)", [other])
  await assert.rejects(command([ops[0]]), { code: '42501' })
  await database.query("select set_config('request.jwt.claim.sub',$1,false)", [owner])
  await database.exec('reset role')
  const foreign = randomUUID()
  await database.query('insert into seasons(id,user_id,title_id,season_number,episode_count) values($1,$2,$3,0,5)', [foreign, other, ops[0].key.title_id])
  await database.exec('set role authenticated')
  await assert.rejects(command([ops[0]]), { code: '23505' })
  await assert.rejects(command([ops[1]]), { code: '23503' })
  await database.exec('reset role')
  const row = (await database.query('select user_id,episode_count from seasons where id=$1', [foreign])).rows[0]
  assert.equal(row.user_id, other)
  assert.equal(row.episode_count, 5)
})
