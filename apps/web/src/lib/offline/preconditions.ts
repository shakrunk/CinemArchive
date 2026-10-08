import type { Mutation, RevisionTable, RowPrecondition, PendingCommand } from './commands'
import type { OfflineSnapshot } from './snapshot'

interface Row { table: RevisionTable; id: string; guard: boolean }
export function collectRowRevisions(titles: unknown[], outings: unknown[]): Record<string, string> {
  const revisions: Record<string, string> = {}
  const collect = (table: RevisionTable, value: unknown): void => {
    if (!value || typeof value !== 'object') return
    const row = value as Record<string, unknown>
    if (typeof row.id === 'string' && typeof row.updated_at === 'string') revisions[`${table}:${row.id}`] = row.updated_at
    for (const child of ['seasons', 'viewings', 'episodes', 'episode_watch_events', 'episode_ratings', 'episode_reviews'] as const) {
      if (Array.isArray(row[child])) for (const nested of row[child]) collect(child, nested)
    }
  }
  titles.forEach((row) => collect('titles', row))
  outings.forEach((row) => collect('cinema_outings', row))
  return revisions
}
export function mutationRows(mutation: Mutation): Row[] {
  return (mutation.kind === 'batch' ? mutation.mutations : [mutation]).flatMap((leaf): Row[] => {
    switch (leaf.kind) {
      case 'title.create': return [{ table: 'titles', id: leaf.title.id, guard: false },
        ...leaf.title.viewings.map((v): Row => ({ table: 'viewings', id: v.id, guard: false })),
        ...(leaf.title.seasons ?? []).flatMap((s): Row[] => [{ table: 'seasons', id: s.id, guard: false },
          ...(s.episodes ?? []).flatMap((ep): Row[] => [{ table: 'episodes', id: ep.id, guard: false },
            ...ep.watchEvents.map((event): Row => ({ table: 'episode_watch_events', id: event.id, guard: false })),
            ...ep.ratings.map((rating): Row => ({ table: 'episode_ratings', id: rating.id, guard: false })),
            ...ep.reviews.map((review): Row => ({ table: 'episode_reviews', id: review.id, guard: false })),
          ])])]
      case 'title.patch': case 'title.delete': return [{ table: 'titles', id: leaf.titleId, guard: true }]
      case 'season.put': return [{ table: 'seasons', id: leaf.season.id, guard: true },
        ...(leaf.season.episodes ?? []).map((ep): Row => ({ table: 'episodes', id: ep.id, guard: true }))]
      case 'season.progress': return [{ table: 'seasons', id: leaf.seasonId, guard: true }]
      case 'episode.metadata': return [{ table: 'episodes', id: leaf.episodeId, guard: true }]
      case 'episode.log': return [
        ...(leaf.watchEvent ? [{ table: 'episode_watch_events' as const, id: leaf.watchEvent.id, guard: false }] : []),
        ...(leaf.rating ? [{ table: 'episode_ratings' as const, id: leaf.rating.id, guard: false }] : []),
        ...(leaf.review ? [{ table: 'episode_reviews' as const, id: leaf.review.id, guard: false }] : []),
      ]
      case 'episodeWatch.delete': return [{ table: 'episode_watch_events', id: leaf.watchEventId, guard: true }]
      case 'viewing.put': return [{ table: 'viewings', id: leaf.viewing.id, guard: false }]
      case 'viewing.patch': case 'viewing.delete': return [{ table: 'viewings', id: leaf.viewingId, guard: true }]
      case 'outing.create': return [{ table: 'cinema_outings', id: leaf.outing.id, guard: false }]
      case 'outing.patch': case 'outing.delete': return [{ table: 'cinema_outings', id: leaf.outingId, guard: true }]
      case 'outing.revert': return [{ table: 'cinema_outings', id: leaf.outingId, guard: true },
        { table: 'titles', id: leaf.titleId, guard: false },
        ...(leaf.viewingId && leaf.viewingPresent ? [{ table: 'viewings' as const, id: leaf.viewingId, guard: true }] : [])]
      // Ticket CAS is independent of ordinary outing fields, but a following
      // generic patch must use the ticket operation's canonical row receipt.
      case 'ticket.attach': case 'ticket.detach': return [{ table: 'cinema_outings', id: leaf.outingId, guard: false }]
      case 'list.create': return [{ table: 'lists', id: leaf.list.id, guard: false }]
      case 'list.patch': case 'list.delete': return [{ table: 'lists', id: leaf.listId, guard: true }]
      default: return []
    }
  })
}

/** Called inside the append transaction. A later local edit refers to its
 * predecessor's immutable server receipt; it never guesses a future timestamp. */
export function capturePreconditions(mutation: Mutation, base: OfflineSnapshot, pending: PendingCommand[]): RowPrecondition[] {
  const rows = mutationRows(mutation)
  const seen = new Set<string>()
  return rows.flatMap((row): RowPrecondition[] => {
    const key = `${row.table}:${row.id}`
    if (!row.guard || seen.has(key)) return []
    seen.add(key)
    const predecessor = [...pending].reverse().find((command) => mutationRows(command.mutation).some((prior) => prior.table === row.table && prior.id === row.id))
    if (predecessor) return [{ table: row.table, id: row.id, afterCommandId: predecessor.id }]
    const updatedAt = base.rowRevisions?.[key]
    return updatedAt ? [{ table: row.table, id: row.id, updatedAt }] : []
  })
}
