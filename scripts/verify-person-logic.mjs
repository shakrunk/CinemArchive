/**
 * Runtime logic verification for the "browse by person" filter
 * (src/store/useAppStore.ts: titleHasPerson + the person filter clause).
 * Run with: node scripts/verify-person-logic.mjs
 */
let pass = 0, fail = 0
function assert(label, actual, expected) {
  if (actual === expected) { console.log(`  ✓ ${label}: ${JSON.stringify(actual)}`); pass++ }
  else { console.error(`  ✗ ${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`); fail++ }
}

// ── Mirror of titleHasPerson in src/store/useAppStore.ts ──
function titleHasPerson(title, personId) {
  if (title.cast?.some((c) => c.tmdbPersonId === personId)) return true
  if (title.crew?.some((c) => c.tmdbPersonId === personId)) return true
  for (const season of title.seasons ?? []) {
    if (season.cast?.some((c) => c.tmdbPersonId === personId)) return true
    for (const ep of season.episodes ?? []) {
      if (ep.crew?.some((c) => c.tmdbPersonId === personId)) return true
    }
  }
  return false
}

// ── Mirror of the person clause inside applyFiltersToTitles ──
function filterByPerson(titles, person) {
  if (!person) return titles
  return titles.filter((t) => titleHasPerson(t, person.id))
}

// ── Fixtures ──
const titleCastOnly = {
  id: 'a', title: 'Cast Only',
  cast: [{ tmdbPersonId: 1, name: 'Ada' }, { tmdbPersonId: 2, name: 'Bo' }],
}
const titleCrewOnly = {
  id: 'b', title: 'Crew Only',
  crew: [{ tmdbPersonId: 2, name: 'Bo', job: 'Director' }],
}
const titleSeasonCast = {
  id: 'c', title: 'Season Cast',
  seasons: [{ seasonNumber: 1, cast: [{ tmdbPersonId: 3, name: 'Cy' }], episodes: [] }],
}
const titleEpisodeCrew = {
  id: 'd', title: 'Episode Crew',
  seasons: [{ seasonNumber: 1, episodes: [{ episodeNumber: 1, crew: [{ tmdbPersonId: 4, name: 'Di', job: 'Writer' }] }] }],
}
const titleEmpty = { id: 'e', title: 'No Credits' }
const all = [titleCastOnly, titleCrewOnly, titleSeasonCast, titleEpisodeCrew, titleEmpty]

console.log('\n── 1. titleHasPerson matches each credit source ──')
assert('title cast', titleHasPerson(titleCastOnly, 1), true)
assert('title crew', titleHasPerson(titleCrewOnly, 2), true)
assert('season cast', titleHasPerson(titleSeasonCast, 3), true)
assert('episode crew', titleHasPerson(titleEpisodeCrew, 4), true)

console.log('\n── 2. titleHasPerson rejects absent / credit-less ──')
assert('absent id', titleHasPerson(titleCastOnly, 999), false)
assert('empty title', titleHasPerson(titleEmpty, 1), false)

console.log('\n── 3. null person filter is a pass-through ──')
assert('null → all', filterByPerson(all, null).length, all.length)

console.log('\n── 4. person filter selects across all credit sources ──')
assert('id 2 (cast in a, crew in b) → 2 titles', filterByPerson(all, { id: 2 }).length, 2)
assert('id 2 includes Cast Only', filterByPerson(all, { id: 2 }).some((t) => t.id === 'a'), true)
assert('id 2 includes Crew Only', filterByPerson(all, { id: 2 }).some((t) => t.id === 'b'), true)
assert('id 3 (season cast) → 1 title', filterByPerson(all, { id: 3 }).length, 1)
assert('id 4 (episode crew) → 1 title', filterByPerson(all, { id: 4 }).length, 1)

console.log('\n── 5. unknown person → empty result ──')
assert('id 999 → 0 titles', filterByPerson(all, { id: 999 }).length, 0)

console.log(`\n${'─'.repeat(50)}`)
console.log(`Result: ${pass} passed, ${fail} failed`)
if (fail > 0) process.exit(1)
