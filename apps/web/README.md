# apps/web — CinemArchive web app

The web client for CinemArchive. See the [repo-root README](../../README.md) for the
product overview, feature list, shared backend (Supabase schema/migrations), and
deployment/release process — this file covers only what's specific to the web app itself.

**Live:** https://cinemarchive.kumarfamilynet.work/

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

---

## Project structure

```
apps/web/
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
  scripts/
    migrate-from-v1.mjs    # One-off importer from "The Projection Room" v1 JSON
    verify-*.mjs           # Standalone logic-verification scripts
  index.html / vite.config.ts / tsconfig*.json / eslint.config.js / tailwind.config.js / …

# One level up (repo root, shared across clients):
../../schema.sql               # Canonical DB schema + RLS policies (human-readable copy)
../../supabase/
  migrations/                  # Versioned migrations applied by CI
  functions/media-proxy/       # Edge Function: TMDB/OMDb proxy + cache (keeps API keys server-side)
../../.github/workflows/
  deploy.yml                   # Build + deploy to GitHub Pages (+ release + Android APK)
  db-migrate.yml               # supabase db push on migration changes
```

The app version shown in Settings → About (`__APP_VERSION__`) comes from the **repo-root**
`package.json`, not this directory's `package.json` — see `vite.config.ts` and the root
`CLAUDE.md`'s Versioning section.

---

## Local development

### Prerequisites
- Node.js (project uses Node 22.x locally)
- A Supabase project (for auth + persistence; the app also runs read-only on seed data without one)

### Setup
1. From `apps/web/`, install dependencies:
   ```bash
   npm install
   ```
2. Create `apps/web/.env.local` with your Supabase project's public values:
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
npm run test        # Vitest
```

---

## Design system

- **Colors:** Void `#0b0907` (background), Amber `#e9b266` (highlights/accents), with ink surfaces, a cool "moon" blue, and ember/paper tones.
- **Fonts:** `Fraunces` (serif titles), `Hanken Grotesk` (UI sans), `DM Mono` (stats/numbers).
- **Atmosphere:** `.projector-beam` (flickering amber glow), `.dust`, `.vignette`, and a `.grain` overlay rendered once as fixed full-viewport siblings.
- **Mobile-first:** bottom-sheet modals on mobile, `TopBar` + `BottomNav` shell.
