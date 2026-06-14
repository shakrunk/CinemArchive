# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**CinemArchive (The Projection Room v2)** is a personal movie and TV series tracking app with a cinematic dark-gold aesthetic. It is a JAMstack app: static React frontend deployed to GitHub Pages, backed by Supabase for database and auth, and TMDB/OMDb for media metadata.

> **Status:** Pre-scaffold. Only `plan.md` exists. Phase 0 must run before any other code is written.

---

## Commands

Once scaffolded (Phase 0 complete), standard Vite commands apply:

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
- **Charting:** Recharts or Chart.js (chosen during Track E)
- **PWA:** vite-pwa plugin (Phase 3)
- **Deploy:** GitHub Actions → `gh-pages` branch (Phase 4)

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
Four relational tables replace V1's JSON schema:
- `titles` — movies and TV series (enum: `movie` | `tv`)
- `seasons` — TV season relations
- `viewings` — re-watch timeline entries per title
- `shared_access_keys` — time-bound read-only access tokens

RLS: authenticated user gets full CRUD; `shared_access_keys` holders get read-only.

### Key Patterns
- **Optimistic UI:** Zustand store is updated immediately on user action; backend write follows asynchronously.
- **Virtualized grid:** Poster wall uses virtualization for large libraries.
- **All filtering/sorting is client-side** in the Zustand store (no DB queries for filter changes).
- **API calls to TMDB/OMDb go through the Edge Function** (never directly from the browser — keeps API keys server-side).
- **SPA routing on GitHub Pages:** requires a `404.html` fallback hack (handled in Phase 4).

---

## Design System

- **Colors:** Void `#0b0907` (background), Amber `#e9b266` (highlights/accents)
- **Fonts:** `Fraunces` (serif titles), `Hanken Grotesk` (UI sans), `DM Mono` (stats/numbers)
- **Atmospheric CSS** in `globals.css`: `.grain` noise overlay, `.vignette`, `.projector-beam` header animation
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
