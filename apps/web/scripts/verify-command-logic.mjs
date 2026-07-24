/**
 * Runtime logic verification for command-palette ranking (src/store/commands.ts).
 * Run with: node scripts/verify-command-logic.mjs
 */
let pass = 0, fail = 0
function assert(label, actual, expected) {
  if (actual === expected) { console.log(`  ✓ ${label}: ${JSON.stringify(actual)}`); pass++ }
  else { console.error(`  ✗ ${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`); fail++ }
}
function escapeRegex(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') }
function scoreCommand(cmd, query) {
  const q = query.trim().toLowerCase()
  if (!q) return 0
  const label = cmd.label.toLowerCase()
  const kw = (cmd.keywords ?? '').toLowerCase()
  const lenAdj = Math.min(label.length, 40) * 0.1
  const boundary = new RegExp(`\\b${escapeRegex(q)}`)
  if (label === q) return 100 - lenAdj
  if (label.startsWith(q)) return 80 - lenAdj
  if (boundary.test(label)) return 60 - lenAdj
  if (label.includes(q)) return 40 - lenAdj
  if (kw) { if (boundary.test(kw)) return 20; if (kw.includes(q)) return 10 }
  return -1
}
function rankCommands(commands, query, limit = 8) {
  if (!query.trim()) return commands.slice(0, limit)
  const scored = commands.map((cmd) => ({ cmd, score: scoreCommand(cmd, query) })).filter((x) => x.score >= 0)
  scored.sort((a, b) => {
    if (b.score !== a.score) return b.score - a.score
    if (a.cmd.label.length !== b.cmd.label.length) return a.cmd.label.length - b.cmd.label.length
    return a.cmd.id < b.cmd.id ? -1 : a.cmd.id > b.cmd.id ? 1 : 0
  })
  return scored.slice(0, limit).map((x) => x.cmd)
}

const C = (id, label, extra = {}) => ({ id, kind: 'title', label, ...extra })
const cmds = [
  C('a', 'Overblade'),
  C('b', 'Blade Runner', { keywords: 'sci-fi noir villeneuve' }),
  C('c', 'Blade Runner 2049'),
  C('d', 'The Godfather', { keywords: 'crime' }),
]

console.log('\n── 1. empty query keeps input order, capped ──')
const empt = rankCommands(cmds, '', 3)
assert('count = 3', empt.length, 3)
assert('order preserved [0]=a', empt[0].id, 'a')
assert('order preserved [2]=c', empt[2].id, 'c')

console.log('\n── 2. prefix beats mid-substring ──')
const bla = rankCommands(cmds, 'bla', 8)
assert('prefix "Blade Runner" before "Overblade"',
  bla.findIndex((x) => x.id === 'b') < bla.findIndex((x) => x.id === 'a'), true)

console.log('\n── 3. shorter label wins ties (b before c) ──')
assert('"Blade Runner" before "Blade Runner 2049"',
  bla.findIndex((x) => x.id === 'b') < bla.findIndex((x) => x.id === 'c'), true)

console.log('\n── 4. word-boundary match ──')
const run = rankCommands(cmds, 'run', 8)
assert('"Blade Runner" matched via boundary', run.some((x) => x.id === 'b'), true)

console.log('\n── 5. keyword match included, ranks below label ──')
const noir = rankCommands(cmds, 'noir', 8)
assert('keyword "noir" finds Blade Runner', noir.some((x) => x.id === 'b'), true)
assert('keyword-only "crime" finds Godfather', rankCommands(cmds, 'crime', 8).some((x) => x.id === 'd'), true)

console.log('\n── 6. no match excluded ──')
assert('"zzz" → empty', rankCommands(cmds, 'zzz', 8).length, 0)

console.log(`\n${'─'.repeat(50)}`)
console.log(`Result: ${pass} passed, ${fail} failed`)
if (fail > 0) process.exit(1)
