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
- **Edge Function deploy:** `media-proxy` deployed by `.github/workflows/deploy-functions.yml` (triggers on `supabase/functions/**` changes). Functions deploy independently of migrations — editing `index.ts` without deploying leaves the live function stale.

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

<!-- rtk-instructions v2 -->
# RTK (Rust Token Killer) - Token-Optimized Commands

## Golden Rule

**Always prefix commands with `rtk`**. If RTK has a dedicated filter, it uses it. If not, it passes through unchanged. This means RTK is always safe to use.

**Important**: Even in command chains with `&&`, use `rtk`:
```bash
# ❌ Wrong
git add . && git commit -m "msg" && git push

# ✅ Correct
rtk git add . && rtk git commit -m "msg" && rtk git push
```

## RTK Commands by Workflow

### Build & Compile (80-90% savings)
```bash
rtk cargo build         # Cargo build output
rtk cargo check         # Cargo check output
rtk cargo clippy        # Clippy warnings grouped by file (80%)
rtk tsc                 # TypeScript errors grouped by file/code (83%)
rtk lint                # ESLint/Biome violations grouped (84%)
rtk prettier --check    # Files needing format only (70%)
rtk next build          # Next.js build with route metrics (87%)
```

### Test (60-99% savings)
```bash
rtk cargo test          # Cargo test failures only (90%)
rtk go test             # Go test failures only (90%)
rtk jest                # Jest failures only (99.5%)
rtk vitest              # Vitest failures only (99.5%)
rtk playwright test     # Playwright failures only (94%)
rtk pytest              # Python test failures only (90%)
rtk rake test           # Ruby test failures only (90%)
rtk rspec               # RSpec test failures only (60%)
rtk test <cmd>          # Generic test wrapper - failures only
```

### Git (59-80% savings)
```bash
rtk git status          # Compact status
rtk git log             # Compact log (works with all git flags)
rtk git diff            # Compact diff (80%)
rtk git show            # Compact show (80%)
rtk git add             # Ultra-compact confirmations (59%)
rtk git commit          # Ultra-compact confirmations (59%)
rtk git push            # Ultra-compact confirmations
rtk git pull            # Ultra-compact confirmations
rtk git branch          # Compact branch list
rtk git fetch           # Compact fetch
rtk git stash           # Compact stash
rtk git worktree        # Compact worktree
```

Note: Git passthrough works for ALL subcommands, even those not explicitly listed.

### GitHub (26-87% savings)
```bash
rtk gh pr view <num>    # Compact PR view (87%)
rtk gh pr checks        # Compact PR checks (79%)
rtk gh run list         # Compact workflow runs (82%)
rtk gh issue list       # Compact issue list (80%)
rtk gh api              # Compact API responses (26%)
```

### JavaScript/TypeScript Tooling (70-90% savings)
```bash
rtk pnpm list           # Compact dependency tree (70%)
rtk pnpm outdated       # Compact outdated packages (80%)
rtk pnpm install        # Compact install output (90%)
rtk npm run <script>    # Compact npm script output
rtk npx <cmd>           # Compact npx command output
rtk prisma              # Prisma without ASCII art (88%)
```

### Files & Search (60-75% savings)
```bash
rtk ls <path>           # Tree format, compact (65%)
rtk read <file>         # Code reading with filtering (60%)
rtk grep <pattern>      # Search grouped by file (75%). Format flags (-c, -l, -L, -o, -Z) run raw.
rtk find <pattern>      # Find grouped by directory (70%)
```

### Analysis & Debug (70-90% savings)
```bash
rtk err <cmd>           # Filter errors only from any command
rtk log <file>          # Deduplicated logs with counts
rtk json <file>         # JSON structure without values
rtk deps                # Dependency overview
rtk env                 # Environment variables compact
rtk summary <cmd>       # Smart summary of command output
rtk diff                # Ultra-compact diffs
```

### Infrastructure (85% savings)
```bash
rtk docker ps           # Compact container list
rtk docker images       # Compact image list
rtk docker logs <c>     # Deduplicated logs
rtk kubectl get         # Compact resource list
rtk kubectl logs        # Deduplicated pod logs
```

### Network (65-70% savings)
```bash
rtk curl <url>          # Compact HTTP responses (70%)
rtk wget <url>          # Compact download output (65%)
```

### Meta Commands
```bash
rtk gain                # View token savings statistics
rtk gain --history      # View command history with savings
rtk discover            # Analyze Claude Code sessions for missed RTK usage
rtk proxy <cmd>         # Run command without filtering (for debugging)
rtk init                # Add RTK instructions to CLAUDE.md
rtk init --global       # Add RTK to ~/.claude/CLAUDE.md
```

## Token Savings Overview

| Category | Commands | Typical Savings |
|----------|----------|-----------------|
| Tests | vitest, playwright, cargo test | 90-99% |
| Build | next, tsc, lint, prettier | 70-87% |
| Git | status, log, diff, add, commit | 59-80% |
| GitHub | gh pr, gh run, gh issue | 26-87% |
| Package Managers | pnpm, npm, npx | 70-90% |
| Files | ls, read, grep, find | 60-75% |
| Infrastructure | docker, kubectl | 85% |
| Network | curl, wget | 65-70% |

Overall average: **60-90% token reduction** on common development operations.
<!-- /rtk-instructions -->