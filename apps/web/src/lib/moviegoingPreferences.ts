import { supabase } from './auth'
import type { DeliveryContext } from './offline/coordinator'
import type { OfflineSnapshot } from './offline/snapshot'

export interface VenueNote {
  id: string
  userId: string
  venue: string
  notes: string
  createdAt: string
  updatedAt: string
}
export interface TheaterInterest {
  id: string
  userId: string
  titleId: string
  createdAt: string
  updatedAt: string
}
export type OwnedMoviegoingPreferences =
  | { support: 'unsupported' }
  | { support: 'authoritative'; venueNotes: VenueNote[]; theaterInterest: TheaterInterest[] }

const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v) &&
  (Object.getPrototypeOf(v) === Object.prototype || Object.getPrototypeOf(v) === null)
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const timestamp = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|[+-]\d{2}:\d{2})$/.test(v) && Number.isFinite(Date.parse(v))
// PostgreSQL char_length counts Unicode code points, not JavaScript UTF-16 units.
const boundedText = (v: unknown, max: number): v is string => typeof v === 'string' && !/[\uD800-\uDFFF]/u.test(v) && !v.includes('\0') && [...v].length <= max
const fields = (v: Record<string, unknown>, names: string[]) => Object.keys(v).length === names.length && names.every((name) => Object.hasOwn(v, name))
export function isVenueNote(v: unknown): v is VenueNote {
  return record(v) && fields(v, ['id', 'userId', 'venue', 'notes', 'createdAt', 'updatedAt']) &&
    uuid(v.id) && uuid(v.userId) && boundedText(v.venue, 512) && v.venue.length > 0 && !v.venue.startsWith(' ') && !v.venue.endsWith(' ') &&
    boundedText(v.notes, 20_000) && timestamp(v.createdAt) && timestamp(v.updatedAt)
}
export function isTheaterInterest(v: unknown): v is TheaterInterest {
  return record(v) && fields(v, ['id', 'userId', 'titleId', 'createdAt', 'updatedAt']) &&
    uuid(v.id) && uuid(v.userId) && v.id === v.titleId && timestamp(v.createdAt) && timestamp(v.updatedAt)
}

/** Optional fields keep old caches readable; supplied fields are never trusted partially. */
export function assertMoviegoingSnapshot(snapshot: Pick<OfflineSnapshot, 'venueNotes' | 'theaterInterest' | 'moviegoingPreferencesSupport'>, ownerId?: string): void {
  if (!record(snapshot)) throw new Error('Invalid moviegoing preferences snapshot')
  const { venueNotes, theaterInterest, moviegoingPreferencesSupport: support } = snapshot
  if ((support !== undefined && support !== 'authoritative' && support !== 'unsupported') ||
      (venueNotes !== undefined && !Array.isArray(venueNotes)) || (theaterInterest !== undefined && !Array.isArray(theaterInterest))) throw new Error('Invalid moviegoing preferences snapshot')
  if (support === 'authoritative' && (!venueNotes || !theaterInterest)) throw new Error('Incomplete moviegoing preferences snapshot')
  const owners = new Set<string>(), ids = new Set<string>(), venues = new Set<string>()
  for (const note of venueNotes ?? []) {
    if (!isVenueNote(note) || ids.has(note.id) || venues.has(note.venue)) throw new Error('Invalid venue notes snapshot')
    ids.add(note.id); venues.add(note.venue); owners.add(note.userId)
  }
  ids.clear()
  for (const interest of theaterInterest ?? []) {
    if (!isTheaterInterest(interest) || ids.has(interest.id)) throw new Error('Invalid theater interest snapshot')
    ids.add(interest.id); owners.add(interest.userId)
  }
  if (owners.size > 1 || (ownerId !== undefined && [...owners].some((owner) => owner !== ownerId))) throw new Error('Moviegoing preferences belong to another owner')
}

interface RemoteOptions {
  projectId: string
  anonKey: string
  session: () => Promise<{ userId: string; accessToken: string } | null>
  fetch?: typeof fetch
}

