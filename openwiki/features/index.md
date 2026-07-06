# Features & Domains

CinemArchive is organized around six major feature domains. This section explains each one: what it does, why it matters, how it's built, and where to look for code.

---

## 1. Library Management

**What it does**: Track movies and TV shows you've watched, are watching, or want to watch. Filter, sort, search, and organize your collection.

### Key Concepts

- **Status**: Each title has a status (watched, watchlist, watching, dropped). Determines what tabs/sections appear.
- **Client-side filtering**: All filtering happens in the Zustand store. No DB queries per filter change.
- **Poster wall vs. list view**: Two rendering modes of the same filtered data; toggle with the view-mode button.
- **Search**: Client-side substring search across title, director, genre, studio names.
- **Filters**: Type (movie/TV), status, genres, tags, networks, decades, original language, minimum rating, director, studio, franchise grouping.

### How It Works

1. User navigates to Library view
2. App loads `titles` from Supabase into Zustand (`useAppStore`)
3. `filteredTitles` derives the result of applying all active filters/sorts
4. Library component renders either a poster grid or list (via `viewMode`)
5. User clicks a title → detail drawer opens (URL updates)

### Where to Look

| Component | Responsibility |
|-----------|-----------------|
| `/src/views/Library.tsx` | Main view: render poster grid or list |
| `/src/store/useAppStore.ts` | Store: library slice, filteredTitles derivation, CRUD actions |
| `/src/store/mockData.ts` | Title type definitions |
| `/schema.sql` | `titles`, `seasons`, `episodes` tables + RLS |
| `/src/lib/db.ts` | `fetchUserLibrary()`, `insertTitleToDb()`, `updateTitleInDb()`, `deleteTitleFromDb()` |

### Common Tasks

**Add a new filter**: 
1. Add field to `LibraryFilters` in useAppStore.ts
2. Update the filter UI component (e.g., in ProfileModal or a sidebar)
3. Update `filteredTitles` derivation to apply the filter
4. Test in Library view

**Change sort order**:
- Update `sortField` and `sortDir` in the store
- `filteredTitles` derivation automatically re-sorts

---

## 2. Episode-Level Tracking

**What it does**: Track individual episodes of TV shows independently. Log watch events, rate episodes, and review them — all decoupled.

### Why This Matters

Unlike traditional trackers, CinemArchive lets you:
- Re-watch an episode without changing its rating
- Rate an episode without a watch date (e.g., "I watched it years ago but rate it 5 stars")
- Write a review without watching (e.g., "I've heard great things")
- See season and series progress roll up from episode data

### How It Works

**Tables** (three independent logs per episode):
- `episode_watch_events` — "Watched X on Y date"
- `episode_ratings` — "Episode X rated 5 stars" (timestamped when user recorded)
- `episode_reviews` — "Episode X review text" (independent timestamp)

**Derivations**:
- `Season.episodesWatched` — count of episodes with at least one watch event
- `Series.rating` — average of all episode ratings in the series
- `Series.status` — computed from season progress (all watched → "watched", some → "watching", none → "watchlist")

### Where to Look

| Component | Responsibility |
|-----------|-----------------|
| `/src/components/TitleDetailDrawer.tsx` | UI for episode logging, expanding seasons, viewing history |
| `/src/store/episodeUtils.ts` | Rollup computation: episodes → seasons → series |
| `/src/store/useAppStore.ts` | `logEpisode()` action + DB sync |
| `/schema.sql` | `episodes`, `episode_watch_events`, `episode_ratings`, `episode_reviews` tables |
| `/src/lib/db.ts` | `logEpisodeToDb()`, `deleteEpisodeWatchEventFromDb()` |
| `/scripts/verify-episode-logic.mjs` | Verification script (episode → season → series rollups) |

### Common Tasks

**Log an episode watch**:
1. Open title detail drawer
2. Expand season
3. Click "Mark watched" on episode
4. Confirm date (or leave blank for "before joining platform")
5. Zustand updates optimistically, `logEpisodeToDb()` fires async

**See episode history**:
- Click the episode number to expand viewing history
- Shows all watch events, ratings, and reviews for that episode

---

## 3. The Ledger (Stats Dashboard)

**What it does**: Visualize your watching habits with charts, timelines, genre breakdowns, and customizable widget layouts.

### Features

