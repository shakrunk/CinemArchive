# Architecture Overview

CinemArchive is a JAMstack application: a static React frontend on GitHub Pages, backed by Supabase for persistence and auth, and TMDB/OMDb APIs for media metadata.

---

## Tech Stack

| Layer | Choice | Why |
|-------|--------|-----|
| **Frontend** | Vite + React 19 + TypeScript | Fast builds, modern React APIs, type safety |
| **Styling** | Tailwind CSS + shadcn-ui | Utility-first + composable Radix UI primitives |
| **State** | Zustand with localStorage persist | Lightweight, easy to split into slices, built-in persist middleware |
| **Database** | Supabase (Postgres + RLS) | Managed Postgres, Row Level Security for multi-user access, Auth built-in |
| **Auth** | Supabase Auth + passkey/WebAuthn | No passwords, phishing-resistant, FIDO2 standard |
| **APIs** | TMDB + OMDb (proxied) | Movie/show metadata, posters, rating badges; proxied to hide keys server-side |
| **Edge Functions** | Deno (Supabase Functions) | Server-side logic: TMDB/OMDb proxy with caching, invite-only signup gate |
| **Ledger Visuals** | Custom CSS (no charting lib) | Minimal bundle size, custom pixel-perfect animations |
| **PWA** | vite-plugin-pwa + workbox | Offline app shell + font caching, installable |
| **Deploy** | GitHub Actions → GitHub Pages | Free static hosting, CI/CD for migrations + frontend |

---

## Data Model

### Core Tables

**titles** — Movies and TV series.
- `id` (UUID primary key)
- `user_id` — foreign key to `auth.users`; every title belongs to one owner
- `tmdb_id` — TMDB identifier (immutable, used for lookups)
- `type` — enum: `movie` | `tv`
- `title`, `year`, `genres`, `poster_url`, `synopsis`, `runtime` (movies), `network` (TV)
- `status` — enum: `watched` | `watchlist` | `watching` | `dropped`
- `rating` (0–5), `notes`, `tags` — user-provided metadata
- `imdbRating`, `rtScore`, `metacriticScore` — external rating badges (from OMDb)
- `imdbId`, `collectionId`, `collectionName` — TMDB/IMDb identifiers
- `releaseDate` — drives "Up Next" filtering
- `originalLanguage`, `contentRating` — ISO codes and certifications
- `customWatchUrl` — owner override for "where to watch"
- `added_at`, `updated_at` — timestamps

Constraint: `unique (user_id, tmdb_id, type)` — a user can't add the same title twice.

**seasons** — TV-only relational child of titles.
- `id`, `title_id`, `season_number`
- `episodeCount`, `episodesWatched` — denormalized from episodes for quick rollups
- `air_year` — season air date

**episodes** — TV-only, identified by `(title_id, season_number, episode_number)`.
- `id`, `title_id`, `season_number`, `episode_number`
- `episodeName`, `airDate`, `runtime`, `synopsis`, `stillUrl` — TMDB metadata
- Unique constraint: `(title_id, season_number, episode_number)`

**episode_watch_events** — Independent timeline entry per episode.
- `id`, `episode_id`, `user_id`
- `watched_at` — ISO date or null (indeterminate)
- `notes`, `color_mode` (Spider Noir only)
- One episode can have multiple watch events (re-watches)

**episode_ratings** — Timestamped rating log per episode.
- `id`, `episode_id`, `user_id`
- `rating` (0–5), `rated_at` — when the user recorded it, not when watched
- One entry per rating change (historical log)

**episode_reviews** — Timestamped review log per episode.
- `id`, `episode_id`, `user_id`
- `review_text`, `color_mode`, `reviewed_at`
- Independent of watch events or ratings

**viewings** — Movie/TV re-watch timeline (all media types).
- `id`, `title_id`, `user_id`
- `viewed_at` — ISO date or null
- `rating`, `notes` — optional per viewing
- Many per title (one entry per viewing session)

### Metadata Tables

**title_cast**, **title_crew** — Top cast & crew per title (from TMDB).
- Keyed by `tmdb_person_id` for browsing by actor/director
- Unique constraint: prevents duplicates on the same title

**season_cast** — Season-level cast (regulars/guests per season).

**episode_crew** — Per-episode directors and writers.

### Social & Sharing Tables

