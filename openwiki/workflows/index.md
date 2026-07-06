# Workflows

This section traces major user flows through the codebase, showing how components, store, and database interact.

---

## 1. Search & Add a Title

**Goal**: User searches for a movie/show on TMDB and adds it to their library.

### Flow

```
1. User clicks "Add Title" (or ⌘K → "Add Title" command)
   → openAddTitle() in store
   → AddTitleWorkflow modal opens

2. User types query (e.g., "Oppenheimer")
   → OnChange calls searchMedia(query)
   → Calls /functions/v1/media-proxy with action: 'search_tmdb'
   → Edge Function fetches TMDB API, caches, returns results
   → Results displayed in dropdown

3. User clicks result
   → Selected title populated in form
   → Metadata fields populated (year, genres, runtime, synopsis, poster)

4. User optionally customizes:
   - Status (watched, watchlist, watching, dropped)
   - Rating (0–5 stars)
   - Notes, tags
   - For TV: which seasons to add

5. User clicks "Add to Library"
   → insertTitleToDb(title) called
   → DB insert into `titles` table (+ `seasons` if TV)
   → Zustand updates optimistically: `set(s => ({ titles: [...s.titles, newTitle] }))`
   → Modal closes, Library view updated

6. Error handling:
   - If DB insert fails, error notification shown with retry button
   - On retry, insertTitleToDb called again
```

### Key Files

| File | What It Does |
|------|--------------|
| `/src/components/AddTitleWorkflow.tsx` | Search UI + form |
| `/src/lib/media.ts` | `searchMedia()` → Edge Function wrapper |
| `/src/store/useAppStore.ts` | `insertTitle()` action |
| `/src/lib/db.ts` | `insertTitleToDb()` → Supabase insert |
| `/supabase/functions/media-proxy/index.ts` | TMDB search endpoint |

### Optimization Notes

- Search results cached in Edge Function (24-hour TTL)
- TMDB metadata (posters, ratings) fetched on-demand when detail drawer opens
- Episode data not fetched until user expands a season

---

## 2. Log an Episode Watch

**Goal**: User marks an episode as watched and optionally rates/reviews it.

### Flow

```
1. User navigates to Library, finds a TV show, clicks to open detail drawer
   → URL updates: ?view=library&title=<uuid>
   → TitleDetailDrawer opens

2. User clicks on season to expand
   → Reveals all episodes in that season

3. User clicks "Mark watched" on an episode
   → Opens episode logging modal (date + optional notes)

4. User confirms
   → logEpisode(titleId, season, episode, watchedAt, rating) called
   → Zustand updates immediately:
     - Episode.watchEvents += new event
     - Season.episodesWatched incremented
     - Series.status computed (if all episodes watched → "watched")
   → Set shows new state to user

5. Async DB write fires:
   → logEpisodeToDb() calls supabase.from('episode_watch_events').insert(...)
   → Also updates `seasons.episodes_watched` count if needed

6. If user rates the episode while logging:
   → Also insert into `episode_ratings` table
   → Series rating rollup updates

7. Error handling:
   - If DB write fails, error notification with retry
   - Optimistic state remains visible; retry will sync with DB
```

### Rollup Computation

After any episode change, rollups are recomputed:

```
Episode.watchEvents → Season.episodesWatched
Episode.ratings → Season.avgRating → Series.avgRating
Episode.reviews → Series.hasReviews
```

Source: `/src/store/episodeUtils.ts` (rollup helpers).

### Key Files

| File | What It Does |
|------|--------------|
| `/src/components/TitleDetailDrawer.tsx` | Episode expand + logging UI |
| `/src/store/useAppStore.ts` | `logEpisode()` action (optimistic) |
| `/src/lib/db.ts` | `logEpisodeToDb()` (DB write) |
| `/src/store/episodeUtils.ts` | Rollup computation |
| `/schema.sql` | `episode_watch_events`, `episode_ratings` tables |

### Edge Cases

- **Null date**: User can mark watched without a date (e.g., "watched before joining platform")
- **Duplicate watch**: Same episode can have multiple watch events (rewatches)
- **Rating without watching**: User can rate an episode without a watch event (e.g., "I watched it years ago but rate it now")
- **Review without rating**: User can review without rating

---

## 3. Filter & Search Library

**Goal**: User refines their library view using filters and search.

### Flow

