import type { Title } from './mockData'
import type { LedgerScope } from 'src/lib/ledgerPanels'

export type RatingBaseline = 'all' | 'media'
type RatedTitle = Title & { rating: number }

export function isRatedTitle(title: Title): title is RatedTitle {
  return typeof title.rating === 'number' && Number.isFinite(title.rating)
    && title.rating >= 0 && title.rating <= 5
}

function summarize(titles: RatedTitle[]) {
  const count = titles.length
  const mean = count ? titles.reduce((sum, title) => sum + title.rating, 0) / count : null
  // Population SD: describing this library, not estimating a wider population.
  const deviation = mean === null ? null : Math.sqrt(
    titles.reduce((sum, title) => sum + (title.rating - mean) ** 2, 0) / count,
  )
  const frequencies = new Map<number, number>()
  for (const title of titles) frequencies.set(title.rating, (frequencies.get(title.rating) ?? 0) + 1)
  const percentiles = new Map<number, number>()
  let below = 0
  for (const [rating, tied] of [...frequencies].sort(([a], [b]) => a - b)) {
    // Empirical midrank: ties share the middle of their occupied percentile range.
    percentiles.set(rating, 100 * (below + tied / 2) / count)
    below += tied
  }
  return { count, mean, deviation, percentiles }
}

/** One observation per title. Rewatches/episode logs do not receive extra weight.
 * Scope filters the displayed rows only; it never changes the chosen baseline.
 * Callers pass the currently visible owner's library, including sharing restrictions.
 */
export function deriveRatingNormalization(
  titles: Title[],
  baseline: RatingBaseline = 'all',
  scope: LedgerScope = 'all',
) {
  const rated = titles.filter(isRatedTitle)
  const groups = baseline === 'all'
    ? [{ key: 'all' as const, label: 'All titles', ...summarize(rated) }]
    : [
        { key: 'movie' as const, label: 'Films', ...summarize(rated.filter((t) => t.type === 'movie')) },
        { key: 'tv' as const, label: 'Series', ...summarize(rated.filter((t) => t.type === 'tv')) },
      ]
  const rows = rated
    .filter((t) => scope === 'all' || t.type === (scope === 'movies' ? 'movie' : 'tv'))
    .map((title) => {
      const group = groups.find((g) => g.key === 'all' || g.key === title.type)!
      const zScore = group.count >= 2 && group.deviation !== null && group.deviation > 1e-12
        ? (title.rating - group.mean!) / group.deviation
        : null
      return { title, zScore, percentile: group.percentiles.get(title.rating)!, baseline: group }
    })
    .sort((a, b) => (b.zScore ?? -Infinity) - (a.zScore ?? -Infinity)
      || b.percentile - a.percentile || a.title.title.localeCompare(b.title.title))
  return { groups, rows }
}
