# CinemArchive — Quick Start

**CinemArchive** (a.k.a. "The Projection Room v2") is a personal movie and TV series tracking app with a cinematic dark-gold aesthetic. Search TMDB, log what you watch, rate and review, and browse your library as a poster wall or dive into detailed viewing stats in **The Ledger**.

**Live:** https://cinemarchive.kumarfamilynet.work/

---

## What It Does

- **Library** — Poster wall or sortable list of your movies/shows with client-side search, filtering (type, status, genre, tag, network, decade, rating, language, studio, director), and sorting.
- **Episode-level tracking** — Log individual episodes per season, decoupled from ratings and reviews. Season/series stats roll up automatically from episode data.
- **The Ledger** — Stats dashboards with charts, viewing timeline, top genres/directors, media breakdown, franchise grouping, and customizable widget layouts.
- **Up Next** — Upcoming releases and unwatched episodes at a glance.
- **Social features** — Share read-only links with time-bound tokens, add friends, comment & react to titles, send recommendations with personal notes, view friend activity feeds.
- **Notifications** — Per-feature push notifications when friends interact, shares are used, or recommendations arrive.
- **Auth** — Passkey/WebAuthn signup (invite-only) via Supabase Auth; no passwords.
- **Offline PWA** — Installable with service worker caching.
- **Import/export** — Backup or move your library as JSON.

---

## Architecture at a Glance

| Component | Choice | Purpose |
|-----------|--------|---------|
| **Build** | Vite + React 19 + TypeScript | Static frontend; HMR during dev |
| **State** | Zustand with localStorage persist | Client-side filters, UI state, library cache |
| **Styling** | Tailwind CSS + shadcn-ui (Radix primitives) | Design system + accessible components |
| **Database** | Supabase (Postgres + RLS) | Persistent user library, social graph, auth |
| **APIs** | TMDB + OMDb (proxied via Edge Function) | Movie/show metadata and rating badges |
| **Auth** | Supabase Auth + passkey/WebAuthn | Signup & login (invite-only via Edge Function) |
| **Deploy** | GitHub Actions → GitHub Pages | CI/CD for frontend + database migrations |
| **Ledger visuals** | Custom CSS (no charting library) | Minimal bundled size; custom animations |

---

## Project Layout

```
src/
  components/
    ui/                      # Atomic shadcn components
    AddTitleWorkflow.tsx      # Search → TMDB → add form
    TitleDetailDrawer.tsx     # Title detail + episode logging + viewing history
    NotificationCenter.tsx    # Notification inbox UI
    TopBar.tsx / BottomNav.tsx / ProfileModal.tsx
  views/
    Library.tsx              # Poster wall + list view
    Ledger.tsx / ledger/     # Stats dashboards + widget panels
    Discover.tsx             # TMDB search browser
    UpNext.tsx               # Upcoming releases & unwatched episodes
    Friends.tsx              # Social graph & recommendations
    Profile.tsx              # Account settings, invite codes, sharing
  store/
    useAppStore.ts           # Zustand store (library, ledger, ui, auth state)
    ledgerDerive.ts          # Stat computation + caching layer
    ledgerStats.ts           # Aggregations (counts, avg rating, etc.)
    episodeUtils.ts          # Episode → season → series rollups
    upNext.ts                # Upcoming title derivations
    mockData.ts              # Type definitions + seed data for logged-out mode
    commands.ts              # Keyboard shortcuts + command palette actions
  lib/
    db.ts                    # All Supabase reads/writes (client API)
    auth.ts                  # Supabase Auth + passkey helpers
    media.ts                 # TMDB/OMDb API wrapper (calls Edge Function proxy)
    export-import.ts         # JSON backup/restore
    navigation.ts            # URL ↔ AppView state parser
    useNavigationSync.ts     # URL ↔ store synchronization
    useKeyboardShortcuts.ts  # Single-key shortcuts (⌘/Ctrl+K → palette, 1-6 → nav tabs)
    theme.ts                 # Theme switching (dark, light, noir, matrix)
    ledgerPanels.ts          # Ledger widget definitions + layout helpers
schema.sql                    # Canonical DB schema + RLS policies
supabase/
  migrations/                # Timestamped SQL migrations (Supabase CLI)
  functions/
    media-proxy/             # Edge Function: TMDB/OMDb proxy + KV cache
    redeem-invite/           # Edge Function: server-side account creation from invite code
scripts/
  verify-*.mjs               # Verification scripts for migrations, state machines, logic
```