```
1. User opens Library view
   → filteredTitles derived from full titles + current filters
   → Poster grid or list rendered

2. User types in search box
   → setFilter({ search: query }) called
   → Store updates: filters.search = query
   → React re-renders
   → filteredTitles re-derived (memoized, only updates if filters change)
   → Grid/list updates with matching titles

3. User clicks filter button (e.g., "Genre")
   → Modal opens with filter options
   → User selects genres
   → setFilter({ genres: [...] }) called
   → Store updates, filteredTitles re-derived
   → Grid/list updates

4. User adjusts sort
   → setSortField('rating') called
   → filteredTitles re-sorted
   → Grid/list updates

5. No DB queries during any filter/sort change
   → All done client-side
   → Fast response, instant feedback
```

### Key Files

| File | What It Does |
|------|--------------|
| `/src/views/Library.tsx` | Library view, poster grid/list rendering |
| `/src/store/useAppStore.ts` | `LibraryFilters`, `filteredTitles` derivation, `setFilter()` |
| `src/store/episodeUtils.ts` | Compute episode counts for filtering |

### Performance Notes

- `filteredTitles` is memoized with dependency array `[titles, filters]`
- Only recomputed when titles or filters change
- Large libraries (10K+ titles) may need grid virtualization (not yet implemented)

---

## 4. View The Ledger

**Goal**: User opens stats dashboard to see viewing habits.

### Flow

```
1. User clicks "Ledger" in nav
   → currentView set to 'ledger'
   → URL updates: ?view=ledger

2. Ledger view mounts
   → Zustand fetches cached ledger stats
   → If not cached, computeLedgerStats(titles) called
   → All aggregations computed (counts, avg rating, viewing timeline, etc.)
   → Cached in store.ledger.stats

3. User's stored layout preferences loaded
   → store.ledger.widgets: ordered list of widget instances
   → Each widget has a panelId (e.g., 'rating-distribution') + optional settings

4. Ledger view renders panels in order
   → Each panel component receives its data from ledgerStats
   → Custom CSS visualizations rendered (bars, timelines, grids)

5. User clicks a panel (e.g., "Top Genres")
   → May drill down to filtered Library view
   → e.g., RatingDistribution → user clicks bar → filters Library by that rating

6. User customizes layout
   → Clicks layout editor icon
   → Can reorder (drag), resize, hide panels
   → Changes saved to store.ledger.widgets
   → Async: saveLedgerLayout(widgets) → Supabase user_prefs.ledger_prefs
   → On page reload, user's layout restored
```

### Key Files

| File | What It Does |
|------|--------------|
| `/src/views/Ledger.tsx` | Main ledger router |
| `/src/views/ledger/` | Panel components (RatingDistribution, TheRun, etc.) |
| `/src/store/ledgerDerive.ts` | Stat computation + per-panel derivations |
| `/src/lib/ledgerPanels.ts` | Panel type defs + layout helpers |
| `/src/store/useAppStore.ts` | ledger slice |

### Customization

Each panel can have settings (e.g., "show only movies" for some panels). Settings stored per-widget in `ledger.widgetSettings`.

---

## 5. Share Library via Link

**Goal**: User creates a shareable link, sends it to a friend.

### Flow

```
1. User opens Profile → Sharing tab
   → Existing links listed

2. User clicks "Create link"
   → Modal: set expiration (optional), label, scopes (optional)

3. User clicks "Generate"
   → createSharedAccessKey() called
   → Supabase insert into `shared_access_keys` table
   → Random 32-byte hex token generated (DB constraint UNIQUE on token)
   → Optional: insert into `share_scopes` if user set filters
   → Link generated: https://...?share=<token>
   → Displayed in modal, user can copy

4. User shares link with friend
   → Friend clicks link

5. Friend's browser receives URL with ?share=<token>
   → App initializes, calls set_shared_token(token) RPC
   → Sets Postgres session variable: app.shared_token = token
   → All subsequent DB queries include RLS check:
     ```sql
     using (... or is_valid_shared_token(get_current_setting('app.shared_token'), user_id) ...)
     ```
   → Friend can browse shared library (read-only)

6. User later revokes link
   → Clicks "Deactivate" next to link in Profile → Sharing
   → Update: is_active = false
   → Shared token no longer validates (RLS policy checks is_active)
   → Friend's browser tries to browse → gets 403 (no rows)
```

### Scopes (Optional)