**profiles** — User metadata (email, display name, bio).
- `user_id` — foreign key to `auth.users`

**friendships** — Bidirectional friend relationships with state machine.
- `requester_id`, `recipient_id` — foreign keys to `auth.users`
- `state` — enum: `pending` | `blocked` | `friend` | `unblocked` (former friend)

**friend_activity_feed** — Denormalized log of what friends watched, rated, commented on.
- Populated by triggers on episodes, comments, reactions
- Read by the Friends view

**shared_access_keys** — Time-bound read-only access tokens.
- `token` — high-entropy hex string (unique)
- `user_id` — owner
- `expires_at` — null = never expires
- `is_active` — soft delete
- `last_used_at` — tracked for notifications

**share_scopes** — Optional filtering per link or per friend.
- `shared_key_id` XOR `friend_user_id` (one target only)
- `allowed_genres`, `allowed_statuses` — null = all (opt-in narrowing)

**title_comments** — Friends-only comments on titles.
- `title_id`, `user_id`, `comment_text`, `created_at`
- Visible only to friends

**title_reactions** — Friends-only reactions (emoji) on titles.
- `title_id`, `user_id`, `emoji`, `created_at`

**recommendations** — "Send to friend" feature.
- `title_id`, `from_user_id`, `to_user_id`
- `personal_note` — optional message
- `status` — enum: `pending` | `accepted` | `dismissed`

**notifications** — Persistent notification center.
- `recipient_id`, `type`, `payload` (JSONB)
- `read_at` — null = unread
- Types: `invite_redeemed`, `share_link_used`, `friend_request`, `comment`, `reaction`, `recommendation`, `activity_update`

### Utility Tables

**user_prefs** — User preferences (nav order, hidden nav items, ledger layout).
- `user_id`, `nav_prefs`, `ledger_prefs` (JSONB)

**api_cache** — KV cache for TMDB/OMDb responses (used by Edge Function).
- `cache_key`, `response`, `expires_at`

### Indexes

Thoughtfully selected for common queries:
- `titles(user_id, added_at desc)` — user's library, newest first
- `episodes(title_id)`, `episodes(user_id)` — episode access patterns
- `episode_watch_events(episode_id)` — finding all watches of an episode
- `shared_keys(token)` — share link validation
- `friendships(requester_id, recipient_id)` — friendship lookups
- ... and many more for social/notification queries

---

## Client-Side Store (Zustand)

### Structure

The `useAppStore` is split into three logical slices, all persisted to localStorage:

#### 1. **library** slice
- `titles: Title[]` — full library (loaded on startup)
- `filters: LibraryFilters` — search, type, status, genres, tags, networks, decades, languages, ratings, person/studio, franchise grouping
- `sortField`, `sortDir` — sort state
- `viewMode` — 'grid' | 'list'
- Actions: `addTitle()`, `updateTitle()`, `deleteTitle()`, `logEpisode()`, `deleteViewing()`, `logPlatformWatchEvent()`, `setFilter()`, `setSortField()`, `setViewMode()`
- Derived: `filteredTitles` (memoized result of applying all filters/sorts)

#### 2. **ledger** slice
- `stats: LedgerStats` — cached aggregations (counts, avg rating, viewing timeline, genre distribution, etc.)
- `widgets: LedgerWidget[]` — customizable dashboard layout (ordered list of widget instances)
- `widgetSettings: LedgerWidgetSettings` — per-widget parameterization
- Actions: `updateLedgerLayout()`, `updateLedgerWidgetSettings()`
- Derived: Stats are recomputed via `ledgerDerive.ts` whenever titles change

