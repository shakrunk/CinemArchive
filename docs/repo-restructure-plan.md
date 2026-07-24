# Repo Restructure Plan: Multi-Platform Monorepo Layout

**Status:** Proposed — not yet executed.
**Owner:** unassigned.
**Companion decision record:** [docs/adr/0002-multi-platform-repo-layout.md](adr/0002-multi-platform-repo-layout.md)

## Why this exists

The web app has lived at the repo root since before `android/` existed, so every path,
config file, and CI workflow implicitly assumes "repo root == the Vite web app." That
assumption is now wrong: `android/` is a real, actively developed sibling client (see
[android-implementation-status.md](android-implementation-status.md) and
[android-parity-matrix.md](android-parity-matrix.md)), and this plan exists because more
native clients (iOS, macOS, Linux, Windows) are anticipated. Left alone, every future
platform has to either awkwardly nest under a web-flavored root or get bolted on as
another oddly-placed top-level folder, and the asymmetry compounds each time.

This document is the full plan for correcting that: giving every platform client equal
standing as a sibling under `apps/`, while keeping the genuinely shared backend
(Supabase schema, migrations, Edge Functions) and shared docs at the true repo root where
every client — present and future — can find them without reaching into another
platform's directory.

**This is a planning document only.** Nothing in this plan has been executed. The goal is
to make the eventual migration a single, well-scoped, low-drama PR instead of a
multi-week trail of "oh, that broke too" follow-ups.

---

## Goals

- Every platform client (`web`, `android`, and future `ios` / `macos` / `linux` /
  `windows`) sits at the same structural depth, with no client privileged by location.
- Shared, cross-platform assets — the Supabase schema/migrations/Edge Functions, the
  Android↔Web sync/parity contract docs, ADRs, the changelog, the canonical version —
  stay at the true repo root, because they are inputs to *every* client, not outputs of
  one.
- Zero behavior change. This is a pure structural move: no feature work, no dependency
  upgrades, no refactors riding along in the same PR.
- CI, GitHub Pages deploy, the Supabase migration/function pipelines, and the Android
  build all keep working with no manual intervention beyond the workflow file changes
  this plan specifies.
- The migration is a single atomic PR (internally organized as clean, reviewable
  commits) so there is no window where `dev`/`main` sits in a half-moved, CI-red state.
- A documented, repeatable "add a new platform" checklist exists afterward, so the next
  client (iOS or otherwise) doesn't require re-deriving any of this.

## Non-goals

- No monorepo build-orchestration tooling (Turborepo, Nx, Bazel, etc.). Nothing in this
  repo today needs cross-package task graphs or caching; adding one would be solving a
  problem that doesn't exist yet.
- No change to the Android package name, application ID, module structure, or Gradle
  configuration beyond its location on disk.
- No change to the deployed web app's URL, custom domain, or GitHub Pages `base` path.
- No splitting into multiple git repositories. Everything stays in one repo with full
  shared history.
- No renaming or reshaping of `src/` internals, component structure, or the Zustand
  store — only its parent directory moves.

---

## Current-state audit

What's at the root today and where it should end up. "Shared" means consumed by more
than one platform (or every future platform); "web-only" moves under `apps/web/`;
"android-only" moves under `apps/android/`.