---

## Key Concepts

### The Title Type

Core data structure representing a movie or TV series. Includes metadata (title, year, genres, rating badges), viewing state (status, personal rating, notes, tags), and nested season/episode data for TV shows.

Source: `/src/store/mockData.ts` — `Title`, `Season`, `Episode`, `Viewing` interfaces.

### Zustand Store

Three major slices:
- **library** — All titles, filter/sort state, CRUD operations
- **ledger** — Cached stat computations, widget layout preferences
- **ui** — Modal visibility (add, detail drawer, profile), command palette state, theme

Optimistic updates: the store updates immediately on user action; DB writes follow async (fire-and-forget with error logging).

Source: `/src/store/useAppStore.ts`

### Database Row Level Security (RLS)

- Authenticated users get full CRUD on their own titles/episodes/viewings.
- Shared-link holders with valid `shared_access_keys` tokens get read-only access to the owner's library (scoped by optional `share_scopes` filters).
- Friend relationships can grant read-only library access.

Source: `/schema.sql` — RLS policies section.

### Episode Independence

Watch events, ratings, and reviews are stored independently. A user can:
- Watch an episode without rating it.
- Re-watch without changing the rating.
- Rate without a watch date.
- Review without watching.

Season and series stats roll up from episodes (e.g., episodes watched → season progress → series completion).

Source: `/schema.sql` — tables `episodes`, `episode_watch_events`, `episode_ratings`, `episode_reviews`.

### Client-Side Filtering & Sorting

All filter/sort state lives in Zustand. Views query the store's filtered library (no DB queries for filter changes). Poster wall and list view are two rendering modes of the same filtered data.

Source: `/src/store/useAppStore.ts` — `LibraryFilters` and filtered titles derivation.

### Deep Links & Back Button

The active view and open title live in the URL query string. Refresh restores where you were. Browser/mobile back button closes a drawer instead of leaving the app.

Source: `/src/lib/navigation.ts` (URL parser), `/src/lib/useNavigationSync.ts` (React ↔ URL sync).

### Themes

