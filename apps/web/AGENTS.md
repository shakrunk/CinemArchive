# AGENTS.md (apps/web)

Web-app-specific guidance for AI coding agents. Auto-discovered alongside the repo-root
[AGENTS.md](../../AGENTS.md) whenever the working directory is inside `apps/web/` — the
root file's Verification/Git/Branching/Versioning/Dependencies/RTK/code-review-graph
sections apply here too and aren't repeated below.

## Project Overview

**CinemArchive (The Projection Room v2)** is a personal movie and TV series tracking app with a cinematic dark-gold aesthetic. It is a JAMstack app: static React frontend deployed to GitHub Pages, backed by Supabase for database and auth, and TMDB/OMDb for media metadata.

> **Status:** Built and deployed. The app is live on GitHub Pages (https://cinemarchive.kumarfamilynet.work/, a custom domain), backed by a connected Supabase project. See the repo-root `README.md` and `apps/web/README.md` for full setup/architecture docs.

---

## Commands

Standard Vite commands, run from `apps/web/`:

```bash
npm run dev        # Start dev server
npm run build      # Production build → dist/
npm run preview    # Preview production build locally
npm run lint       # ESLint
```

---

## Architecture

### Stack
- **Frontend:** Vite + React + TypeScript + Tailwind CSS
- **UI Components:** shadcn-ui (pre-configured in Phase 0)
- **State:** Zustand (`src/store/useAppStore.ts`) — slices for `library`, `ledger`, `ui`
- **Backend:** Supabase (Postgres + RLS + Auth + Edge Functions)
- **APIs:** TMDB (metadata/posters) + OMDb (IMDb/RT/Metacritic badges) — both proxied through a Supabase Edge Function with a caching layer
- **Auth:** Passkey/WebAuthn via Supabase Auth (`src/lib/auth.ts`)
- **Ledger visuals:** custom CSS visualizations (Recharts was evaluated and dropped — no charting lib bundled)
- **PWA:** vite-plugin-pwa
- **Deploy:** GitHub Actions → GitHub Pages (`.github/workflows/deploy.yml`, at the repo root)
- **DB migrations:** Supabase CLI migrations applied by `.github/workflows/db-migrate.yml` (repo root), against `supabase/migrations/` (repo root, shared across clients)
- **Edge Function deploy:** `media-proxy` deployed by `.github/workflows/deploy-functions.yml` (triggers on `supabase/functions/**` changes, repo root). Functions deploy independently of migrations — editing `index.ts` without deploying leaves the live function stale.

### Source Layout
```
apps/web/
  src/
    components/
      ui/              # Atomic components
      AddTitleWorkflow.tsx
    views/
      Library.tsx      # Poster wall + sortable ledger list
      Discover.tsx     # TMDB search, trending, recommendation carousels
      UpNext.tsx       # Watchlist, continue watching, Cinema Outings
      Lists.tsx        # Custom title lists (grid + detail)
      Friends.tsx      # Friend requests, activity feed, invites
      Profile.tsx      # Account, sharing, import/export, about
      Ledger.tsx / ledger/  # Stats dashboard shell + widget panels
    store/
      useAppStore.ts   # Zustand store
      outings.ts       # Cinema Outings logic
      upNext.ts / episodeUtils.ts / ledgerDerive.ts  # Derivations and rollups
      mockData.ts      # Type definitions + dummy data for the logged-out view
    lib/
      auth.ts          # Passkey/WebAuthn helpers
      db.ts            # All Supabase reads/writes
  scripts/             # migrate-from-v1.mjs, verify-*.mjs
  index.html / vite.config.ts / tsconfig*.json / etc.
schema.sql             # Supabase DB schema + RLS policies (repo root, shared)
```

The app version (`__APP_VERSION__`, shown in Settings → About) is read by `vite.config.ts`
from the **repo-root** `package.json` (`../../package.json`), not this directory's own
`package.json` — see the root AGENTS.md's Versioning section. `apps/web/package.json` keeps
its own `version` field only because npm requires one; it is not authoritative.

### Data Model (schema.sql)
Relational tables replace V1's JSON schema:
- `titles` — movies and TV series (enum: `movie` | `tv`)
- `seasons` — TV season relations
- `episodes` — individual episodes (unique per `title + season + episode number`)
- `episode_watch_events` / `episode_ratings` / `episode_reviews` — independent, timestamped per-episode logs (decoupled: watch ≠ rate ≠ review)
- `viewings` — re-watch timeline entries per title (`venue`/`companions`/`outing_id` columns carry a cinema trip's theater and company, whether logged manually or via a completed outing)
- `cinema_outings` — a booked movie trip (showtime, venue, companions, format, ticket price, seat); `complete_due_outings()` auto-transitions it `scheduled → completed` (logging a `viewings` row and flipping the title to `watched`) once `showtime + previews + runtime` passes; owner-only, never shared
- `lists` / `list_items` — user-created custom title lists (many-to-many with `titles`, independent of watch status); owner-only RLS, never shared
- `shared_access_keys` — time-bound read-only access tokens (scoped via `share_scopes`)
- Social tables — friendships, recommendations, title comments/reactions, notifications, invite codes (see `schema.sql`)
- `user_prefs` — per-user settings such as the saved Ledger layout
- `api_cache` — used by the `media-proxy` Edge Function

RLS: authenticated user gets full CRUD; valid shared-token holders get read-only. `db.ts` maps DB rows ⇄ the client `Title` type (episodes are grouped onto their seasons).

### Key Patterns
- **Optimistic UI:** Zustand store is updated immediately on user action; backend write follows asynchronously (fire-and-forget with error logging in `db.ts`).
- **All filtering/sorting is client-side** in the Zustand store (no DB queries for filter changes); flat and franchise-grouped poster grids and library lists use windowed rendering while preserving the full filtered data set.
- **API calls to TMDB/OMDb go through the Edge Function** (never directly from the browser — keeps API keys server-side).
- **SPA routing on GitHub Pages:** uses a `404.html` redirect fallback; served from a custom domain (`public/CNAME`) at the domain root, so Vite `base` is `/`.
- **Schema changes go through migrations, not the SQL editor:** add a timestamped file under `supabase/migrations/` (repo root), keep `schema.sql` (repo root) in sync, and merge a reviewed release PR into `main`. `deploy.yml` validates/builds the web artifact, runs `supabase db push`, then deploys Pages. The separate `db-migrate.yml` is `workflow_dispatch`-only and remains an explicit operations/recovery path. See `docs/release-readiness.md` for current gates and limitations. (`db push`/`migration repair` are Docker-free; only `db pull` needs Docker.)

---

## Design System

- **Colors:** Void `#0b0907` (background), Amber `#e9b266` (highlights/accents)
- **Fonts:** `Fraunces` (serif titles), `Hanken Grotesk` (UI sans), `DM Mono` (stats/numbers)
- **Atmospheric CSS** in `index.css`, rendered once in `App.tsx` as fixed full-viewport siblings: `.grain` noise overlay, `.vignette`, `.dust`, `.projector-beam` glow animation
- **Mobile-first:** bottom-sheet modals for mobile, `TopBar` + `BottomNav` shell

---

## Further reading

The wiki's [Web App](https://github.com/shakrunk/CinemArchive/wiki/Web-App) page expands on the
above — store slices, the Ledger panel catalogue, testing, and the gotchas — and
[Design System](https://github.com/shakrunk/CinemArchive/wiki/Design-System) holds the full token
reference. The original phased build plan is recorded under
[Web App § Project history](https://github.com/shakrunk/CinemArchive/wiki/Web-App#project-history).
