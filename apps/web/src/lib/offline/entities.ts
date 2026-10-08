import type { Mutation } from './commands'
import type { OfflineSnapshot } from './snapshot'

/** Conservative entity groups preserve parent/child ordering and make discard
 * safe. A failed title creation must not strand an independently queued child. */
export function mutationEntities(mutation: Mutation, base: OfflineSnapshot): Set<string> {
  const entities = new Set<string>()
  for (const leaf of mutation.kind === 'batch' ? mutation.mutations : [mutation]) {
    if ('titleId' in leaf) entities.add(`title:${leaf.titleId}`)
    if (leaf.kind === 'title.create') entities.add(`title:${leaf.title.id}`)
    if ('listId' in leaf) entities.add(`list:${leaf.listId}`)
    if (leaf.kind === 'list.create') entities.add(`list:${leaf.list.id}`)
    if (leaf.kind === 'outing.create') {
      entities.add(`outing:${leaf.outing.id}`)
      entities.add(`title:${leaf.outing.titleId}`)
    }
    if ('outingId' in leaf) {
      entities.add(`outing:${leaf.outingId}`)
      const outing = base.outings.find((row) => row.id === leaf.outingId)
      if (outing) entities.add(`title:${outing.titleId}`)
    }
    if (leaf.kind === 'ledger.set') entities.add('ledger')
  }
  return entities
}
