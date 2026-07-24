# CinemArchive — The Projection Room

A personal movie and TV series tracking app with a cinematic dark-gold aesthetic. Search TMDB, log what you watch (down to the individual episode), rate and review, and browse your library as a poster wall or pore over your viewing stats in **The Ledger**.

**Live:** https://cinemarchive.kumarfamilynet.work/

It's a JAMstack app: a static React frontend on GitHub Pages, backed by Supabase (Postgres + Auth + Edge Functions), with TMDB and OMDb for metadata and rating badges. A native Android client (Kotlin + Jetpack Compose) shares the same Supabase backend — see [Android app](#android-app) below.

---

## Features

- **Library** — poster wall + sortable list view, with client-side search, filtering (type, status, genre, tag, network, decade, rating), and sorting.
- **Command palette (⌘K / Ctrl+K)** — jump to any title or fire an action (add a title, switch view, change layout) from the keyboard; ↑/↓ to move, Enter to run, Esc to close.
- **Deep links & back button** — the active view and the open title live in the URL, so a refresh restores where you were, titles are linkable, and the browser/mobile back button closes an open drawer instead of leaving the app.
- **Episode-level TV tracking** — each season expands into episodes; log watch events, ratings, and reviews per episode, all decoupled (re-watch an episode without changing its rating; review without re-watching). Season and series rollups are computed from the episode data.
- **The Ledger** — a customizable stats dashboard (~19 widgets covering counts, rating distribution, viewing timeline, genres, auteurs/ensemble cast, runtime, language, and more), rendered with custom CSS visuals. Widgets are drag-reordered, resized, and duplicated from a palette; layout is saved per user.
- **Re-watch timeline** — every viewing is its own dated entry per title.
- **Cast, crew & franchise info** — TMDB cast/crew per title and season, plus a "other movies in this franchise" section with watched-progress tracking.
- **Cinema Outings** — log a booked movie trip ("I've got tickets") and it moves itself from watchlist to watched: Up Next leads with a countdown-to-showtime marquee, the show auto-completes into a viewing (theater, companions, format) when it lets out, and a "how was it?" prompt follows with rating, notes, and a friend recommendation. Add-to-calendar `.ics`, plan-sharing with friends, and a "didn't make it" undo round out the flow.
- **Friends & social** — invite-only accounts (each account can issue a capped number of invites); friend requests, a friend activity feed, sent recommendations, per-title comments/reactions, and in-app notifications.
- **Where to watch** — TMDB watch-provider listings, plus a personal "in my home collection" toggle and a physical media shelf (DVD/Blu-ray/4K UHD/etc., with edition notes).
- **Import** — bring in watch history/ratings from Letterboxd CSV exports (watched, ratings, diary, watchlist), matched to TMDB by name + year.
- **Auth** — passkey / WebAuthn via Supabase Auth.
- **Shareable read-only links** — time-bound, scope-configurable access tokens let others browse your library without editing it.
- **Offline-first PWA** — installable, with a service worker caching the app shell, posters, and fonts.
- **Import / export** — back up or move your library as JSON.

---

## Tech stack

