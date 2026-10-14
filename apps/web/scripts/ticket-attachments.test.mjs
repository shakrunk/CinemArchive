import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { createStorageFixture } from './storage-fixture.mjs'

const db = new PGlite({ extensions:{pgcrypto} })
const root = new URL('../../../',import.meta.url)
const owner=randomUUID(),other=randomUUID(),title=randomUUID(),outing=randomUUID()
const metadata={mimeType:'image/png',byteLength:123,sha256:'a'.repeat(64),barcode:{payload:'real ticket code',format:'QR_CODE'}}
async function as(role,user='') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[user])
  if(role!=='postgres') await db.exec(`set role ${role}`)
}
async function prepare(id=randomUUID(),data=metadata,outingId=outing) {
  return (await db.query('select public.prepare_ticket_attachment($1,$2,$3) as r',[id,outingId,data])).rows[0].r
}
async function upload(id,data=metadata) {
  await db.query("insert into storage.objects(bucket_id,name,owner_id,metadata) values('ticket-attachments',$1,$2,$3)",
    [`${owner}/${id}/original`,owner,{size:data.byteLength,mimetype:data.mimeType}])
}
async function finalize(id,previous=null,op=randomUUID()) {
  return (await db.query('select public.finalize_ticket_attachment($1,$2,$3,$4) as r',[op,outing,id,previous])).rows[0].r
}
async function detach(previous,op=randomUUID()) {
  return (await db.query('select public.detach_ticket_attachment($1,$2,$3) as r',[op,outing,previous])).rows[0].r
}
async function receipt(op) { return (await db.query('select public.get_ticket_command_receipt($1) as r',[op])).rows[0].r }
before(async()=>{
  await db.exec(`create role anon; create role authenticated; create role service_role;
    create schema auth;
    create table auth.users(id uuid primary key,email text,raw_user_meta_data jsonb default '{}',raw_app_meta_data jsonb default '{}');
    create function auth.uid() returns uuid language sql stable as
      $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
    grant usage on schema auth to authenticated,anon;
    grant execute on function auth.uid() to authenticated,anon;
    set check_function_bodies=false;`)
  await createStorageFixture(db)
  const schema=(await readFile(new URL('schema.sql',root),'utf8')).replaceAll('\r\n','\n')
  const migration=(await readFile(new URL('supabase/migrations/20261008190944_private_ticket_attachments.sql',root),'utf8')).replaceAll('\r\n','\n')
  assert.ok(schema.includes(migration.trim()),'canonical schema includes complete private attachment migration')
  await db.exec(schema.split('-- Private ticket attachments (20261008190944)')[0])
  await db.exec(migration)
  // Model the existing REST grants explicitly so direct-write tests exercise
  // the association trigger and owner RLS, not a missing table privilege.
  await db.exec('grant select,insert,update,delete on public.cinema_outings to authenticated')
  for(const id of [owner,other]) await db.query('insert into auth.users(id,email) values($1,$2)',[id,`${id}@example.test`])
  await db.query("insert into public.titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,888,'movie','Ticket film',2026,'watchlist')",[title,owner])
  await db.query(`insert into public.cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes,venue,ticket_image_path,ticket_barcode_payload)
    values($1,$2,$3,now()+interval '1 day',now()+interval '1 day 2 hours',120,'Original venue','/legacy/private.jpg','legacy code')`,[outing,owner,title])
},{timeout:60000})
after(async()=>db.close())

test('prepare is immutable, owner scoped, and keeps private object names free of ticket content',async()=>{
  const id=randomUUID()
  await as('authenticated',owner)
  const first=await prepare(id)
  assert.equal(first.state,'prepared')
  assert.equal(first.attachment.objectKey,`${owner}/${id}/original`)
  assert.deepEqual(await prepare(id),first)
  await assert.rejects(prepare(id,{...metadata,sha256:'b'.repeat(64)}),{code:'22023'})
  await as('authenticated',other)
  assert.equal((await db.query('select * from public.ticket_attachments')).rows.length,0)
  await assert.rejects(prepare(id),{code:'42501'})
  await as('anon')
  await assert.rejects(prepare(id),{code:'42501'})
  await as('authenticated')
  await assert.rejects(prepare(id),{code:'42501'})
})

test('metadata rejects unsupported bytes, types, digest, barcode and arbitrary fields',async()=>{
  await as('authenticated',owner)
  for(const invalid of [
    {...metadata,mimeType:'text/html'},{...metadata,byteLength:0},{...metadata,byteLength:20971521},
    {...metadata,byteLength:1.5},{...metadata,sha256:'invalid'},{...metadata,objectKey:'foreign/key'},
    {...metadata,barcode:{payload:'a',format:null}},{...metadata,barcode:{payload:'',format:'QR_CODE'}},
    {...metadata,barcode:{payload:'a',format:'invented'}},{...metadata,barcode:{payload:'x'.repeat(32769),format:'OTHER'}},
  ]) await assert.rejects(prepare(randomUUID(),invalid),{code:'22023'})
})

