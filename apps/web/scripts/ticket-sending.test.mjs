import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'
import { createStorageFixture } from './storage-fixture.mjs'

// Sending a ticket to a friend (#220): docs/android-contracts/ticket-sending.md.
const db = new PGlite({ extensions: { pgcrypto } })
const root = new URL('../../../', import.meta.url)
const HEADER = '-- Ticket sending (20261103000000).'
const owner = randomUUID(), sam = randomUUID(), pat = randomUUID(), stranger = randomUUID()
const title = randomUUID(), outing = randomUUID(), bare = randomUUID()
const barcode = { payload: 'REAL-TICKET-CODE-123', format: 'QR_CODE' }
const metadata = { mimeType: 'image/png', byteLength: 123, sha256: 'a'.repeat(64), barcode }
let attachment

async function as(role, user = '') {
  await db.exec('reset role')
  await db.query("select set_config('request.jwt.claim.sub',$1,false)", [user])
  if (role !== 'postgres') await db.exec(`set role ${role}`)
}
const call = async (sql, params = []) => (await db.query(sql, params)).rows[0].r
const send = (recipients = [sam], id = outing) => call('select public.send_ticket($1,$2::uuid[]) as r', [id, recipients])
const grants = (id = outing) => call('select public.get_ticket_grants($1) as r', [id])
const received = () => call('select public.get_received_tickets() as r')
const open = (grant) => call('select public.open_received_ticket($1) as r', [grant])
const revoke = (grant) => call('select public.revoke_ticket_grant($1) as r', [grant])
const visibleObjects = async () => (await db.query("select name from storage.objects where bucket_id='ticket-attachments'")).rows.length
async function befriend(a, b) {
  const [x, y] = [a, b].sort()
  await db.query("insert into public.friendships(user_id_a,user_id_b,requested_by,status) values($1,$2,$3,'accepted')", [x, y, a])
}
async function attachTicket(previous = null) {
  const id = randomUUID()
  await db.query('select public.prepare_ticket_attachment($1,$2,$3)', [id, outing, metadata])
  await db.query("insert into storage.objects(bucket_id,name,owner_id,metadata) values('ticket-attachments',$1,$2,$3)",
    [`${owner}/${id}/original`, owner, { size: metadata.byteLength, mimetype: metadata.mimeType }])
  await db.query('select public.finalize_ticket_attachment($1,$2,$3,$4)', [randomUUID(), outing, id, previous])
  return id
}
async function count(sql, params = []) { await as('postgres'); return Number((await db.query(`select count(*) as n from ${sql}`, params)).rows[0].n) }

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
  const migration = (await readFile(new URL('supabase/migrations/20261103000000_ticket_sending.sql', root), 'utf8')).replaceAll('\r\n', '\n')
  assert.ok(schema.includes(migration.trim()), 'canonical schema includes the complete ticket sending migration')
  await db.exec(schema.split(HEADER)[0])
  await db.exec(migration)
  await db.exec('grant select,insert,update,delete on public.cinema_outings,public.friendships,public.notifications to authenticated')
  for (const id of [owner, sam, pat, stranger]) await db.query('insert into auth.users(id,email) values($1,$2)', [id, `${id}@example.test`])
  for (const [id, name] of [[owner, 'Olive Owner'], [sam, 'Sam Friend'], [pat, 'Pat Friend']]) {
    await db.query(`insert into public.profiles(user_id,email,display_name) values($1,$2,$3)
      on conflict (user_id) do update set display_name=excluded.display_name`, [id, `${id}@example.test`, name])
  }
  await db.query("insert into public.titles(id,user_id,tmdb_id,type,title,year,status) values($1,$2,808,'movie','Ticket film',2026,'watchlist')", [title, owner])
  for (const id of [outing, bare]) {
    await db.query(`insert into public.cinema_outings(id,user_id,title_id,showtime,ends_at,runtime_minutes,venue,booking_ref,seat_row,seats)
      values($1,$2,$3,now()+interval '1 day',now()+interval '1 day 2 hours',120,'Cinema','PRIVATE-BOOKING','F','["F1","F2"]')`, [id, owner, title])
  }
  await befriend(owner, sam)
  await befriend(owner, pat)
  await as('authenticated', owner)
  attachment = await attachTicket()
}, { timeout: 60000 })
after(async () => db.close())

test('an owner sends a ticket; the friend is told without the code, and sees it listed', async () => {
  await as('authenticated', owner)
  const sent = await send()
  assert.deepEqual(sent.map((g) => [g.user_id, g.name, g.opened_at]), [[sam, 'Sam Friend', null]])
  await as('postgres')
  const note = (await db.query("select * from public.notifications where type='ticket_received'")).rows[0]
  assert.equal(note.recipient_id, sam)
  assert.equal(note.actor_id, owner)
  assert.equal(note.payload.grant_id, sent[0].grant_id)
  assert.ok(!JSON.stringify(note.payload).includes('REAL-TICKET-CODE') && !JSON.stringify(note.payload).includes('PRIVATE'))
  await as('authenticated', sam)
  const list = await received()
  assert.equal(list.length, 1)
  assert.equal(list[0].sender_name, 'Olive Owner')
  assert.equal(list[0].title, 'Ticket film')
  assert.equal(list[0].opened_at, null)
  assert.ok(!JSON.stringify(list).includes('REAL-TICKET-CODE') && !JSON.stringify(list).includes('PRIVATE') && !JSON.stringify(list).includes('F1'))
  await as('authenticated', stranger)
  assert.deepEqual(await received(), [])
})

