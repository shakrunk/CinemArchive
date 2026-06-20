/**
 * Runtime logic verification for the episode tracking system.
 * Run with: node scripts/verify-episode-logic.mjs
 *
 * Verifies:
 *   1. avgEpisodeRating for BM S2E2 (two ratings: 5 + 4 → avg 4.5)
 *   2. avgSeasonRating and avgSeriesRating rollups
 *   3. logEpisode decoupling: rating-only → 0 watch events, 1 rating
 *   4. PERSIST_VERSION bumped to 2
 */

// ─── Inline the pure logic (mirrors episodeUtils.ts) ─────────────────────────

function avgEpisodeRating(episode) {
  if (episode.ratings.length === 0) return null
  return episode.ratings.reduce((sum, r) => sum + r.rating, 0) / episode.ratings.length
}

function avgSeasonRating(season) {
  if (!season.episodes?.length) return null
  const rated = season.episodes.map(avgEpisodeRating).filter((r) => r !== null)
  if (rated.length === 0) return null
  return rated.reduce((sum, r) => sum + r, 0) / rated.length
}

function avgSeriesRating(seasons) {
  const rated = seasons.map(avgSeasonRating).filter((r) => r !== null)
  if (rated.length === 0) return null
  return rated.reduce((sum, r) => sum + r, 0) / rated.length
}

function episodesWatchedInSeason(season) {
  if (season.episodes?.length) {
    return season.episodes.filter((e) => e.watchEvents.length > 0).length
  }
  return season.episodesWatched
}

// ─── Inline logEpisode fan-out (mirrors useAppStore.ts) ──────────────────────

function logEpisode(seasons, seasonNumber, episodeNumber, opts) {
  const now = '2026-06-20T12:00:00.000Z'  // fixed for determinism
  return seasons.map((season) => {
    if (season.seasonNumber !== seasonNumber) return season
    if (!season.episodes) return season
    const episodes = season.episodes.map((ep) => {
      if (ep.episodeNumber !== episodeNumber) return ep
      const updated = { ...ep, watchEvents: [...ep.watchEvents], ratings: [...ep.ratings], reviews: [...ep.reviews] }
      if (opts.watchedAt) {
        updated.watchEvents.push({ id: 'we-test', watchedAt: opts.watchedAt, notes: opts.watchNotes })
      }
      if (opts.rating && opts.rating > 0) {
        updated.ratings.push({ id: 'er-test', rating: opts.rating, ratedAt: now })
      }
      if (opts.reviewText?.trim()) {
        updated.reviews.push({ id: 'rv-test', reviewText: opts.reviewText.trim(), reviewedAt: now })
      }
      return updated
    })
    const episodesWatched = episodes.filter((e) => e.watchEvents.length > 0).length
    return { ...season, episodes, episodesWatched }
  })
}

// ─── Mock data fixtures (mirrors mockData.ts TV entries) ─────────────────────

const BM_S2 = {
  id: 'tv-1-s2', seasonNumber: 2, episodeCount: 4, episodesWatched: 4, airYear: 2013,
  episodes: [
    { id: 'tv-1-s2-e1', episodeNumber: 1, episodeName: 'Be Right Back', runtime: 48,
      watchEvents: [{ id: 'we-tv-1-s2-e1-1', watchedAt: '2026-01-18' }],
      ratings: [{ id: 'er-tv-1-s2-e1-1', rating: 4, ratedAt: '2026-01-18T21:00:00Z' }],
      reviews: [] },
    { id: 'tv-1-s2-e2', episodeNumber: 2, episodeName: 'White Bear', runtime: 42,
      watchEvents: [{ id: 'we-tv-1-s2-e2-1', watchedAt: '2026-01-19' }],
      ratings: [
        { id: 'er-tv-1-s2-e2-1', rating: 5, ratedAt: '2026-01-19T22:00:00Z' },
        { id: 'er-tv-1-s2-e2-2', rating: 4, ratedAt: '2026-02-01T14:00:00Z' },
      ],
      reviews: [{ id: 'rv-tv-1-s2-e2-1', reviewText: 'Punishing and brilliant.', reviewedAt: '2026-01-19T22:30:00Z' }] },
    { id: 'tv-1-s2-e3', episodeNumber: 3, episodeName: 'The Waldo Moment', runtime: 43,
      watchEvents: [{ id: 'we-tv-1-s2-e3-1', watchedAt: '2026-01-20' }],
      ratings: [{ id: 'er-tv-1-s2-e3-1', rating: 2, ratedAt: '2026-01-20T21:00:00Z' }],
      reviews: [] },
    { id: 'tv-1-s2-e4', episodeNumber: 4, episodeName: 'White Christmas', runtime: 73,
      watchEvents: [{ id: 'we-tv-1-s2-e4-1', watchedAt: '2026-01-21' }],
      ratings: [{ id: 'er-tv-1-s2-e4-1', rating: 5, ratedAt: '2026-01-21T22:00:00Z' }],
      reviews: [] },
  ]
}

