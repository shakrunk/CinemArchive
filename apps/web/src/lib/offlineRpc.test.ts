import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createCommand } from './offline/commands'
import { assertDeliverableCommand, classifyLibraryError, createLibraryCommandDelivery, libraryOperations } from './offlineRpc'
import { title } from './offline/fixtures.test-support'
import type { DeliveryContext } from './offline/coordinator'
import type { OfflineSnapshot } from './offline/snapshot'
import { venueChange } from './venueNotes'

const { getSession }=vi.hoisted(()=>({getSession:vi.fn()}))
vi.mock('./auth',()=>({supabase:{auth:{getSession}}}))
const scope={projectId:'https://example.supabase.co',userId:'owner-a'}
const snapshot:OfflineSnapshot={titles:[],outings:[],lists:[],listMemberships:{},pinnedModes:{},ledgerWidgets:[]}
const context=():DeliveryContext=>({scope,signal:new AbortController().signal,isCurrent:()=>true})
const command=()=>createCommand(scope,{kind:'title.patch',titleId:'title',patch:{rating:null,notes:'Preserve intent'}})

beforeEach(()=>{
  vi.stubEnv('VITE_SUPABASE_URL',scope.projectId)
  vi.stubEnv('VITE_SUPABASE_ANON_KEY','public-test-key')
  getSession.mockResolvedValue({data:{session:{user:{id:scope.userId},access_token:'owner-a-token'}},error:null})
})
afterEach(()=>{vi.unstubAllEnvs();vi.unstubAllGlobals();vi.clearAllMocks()})