test('private storage requires a prepared owned key and refuses overwrites',async()=>{
  const id=randomUUID()
  await as('authenticated',owner)
  await assert.rejects(upload(id),{code:'42501'})
  await prepare(id)
  await upload(id)
  await assert.rejects(upload(id),{code:'23505'})
  assert.equal((await db.query("update storage.objects set metadata='{}' where name=$1 returning id",[`${owner}/${id}/original`])).rows.length,0)
  await as('authenticated',other)
  assert.equal((await db.query("select * from storage.objects where bucket_id='ticket-attachments'")).rows.length,0)
  await assert.rejects(upload(id),{code:'42501'})
})

test('finalize validates upload and publishes a causal receipt without changing legacy/private outing fields',async()=>{
  const id=randomUUID(),op=randomUUID()
  await as('authenticated',owner)
  await prepare(id)
  await assert.rejects(finalize(id,null,op),{code:'22023'})
  assert.equal(await receipt(op),null)
  await upload(id)
  const ack=await finalize(id,null,op)
  assert.equal(ack.attachment.id,id)
  assert.equal(ack.rows[0].row.ticket_image_path,'/legacy/private.jpg')
  assert.equal(ack.rows[0].row.ticket_barcode_payload,'legacy code')
  assert.equal(ack.rows[0].row.venue,'Original venue')
  assert.equal(ack.rows[0].row.ticket_attachment_managed,true)
  assert.deepEqual(ack.request,{kind:'ticket.attach',outingId:outing,attachmentId:id,expectedAttachmentId:null})
  assert.deepEqual(await finalize(id,null,op),ack)
  assert.deepEqual(await receipt(op),ack)
  const patch=[{table:'cinema_outings',action:'update',key:{id:outing},values:{venue:'New venue'},expectedOperationId:op}]
  const edited=(await db.query('select public.apply_library_command($1,$2) as r',[randomUUID(),patch])).rows[0].r
  assert.equal(edited.rows[0].row.ticket_attachment_id,id)
  assert.equal(edited.rows[0].row.venue,'New venue')
  await detach(id)
  const detached=(await db.query('select public.get_outing_ticket_attachments() as r')).rows[0].r
  assert.deepEqual(detached,[{outingId:outing,managed:true,attachment:null}])
})

test('association CAS preserves the winner and retained replacement bytes',async()=>{
  const a=randomUUID(),b=randomUUID(),op=randomUUID()
  await as('authenticated',owner)
  for(const id of [a,b]) { await prepare(id); await upload(id) }
  const original=await finalize(a,null,op)
  await assert.rejects(finalize(b),{code:'40001'})
  await finalize(b,a)
  assert.deepEqual(await finalize(a,null,op),original)
  assert.deepEqual(await receipt(op),original)
  const descriptors=(await db.query('select public.get_outing_ticket_attachments() as r')).rows[0].r
  assert.deepEqual(descriptors.map(row=>row.attachment.id),[b])
  await assert.rejects(detach(a),{code:'40001'})
  await detach(b)
  assert.equal((await prepare(a)).state,'retired')
  await assert.rejects(finalize(a),{code:'40001'})
})

test('direct association updates and command UUID reuse cannot bypass the managed API',async()=>{
  const id=randomUUID(),op=randomUUID()
  await as('authenticated',owner)
  await prepare(id); await upload(id)
  await assert.rejects(db.query('update public.cinema_outings set ticket_attachment_id=$1 where id=$2',[id,outing]),{code:'42501',message:'Use the ticket attachment API'})
  await finalize(id,null,op)
  await assert.rejects(detach(id,op),{code:'22023'})
  await assert.rejects(db.query('select public.apply_library_command($1,$2)',[op,[{table:'titles',action:'update',key:{id:title},values:{notes:'not a ticket'}}]]),{code:'22023'})
  const generic=randomUUID()
  await db.query('select public.apply_library_command($1,$2)',[generic,[{table:'titles',action:'update',key:{id:title},values:{notes:'owner edit'}}]])
  await assert.rejects(receipt(generic),{code:'22023'})
  await detach(id)
})