- **Counts**: Total movies, series, episodes, viewings
- **Rating distribution**: Histogram of how many titles you rated 1, 2, 3, 4, 5 stars
- **Viewing timeline**: Timeline of all viewings (movies + episodes rewatched)
- **Top genres** and **top directors/auteurs**: Ranked by count or viewing recency
- **Media breakdown**: Pie chart of movies vs. series vs. episodes
- **Franchise grouping**: Section movies into their TMDB collections
- **Streaks**: Consecutive days with viewing activity
- **Trajectory**: Smoothed trend of viewing frequency over time
- **Revivals**: Movies/shows watched multiple times with gap analysis
- **Time Warp**: Viewing distribution by decade/year
- **Progress**: Per-series episode completion progress
- **Watchlist**: Count of unwatched titles by type/status

### How It Works

1. User navigates to Ledger view
2. App computes `LedgerStats` from the library (once per library change)
3. Each stat is cached in Zustand `ledger` slice
4. User's `ledger.widgets` (layout prefs) determines which panels appear
5. Each panel renders its stat with a custom CSS visualization (no charting library)
6. User can click panels to drill down into the Library (e.g., "Top Genres" → filters library by that genre)

### Where to Look

| Component | Responsibility |
|-----------|-----------------|
| `/src/views/Ledger.tsx` | Main ledger view router |
| `/src/views/ledger/` | Panel components (RatingDistribution, TheRun, etc.) |
| `/src/store/ledgerDerive.ts` | Stat computation + per-widget derivations |
| `/src/store/ledgerStats.ts` | Aggregation helpers (counts, sums, averages) |
| `/src/lib/ledgerPanels.ts` | Panel type definitions + layout helpers |
| `/src/store/useAppStore.ts` | `ledger` slice (widgets, widgetSettings) |

### Custom CSS Visuals

No Recharts or other charting library. Instead, custom CSS classes and inline styles:
- Bars (e.g., rating distribution): `<div style={{ width: '30%' }}>`
- Timeline: CSS grid with positioned items
- Grid/heatmap: CSS grid or flex with aspect-ratio
- Animations: CSS transitions for drag-reorder (FLIP technique)

Source: `/src/views/ledger/panels/` (each panel has its own CSS in the same file or `App.css`).

### Common Tasks

**Add a new panel**:
1. Define panel type in `/src/lib/ledgerPanels.ts`
2. Create component in `/src/views/ledger/panels/`
3. Add stat computation to `/src/store/ledgerDerive.ts`
4. Register in default widget list (`defaultLedgerWidgets`)

**Customize layout**:
- User opens layout editor (icon in Ledger header)
- Drags panels to reorder, resizes with handles, hides unwanted panels
- Preferences saved to Supabase `user_prefs.ledger_prefs` (JSONB)
- On app load, prefs rehydrated from Supabase

---

## 4. Sharing & Access Control

**What it does**: Share your library with others via time-bound read-only links or grant friend access.

### Features

- **Shareable links**: Generate tokens with optional expiration
- **Read-only access**: Shared users can't edit, only browse
- **Scoped access**: Optionally restrict what's visible (e.g., only show "watched" titles, only show certain genres)
- **Link labels**: Friendly names for tokens (e.g., "Dad's link")
- **Deactivate anytime**: `is_active` flag allows soft deletion
- **Usage tracking**: `last_used_at` timestamp for analytics

### How It Works

**Shared Link Flow**:
1. Owner opens Profile → Sharing → "Create link"
2. Generates new row in `shared_access_keys` with a random 32-byte hex token
3. Link is `https://cinemarchive.../...?share=<token>`
4. Visitor opens link, app calls `set_shared_token(token)` RPC to set session variable
5. All subsequent queries filter by the RLS policy:
   ```sql
   using (... or is_valid_shared_token(get_current_setting('app.shared_token'), user_id) ...)
   ```
6. Visitor sees the shared library (no auth required)

**Share Scopes**:
- Optional filtering per link or per friend (narrow genres, statuses)
- Stored in `share_scopes` table
- Accessed by `lib/db.ts` functions to apply filters server-side

### Where to Look