describe('atomic command payloads',()=>{
  it('keeps imported provenance an immutable natural identity', () => {
    const pending = createCommand(scope, { kind: 'external.link', titleId: 'title', provider: 'letterboxd', externalId: 'film-42' })
    expect(libraryOperations(pending)).toEqual([{ table: 'external_title_links', action: 'insert', key: { provider: 'letterboxd', external_id: 'film-42' }, values: { title_id: 'title' } }])
  })
  it('admits large title graphs above the previous limit and rejects oversized work before saving', () => {
    const pending = createCommand(scope, { kind: 'title.create', title: { ...title, cast: Array.from({ length: 2100 }, (_, i) => ({ tmdbPersonId: i + 1, name: 'Actor', order: i })) } })
    expect(libraryOperations(pending).length).toBeGreaterThan(2048)
    expect(() => assertDeliverableCommand(pending)).not.toThrow()
    const oversized = { ...pending, mutation: { kind: 'title.patch' as const, titleId: title.id, patch: { notes: 'x'.repeat(16 * 1024 * 1024) } } }
    expect(() => assertDeliverableCommand(oversized)).toThrow('more data')
    const nested = { ...pending, mutation: { kind: 'title.patch' as const, titleId: title.id, patch: { genres: Array.from({ length: 50_000 }, () => 'x'.repeat(330)) } } }
    expect(new TextEncoder().encode(JSON.stringify(libraryOperations(nested))).byteLength).toBeLessThan(16 * 1024 * 1024)
    expect(() => assertDeliverableCommand(nested)).toThrow('more data')
  })
  it('maps compound row guards and predecessor receipts without mutating journal payloads', () => {
    const pending = createCommand(scope, { kind: 'batch', mutations: [
      { kind: 'title.patch', titleId: 'title', patch: { notes: 'second edit' } },
      { kind: 'viewing.delete', titleId: 'title', viewingId: 'viewing' },
    ] }, { preconditions: [
      { table: 'titles', id: 'title', afterCommandId: 'first-operation' },
      { table: 'viewings', id: 'viewing', updatedAt: '2026-10-08T00:00:00Z' },
    ] })
    const original = JSON.stringify(pending)
    expect(libraryOperations(pending)).toMatchObject([
      { expectedOperationId: 'first-operation' }, { expectedUpdatedAt: '2026-10-08T00:00:00Z' },
    ])
    expect(JSON.stringify(pending)).toBe(original)
    expect(() => libraryOperations({ ...pending, preconditions: [{ table: 'titles', id: 'unmatched', updatedAt: '2026-10-08T00:00:00Z' }] })).toThrow('must match')
  })

  it('maps omission and explicit null without inventing IDs or clocks',()=>{
    const pending=command()
    const first=libraryOperations(pending)
    expect(first).toEqual([{table:'titles',action:'update',key:{id:'title'},values:{rating:null,notes:'Preserve intent'}}])
    expect(libraryOperations(pending)).toEqual(first)
  })
  it('keeps independent episode event identities and original record time',()=>{
    const pending=createCommand(scope,{kind:'episode.log',titleId:'title',episodeId:'episode',watchEvent:{id:'watch'},
      rating:{id:'rating',rating:3.5,ratedAt:'2026-10-08T00:00:00Z'},review:{id:'review',reviewText:'Notes',reviewedAt:'2026-10-08T00:00:00Z'}},
    {createdAt:'2026-10-08T01:00:00Z'})
    const operations=libraryOperations(pending)
    expect(operations.map(op=>op.key.id)).toEqual(['watch','rating','review'])
    expect(operations[0].values).toMatchObject({watched_at:null,created_at:pending.createdAt})
    expect(operations[1].values).toMatchObject({rating:3.5,rated_at:'2026-10-08T00:00:00Z'})
  })
  it('maps a compound action to one ordered transaction with natural membership identity',()=>{
    const pending=createCommand(scope,{kind:'batch',mutations:[
      {kind:'list.create',list:{id:'list',name:'Favorites',description:null,createdAt:'2026-10-08',updatedAt:'2026-10-08'}},
      {kind:'membership.set',listId:'list',titleId:'title',present:true},
      {kind:'pin.set',titleId:'title',easterEggKey:'spider-noir',variant:'bw'},
    ]})
    const operations=libraryOperations(pending)
    expect(operations.map(op=>op.table)).toEqual(['lists','list_items','user_title_pins'])
    expect(operations[1].key).toEqual({list_id:'list',title_id:'title'})
    expect(operations[2].values).toEqual({pinned_variant:'bw'})
  })
  it('replaces metadata credits in the same transaction as title changes',()=>{
    const pending=createCommand(scope,{kind:'title.patch',titleId:'title',patch:{cast:[],crew:[{tmdbPersonId:42,name:'Director',job:'Director'}]}})
    expect(libraryOperations(pending).map(op=>[op.table,op.action])).toEqual([
      ['titles','update'],['title_cast','delete'],['title_crew','delete'],['title_crew','insert'],
    ])
  })
  it('transmits a revision precondition without changing its representation',()=>{
    const pending=createCommand(scope,{kind:'viewing.patch',titleId:'title',viewingId:'viewing',patch:{date:null}},{baseRevision:'2026-10-08T00:00:00.001Z'})
    expect(libraryOperations(pending)[0]).toMatchObject({values:{viewed_at:null},expectedUpdatedAt:pending.baseRevision})
  })
  it('clears optional local collections using non-null database defaults',()=>{
    const pending=createCommand(scope,{kind:'title.patch',titleId:'title',patch:{physicalMedia:null,studios:null,inHomeCollection:null}})
    expect(libraryOperations(pending)[0].values).toEqual({physical_media:[],studios:[],in_home_collection:false})
    const viewing=createCommand(scope,{kind:'viewing.patch',titleId:'title',viewingId:'viewing',patch:{companions:null}})
    expect(libraryOperations(viewing)[0].values).toEqual({companions:[]})
  })
  it('metadata refresh never resurrects stale watches or overwrites watched progress',()=>{
    const pending=createCommand(scope,{kind:'season.put',titleId:'title',season:{id:'season',seasonNumber:1,episodeCount:1,episodesWatched:0,
      episodes:[{id:'episode',episodeNumber:1,watchEvents:[{id:'deleted-watch'}],ratings:[],reviews:[]}]}})
    const operations=libraryOperations(pending)
    expect(operations.some(op=>op.table==='episode_watch_events')).toBe(false)
    expect(operations.find(op=>op.table==='seasons' && op.action==='update')?.values).not.toHaveProperty('episodes_watched')
  })
})