const BM_S1 = {
  id: 'tv-1-s1', seasonNumber: 1, episodeCount: 3, episodesWatched: 3, airYear: 2011,
  episodes: [
    { id: 'tv-1-s1-e1', episodeNumber: 1, episodeName: 'The National Anthem', runtime: 44,
      watchEvents: [{ id: 'we-tv-1-s1-e1-1', watchedAt: '2026-01-10' }],
      ratings: [{ id: 'er-tv-1-s1-e1-1', rating: 3, ratedAt: '2026-01-10T21:30:00Z' }], reviews: [] },
    { id: 'tv-1-s1-e2', episodeNumber: 2, episodeName: 'Fifteen Million Merits', runtime: 62,
      watchEvents: [{ id: 'we-tv-1-s1-e2-1', watchedAt: '2026-01-11' }],
      ratings: [{ id: 'er-tv-1-s1-e2-1', rating: 5, ratedAt: '2026-01-11T22:15:00Z' }], reviews: [] },
    { id: 'tv-1-s1-e3', episodeNumber: 3, episodeName: 'The Entire History of You', runtime: 49,
      watchEvents: [{ id: 'we-tv-1-s1-e3-1', watchedAt: '2026-01-12' }],
      ratings: [{ id: 'er-tv-1-s1-e3-1', rating: 4, ratedAt: '2026-01-12T23:00:00Z' }], reviews: [] },
  ]
}

const BM_S3 = {
  id: 'tv-1-s3', seasonNumber: 3, episodeCount: 6, episodesWatched: 6, airYear: 2016,
  episodes: [
    { id: 'tv-1-s3-e1', episodeNumber: 1, ratings: [{ id: 'er-tv-1-s3-e1-1', rating: 4, ratedAt: '2026-02-05T22:00:00Z' }], watchEvents: [{}], reviews: [] },
    { id: 'tv-1-s3-e2', episodeNumber: 2, ratings: [{ id: 'er-tv-1-s3-e2-1', rating: 4, ratedAt: '2026-02-06T22:00:00Z' }], watchEvents: [{}], reviews: [] },
    { id: 'tv-1-s3-e3', episodeNumber: 3, ratings: [{ id: 'er-tv-1-s3-e3-1', rating: 5, ratedAt: '2026-02-07T22:00:00Z' }], watchEvents: [{}], reviews: [] },
    { id: 'tv-1-s3-e4', episodeNumber: 4, ratings: [
        { id: 'er-tv-1-s3-e4-1', rating: 5, ratedAt: '2026-02-08T21:00:00Z' },
        { id: 'er-tv-1-s3-e4-2', rating: 5, ratedAt: '2026-03-15T10:00:00Z' },
      ], watchEvents: [{}], reviews: [] },
    { id: 'tv-1-s3-e5', episodeNumber: 5, ratings: [{ id: 'er-tv-1-s3-e5-1', rating: 3, ratedAt: '2026-02-09T22:00:00Z' }], watchEvents: [{}], reviews: [] },
    { id: 'tv-1-s3-e6', episodeNumber: 6, ratings: [{ id: 'er-tv-1-s3-e6-1', rating: 4, ratedAt: '2026-02-10T22:00:00Z' }], watchEvents: [{}], reviews: [] },
  ]
}

