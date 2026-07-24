/**
 * Verifies the friendship state-machine transitions. Mirrors the SQL logic
 * in supabase/migrations/20260630010000_friendships.sql (send_friend_request,
 * accept_friend_request, decline_friend_request, cancel_friend_request, block_user) so the rules
 * stay honest without spinning up a real Postgres instance.
 *
 * Run: node scripts/verify-friendship-state-machine.mjs
 */

// ─── Pure logic (mirrored from the friendships migration) ───────────────────

/** @param {string} u1 @param {string} u2 */
function canonicalPair(u1, u2) {
  return u1 < u2 ? [u1, u2] : [u2, u1]
}

/**
 * @param {object|null} state
 * @param {string} me
 * @param {string} target
 */
function sendFriendRequest(state, me, target) {
  if (me === target) throw new Error('Cannot send a friend request to yourself')
  const [a, b] = canonicalPair(me, target)

  if (!state) {
    return { user_id_a: a, user_id_b: b, requested_by: me, status: 'pending', blocked_by: null }
  }
  if (state.status === 'blocked') throw new Error('Cannot send a friend request to this user')
  if (state.status === 'accepted' || state.requested_by === me) return state // no-op

  // Mutual request — the other party already asked us.
  return { ...state, status: 'accepted' }
}

/**
 * The SQL recomputes user_id_a/b from (me, requester_user_id) and only
 * updates a row matching that exact pair — so passing your own id as the
 * "requester" degenerates to a=b and can never match a real row.
 *
 * @param {object|null} state
 * @param {string} me
 * @param {string} requesterId
 */
function acceptFriendRequest(state, me, requesterId) {
  const [a, b] = canonicalPair(me, requesterId)
  const matches =
    state && state.user_id_a === a && state.user_id_b === b && state.status === 'pending' && state.requested_by === requesterId
  if (!matches) throw new Error('No pending friend request from this user')
  return { ...state, status: 'accepted' }
}

/**
 * @param {object|null} state
 * @param {string} me
 * @param {string} requesterId
 */
function declineFriendRequest(state, me, requesterId) {
  const [a, b] = canonicalPair(me, requesterId)
  const matches =
    state && state.user_id_a === a && state.user_id_b === b && state.status === 'pending' && state.requested_by === requesterId
  if (!matches) return state
  return null // row deleted
}

/**
 * @param {object|null} state
 * @param {string} me
 * @param {string} recipientId
 */
function cancelFriendRequest(state, me, recipientId) {
  const [a, b] = canonicalPair(me, recipientId)
  const matches =
    state && state.user_id_a === a && state.user_id_b === b && state.status === 'pending' && state.requested_by === me
  if (!matches) throw new Error('No pending friend request to this user')
  return null // row deleted
}

/**
 * @param {object|null} state
 * @param {string} me
 * @param {string} target
 */
function blockUser(state, me, target) {
  if (me === target) throw new Error('Cannot block yourself')
  const [a, b] = canonicalPair(me, target)
  return { user_id_a: a, user_id_b: b, requested_by: state?.requested_by ?? me, status: 'blocked', blocked_by: me }
}

/**
 * Drops the row entirely (like declineFriendRequest) rather than reverting to
 * 'pending'/'accepted' — only the blocking party may unblock.
 * @param {object|null} state
 * @param {string} me
 * @param {string} target
 */
