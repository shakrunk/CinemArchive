# CinemArchive — The Projection Room

A personal movie and TV series tracking app with a cinematic dark-gold aesthetic. Search TMDB, log what you watch (down to the individual episode), rate and review, and browse your library as a poster wall or pore over your viewing stats in **The Ledger**.

**Live (web):** https://cinemarchive.kumarfamilynet.work/

It's a JAMstack app: a static React frontend on GitHub Pages, backed by a shared Supabase project (Postgres + Auth + Edge Functions), with TMDB and OMDb for metadata and rating badges. A native Android client shares the same backend.

---

## Clients

| Client | Location | Notes |
|--------|----------|-------|
| Web | [`apps/web/`](apps/web/README.md) | Vite + React + TypeScript, deployed to GitHub Pages. See [apps/web/README.md](apps/web/README.md) for its stack, project structure, and local dev setup. |
| Android | `apps/android/` | Kotlin + Jetpack Compose (module layout: `app`, `core:model`, `core:designsystem`, `core:database`, `data`, `feature:{auth,library,discover,upnext,ledger,settings}`). Room-backed local database with an outbox-based incremental sync layer (`sync_tombstones`, see `docs/android-sync-contract.md`). Pre-distribution (not yet published to Play). Tracks feature parity with the web app domain by domain — see `docs/android-parity-matrix.md` and `docs/android-implementation-status.md`, and `docs/android-contracts/` for the per-domain field/RLS/fixture contracts. |

Each client has its own nested `CLAUDE.md` with client-specific guidance for Claude Code, layered on top of the repo-root [CLAUDE.md](CLAUDE.md).

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

## Shared backend

The Supabase project, schema, and Edge Functions are genuinely shared infrastructure — inputs to every client, not owned by one:

- **`schema.sql`** — the canonical, human-readable copy of the full DB schema and RLS policies.
- **`supabase/migrations/`** — versioned migrations applied by CI. The baseline migration (`20260620084847_initial_schema.sql`) captures the schema as of the multi-client split and is already marked **applied** on the remote.
- **`supabase/functions/media-proxy/`** — Edge Function proxying TMDB/OMDb (keeps API keys server-side).

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

`schema.sql` is the canonical, human-readable copy of the full schema and RLS policies — the table list above is its shape, not a substitute for it.

### Changing the schema (automated — no manual SQL)

1. Add a new file under `supabase/migrations/`, named with a UTC timestamp prefix, e.g. `20260701120000_add_favorite_flag.sql`, containing just the `ALTER`/`CREATE`/etc. for the change.
2. Keep `schema.sql` in sync as the readable canonical copy.
3. Commit and push to `main`, then manually run the **DB Migrate (manual)** workflow (`gh workflow run db-migrate.yml --ref main`) — it does **not** trigger automatically on push.

The workflow needs these set in **GitHub → Settings → Secrets and variables → Actions (Repository scope)**:

| Name | Kind | Source |
|------|------|--------|
| `SUPABASE_ACCESS_TOKEN` | secret | supabase.com → Account → Access Tokens |
| `SUPABASE_DB_PASSWORD` | secret | Project → Settings → Database |
| `SUPABASE_PROJECT_REF` | variable | Project reference id |

> Working with migrations locally: `supabase db push` and `supabase migration repair` connect directly to the remote and need no Docker. Only `supabase db pull` (which dumps the schema with a version-matched `pg_dump`) requires Docker Desktop running.

---

## Deployment & release

`.github/workflows/deploy.yml` runs on push to `main`: applies pending Supabase migrations, builds and publishes the web app (`apps/web/`) to GitHub Pages, tags a `vX.Y.Z` GitHub Release from the root `package.json` version and `CHANGELOG.md`, and — for a genuinely new release — builds and attaches a signed Android release APK. `.github/workflows/deploy-functions.yml` deploys `supabase/functions/**` independently on change. See [CLAUDE.md](CLAUDE.md#versioning) for the versioning/release policy.

---

## Credits

A personal project — the successor to "The Projection Room" v1. Metadata from [TMDB](https://www.themoviedb.org/) and [OMDb](https://www.omdbapi.com/). Not endorsed or certified by either.