// An unwatched episode for independence test
const UNWATCHED_EP = {
  id: 'tv-2-s2-e6', episodeNumber: 6, episodeName: 'Attila',
  watchEvents: [], ratings: [], reviews: []
}

const UNWATCHED_SEASON = {
  id: 'tv-2-s2', seasonNumber: 2, episodeCount: 10, episodesWatched: 5, airYear: 2025,
  episodes: [
    ...Array.from({ length: 5 }, (_, i) => ({
      id: `tv-2-s2-e${i+1}`, episodeNumber: i + 1,
      watchEvents: [{ id: `we-${i}`, watchedAt: '2026-06-0' + (i+1) }],
      ratings: [{ id: `er-${i}`, rating: 4 + (i % 2), ratedAt: '2026-06-0' + (i+1) + 'T22:00:00Z' }],
      reviews: []
    })),
    UNWATCHED_EP,
    ...Array.from({ length: 4 }, (_, i) => ({
      id: `tv-2-s2-e${i+7}`, episodeNumber: i + 7,
      watchEvents: [], ratings: [], reviews: []
    })),
  ]
}

// ─── Assertions ───────────────────────────────────────────────────────────────

let pass = 0; let fail = 0
function assert(label, actual, expected, tolerance = 0.001) {
  const ok = (typeof actual === 'number' && typeof expected === 'number' && tolerance > 0)
    ? Math.abs(actual - expected) < tolerance
    : actual === expected
  if (ok) {
    console.log(`  ✓ ${label}: ${actual}`)
    pass++
  } else {
    console.error(`  ✗ ${label}: expected ${expected}, got ${actual}`)
    fail++
  }
}
function assertNull(label, actual) {
  const ok = actual === null
  if (ok) { console.log(`  ✓ ${label}: null`); pass++ }
  else { console.error(`  ✗ ${label}: expected null, got ${actual}`); fail++ }
}

console.log('\n── 1. avgEpisodeRating ──')
const bmS2E1 = BM_S2.episodes[0]
const bmS2E2 = BM_S2.episodes[1]
const bmS2E3 = BM_S2.episodes[2]
assert('BM S2E1 (single rating 4)', avgEpisodeRating(bmS2E1), 4)
assert('BM S2E2 (ratings 5+4 → avg 4.5)', avgEpisodeRating(bmS2E2), 4.5)
assert('BM S2E3 (single rating 2)', avgEpisodeRating(bmS2E3), 2)
assertNull('unwatched/unrated ep', avgEpisodeRating(UNWATCHED_EP))

console.log('\n── 2. avgSeasonRating rollup ──')
// S1: ratings 3, 5, 4 → avg 4.0
assert('BM S1 avg (3+5+4)/3 = 4.0', avgSeasonRating(BM_S1), 4.0)
// S2: episodes avg → (4 + 4.5 + 2 + 5) / 4 = 3.875
assert('BM S2 avg (4+4.5+2+5)/4 = 3.875', avgSeasonRating(BM_S2), 3.875)
// S3: (4+4+5+5+3+4)/6 = 25/6 ≈ 4.167
assert('BM S3 avg (4+4+5+5+3+4)/6 ≈ 4.167', avgSeasonRating(BM_S3), 25/6)

console.log('\n── 3. avgSeriesRating rollup ──')
const bmSeasons = [BM_S1, BM_S2, BM_S3]
// (4.0 + 3.875 + 25/6) / 3
const expectedSeriesAvg = (4.0 + 3.875 + 25/6) / 3
assert(`BM series avg ≈ ${expectedSeriesAvg.toFixed(3)}`, avgSeriesRating(bmSeasons), expectedSeriesAvg)

console.log('\n── 4. episodesWatchedInSeason (derived) ──')
assert('BM S2: 4 episodes with watch events', episodesWatchedInSeason(BM_S2), 4)
assert('Severance S2: 5 episodes with watch events', episodesWatchedInSeason(UNWATCHED_SEASON), 5)
// Without episodes[], falls back to counter
const seasonNoEps = { seasonNumber: 1, episodeCount: 10, episodesWatched: 7, episodes: undefined }
assert('Fallback to counter when no episodes[]', episodesWatchedInSeason(seasonNoEps), 7)