function unblockUser(state, me, target) {
  const [a, b] = canonicalPair(me, target)
  const matches = state && state.user_id_a === a && state.user_id_b === b && state.status === 'blocked' && state.blocked_by === me
  if (!matches) throw new Error('No block from you on this user to remove')
  return null // row deleted
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

console.log('Friendship state machine check:')

// Canonicalization is order-independent
assert(
  JSON.stringify(canonicalPair(ALICE, BOB)) === JSON.stringify(canonicalPair(BOB, ALICE)),
  'canonical pair ordering is symmetric regardless of caller order'
)

// Basic request → accept flow
let s1 = sendFriendRequest(null, ALICE, BOB)
assert(s1.status === 'pending' && s1.requested_by === ALICE, 'send_friend_request creates a pending row owned by the sender')

s1 = acceptFriendRequest(s1, BOB, ALICE)
assert(s1.status === 'accepted', 'recipient accepting a pending request from the sender marks it accepted')

// The sender cannot accept their own outgoing request by passing their own
// id as "who it's from" — the recomputed pair (ALICE, ALICE) never matches
// the real (ALICE, BOB) row.
let s2 = sendFriendRequest(null, ALICE, BOB)
let threw = false
try {
  acceptFriendRequest(s2, ALICE, ALICE)
} catch {
  threw = true
}
assert(threw, 'accepting a request "from yourself" throws (cannot self-accept)')

// Duplicate send is a no-op while pending
let s3 = sendFriendRequest(null, ALICE, BOB)
const s3Again = sendFriendRequest(s3, ALICE, BOB)
assert(s3Again.status === 'pending' && s3Again.requested_by === ALICE, 're-sending an already-pending request is a no-op')

// Mutual request auto-accepts
let s4 = sendFriendRequest(null, ALICE, BOB)
s4 = sendFriendRequest(s4, BOB, ALICE)
assert(s4.status === 'accepted', 'the recipient independently requesting back auto-accepts the friendship')

// Sending to an already-accepted pair is a no-op
let s5 = { user_id_a: canonicalPair(ALICE, BOB)[0], user_id_b: canonicalPair(ALICE, BOB)[1], requested_by: ALICE, status: 'accepted', blocked_by: null }
const s5Again = sendFriendRequest(s5, BOB, ALICE)
assert(s5Again.status === 'accepted', 'sending a request to an existing friend is a no-op')

// Decline removes a pending row
let s6 = sendFriendRequest(null, ALICE, BOB)
s6 = declineFriendRequest(s6, BOB, ALICE)
assert(s6 === null, 'declining a pending request from the correct sender removes the row')

// Sender can withdraw their own outgoing request.
let s6b = sendFriendRequest(null, ALICE, BOB)
s6b = cancelFriendRequest(s6b, ALICE, BOB)
assert(s6b === null, 'cancelling an outgoing pending request removes the row')

threw = false
try {
  cancelFriendRequest(sendFriendRequest(null, ALICE, BOB), BOB, ALICE)
} catch {
  threw = true
}
assert(threw, 'only the original sender can cancel a pending request')

// Decline is a no-op against the wrong sender id
let s7 = sendFriendRequest(null, ALICE, BOB)
const s7Unchanged = declineFriendRequest(s7, BOB, CAROL)
assert(s7Unchanged === s7, 'declining with a mismatched sender id leaves the row untouched')

// Self-request / self-block throw
threw = false
try {
  sendFriendRequest(null, ALICE, ALICE)
} catch {
  threw = true
}
assert(threw, 'sending a friend request to yourself throws')

threw = false
try {
  blockUser(null, ALICE, ALICE)
} catch {
  threw = true
}
assert(threw, 'blocking yourself throws')

// Blocking overrides any prior state
let s8 = sendFriendRequest(null, ALICE, BOB)
s8 = acceptFriendRequest(s8, BOB, ALICE)
s8 = blockUser(s8, BOB, ALICE)
assert(s8.status === 'blocked' && s8.blocked_by === BOB, 'blocking an accepted friend overrides status regardless of who originally requested')

// Sending a request into a blocked pair throws
threw = false
try {
  sendFriendRequest(s8, ALICE, BOB)
} catch {
  threw = true
}
assert(threw, 'sending a friend request while blocked throws')

// Unblock: only the blocking party may act, and it drops the row entirely
let s9 = blockUser(null, BOB, ALICE)
threw = false
try {
  unblockUser(s9, ALICE, BOB) // ALICE was blocked, not the blocker — cannot unblock
} catch {
  threw = true
}
assert(threw, 'unblocking as the blocked party (not the blocker) throws')

const s9Unblocked = unblockUser(s9, BOB, ALICE)
assert(s9Unblocked === null, 'unblocking as the blocking party removes the row')

threw = false
try {
  unblockUser(null, ALICE, BOB) // nothing to unblock
} catch {
  threw = true
}
assert(threw, 'unblocking a non-existent relationship throws')

// Re-friending after unblock requires a fresh request, starting clean
let s10 = sendFriendRequest(null, ALICE, BOB)
assert(s10.status === 'pending' && s10.requested_by === ALICE, 'sending a fresh request after unblock starts a normal pending flow')

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
