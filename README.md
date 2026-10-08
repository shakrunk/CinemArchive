# CinemArchive — The Projection Room

A personal movie and TV series tracking app with a cinematic dark-gold aesthetic. Search TMDB, log what you watch (down to the individual episode), rate and review, and browse your library as a poster wall or pore over your viewing stats in **The Ledger**.

**Live (web):** https://cinemarchive.kumarfamilynet.work/

It's a JAMstack app: a static React frontend on GitHub Pages, backed by a shared Supabase project (Postgres + Auth + Edge Functions), with TMDB and OMDb for metadata and rating badges. A native Android client shares the same backend.

---

## Documentation

**📖 [The wiki](https://github.com/shakrunk/CinemArchive/wiki) is the place to start** — architecture, local setup for both clients, the security model, the release process, troubleshooting, and a glossary.

| Looking for | Go to |
|-------------|-------|
| How the pieces fit together, and why | [Architecture](https://github.com/shakrunk/CinemArchive/wiki/Architecture) |
| Running either client locally | [Getting Started](https://github.com/shakrunk/CinemArchive/wiki/Getting-Started) |
| Changing the schema | [Database Migrations](https://github.com/shakrunk/CinemArchive/wiki/Database-Migrations) |
| RLS, share tokens, secrets handling | [Security Model](https://github.com/shakrunk/CinemArchive/wiki/Security-Model) |
| Shipping a release | [Deployment & Releases](https://github.com/shakrunk/CinemArchive/wiki/Deployment-and-Releases) |
| Current release gates and remaining blockers | [Release readiness](docs/release-readiness.md) |
| Something is broken | [Troubleshooting](https://github.com/shakrunk/CinemArchive/wiki/Troubleshooting) |
| Which doc owns which fact | [Documentation Map](https://github.com/shakrunk/CinemArchive/wiki/Documentation-Map) |

The wiki **explains and navigates**; anything normative lives in this repository — `schema.sql`, `CHANGELOG.md`, `docs/`, and the `AGENTS.md` convention files. Where they disagree, the repo wins.

---

## Clients

| Client | Location | Notes |
|--------|----------|-------|
| Web | [`apps/web/`](apps/web/README.md) | Vite + React + TypeScript, deployed to GitHub Pages. See [apps/web/README.md](apps/web/README.md) for its stack, project structure, and local dev setup. |
| Android | `apps/android/` | Kotlin + Jetpack Compose (module layout: `app`, `core:model`, `core:designsystem`, `core:database`, `data`, `feature:{auth,library,discover,upnext,ledger,lists,settings}`). Room-backed local database with an outbox-based incremental sync layer (`sync_tombstones`, see `docs/android-sync-contract.md`). Pre-distribution (not yet published to Play; signed APKs are attached to each GitHub Release for sideloading). Tracks feature parity with the web app domain by domain — see `docs/android-parity-matrix.md` and `docs/android-implementation-status.md`, and `docs/android-contracts/` for the per-domain field/RLS/fixture contracts. |

Each client has its own nested `AGENTS.md` with client-specific guidance for AI coding agents, layered on top of the repo-root [AGENTS.md](AGENTS.md) (each `CLAUDE.md` just imports its `AGENTS.md` for Claude Code).

---

## Features

- **Library** — poster wall + sortable list view, with client-side search, filtering (type, status, genre, tag, network, decade, rating), and sorting.
- **Custom lists** — group titles into your own named lists (a marathon, a ranked shortlist, a movie-night pile). Create a list from the Lists view or on the fly from a title's "Add to list" sheet, toggle a title into any number of lists, and open a list to browse its poster grid or remove titles. Lists are independent of watch status (a title can be on the watchlist *and* in several lists) and private to the owner (sharing with friends is not built yet). Available on web and Android.
- **Command palette (⌘K / Ctrl+K)** — jump to any title or fire an action (add a title, switch view, change layout) from the keyboard; ↑/↓ to move, Enter to run, Esc to close.
- **Deep links & back button** — the active view and the open title live in the URL, so a refresh restores where you were, titles are linkable, and the browser/mobile back button closes an open drawer instead of leaving the app.
- **Episode-level TV tracking** — each season expands into episodes; log watch events, ratings, and reviews per episode, all decoupled (re-watch an episode without changing its rating; review without re-watching). Season and series rollups are computed from the episode data.
- **The Ledger** — a customizable stats dashboard (~19 widgets covering counts, rating distribution, viewing timeline, genres, auteurs/ensemble cast, runtime, language, and more), rendered with custom CSS visuals. Widgets are drag-reordered, resized, and duplicated from a palette; layout is saved per user.
- **Re-watch timeline** — every viewing is its own dated entry per title.
- **Specials, next episode & episode cast** — TMDB's "Specials" season appears after the main seasons (loggable and reviewable, but excluded from series progress and Up Next); a show's detail view calls out its next scheduled episode and air date; a TV episode's guest and regular cast can be browsed per episode.
- **Cast, crew & franchise info** — TMDB cast/crew per title and season, plus a "other movies in this franchise" section with watched-progress tracking.
- **Discover** — TMDB search, trending, and "Because You Watched" / "More Starring" carousels; rich title previews with trailers, streaming/rental/purchase options, expandable cast and crew, and browse-by-person filmographies.
- **Critical Record** — normalized personal ratings (z-scores and percentile ranks) against your whole library or separate film/series baselines, on web and Android.
- **Themes** — dark, light, a "System" mode that follows your OS, and the Spider-Noir and Matrix easter-egg themes.
- **Cinema Outings** — log a booked movie trip ("I've got tickets") and it moves itself from watchlist to watched: Up Next leads with a countdown-to-showtime marquee, the show auto-completes into a viewing (theater, companions, format) when it lets out, and a "how was it?" prompt follows with rating, notes, and a friend recommendation. Add-to-calendar `.ics`, plan-sharing with friends, and a "didn't make it" undo round out the flow.
- **Friends & social** — invite-only accounts (each account can issue a capped number of invites); friend requests, a friend activity feed, sent recommendations, per-title comments/reactions, and in-app notifications.
- **Where to watch** — TMDB watch-provider listings, plus a personal "in my home collection" toggle and a physical media shelf (DVD/Blu-ray/4K UHD/etc., with edition notes).
- **Import** — bring in watch history/ratings from Letterboxd CSV exports (watched, ratings, diary, watchlist), matched to TMDB by name + year.
- **Keyboard-first** — numbered view switching, `Ctrl/Cmd+,` for Settings, and a shortcuts help dialog alongside the command palette.
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
- **`supabase/functions/redeem-invite/`** — Edge Function that creates an account server-side from an invite code (accounts are invite-only).

The schema covers core library and episode tracking (`titles`, `seasons`, `episodes`, the three independent `episode_watch_events` / `episode_ratings` / `episode_reviews` logs, `viewings`), cinema outings, custom lists (`lists` / `list_items`, owner-only), cached TMDB credits, scoped read-only sharing, invite-only accounts and the friend/social graph, per-user preferences, and the `sync_tombstones` deletion log the Android client's incremental sync consumes.

**Row Level Security:** the authenticated owner gets full CRUD on their rows; holders of a valid shared token get read-only access (scoped by `share_scopes`) via the `app.shared_token` session setting.

Read [`schema.sql`](schema.sql) for the authoritative tables, columns and policies, or the wiki's [Backend & Database](https://github.com/shakrunk/CinemArchive/wiki/Backend-and-Database) page for a walkthrough of the tables, the RPC surface, and the Edge Functions.

### Changing the schema (automated — no manual SQL)

1. Add a new file under `supabase/migrations/`, named with a UTC timestamp prefix, e.g. `20260701120000_add_favorite_flag.sql`, containing just the `ALTER`/`CREATE`/etc. for the change.
2. Keep `schema.sql` in sync as the readable canonical copy.
3. Merge the reviewed release PR into `main`. The deployment workflow validates and builds the web app, applies pending migrations, then publishes the web artifact. The separate **DB Migrate (manual)** workflow (`gh workflow run db-migrate.yml --ref main`) remains available for an explicitly planned operations/recovery run; merging a release normally does not require it.

The workflow needs these set in **GitHub → Settings → Secrets and variables → Actions (Repository scope)**:

| Name | Kind | Source |
|------|------|--------|
| `SUPABASE_ACCESS_TOKEN` | secret | supabase.com → Account → Access Tokens |
| `SUPABASE_DB_PASSWORD` | secret | Project → Settings → Database |
| `SUPABASE_PROJECT_REF` | variable | Project reference id |

> Working with migrations locally: `supabase db push` and `supabase migration repair` connect directly to the remote and need no Docker. Only `supabase db pull` (which dumps the schema with a version-matched `pg_dump`) requires Docker Desktop running.

Full workflow, including how migrations reach production and the failure modes: [Database Migrations](https://github.com/shakrunk/CinemArchive/wiki/Database-Migrations).

---

## Deployment & release

`.github/workflows/web.yml` validates every PR into `dev`/`main` with typecheck, a lint warning budget, unit tests, a production dependency audit, and build. `.github/workflows/deploy.yml` runs only for `main`: reuses those checks, builds the production web artifact, applies pending Supabase migrations, and publishes to GitHub Pages. It then tags a `vX.Y.Z` GitHub Release from the root `package.json` version and `CHANGELOG.md`, and — for a genuinely new release — builds and attaches a signed Android release APK. Android build failures can still leave a partial release; [release readiness](docs/release-readiness.md) records that and the remaining launch gates. `.github/workflows/deploy-functions.yml` deploys `supabase/functions/**` independently on change. See [AGENTS.md](AGENTS.md#versioning) for versioning/release policy and the wiki's [Deployment & Releases](https://github.com/shakrunk/CinemArchive/wiki/Deployment-and-Releases) for background.

---

## Contributing

Conventions are strict and written down: verification gates, Conventional Commits, branch topology, and the versioning policy live in [AGENTS.md](AGENTS.md) (authoritative), with a walkthrough in [CONTRIBUTING.md](CONTRIBUTING.md) and the wiki's [Contributing](https://github.com/shakrunk/CinemArchive/wiki/Contributing) page.

Security issues: please don't open a public issue — see [SECURITY.md](SECURITY.md).

---

## Credits

A personal project — the successor to "The Projection Room" v1. Metadata from [TMDB](https://www.themoviedb.org/) and [OMDb](https://www.omdbapi.com/). Not endorsed or certified by either.