console.log('\n── 5. logEpisode decoupling ──')

// Rating-only on an unwatched episode — must not create a watch event
const afterRatingOnly = logEpisode([UNWATCHED_SEASON], 2, 6, { rating: 4 })
const updatedEpAfterRating = afterRatingOnly[0].episodes.find(e => e.episodeNumber === 6)
assert('Rating-only: watchEvents count = 0 (decoupled)', updatedEpAfterRating.watchEvents.length, 0)
assert('Rating-only: ratings count = 1', updatedEpAfterRating.ratings.length, 1)
assert('Rating-only: reviews count = 0', updatedEpAfterRating.reviews.length, 0)
assert('Rating-only: rating value = 4', updatedEpAfterRating.ratings[0].rating, 4)
assert('Rating-only: ratedAt is set (not empty)', !!updatedEpAfterRating.ratings[0].ratedAt, true)
assert('Rating-only: no watch event, episodesWatched unchanged = 5', afterRatingOnly[0].episodesWatched, 5)

// Review-only on the same unwatched episode
const afterReviewOnly = logEpisode([UNWATCHED_SEASON], 2, 6, { reviewText: 'Great episode' })
const updatedEpAfterReview = afterReviewOnly[0].episodes.find(e => e.episodeNumber === 6)
assert('Review-only: watchEvents count = 0', updatedEpAfterReview.watchEvents.length, 0)
assert('Review-only: reviews count = 1', updatedEpAfterReview.reviews.length, 1)
assert('Review-only: ratings count = 0', updatedEpAfterReview.ratings.length, 0)
assert('Review-only: episodesWatched still 5 (no watch event)', afterReviewOnly[0].episodesWatched, 5)

// Watch + rating + review together → 3 independent records
const afterAll = logEpisode([UNWATCHED_SEASON], 2, 6, {
  watchedAt: '2026-06-20',
  watchNotes: 'Loved it',
  rating: 5,
  reviewText: 'Mind blowing',
})
const updatedEpAfterAll = afterAll[0].episodes.find(e => e.episodeNumber === 6)
assert('Combined: watchEvents count = 1', updatedEpAfterAll.watchEvents.length, 1)
assert('Combined: ratings count = 1 (independent)', updatedEpAfterAll.ratings.length, 1)
assert('Combined: reviews count = 1 (independent)', updatedEpAfterAll.reviews.length, 1)
assert('Combined: watch date is the date I gave', updatedEpAfterAll.watchEvents[0].watchedAt, '2026-06-20')
assert('Combined: episodesWatched incremented to 6', afterAll[0].episodesWatched, 6)

console.log('\n── 6. PERSIST_VERSION ──')
// Read useAppStore.ts and verify the version number
import { readFileSync } from 'fs'
const storeText = readFileSync(new URL('../src/store/useAppStore.ts', import.meta.url), 'utf8')
const versionMatch = storeText.match(/const PERSIST_VERSION = (\d+)/)
const version = versionMatch ? parseInt(versionMatch[1]) : 0
assert('PERSIST_VERSION bumped to 2', version, 2)

console.log('\n── 7. Mock data: 2 TV shows in mockTitles ──')
const mockDataText = readFileSync(new URL('../src/store/mockData.ts', import.meta.url), 'utf8')
const tvMatches = [...mockDataText.matchAll(/"type": "tv"/g)]
assert('2 TV entries in mockTitles', tvMatches.length, 2)
const blackMirrorMatch = mockDataText.includes('"Black Mirror"')
const severanceMatch = mockDataText.includes('"Severance"')
assert('Black Mirror present', blackMirrorMatch, true)
assert('Severance present', severanceMatch, true)

// ─── Summary ─────────────────────────────────────────────────────────────────
console.log(`\n${'─'.repeat(50)}`)
console.log(`Result: ${pass} passed, ${fail} failed`)
if (fail > 0) process.exit(1)