test('only a friend with a live grant can read the ticket bytes', async () => {
  await as('authenticated', sam)
  assert.equal(await visibleObjects(), 1)
  await as('authenticated', pat)
  assert.equal(await visibleObjects(), 0, 'a friend who was not sent it sees nothing')
  await as('authenticated', stranger)
  assert.equal(await visibleObjects(), 0)
  await as('anon')
  assert.equal(await visibleObjects(), 0)
  await as('authenticated', owner)
  assert.equal(await visibleObjects(), 1, 'the owner still reads their own ticket')
  await as('authenticated', sam)
  assert.equal((await db.query("delete from storage.objects where bucket_id='ticket-attachments' returning id")).rows.length, 0)
})

test('opening returns the file descriptor and barcode, records the first open and tells the sender once', async () => {
  await as('authenticated', owner)
  const [grant] = await grants()
  await as('authenticated', sam)
  const opened = await open(grant.grant_id)
  assert.equal(opened.id, attachment)
  assert.equal(opened.objectKey, `${owner}/${attachment}/original`)
  assert.deepEqual(opened.barcode, barcode)
  assert.equal(opened.title, 'Ticket film')
  assert.ok(!('seats' in opened) && !JSON.stringify(opened).includes('PRIVATE'))
  await open(grant.grant_id)
  assert.equal(await count("public.notifications where type='ticket_opened'"), 1)
  await as('postgres')
  const note = (await db.query("select * from public.notifications where type='ticket_opened'")).rows[0]
  assert.equal(note.recipient_id, owner)
  assert.equal(note.actor_id, sam)
  await as('authenticated', owner)
  assert.ok((await grants())[0].opened_at)
  await as('authenticated', pat)
  await assert.rejects(open(grant.grant_id), { code: '42501' })
  await as('anon')
  await assert.rejects(open(grant.grant_id), { code: '42501' })
})

test('sending again is idempotent, and sending is validated', async () => {
  await as('authenticated', owner)
  const notes = await count("public.notifications where type='ticket_received'")
  await as('authenticated', owner)
  assert.equal((await send([sam, sam])).length, 1)
  assert.equal(await count("public.notifications where type='ticket_received'"), notes)
  await as('authenticated', owner)
  for (const [recipients, id, code] of [
    [[], outing, '22023'], [[sam, null], outing, '22023'], [Array.from({ length: 9 }, () => randomUUID()), outing, '22023'],
    [[stranger], outing, '42501'], [[owner], outing, '42501'], [[sam], bare, '22023'], [[sam], randomUUID(), '42501'],
  ]) await assert.rejects(send(recipients, id), { code }, `${recipients.length} ${code}`)
  await as('authenticated', sam)
  await assert.rejects(send([owner]), { code: '42501' })
  await as('anon')
  await assert.rejects(send(), { code: '42501' })
})

test('taking a ticket back ends access at once, and sending again restores it and resets "opened"', async () => {
  await as('authenticated', owner)
  const [grant] = await grants()
  await revoke(grant.grant_id)
  await revoke(grant.grant_id)
  assert.deepEqual(await grants(), [])
  await as('authenticated', sam)
  assert.deepEqual(await received(), [])
  assert.equal(await visibleObjects(), 0)
  await assert.rejects(open(grant.grant_id), { code: '42501' })
  await as('authenticated', pat)
  await assert.rejects(revoke(grant.grant_id), { code: '42501' })
  await as('authenticated', owner)
  const again = await send()
  assert.equal(again[0].opened_at, null)
  assert.equal(again[0].grant_id, grant.grant_id)
  await as('authenticated', sam)
  assert.equal(await visibleObjects(), 1)
  assert.equal((await received()).length, 1)
})

test('replacing the ticket ends every grant on the old one', async () => {
  await as('authenticated', owner)
  const next = await attachTicket(attachment)
  assert.notEqual(next, attachment)
  assert.deepEqual(await grants(), [])
  await as('authenticated', sam)
  assert.deepEqual(await received(), [])
  assert.equal(await visibleObjects(), 0)
  await as('authenticated', owner)
  const resent = await send()
  assert.equal(resent.length, 1)
  await as('authenticated', sam)
  assert.equal((await received()).length, 1)
  assert.equal(await visibleObjects(), 1)
  attachment = next
})

test('unfriending ends access without any other step', async () => {
  const [a, b] = [owner, sam].sort()
  await as('postgres')
  await db.query("update public.friendships set status='blocked', blocked_by=$3 where user_id_a=$1 and user_id_b=$2", [a, b, owner])
  await as('authenticated', sam)
  assert.deepEqual(await received(), [])
  assert.equal(await visibleObjects(), 0)
  await as('authenticated', owner)
  assert.deepEqual(await grants(), [])
  await assert.rejects(send([sam]), { code: '42501' })
  await as('postgres')
  await db.query("update public.friendships set status='accepted', blocked_by=null where user_id_a=$1 and user_id_b=$2", [a, b])
  await as('authenticated', sam)
  assert.equal((await received()).length, 1, 'the grant itself was kept, so restoring the friendship restores it')
})

test('a ticket for a show that ended over a day ago drops off the list', async () => {
  await as('postgres')
  await db.query("update public.cinema_outings set showtime=now()-interval '2 days 2 hours', ends_at=now()-interval '2 days' where id=$1", [outing])
  await as('authenticated', sam)
  assert.deepEqual(await received(), [])
})

test('the grants table is closed to clients', async () => {
  await as('authenticated', sam)
  await assert.rejects(db.query('select * from public.ticket_grants'), { code: '42501' })
  await assert.rejects(db.query('update public.ticket_grants set revoked_at=null'), { code: '42501' })
  await assert.rejects(db.query('delete from public.ticket_grants'), { code: '42501' })
  await as('anon')
  await assert.rejects(db.query('select * from public.ticket_grants'), { code: '42501' })
})