User can restrict what's visible by genre or status:
- Insert `share_scopes` row with `shared_key_id` + `allowed_genres` / `allowed_statuses`
- RLS policies on `titles` check these constraints before returning rows
- Absence = unrestricted (opt-in narrowing only)

### Key Files

| File | What It Does |
|------|--------------|
| `/src/views/Profile.tsx` | Sharing tab UI + link management |
| `/src/lib/db.ts` | `createSharedAccessKey()`, `fetchShareScopes()` |
| `/src/lib/auth.ts` | `set_shared_token()` RPC call |
| `/schema.sql` | `shared_access_keys`, `share_scopes` tables + RLS |
| `/supabase/functions/media-proxy/index.ts` | Proxies shared library queries (if needed) |

---

## 6. Add a Friend & Send Recommendation

**Goal**: User adds a friend, sends them a recommendation.

### Flow (Add Friend)

```
1. User opens Friends view
   → Search box for "Find by email"

2. User types friend's email, clicks "Send request"
   → createFriendshipRequest(friendEmail) called
   → Supabase insert into `friendships`:
     { requester_id: currentUser, recipient_id: foundUser, state: 'pending' }
   → Friend receives notification: "X added you as a friend"

3. Friend logs in, sees notification
   → Clicks notification or opens Friends view
   → Sees pending request from user
   → Clicks "Accept" or "Decline"
   → If accept: update friendships.state = 'friend'
   → Both users now see each other as friends
```

### Flow (Send Recommendation)

```
1. User opens a title detail drawer
   → Clicks "Recommend" button

2. Modal opens: select friend(s) + optional note
   → User selects friend(s), types note
   → User clicks "Send"

3. For each friend:
   → Insert into `recommendations`:
     { title_id, from_user_id: currentUser, to_user_id: friend, personal_note, status: 'pending' }
   → Friend receives notification: "X recommended Y (note: ...)"

4. Friend logs in
   → Opens Profile → Recommendations
   → Sees pending recommendations
   → Can accept (adds to their library) or dismiss

5. If accept:
   → insertTitleToDb(title) called
   → Title added to friend's library
   → Recommendation marked as 'accepted'

6. If dismiss:
   → Recommendation marked as 'dismissed'
   → No longer shows in inbox
```

### Key Files

| File | What It Does |
|------|--------------|
| `/src/views/Friends.tsx` | Friend list + activity feed |
| `/src/components/SendRecommendationPanel.tsx` | Recommendation send UI |
| `/src/views/Profile.tsx` | Recommendations inbox |
| `/src/lib/db.ts` | `createFriendshipRequest()`, `sendRecommendation()`, `acceptRecommendation()` |
| `/schema.sql` | `friendships`, `recommendations` tables |

---

## 7. View Friend Activity Feed

**Goal**: User sees what friends are watching/rating/commenting.

### Flow

