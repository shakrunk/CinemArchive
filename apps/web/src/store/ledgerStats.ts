import type { LedgerStats, Title } from './mockData'
import { watchedMinutesInSeason } from './episodeUtils'
import { deriveTopGenres, deriveTopDirectors, deriveTopActors, deriveRatingDistribution, bucketViewingsByMonth } from './ledgerDerive'

// The whole-library hero/ribbon stats. Delegates its per-category rollups
// (genres/directors/actors/ratings/monthly bucketing) to ledgerDerive.ts's
// panel derivations called with no settings (i.e. unscoped, all-time), so
// there's one implementation of each rollup rather than two.
export function computeLedgerStats(titles: Title[]): LedgerStats {
  const movies = titles.filter((t) => t.type === 'movie')
  const series = titles.filter((t) => t.type === 'tv')
  const viewings = titles.flatMap((t) => t.viewings ?? [])

  const { distribution: ratingDistribution, avgRating } = deriveRatingDistribution(titles)

  const monthCounts = bucketViewingsByMonth(titles)
  const viewingsByMonth = [...monthCounts.entries()]
    .map(([month, count]) => ({ month, count }))
    .sort((a, b) => a.month.localeCompare(b.month))

  return {
    totalMovies: movies.length,
    totalSeries: series.length,
    totalViewings: viewings.length,
    avgRating,
    // Films count once, and only when watched — a watchlisted or dropped film
    // hasn't been sat through. Series count the runtime of each watched episode.
    totalMinutes: titles.reduce((sum, t) => {
      if (t.type === 'movie') return t.status === 'watched' ? sum + (t.runtime ?? 0) : sum
      return sum + (t.seasons ?? []).reduce((s, season) => s + watchedMinutesInSeason(season), 0)
    }, 0),
    topGenres: deriveTopGenres(titles),
    topDirectors: deriveTopDirectors(titles),
    topActors: deriveTopActors(titles),
    ratingDistribution,
    viewingsByMonth,
  }
}
