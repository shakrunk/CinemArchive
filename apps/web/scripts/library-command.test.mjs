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
