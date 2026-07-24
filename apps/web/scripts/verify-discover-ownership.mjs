/**
 * Verifies the tmdbId-based "already in library" ownership check used by the
 * Discover view. Mirrors the logic in src/views/Discover.tsx so the build
 * stays honest without a test runner.
 *
 * Run: node scripts/verify-discover-ownership.mjs
 */

// ─── Pure logic (mirrored from Discover.tsx) ─────────────────────────────────

/**
 * Build a Set of tmdbIds from a library title list.
 * Handles titles that have no tmdbId (undefined / null) by excluding them.
 *
 * @param {Array<{tmdbId?: number}>} titles
 * @returns {Set<number>}
 */
function buildLibraryTmdbIds(titles) {
  const ids = new Set()
  for (const t of titles) {
    if (t.tmdbId != null) ids.add(t.tmdbId)
  }
  return ids
}

/**
 * Check whether a discovered result is already in the library.
 *
 * @param {{tmdbId?: number}} result
 * @param {Set<number>} libraryTmdbIds
 * @returns {boolean}
 */
function isOwned(result, libraryTmdbIds) {
  return result.tmdbId != null && libraryTmdbIds.has(result.tmdbId)
}

// ─── Test cases ───────────────────────────────────────────────────────────────

let passed = 0
let failed = 0

function assert(condition, label) {
  if (condition) {
    console.log(`  ✓ ${label}`)
    passed++
  } else {
    console.error(`  ✗ ${label}`)
    failed++
  }
}

const libraryTitles = [
  { id: 'a', tmdbId: 238, title: 'The Godfather' },
  { id: 'b', tmdbId: 372058, title: 'Your Name' },
  { id: 'c', title: 'Unknown (no tmdbId)' }, // no tmdbId
]

const ids = buildLibraryTmdbIds(libraryTitles)

console.log('Discover ownership check:')

assert(ids.size === 2, 'builds id set excluding titles without tmdbId')
assert(isOwned({ tmdbId: 238 }, ids), 'The Godfather (238) recognized as owned')
assert(isOwned({ tmdbId: 372058 }, ids), 'Your Name (372058) recognized as owned')
assert(!isOwned({ tmdbId: 999 }, ids), 'Unknown tmdbId not marked as owned')
assert(!isOwned({ tmdbId: undefined }, ids), 'undefined tmdbId not marked as owned')
assert(!isOwned({}, ids), 'missing tmdbId not marked as owned')

const resultNotOwned = { tmdbId: 533535, title: 'Deadpool & Wolverine' }
assert(!isOwned(resultNotOwned, ids), 'Discover result not in library shows as not owned')

const resultOwned = { tmdbId: 238, title: 'The Godfather' }
assert(isOwned(resultOwned, ids), 'Discover result that is in library shows as owned')

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