```
1. User opens Friends view
   → Loads friend list (users where friendships.state = 'friend')
   → Loads activity feed via fetchFriendActivityFeed()

2. Activity feed query:
   ```sql
   SELECT * FROM friend_activity_feed
   WHERE initiator_id IN (user's friends)
   ORDER BY created_at DESC
   LIMIT 50
   ```
   → Returns denormalized log: episodes watched, ratings, comments, reactions

3. Displayed as paginated feed
   → User can scroll through
   → Each entry shows: "Friend X watched Y", "Friend X rated Z 5 stars", etc.
   → Timestamps relative (e.g., "2 hours ago")

4. User clicks an entry
   → If "watched episode": navigate to title detail drawer
   → If "rated": navigate to title
   → If "commented": drill into comments panel for that title
```

### Denormalization

Activity feed is denormalized (not computed on-the-fly) for performance:
- Triggers on `episodes`, `episode_ratings`, `episode_reviews` inserts
- Trigger inserts row into `friend_activity_feed`
- App queries this log directly (no joins to derive activity)

### Key Files

| File | What It Does |
|------|--------------|
| `/src/views/Friends.tsx` | Activity feed rendering |
| `/src/lib/db.ts` | `fetchFriendActivityFeed()`, pagination |
| `/schema.sql` | `friend_activity_feed` table + triggers |

---

## 8. Notification Flow

**Goal**: User receives notifications for various events (shares used, friend requests, comments, etc.).

### Flow

```
1. Event occurs in DB (e.g., someone redeems an invite)
   → Trigger fires on `invite_codes` table
   → Inserts row into `notifications`:
     { recipient_id, type: 'invite_redeemed', payload: {...} }

2. User's app polls fetchUnreadNotificationCount()
   → Called every 5-10 seconds
   → Returns count of notifications where read_at IS NULL
   → Bell icon shows badge with count

3. User clicks bell icon
   → NotificationCenter modal opens
   → Calls fetchNotifications() (paginated)
   → Loads notifications with type, payload, timestamps
   → Displayed as list

4. User clicks notification
   → markNotificationRead(notificationId) called
   → Updates read_at = now()
   → May trigger drill-down:
     - "Comment on title X" → opens title detail drawer
     - "Friend request" → opens Friends view
     - "Recommendation" → opens Profile → Recommendations

5. User dismisses notification
   → deleteNotification(notificationId) called
   → Soft delete (or hard delete) from DB
   → Removed from inbox
```

### Notification Types

- `invite_redeemed` — Someone used an invite code you created
- `share_link_used` — Someone opened a share link (throttled to 1/hour per key)
- `friend_request` — Someone added you as friend
- `comment` — Friend commented on a title you own
- `reaction` — Friend reacted (emoji) to a title you own
- `recommendation` — Friend sent you a recommendation
- `activity_update` — Friend activity feed updates

### Key Files

| File | What It Does |
|------|--------------|
| `/src/components/NotificationCenter.tsx` | Notification inbox UI |
| `/src/store/useAppStore.ts` | Notifications slice, polling logic |
| `/src/lib/db.ts` | `fetchNotifications()`, `markNotificationRead()`, `deleteNotification()` |
| `/schema.sql` | `notifications` table + triggers for each event type |

---

## 9. Update Library Metadata (Refresh)

**Goal**: User refreshes metadata for a title (new poster, updated runtime, etc.) from TMDB.

### Flow

```
1. User opens title detail drawer
   → Clicks "Refresh metadata" button

2. RefreshMetadataModal opens
   → Shows current metadata, allows edits

3. User clicks "Refresh from TMDB"
   → Fetches latest metadata from TMDB via Edge Function
   → Displays in modal

4. User can:
   - Keep current values (for some fields)
   - Accept refreshed values
   - Manually edit

5. User clicks "Save"
   → updateTitleInDb() called
   → Supabase update to `titles` table
   → Zustand updates optimistically
   → Recompute Ledger stats (if rating or other agg-relevant field changed)
```

### Key Files

| File | What It Does |
|------|--------------|
| `/src/components/RefreshMetadataModal.tsx` | Modal UI + TMDB fetch |
| `/src/store/useAppStore.ts` | `updateTitle()` action |
| `/src/lib/db.ts` | `updateTitleInDb()` |
| `/src/lib/media.ts` | `getMediaDetails()` → Edge Function |

---

## Common Patterns

### Optimistic Updates

Most mutations follow this pattern:

```typescript
// 1. Update Zustand immediately
set(state => ({
  titles: state.titles.map(t => t.id === id ? { ...t, ...changes } : t)
}))

// 2. Fire async DB write
writeToDb(id, changes).catch(err => {
  // 3. On error, show notification with retry
  addNotification({ 
    kind: 'error', 
    message: 'Failed to update title',
    retry: () => writeToDb(id, changes)
  })
})
```

### Memoized Derivations

Heavy computations are memoized to avoid recomputation:

```typescript
const filteredTitles = useMemo(() => {
  return applyFiltersAndSort(titles, filters)
}, [titles, filters]) // Only recompute if these change
```

### URL-Based Navigation

View state lives in the URL. Synchronization via `/src/lib/useNavigationSync.ts`:

```typescript
// When user clicks a title:
set(state => ({ openDetailTitle: titleId }))
// Trigger URL update: ?view=library&title=<titleId>

// When user hits back button:
// URL changes → popstate event → updateStore({ openDetailTitle: null })
```

---

## Debugging Workflows

To understand a workflow:

1. Find the entry point (user action) in a component
2. Trace the action dispatch in the store (`useAppStore.ts`)
3. Follow the async DB call in `/src/lib/db.ts`
4. Check the RLS policy in `/schema.sql` to understand data access
5. Review the relevant migration in `/supabase/migrations/` for context

Use the [Source Reference](../source-reference.md) to locate component/store files quickly.
