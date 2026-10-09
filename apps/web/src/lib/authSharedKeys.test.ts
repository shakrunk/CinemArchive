import { afterEach, expect, it, vi } from 'vitest'
const { getUser, insert, single, select, from }=vi.hoisted(()=>({getUser:vi.fn(),insert:vi.fn(),single:vi.fn(),select:vi.fn(),from:vi.fn()}))
vi.mock('@supabase/supabase-js',()=>({createClient:()=>({auth:{getUser},from})}))
afterEach(()=>{vi.unstubAllEnvs();vi.resetModules();vi.resetAllMocks()})

it('creates a key with explicit authenticated ownership and requested expiry',async()=>{
  vi.stubEnv('VITE_SUPABASE_URL','https://example.supabase.co');vi.stubEnv('VITE_SUPABASE_ANON_KEY','test')
  getUser.mockResolvedValue({data:{user:{id:'owner'}},error:null})
  from.mockReturnValue({insert});insert.mockReturnValue({select});select.mockReturnValue({single});single.mockResolvedValue({data:{id:'key'},error:null})
  const {createSharedKey}=await import('./auth')
  expect(await createSharedKey('Family',new Date('2026-10-09T00:00:00Z'))).toEqual({id:'key'})
  expect(insert).toHaveBeenCalledWith({user_id:'owner',label:'Family',expires_at:'2026-10-09T00:00:00.000Z'})
})

it('does not try to create a key without a signed-in owner',async()=>{
  vi.stubEnv('VITE_SUPABASE_URL','https://example.supabase.co');vi.stubEnv('VITE_SUPABASE_ANON_KEY','test')
  getUser.mockResolvedValue({data:{user:null},error:null})
  const {createSharedKey}=await import('./auth')
  await expect(createSharedKey()).rejects.toThrow('Not signed in')
  expect(from).not.toHaveBeenCalled()
})
