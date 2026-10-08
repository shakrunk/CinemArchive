import { afterEach, expect, it, vi } from 'vitest'
const { signInWithOtp } = vi.hoisted(() => ({ signInWithOtp: vi.fn() }))
vi.mock('@supabase/supabase-js', () => ({ createClient: () => ({ auth: { signInWithOtp } }) }))
afterEach(() => { vi.unstubAllEnvs(); vi.resetModules(); vi.resetAllMocks() })

async function auth() {
  vi.stubEnv('VITE_SUPABASE_URL', 'https://example.supabase.co')
  vi.stubEnv('VITE_SUPABASE_ANON_KEY', 'test')
  return import('./auth')
}

it('requests an email link without creating an uninvited account', async () => {
  signInWithOtp.mockResolvedValue({ data: {}, error: null })
  const { signInWithEmail } = await auth()
  await signInWithEmail('viewer@example.test')
  expect(signInWithOtp).toHaveBeenCalledWith({ email: 'viewer@example.test', options: {
    shouldCreateUser: false, emailRedirectTo: window.location.origin + import.meta.env.BASE_URL,
  } })
})

it('propagates authentication failure instead of reporting a sent link', async () => {
  const failure = new Error('Email service unavailable')
  signInWithOtp.mockResolvedValue({ data: null, error: failure })
  const { signInWithEmail } = await auth()
  await expect(signInWithEmail('viewer@example.test')).rejects.toBe(failure)
})
