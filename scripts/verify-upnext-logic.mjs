/**
 * Runtime logic verification for the "Up Next" continue-watching feature.
 * Run with: node scripts/verify-upnext-logic.mjs
 *
 * Mirrors the pure logic of src/store/episodeUtils.ts (nextUnwatchedEpisode)
 * and src/store/upNext.ts (computeUpNextShows), matching the convention of
 * scripts/verify-episode-logic.mjs.
 */

let pass = 0, fail = 0
function assert(label, actual, expected) {
  if (actual === expected) { console.log(`  ✓ ${label}: ${actual}`); pass++ }
  else { console.error(`  ✗ ${label}: expected ${expected}, got ${actual}`); fail++ }
}
function assertNull(label, actual) {
  if (actual === null) { console.log(`  ✓ ${label}: null`); pass++ }
  else { console.error(`  ✗ ${label}: expected null, got ${JSON.stringify(actual)}`); fail++ }
}

// ── Pure logic mirror: nextUnwatchedEpisode (mirrors episodeUtils.ts) ──
function nextUnwatchedEpisode(seasons) {
  const orderedSeasons = [...seasons].sort((a, b) => a.seasonNumber - b.seasonNumber)
  for (const season of orderedSeasons) {
    if (!season.episodes || season.episodes.length === 0) continue
    const orderedEpisodes = [...season.episodes].sort((a, b) => a.episodeNumber - b.episodeNumber)
    for (const episode of orderedEpisodes) {
      if (episode.watchEvents.length === 0) return { season, episode }
    }
  }
  return null
}

// ── Fixtures ──
const we = (n) => ({ id: `we-${n}`, watchedAt: '2026-01-' + String(n).padStart(2, '0') })
function ep(num, watched) {
  return { id: `e${num}`, episodeNumber: num, episodeName: `Ep ${num}`, watchEvents: watched ? [we(num)] : [], ratings: [], reviews: [] }
}

const partialSeasons = [
  { id: 's1', seasonNumber: 1, episodeCount: 3, episodesWatched: 3, episodes: [ep(1, true), ep(2, true), ep(3, true)] },
  { id: 's2', seasonNumber: 2, episodeCount: 5, episodesWatched: 3, episodes: [ep(1, true), ep(2, true), ep(3, true), ep(4, false), ep(5, false)] },
]
const caughtUpSeasons = [
  { id: 's1', seasonNumber: 1, episodeCount: 2, episodesWatched: 2, episodes: [ep(1, true), ep(2, true)] },
]
const unstartedSeasons = [
  { id: 's1', seasonNumber: 1, episodeCount: 3, episodesWatched: 0, episodes: [ep(1, false), ep(2, false), ep(3, false)] },
]
const coarseThenEpisodes = [
  { id: 's1', seasonNumber: 1, episodeCount: 10, episodesWatched: 5, episodes: undefined },
  { id: 's2', seasonNumber: 2, episodeCount: 2, episodesWatched: 0, episodes: [ep(1, false), ep(2, false)] },
]
const coarseOnly = [
  { id: 's1', seasonNumber: 1, episodeCount: 10, episodesWatched: 5, episodes: undefined },
]

console.log('\n── 1. nextUnwatchedEpisode ──')
const n1 = nextUnwatchedEpisode(partialSeasons)
assert('partial: season = 2', n1 ? n1.season.seasonNumber : null, 2)
assert('partial: episode = 4', n1 ? n1.episode.episodeNumber : null, 4)
assertNull('caught up: null', nextUnwatchedEpisode(caughtUpSeasons))
const n2 = nextUnwatchedEpisode(unstartedSeasons)
assert('unstarted: episode = 1', n2 ? n2.episode.episodeNumber : null, 1)
const n3 = nextUnwatchedEpisode(coarseThenEpisodes)
assert('coarse-then-episodes: skips coarse, season = 2', n3 ? n3.season.seasonNumber : null, 2)
assert('coarse-then-episodes: episode = 1', n3 ? n3.episode.episodeNumber : null, 1)
assertNull('coarse-only: null (no episode rows)', nextUnwatchedEpisode(coarseOnly))

