import type { User } from '@supabase/supabase-js'

// Dev-only stand-in for a real Supabase session. Lets "Sign in" show
// signed-in-only UI immediately on the dev server without a real passkey/
// magic-link round trip. Never reachable in production (gated by
// import.meta.env.DEV at the call site in TopBar).
export const DEV_MOCK_USER_ID = 'dev-mock-user'

export const DEV_MOCK_USER: User = {
  id: DEV_MOCK_USER_ID,
  app_metadata: {},
  user_metadata: {},
  aud: 'authenticated',
  created_at: '2024-01-01T00:00:00.000Z',
  email: 'dev@localhost',
}

export function isDevMockUser(user: { id: string } | null | undefined): boolean {
  return user?.id === DEV_MOCK_USER_ID
}