describe('account-fenced delivery',()=>{
  it.each(['40001', '23505'])('records venue rejection only from the write transaction (%s)', async (code) => {
    const ownerScope = { ...scope, userId: '10000000-0000-4000-8000-000000000001' }
    getSession.mockResolvedValue({ data: { session: { user: { id: ownerScope.userId }, access_token: 'captured' } }, error: null })
    const pending = createCommand(ownerScope, venueChange({ venue: '__proto__', userId: ownerScope.userId, baseline: null }, ''))
    const deliveryContext = { ...context(), scope: ownerScope }
    const request = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ code, message: 'Changed' }), { status: 409 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ operationId: pending.id })))
    vi.stubGlobal('fetch', request)
    const refresh = vi.fn().mockRejectedValue(Object.assign(new Error('Refresh failed after acceptance'), { code }))
    const deliver = createLibraryCommandDelivery(refresh)
    expect(await deliver(pending, deliveryContext)).toMatchObject({ kind: 'conflict', venueRejection: true })
    expect(refresh).not.toHaveBeenCalled()
    await expect(deliver(pending, deliveryContext)).rejects.toThrow('after acceptance')
    expect(request.mock.calls[0][1].body).toBe(request.mock.calls[1][1].body)
  })
  it('keeps a venue receipt queued until authoritative refresh without replaying historical receipt text', async () => {
    const ownerScope = { ...scope, userId: '10000000-0000-4000-8000-000000000001' }
    getSession.mockResolvedValue({ data: { session: { user: { id: ownerScope.userId }, access_token: 'captured' } }, error: null })
    const pending = createCommand(ownerScope, venueChange({ venue: "O'Brien", userId: ownerScope.userId, baseline: null }, 'Original'))
    const request = vi.fn().mockImplementation(async () => new Response(JSON.stringify({ operationId: pending.id, rows: [{ table: 'venue_notes', row: { notes: 'Original' } }] })))
    vi.stubGlobal('fetch', request)
    const canonical = { ...snapshot, moviegoingPreferencesSupport: 'authoritative', venueNotes: [], theaterInterest: [] }
    const deliver = createLibraryCommandDelivery(vi.fn().mockResolvedValueOnce({ ...snapshot, moviegoingPreferencesSupport: 'unsupported' }).mockResolvedValueOnce(canonical))
    const deliveryContext = { ...context(), scope: ownerScope }
    expect((await deliver(pending, deliveryContext)).kind).toBe('retry')
    expect(await deliver(pending, deliveryContext)).toEqual({ kind: 'success', canonicalBase: canonical })
    expect(request.mock.calls[0][1].body).toBe(request.mock.calls[1][1].body)
    expect(JSON.parse(request.mock.calls[0][1].body).p_operations[0].key).toEqual({ venue: "O'Brien" })
  })
  it('retains accepted interest until an authoritative read and retries the exact original operation', async () => {
    const interestScope = { ...scope, userId: '10000000-0000-4000-8000-000000000001' }
    getSession.mockResolvedValue({ data: { session: { user: { id: interestScope.userId }, access_token: 'captured' } }, error: null })
    const pending = createCommand(interestScope, { kind: 'theaterInterest.set', titleId: '20000000-0000-4000-8000-000000000001', userId: interestScope.userId, present: true, createdAt: '2026-10-08T00:00:00Z' })
    const request = vi.fn().mockImplementation(async () => new Response(JSON.stringify({ operationId: pending.id, rows: [{ table: 'theater_interest', row: { id: pending.mutation } }] })))
    vi.stubGlobal('fetch', request)
    const canonical = { ...snapshot, moviegoingPreferencesSupport: 'authoritative', venueNotes: [], theaterInterest: [] }
    const fetchBase = vi.fn().mockResolvedValueOnce({ ...snapshot, moviegoingPreferencesSupport: 'unsupported' }).mockResolvedValueOnce(canonical)
    const deliver = createLibraryCommandDelivery(fetchBase), deliveryContext = { ...context(), scope: interestScope }
    expect((await deliver(pending, deliveryContext)).kind).toBe('retry')
    expect(await deliver(pending, deliveryContext)).toEqual({ kind: 'success', canonicalBase: canonical })
    expect(request.mock.calls[0][1].body).toBe(request.mock.calls[1][1].body)
    // The original insert receipt cannot resurrect an interest removed on another device.
    expect(canonical.theaterInterest).toEqual([])
  })
  it('captures the matching token and returns fresh canonical state',async()=>{
    const pending=command(), fetchBase=vi.fn().mockResolvedValue(snapshot)
    const request=vi.fn().mockResolvedValue(new Response(JSON.stringify({operationId:pending.id,rows:[]})))
    vi.stubGlobal('fetch',request)
    const result=await createLibraryCommandDelivery(fetchBase)(pending,context())
    expect(result).toEqual({kind:'success',canonicalBase:snapshot})
    expect(request.mock.calls[0][1].headers.Authorization).toBe('Bearer owner-a-token')
    expect(JSON.parse(request.mock.calls[0][1].body).p_operation_id).toBe(pending.id)
    expect(fetchBase).toHaveBeenCalledOnce()
  })
  it('does not send owner A work with owner B credentials',async()=>{
    getSession.mockResolvedValue({data:{session:{user:{id:'owner-b'},access_token:'owner-b-token'}},error:null})
    const request=vi.fn();vi.stubGlobal('fetch',request)
    const result=await createLibraryCommandDelivery(vi.fn())(command(),context())
    expect(result.kind).toBe('auth');expect(request).not.toHaveBeenCalled()
  })
  it('fences a switch while authentication is being read',async()=>{
    let active=true
    getSession.mockImplementation(async()=>{active=false;return {data:{session:{user:{id:scope.userId},access_token:'owner-a-token'}},error:null}})
    const request=vi.fn();vi.stubGlobal('fetch',request)
    const result=await createLibraryCommandDelivery(vi.fn())(command(),{...context(),isCurrent:()=>active})
    expect(result.kind).toBe('auth');expect(request).not.toHaveBeenCalled()
  })
  it('retains unknown outcomes when the post-commit refresh fails',async()=>{
    const pending=command(), fetchBase=vi.fn().mockRejectedValue(new Error('offline'))
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({operationId:pending.id}))))
    await expect(createLibraryCommandDelivery(fetchBase)(pending,context())).rejects.toThrow('offline')
  })
  it('never acknowledges an unexpected receipt',async()=>{
    const fetchBase=vi.fn()
    vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response(JSON.stringify({operationId:'wrong'}))))
    expect((await createLibraryCommandDelivery(fetchBase)(command(),context())).kind).toBe('retry')
    expect(fetchBase).not.toHaveBeenCalled()
  })
  it('separates conflicts, invalid data, authentication and temporary service failures',()=>{
    expect(classifyLibraryError(409,'23505','duplicate').kind).toBe('conflict')
    expect(classifyLibraryError(400,'40001','changed').kind).toBe('conflict')
    expect(classifyLibraryError(400,'23514','invalid').kind).toBe('failed')
    expect(classifyLibraryError(401,'PGRST301','expired').kind).toBe('auth')
    expect(classifyLibraryError(503,undefined,'down').kind).toBe('retry')
  })
})
