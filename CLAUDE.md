# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**CinemArchive (The Projection Room v2)** is a personal movie and TV series tracking app with a cinematic dark-gold aesthetic. It is a JAMstack app: static React frontend deployed to GitHub Pages, backed by Supabase for database and auth, and TMDB/OMDb for media metadata.

> **Status:** Built and deployed. All phases from `plan.md` are implemented and the app is live on GitHub Pages (https://shakrunk.github.io/CinemArchive/), backed by a connected Supabase project. The phase table at the bottom is retained as historical context. See `README.md` for full setup/architecture docs.

---

## Commands

Standard Vite commands:

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
- **Deploy:** GitHub Actions → GitHub Pages (`.github/workflows/deploy.yml`)
- **DB migrations:** Supabase CLI migrations applied by `.github/workflows/db-migrate.yml`

### Source Layout (planned)
```
src/
  components/
    ui/              # Atomic components (Phase 1, Track B)
    AddTitleWorkflow.tsx
  views/
    Library.tsx      # Poster wall + sortable ledger list (Track D)
    Ledger.tsx       # Stats dashboards (Track E)
  store/
    useAppStore.ts   # Zustand store
    mockData.ts      # Dummy data used until Phase 3 wires real API
  lib/
    auth.ts          # Passkey/WebAuthn helpers
schema.sql           # Supabase DB schema + RLS policies (Track A)
```

### Data Model (schema.sql)
Relational tables replace V1's JSON schema:
- `titles` — movies and TV series (enum: `movie` | `tv`)
- `seasons` — TV season relations
- `episodes` — individual episodes (unique per `title + season + episode number`)
- `episode_watch_events` / `episode_ratings` / `episode_reviews` — independent, timestamped per-episode logs (decoupled: watch ≠ rate ≠ review)
- `viewings` — re-watch timeline entries per title
- `shared_access_keys` — time-bound read-only access tokens
- `api_cache` — used by the `media-proxy` Edge Function

RLS: authenticated user gets full CRUD; valid shared-token holders get read-only. `db.ts` maps DB rows ⇄ the client `Title` type (episodes are grouped onto their seasons).

### Key Patterns
- **Optimistic UI:** Zustand store is updated immediately on user action; backend write follows asynchronously (fire-and-forget with error logging in `db.ts`).
- **All filtering/sorting is client-side** in the Zustand store (no DB queries for filter changes); the poster wall renders the full filtered set (grid virtualization was planned but is not implemented).
- **API calls to TMDB/OMDb go through the Edge Function** (never directly from the browser — keeps API keys server-side).
- **SPA routing on GitHub Pages:** uses a `404.html` redirect fallback; Vite `base` is `/CinemArchive/`.
- **Schema changes go through migrations, not the SQL editor:** add a timestamped file under `supabase/migrations/`, keep `schema.sql` in sync, and push to `main` — `db-migrate.yml` runs `supabase db push`. (`db push`/`migration repair` are Docker-free; only `db pull` needs Docker.)

---

## Design System

- **Colors:** Void `#0b0907` (background), Amber `#e9b266` (highlights/accents)
- **Fonts:** `Fraunces` (serif titles), `Hanken Grotesk` (UI sans), `DM Mono` (stats/numbers)
- **Atmospheric CSS** in `index.css`, rendered once in `App.tsx` as fixed full-viewport siblings: `.grain` noise overlay, `.vignette`, `.dust`, `.projector-beam` glow animation
- **Mobile-first:** bottom-sheet modals for mobile, `TopBar` + `BottomNav` shell

---

## Development Phases (from plan.md)

| Phase | Parallelism | Key Outputs |
|-------|-------------|-------------|
| 0 | Sequential | Git init, Vite scaffold, shadcn, Tailwind theme, mockData, ESLint/Prettier |
| 1 (A/B/C) | Parallel | DB schema+RLS, UI component library, Zustand store |
| 2 (D/E/F) | Parallel | Library view, Ledger view, AddTitle workflow |
| 3 | Sequential | Replace mock data with real Supabase calls, auth wiring, PWA |
| 4 | Sequential | GitHub Actions deploy pipeline |

Phases 1 and 2 are designed for parallel sub-agent execution. Phases 0, 3, and 4 must be sequential to avoid merge conflicts.
