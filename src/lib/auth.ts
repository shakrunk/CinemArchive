import { createClient, type SupabaseClient, type User } from '@supabase/supabase-js'

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL as string | undefined
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined

/** True once VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY are both set. */
export const isSupabaseConfigured = Boolean(supabaseUrl && supabaseAnonKey)

export const supabase: SupabaseClient | null = isSupabaseConfigured
  ? createClient(supabaseUrl as string, supabaseAnonKey as string)
  : null

function getClient(): SupabaseClient {
  if (!supabase) {
    throw new Error(
      'Supabase is not configured — set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY.'
    )
  }
  return supabase
}

// ─── Passkey / WebAuthn helpers ─────────────────────────────────────────────

/** Start passkey registration for the current authenticated user. */
export async function registerPasskey() {
  const { data, error } = await getClient().auth.mfa.enroll({ factorType: 'webauthn' })
  if (error) throw error
  return data
}

/** Authenticate using a passkey (WebAuthn assertion). */
export async function signInWithPasskey(email: string) {
  // Phase 1: initiate the challenge
  const { data: challengeData, error: challengeError } =
    await getClient().auth.signInWithOtp({ email })
  if (challengeError) throw challengeError

  return challengeData
}

/** Sign in with email magic link (fallback when passkey unavailable). */
export async function signInWithEmail(email: string) {
  const { data, error } = await getClient().auth.signInWithOtp({
    email,
    options: {
      emailRedirectTo: window.location.origin,
    },
  })
  if (error) throw error
  return data
}

/** Sign out the current user. */
export async function signOut() {
  const { error } = await getClient().auth.signOut()
  if (error) throw error
}

/** Get the currently authenticated user. */
export async function getCurrentUser() {
  const { data, error } = await getClient().auth.getUser()
  if (error) throw error
  return data.user
}

/** Subscribe to auth state changes. */
export function onAuthStateChange(callback: (user: User | null) => void) {
  const { data } = getClient().auth.onAuthStateChange((_event, session) => {
    callback(session?.user ?? null)
  })
  return data.subscription
}

// ─── Shared Access Key helpers ───────────────────────────────────────────────

/** Set the shared token for the current Supabase session (enables read-only RLS). */
export function setSharedToken(token: string) {
  return getClient().rpc('set_shared_token', { token })
}

/** Create a new shared access key for the authenticated user. */
export async function createSharedKey(label?: string, expiresAt?: Date) {
  const { data, error } = await getClient()
    .from('shared_access_keys')
    .insert({ label, expires_at: expiresAt?.toISOString() })
    .select()
    .single()
  if (error) throw error
  return data
}

/** Revoke a shared access key. */
export async function revokeSharedKey(id: string) {
  const { error } = await getClient()
    .from('shared_access_keys')
    .update({ is_active: false })
    .eq('id', id)
  if (error) throw error
}

/** List all shared keys for the authenticated user. */
export async function listSharedKeys() {
  const { data, error } = await getClient()
    .from('shared_access_keys')
    .select('*')
    .order('created_at', { ascending: false })
  if (error) throw error
  return data
}
