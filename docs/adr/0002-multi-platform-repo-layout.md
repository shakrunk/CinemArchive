# ADR 0002: Multi-platform repo layout (`apps/*`)

- **Status:** Accepted — executed 2026-07-24
- **Date:** 2026-07-23

## Decision

Restructure the repository so no platform client is privileged by its position in the
tree. Every client — the web app (currently at repo root) and the Android app
(currently `android/`), plus future iOS, macOS, Linux, and Windows native clients — will
live as a sibling under `apps/<platform>/` (`apps/web/`, `apps/android/`, …). Backend
infrastructure that is genuinely shared across every client — `supabase/migrations/`,
`supabase/functions/`, `schema.sql` — stays at the true repo root, as do repo-wide docs
(ADRs, the changelog, the Android↔web contract docs) and AI-tool configuration files
(`CLAUDE.md`, `AGENTS.md`, etc.), which are discovered by their tools at root by
convention regardless of what moves underneath them.

The canonical semantic version moves from the web app's `package.json` to a new,
minimal root `package.json` (no dependencies, `version` only), since that version
already governs both the web release and the Android APK's `versionCode`/`versionName`
via `.github/workflows/deploy.yml` — it was never really "the web app's version," just
housed in the web app's manifest by historical accident of the web app being at root.

No monorepo build-orchestration tooling (Turborepo, Nx, npm workspaces) is adopted as
part of this change — there is no cross-package code sharing need yet between Android
(Kotlin/Gradle) and the web app (TypeScript/Vite), so there's nothing for such tooling
to orchestrate.

The full execution plan — file-by-file audit, phased migration steps, CI workflow
changes, rollback plan, and a template for onboarding future platforms — is
[docs/repo-restructure-plan.md](../repo-restructure-plan.md).

## Consequences

- `apps/web/vite.config.ts` reads the app version from the root `package.json`
  (`../../package.json`) instead of its own, so the web app's dependency manifest and
  the repo's canonical version can never drift against each other.
- Every hardcoded reference to the old `android/` and root-relative `src/` paths across
  docs and CI workflows must be updated in the same migration — most concentrated in
  `docs/known-problems.md`'s per-issue file links and `.github/workflows/deploy.yml` /
  `android.yml`.
- `docs/known-problems.md`'s absolute `file:///V:/repos/CinemArchive/...` links are
  converted to repo-relative Markdown links as part of the same pass, since every one
  of them needs touching anyway to fix the `android/` → `apps/android/` segment, and
  the absolute form was already machine-specific and broken for anyone not on that
  exact Windows drive letter.
- Adding a new platform later follows a documented checklist (end of the restructure
  plan) instead of re-deriving root-vs-nested placement decisions from scratch each
  time.
- The migration must land as a single atomic PR — a half-moved state would leave CI red
  on every intermediate commit reachable by `dev`, since `deploy.yml` and `android.yml`
  both need their path assumptions updated in lockstep with the file moves they
  reference.