| Component | Responsibility |
|-----------|-----------------|
| `/src/views/Profile.tsx` | Sharing tab: create/manage/revoke links |
| `/src/lib/auth.ts` | `set_shared_token()`, `fetchSharedLibrary()` logic |
| `/src/lib/db.ts` | `fetchSharedLibrary()`, `fetchShareScopes()` |
| `/schema.sql` | `shared_access_keys`, `share_scopes` tables + RLS policies |
| `/supabase/migrations/20260706020000_shared_key_hardening.sql` | RLS hardening for shared access |
| `/scripts/verify-share-scope-logic.mjs` | Verification: scopes filter correctly |

### Common Tasks

**Create a share link**:
1. Open Profile → Sharing
2. Click "New link"
3. Set expiration (optional), label, scopes (optional)
4. Copy link, share with friend

**View a shared library**:
1. Receive link from friend
2. Click link → app loads shared library
3. Can browse/search/filter but not edit

**Revoke a link**:
1. Open Profile → Sharing
2. Click "Deactivate" next to link
3. Link no longer works

---

## 5. Social Features

**What it does**: Connect with friends, see what they're watching, comment, react, and send recommendations.

### Features

- **Friend requests**: Add friends, accept/decline requests
- **Blocking**: Block users (prevents friend requests, hides activity)
- **Activity feed**: Paginated feed of what friends watched, rated, commented on
- **Comments**: Leave friends-only comments on titles
- **Reactions**: React with emoji to titles (visible to friends)
- **Recommendations**: Send titles to friends with optional personal notes
- **Recommendation inbox**: View received recommendations, accept/dismiss

### How It Works

**Friendship State Machine**:
- `pending` — requester sent invite, recipient hasn't responded
- `blocked` — blocker blocked the other user
- `friend` — mutual friends
- `unblocked` — formerly friends, now unblocked

**Activity Feed**:
- Triggers on `episodes` (watch/rate/review), `comments`, `reactions` inserts
- Denormalized log in `friend_activity_feed` table
- Friend view queries and paginates this feed

**Recommendations**:
- Stored in `recommendations` table with status: `pending` | `accepted` | `dismissed`
- Recipient sees in Profile → Recommendations inbox
- Can accept (adds to their library) or dismiss

### Where to Look

| Component | Responsibility |
|-----------|-----------------|
| `/src/views/Friends.tsx` | Friend management, activity feed, recommendations inbox |
| `/src/views/Profile.tsx` | Recommendations inbox + send panel |
| `/src/components/SendRecommendationPanel.tsx` | UI to send recommendation to friend |
| `/src/components/TitleCommentsPanel.tsx` | Comments & reactions UI |
| `/src/lib/db.ts` | Friends CRUD, activity feed queries, recommendations |
| `/schema.sql` | `friendships`, `friend_activity_feed`, `recommendations`, `title_comments`, `title_reactions` tables |
| `/supabase/migrations/20260630010000_friendships.sql` | Friendship table + state machine |
| `/scripts/verify-friendship-state-machine.mjs` | Verification: state machine logic |

### Common Tasks

**Add a friend**:
1. Open Friends view
2. Search for friend by email
3. Send request
4. Friend sees notification, can accept/decline

**Send a recommendation**:
1. Open title detail drawer
2. Click "Recommend" button
3. Select friend, add optional note
4. Sends notification to friend

**View friend activity**:
1. Open Friends view
2. See paginated feed of what friends watched, rated, commented on
3. Click activity entry to drill down

---

## 6. Notifications

**What it does**: Persistent in-app notification center tracking all app events (shares used, friend requests, comments, reactions, recommendations, activity).

### Features

- **Notification types**: `invite_redeemed`, `share_link_used`, `friend_request`, `comment`, `reaction`, `recommendation`, `activity_update`
- **Persistent inbox**: Notifications stored in DB, survive page refresh
- **Unread count**: Real-time badge showing unread count
- **Mark as read**: Click notification to mark read
- **Dismiss**: Remove notification from inbox
- **Share tracking**: `last_used_at` on `shared_access_keys` updated per user per hour (prevents spam)

### How It Works

1. DB event occurs (someone redeems invite, comments on title, etc.)
2. Supabase trigger inserts row into `notifications` table
3. App polls `fetchUnreadNotificationCount()` every N seconds
4. Notification center shows unread count badge
5. User opens notification center → loads notifications with pagination
6. User clicks notification → marks read, potentially triggers drill-down (e.g., "comment on title X" → opens that title)

### Where to Look

