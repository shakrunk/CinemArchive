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
    await getClient().auth.signInWithOtp({
      email,
      options: {
        emailRedirectTo: window.location.origin + import.meta.env.BASE_URL,
      },
    })
  if (challengeError) throw challengeError

  return challengeData
}

/** Sign in with email magic link (fallback when passkey unavailable). */
export async function signInWithEmail(email: string) {
  const { data, error } = await getClient().auth.signInWithOtp({
    email,
    options: {
      emailRedirectTo: window.location.origin + import.meta.env.BASE_URL,
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

// ─── Own profile ─────────────────────────────────────────────────────────────

export interface MyProfile {
  user_id: string
  email: string
  username: string | null
  display_name: string | null
  created_at: string
}

/** Fetch the current user's profile row (owner-only RLS). */
export async function getMyProfile(): Promise<MyProfile | null> {
  const { data, error } = await getClient()
    .from('profiles')
    .select('user_id, email, username, display_name, created_at')
    .maybeSingle()
  if (error) throw error
  return data
}

/** Update the current user's display name and/or username.
 *  Throws a friendly error when the username is already taken. */
export async function updateMyProfile(patch: {
  username?: string | null
  display_name?: string | null
}): Promise<MyProfile> {
  const user = await getCurrentUser()
  if (!user) throw new Error('Not signed in.')
  const { data, error } = await getClient()
    .from('profiles')
    .update(patch)
    .eq('user_id', user.id)
    .select('user_id, email, username, display_name, created_at')
    .single()
  if (error) {
    if (error.code === '23505') throw new Error('That username is already taken.')
    throw error
  }
  return data
}

// ─── Friend lookup ───────────────────────────────────────────────────────────

export interface FoundProfile {
  user_id: string
  username: string | null
  display_name: string | null
}

/** Resolve another user's account by exact email match, or null if none exists. */
export async function findUserByEmail(email: string): Promise<FoundProfile | null> {
  const { data, error } = await getClient().rpc('find_user_by_email', {
    lookup_email: normalizeEmail(email),
  })
  if (error) throw error
  return data?.[0] ?? null
}

/** Trim and lowercase an email for consistent matching against find_user_by_email. */
export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

// ─── Friendships ──────────────────────────────────────────────────────────

export type FriendshipStatus = 'pending' | 'accepted' | 'blocked'

export interface FriendshipView {
  friend_user_id: string
  status: FriendshipStatus
  requested_by: string
  blocked_by: string | null
  created_at: string
  updated_at: string
  display_name: string | null
  username: string | null
}

/** Send a friend request to another user. Auto-accepts if they already requested you. */
export async function sendFriendRequest(targetUserId: string): Promise<void> {
  const { error } = await getClient().rpc('send_friend_request', { target_user_id: targetUserId })
  if (error) throw error
}

/** Accept a pending friend request from the given user. */
export async function acceptFriendRequest(requesterUserId: string): Promise<void> {
  const { error } = await getClient().rpc('accept_friend_request', { requester_user_id: requesterUserId })
  if (error) throw error
}

/** Decline (delete) a pending friend request from the given user. */
export async function declineFriendRequest(requesterUserId: string): Promise<void> {
  const { error } = await getClient().rpc('decline_friend_request', { requester_user_id: requesterUserId })
  if (error) throw error
}

/** Block another user, exiting any existing pending/accepted relationship. */
export async function blockFriend(targetUserId: string): Promise<void> {
  const { error } = await getClient().rpc('block_user', { target_user_id: targetUserId })
  if (error) throw error
}

/** List all friendships (pending, accepted, blocked) involving the current user. */
export async function listFriendships(): Promise<FriendshipView[]> {
  const { data, error } = await getClient().rpc('list_friendships')
  if (error) throw error
  return data ?? []
}