#### 3. **ui** slice
- `isAddTitleOpen`, `isDetailDrawerOpen`, `isRefreshMetadataOpen`, `isCommandPaletteOpen`, `isProfileOpen` — modal visibility
- `openDetailTitle` — which title's detail drawer is open
- `theme` — 'dark' | 'light' | 'noir' | 'matrix'
- `navPrefs` — nav order, hidden, compact
- `user` — currently authenticated user (or null)
- `isSharedView`, `sharedLibrary` — when viewing a shared link
- `isFriendView`, `friendLibrary` — when viewing a friend's library
- `pendingView`, `requestView()` — async view changes (e.g., from detail drawer's "browse by director")
- Notifications, command palette state, keyboard shortcuts active state

### Optimistic Updates

User actions update Zustand **immediately** (optimistic update). The async DB write follows:

```typescript
// Example: logEpisode
logEpisode: (titleId, season, episode, watchedAt, rating) => {
  // 1. Update Zustand immediately
  const newTitle = { ...title, seasons: [...] }; // update local state
  set(s => ({ titles: [...s.titles, newTitle] }))
  
  // 2. Fire async DB write
  logEpisodeToDb(titleId, season, episode, watchedAt, rating).catch(err => {
    // 3. On error, log and show notification (user can retry)
    console.error(err)
    addNotification({ kind: 'error', message: '...', retry: ... })
  })
}
```

This keeps the UI snappy. Errors are surfaced as notifications; the user can retry.

### Derivations & Memoization

Heavy derivations (filtered titles, ledger stats) are memoized to avoid recomputation on every render:

- `filteredTitles` — memoized selector applying all filters/sorts to library
- Ledger stats — computed once per library change via `computeLedgerStats()`
- Per-panel stats — derived by `ledgerDerive.ts` with caching

---

## Supabase RLS Policies

Row Level Security ensures:

1. **Own data** — Authenticated users can read/write their own titles, episodes, viewings, etc.
   ```sql
   using (auth.uid() = user_id)
   ```

2. **Shared links** — Valid `shared_access_keys` token holders can read the owner's library (scoped by `share_scopes` if set).
   ```sql
   using (
     auth.uid() = user_id or
     is_valid_shared_token(get_current_setting('app.shared_token'), user_id) or
     friend access...
   )
   ```

3. **Friend access** — If users are friends, they can read each other's libraries (filtered by `share_scopes`).

4. **Friends-only data** — Comments, reactions, recommendations are visible only to friends.

Every query is automatically filtered by these policies. A hacked client can't bypass them — Supabase enforces at the database layer.

Source: `/schema.sql` — RLS policies section.

---

## Supabase Edge Functions

### media-proxy

**Purpose**: Proxy TMDB and OMDb API requests, keep API keys server-side, cache responses.

**Endpoints**:
- `POST /functions/v1/media-proxy` with JSON body:
  - `action: 'search_tmdb' | 'search_omdb' | 'get_tmdb_details' | ...`
  - `query`, `type` (movie/tv), `id`, etc. depending on action

**Caching**: Responses cached in `api_cache` table with 24-hour TTL.

**Called by**: `/src/lib/media.ts` (search modal, detail refresh, TMDB lookup).

Source: `/supabase/functions/media-proxy/index.ts`

### redeem-invite

**Purpose**: Server-side gate for invite-only signup. Creates an `auth.users` row only when a valid, unredeemed invite code is presented.

**Endpoint**: `POST /functions/v1/redeem-invite` with JSON body:
- `email` — new user's email
- `code` — invite code (8 chars, uppercase)

**Rate limiting**: Max 10 attempts per IP or email in a 15-minute window (closes the "unlimited endpoint calls" gap).

**Validation**:
1. Check email format and code presence
2. Verify rate limit
3. Lookup invite code and check redeem count
4. Create user via admin API (service role key)
5. Mark code as redeemed, send notification to inviter

**Response**: `{ success: true }` on success, `{ error: message }` on failure (always 200 to let supabase-js parse the JSON).

Source: `/supabase/functions/redeem-invite/index.ts`

---

## Frontend-Database Communication

### Reads: Query → Zustand Cache

Example: User opens Library view on startup.

1. App calls `useAppStore.getState().loadUserLibrary()`
2. `loadUserLibrary` calls `fetchUserLibrary()` from `db.ts`
3. `fetchUserLibrary()` executes:
   ```typescript
   const { data, error } = await supabase
     .from('titles')
     .select('id, title, year, ..., seasons(...), episodes(...), ...')
     .eq('user_id', userId)
   ```
4. Zustand updates: `set(s => ({ titles: data || [] }))`
5. All views derive `filteredTitles` from the store and render

The entire library lives in Zustand. No incremental queries per filter change — the store is the single source of truth.

### Writes: Optimistic → DB

Example: User logs an episode watch.

1. User clicks "Mark watched" in the episode logging UI
2. Component calls `useAppStore.getState().logEpisode(...)`
3. Store updates immediately (optimistic)
4. Store also fires `logEpisodeToDb(...)` async
5. DB write:
   ```typescript
   const { error } = await supabase
     .from('episode_watch_events')
     .insert({ episode_id, user_id, watched_at, notes })
   ```
6. If error, show notification with retry button

---

## URL-Based Navigation

The app uses query parameters for view state. The URL is the source of truth.

**Format**: `?view=library&title=<uuid>&add=1&share=<token>&friend=<uuid>`

- `view` — current section (upnext, library, ledger, discover, profile, friends)
- `title` — which title's detail drawer is open (UUID)
- `add` — is the add-title search modal open?
- `share` — if set, user is viewing a shared library with this token
- `friend` — if set, user is viewing this friend's library

**Sync**: `/src/lib/useNavigationSync.ts` watches for popstate (back button) and syncs `window.location.search` ↔ Zustand `pendingView`.

**Benefits**:
- Refresh restores the exact view and open modal
- Titles are linkable: `?view=library&title=abc123` → friend opens the link, sees that title
- Back/forward navigation works naturally
- Shared links embed the token in the URL

Source: `/src/lib/navigation.ts`, `/src/lib/useNavigationSync.ts`

---

## Deployment: GitHub Actions → GitHub Pages

### Workflow: Deploy Frontend

Triggers on push to `main`:

1. Checkout code
2. Install dependencies
3. Run `npm run build` (type-check + vite build → `dist/`)
4. Deploy to GitHub Pages (`.github/workflows/deploy.yml`)

### Workflow: Deploy Database Migrations

Triggers on changes to `supabase/migrations/**`:

1. Checkout code
2. Install Supabase CLI
3. Run `supabase db push` (applies pending migrations to the linked Supabase project)

Note: Functions deploy independently via `.github/workflows/deploy-functions.yml` when `supabase/functions/**` changes.

### SPA on GitHub Pages

GitHub Pages serves a static site (no server-side routing). For SPAs, we use a `404.html` fallback:
- All URLs not matching a real file redirect to `index.html`
- React Router (or in this case, query-param navigation) takes over
- App served from a custom domain (`public/CNAME`) at `/` (Vite `base: '/'`)

---

## PWA (Progressive Web App)

**Plugin**: `vite-plugin-pwa`

**Features**:
- Service worker caches the app shell (HTML, JS, CSS)
- Fonts cached for offline access
- Installable via "Add to Home Screen" on mobile
- Offline mode: cached library works without network

**Cache strategy**: App shell cached, API calls fail gracefully when offline (user sees cached data).

Source: `/vite.config.ts` (PWA plugin config)

---

## Key Patterns

### Memoized Selectors in Zustand

```typescript
const filteredTitles = useMemo(() => {
  return apply_filters(all_titles, filters)
}, [titles, filters])

// Don't compute on every render — only when inputs change
```

### Fire-and-Forget Async

```typescript
// Component triggers action
openAddTitle()

// Store action updates immediately + fires async
const action = async () => {
  await dbWrite() // fire-and-forget
}
action().catch(err => handle_error(err))
```

### Timestamp-Based Migrations

Schema changes are versioned SQL files in `supabase/migrations/`. New files are auto-applied. `schema.sql` is kept in sync as a human-readable reference.

Never edit the SQL editor directly — always use migrations.

### RLS as Access Control Layer

Don't try to implement access control in the client. Every DB query goes through RLS. If a query passes RLS, the user is authorized to see that data.

---

## Performance Considerations

1. **No pagination on library** — All titles loaded into Zustand on startup. Assumes < 10K titles per user. Poster wall grid virtualization (not implemented) would help for very large libraries.

2. **Client-side filtering** — No DB queries for filter changes. Fast but only works because the library is small.

3. **Cached stats** — Ledger stats recomputed once per library change (not on every render). Stored in Zustand slice.

4. **Selective data loading** — Detail drawer fetches episode details on demand (not all episodes upfront). Metadata (posters, badges) fetched via Edge Function with 24-hour cache.

5. **Lazy component loading** — Views and modals are code-split by Vite.

---

## To Learn More

- **Database**: See `/schema.sql` for the complete schema with comments.
- **Client API**: See `/src/lib/db.ts` for all Supabase read/write functions.
- **Store actions**: See `/src/store/useAppStore.ts` for business logic.
- **Migrations**: Browse `/supabase/migrations/` to see how schema evolved.