| Component | Responsibility |
|-----------|-----------------|
| `/src/components/NotificationCenter.tsx` | Notification inbox UI + pagination |
| `/src/components/NotificationStack.tsx` | Toast-style notifications (ephemeral) |
| `/src/store/useAppStore.ts` | Notifications slice: fetch, mark read, dismiss |
| `/src/lib/db.ts` | `fetchNotifications()`, `markNotificationRead()`, `deleteNotification()` |
| `/schema.sql` | `notifications` table + triggers for each event type |
| `/supabase/migrations/20260706090000_notifications.sql` | Notification table + triggers |

### Common Tasks

**Dismiss a notification**:
1. Open notification center (bell icon)
2. Click ✕ on notification
3. Removed from inbox

**Check unread count**:
- Badge on bell icon shows unread count
- Count polled every few seconds

---

## 7. Auth & Invite-Only Signup

**What it does**: Invite-only signup with server-side verification. Users sign in with passkey/WebAuthn (no passwords).

### How It Works

**Signup Flow**:
1. User clicks "Sign up"
2. Enters email, clicks "Send invite code"
3. Code is sent to email (assumed external email service)
4. User enters code (or clicks link in email)
5. Clicks "Create account" → calls `redeemInvite(email, code)` Edge Function
6. Server-side validation:
   - Check code format and email validity
   - Check rate limit (max 10 attempts per IP or email in 15 min)
   - Lookup code in `invite_codes` table, verify not redeemed yet
   - Create user via admin API
   - Mark code as redeemed, send notification to inviter
7. User now authenticated, redirected to app

**Login Flow**:
1. User clicks "Sign in"
2. Enters email
3. Clicks "Send magic link" → Supabase sends WebAuthn/passkey challenge
4. User completes passkey auth on their device
5. User logged in, redirected to app

**Logout**:
1. User opens Profile → Account
2. Clicks "Sign out"
3. Clears Supabase session + Zustand user state

### Where to Look

| Component | Responsibility |
|-----------|-----------------|
| `/src/lib/auth.ts` | `signUpWithOtp()`, `redeemInvite()`, `signInWithPasskey()`, `logout()` |
| `/src/components/InviteRedeemForm.tsx` | Signup form UI |
| `/supabase/functions/redeem-invite/index.ts` | Server-side account creation gate |
| `/schema.sql` | `invite_codes`, `invite_redeem_attempts` tables |
| `/supabase/migrations/20260702000001_invite_codes.sql` | Invite code table |
| `/supabase/migrations/20260706050000_invite_redeem_attempts.sql` | Rate limiting table |

### Common Tasks

**Generate invite codes**:
- Owner profile has "Invite codes" tab
- Can generate new codes, set max redeem count (or leave uncapped)
- Codes are 8-char uppercase hex strings (from UUID)

**Resend invite code**:
1. User doesn't receive email
2. Clicks "Resend" button
3. Email service resends code

---

## 8. Themes

**What it does**: Switch between four visual themes: dark (default), light, noir, and matrix.

### Themes

- **dark** — Default: void black + amber gold
- **light** — Light background, dark text (accessibility)
- **noir** — Black & white, grayscale with hints of color; enables "color mode" per episode (Spider Noir feature)
- **matrix** — Green on black, digital rain effect

### How It Works

1. User opens Profile → Settings → Theme
2. Selects theme
3. App calls `toggleTheme(theme)` in `/src/lib/theme.ts`
4. Sets `<html data-theme="...">` attribute
5. CSS variables update (defined in `/src/index.css`)
6. Zustand persists choice to localStorage

### Where to Look

| Component | Responsibility |
|-----------|-----------------|
| `/src/lib/theme.ts` | Theme switching logic + CSS variable setters |
| `/src/index.css` | CSS variables per theme (--color-*, --font-*) |
| `/src/store/useAppStore.ts` | `theme` state, `toggleTheme()` action |
| `/src/views/Profile.tsx` | Theme picker UI |
| `/src/lib/easterEggThemes.ts` | Easter egg theme unlocks |

### Common Tasks

**Add a new theme**:
1. Define CSS variables in `/src/index.css` for the theme
2. Add theme name to `Theme` type in useAppStore.ts
3. Register in theme picker UI
4. Test across all views

---

## Discovering More

Each feature domain has specific RLS policies, tables, migrations, and business logic. Check the [Source Reference](../source-reference.md) for a complete file map, or the [Architecture](../architecture/overview.md) section for data model details.
