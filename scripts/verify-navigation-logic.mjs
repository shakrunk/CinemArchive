/**
 * Runtime logic verification for query-param navigation (src/lib/navigation.ts).
 * Run with: node scripts/verify-navigation-logic.mjs
 * Mirrors parseNav / serializeNav / preservedParams (URLSearchParams is a Node global).
 */
let pass = 0, fail = 0
function assert(label, actual, expected) {
  if (actual === expected) { console.log(`  ✓ ${label}: ${JSON.stringify(actual)}`); pass++ }
  else { console.error(`  ✗ ${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`); fail++ }
}

const APP_VIEWS = ['upnext', 'library', 'ledger', 'discover', 'profile']
const PRESERVED_KEYS = ['share']
function parseNav(search, fallbackView) {
  const p = new URLSearchParams(search)
  const raw = p.get('view')
  const view = APP_VIEWS.includes(raw) ? raw : fallbackView
  const title = p.get('title')
  return { view, title: title || null, add: p.get('add') === '1' }
}
function preservedParams(search) {
  const p = new URLSearchParams(search)
  const out = {}
  for (const k of PRESERVED_KEYS) { const v = p.get(k); if (v) out[k] = v }
  return out
}
function serializeNav(nav, preserved) {
  const p = new URLSearchParams()
  for (const k of Object.keys(preserved).sort()) p.set(k, preserved[k])
  p.set('view', nav.view)
  if (nav.title) p.set('title', nav.title)
  if (nav.add) p.set('add', '1')
  return `?${p.toString()}`
}

console.log('\n── 1. parseNav ──')
assert('empty → fallback view', parseNav('', 'library').view, 'library')
assert('empty → title null', parseNav('', 'library').title, null)
assert('empty → add false', parseNav('', 'library').add, false)
assert('unknown view → fallback', parseNav('?view=bogus', 'upnext').view, 'upnext')
assert('valid view', parseNav('?view=ledger', 'library').view, 'ledger')
assert('valid view (profile)', parseNav('?view=profile', 'library').view, 'profile')
assert('title round-trips', parseNav('?view=library&title=abc123', 'library').title, 'abc123')
assert('add=1 → true', parseNav('?add=1', 'library').add, true)
assert('add=0 → false', parseNav('?add=0', 'library').add, false)

console.log('\n── 2. preservedParams ──')
assert('share extracted', preservedParams('?share=tok&view=ledger').share, 'tok')
assert('no share → undefined', preservedParams('?view=ledger').share, undefined)

console.log('\n── 3. serializeNav ──')
assert('includes view', serializeNav({ view: 'ledger', title: null, add: false }, {}), '?view=ledger')
assert('omits null title/add', serializeNav({ view: 'upnext', title: null, add: false }, {}).includes('title'), false)
const withTitle = serializeNav({ view: 'library', title: 'x1', add: false }, {})
assert('title serialized', withTitle.includes('title=x1'), true)
const withShare = serializeNav({ view: 'ledger', title: null, add: false }, { share: 'tok' })
assert('share preserved + view', withShare.includes('share=tok') && withShare.includes('view=ledger'), true)
assert('determinism', serializeNav({ view: 'library', title: 't', add: true }, { share: 's' }),
                       serializeNav({ view: 'library', title: 't', add: true }, { share: 's' }))

console.log('\n── 4. round-trip identity ──')
for (const n of [
  { view: 'upnext', title: null, add: false },
  { view: 'library', title: 'abc', add: false },
  { view: 'ledger', title: null, add: true },
  { view: 'library', title: 'z9', add: true },
]) {
  const round = parseNav(serializeNav(n, {}), 'library')
  assert(`round-trip ${JSON.stringify(n)}`, JSON.stringify(round), JSON.stringify(n))
}

console.log(`\n${'─'.repeat(50)}`)
console.log(`Result: ${pass} passed, ${fail} failed`)
if (fail > 0) process.exit(1)
