---
type: Project Guide
title: CinemArchive quick start
description: Orientation for CinemArchive, a multi-client personal film and television tracker with a shared Supabase backend.
tags: [cinemarchive, onboarding, web, android, supabase]
---

# CinemArchive quick start

CinemArchive is a personal movie and television tracker. Its web client is a React PWA, and its Android client is a native Kotlin application. Both clients use the same Supabase database, RLS policies, media gateway, and invite-only account model. The public web deployment is https://cinemarchive.kumarfamilynet.work/.

The repository deliberately treats neither client as primary: [ADR 0002](../docs/adr/0002-multi-platform-repo-layout.md) places them at `apps/web/` and `apps/android/`, while shared backend infrastructure remains at the repository root.

## Start here

| Need | Read | Main source anchors |
|---|---|---|
| Understand the two clients and shared services | [Architecture overview](architecture/overview.md) | `apps/web/`, `apps/android/`, `schema.sql`, `supabase/functions/` |
| Find product behavior and platform coverage | [Features and domains](features/index.md) | `README.md`, `docs/android-parity-matrix.md` |
| Trace startup, read access, and mobile sync | [Workflows](workflows/index.md) | `App.tsx`, `useAppStore.ts`, `LibrarySyncRepository.kt` |
| Set up, migrate, or release | [Operations](operations/index.md) | `.github/workflows/deploy.yml`, `supabase/migrations/` |
| Select checks before changing code | [Testing and verification](testing/index.md) | `apps/web` tests, Android unit tests, `scripts/check-parity.mjs` |
| Locate an implementation area quickly | [Source reference](source-reference.md) | application and backend directories |

## Repository shape

```text
apps/
  web/                  React, Vite, TypeScript, Zustand, Vitest
  android/              Kotlin, Compose, Room, Gradle feature modules
supabase/
  migrations/           ordered database changes
  functions/            media-proxy and redeem-invite Edge Functions
schema.sql              readable schema and RLS reference
docs/
  adr/                  architecture decisions
  android-contracts/    cross-client domain contracts
```

## Local development

### Web

```bash
cd apps/web
npm install
npm run dev
npm run test
npm run lint
npm run build
```

The web app needs `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY` for Supabase-backed behavior. Do not put TMDB or OMDb keys in browser configuration: the [architecture](architecture/overview.md) routes media access through `media-proxy`.

### Android

```bash
cd apps/android
./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest
```

Android reads the Supabase URL and publishable key from its gitignored `local.properties`; use `apps/android/local.properties.example` for the non-secret shape. It targets Android API 31+ and uses Java 17. Its Room database allows the app to render cached data and queue mutations locally; see the [sync workflow](workflows/index.md#android-local-first-sync).

## Mental model

- **Web uses a durable owner journal.** `App.tsx` selects an owner, anonymous shared-link, or authenticated friend read path; Zustand holds the active client state, `lib/db.ts` is the read boundary, and owner writes flow through the IndexedDB offline command runtime.
- **Android is local-first.** Compose features read Room through repositories. `LibrarySyncRepository` pulls server changes, while `MutationOutbox` records changes for retryable remote delivery.
- **The backend is shared and authoritative.** `schema.sql` documents tables and RLS; timestamped migrations are the deployable schema history. Both clients use it, so backend changes need cross-client consideration.
- **Product parity is intentional rather than automatic.** `docs/android-parity-matrix.md` tracks domain coverage and explicit Android-only behavior. [Features and domains](features/index.md) summarizes the important distinctions.

## Change guide

1. Identify the affected product domain in [Features and domains](features/index.md).
2. Trace the corresponding runtime path in [Workflows](workflows/index.md), especially before changing writes or access rules.
3. If a shared table, RPC, or Edge Function changes, update the migration and `schema.sql` together, then assess both clients as described in [Operations](operations/index.md).
4. Run the focused checks from [Testing and verification](testing/index.md). A `feat` or `fix` that intentionally affects one client must follow the repository’s parity-declaration convention in `AGENTS.md`.

## Documentation authority

This wiki is a navigation and maintenance guide. Source code, `schema.sql`, deployable migrations, `docs/`, and the applicable `AGENTS.md` are normative when they conflict with this guide. In particular, several Android contract pages contain historical implementation-status language; validate changes against the current migration and code.

## Backlog

- **Cross-client sync test depth** — `apps/android/data/` has sync/outbox tests, but `ListsRepository` and some cross-device flows lack focused regression coverage according to the parity matrix.
