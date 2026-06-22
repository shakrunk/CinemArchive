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

// ── Summary ──  (Tasks 2 and 3 insert their sections ABOVE this block)
console.log(`\n${'─'.repeat(50)}`)
console.log(`Result: ${pass} passed, ${fail} failed`)
if (fail > 0) process.exit(1)
