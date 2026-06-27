/**
 * Verify getUnlockedModes and getEarnedModes logic.
 * Run with: node scripts/verify-episode-modes.mjs
 */

function getUnlockedModes(title) {
  const modes = new Set()
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) {
      for (const we of ep.watchEvents) {
        if (we.colorMode) modes.add(we.colorMode)
      }
    }
  }
  return modes
}

function getEarnedModes(title) {
  const allEps = []
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) allEps.push(ep)
  }
  if (allEps.length === 0) return new Set()
  const earned = new Set()
  for (const mode of ['bw', 'color']) {
    if (allEps.every((ep) => ep.watchEvents.some((we) => we.colorMode === mode))) {
      earned.add(mode)
    }
  }
  return earned
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

let passed = 0, failed = 0
function assert(label, cond) {
  if (cond) { console.log(`  ✓ ${label}`); passed++ }
  else { console.error(`  ✗ ${label}`); failed++ }
}
const ep = (n, watchEvents) => ({ id: `e${n}`, episodeNumber: n, watchEvents, ratings: [], reviews: [] })
const we = (colorMode) => ({ id: 'w1', watchedAt: '2026-01-01', colorMode })
const season = (episodes) => ({ seasonNumber: 1, episodes })
const title = (seasons) => ({ seasons })

// ─── getUnlockedModes ────────────────────────────────────────────────────────

console.log('\ngetUnlockedModes')

assert('no watch events → empty',
  getUnlockedModes(title([season([ep(1, [])])])).size === 0)

assert('one bw event → bw unlocked, not color',
  (() => { const s = getUnlockedModes(title([season([ep(1, [we('bw')])])])); return s.has('bw') && !s.has('color') })())

assert('one color event → color unlocked, not bw',
  (() => { const s = getUnlockedModes(title([season([ep(1, [we('color')])])])); return s.has('color') && !s.has('bw') })())

assert('bw on ep1 + color on ep2 → both unlocked',
  (() => { const s = getUnlockedModes(title([season([ep(1, [we('bw')]), ep(2, [we('color')])])])); return s.has('bw') && s.has('color') })())

assert('undefined colorMode on event → not counted',
  getUnlockedModes(title([season([ep(1, [we(undefined)])])])).size === 0)

// ─── getEarnedModes ──────────────────────────────────────────────────────────

console.log('\ngetEarnedModes')

assert('no episodes → empty',
  getEarnedModes(title([])).size === 0)

assert('ep1 watched bw, ep2 not watched → bw not earned',
  !getEarnedModes(title([season([ep(1, [we('bw')]), ep(2, [])])])).has('bw'))

assert('ep1 + ep2 both watched bw → bw earned',
  getEarnedModes(title([season([ep(1, [we('bw')]), ep(2, [we('bw')])])])).has('bw'))

assert('all bw but none color → only bw earned',
  (() => { const s = getEarnedModes(title([season([ep(1, [we('bw')]), ep(2, [we('bw')])])])); return s.has('bw') && !s.has('color') })())

assert('all watched in both modes → both earned',
  (() => { const s = getEarnedModes(title([season([ep(1, [we('bw'), we('color')]), ep(2, [we('bw'), we('color')])])])); return s.has('bw') && s.has('color') })())

assert('multiple seasons: bw earned only when all seasons complete',
  (() => {
    const t = title([
      season([ep(1, [we('bw')])]),
      season([ep(2, [])]),  // ep2 in season 2 not watched
    ])
    return !getEarnedModes(t).has('bw')
  })())

// ─── Result ──────────────────────────────────────────────────────────────────

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
