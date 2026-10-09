import type { Episode, Season, Title } from '../../store/mockData'
import type { Mutation, PendingCommand, TrackingMutation, OutingRevertMutation } from './commands'
import type { OfflineSnapshot } from './snapshot'
import type { TicketMutation } from '../tickets/types'
import type { VenueNoteMutation } from '../venueNotes'

function put<T extends { id: string }>(rows: T[], row: T): T[] {
  return rows.some((r) => r.id === row.id) ? rows.map((r) => r.id === row.id ? row : r) : [...rows, row]
}
/** Existing server fields win for rows already inserted. Only missing child
 * identities are added back while a partially delivered creation is pending. */
function mergeMissing<T>(existing: T[], pending: T[], key: (row: T) => string, merge?: (existing: T, pending: T) => T): T[] {
  const pendingById = new Map(pending.map((row) => [key(row), row]))
  const seen = new Set<string>()
  const result = existing.map((row) => {
    const id = key(row)
    seen.add(id)
    const incoming = pendingById.get(id)
    return incoming && merge ? merge(row, incoming) : row
  })
  for (const row of pending) {
    const id = key(row)
    if (!seen.has(id)) { result.push(row); seen.add(id) }
  }
  return result
}
const rowId = (row: { id: string }) => row.id
function mergeEpisode(existing: Episode, pending: Episode): Episode {
  const result = { ...existing,
    watchEvents: mergeMissing(existing.watchEvents, pending.watchEvents, rowId),
    ratings: mergeMissing(existing.ratings, pending.ratings, rowId),
    reviews: mergeMissing(existing.reviews, pending.reviews, rowId),
  }
  if (existing.crew || pending.crew) result.crew = mergeMissing(existing.crew ?? [], pending.crew ?? [], (row) => `${row.tmdbPersonId}:${row.job}`)
  return result
}
function mergeSeason(existing: Season, pending: Season): Season {
  const result = { ...existing }
  if (existing.cast || pending.cast) result.cast = mergeMissing(existing.cast ?? [], pending.cast ?? [], (row) => String(row.tmdbPersonId))
  if (existing.episodes || pending.episodes) {
    result.episodes = mergeMissing(existing.episodes ?? [], pending.episodes ?? [], rowId, mergeEpisode)
    result.episodesWatched = result.episodes.filter((ep) => ep.watchEvents.length > 0).length
  }
  return result
}
function mergeCreatedTitle(existing: Title, pending: Title): Title {
  const result = { ...existing, viewings: mergeMissing(existing.viewings, pending.viewings, rowId) }
  if (existing.seasons || pending.seasons) result.seasons = mergeMissing(existing.seasons ?? [], pending.seasons ?? [], rowId, mergeSeason)
  if (existing.cast || pending.cast) result.cast = mergeMissing(existing.cast ?? [], pending.cast ?? [], (row) => String(row.tmdbPersonId))
  if (existing.crew || pending.crew) result.crew = mergeMissing(existing.crew ?? [], pending.crew ?? [], (row) => `${row.tmdbPersonId}:${row.job}`)
  return result
}
function updateSeasonMetadata(existing: Season | undefined, incoming: Season): Season {
  const previous = new Map(existing?.episodes?.map((ep) => [ep.id, ep]))
  const episodes = incoming.episodes?.map((ep) => {
    const old = previous.get(ep.id)
    return { ...ep, watchEvents: old?.watchEvents ?? [], ratings: old?.ratings ?? [], reviews: old?.reviews ?? [] }
  })
  const merged = { ...existing, ...incoming, episodesWatched: existing?.episodesWatched ?? 0 }
  if (episodes) {
    merged.episodes = mergeMissing(episodes, existing?.episodes ?? [], rowId)
    merged.episodesWatched = merged.episodes.filter((ep) => ep.watchEvents.length > 0).length
  }
  return merged
}
function fields<T extends object>(row: T, patch: object): T {
  const next = { ...row } as Record<string, unknown>
  for (const [key, value] of Object.entries(patch)) {
    if (value === null) delete next[key]
    else next[key] = value
  }
  return next as T
}
function titles(state: OfflineSnapshot, titleId: string, change: (title: Title) => Title): OfflineSnapshot {
  return { ...state, titles: state.titles.map((t) => t.id === titleId ? change(t) : t) }
}
function episode(state: OfflineSnapshot, titleId: string, episodeId: string, change: (ep: Episode) => Episode): OfflineSnapshot {
  return titles(state, titleId, (t) => ({ ...t, seasons: t.seasons?.map((s) => {
    if (!s.episodes) return s
    const episodes = s.episodes.map((ep) => ep.id === episodeId ? change(ep) : ep)
    return { ...s, episodes, episodesWatched: episodes.filter((ep) => ep.watchEvents.length > 0).length }
  }) }))
}

