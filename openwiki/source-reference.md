---
type: Source Map
title: CinemArchive source reference
description: Navigational map of the current multi-client repository, shared backend, contracts, and delivery workflows.
tags: [source-map, architecture, web, android, supabase]
---

# Source reference

Use this map with [Architecture](architecture/overview.md) for ownership and [Workflows](workflows/index.md) for behavior. Paths below reflect the `apps/*` multi-client layout, not the repository’s former root-level web layout.

## Web client: `apps/web/`

| Area | Key paths | Start here when changing |
|---|---|---|
| Application bootstrap and access modes | `src/main.tsx`, `src/App.tsx` | Authentication startup, shared/friend deep links, code-split views |
| Client state | `src/store/useAppStore.ts`, `mockData.ts` | Optimistic actions, library/UI/list/outings state, derived filtering |
| Supabase boundary | `src/lib/db.ts`, `auth.ts`, `media.ts` | Data loading/writes, auth helpers, media-proxy calls |
| Library and detail | `src/views/Library.tsx`, `src/components/TitleDetailDrawer.tsx` | Library UI, title/episode interaction |
| Product views | `src/views/Discover.tsx`, `Ledger.tsx`, `UpNext.tsx`, `Friends.tsx`, `Profile.tsx`, `Lists.tsx` | Domain screens |
| Ledger | `src/views/ledger/`, `src/store/ledgerDerive.ts`, `ledgerStats.ts`, `src/lib/ledgerPanels.ts` | Widgets, derivations, layout |
| Tests and checks | `src/**/*.test.ts(x)`, `scripts/verify-*.mjs`, `vitest.config.ts` | Targeted regressions and test runner setup |

## Android client: `apps/android/`

| Area | Key paths | Start here when changing |
|---|---|---|
| Composition root | `app/.../CinemArchiveApplication.kt` | Repository wiring, startup sync, outbox flushing, outings reconciliation |
| Feature structure | `settings.gradle.kts`, `feature/*` | Module boundaries and feature screens |
| Shared models | `core/model/` | Library, Ledger, outings, scheduling, normalization concepts |
| Local persistence | `core/database/` | Room database, entities, DAOs, outbox table |
| Repositories and remote access | `data/LibraryRepository.kt`, `LibrarySyncRepository.kt`, `MutationOutbox.kt`, `SupabaseRemoteMutationWriter.kt`, `SupabaseRestClient.kt` | Local-first writes, pull sync, conflict/retry handling |
| Platform design system | `core/designsystem/` | Compose primitives, visual behavior, shared screens |
| Android tests | `**/src/test/**/*.kt` | Unit/JVM tests for models, data, and Room behavior |

The Android repository layer **implements the sync lifecycle** described in [Workflows](workflows/index.md#android-local-first-sync); UI modules should not take on direct Supabase responsibilities.

## Shared backend

| Area | Key paths | Purpose |
|---|---|---|
| Schema reference | `schema.sql` | Current readable tables, RLS, triggers, RPCs, and comments |
| Deployable schema history | `supabase/migrations/` | Ordered database changes; newest migration is not a replacement for the full schema reference |
| Media gateway | `supabase/functions/media-proxy/index.ts` | Server-side catalog proxy and cache behavior |
| Invite redemption | `supabase/functions/redeem-invite/index.ts` | Server-side invite validation and account creation |
| HTTP utilities | `supabase/functions/_shared/http.ts` | Shared function HTTP helpers |

The backend **is consumed by both clients**, so source changes in this area should lead to [Operations](operations/index.md) and [Testing](testing/index.md), not only to one client implementation.

## Normative docs and delivery configuration

| Path | Why it matters |
|---|---|
| `AGENTS.md` | Repository conventions, precedence, and parity expectations |
| `apps/web/AGENTS.md`, `apps/android/AGENTS.md` | Client-specific engineering guidance |
| `docs/adr/0001-android-foundation.md` | Android architectural direction |
| `docs/adr/0002-multi-platform-repo-layout.md` | Reasoning behind `apps/web` and `apps/android` sibling layout |
| `docs/android-sync-contract.md` | Sync design rationale and server contract history |
| `docs/android-contracts/` | Per-domain web/Android field, RLS, and fixture contracts |
| `docs/android-parity-matrix.md` | Domain-level Android coverage and known verification gaps |
| `.github/workflows/deploy.yml` | Main migration, web Pages deploy, release, and APK pipeline |
| `.github/workflows/deploy-functions.yml` | Edge Function deployment |
| `.github/workflows/parity.yml` | Parity-declaration enforcement |

## Maintenance traps

- Do not use old root-relative `src/` paths for current code; web source is under `apps/web/src/`.
- `schema.sql` and migrations answer different questions: current reference versus ordered deployment history. Update both for schema changes.
- A source-level Android feature can be local-only. Check the parity matrix before documenting it as cross-platform behavior.
- Some Android contract documents include historical status prose. Prefer current code and applied migrations for implementation status, while preserving the contract docs as design rationale.
