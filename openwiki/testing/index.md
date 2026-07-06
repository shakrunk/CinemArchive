# Testing & Verification

CinemArchive uses **verification scripts** to validate business logic and state machines. There are no unit/integration tests; instead, logic is verified via Node.js scripts that test real scenarios.

---

## Verification Scripts

All verification scripts live in `/scripts/` and are **Node.js** files (`.mjs` for ES modules).

### Running Verification Scripts

```bash
node scripts/verify-episode-logic.mjs
node scripts/verify-navigation-logic.mjs
node scripts/verify-friendship-state-machine.mjs
node scripts/verify-share-scope-logic.mjs
node scripts/verify-recommendation-inbox-logic.mjs
node scripts/verify-activity-feed-merge-logic.mjs
```

Each script:
1. Sets up test data (mock titles, episodes, friendships, etc.)
2. Runs scenario checks (e.g., "episode watch → season progress updates")
3. Logs results: `✓ scenario` or `✗ failed` with details
4. Exits with code 0 (all passed) or 1 (any failed)

---

## Script Details

### verify-episode-logic.mjs

**Purpose**: Validate episode watch events, ratings, reviews, and rollup computation.

**Scenarios Tested**:
- Single episode watch → season progress incremented
- Multiple watch events on same episode (rewatches) → progress doesn't double-count
- Episode rating recorded independently of watch date
- Episode review recorded independently of rating
- Series status computed from season progress (all watched → "watched", partial → "watching")
- Series rating averaged from episode ratings
- Season/series stats roll up correctly after episode changes

**Key Functions Tested** (from `/src/store/episodeUtils.ts`):
- `nextUnwatchedEpisode()` — finds next unwatched episode in series
- Rollup computation from episodes to seasons to series

### verify-navigation-logic.mjs

**Purpose**: Validate URL ↔ AppView state parsing and serialization.

**Scenarios Tested**:
- URL parse: `?view=library&title=abc123&add=1` → correct NavState
- URL serialize: NavState → correct query string
- Deep links: `?view=library&title=...` → detail drawer opens on load
- Back button: history.back() → popstate event → correct view
- Preserved params: `share` and `friend` params carried through navigation
- Fallback view when URL is invalid

**Key Functions Tested** (from `/src/lib/navigation.ts`):
- `parseNav()` — parse query string to NavState
- `serializeNav()` — serialize NavState to query string
- `preservedParams()` — extract preserved params (share, friend)

### verify-friendship-state-machine.mjs

**Purpose**: Validate friendship state transitions and access rules.

**Scenarios Tested**:
- Initial request: requester → recipient, state = 'pending'
- Accept request: state = 'pending' → 'friend'
- Reject request: pending request deleted (or state = 'rejected')
- Block user: state = 'friend' → 'blocked'
- Unblock user: state = 'blocked' → 'friend' (or 'unblocked')
- Access rules: friends can read each other's libraries (if not scoped)
- No access: non-friends cannot read library

**Key Logic** (from `/src/lib/db.ts`):
- `createFriendshipRequest()`
- `acceptFriendshipRequest()`
- `blockUser()`
- `unblockUser()`

**Database** (from `/schema.sql`):
- `friendships` table state enum
- RLS policy: friends can read each other's libraries

### verify-share-scope-logic.mjs

**Purpose**: Validate share scopes (per-link and per-friend filtering).

**Scenarios Tested**:
- Unrestricted link: absence of share_scopes row = all titles visible
- Scoped by genre: `allowed_genres = ['Drama', 'Sci-Fi']` → only those genres visible
- Scoped by status: `allowed_statuses = ['watched']` → only watched titles visible
- Combined scoping: both genres and statuses restricted
- Friend scoping: different restrictions per friend
- Shared-link scoping: different restrictions per link

**Key Logic**:
- Absence of row = unrestricted (opt-in narrowing only)
- RLS policy applies scopes via JSONB filters on `titles`

**Database**:
- `share_scopes` table (one row per link/friend)
- One-of-two constraint: `(shared_key_id XOR friend_user_id)`

### verify-recommendation-inbox-logic.mjs

**Purpose**: Validate recommendation lifecycle and inbox state.

**Scenarios Tested**:
- Send recommendation: creates row with status = 'pending'
- Recipient sees pending recommendations in inbox
- Accept recommendation: title added to recipient's library, status = 'accepted'
- Dismiss recommendation: status = 'dismissed', no longer in inbox
- Duplicate check: sending same title twice to same friend

**Key Logic** (from `/src/lib/db.ts`):
- `sendRecommendation()`
- `acceptRecommendation()`
- `dismissRecommendation()`

### verify-activity-feed-merge-logic.mjs

**Purpose**: Validate activity feed denormalization and merging of episode watch/rate/review events.

**Scenarios Tested**:
- Episode watch event → activity feed entry created
- Episode rating → activity feed entry created
- Episode review → activity feed entry created
- Multiple events on same episode → separate feed entries (or merged?)
- Pagination: fetch first 50, then next 50
- Filtering: activity from friends only

**Key Logic**:
- Triggers populate `friend_activity_feed` table
- App queries this log directly (no joins to derive)

---

## Test Data

Each script uses mock data that mirrors real DB structure:

