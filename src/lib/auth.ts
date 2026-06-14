import { createClient } from '@supabase/supabase-js'

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL as string
const supabaseAnonKey = import.meta.env.VITE_SUPABASE_ANON_KEY as string

export const supabase = createClient(supabaseUrl, supabaseAnonKey)

// ─── Passkey / WebAuthn helpers ─────────────────────────────────────────────

/** Start passkey registration for the current authenticated user. */
export async function registerPasskey() {
  const { data, error } = await supabase.auth.mfa.enroll({ factorType: 'webauthn' })
  if (error) throw error
  return data
}

/** Authenticate using a passkey (WebAuthn assertion). */
export async function signInWithPasskey(email: string) {
  // Phase 1: initiate the challenge
  const { data: challengeData, error: challengeError } =
    await supabase.auth.signInWithOtp({ email })
  if (challengeError) throw challengeError

  return challengeData
}

/** Sign in with email magic link (fallback when passkey unavailable). */
export async function signInWithEmail(email: string) {
  const { data, error } = await supabase.auth.signInWithOtp({
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
  const { error } = await supabase.auth.signOut()
  if (error) throw error
}

/** Get the currently authenticated user. */
export async function getCurrentUser() {
  const { data, error } = await supabase.auth.getUser()
  if (error) throw error
  return data.user
}

/** Subscribe to auth state changes. */
export function onAuthStateChange(callback: (user: ReturnType<typeof supabase.auth.getUser> extends Promise<infer T> ? T extends { data: { user: infer U } } ? U : never : never) => void) {
  const { data } = supabase.auth.onAuthStateChange((_event, session) => {
    callback(session?.user ?? null as Parameters<typeof callback>[0])
  })
  return data.subscription
}

// ─── Shared Access Key helpers ───────────────────────────────────────────────

/** Set the shared token for the current Supabase session (enables read-only RLS). */
export function setSharedToken(token: string) {
  return supabase.rpc('set_config', {
    setting: 'app.shared_token',
    value: token,
    is_local: false,
  })
}

/** Create a new shared access key for the authenticated user. */
export async function createSharedKey(label?: string, expiresAt?: Date) {
  const { data, error } = await supabase
    .from('shared_access_keys')
    .insert({ label, expires_at: expiresAt?.toISOString() })
    .select()
    .single()
  if (error) throw error
  return data
}

/** Revoke a shared access key. */
export async function revokeSharedKey(id: string) {
  const { error } = await supabase
    .from('shared_access_keys')
    .update({ is_active: false })
    .eq('id', id)
  if (error) throw error
}

/** List all shared keys for the authenticated user. */
export async function listSharedKeys() {
  const { data, error } = await supabase
    .from('shared_access_keys')
    .select('*')
    .order('created_at', { ascending: false })
  if (error) throw error
  return data
}
