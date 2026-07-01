/**
 * Verifies the email normalization used before calling find_user_by_email.
 * Mirrors normalizeEmail in src/lib/auth.ts — the actual lookup (exact match,
 * self-exclusion) lives in the find_user_by_email SQL function and must be
 * checked manually against a real Supabase project (see plan notes).
 *
 * Run: node scripts/verify-friend-lookup-logic.mjs
 */

// ─── Pure logic (mirrored from auth.ts) ──────────────────────────────────────

/** @param {string} email */
function normalizeEmail(email) {
  return email.trim().toLowerCase()
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

console.log('Friend lookup normalization check:')

assert(normalizeEmail('Friend@Example.com') === 'friend@example.com', 'lowercases the domain and local part')
assert(normalizeEmail('  friend@example.com  ') === 'friend@example.com', 'trims surrounding whitespace')
assert(normalizeEmail('FRIEND@EXAMPLE.COM') === 'friend@example.com', 'fully uppercase input normalizes')
assert(normalizeEmail('friend@example.com') === 'friend@example.com', 'already-normalized input is unchanged')
assert(normalizeEmail(' Friend@Example.com') === normalizeEmail('friend@example.com '), 'two differently-cased/padded inputs match after normalization')

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
