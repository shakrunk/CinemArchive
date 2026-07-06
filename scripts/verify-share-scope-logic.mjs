/**
 * Verifies the pure scope-matching predicate mirrored from
 * supabase/migrations/20260706060000_share_scopes.sql's title_in_scope()
 * SQL function, plus the "no share_scopes row -> unrestricted" invariant
 * that can_view_title() depends on.
 *
 * This does NOT (and cannot) verify the RLS policies themselves — that
 * requires a real Postgres (supabase db start / db reset) with sessions set
 * up as owner / scoped friend / unscoped friend / anonymous shared-token
 * visitor. Before merging 20260706060000_share_scopes.sql to main, run:
 *   1. supabase db start && supabase db reset   (applies all migrations)
 *   2. As owner: create a shared link and a friend, scope one of them to a
 *      single genre via share_scopes (insert directly or via the UI once built)
 *   3. select set_config('app.shared_token', '<token>', false); as anon —
 *      confirm only in-scope titles are returned from `titles`
 *   4. Repeat as the scoped friend (set a session with that friend's auth.uid())
 *   5. Confirm a friend/link with NO share_scopes row still sees everything
 *   6. Confirm episode_watch_events/episode_ratings/episode_reviews (joined
 *      through episodes, no direct title_id) respect the same scoping
 *
 * Run: node scripts/verify-share-scope-logic.mjs
 */

// ─── Pure logic (mirrored from the migration) ────────────────────────────────

/**
 * @param {string[]} genres
 * @param {string} status
 * @param {string[]|null} allowedGenres
 * @param {string[]|null} allowedStatuses
 */
function titleInScope(genres, status, allowedGenres, allowedStatuses) {
  const genresOk = allowedGenres == null || genres.some((g) => allowedGenres.includes(g))
  const statusOk = allowedStatuses == null || allowedStatuses.includes(status)
  return genresOk && statusOk
}

/**
 * Mirrors can_view_title's scope resolution: no share_scopes row for this
 * link/friend means unrestricted, regardless of what the title looks like.
 * @param {{allowed_genres: string[]|null, allowed_statuses: string[]|null}|null} scope
 * @param {string[]} genres
 * @param {string} status
 */
function canViewGivenScope(scope, genres, status) {
  if (!scope) return true // the load-bearing default-unrestricted invariant
  return titleInScope(genres, status, scope.allowed_genres, scope.allowed_statuses)
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

console.log('Share scope logic check:')

// ── The non-negotiable invariant: no row = unrestricted ──
assert(
  canViewGivenScope(null, [], 'watched') === true,
  'no share_scopes row + a title with no genres at all is still visible (default-unrestricted)'
)
assert(
  canViewGivenScope(null, ['Horror'], 'dropped') === true,
  'no share_scopes row makes every genre/status combination visible'
)

// ── Null on one dimension means unrestricted on that dimension only ──
assert(
  titleInScope(['Comedy'], 'watchlist', null, ['watched']) === false,
  'null allowed_genres (unrestricted genre) still enforces a non-null status restriction'
)
assert(
  titleInScope(['Comedy'], 'watched', ['Horror'], null) === false,
  'null allowed_statuses (unrestricted status) still enforces a non-null genre restriction'
)
assert(
  titleInScope(['Comedy'], 'watched', null, null) === true,
  'both dimensions null is fully unrestricted'
)

// ── Genre matching uses array overlap (ANY shared genre), not full match ──
assert(
  titleInScope(['Horror', 'Comedy'], 'watched', ['Horror', 'Thriller'], null) === true,
  'a title matches if it has at least one genre in the allowed set'
)
assert(
  titleInScope(['Comedy', 'Romance'], 'watched', ['Horror', 'Thriller'], null) === false,
  'a title with no genre in the allowed set is excluded'
)
assert(
  titleInScope([], 'watched', ['Horror'], null) === false,
  "a title with no genres at all never matches a non-null genre restriction (Postgres && against '{}' is always false)"
)

// ── Status matching is exact membership ──
assert(
  titleInScope(['Horror'], 'watching', null, ['watched', 'watching']) === true,
  'status matches when it is one of the allowed statuses'
)
assert(
  titleInScope(['Horror'], 'dropped', null, ['watched', 'watching']) === false,
  'status is excluded when it is not in the allowed set'
)

// ── Combined restriction is AND, not OR ──
assert(
  titleInScope(['Horror'], 'watched', ['Horror'], ['watched']) === true,
  'a title matching both genre and status restrictions is visible'
)
assert(
  titleInScope(['Horror'], 'dropped', ['Horror'], ['watched']) === false,
  'a title matching genre but not status is excluded (AND, not OR)'
)
assert(
  titleInScope(['Comedy'], 'watched', ['Horror'], ['watched']) === false,
  'a title matching status but not genre is excluded (AND, not OR)'
)

// ── Scope resolution end-to-end (mirrors the shape can_view_title checks) ──
const horrorOnlyScope = { allowed_genres: ['Horror'], allowed_statuses: null }
assert(
  canViewGivenScope(horrorOnlyScope, ['Horror', 'Drama'], 'watched') === true,
  'a scoped friend/link sees an in-scope title'
)
assert(
  canViewGivenScope(horrorOnlyScope, ['Drama'], 'watched') === false,
  'a scoped friend/link does not see an out-of-scope title'
)

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
