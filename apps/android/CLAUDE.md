# CLAUDE.md (apps/android)

Android-app-specific guidance for Claude Code. Auto-discovered alongside the repo-root
[CLAUDE.md](../../CLAUDE.md) whenever the working directory is inside `apps/android/` — the
root file's Verification/Git/Branching/Versioning/Dependencies/RTK/code-review-graph sections
apply here too and aren't repeated below.

## Project Overview

Native Android client for CinemArchive: Kotlin + Jetpack Compose + Material 3, sharing the
same Supabase backend as the web app (`apps/web/`). Namespace/application ID
`work.kumarfamilynet.cinemarchive` (provisional — see
[docs/adr/0001-android-foundation.md](../../docs/adr/0001-android-foundation.md); confirm Play
ownership before any distributed build, since changing it post-publication creates a new app
identity). Pre-distribution — not yet published to Play.

## Module layout

```
apps/android/
  app/                     # Single-activity entry point
  core/model/              # Shared domain types
  core/designsystem/       # Compose theme, shared components
  core/database/           # Room-backed local database
  data/                    # Repositories, Supabase remote read/write, incremental sync
  feature/auth/            # Sign-in, passkey, invite redemption
  feature/library/         # Library + title detail
  feature/discover/        # Search, trending, add-title
  feature/upnext/          # Watchlist + continue watching
  feature/ledger/          # Stats dashboard widgets
  feature/settings/        # Profile, about, account
```

UI reads through a Room-backed repository; a data-layer outbox drives incremental sync
(`sync_tombstones` — see [docs/android-sync-contract.md](../../docs/android-sync-contract.md)).

## Commands

Run from `apps/android/`:

```bash
./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest   # matches CI (android.yml)
./gradlew :app:assembleRelease                                  # signed release build (CI-only; needs keystore secrets)
```

CI (`.github/workflows/android.yml`) triggers only on `apps/android/**` or its own workflow
file changing. The release APK is built and attached to the GitHub Release by
`.github/workflows/deploy.yml`'s `android` job, reading the same root `package.json` version
as the web app (`versionCode`/`versionName` computed from it — see the root CLAUDE.md's
Versioning section).

## Feature parity with the web app

This client tracks the web app domain by domain — always check these before landing a change:

- [docs/android-parity-matrix.md](../../docs/android-parity-matrix.md) — per-domain status and what's still gapped
- [docs/android-implementation-status.md](../../docs/android-implementation-status.md) — phased progress log, including on-device verification notes
- [docs/android-contracts/](../../docs/android-contracts/) — per-domain field/RLS/fixture contracts against the web app's `apps/web/src/lib/db.ts` and `schema.sql`
- [docs/android-sync-contract.md](../../docs/android-sync-contract.md) — incremental sync design (tombstones, cursors)
