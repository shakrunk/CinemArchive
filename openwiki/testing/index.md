---
type: Testing Guide
title: CinemArchive testing and verification
description: Test layers and practical validation commands for the web client, Android client, shared schema behavior, and parity process.
tags: [testing, verification, web, android, parity]
---

# Testing and verification

CinemArchive has automated tests in both client stacks as well as focused executable verification scripts. The previous claim that the repository has no unit or integration tests is stale.

## Web checks

From `apps/web/`:

```bash
npm run test
npm run lint
npm run build
```

`vitest.config.ts` configures Vitest. Tests live beside behavior across `src/lib/`, `src/store/`, `src/components/`, and `src/views/`. Examples include library loading/cache behavior, navigation and helpers, episode and Ledger derivations, rating normalization, imports/exports, UI components, and Library/Discover flows.

Focused scripts remain useful where they encode scenario-style business rules:

```bash
cd apps/web
node scripts/verify-episode-logic.mjs
node scripts/verify-navigation-logic.mjs
node scripts/verify-share-scope-logic.mjs
node scripts/verify-friendship-state-machine.mjs
```

Choose a test close to the code being changed; do not substitute a broad build for a focused behavioral regression. Web tests **validate the optimistic client model** described in [Workflows](../workflows/index.md), but RLS and function behavior still need backend-aware review.

## Android checks

From `apps/android/`:

```bash
./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest
```

JVM tests are distributed by module. Notable coverage includes:

- `core:model`: specials, seating, rating normalization, Ledger layout/settings, schedules, and outing rules;
- `core:database`: DAO behavior and interaction ordering;
- `data`: mutation outbox outcomes, conflict handling, add/remove title behavior, metadata parsing/backfill, Ledger calculations/layout, and remote-writer behavior;
- feature-related behavior in the corresponding Android test sources.

`SupabaseRemoteMutationWriterLiveTest` is credential-gated and skips when its dedicated test configuration is unavailable. A routine local/CI unit pass therefore does not prove live project access. Do not supply credentials in chat or source files.

## Shared-domain validation

When changing a shared table, RPC, or trigger:

1. Add/adjust the migration and `schema.sql`.
2. Review RLS using the actual owner/shared/friend authorization model.
3. Exercise web mapping (`apps/web/src/lib/db.ts`) and Android mapping (`Room` entities, `LibrarySyncRepository`, and `SupabaseRemoteMutationWriter`) as applicable.
4. Run both client-focused tests if both clients expose the domain.
5. Confirm that pull sync handles new/updated/deleted rows, including tombstones where the entity is synchronizable.

The Android parity matrix identifies incomplete high-risk validation, notably list sync and some on-device cross-client workflows. Treat it as a test backlog, not as proof that an untested feature is unsupported.

## Parity process

At the repository root:

```bash
npm run check:parity
```

The repository requires explicit parity declarations for `feat` and `fix` changes that intentionally affect only one client. `docs/android-parity-matrix.md` records domain-level status. This check **connects product changes to cross-client review**; it does not replace platform tests.

## Suggested change matrix

| Change | Minimum checks |
|---|---|
| Web UI/state only | Relevant Vitest test, `npm run lint`, `npm run build` from `apps/web` |
| Android UI/repository only | Relevant JVM test plus `:app:assembleDebug :app:lintDebug testDebugUnitTest` |
| Shared schema/RPC | Migration/schema review, focused web and Android mapping tests, parity check |
| Edge Function | Function-specific behavioral review, affected client tests, deploy-function workflow awareness |
| Sync or outbox | Android data tests, deletion/tombstone and retry/conflict scenarios, targeted cross-device validation where available |

## Execution note

Commands documented here are declared by repository configuration and CI guidance. This wiki update did not claim a fresh local pass because the available sandbox could not execute the repository’s package/Gradle paths reliably.
