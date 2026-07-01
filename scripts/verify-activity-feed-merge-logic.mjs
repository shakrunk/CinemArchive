/**
 * Verifies the friend activity feed's merge/cap logic and the client-side
 * unseen-count logic. Mirrors supabase/migrations/20260701010000_friend_activity_feed.sql
 * (friend_activity_feed: union of title_added + viewing_logged, filtered to
 * accepted friends, ordered newest-first, capped) and the
 * refreshActivityUnseenCount action in src/store/useAppStore.ts, so both stay
 * honest without spinning up a real Postgres instance or a browser.
 *
 * Run: node scripts/verify-activity-feed-merge-logic.mjs
 */

// ─── Pure logic (mirrored from the migration + store) ───────────────────────

/**
 * Mirrors the `friend_activity_feed()` SQL function: union both event
 * sources, drop anything from a non-friend (the is_friend(...) predicate on
 * each branch), sort newest-first, cap to `limit`.
 *
 * @param {object[]} titleAddedEvents
 * @param {object[]} viewingEvents
 * @param {(friendUserId: string) => boolean} isFriend
 * @param {number} limit
 */
function mergeActivityFeed(titleAddedEvents, viewingEvents, isFriend, limit = 100) {
  const merged = [
    ...titleAddedEvents.map((e) => ({ ...e, event_type: 'title_added' })),
    ...viewingEvents.map((e) => ({ ...e, event_type: 'viewing_logged' })),
  ].filter((e) => isFriend(e.friend_user_id))

  merged.sort((a, b) => new Date(b.event_at).getTime() - new Date(a.event_at).getTime())
  return merged.slice(0, limit)
}

/**
 * Mirrors refreshActivityUnseenCount: null lastSeenAt means nothing has ever
 * been seen (everything counts); otherwise only events strictly newer than
 * the watermark count, matching markActivityFeedSeen's `>` comparison.
 *
 * @param {{ event_at: string }[]} feed
 * @param {string | null} lastSeenAt
 */
function countUnseen(feed, lastSeenAt) {
  const lastSeenMs = lastSeenAt ? new Date(lastSeenAt).getTime() : 0
  return feed.filter((e) => new Date(e.event_at).getTime() > lastSeenMs).length
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
const CAROL = 'c3333333-3333-3333-3333-333333333333' // not a friend

const isFriendOfCaller = (friendUserId) => friendUserId === ALICE || friendUserId === BOB

console.log('Friend activity feed merge logic check:')

// Merges both event kinds, newest first
const titleAdds = [
  { friend_user_id: ALICE, title: 'Dune', event_at: '2026-06-01T00:00:00Z' },
  { friend_user_id: BOB, title: 'Arrival', event_at: '2026-06-03T00:00:00Z' },
]
const viewings = [
  { friend_user_id: ALICE, title: 'Sicario', event_at: '2026-06-02T00:00:00Z' },
]
let feed = mergeActivityFeed(titleAdds, viewings, isFriendOfCaller)
assert(feed.length === 3, 'merges title-added and viewing events into one feed')
assert(
  feed.map((e) => e.title).join(',') === 'Arrival,Sicario,Dune',
  'feed is ordered newest-first across both event kinds'
)

// Events from a non-friend are excluded even if present in the raw rows
// (defense in depth — the SQL predicate should already exclude these)
const withNonFriend = [
  ...titleAdds,
  { friend_user_id: CAROL, title: 'Not a friend', event_at: '2026-06-05T00:00:00Z' },
]
feed = mergeActivityFeed(withNonFriend, viewings, isFriendOfCaller)
assert(
  feed.every((e) => e.title !== 'Not a friend'),
  'events from a non-friend are excluded from the merged feed'
)

// Cap keeps only the newest `limit` events
const manyAdds = Array.from({ length: 150 }, (_, i) => ({
  friend_user_id: ALICE,
  title: `Title ${i}`,
  event_at: new Date(2026, 0, 1 + i).toISOString(),
}))
feed = mergeActivityFeed(manyAdds, [], isFriendOfCaller, 100)
assert(feed.length === 100, 'the feed is capped to the limit')
assert(feed[0].title === 'Title 149', 'capping keeps the newest events, not the oldest')

// Unseen count: null watermark means everything is unseen
const smallFeed = [
  { event_at: '2026-06-01T00:00:00Z' },
  { event_at: '2026-06-02T00:00:00Z' },
]
assert(countUnseen(smallFeed, null) === 2, 'a null last-seen watermark treats every event as unseen')

// Unseen count: only events strictly newer than the watermark count
assert(countUnseen(smallFeed, '2026-06-01T00:00:00Z') === 1, 'only events after the watermark count as unseen')

// Unseen count: an event exactly at the watermark does not count (strict >)
assert(countUnseen(smallFeed, '2026-06-02T00:00:00Z') === 0, 'an event exactly at the watermark is not counted as unseen')

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
