/**
 * Verifies the recommendation-inbox state machine. Mirrors the SQL logic in
 * supabase/migrations/20260701000000_recommendations.sql (send_recommendation,
 * mark_recommendation_read, dismiss_recommendation, and the
 * recommendations_unique_idx upsert target) so the rules stay honest without
 * spinning up a real Postgres instance.
 *
 * Run: node scripts/verify-recommendation-inbox-logic.mjs
 */

// ─── Pure logic (mirrored from the recommendations migration) ───────────────

/** @param {string} senderId @param {string} recipientId @param {number} tmdbId @param {string} type */
function recKey(senderId, recipientId, tmdbId, type) {
  return `${senderId}:${recipientId}:${tmdbId}:${type}`
}

/**
 * @param {Map<string, object>} table
 * @param {string} me
 * @param {string} recipientId
 * @param {boolean} isFriend
 * @param {{ tmdbId: number, type: string, title: string, year: number, posterUrl: string | null }} snapshot
 */
function sendRecommendation(table, me, recipientId, isFriend, snapshot) {
  if (me === recipientId) throw new Error('Cannot send a recommendation to yourself')
  if (!isFriend) throw new Error('Can only send recommendations to accepted friends')

  // ON CONFLICT (sender_user_id, recipient_user_id, tmdb_id, type) DO UPDATE —
  // a full (non-partial) unique index, so resending after dismissal flips the
  // existing row back to unread instead of inserting a duplicate.
  const key = recKey(me, recipientId, snapshot.tmdbId, snapshot.type)
  const existing = table.get(key)
  table.set(key, {
    sender_user_id: me,
    recipient_user_id: recipientId,
    tmdb_id: snapshot.tmdbId,
    type: snapshot.type,
    title: snapshot.title,
    year: snapshot.year,
    poster_url: snapshot.posterUrl,
    status: 'unread',
    created_at: existing?.created_at ?? 'created',
  })
  return table.get(key)
}

/**
 * @param {Map<string, object>} table
 * @param {string} recId
 * @param {string} recipientId
 */
function markRecommendationRead(table, recId, recipientId) {
  const entry = [...table.entries()].find(([, v]) => v.id === recId)
  if (!entry) return
  const [key, v] = entry
  if (v.recipient_user_id !== recipientId || v.status !== 'unread') return // matches `where status = 'unread'`
  table.set(key, { ...v, status: 'read' })
}

/**
 * @param {Map<string, object>} table
 * @param {string} recId
 * @param {string} recipientId
 */
function dismissRecommendation(table, recId, recipientId) {
  const entry = [...table.entries()].find(([, v]) => v.id === recId)
  if (!entry) return
  const [key, v] = entry
  if (v.recipient_user_id !== recipientId) return
  table.set(key, { ...v, status: 'dismissed' })
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

const ALICE = 'a1111111-1111-1111-1111-111111111111'
const BOB = 'b2222222-2222-2222-2222-222222222222'
const CAROL = 'c3333333-3333-3333-3333-333333333333'

const DUNE = { tmdbId: 438631, type: 'movie', title: 'Dune', year: 2021, posterUrl: '/dune.jpg' }
const DUNE_REDUX = { tmdbId: 438631, type: 'movie', title: 'Dune (Extended)', year: 2021, posterUrl: '/dune2.jpg' }

console.log('Recommendation inbox logic check:')

// Sending creates an unread row addressed to the right recipient
let table = new Map()
let rec = sendRecommendation(table, ALICE, BOB, true, DUNE)
assert(rec.status === 'unread' && rec.sender_user_id === ALICE && rec.recipient_user_id === BOB, 'sending creates an unread row from sender to recipient')
assert(table.size === 1, 'a single send produces a single row')

// Self-send throws
let threw = false
try {
  sendRecommendation(table, ALICE, ALICE, true, DUNE)
} catch {
  threw = true
}
assert(threw, 'sending a recommendation to yourself throws')

// Sending to a non-friend throws
threw = false
try {
  sendRecommendation(table, ALICE, CAROL, false, DUNE)
} catch {
  threw = true
}
assert(threw, 'sending to a non-friend throws')

// Resending the same title to the same friend updates the row in place —
// no duplicate, because the unique index covers (sender, recipient, tmdb_id, type)
sendRecommendation(table, ALICE, BOB, true, DUNE_REDUX)
assert(table.size === 1, 'resending the same title to the same friend does not create a duplicate row')
assert(table.get(recKey(ALICE, BOB, DUNE.tmdbId, DUNE.type)).title === 'Dune (Extended)', 'resending overwrites the snapshot with the latest title/year/poster')

// mark_recommendation_read only transitions from unread, scoped to the recipient
table = new Map()
table.set(recKey(ALICE, BOB, DUNE.tmdbId, DUNE.type), { id: 'r1', sender_user_id: ALICE, recipient_user_id: BOB, status: 'unread' })
markRecommendationRead(table, 'r1', BOB)
assert(table.get(recKey(ALICE, BOB, DUNE.tmdbId, DUNE.type)).status === 'read', 'the recipient marking an unread recommendation flips it to read')

markRecommendationRead(table, 'r1', BOB) // already read — no-op, stays read
assert(table.get(recKey(ALICE, BOB, DUNE.tmdbId, DUNE.type)).status === 'read', 'marking an already-read recommendation is a no-op')

table.set(recKey(ALICE, CAROL, DUNE.tmdbId, DUNE.type), { id: 'r2', sender_user_id: ALICE, recipient_user_id: CAROL, status: 'unread' })
markRecommendationRead(table, 'r2', BOB) // BOB isn't the recipient
assert(table.get(recKey(ALICE, CAROL, DUNE.tmdbId, DUNE.type)).status === 'unread', 'marking read as a non-recipient is a no-op')

// dismiss_recommendation works from any prior status, but only for the recipient
table = new Map()
table.set(recKey(ALICE, BOB, DUNE.tmdbId, DUNE.type), { id: 'r3', sender_user_id: ALICE, recipient_user_id: BOB, status: 'read' })
dismissRecommendation(table, 'r3', BOB)
assert(table.get(recKey(ALICE, BOB, DUNE.tmdbId, DUNE.type)).status === 'dismissed', 'the recipient dismissing a read recommendation marks it dismissed')

table.set(recKey(ALICE, CAROL, DUNE.tmdbId, DUNE.type), { id: 'r4', sender_user_id: ALICE, recipient_user_id: CAROL, status: 'unread' })
dismissRecommendation(table, 'r4', BOB) // BOB isn't the recipient
assert(table.get(recKey(ALICE, CAROL, DUNE.tmdbId, DUNE.type)).status === 'unread', 'dismissing as a non-recipient is a no-op')

// Resending after dismissal resurfaces the same row as unread — the exact
// edge case a partial (WHERE status <> 'dismissed') unique index would miss.
table = new Map()
sendRecommendation(table, ALICE, BOB, true, DUNE)
const dismissedKey = recKey(ALICE, BOB, DUNE.tmdbId, DUNE.type)
table.set(dismissedKey, { ...table.get(dismissedKey), id: 'r5', status: 'dismissed' })
sendRecommendation(table, ALICE, BOB, true, DUNE)
assert(table.size === 1, 'resending after dismissal does not create a second row')
assert(table.get(dismissedKey).status === 'unread', 'resending after dismissal resurfaces the recommendation as unread')

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