export function createMoviegoingPreferencesRemote(options: RemoteOptions) {
  return async (context: DeliveryContext): Promise<OwnedMoviegoingPreferences> => {
    const check = () => {
      if (!context.isCurrent() || context.signal.aborted || context.scope.projectId !== options.projectId) throw new Error('Library account changed')
    }
    check()
    const session = await options.session()
    check()
    if (!session || !uuid(context.scope.userId) || session.userId !== context.scope.userId) throw new Error('Sign in to read this account’s moviegoing preferences')
    const send = options.fetch ?? globalThis.fetch
    async function read(table: 'venue_notes' | 'theater_interest'): Promise<Record<string, unknown>[] | null> {
      const rows: Record<string, unknown>[] = []
      const columns = table === 'venue_notes' ? ['id', 'user_id', 'venue', 'notes', 'created_at', 'updated_at'] : ['id', 'user_id', 'title_id', 'created_at', 'updated_at']
      // Keyset pagination survives deletion of earlier rows between requests.
      // Continue to an empty page: the server cap may be smaller than our limit.
      let lastId: string | undefined
      for (;;) {
        check()
        const query = new URLSearchParams({ select: columns.join(','), user_id: `eq.${session!.userId}`, order: 'id.asc', limit: '1000' })
        if (lastId) query.set('id', `gt.${lastId}`)
        const response = await send(`${options.projectId.replace(/\/$/, '')}/rest/v1/${table}?${query}`, {
          headers: { apikey: options.anonKey, Authorization: `Bearer ${session!.accessToken}` }, signal: context.signal, cache: 'no-store',
        })
        const data: unknown = await response.json()
        check()
        if (!response.ok) {
          if ((response.status === 404 || response.status === 400) && record(data) && (data.code === '42P01' || data.code === 'PGRST205')) return null
          throw new Error(record(data) && typeof data.message === 'string' ? data.message : 'Could not read moviegoing preferences')
        }
        if (!Array.isArray(data) || !data.every((row) => record(row) && fields(row, columns) && row.user_id === session!.userId)) throw new Error('Invalid moviegoing owner projection')
        for (const row of data) {
          if (!uuid(row.id) || (lastId !== undefined && row.id <= lastId)) throw new Error('Invalid moviegoing pagination; retry the refresh')
          lastId = row.id
        }
        rows.push(...data)
        if (data.length === 0) return rows
      }
    }
    const [notes, interests] = await Promise.all([read('venue_notes'), read('theater_interest')])
    check()
    const result: OwnedMoviegoingPreferences = { support: 'authoritative',
      venueNotes: (notes ?? []).map((row) => ({ id: row.id, userId: row.user_id, venue: row.venue, notes: row.notes, createdAt: row.created_at, updatedAt: row.updated_at })) as VenueNote[],
      theaterInterest: (interests ?? []).map((row) => ({ id: row.id, userId: row.user_id, titleId: row.title_id, createdAt: row.created_at, updatedAt: row.updated_at })) as TheaterInterest[],
    }
    assertMoviegoingSnapshot({ ...result, moviegoingPreferencesSupport: result.support }, context.scope.userId)
    // Neither half is authoritative when an older backend lacks the full domain.
    // Still reject malformed rows in the available half instead of hiding damage.
    if (notes === null || interests === null) return { support: 'unsupported' }
    return result
  }
}

export function mergeOwnedMoviegoingPreferences(snapshot: OfflineSnapshot, result: OwnedMoviegoingPreferences): OfflineSnapshot {
  if (result.support === 'unsupported') return { ...snapshot, moviegoingPreferencesSupport: 'unsupported' }
  const titles = new Set(snapshot.titles.map((title) => title.id))
  if (result.theaterInterest.some((interest) => !titles.has(interest.titleId))) throw new Error('Moviegoing preferences changed while loading the library; retry the refresh')
  return { ...snapshot, moviegoingPreferencesSupport: 'authoritative', venueNotes: result.venueNotes, theaterInterest: result.theaterInterest,
    rowRevisions: { ...Object.fromEntries(Object.entries(snapshot.rowRevisions ?? {}).filter(([key]) => !key.startsWith('venue_notes:') && !key.startsWith('theater_interest:'))),
      ...Object.fromEntries(result.venueNotes.map((note) => [`venue_notes:${note.id}`, note.updatedAt])),
      ...Object.fromEntries(result.theaterInterest.map((interest) => [`theater_interest:${interest.id}`, interest.updatedAt])),
    },
  }
}

export const fetchOwnedMoviegoingPreferences = createMoviegoingPreferencesRemote({
  projectId: import.meta.env.VITE_SUPABASE_URL || 'unconfigured-local', anonKey: import.meta.env.VITE_SUPABASE_ANON_KEY || '',
  session: async () => {
    if (!supabase) return null
    const { data, error } = await supabase.auth.getSession()
    return error || !data.session ? null : { userId: data.session.user.id, accessToken: data.session.access_token }
  },
})