Four themes (dark, light, noir, matrix) with CSS in `index.css`. Some themes enable special features (e.g., Spider Noir's color-mode per episode). Rehydrated from localStorage.

Source: `/src/lib/theme.ts`, `/src/index.css`.

---

## Getting Started

### Local Development

```bash
npm install
npm run dev          # http://localhost:5173
npm run build        # Type-check + production build → dist/
npm run lint         # ESLint
```

Environment variables (`.env.local`):
```
VITE_SUPABASE_URL=https://<project>.supabase.co
VITE_SUPABASE_ANON_KEY=<anon-key>
```

TMDB and OMDb API keys live on the `media-proxy` Edge Function as secrets (never in the browser).

### Supabase Setup

The app also runs read-only on seed data if Supabase is not configured — useful for local prototyping.

For a real Supabase project:
1. Create a project at https://supabase.com
2. Paste the URL and anon key into `.env.local`
3. Migrations auto-apply via `.github/workflows/db-migrate.yml` on push to main

---

## Documentation Map

- **[Architecture](architecture/overview.md)** — Tech stack, data model, core patterns, Supabase setup, Edge Functions
- **[Features & Domains](features/index.md)** — Library, Ledger, Episode tracking, Sharing, Social features, Notifications, Auth, Themes
- **[Workflows](workflows/index.md)** — Search & add title, log episode, invite friends, share links, recommendations, manage permissions
- **[Operations](operations/index.md)** — Local dev, Supabase migrations, GitHub Actions deploy, environment setup
- **[Testing & Verification](testing/index.md)** — Verification scripts, logic checks, state machine validation
- **[Source Reference](source-reference.md)** — Organized file map with key exports and responsibilities

---

## What to Read First

**If you're new to the codebase:**
1. Read this quickstart.
2. Skim the [Architecture overview](architecture/overview.md) to understand the stack.
3. Check the [Source Reference](source-reference.md) for a high-level file map.

**If you're adding a feature:**
1. Read the relevant section in [Features & Domains](features/index.md).
2. Check the [Workflows](workflows/index.md) to understand the flow.
3. Refer to the source files listed there.

**If you're debugging or maintaining:**
1. Check [Operations](operations/index.md) for deployment, auth, and migration guidance.
2. Review [Testing & Verification](testing/index.md) for validation tools.
3. Read the relevant feature section above and trace through the store + components.

---

## Key Files to Know

- **`/src/store/useAppStore.ts`** — The nerve center. All app state, derived data, and async actions live here.
- **`/src/lib/db.ts`** — Supabase client API. Read/write functions for every table.
- **`/schema.sql`** — Source of truth for the DB schema and RLS policies.
- **`/src/components/TitleDetailDrawer.tsx`** — Massive file containing title detail view + episode logging UI.
- **`/src/views/Ledger.tsx` + `/src/views/ledger/`** — Stats dashboard and widget implementations.
- **`/supabase/migrations/`** — All schema changes, timestamped and version-controlled.

---

## Common Tasks

### Add a New Filter to the Library

1. Add a field to `LibraryFilters` in `/src/store/useAppStore.ts`
2. Add a filter UI component (usually in a modal or sidebar)
3. Update the `filteredTitles` derivation in the store to apply the filter
4. Test in the Library view

### Log an Episode

Flow: User opens title detail drawer → selects season/episode → clicks "Mark watched" → `logEpisodeToDb()` fires async in `db.ts` → Zustand updates optimistically.

Source: `/src/components/TitleDetailDrawer.tsx` (UI), `/src/store/useAppStore.ts` (logEpisode action), `/src/lib/db.ts` (DB operation).

### Add a Ledger Panel

1. Define the panel type in `/src/lib/ledgerPanels.ts`
2. Implement the component in `/src/views/ledger/panels/`
3. Add stat computation to `/src/store/ledgerDerive.ts`
4. Register in the default widget list

Source: `/src/lib/ledgerPanels.ts`, `/src/views/ledger/panels/`, `/src/store/ledgerDerive.ts`.

### Invite a Friend

Server-side invite creation lives in the `redeem-invite` Edge Function. Client calls it with an email + code generated from the Profile page.

Source: `/supabase/functions/redeem-invite/index.ts`, `/src/components/ProfileModal.tsx`.

---

## Recent Work (Last 10 Commits)

The repository has been actively developed with a focus on sharing and social features:

- **Notifications** — Persistent notification center with per-feature events (shares used, invites redeemed, activity updates)
- **Comments & Reactions** — Friends can comment and react to titles in shared libraries
- **Activity Feed** — Paginated feed showing what friends watched, rated, and commented on
- **Share Scopes** — Per-friend or per-link filtering of what's visible (genres, statuses)
- **Recommendations** — Send titles to friends with optional personal notes; view in an inbox
- **Friends** — First-class view for social graph management, blocking, unlocking

Source: Recent migrations in `/supabase/migrations/20260706*.sql`, components like `/src/components/NotificationCenter.tsx`, `/src/components/TitleCommentsPanel.tsx`, `/src/views/Friends.tsx`.

---

## Known Issues & Caveats

- Keyboard shortcuts info panel could be improved for better layout
- Custom "where to watch" URL is not yet settable when sending recommendations
- Invite-only signup is server-side (redeem-invite Edge Function) but not yet capped per user

See `/docs/planned-features-human-written.md` for human-written feature ideas.

---

## Questions or Need Help?

Refer to the detailed sections linked above, or check the source code inline comments (especially in `/src/store/useAppStore.ts` and `/src/lib/db.ts` for business logic).