```javascript
// Example from verify-episode-logic.mjs
const mockTitle = {
  id: 'abc123',
  type: 'tv',
  title: 'Breaking Bad',
  seasons: [
    {
      seasonNumber: 1,
      episodes: [
        {
          episodeNumber: 1,
          watchEvents: [],
          ratings: [],
          reviews: []
        }
      ]
    }
  ]
}

// Scenario: user watches episode
mockTitle.seasons[0].episodes[0].watchEvents.push({ id: '...', watchedAt: '2025-01-01' })
// Check: season.episodesWatched incremented
// Check: series.status computed correctly
```

---

## Adding a New Verification Script

If you add new business logic:

1. Create `/scripts/verify-<feature>.mjs`
2. Import mock data from `/src/store/mockData.ts` (types) or define inline
3. Set up test scenarios:
   ```javascript
   console.log('Scenario: user logs episode watch')
   const episode = { ... }
   // Perform action
   // Check result
   if (/* condition */) {
     console.log('✓ Episode progress incremented')
   } else {
     console.log('✗ Episode progress not incremented')
     process.exit(1)
   }
   ```
4. Run and iterate until all scenarios pass
5. Commit to `/scripts/`

---

## Linting & Type Checking

The app uses ESLint for code quality:

```bash
npm run lint
```

Checks for:
- Unused variables
- Missing dependencies in React hooks
- Type errors (TypeScript)
- Code style (via Prettier)

### Configuration

- `.eslintrc.js` — ESLint rules
- `.prettierrc` — Formatter config (no quotes, 2 spaces, etc.)
- `tsconfig.json` — TypeScript config

---

## Manual Testing Checklist

Before deploying:

- [ ] Library view: add title, filter, sort, search
- [ ] Episode logging: expand season, mark episode watched, rate, review
- [ ] Ledger view: see stats, customize layout, click panels to drill down
- [ ] Sharing: create link, share, view as non-authenticated visitor
- [ ] Friends: add friend, send recommendation, view activity feed
- [ ] Notifications: trigger event (e.g., share link use), see notification in center
- [ ] Auth: sign up with invite, sign in with passkey, sign out
- [ ] Themes: switch between dark, light, noir, matrix
- [ ] Responsive: test on mobile (bottom nav, modals as bottom sheets)
- [ ] Deep links: share a title URL, refresh, go back/forward
- [ ] Offline: open app in offline mode, see cached data

---

## Performance Benchmarks

Two benchmark scripts measure library size impact:

### benchmark.js

Measures import/render time and store hydration for various library sizes.

```bash
node benchmark.js
```

Output:
```
1000 titles:
  - Import time: 50ms
  - Render time: 120ms
  - Store hydration: 30ms

10000 titles:
  - Import time: 450ms
  - Render time: 980ms
  - Store hydration: 280ms
```

### benchmark-seasons-upsert.js

Measures Supabase upsert performance for season data.

```bash
node benchmark-seasons-upsert.js
```

---

## Debugging Tips

### Check Store State

Open browser DevTools → Console:

```javascript
// Zustand store exposed globally during dev
useAppStore.getState()
// Returns full store state (library, ledger, ui slices)

// Subscribe to changes
useAppStore.subscribe(state => console.log('Store changed:', state))
```

### Check Database Queries

Supabase dashboard → SQL Editor → Run queries:

```sql
-- See user's titles
SELECT * FROM titles WHERE user_id = '<your-id>';

-- See episode watch events
SELECT * FROM episode_watch_events WHERE user_id = '<your-id>';

-- Check RLS: should only see your own data
SELECT * FROM shared_access_keys WHERE user_id = '<your-id>';
```

### Log RLS Policy Matches

In Supabase SQL Editor, run:

```sql
-- Manually verify RLS policy
SELECT id, user_id FROM titles
WHERE user_id = '<your-id>'
  OR is_valid_shared_token('<token>', user_id)
ORDER BY added_at DESC LIMIT 10;
```

### Profile Network Requests

- Open DevTools → Network tab
- Search for `media-proxy` calls (TMDB/OMDb requests)
- Check Edge Function logs: Supabase dashboard → Edge Functions → media-proxy → Logs

---

## CI/CD Testing

GitHub Actions runs the following on every push:

1. **Type check**: `tsc -b` (TypeScript compiler)
2. **Lint**: `eslint .` (ESLint)
3. **Build**: `vite build` (production build)

Failures block merge to `main`.

### Viewing CI Results

- Push a commit → GitHub Actions workflow starts
- Click the workflow run → see logs
- Red `✗` = failure, Green `✓` = success

---

## Common Issues & Solutions

### State Not Updating

- Check that the action dispatches correctly: `useAppStore.getState().updateTitle(...)`
- Verify Zustand selector is re-evaluated: use React DevTools Profiler
- Ensure you're reading the right slice: `useAppStore(s => s.library)` vs. `useAppStore(s => s.ledger)`

### UI Doesn't Reflect DB Change

- Optimistic update may have succeeded, but DB write failed (check notifications)
- Reload page to force refetch from DB
- Check RLS policy: verify user can read the updated row

### Share Link Not Working

- Verify `shared_access_keys.is_active = true` in DB
- Check `expires_at` is null or in future
- Ensure token matches exactly (case-sensitive)
- Try incognito window (no auth cookies)

### Notification Not Appearing

- Check `notifications` table: `SELECT * FROM notifications WHERE recipient_id = '<your-id>' ORDER BY created_at DESC LIMIT 5;`
- Verify trigger fired: check event that should have created notification
- Refresh page to force poll of unread count

---

## Questions?

- Check specific verification script source for detailed logic
- Review [Architecture](../architecture/overview.md) for data model
- Read [Workflows](../workflows/index.md) for flow diagrams
