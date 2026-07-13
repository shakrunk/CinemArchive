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
  // shouldCreateUser: false — this app is invite-only. Accounts are created
  // exclusively by the redeem-invite Edge Function (see redeemInvite below);
  // an unknown email must never silently become a new account here.
  const { data: challengeData, error: challengeError } =
    await getClient().auth.signInWithOtp({
      email,
      options: {
        shouldCreateUser: false,
        emailRedirectTo: window.location.origin + import.meta.env.BASE_URL,
      },
    })
  if (challengeError) throw challengeError

  return challengeData
}

/** Sign in with email magic link (fallback when passkey unavailable). */
export async function signInWithEmail(email: string) {
  // shouldCreateUser: false — see signInWithPasskey above.
  const { data, error } = await getClient().auth.signInWithOtp({
    email,
    options: {
      shouldCreateUser: false,
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

// ─── Share Scopes (per-friend / per-link narrowing) ──────────────────────────
// Absence of a row means unrestricted (the default, pre-scoping behavior) —
// setShareScope(target, null) deletes the row rather than storing an "allow
// everything" row, so that invariant has exactly one representation.

export type ShareScopeTarget = { sharedKeyId: string } | { friendUserId: string }

export interface ShareScope {
  allowed_genres: string[] | null
  allowed_statuses: string[] | null
}

function shareScopeColumn(target: ShareScopeTarget): 'shared_key_id' | 'friend_user_id' {
  return 'sharedKeyId' in target ? 'shared_key_id' : 'friend_user_id'
}

function shareScopeValue(target: ShareScopeTarget): string {
  return 'sharedKeyId' in target ? target.sharedKeyId : target.friendUserId
}

/** Fetch the current scope for a share link or friend — null means unrestricted. */
export async function getShareScope(target: ShareScopeTarget): Promise<ShareScope | null> {
  const { data, error } = await getClient()
    .from('share_scopes')
    .select('allowed_genres, allowed_statuses')
    .eq(shareScopeColumn(target), shareScopeValue(target))
    .maybeSingle()
  if (error) throw error
  return data
}

/** Set (or clear, with scope=null) the scope for a share link or friend. */
export async function setShareScope(target: ShareScopeTarget, scope: ShareScope | null): Promise<void> {
  const column = shareScopeColumn(target)
  const value = shareScopeValue(target)

  if (!scope) {
    const { error } = await getClient().from('share_scopes').delete().eq(column, value)
    if (error) throw error
    return
  }

  const user = await getCurrentUser()
  if (!user) throw new Error('Not signed in.')

  // onConflict must name the actual unique constraint: share links are
  // unique on shared_key_id alone, friends on the (owner, friend) pair.
  const onConflict = column === 'shared_key_id' ? 'shared_key_id' : 'owner_user_id,friend_user_id'

  const { error } = await getClient()
    .from('share_scopes')
    .upsert(
      {
        owner_user_id: user.id,
        [column]: value,
        allowed_genres: scope.allowed_genres,
        allowed_statuses: scope.allowed_statuses,
        updated_at: new Date().toISOString(),
      },
      { onConflict }
    )
  if (error) throw error
}

// ─── Invite-only signup ─────────────────────────────────────────────────────

export interface InviteCode {
  id: string
  code: string
  created_at: string
  redeemed_by: string | null
  redeemed_at: string | null
}

/** Generate and persist a new invite code for the current user.
 *  Throws (via the `invite_codes: capped insert` RLS policy) once a
 *  non-owner account has already created 2 codes. */
export async function createInviteCode(): Promise<InviteCode> {
  const user = await getCurrentUser()
  if (!user) throw new Error('Not signed in.')
  const code = crypto.randomUUID().replace(/-/g, '').slice(0, 8).toUpperCase()
  const { data, error } = await getClient()
    .from('invite_codes')
    .insert({ code, created_by: user.id })
    .select()
    .single()
  if (error) {
    if (error.code === '23505') throw new Error('That code collided — please try again.')
    if (error.code === '42501') throw new Error("You've used both of your invites.")
    throw error
  }
  return data
}

/** List invite codes created by the current user, newest first. */
export async function listMyInviteCodes(): Promise<InviteCode[]> {
  const { data, error } = await getClient()
    .from('invite_codes')
    .select('*')
    .order('created_at', { ascending: false })
  if (error) throw error
  return data ?? []
}

/** Delete an unredeemed invite code (frees up the cap slot it was using). */
export async function deleteInviteCode(id: string): Promise<void> {
  const { error } = await getClient().from('invite_codes').delete().eq('id', id)
  if (error) throw error
}

/** Redeem an invite code for a brand-new account. On success, the email is
 *  now a known user — follow up with signInWithEmail/signInWithPasskey to
 *  actually log in. */
export async function redeemInvite(email: string, code: string): Promise<void> {
  const client = getClient()
  const { data, error } = await client.functions.invoke('redeem-invite', {
    body: { email, code },
  })
  if (error) throw error
  if (data?.error) throw new Error(data.error)
}

// ─── Own profile ─────────────────────────────────────────────────────────────

export interface MyProfile {
  user_id: string
  email: string
  username: string | null
  display_name: string | null
  created_at: string
  is_owner: boolean
}

/** Fetch the current user's profile row (owner-only RLS). */
export async function getMyProfile(): Promise<MyProfile | null> {
  const { data, error } = await getClient()
    .from('profiles')
    .select('user_id, email, username, display_name, created_at, is_owner')
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
    .select('user_id, email, username, display_name, created_at, is_owner')
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

/** Cancel the current user's own pending friend request. */
export async function cancelFriendRequest(recipientUserId: string): Promise<void> {
  const { error } = await getClient().rpc('cancel_friend_request', { recipient_user_id: recipientUserId })
  if (error) throw error
}

/** Block another user, exiting any existing pending/accepted relationship. */
export async function blockFriend(targetUserId: string): Promise<void> {
  const { error } = await getClient().rpc('block_user', { target_user_id: targetUserId })
  if (error) throw error
}

/** Remove a block you placed. Drops the relationship entirely — re-friending needs a fresh request. */
export async function unblockFriend(targetUserId: string): Promise<void> {
  const { error } = await getClient().rpc('unblock_user', { target_user_id: targetUserId })
  if (error) throw error
}

/** List all friendships (pending, accepted, blocked) involving the current user. */
export async function listFriendships(): Promise<FriendshipView[]> {
  const { data, error } = await getClient().rpc('list_friendships')
  if (error) throw error
  return data ?? []
}

// ─── Suggested friends (invite lineage) ──────────────────────────────────────

export interface InviteConnection {
  user_id: string
  username: string | null
  display_name: string | null
  connection: 'invited_by_you' | 'invited_you'
}

/** People linked to the current user by an invite code (they redeemed yours,
 *  or you redeemed theirs) who aren't friends (or pending/blocked) yet. */
export async function listInviteConnections(): Promise<InviteConnection[]> {
  const { data, error } = await getClient().rpc('list_invite_connections')
  if (error) throw error
  return data ?? []
}
