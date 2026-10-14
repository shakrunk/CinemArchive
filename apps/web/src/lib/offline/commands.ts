import type { CinemaOuting, Episode, EpisodeRating, EpisodeReview, EpisodeWatchEvent, List, Season, Title, Viewing } from '../../store/mockData'
import type { LedgerWidget } from '../ledgerPanels'
import { assertCommand, assertMutation, assertScope } from './validation'
import type { TicketMutation, TicketOutingField } from '../tickets/types'
import type { VenueNoteMutation } from '../venueNotes'

/** Project identity must be the configured Supabase project/URL, never an access token. */
export interface OfflineScope { projectId: string; userId: string }
export const OFFLINE_VERSION = 1 as const

/** Omission leaves a field alone; null explicitly clears an optional field. */
export type FieldPatch<T> = { [K in keyof T]?: T[K] | (undefined extends T[K] ? null : never) }
export type TitlePatch = FieldPatch<Omit<Title, 'id' | 'viewings' | 'seasons' | 'addedAt'>>
export type ViewingPatch = FieldPatch<Omit<Viewing, 'id' | 'titleId'>>
export type OutingPatch = FieldPatch<Omit<CinemaOuting, 'id' | 'titleId' | 'createdAt' | TicketOutingField>>

export type TrackingMutation =
  | { kind: 'title.create'; title: Title }
  | { kind: 'title.patch'; titleId: string; patch: TitlePatch }
  | { kind: 'title.delete'; titleId: string }
  | { kind: 'season.put'; titleId: string; season: Season }
  | { kind: 'season.progress'; titleId: string; seasonId: string; episodesWatched: number }
  | { kind: 'episode.metadata'; titleId: string; episodeId: string; patch: FieldPatch<Omit<Episode, 'id' | 'episodeNumber' | 'watchEvents' | 'ratings' | 'reviews'>> }
  | { kind: 'episode.log'; titleId: string; episodeId: string; watchEvent?: EpisodeWatchEvent; rating?: EpisodeRating; review?: EpisodeReview }
  | { kind: 'episodeWatch.delete'; titleId: string; episodeId: string; watchEventId: string }
  | { kind: 'viewing.put'; titleId: string; viewing: Viewing }
  | { kind: 'viewing.patch'; titleId: string; viewingId: string; patch: ViewingPatch }
  | { kind: 'viewing.delete'; titleId: string; viewingId: string }
  | { kind: 'outing.create'; outing: CinemaOuting }
  | { kind: 'outing.patch'; outingId: string; patch: OutingPatch }
  | { kind: 'outing.delete'; outingId: string }
  | { kind: 'list.create'; list: List }
  | { kind: 'list.patch'; listId: string; patch: Partial<Pick<List, 'name' | 'description'>>; updatedAt: string }
  | { kind: 'list.delete'; listId: string }
  | { kind: 'membership.set'; listId: string; titleId: string; present: boolean }
  | { kind: 'theaterInterest.set'; titleId: string; userId: string; present: boolean; createdAt: string }
  | { kind: 'pin.set'; titleId: string; easterEggKey: string; variant: 'bw' | 'color' | null }
  | { kind: 'ledger.set'; widgets: LedgerWidget[] }
  | { kind: 'external.link'; titleId: string; provider: 'letterboxd' | 'simkl' | 'plex' | 'emby'; externalId: string }

/** A compound user action is one journal record and one delivery hook invocation.
 * The remote writer must supply transactional semantics for compound actions. */
export interface OutingRevertMutation {
  kind: 'outing.revert'; outingId: string; titleId: string
  viewingId: string | null; viewingPresent: boolean
}
export type Mutation = TrackingMutation | TicketMutation | VenueNoteMutation | OutingRevertMutation | { kind: 'batch'; mutations: TrackingMutation[] }

export type RevisionTable = 'titles' | 'lists' | 'cinema_outings' | 'seasons' | 'episodes' | 'viewings' | 'episode_watch_events' | 'episode_ratings' | 'episode_reviews'
export type RowPrecondition = { table: RevisionTable; id: string } & ({ updatedAt: string; afterCommandId?: never } | { afterCommandId: string; updatedAt?: never })

export interface PendingCommand {
  version: typeof OFFLINE_VERSION
  id: string
  scope: OfflineScope
  createdAt: string
  /** Assigned atomically by storage; timestamps do not determine delivery order. */
  sequence: number
  mutation: Mutation
  dependsOn: string[]
  baseRevision?: string
  preconditions?: RowPrecondition[]
  state: 'pending' | 'failed' | 'conflict'
  attempts: number
  nextAttemptAt: number
  lastError?: string
  /** Definitive rejection of this immutable venue RPC, cleared before retry. */
  venueRejection?: true
}

export function scopeKey(scope: OfflineScope): string {
  assertScope(scope)
  return JSON.stringify([scope.projectId, scope.userId])
}

export function sameScope(a: OfflineScope, b: OfflineScope): boolean {
  return a.projectId === b.projectId && a.userId === b.userId
}

/** Full model objects often have undefined optional properties; omit those.
 * Undefined PATCH values are rejected first: callers must use explicit null. */
export function omitUndefined(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(omitUndefined)
  if (value && typeof value === 'object') {
    if (Object.getPrototypeOf(value) !== Object.prototype && Object.getPrototypeOf(value) !== null) {
      throw new Error('Offline commands require plain JSON objects')
    }
    return Object.fromEntries(Object.entries(value).filter(([, v]) => v !== undefined).map(([k, v]) => [k, omitUndefined(v)]))
  }
  return value
}

export function createCommand(
  scope: OfflineScope,
  mutation: Mutation,
  options: { id?: string; createdAt?: string; dependsOn?: string[]; baseRevision?: string; preconditions?: RowPrecondition[] } = {},
): PendingCommand {
  assertScope(scope)
  const leaves = mutation.kind === 'batch' ? mutation.mutations : [mutation]
  for (const leaf of leaves) {
    if ('patch' in leaf && Object.values(leaf.patch).some((value) => value === undefined)) {
      throw new Error('Use null to clear an offline patch field; undefined loses intent')
    }
  }
  const clean = omitUndefined(mutation)
  assertMutation(clean)
  const command: PendingCommand = {
    version: OFFLINE_VERSION, id: options.id ?? crypto.randomUUID(), scope: { ...scope },
    createdAt: options.createdAt ?? new Date().toISOString(), sequence: 0,
    mutation: clean, dependsOn: [...new Set(options.dependsOn ?? [])],
    state: 'pending', attempts: 0, nextAttemptAt: 0,
  }
  if (options.baseRevision !== undefined) command.baseRevision = options.baseRevision
  if (options.preconditions !== undefined) command.preconditions = options.preconditions.map((row) => ({ ...row }))
  assertCommand(command)
  return command
}
