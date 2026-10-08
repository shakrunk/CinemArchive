import type { CinemaOuting, Title, Viewing } from './mockData'

export interface OutingMemory {
  outing: CinemaOuting
  title: Title
  yearsAgo: number
  viewing?: Viewing
}

/** Anniversary of a completed cinema trip, using the viewer's local calendar
 *  just like Android's CinemaOutingRules.onThisDay. Outings are private: callers
 *  must restrict this derivation to the owner's library. */
export function computeOutingMemories(outings: CinemaOuting[], titles: Title[], now: Date): OutingMemory[] {
  const titlesById = new Map(titles.map((title) => [title.id, title]))
  const memories: OutingMemory[] = []
  for (const outing of outings) {
    if (outing.status !== 'completed') continue
    const title = titlesById.get(outing.titleId)
    if (!title) continue
    const showtime = new Date(outing.showtime)
    const yearsAgo = now.getFullYear() - showtime.getFullYear()
    if (!(yearsAgo > 0) || showtime.getMonth() !== now.getMonth() || showtime.getDate() !== now.getDate()) continue
    memories.push({
      outing,
      title,
      yearsAgo,
      viewing: title.viewings.find((viewing) => viewing.id === outing.completedViewingId)
        ?? title.viewings.find((viewing) => viewing.outingId === outing.id),
    })
  }
  return memories.sort((a, b) =>
    b.outing.showtime < a.outing.showtime ? -1 : b.outing.showtime > a.outing.showtime ? 1 : 0
  )
}
