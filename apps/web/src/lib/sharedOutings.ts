import { isSupabaseConfigured, supabase } from './auth'

// Shared outings (#206): invite accepted friends to an outing so it shows on their Up Next once
// they accept. Mirrors Android's SharedOuting.kt / SharedOutingsRepository.kt; the contract is
// docs/android-contracts/shared-outings.md. Online-only: these are server records with no local copy.
// A shared outing carries only the plan-sharing snapshot, never the owner's booking reference,
// price, notes, seats or ticket.

export type SharedOutingStatus = 'scheduled' | 'completed' | 'cancelled'
export type MemberStatus = 'invited' | 'accepted' | 'declined'

export interface SharedOuting {
  id: string
  status: SharedOutingStatus
  myStatus: Exclude<MemberStatus, 'declined'>
  ownerId: string
  ownerName: string
  showtime: string
  endsAt: string
  title: string
  year: number | null
  posterUrl: string | null
  venue: string | null
  format: string | null
  companions: string[]
}

export interface OutingMember {
  sharedOutingId: string
  userId: string
  name: string
  status: MemberStatus
}

/** The most friends one outing invites at once; the database refuses a ninth. */
export const MAX_INVITEES = 8

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const isUuid = (value: unknown): value is string => typeof value === 'string' && UUID_RE.test(value)
const text = (value: unknown): string | null => (typeof value === 'string' && value.trim() ? value : null)
const instant = (value: unknown): string | null => (typeof value === 'string' && !Number.isNaN(Date.parse(value)) ? value : null)
const record = (value: unknown): Record<string, unknown> | null =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null

/** A response is only taken while the outing is still scheduled and has not ended. */
export const canRespond = (outing: SharedOuting, now: number) =>
  outing.status === 'scheduled' && Date.parse(outing.endsAt) > now

/** Invitations still waiting on an answer, soonest first. */
export const invitationsOf = (outings: SharedOuting[], now: number) =>
  outings.filter((o) => o.myStatus === 'invited' && canRespond(o, now)).sort((a, b) => Date.parse(a.showtime) - Date.parse(b.showtime))

/** Outings the user said yes to, including a cancelled one so the change is not silent. */
export const goingOf = (outings: SharedOuting[]) =>
  outings.filter((o) => o.myStatus === 'accepted').sort((a, b) => Date.parse(a.showtime) - Date.parse(b.showtime))

/** Rows with an unexpected shape are skipped, never shown. */
export function parseSharedOutings(rows: unknown): SharedOuting[] {
  if (!Array.isArray(rows)) return []
  return rows.flatMap((raw): SharedOuting[] => {
    const row = record(raw)
    const snapshot = record(row?.snapshot)
    if (!row || !snapshot) return []
    const status = row.status
    const myStatus = row.my_status
    const showtime = instant(row.showtime)
    const endsAt = instant(row.ends_at)
    const title = text(snapshot.title)
    if (!isUuid(row.id) || !isUuid(row.owner_id) || !showtime || !endsAt || !title) return []
    if (status !== 'scheduled' && status !== 'completed' && status !== 'cancelled') return []
    if (myStatus !== 'invited' && myStatus !== 'accepted') return []
    return [{
      id: row.id, status, myStatus, ownerId: row.owner_id, ownerName: text(row.owner_name) ?? 'A friend',
      showtime, endsAt, title,
      year: typeof snapshot.year === 'number' && snapshot.year > 0 ? snapshot.year : null,
      posterUrl: text(snapshot.poster_url), venue: text(snapshot.venue), format: text(snapshot.format),
      companions: Array.isArray(snapshot.companions) ? snapshot.companions.filter((c): c is string => typeof c === 'string' && !!c.trim()) : [],
    }]
  })
}

export function parseOutingMembers(rows: unknown): OutingMember[] {
  if (!Array.isArray(rows)) return []
  return rows.flatMap((raw): OutingMember[] => {
    const row = record(raw)
    if (!row || !isUuid(row.shared_outing_id) || !isUuid(row.user_id)) return []
    if (row.status !== 'invited' && row.status !== 'accepted' && row.status !== 'declined') return []
    return [{ sharedOutingId: row.shared_outing_id, userId: row.user_id, name: text(row.name) ?? 'A friend', status: row.status }]
  })
}

function connection() {
  if (!isSupabaseConfigured || !supabase) throw new Error('Sign in to share outings with friends.')
  return supabase
}

function failure(error: { message?: string } | null, fallback: string): Error {
  return new Error(error?.message && error.message.length < 160 ? error.message : fallback)
}

/** Outings this user is invited to or going to, with recently ended and cancelled ones. */
export async function listSharedOutings(): Promise<SharedOuting[]> {
  const { data, error } = await connection().rpc('get_shared_outings')
  if (error) throw failure(error, "Couldn't load invitations from friends.")
  return parseSharedOutings(data)
}

/** The owner's view of who is invited to an outing. */
export async function outingMembers(outingId: string): Promise<OutingMember[]> {
  if (!isUuid(outingId)) throw new Error('Invalid outing')
  const { data, error } = await connection().rpc('get_outing_members', { p_outing_id: outingId })
  if (error) throw failure(error, "Couldn't load your invitations.")
  return parseOutingMembers(data)
}

/** Invites accepted friends (the server refuses anyone else), then returns the outing's members. */
export async function inviteFriends(outingId: string, friendIds: string[]): Promise<OutingMember[]> {
  if (!isUuid(outingId) || friendIds.length === 0 || !friendIds.every(isUuid)) throw new Error('Choose at least one friend.')
  const { error } = await connection().rpc('invite_friends_to_outing', { p_outing_id: outingId, p_friend_ids: friendIds })
  if (error) throw failure(error, 'Could not send the invitation.')
  return outingMembers(outingId)
}

export async function respondToInvite(sharedOutingId: string, accept: boolean): Promise<void> {
  if (!isUuid(sharedOutingId)) throw new Error('Invalid outing')
  const { error } = await connection().rpc('respond_to_outing_invite', { p_shared_outing_id: sharedOutingId, p_accept: accept })
  if (error) throw failure(error, 'Could not send your answer.')
}

export async function removeOutingMember(sharedOutingId: string, userId: string): Promise<void> {
  if (!isUuid(sharedOutingId) || !isUuid(userId)) throw new Error('Invalid id')
  const { error } = await connection().rpc('remove_outing_member', { p_shared_outing_id: sharedOutingId, p_user_id: userId })
  if (error) throw failure(error, "Couldn't take back that invitation.")
}