| Path | Ownership today | Target |
|---|---|---|
| `src/`, `index.html`, `public/`, `vite.config.ts`, `tsconfig*.json`, `eslint.config.js`, `.prettierrc`, `postcss.config.js`, `tailwind.config.js`, `components.json`, `vitest.config.ts` | Web-only | `apps/web/` |
| `package.json`, `package-lock.json` (as the web app's dependency manifest) | Web-only today, but its `version` field is also read by Android's build and the release workflow | Split — see [Decision 3](#decision-3-where-does-the-canonical-version-live) |
| `android/` (Gradle project: `app/`, `core/`, `data/`, `feature/`, `gradle*`) | Android-only | `apps/android/` |
| `dist/` | Web build output (gitignored) | `apps/web/dist/` (regenerated, nothing to move) |
| `supabase/migrations/`, `supabase/functions/` | Shared backend — consumed by both web and Android clients | Stays at root |
| `schema.sql` | Shared backend reference doc | Stays at root (see [Decision 4](#decision-4-schemasql-location)) |
| `docs/android-contracts/`, `docs/android-sync-contract.md`, `docs/android-parity-matrix.md` | Cross-platform contract — defines the interface *between* web and Android | Stays in root `docs/` (see [Decision 5](#decision-5-which-docs-are-shared-vs-platform-owned)) |
| `docs/android-implementation-status.md` | Android-internal progress tracker, not a contract | Candidate to move under `apps/android/docs/` (see Decision 5) |
| `docs/adr/`, `docs/known-problems.md`, `docs/import-feasibility.md`, `docs/planned-features-human-written.md` | Repo-wide | Stays at root |
| `CHANGELOG.md`, `LICENSE`, `CODE_OF_CONDUCT.md`, `README.md` | Repo-wide | Stay at root; `README.md` gets rewritten as a top-level index (see [Phase 6](#phase-6-rewrite-repo-root-documentation)) |
| `.github/workflows/*.yml` | Repo-wide orchestration | Stays at root (paths/working-directories inside them change) |
| `CLAUDE.md`, `AGENTS.md`, `.cursorrules`, `.windsurfrules`, `GEMINI.md`, `QODER.md`, `.claude/`, `.jules/`, `openwiki/`, `.code-review-graph/` | AI-tool configuration, discovered by convention at true repo root | Stay at root; content gets rewritten (see [Decision 6](#decision-6-ai-tool-configuration-files)) |
| `scripts/migrate-from-v1.mjs`, `scripts/verify-*.mjs` | Operate on `src/store/mockData.ts` / web-app-internal logic | `apps/web/scripts/` |
| `benchmark.js`, `benchmark-seasons-upsert.js`, `test-zustand.js`, `test-zustand-shallow.js` | Ad hoc scratch scripts, currently loose at repo root, web-specific | See [Phase 9](#phase-9-clean-up-stray-root-files) — relocate or delete, not left at root either way |
| `plan_review.md`, `projection-room-ui-audit.md` | Stray planning/audit docs, currently loose at repo root | See Phase 9 — relocate into `docs/` or delete |
| `.env.example` | Web-only today (Vite `VITE_*` vars) | `apps/web/.env.example` |
| `.gitignore` | Repo-wide, but most entries are web-specific (`node_modules`, `dist`, `.local`) | Stays at root; content reviewed per [Phase 5](#phase-5-fix-configs-that-assume-repo-root--web-app-root) |

---

## Target repository layout

```
/
├── apps/
│   ├── web/                     # everything currently at repo root that's web-specific
│   │   ├── src/
│   │   ├── public/
│   │   ├── scripts/
│   │   ├── index.html
│   │   ├── vite.config.ts
│   │   ├── tsconfig*.json
│   │   ├── eslint.config.js
│   │   ├── tailwind.config.js
│   │   ├── postcss.config.js
│   │   ├── components.json
│   │   ├── vitest.config.ts
│   │   ├── package.json         # web app's own dependency manifest
│   │   ├── package-lock.json
│   │   ├── .env.example
│   │   └── CLAUDE.md            # web-specific guidance (nested, auto-discovered)
│   ├── android/                 # current android/ moved as-is
│   │   ├── app/ core/ data/ feature/ gradle*
│   │   └── CLAUDE.md            # android-specific guidance (nested, auto-discovered)
│   ├── ios/                     # future — not created by this migration
│   ├── macos/                   # future
│   ├── windows/                 # future
│   └── linux/                   # future
├── supabase/                    # unchanged location — shared backend
│   ├── migrations/
│   └── functions/
├── schema.sql                   # unchanged location — shared backend reference
├── docs/
│   ├── adr/
│   ├── android-contracts/       # cross-platform contract, stays here
│   ├── android-sync-contract.md
│   ├── android-parity-matrix.md
│   ├── known-problems.md
│   ├── repo-restructure-plan.md # this document
│   └── ...
├── .github/workflows/
├── package.json                 # new: minimal root manifest, canonical version only
├── CHANGELOG.md
├── README.md                    # rewritten as a top-level index
├── CLAUDE.md                    # rewritten as a router/index over apps/*
├── AGENTS.md / .cursorrules / GEMINI.md / QODER.md / .windsurfrules
├── .claude/ .jules/ openwiki/ .code-review-graph/
└── LICENSE / CODE_OF_CONDUCT.md
```

---

## Key decisions

These are the judgment calls with real trade-offs. Each is written as a recommendation
with the alternative spelled out, so it can be challenged before execution starts.

### Decision 1: Move Android too, or only move the web app?

**Options:**
- **(A) Recommended.** Move both: `android/` → `apps/android/`, current root → `apps/web/`.
- (B) Lower-risk alternative: only move the web app into `apps/web/` (or `web/`) and
  leave `android/` exactly where it is.

**Recommendation: (A).** The stated goal is that no platform is structurally privileged
over another, including platforms that don't exist yet. Option B still leaves Android
sitting bare at root while web is nested — that's a different asymmetry, not a fix for
one. It also punts the same decision onto whoever adds the first future platform, with
no precedent to follow. Option A costs one extra `git mv` and one extra CI workflow edit
(`android.yml`'s `working-directory` and `paths` filter) over Option B, which is cheap
relative to getting this right once.

### Decision 2: Workspace tooling (npm workspaces) — adopt now or later?

**Options:**
- **(A) Recommended.** Don't adopt npm workspaces in this migration. `apps/web/`
  keeps its own standalone `package.json` and `package-lock.json`, installed and built
  from within `apps/web/`, exactly as it works today — just one directory level deeper.
- (B) Adopt npm workspaces now, with a `workspaces: ["apps/*"]` root `package.json`,
  hoisted `node_modules`, and a single root lockfile.

**Recommendation: (A).** Workspaces solve a code-sharing problem this repo doesn't have:
Android is Kotlin/Gradle, not JS, so there's nothing to hoist or share between it and the
web app today. Adopting (B) now adds lockfile regeneration, hoisting edge cases, and a
non-trivial new failure surface to a migration whose whole point is to be a boring,
reversible move. Revisit workspaces the day a second JS/TS package actually needs to
share code with `apps/web` (e.g., a future Electron/Tauri desktop client, or a generated
TypeScript types package derived from `schema.sql`) — at that point introducing
workspaces is its own small, well-scoped follow-up, not tangled into this one.

### Decision 3: Where does the canonical version live?

Today, `package.json`'s `version` field at repo root is the single source of truth per
[CLAUDE.md's Versioning section](../CLAUDE.md#versioning) — and it's consumed by more
than the web app:

- `vite.config.ts` reads it to define `__APP_VERSION__` for the in-app About screen.
- `.github/workflows/deploy.yml`'s `release` job reads it to compute the git tag and
  GitHub Release.
- The same job computes Android's `versionCode`/`versionName` from it and passes them
  into `./gradlew :app:assembleRelease`.
- `CHANGELOG.md` sections are keyed to it (`## [X.Y.Z]`), and both the GitHub Release
  notes *and* implicitly the Android release build are described by the same changelog
  entry — one version, one changelog entry, two shipped artifacts.

If `package.json` simply moves to `apps/web/package.json`, the "web app's dependency
manifest" becomes the de facto version authority for Android too, which is a confusing
inversion (Android's version would be governed by a file it has no other relationship
to).

**Recommendation:** introduce a new, minimal root `package.json`:

```json
{
  "name": "cinemarchive",
  "private": true,
  "version": "1.12.0"
}
```

No `dependencies`, no `scripts` beyond maybe pass-through convenience ones. This becomes
the one place `npm version <bump>` (or manual edits) touch, and it's what
`deploy.yml`'s `release` job, the `ship` skill's version-bump step, and `CHANGELOG.md`
headings key off — unchanged in spirit from today, just now honestly named "the repo's
canonical version" instead of "the web app's version that Android also happens to read."
`apps/web/package.json` keeps its own `version` field for npm's sake (npm requires one),
but it stops being load-bearing — `vite.config.ts`'s `readFileSync` for
`__APP_VERSION__` is repointed to `../../package.json` (root) instead of the local one,
so the two never drift.

### Decision 4: `schema.sql` location

**Recommendation:** leave `schema.sql` at the true repo root, not inside `supabase/`.
It's referenced by name (`schema.sql`, not `supabase/schema.sql`) throughout `CLAUDE.md`,
`README.md`, and `docs/android-contracts/README.md`; moving it saves nothing (it doesn't
resolve any path-assumption problem — it has none, since nothing computes a path to it
relative to the old web-app root) and would just add churn to every doc that names it.
Not a place to spend migration risk budget.

### Decision 5: Which docs are shared vs platform-owned?

**Rule:** if a doc describes a contract or interface consumed by more than one platform,
it stays in root `docs/`. If it's implementation-only detail for one platform, it moves
under that platform's `apps/<platform>/docs/`.

| Doc | Classification | Target |
|---|---|---|
| `docs/android-contracts/**`, `docs/android-sync-contract.md`, `docs/android-parity-matrix.md` | Contract between web (source of truth via `src/lib/db.ts`/`schema.sql`) and Android (consumer) | Stays in root `docs/` |
| `docs/android-implementation-status.md` | Pure Android progress tracker, no cross-platform contract content | Move to `apps/android/docs/implementation-status.md` |
| `docs/adr/0001-android-foundation.md` | Architecture decision record | Stays in root `docs/adr/` (ADRs are always repo-wide history, regardless of which platform they're about) |
| `docs/known-problems.md` | Tracks issues across both web and Android (see its "Android app" section) | Stays in root `docs/` |
| `docs/import-feasibility.md`, `docs/planned-features-human-written.md` | Product-level, not platform-specific | Stay in root `docs/` |

This is the one judgment call in the audit worth double-checking before execution:
confirm `docs/android-implementation-status.md` genuinely has no content that
`docs/android-parity-matrix.md` or the contract docs depend on being co-located, since
those *do* stay at root.

### Decision 6: AI tool configuration files

`CLAUDE.md`, `AGENTS.md`, `.cursorrules`, `.windsurfrules`, `GEMINI.md`, `QODER.md`,
`.claude/`, `.jules/`, `openwiki/`, and `.code-review-graph/` are discovered by their
respective tools **by convention at the true repository root** — none of them move.
What changes is their content:

- Root `CLAUDE.md` becomes a short router: repo-wide conventions (git/commit rules,
  versioning policy, branching) stay here since they apply everywhere, but the
  web-specific "Architecture," "Source Layout," and "Design System" sections move into
  a new `apps/web/CLAUDE.md`, and Android-specific guidance moves into a new
  `apps/android/CLAUDE.md`. Claude Code auto-discovers nested `CLAUDE.md` files by
  directory, so working inside `apps/web/` picks up both the root conventions and the
  web-specific detail automatically — this is a net improvement, not just parity with
  today.
- `AGENTS.md`, `.cursorrules`, `.windsurfrules`, `GEMINI.md`, `QODER.md` currently exist
  as a fixed rotation of near-duplicate files for other AI tools' discovery conventions.
  Confirm whether each tool supports nested/directory-scoped config the way Claude Code
  does; for any that only read a single root file, that root file needs to describe the
  `apps/*` layout well enough to route the tool correctly (point it at the right nested
  `CLAUDE.md`-equivalent, if the tool has one, rather than duplicating content).
- `.jules/bolt.md` and `.jules/palette.md` currently hold web-specific learnings
  (Zustand selector patterns). Leave them at root for now — Jules' own discovery
  convention is presumably also root-anchored — but be aware their content is
  web-scoped and will read oddly once Android learnings start accumulating too. Not
  blocking for this migration; worth a follow-up once there's enough Android-specific
  learning content to warrant an `apps/android`-scoped equivalent, if Jules supports one.
- `.code-review-graph/`, `openwiki/`: these tools index the whole repo tree and will
  need to be **re-run/re-indexed after the move**, not reconfigured — their generated
  content refers to file paths that all shift. This is an operational step (Phase 10),
  not a design decision.

---

## Phased execution plan

Everything below happens on one dedicated branch (e.g. `chore/apps-restructure`) off
`dev`, as a single PR with multiple atomic commits — never merged mid-way. Do not layer
other feature work on top of this branch, and avoid rebasing unrelated branches on top
of it until it's confirmed stable on `dev` (see [Rollback plan](#rollback-plan)).

### Phase 0: Pre-flight
- [ ] Confirm working tree is clean (`git status`) before branching.
- [ ] Create the branch off current `dev`.
- [ ] Re-read this plan's Decisions section for sign-off; flag any disagreement before
      moving file #1 — every phase after this assumes the decisions above are final.

### Phase 1: Move the web app
- [ ] `git mv` every web-only path from the audit table into `apps/web/`: `src/`,
      `public/`, `scripts/`, `index.html`, `vite.config.ts`, `tsconfig.json`,
      `tsconfig.app.json`, `tsconfig.node.json`, `eslint.config.js`, `.prettierrc`,
      `postcss.config.js`, `tailwind.config.js`, `components.json`, `vitest.config.ts`,
      `.env.example`, `package.json`, `package-lock.json`.
  Use `git mv` (not delete+recreate) so rename detection preserves history — verify
  with `git log --follow` on a moved file afterward.
- [ ] Do **not** move `dist/` — it's gitignored build output; it'll regenerate under
      `apps/web/dist/` on the next build.

### Phase 2: Move Android
- [ ] `git mv android apps/android`. Gradle module paths (`:app`, `:core:designsystem`,
      etc. in `settings.gradle.kts`) are relative to whatever directory contains
      `settings.gradle.kts`, not to the repo root, so this should need zero Gradle file
      edits — verify with a local `./gradlew :app:assembleDebug` from the new location
      as part of Phase 8 verification.

### Phase 3: Create the new root `package.json`
- [ ] Add the minimal root `package.json` from [Decision 3](#decision-3-where-does-the-canonical-version-live)
      with `version` copied from the current (pre-move) value.
- [ ] Update `apps/web/vite.config.ts`'s `readFileSync(new URL('./package.json', ...))`
      to read `../../package.json` instead, so `__APP_VERSION__` tracks the root file.

### Phase 4: Fix web-app-internal path assumptions
Most of these are already relative to `vite.config.ts`/`tsconfig.json`'s own location
and should need no edits — verify rather than assume:
- [ ] `vite.config.ts`'s `@`/`src` aliases (`path.resolve(__dirname, './src')`) —
      `__dirname` recomputes automatically post-move, no edit expected.
- [ ] `tsconfig.app.json`'s `baseUrl`/`paths` — already relative, no edit expected.
- [ ] `eslint.config.js`'s `globalIgnores(['dist', '.worktrees'])` — confirm this
      repo's `git worktree` convention still resolves correctly relative to
      `apps/web/` (if worktrees are created as `apps/web/.worktrees/*`, this is fine
      unchanged; if they're created at the true repo root, this ignore pattern needs
      to move to a root-level lint config instead, or be re-pointed).
- [ ] `apps/web/.env.example`'s content is unaffected (env var names don't encode
      paths), but confirm any local `.env.local` files developers keep are moved/
      recreated alongside the app.

### Phase 5: Fix configs that assume repo root == web app root
- [ ] Root `.gitignore`: review entries that were web-specific by convenience of
      co-location (`node_modules`, `dist`, `dist-ssr`, `*.local`, `cinemarchive-*.json`
      exports) — these still apply fine at root since nothing else in the repo
      produces `node_modules`/`dist` elsewhere, but double check nothing is now
      *under-ignored* for `apps/web/` specifically (git patterns without a leading
      `/` match at any depth, so most of these keep working unchanged — verify with
      `git status` showing no unexpected untracked build artifacts post-build).
- [ ] Root `package-lock.json` reference in `.gitignore` (`# pnpm lock (project uses
      npm)` / `pnpm-lock.yaml`) — this comment/rule is web-specific context; fine to
      leave at root, harmless if Android or a future platform never produces a
      `pnpm-lock.yaml`.

### Phase 6: Rewrite repo-root documentation
- [ ] `README.md` — rewrite as a top-level index: brief product description, links to
      `apps/web/README.md` (new, or reuse the current README content, moved) and to
      Android's docs, shared architecture (Supabase backend, contract docs), and
      deployment. The current README's "Project structure," "Local development,"
      "Commands," and "Design system" sections are web-specific and move into
      `apps/web/README.md` (create if it doesn't already effectively exist via
      `apps/web/CLAUDE.md`).
- [ ] Root `CLAUDE.md` — split per [Decision 6](#decision-6-ai-tool-configuration-files):
      keep repo-wide sections (Verification, Git & Commit Conventions, Branching &
      Release, Versioning, Dependencies, the RTK reference, the code-review-graph MCP
      reference), move "Architecture," "Source Layout," "Data Model," "Design System"
      into `apps/web/CLAUDE.md`.
- [ ] Create `apps/android/CLAUDE.md` with Android-specific guidance pulled from
      wherever it currently lives implicitly (ADR 0001, the parity/contract docs) —
      at minimum, point back to the root contract docs so Android work always checks
      parity before landing a change.
- [ ] `AGENTS.md`, `.cursorrules`, `.windsurfrules`, `GEMINI.md`, `QODER.md` — apply
      the same split where each tool supports it; otherwise update root content to
      describe the new layout without duplicating web/Android specifics into files
      that were never meant to hold that much detail.

### Phase 7: Fix cross-references and hardcoded paths in docs
- [ ] `docs/known-problems.md` contains ~15+ hardcoded `file:///V:/repos/CinemArchive/android/...`
      absolute links. Every one needs its `/android/` segment updated to
      `/apps/android/`. Since every single link needs touching anyway, take the
      opportunity to convert them to repo-relative Markdown links
      (`[MainActivity.kt](../apps/android/app/src/...)`) instead of `file:///V:/...`
      absolute paths — the current form is already broken for anyone not on this exact
      Windows machine/drive letter, and this migration is the natural point to fix it
      since the alternative is re-breaking the *same* links a second time later.
- [ ] `docs/android-contracts/README.md` and the per-domain contract docs reference
      `src/lib/db.ts` and `schema.sql` by path — update `src/lib/db.ts` references to
      `apps/web/src/lib/db.ts`; `schema.sql` references are unaffected (Decision 4).
- [ ] Repo-wide sweep: `grep -rn "\bandroid/" docs/ README.md CLAUDE.md AGENTS.md
      .cursorrules .windsurfrules GEMINI.md QODER.md` and `grep -rn "\bsrc/"` (outside
      `apps/web/`) after the move, to catch anything the above steps missed. Treat this
      grep as the actual completion gate for this phase, not the checklist above.

### Phase 8: Fix CI workflows
- [ ] `.github/workflows/android.yml`: update the `paths:` filters (`android/**` →
      `apps/android/**`) and `defaults.run.working-directory` (`android` →
      `apps/android`).
- [ ] `.github/workflows/deploy.yml`:
  - `build` job: add `working-directory: apps/web` to the install/build steps, or
    `cd apps/web` inline — pick one convention and use it consistently across all
    workflow files touched in this phase.
  - `Upload artifact` step: change `path: dist` to `path: apps/web/dist`.
  - `release` job: change `require('./package.json').version` to read the new root
    `package.json` (unchanged path, since Phase 3 put it back at root — confirm no
    edit is actually needed here, only in the `build`/`android` jobs).
  - `android` job: change `defaults.run.working-directory` from `android` to
    `apps/android`.
- [ ] `.github/workflows/db-migrate.yml`, `.github/workflows/deploy-functions.yml`: no
      changes expected — both operate on `supabase/**`, which isn't moving. Confirm by
      re-reading them post-move rather than assuming.

### Phase 9: Clean up stray root files
While root is already being touched, resolve the loose files identified in the audit
so they don't linger as orphaned root clutter next to the new, intentionally minimal
root:
- [ ] `plan_review.md` — appears to be a leftover single-purpose planning note from a
      prior AI-assisted change; confirm it's fully superseded (its content describes
      focus-visible styling work) and delete, or fold into `docs/known-problems.md` if
      it documents something still outstanding.
- [ ] `projection-room-ui-audit.md` — a real audit document; move into `docs/` (e.g.
      `docs/ui-consistency-audit.md`) rather than leave it loose at root.
- [ ] `benchmark.js`, `benchmark-seasons-upsert.js` — ad hoc perf scratch scripts
      against mocked Supabase clients; if still useful, move into `apps/web/scripts/`;
      if superseded by the actual upsert logic already shipped, delete.
- [ ] `test-zustand.js`, `test-zustand-shallow.js` — two-line smoke scripts that
      predate the real `vitest` test suite; delete unless they're still doing
      something the real test suite doesn't cover.

This phase is explicitly optional to the *structural* goal but cheap to do in the same
PR since root is already under review — do not expand it into a broader cleanup pass.

### Phase 10: Re-index external tooling
- [ ] Re-run/re-initialize `code-review-graph` against the new layout (its graph refers
      to file paths that all shifted).
- [ ] Re-run `openwiki` generation, or manually confirm its existing pages'
      cross-references still resolve — same path-shift concern.
- [ ] If `graphify` has been pointed at this repo separately, refresh it too.
- [ ] Confirm `.claude/settings.json` (the one file kept under version control inside
      `.claude/`, per `.gitignore`'s `!.claude/settings.json` exception) has no
      path-specific permissions or hooks that assumed the old layout.

### Phase 11: Local verification (before opening the PR)
- [ ] `apps/web`: `npm install`, `npm run lint`, `npm run build`, `npm run test`, `npm
      run dev` and manually confirm the app boots, the service worker/PWA manifest
      still resolves (`public/CNAME`, `favicon.svg`, `manifest` icons are all
      `publicDir`-relative and should just work), and the About screen shows the
      correct version (proves the `__APP_VERSION__` repoint in Phase 3 worked).
- [ ] `apps/android`: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest`
      from the new location.
- [ ] Run the Phase 7 grep sweep and confirm it returns nothing unexpected.
- [ ] Diff review: `git diff --stat` against `dev` should show renames (`R`) for the
      bulk of the changed files, not delete+add pairs — if git shows deletes+adds
      instead of renames for anything moved with `git mv`, investigate before
      committing (usually means the working tree had uncommitted changes to that file
      before the move, or the move+edit happened in the same commit and confused
      rename detection — split them into separate commits if so).

### Phase 12: Push and verify CI
- [ ] Push the branch and confirm `android.yml` triggers correctly on its new `paths:`
      filter (push a trivial change under `apps/android/` on the branch to confirm, if
      the main restructure commits alone don't exercise the path-filter logic clearly).
- [ ] `deploy.yml` only triggers on push to `main` or `workflow_dispatch` — it will
      **not** run on this branch by pushing alone. This is the highest-risk step in the
      whole migration, because the *first real execution* of the edited `deploy.yml`
      happens at merge time, against production (GitHub Pages, the live custom domain,
      Supabase). De-risk it as much as possible before merging:
  - Manually re-read the full diff of `deploy.yml` line by line against the Phase 8
    checklist — treat this as the actual gate, not "CI was green on the branch," since
    CI being green on the branch does not exercise this workflow at all.
  - Confirm `npm run build` from `apps/web` locally produces the exact same `dist/`
    shape (including `CNAME`, `404.html`, `favicon.svg`) that today's root-level build
    produces, since that's what the Pages artifact upload now points at.
  - Consider triggering `db-migrate.yml` and `deploy-functions.yml` manually via
    `workflow_dispatch` from the branch (both are safe to run repeatedly and don't
    depend on the moved paths) purely to confirm they're unaffected, per Phase 8.
  - Merge during a window where you can watch the Actions run immediately and are
    ready to act on the [rollback plan](#rollback-plan) if `deploy`, `release`, or
    `android` fail.

---

## Rollback plan

Because this is a same-repo structural move (`git mv`, no history rewriting, no
`filter-repo`/`subtree` surgery), rollback is a plain `git revert` of the restructure
commit(s) — as long as nothing else has merged on top of it in the meantime. This is
the concrete reason Phase 0 insists on a single tight PR with no other work layered on
top: it keeps the revert clean. If the PR merges to `dev` and something breaks that
wasn't caught in Phase 11/12, revert on `dev` immediately rather than attempting a
forward-fix under pressure; re-open the branch, fix, and re-merge once verified.

If the break is only discovered *after* promotion to `main` (i.e., after a live
`deploy.yml` run fails or partially succeeds — e.g., Pages deployed but the Android job
failed), evaluate partial rollback: the `release`/tag job creating a bad tag is the one
step that isn't cleanly revertible (a pushed git tag needs explicit deletion), so treat
tag creation as the point of no return and make sure everything upstream of it in
`deploy.yml` has been verified per Phase 12 before that job can run.

---

## Post-migration verification checklist

Run through this once, after merge to `dev` and again after promotion to `main`:

- [ ] `apps/web` dev server boots and the app is fully functional (library, ledger,
      add-title flow, auth) against a real Supabase project.
- [ ] `npm run build` + `npm run lint` + `npm run test` all green from `apps/web`.
- [ ] `apps/android` debug build, lint, and unit tests all green from `apps/android`.
- [ ] GitHub Pages deploy completes end-to-end on `main`; the live custom domain still
      serves the app correctly (check the deployed version number matches).
- [ ] `db-migrate.yml` and `deploy-functions.yml` still run correctly (unaffected, but
      confirm rather than assume).
- [ ] The `release` job tags the correct version and generates release notes from the
      correct `CHANGELOG.md` section.
- [ ] The `android` job attaches a correctly-versioned signed APK to that release.
- [ ] Opening Claude Code (or other configured AI tools) with cwd inside `apps/web/`
      picks up both root `CLAUDE.md` and `apps/web/CLAUDE.md`; same check inside
      `apps/android/`.
- [ ] `code-review-graph`/`openwiki` reflect the new paths (Phase 10 actually ran, not
      just planned).
- [ ] No remaining hits for stale `android/` or root-relative `src/` references from
      the Phase 7 grep sweep.

---

## Future platform onboarding checklist

Once this migration lands, adding the next platform (iOS is the most likely next
candidate given the roadmap) should follow this template rather than re-deriving repo
structure decisions from scratch:

1. Create `apps/<platform>/` with that platform's native project structure.
2. Add `apps/<platform>/CLAUDE.md` (and equivalents for other configured AI tools, per
   [Decision 6](#decision-6-ai-tool-configuration-files)) with platform-specific
   guidance; keep repo-wide policy in root `CLAUDE.md` only.
3. Add a path-scoped CI workflow modeled on `.github/workflows/android.yml`
   (`paths: ["apps/<platform>/**", ...]`, scoped `working-directory`).
4. Decide up front — before any distributed build — the platform's bundle
   identifier/application ID and signing identity, the way ADR 0001 did for Android;
   changing these after store publication creates a new app identity.
5. If the platform should also ship a build artifact off the shared release process,
   extend `deploy.yml`'s `release`-dependent job fan-out the way the `android` job
   does today, reading the same root `package.json` version.
6. If the new platform needs a contract with the shared Supabase backend beyond what
   `docs/android-contracts/` already documents generically, add
   `docs/<platform>-contracts/` alongside it — following [Decision 5](#decision-5-which-docs-are-shared-vs-platform-owned)'s
   rule: contract docs are shared, implementation-detail docs live under the platform's
   own `apps/<platform>/docs/`.
7. Do not introduce workspace/build-orchestration tooling speculatively — revisit
   [Decision 2](#decision-2-workspace-tooling-npm-workspaces--adopt-now-or-later) only
   when actual code-sharing need exists between two or more JS/TS packages.
