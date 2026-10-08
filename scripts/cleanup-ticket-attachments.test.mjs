import { test } from 'node:test'
import assert from 'node:assert/strict'
import { cleanupTicketAttachments } from './cleanup-ticket-attachments.mjs'

const owner='10000000-0000-4000-8000-000000000001'
const id='20000000-0000-4000-8000-000000000001'
const claim={attachmentId:id,objectKey:`${owner}/${id}/original`}
function setup(responses) {
  const calls=[]
  return {calls,run:()=>cleanupTicketAttachments({url:'https://project.example.test',serviceKey:'test-only-key',fetchImpl:async(url,init)=>{
    calls.push({path:url.pathname,method:init.method,body:JSON.parse(init.body),headers:init.headers,redirect:init.redirect})
    const next=responses.shift()
    assert.ok(next,'unexpected request')
    return new Response(next.body===undefined?null:JSON.stringify(next.body),{status:next.status??200})
  }})}
}
test('claims first, deletes bytes through Storage, then acknowledges exactly that attachment',async()=>{
  const {calls,run}=setup([{body:[claim]}, {}, {}])
  assert.deepEqual(await run(),{claimed:1,removed:1,failed:0})
  assert.deepEqual(calls.map(c=>[c.method,c.path]),[
    ['POST','/rest/v1/rpc/claim_ticket_attachment_cleanup'],['DELETE','/storage/v1/object/ticket-attachments'],['POST','/rest/v1/rpc/finish_ticket_attachment_cleanup'],
  ])
  assert.deepEqual(calls[1].body,{prefixes:[claim.objectKey]})
  assert.deepEqual(calls[2].body,{p_attachment_id:id})
  assert.ok(calls.every(c=>c.redirect==='error'&&c.headers.Authorization==='Bearer test-only-key'))
})
test('a failed byte deletion is never acknowledged',async()=>{
  const {calls,run}=setup([{body:[claim]},{status:500}])
  assert.deepEqual(await run(),{claimed:1,removed:0,failed:1})
  assert.equal(calls.length,2)
})
test('failed completion remains retryable without logging private details',async()=>{
  const {run}=setup([{body:[claim]},{},{status:503,body:{secret:'must not be returned'}}])
  assert.deepEqual(await run(),{claimed:1,removed:0,failed:1})
})
test('malformed or mismatched object claims never reach Storage',async()=>{
  const {calls,run}=setup([{body:[{...claim,objectKey:'../other-bucket/private'}, {...claim,attachmentId:owner}]}])
  assert.deepEqual(await run(),{claimed:2,removed:0,failed:2})
  assert.equal(calls.length,1)
})
test('empty and malformed claims have no deletion side effects',async()=>{
  const empty=setup([{body:[]}])
  assert.deepEqual(await empty.run(),{claimed:0,removed:0,failed:0})
  const invalid=setup([{body:{objects:[claim]}}])
  await assert.rejects(invalid.run(),/Invalid ticket cleanup claim/)
  assert.equal(invalid.calls.length,1)
})
test('rejects missing credentials or an insecure remote target before any request',async()=>{
  const fetchImpl=()=>{throw new Error('must not fetch')}
  await assert.rejects(cleanupTicketAttachments({url:'https://example.test',fetchImpl}),/required/)
  await assert.rejects(cleanupTicketAttachments({url:'http://example.test',serviceKey:'test',fetchImpl}),/HTTPS/)
  await assert.rejects(cleanupTicketAttachments({url:'https://example.test/unexpected',serviceKey:'test',fetchImpl}),/origin/)
})