test('broad existing Storage policies still cannot expose, overwrite or delete ticket bytes',async()=>{
  await as('postgres')
  await db.exec('create policy unrelated_broad_policy on storage.objects for all to public using(true) with check(true)')
  await as('anon')
  assert.equal((await db.query("select * from storage.objects where bucket_id='ticket-attachments'")).rows.length,0)
  await assert.rejects(upload(randomUUID()),{code:'42501'})
  await as('authenticated',other)
  assert.equal((await db.query("select * from storage.objects where bucket_id='ticket-attachments'")).rows.length,0)
  await as('authenticated',owner)
  assert.equal((await db.query("update storage.objects set metadata='{}' where bucket_id='ticket-attachments' returning id")).rows.length,0)
  assert.equal((await db.query("delete from storage.objects where bucket_id='ticket-attachments' returning id")).rows.length,0)
})

test('cleanup requires a service claim and actual byte deletion, and old receipts survive',async()=>{
  const id=randomUUID(),op=randomUUID()
  await as('authenticated',owner)
  await prepare(id); await upload(id)
  const original=await finalize(id,null,op)
  await detach(id)
  await assert.rejects(db.query('select public.claim_ticket_attachment_cleanup(100)'),{code:'42501'})
  await as('postgres')
  await db.query("update public.ticket_attachments set retired_at=now()-interval '8 days' where id=$1",[id])
  await as('service_role')
  const claims=(await db.query('select public.claim_ticket_attachment_cleanup(100) as r')).rows[0].r
  assert.ok(claims.some(row=>row.attachmentId===id))
  await assert.rejects(db.query('select public.finish_ticket_attachment_cleanup($1)',[id]),{code:'22023'})
  await as('authenticated',owner)
  await assert.rejects(finalize(id),{code:'40001'})
  await assert.rejects(upload(id),{code:'42501'})
  await as('postgres')
  // Simulates the Storage API's metadata removal; no physical service is in this fixture.
  await db.query('delete from storage.objects where name=$1',[`${owner}/${id}/original`])
  await as('service_role')
  await db.query('select public.finish_ticket_attachment_cleanup($1)',[id])
  await db.query('select public.finish_ticket_attachment_cleanup($1)',[id])
  await as('authenticated',owner)
  assert.deepEqual(await receipt(op),original)
  assert.equal((await prepare(id)).state,'retired')
  await assert.rejects(prepare(id,{...metadata,sha256:'b'.repeat(64)}),{code:'22023'})
})

test('wrong upload metadata and unrelated owners cannot finalize or discover a ticket',async()=>{
  const id=randomUUID()
  await as('authenticated',owner)
  await prepare(id)
  await upload(id,{...metadata,byteLength:99})
  await assert.rejects(finalize(id),{code:'22023'})
  await as('authenticated',other)
  await assert.rejects(finalize(id),{code:'42501'})
  assert.deepEqual((await db.query('select public.get_outing_ticket_attachments() as r')).rows[0].r,[])
  await as('anon')
  await assert.rejects(db.query('select public.get_outing_ticket_attachments()'),{code:'42501'})
})

test('prepared attachments cannot move between outings and managed mode cannot be reset by a normal edit',async()=>{
  const second=randomUUID(),id=randomUUID()
  await as('postgres')
  await db.query(`insert into public.cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes)
    values($1,$2,$3,now()+interval '1 day',now()+interval '1 day 2 hours',120)`,[second,owner,title])
  await as('authenticated',owner)
  await prepare(id,metadata,second); await upload(id)
  await assert.rejects(finalize(id),{code:'42501'})
  await assert.rejects(prepare(id),{code:'22023'})
  await assert.rejects(db.query('update public.cinema_outings set ticket_attachment_managed=false where id=$1',[outing]),
    {code:'42501',message:'Use the ticket attachment API'})
})

test('cleanup skips active references and fresh prepared uploads, and deleting an outing retires its attachments',async()=>{
  const active=randomUUID(),fresh=randomUUID(),abandoned=randomUUID()
  await as('authenticated',owner)
  for(const id of [active,fresh,abandoned]) await prepare(id)
  await upload(active); await finalize(active)
  await as('postgres')
  await db.query("update public.ticket_attachments set created_at=now()-interval '31 days',last_prepared_at=now()-interval '31 days' where id=any($1::uuid[])",[[active,fresh,abandoned]])
  await as('authenticated',owner)
  await prepare(fresh) // An active retry renews its preparation lease.
  await as('service_role')
  const claims=(await db.query('select public.claim_ticket_attachment_cleanup(100) as r')).rows[0].r.map(row=>row.attachmentId)
  assert.ok(claims.includes(abandoned))
  assert.ok(!claims.includes(active) && !claims.includes(fresh))
  await as('authenticated',owner)
  await db.query('delete from public.cinema_outings where id=$1',[outing])
  const states=(await db.query('select id,state from public.ticket_attachments where id=any($1::uuid[])',[[active,fresh]])).rows
  assert.ok(states.every(row=>row.state==='retired'))
})
