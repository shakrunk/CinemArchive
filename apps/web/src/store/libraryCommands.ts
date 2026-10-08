import type { Episode, Title, Viewing } from './mockData'
import type { FieldPatch, Mutation, TitlePatch, TrackingMutation, ViewingPatch } from '../lib/offline/commands'

/** At the UI boundary an explicitly supplied undefined means clear. Omitted
 * keys stay omitted, then the journal only ever sees JSON-safe explicit null. */
export function explicitPatch<T extends object>(patch: Partial<T>): FieldPatch<T> {
  return Object.fromEntries(Object.entries(patch).map(([key, value]) => [key, value === undefined ? null : value])) as FieldPatch<T>
}

export function compound(mutations: TrackingMutation[]): Mutation | null {
  return mutations.length === 0 ? null : mutations.length === 1 ? mutations[0] : { kind: 'batch', mutations }
}

function changedFields<T extends object>(before: T, after: T, excluded: string[]): Record<string, unknown> {
  return Object.fromEntries([...new Set([...Object.keys(before), ...Object.keys(after)])]
    .filter((key) => !excluded.includes(key) && JSON.stringify((before as Record<string, unknown>)[key]) !== JSON.stringify((after as Record<string, unknown>)[key]))
    .map((key) => [key, (after as Record<string, unknown>)[key] ?? null]))
}

function episodeChanges(titleId: string, before: Episode | undefined, after: Episode): TrackingMutation[] {
  const changes: TrackingMutation[] = []
  for (const event of after.watchEvents) if (!before?.watchEvents.some((old) => old.id === event.id)) changes.push({ kind: 'episode.log', titleId, episodeId: after.id, watchEvent: event })
  for (const rating of after.ratings) if (!before?.ratings.some((old) => old.id === rating.id)) changes.push({ kind: 'episode.log', titleId, episodeId: after.id, rating })
  for (const review of after.reviews) if (!before?.reviews.some((old) => old.id === review.id)) changes.push({ kind: 'episode.log', titleId, episodeId: after.id, review })
  for (const event of before?.watchEvents ?? []) if (!after.watchEvents.some((next) => next.id === event.id)) changes.push({ kind: 'episodeWatch.delete', titleId, episodeId: after.id, watchEventId: event.id })
  // Ratings/reviews are append-only in the shipped editors. Refuse unsupported
  // replacement/removal instead of silently losing that user's history.
  for (const key of ['ratings', 'reviews'] as const) for (const old of before?.[key] ?? []) {
    const next = after[key].find((entry) => entry.id === old.id)
    if (!next || JSON.stringify(next) !== JSON.stringify(old)) throw new Error('Existing episode ratings and reviews cannot be replaced by a metadata update')
  }
  return changes
}

/** Convert the legacy whole-title editing API to precise row commands. This
 * preserves credits, explicit clears, individual viewing changes, metadata,
 * and new logs without serializing arbitrary write closures or stale arrays. */
export function titlePatchCommand(title: Title, patch: Partial<Title>): Mutation | null {
  const { id, addedAt, viewings, seasons, ...fields } = patch
  if (id !== undefined && id !== title.id) throw new Error('A title ID cannot be changed')
  if (addedAt !== undefined && addedAt !== title.addedAt) throw new Error('A title creation time cannot be changed')
  const changes: TrackingMutation[] = []
  const scalar = explicitPatch(fields) as TitlePatch
  if (Object.keys(scalar).length) changes.push({ kind: 'title.patch', titleId: title.id, patch: scalar })
  if (Object.hasOwn(patch, 'viewings')) {
    if (!viewings) throw new Error('Viewings must be supplied as an array')
    for (const viewing of viewings) {
      const previous = title.viewings.find((old) => old.id === viewing.id)
      if (viewing.titleId !== title.id) throw new Error('Viewing belongs to another title')
      if (!previous) changes.push({ kind: 'viewing.put', titleId: title.id, viewing })
      else {
        const changed = changedFields<Viewing>(previous, viewing, ['id', 'titleId']) as ViewingPatch
        if (Object.keys(changed).length) changes.push({ kind: 'viewing.patch', titleId: title.id, viewingId: viewing.id, patch: changed })
      }
    }
    for (const previous of title.viewings) if (!viewings.some((row) => row.id === previous.id)) changes.push({ kind: 'viewing.delete', titleId: title.id, viewingId: previous.id })
  }
  if (Object.hasOwn(patch, 'seasons')) {
    if (!seasons) throw new Error('Seasons must be supplied as an array')
    for (const season of seasons) {
      const previous = title.seasons?.find((old) => old.id === season.id)
      if (JSON.stringify(previous) === JSON.stringify(season)) continue
      changes.push({ kind: 'season.put', titleId: title.id, season })
      for (const episode of season.episodes ?? []) changes.push(...episodeChanges(title.id, previous?.episodes?.find((old) => old.id === episode.id), episode))
      if (!season.episodes?.length && season.episodesWatched !== (previous?.episodesWatched ?? 0)) {
        changes.push({ kind: 'season.progress', titleId: title.id, seasonId: season.id, episodesWatched: season.episodesWatched })
      }
    }
    if (title.seasons?.some((old) => !seasons.some((season) => season.id === old.id))) throw new Error('Metadata updates cannot remove existing seasons')
  }
  return compound(changes)
}
