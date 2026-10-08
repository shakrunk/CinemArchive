import { beforeEach, expect, it, vi } from 'vitest'
import { fetchSharedLibrary } from './db'

const { rpc, from }=vi.hoisted(()=>({rpc:vi.fn(),from:vi.fn()}))
vi.mock('./auth',()=>({supabase:{rpc,from}}))
beforeEach(()=>{vi.resetAllMocks()})
const row=(id:string,owner='owner')=>({id,user_id:owner,tmdb_id:42,type:'movie',title:id,year:2026,
  genres:[],tags:[],status:'watchlist',added_at:'2026-10-08',viewings:[],seasons:[],episodes:[],title_cast:[],title_crew:[]})
const page=(titles:ReturnType<typeof row>[],hasMore=false,ownerUserId='owner',ledgerLayout:unknown=null)=>
  ({data:{titles,hasMore,ownerUserId,ledgerLayout},error:null})

it('reads a valid empty library and board in one stateless request',async()=>{
  rpc.mockResolvedValue(page([],false,'owner',[]))
  expect(await fetchSharedLibrary('secret')).toEqual({titles:[],ownerUserId:'owner',ledgerWidgets:[]})
  expect(rpc).toHaveBeenCalledExactlyOnceWith('get_shared_library',{p_token:'secret',p_offset:0,p_limit:100})
  expect(from).not.toHaveBeenCalled()
})

it('paginates complete nested results and deduplicates rows shifted between pages',async()=>{
  rpc.mockResolvedValueOnce(page([row('a'),row('b')],true)).mockResolvedValueOnce(page([row('b'),row('c')]))
  const result=await fetchSharedLibrary('secret')
  expect(result.titles.map(title=>title.id)).toEqual(['a','b','c'])
  expect(rpc).toHaveBeenLastCalledWith('get_shared_library',{p_token:'secret',p_offset:2,p_limit:100})
  expect(from).not.toHaveBeenCalled()
})

it('never turns an invalid link into a successful empty library',async()=>{
  rpc.mockResolvedValue({data:null,error:{code:'42501',message:'Invalid or expired share link'}})
  await expect(fetchSharedLibrary('expired')).rejects.toMatchObject({code:'42501'})
})

it('fails the complete read when a later page fails rather than publishing partial data',async()=>{
  rpc.mockResolvedValueOnce(page([row('a')],true)).mockResolvedValueOnce({data:null,error:{code:'503',message:'Offline'}})
  await expect(fetchSharedLibrary('secret')).rejects.toMatchObject({message:'Offline'})
})

it('rejects an owner change or foreign-owned title during pagination',async()=>{
  rpc.mockResolvedValueOnce(page([row('a')],true)).mockResolvedValueOnce(page([],false,'other'))
  await expect(fetchSharedLibrary('secret')).rejects.toThrow('owner changed')
  rpc.mockResolvedValueOnce(page([row('b','other')]))
  await expect(fetchSharedLibrary('secret')).rejects.toThrow('title owner')
})

it('stops malformed or nonadvancing pagination',async()=>{
  rpc.mockResolvedValueOnce(page([],true))
  await expect(fetchSharedLibrary('secret')).rejects.toThrow('did not advance')
  rpc.mockResolvedValueOnce({data:{titles:[],hasMore:false},error:null})
  await expect(fetchSharedLibrary('secret')).rejects.toThrow('Invalid shared library response')
})