function applyLeaf(state: OfflineSnapshot, mutation: TrackingMutation | TicketMutation | VenueNoteMutation | OutingRevertMutation): OfflineSnapshot {
  switch (mutation.kind) {
    case 'venueNote.change': {
      const current = state.venueNotes?.find((row) => row.venue === mutation.venue)
      const remaining = (state.venueNotes ?? []).filter((row) => row.venue !== mutation.venue)
      return { ...state, venueNotes: mutation.notes === null ? remaining : [...remaining, {
        id: current?.id ?? mutation.baseline?.id ?? mutation.localId, userId: mutation.userId, venue: mutation.venue,
        notes: mutation.notes, createdAt: current?.createdAt ?? mutation.baseline?.createdAt ?? mutation.recordedAt, updatedAt: mutation.recordedAt,
      }] }
    }
    case 'title.create':
      return state.titles.some((t) => t.id === mutation.title.id)
        ? titles(state, mutation.title.id, (existing) => mergeCreatedTitle(existing, mutation.title))
        : { ...state, titles: [mutation.title, ...state.titles] }
    case 'title.patch': return titles(state, mutation.titleId, (t) => fields(t, mutation.patch))
    case 'title.delete': {
      const pinnedModes = Object.fromEntries(Object.entries(state.pinnedModes).filter(([key]) => !key.startsWith(`${mutation.titleId}:`)))
      return { ...state, pinnedModes, titles: state.titles.filter((t) => t.id !== mutation.titleId),
        ...(state.theaterInterest ? { theaterInterest: state.theaterInterest.filter((row) => row.titleId !== mutation.titleId) } : {}),
        outings: state.outings.filter((o) => o.titleId !== mutation.titleId),
        listMemberships: Object.fromEntries(Object.entries(state.listMemberships).map(([key, ids]) => [key, ids.filter((id) => id !== mutation.titleId)])),
      }
    }
    case 'season.put': return titles(state, mutation.titleId, (t) => ({ ...t,
      seasons: put(t.seasons ?? [], updateSeasonMetadata(t.seasons?.find((s) => s.id === mutation.season.id), mutation.season)),
    }))
    case 'season.progress': return titles(state, mutation.titleId, (t) => ({ ...t,
      seasons: t.seasons?.map((season) => season.id === mutation.seasonId ? { ...season, episodesWatched: mutation.episodesWatched } : season),
    }))
    case 'external.link': return state // Provenance has no library-facing projection.
    case 'theaterInterest.set': {
      const remaining = (state.theaterInterest ?? []).filter((row) => row.titleId !== mutation.titleId)
      const existing = state.theaterInterest?.find((row) => row.titleId === mutation.titleId)
      return { ...state, theaterInterest: mutation.present && state.titles.some((title) => title.id === mutation.titleId)
        ? [...remaining, existing ?? { id: mutation.titleId, titleId: mutation.titleId, userId: mutation.userId, createdAt: mutation.createdAt, updatedAt: mutation.createdAt }]
        : remaining }
    }
    case 'episode.metadata': return episode(state, mutation.titleId, mutation.episodeId, (ep) => fields(ep, mutation.patch))
    case 'episode.log': return episode(state, mutation.titleId, mutation.episodeId, (ep) => ({ ...ep,
      watchEvents: mutation.watchEvent ? put(ep.watchEvents, mutation.watchEvent) : ep.watchEvents,
      ratings: mutation.rating ? put(ep.ratings, mutation.rating) : ep.ratings,
      reviews: mutation.review ? put(ep.reviews, mutation.review) : ep.reviews,
    }))
    case 'episodeWatch.delete': return episode(state, mutation.titleId, mutation.episodeId, (ep) => ({ ...ep, watchEvents: ep.watchEvents.filter((e) => e.id !== mutation.watchEventId) }))
    case 'viewing.put': return titles(state, mutation.titleId, (t) => ({ ...t, viewings: put(t.viewings, mutation.viewing) }))
    case 'viewing.patch': return titles(state, mutation.titleId, (t) => ({ ...t, viewings: t.viewings.map((v) => v.id === mutation.viewingId ? fields(v, mutation.patch) : v) }))
    case 'viewing.delete': return titles(state, mutation.titleId, (t) => ({ ...t, viewings: t.viewings.filter((v) => v.id !== mutation.viewingId) }))
    case 'outing.create': return state.outings.some((o) => o.id === mutation.outing.id) ? state : { ...state, outings: [mutation.outing, ...state.outings] }
    case 'outing.patch': return { ...state, outings: state.outings.map((o) => o.id === mutation.outingId ? fields(o, mutation.patch) : o) }
    case 'outing.delete': return { ...state, outings: state.outings.filter((o) => o.id !== mutation.outingId) }
    case 'outing.revert': {
      const outing = state.outings.find((o) => o.id === mutation.outingId && o.titleId === mutation.titleId)
      const viewing = state.titles.find((t) => t.id === mutation.titleId)?.viewings.find((v) => v.id === mutation.viewingId)
      if (!outing || outing.status !== 'completed' || (outing.completedViewingId ?? null) !== mutation.viewingId || viewing?.rating != null) return state
      // Only the server can prove that no later intentional title-status write
      // occurred. Keep its status until the guarded receipt supplies fresh state.
      const next = titles(state, mutation.titleId, (t) => ({ ...t, viewings: t.viewings.filter((v) => v.id !== mutation.viewingId) }))
      return { ...next, outings: next.outings.map((o) => o.id === mutation.outingId ? fields(o, { status: 'missed', completedViewingId: null }) : o) }
    }
    case 'ticket.attach': return { ...state, outings: state.outings.map((o) => o.id === mutation.outingId ? { ...o, ticketAttachment: mutation.attachment, ticketManaged: true } : o) }
    case 'ticket.detach': return { ...state, outings: state.outings.map((o) => o.id === mutation.outingId ? fields(o, { ticketAttachment: null, ticketManaged: true }) : o) }
    case 'list.create': return state.lists.some((l) => l.id === mutation.list.id) ? state : { ...state, lists: [mutation.list, ...state.lists] }
    // description=null is a required nullable model field, not property removal.
    case 'list.patch': return { ...state, lists: state.lists.map((l) => l.id === mutation.listId ? { ...l, ...mutation.patch, updatedAt: mutation.updatedAt } : l) }
    case 'list.delete': {
      const listMemberships = { ...state.listMemberships }
      delete listMemberships[mutation.listId]
      return { ...state, listMemberships, lists: state.lists.filter((l) => l.id !== mutation.listId) }
    }
    case 'membership.set': {
      const ids = state.listMemberships[mutation.listId] ?? []
      return { ...state, listMemberships: { ...state.listMemberships,
        [mutation.listId]: mutation.present ? [...new Set([...ids, mutation.titleId])] : ids.filter((id) => id !== mutation.titleId),
      } }
    }
    case 'pin.set': {
      const pinnedModes = { ...state.pinnedModes }
      const key = `${mutation.titleId}:${mutation.easterEggKey}`
      if (mutation.variant === null) delete pinnedModes[key]
      else pinnedModes[key] = mutation.variant
      return { ...state, pinnedModes }
    }
    case 'ledger.set': return { ...state, ledgerWidgets: mutation.widgets }
  }
}

/** No UUID generation, clocks, network calls, or writes: safe on every rebase. */
export function applyMutation(state: OfflineSnapshot, mutation: Mutation): OfflineSnapshot {
  return mutation.kind === 'batch' ? mutation.mutations.reduce(applyLeaf, state) : applyLeaf(state, mutation)
}

/** Failed/conflicted commands stay visible until resolved or explicitly discarded. */
export function replayPending(base: OfflineSnapshot, commands: readonly PendingCommand[]): OfflineSnapshot {
  return [...commands].sort((a, b) => a.sequence - b.sequence).reduce((state, command) => applyMutation(state, command.mutation), base)
}
