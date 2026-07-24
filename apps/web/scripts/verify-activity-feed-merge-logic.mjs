/**
 * Verifies the friend activity feed's merge/pagination logic. Mirrors
 * supabase/migrations/20260706080000_activity_feed_pagination.sql
 * (friend_activity_feed: union of title_added + viewing_logged +
 * comment_added + reaction_added, filtered to what the caller can view,
 * ordered newest-first, keyset-paginated by event_at), so it stays honest
 * without spinning up a real Postgres instance.
 *
 * The client-side "unseen count" watermark this script used to also verify
 * (countUnseen/markActivityFeedSeen) was retired in favor of the persistent,
 * server-side notifications table (see verify-share-scope-logic.mjs's
 * sibling checklist and 20260706090000_notifications.sql) — there is no
 * longer any client-side merge logic for that concept to mirror.
 *
 * Run: node scripts/verify-activity-feed-merge-logic.mjs
 */

// ─── Pure logic (mirrored from the migration) ────────────────────────────────

/**
 * Mirrors the `friend_activity_feed()` SQL function: union all four event
 * sources, drop anything the caller can't view (the can_view_title(...)
 * predicate on each branch — here reduced to a simple canView callback),
 * sort newest-first, then apply keyset pagination (only events strictly
 * before `before`, capped to `limit`).
 *
 * @param {object[]} titleAddedEvents
 * @param {object[]} viewingEvents
 * @param {object[]} commentEvents
 * @param {object[]} reactionEvents
 * @param {(friendUserId: string) => boolean} canView
 * @param {string|null} before
 * @param {number} limit
 */
function mergeActivityFeed(titleAddedEvents, viewingEvents, commentEvents, reactionEvents, canView, before = null, limit = 30) {
  const merged = [
    ...titleAddedEvents.map((e) => ({ ...e, event_type: 'title_added' })),
    ...viewingEvents.map((e) => ({ ...e, event_type: 'viewing_logged' })),
    ...commentEvents.map((e) => ({ ...e, event_type: 'comment_added' })),
    ...reactionEvents.map((e) => ({ ...e, event_type: 'reaction_added' })),
  ].filter((e) => canView(e.friend_user_id))

  merged.sort((a, b) => new Date(b.event_at).getTime() - new Date(a.event_at).getTime())

  const cursored = before ? merged.filter((e) => new Date(e.event_at).getTime() < new Date(before).getTime()) : merged
  return cursored.slice(0, Math.min(limit, 50))
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
const CAROL = 'c3333333-3333-3333-3333-333333333333' // out of view (not a friend, or scoped out)

const canViewOfCaller = (friendUserId) => friendUserId === ALICE || friendUserId === BOB

console.log('Friend activity feed merge/pagination logic check:')

// Merges all four event kinds, newest first
const titleAdds = [
  { friend_user_id: ALICE, title: 'Dune', event_at: '2026-06-01T00:00:00Z' },
  { friend_user_id: BOB, title: 'Arrival', event_at: '2026-06-03T00:00:00Z' },
]
const viewings = [{ friend_user_id: ALICE, title: 'Sicario', event_at: '2026-06-02T00:00:00Z' }]
const comments = [{ friend_user_id: BOB, title: 'Dune', event_at: '2026-06-04T00:00:00Z' }]
const reactions = [{ friend_user_id: ALICE, title: 'Arrival', event_at: '2026-06-05T00:00:00Z' }]

let feed = mergeActivityFeed(titleAdds, viewings, comments, reactions, canViewOfCaller)
assert(feed.length === 5, 'merges all four event kinds into one feed')
assert(
  feed.map((e) => e.title).join(',') === 'Arrival,Dune,Arrival,Sicario,Dune',
  'feed is ordered newest-first across every event kind'
)

// Events the caller can't view are excluded even if present in the raw rows
// (defense in depth — the SQL predicate should already exclude these, and
// now also accounts for share_scopes narrowing, not just is_friend)
const withUnviewable = [...titleAdds, { friend_user_id: CAROL, title: 'Not visible', event_at: '2026-06-06T00:00:00Z' }]
feed = mergeActivityFeed(withUnviewable, viewings, comments, reactions, canViewOfCaller)
assert(
  feed.every((e) => e.title !== 'Not visible'),
  'events the caller cannot view (non-friend or scoped out) are excluded from the merged feed'
)

// Keyset pagination: no cursor returns the newest page
feed = mergeActivityFeed(titleAdds, viewings, comments, reactions, canViewOfCaller, null, 2)
assert(feed.map((e) => e.title).join(',') === 'Arrival,Dune', 'first page (no cursor) returns the newest events up to the limit')

// Keyset pagination: passing the last event's timestamp as the cursor
// returns the next page, never repeating or skipping an event
const secondPage = mergeActivityFeed(titleAdds, viewings, comments, reactions, canViewOfCaller, feed[feed.length - 1].event_at, 2)
assert(
  secondPage.map((e) => e.title).join(',') === 'Arrival,Sicario',
  'passing the previous page\'s last event_at as the cursor returns the next page'
)

// Limit is capped at 50 regardless of what's requested
const manyAdds = Array.from({ length: 80 }, (_, i) => ({
  friend_user_id: ALICE,
  title: `Title ${i}`,
  event_at: new Date(2026, 0, 1 + i).toISOString(),
}))
feed = mergeActivityFeed(manyAdds, [], [], [], canViewOfCaller, null, 999)
assert(feed.length === 50, 'the page size is capped at 50 even if a larger limit is requested')
assert(feed[0].title === 'Title 79', 'capping keeps the newest events, not the oldest')

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
