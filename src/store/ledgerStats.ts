import type { LedgerStats, Title } from './mockData'
import { watchedMinutesInSeason } from './episodeUtils'

// Mirrors the logic in scripts/migrate-from-v1.mjs's computeStats, kept in
// sync so stats are always derived from the live titles list.
export function computeLedgerStats(titles: Title[]): LedgerStats {
  const movies = titles.filter((t) => t.type === 'movie')
  const series = titles.filter((t) => t.type === 'tv')
  const viewings = titles.flatMap((t) => t.viewings ?? [])
  const rated = titles.filter((t) => typeof t.rating === 'number')
  const avgRating = rated.length
    ? rated.reduce((sum, t) => sum + (t.rating ?? 0), 0) / rated.length
    : 0

  const tally = (values: string[]): [string, number][] => {
    const counts = new Map<string, number>()
    for (const v of values) counts.set(v, (counts.get(v) ?? 0) + 1)
    return [...counts.entries()]
  }

  const topGenres = tally(titles.flatMap((t) => t.genres))
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)
    .map(([genre, count]) => ({ genre, count }))

  const topDirectors = tally(titles.map((t) => t.director).filter((d): d is string => Boolean(d)))
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([director, count]) => ({ director, count }))

  const topActors = tally(
    titles.flatMap((t) => (t.cast ?? []).filter((c) => c.order < 5).map((c) => c.name))
  )
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([actor, count]) => ({ actor, count }))

  const ratingDistribution: { rating: number; count: number }[] = []
  for (let r = 5; r >= 1; r -= 0.5) {
    ratingDistribution.push({ rating: r, count: titles.filter((t) => t.rating === r).length })
  }

  const monthCounts = new Map<string, number>()
  for (const v of viewings) {
    if (!v.date) continue // pre-platform viewings have no date to bucket
    const month = v.date.slice(0, 7)
    monthCounts.set(month, (monthCounts.get(month) ?? 0) + 1)
  }
  const viewingsByMonth = [...monthCounts.entries()]
    .map(([month, count]) => ({ month, count }))
    .sort((a, b) => a.month.localeCompare(b.month))

  return {
    totalMovies: movies.length,
    totalSeries: series.length,
    totalViewings: viewings.length,
    avgRating: Math.round(avgRating * 10) / 10,
    totalMinutes: titles.reduce((sum, t) => {
      if (t.type === 'movie') return sum + (t.runtime ?? 0)
      return sum + (t.seasons ?? []).reduce((s, season) => s + watchedMinutesInSeason(season), 0)
    }, 0),
    topGenres,
    topDirectors,
    topActors,
    ratingDistribution,
    viewingsByMonth,
  }
}