| Layer | Choice |
|-------|--------|
| Build / framework | Vite + React 19 + TypeScript |
| Styling | Tailwind CSS v3 + shadcn-ui (Radix primitives) |
| State | Zustand (`library` / `ledger` / `ui` slices) with `persist` to localStorage |
| Backend | Supabase — Postgres + Row Level Security + Auth |
| Metadata APIs | TMDB (posters/details) + OMDb (IMDb/RT/Metacritic) via a Supabase Edge Function proxy |
| Icons | lucide-react |
| Deploy | GitHub Actions → GitHub Pages |
| Native client | Android — Kotlin + Jetpack Compose (`android/`, see [Android app](#android-app)) |

---

## Project structure

```
src/
  components/
    ui/                  # Atomic shadcn components (button, dialog, star-rating, dynamic-poster, …)
    AddTitleWorkflow.tsx # Search → log form → optimistic add
    TitleDetailDrawer.tsx# Title detail + per-episode logging + viewing log + cast/franchise
    OutingScheduleSheet.tsx / ShareOutingPanel.tsx  # Cinema Outings scheduling + plan sharing
    NotificationCenter.tsx / SendRecommendationPanel.tsx / TitleCommentsPanel.tsx  # Social layer
    TopBar.tsx / BottomNav.tsx / ProfileModal.tsx
  views/
    Library.tsx          # Poster wall + ledger list
    Discover.tsx         # TMDB search + trending + recommendation carousels
    UpNext.tsx           # Watchlist + upcoming Cinema Outings
    Friends.tsx          # Friend requests, activity feed, invites
    Profile.tsx          # Auth, account, data import/export, about
    Ledger.tsx / ledger/ # Editable stats dashboard shell + ~19 widget panels (ledger/panels/)
  store/
    useAppStore.ts       # Zustand store (filters, CRUD, logEpisode, auth/library loading)
    mockData.ts          # Type definitions + seed data (drives logged-out view)
    episodeUtils.ts      # Rating/watch rollups (episode → season → series)
    ledgerStats.ts       # Ledger stat computation
  lib/
    auth.ts              # Supabase client + passkey/WebAuthn helpers
    db.ts                # All Supabase reads/writes (titles, seasons, episodes, viewings, social)
    media.ts             # TMDB/OMDb/Wikidata fetch helpers (via the media-proxy Edge Function)
    export-import.ts     # JSON backup/restore
    letterboxd-import.ts # Letterboxd CSV import (parser + TMDB matcher)
schema.sql               # Canonical DB schema + RLS policies (human-readable copy)
supabase/
  migrations/            # Versioned migrations applied by CI (see "Database" below)
  functions/media-proxy/ # Edge Function: TMDB/OMDb proxy + cache (keeps API keys server-side)
android/                 # Native Kotlin/Compose client — see "Android app" below
scripts/
  migrate-from-v1.mjs    # One-off importer from "The Projection Room" v1 JSON
  verify-episode-logic.mjs
.github/workflows/
  deploy.yml             # Build + deploy to GitHub Pages
  db-migrate.yml         # supabase db push on migration changes
  deploy-functions.yml   # Deploy the media-proxy Edge Function on change
  android.yml            # Android build/lint/test
```

---

## Local development

### Prerequisites
- Node.js (project uses Node 22.x locally)
- A Supabase project (for auth + persistence; the app also runs read-only on seed data without one)

### Setup
1. Install dependencies:
   ```bash
   npm install
   ```
2. Create `.env.local` with your Supabase project's public values:
   ```
   VITE_SUPABASE_URL=https://<your-ref>.supabase.co
   VITE_SUPABASE_ANON_KEY=<your-anon-key>
   ```
   > TMDB/OMDb API keys are **not** here — they live server-side as secrets on the `media-proxy` Edge Function, so they never reach the browser.
3. Run the dev server:
   ```bash
   npm run dev        # http://localhost:5173
   ```

### Commands
```bash
npm run dev        # Start dev server (HMR)
npm run build      # Type-check (tsc -b) + production build → dist/
npm run preview    # Preview the production build locally
npm run lint       # ESLint
```

---

## Database

The schema covers core library/episode tracking, cast & crew, sharing, and a social layer:

- **`titles`** — movies and TV series (`type` enum: `movie` | `tv`)
- **`seasons`** / **`episodes`** — TV season and episode rows (episodes unique per `title + season + episode number`)
- **`episode_watch_events`** / **`episode_ratings`** / **`episode_reviews`** — independent, timestamped logs per episode
- **`viewings`** — re-watch timeline entries per title (venue/companions/outing carried for cinema trips)
- **`cinema_outings`** — a booked movie trip; `complete_due_outings()` auto-transitions it to a logged viewing once showtime + runtime passes
- **`title_cast`** / **`title_crew`** / **`season_cast`** / **`episode_crew`** — cached TMDB credit metadata
- **`shared_access_keys`** / **`share_scopes`** — time-bound, scope-configurable read-only access tokens
- **`profiles`** / **`friendships`** / **`invite_codes`** / **`invite_redeem_attempts`** — invite-only accounts and the friend graph
- **`recommendations`** / **`title_comments`** / **`title_reactions`** / **`notifications`** — the social/activity layer
- **`user_prefs`** / **`user_title_pins`** — per-user Ledger layout and pinned titles
- **`sync_tombstones`** — deletion log consumed by the Android client's incremental sync
- **`api_cache`** — used by the `media-proxy` Edge Function

**Row Level Security:** the authenticated owner gets full CRUD on their rows; holders of a valid shared token get read-only access (scoped by `share_scopes`) via the `app.shared_token` session setting.

`schema.sql` is the canonical, human-readable copy of the full schema and RLS policies — the table list above is its shape, not a substitute for it. `db.ts` reads/writes through the Supabase client and maps DB rows ⇄ the client `Title` type (episodes are grouped onto their seasons on the way in).

### Migrations (automated — no manual SQL)

Schema changes are versioned under `supabase/migrations/` and applied by CI. The baseline migration (`20260620084847_initial_schema.sql`) captures the current schema and is already marked **applied** on the remote.

**To change the schema:**
1. Add a new file under `supabase/migrations/`, named with a UTC timestamp prefix, e.g. `20260701120000_add_favorite_flag.sql`, containing just the `ALTER`/`CREATE`/etc. for the change.
2. Keep `schema.sql` in sync as the readable canonical copy.
3. Commit and push to `main`. The **DB Migrate** workflow runs `supabase db push` and applies any pending migrations to the live database.

The workflow needs these set in **GitHub → Settings → Secrets and variables → Actions (Repository scope)**:

| Name | Kind | Source |
|------|------|--------|
| `SUPABASE_ACCESS_TOKEN` | secret | supabase.com → Account → Access Tokens |
| `SUPABASE_DB_PASSWORD` | secret | Project → Settings → Database |
| `SUPABASE_PROJECT_REF` | variable | Project reference id |

> Working with migrations locally: `supabase db push` and `supabase migration repair` connect directly to the remote and need no Docker. Only `supabase db pull` (which dumps the schema with a version-matched `pg_dump`) requires Docker Desktop running.

---

## Deployment

Pushing to `main` triggers `.github/workflows/deploy.yml`, which builds the app (injecting `VITE_SUPABASE_URL` / `VITE_SUPABASE_ANON_KEY` from repository secrets) and publishes `dist/` to GitHub Pages.

The site is served from a custom domain (`public/CNAME`) at the domain root, so Vite's `base` is `/`. SPA deep links are handled by a `404.html` redirect fallback.

---

## Android app

`android/` is a native Kotlin + Jetpack Compose client (module layout: `app`, `core:model`, `core:designsystem`, `core:database`, `data`, `feature:{auth,library,discover,upnext,ledger,settings}`) sharing the same Supabase project as the web app, with a Room-backed local database and an outbox-based incremental sync layer (`sync_tombstones`, see `docs/android-sync-contract.md`). It is pre-distribution — not yet published to Play — and tracks feature parity with the web app domain by domain; see `docs/android-parity-matrix.md` and `docs/android-implementation-status.md` for current status, and `docs/android-contracts/` for the per-domain field/RLS/fixture contracts it's built against. `.github/workflows/android.yml` builds, lints, and unit-tests it in CI.

---

## Design system

- **Colors:** Void `#0b0907` (background), Amber `#e9b266` (highlights/accents), with ink surfaces, a cool "moon" blue, and ember/paper tones.
- **Fonts:** `Fraunces` (serif titles), `Hanken Grotesk` (UI sans), `DM Mono` (stats/numbers).
- **Atmosphere:** `.projector-beam` (flickering amber glow), `.dust`, `.vignette`, and a `.grain` overlay rendered once as fixed full-viewport siblings.
- **Mobile-first:** bottom-sheet modals on mobile, `TopBar` + `BottomNav` shell.

---

## Credits

A personal project — the successor to "The Projection Room" v1. Metadata from [TMDB](https://www.themoviedb.org/) and [OMDb](https://www.omdbapi.com/). Not endorsed or certified by either.