// ── Pure logic mirror: computeUpNextShows (mirrors upNext.ts) ──
function episodesWatchedInSeason(season) {
  if (season.episodes?.length) return season.episodes.filter((e) => e.watchEvents.length > 0).length
  return season.episodesWatched
}
function totalEpisodesWatched(seasons) { return seasons.reduce((s, x) => s + episodesWatchedInSeason(x), 0) }
function totalEpisodeCount(seasons) { return seasons.reduce((s, x) => s + x.episodeCount, 0) }
function lastWatchedAtForTitle(title) {
  let max = null
  for (const season of title.seasons ?? [])
    for (const e of season.episodes ?? [])
      for (const w of e.watchEvents)
        if (max === null || w.watchedAt > max) max = w.watchedAt
  return max
}
function computeUpNextShows(titles) {
  const entries = []
  for (const title of titles) {
    if (title.type !== 'tv' || title.status !== 'watching') continue
    if (!title.seasons || title.seasons.length === 0) continue
    const next = nextUnwatchedEpisode(title.seasons)
    if (!next) continue
    entries.push({
      title,
      season: next.season,
      episode: next.episode,
      watchedCount: totalEpisodesWatched(title.seasons),
      totalCount: totalEpisodeCount(title.seasons),
      lastWatchedAt: lastWatchedAtForTitle(title),
    })
  }
  entries.sort((a, b) => {
    const aTs = a.lastWatchedAt ?? a.title.addedAt
    const bTs = b.lastWatchedAt ?? b.title.addedAt
    if (aTs < bTs) return 1
    if (aTs > bTs) return -1
    return 0
  })
  return entries
}

// Fixtures: a representative library
const T = (over) => ({ type: 'tv', status: 'watching', seasons: [], addedAt: '2026-01-01', ...over })
const upNextTitles = [
  T({ id: 't1', seasons: partialSeasons, addedAt: '2026-01-01' }),          // include: next S2E4, lastWatched 2026-01-03
  T({ id: 't2', seasons: caughtUpSeasons }),                                // exclude: caught up
  T({ id: 't3', status: 'watched', seasons: partialSeasons }),             // exclude: status
  T({ id: 't4', type: 'movie', status: 'watchlist', seasons: undefined }), // exclude: movie/watchlist
  T({ id: 't5', status: 'watchlist', seasons: unstartedSeasons }),         // exclude: status
  T({ id: 't6', seasons: unstartedSeasons, addedAt: '2026-03-01' }),       // include: next S1E1, no watch events
  T({ id: 't7', seasons: coarseOnly }),                                    // exclude: no episode rows
]

console.log('\n── 2. computeUpNextShows ──')
const up = computeUpNextShows(upNextTitles)
assert('count = 2 (only in-progress TV with a next ep)', up.length, 2)
assert('order[0] = t6 (newer effective ts: addedAt 2026-03-01)', up[0] ? up[0].title.id : null, 't6')
assert('order[1] = t1 (lastWatched 2026-01-03)', up[1] ? up[1].title.id : null, 't1')
const t1Entry = up.find((e) => e.title.id === 't1')
assert('t1 next season = 2', t1Entry ? t1Entry.season.seasonNumber : null, 2)
assert('t1 next episode = 4', t1Entry ? t1Entry.episode.episodeNumber : null, 4)
assert('t1 watchedCount = 6', t1Entry ? t1Entry.watchedCount : null, 6)
assert('t1 totalCount = 8', t1Entry ? t1Entry.totalCount : null, 8)

// ── Summary ──  (Tasks 2 and 3 insert their sections ABOVE this block)
console.log(`\n${'─'.repeat(50)}`)
console.log(`Result: ${pass} passed, ${fail} failed`)
if (fail > 0) process.exit(1)
