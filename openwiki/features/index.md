---
type: Product Guide
title: CinemArchive features and platform coverage
description: Product domains in CinemArchive, their shared data model, and notable web and Android coverage boundaries.
tags: [features, product, web, android, parity]
---

# Features and platform coverage

CinemArchive centers on a personal media library, detailed viewing history, and analytics. The web and Android clients share primary domain data but do not always expose identical interfaces. `docs/android-parity-matrix.md` is the detailed contract index; this page is the maintenance-oriented overview.

## Core library and TV tracking

A title is a movie or television series with personal status, ratings, notes, tags, metadata, and history. TV series contain seasons and episodes. Episode watch events, ratings, and reviews are independent logs: an episode may be rated or reviewed without a dated watch event, and multiple watch events represent rewatches.

Season and series progress are derived from episode data. Specials are loggable but excluded from main-series progress and Up Next calculations. This shared concept **feeds** both [The Ledger](#the-ledger) and [Up Next and cinema outings](#up-next-and-cinema-outings).

- **Web:** `apps/web/src/views/Library.tsx`, `TitleDetailDrawer.tsx`, `store/episodeUtils.ts`, and `lib/db.ts`.
- **Android:** `feature:library`, `core:model`, Room entities/DAOs, and `data/LibraryRepository`.

## Lists

Private custom lists group titles independently of watch status. A title may appear in multiple lists. Lists and list items are owner-only in the shared backend and synchronize to Android through the same change stream as library entities.

- **Web:** `apps/web/src/views/Lists.tsx` and `components/AddToListSheet.tsx`.
- **Android:** `feature:lists`, `data/ListsRepository`, and the Room/sync mapping.

Lists **depend on** the shared title identity and Android sync flow in [Workflows](../workflows/index.md#android-local-first-sync). The parity matrix records that focused Android list/sync tests and an on-device cross-client pass remain deferred.

## The Ledger

The Ledger is a configurable statistics board based on library, viewing, and episode data. It includes distributions, timelines, genres, credits, runtimes, languages, queues, and personal rating normalization. Layout preferences are per user.

- **Web:** `apps/web/src/views/Ledger.tsx`, `views/ledger/`, `store/ledgerDerive.ts`, and `lib/ledgerPanels.ts`.
- **Android:** `feature:ledger`, `data/LedgerRepository`, and `core:model` layout/settings types.

The two clients share the underlying library history but have platform-specific presentation choices. Android includes accessible list alternatives and some Android-only moviegoing breakdowns. Shared/friend Ledger reading on Android is deferred. See [Architecture](../architecture/overview.md) for the client boundary and [Testing](../testing/index.md) for calculation test locations.

## Up Next and cinema outings

Up Next derives unwatched episodes and upcoming/watchlist material from the library. Cinema outings add a scheduled theater viewing and can produce a viewing entry when completion conditions are met.

Cinema outings are owner-only. Android adds local alarms and reboot recovery, so it can complete a due outing while the web app is not open; Android-only venue notes, theater-interest flags, and ticket capture remain local enhancements. This feature **relies on** the sequencing and idempotency constraints in [Workflows](../workflows/index.md#cinema-outing-completion).

## Discovery and metadata

Discover provides search, trending material, recommendations, rich title details, credits, trailers, providers, and related browsing. Both clients call the same `media-proxy` Edge Function rather than exposing third-party catalog keys.

The proxy **connects** client discovery to `TMDB`, `OMDb`, and selected enrichment sources, as explained in [Architecture](../architecture/overview.md#edge-functions). Android supports real browsing and title add flows, with documented feature gaps such as some metadata enrichment and fields.

## Sharing, friends, and notifications

The web client supports invite-only accounts, scoped read-only share links, friend libraries, activity, comments, reactions, recommendations, and notifications. Authorization is implemented in backend RLS and RPCs, not merely in UI state.

Android coverage for sharing, social surfaces, and notifications remains Discovery in the parity matrix. Do not infer Android read/write support from the shared database tables alone. Review [Workflows](../workflows/index.md#web-startup-and-read-scopes) and [Operations](../operations/index.md#security-and-secrets) before changing access behavior.

## Import, export, themes, and client affordances

The web client includes JSON backup/restore, Letterboxd import, PWA behavior, command shortcuts, deep links, and visual themes. Android has its own appearance preferences, app-update behavior, and native interaction patterns. These are mostly client-local concerns, although imported library data still becomes shared backend data after persistence.

## Product-change guidance

- For a **new shared field or relation**, update the migration and `schema.sql`, then inspect web mapping, Android Room entities, remote writer, and sync pull mapping.
- For a **new derived statistic**, keep its definition explicit; web and Android may need matching calculations or a documented parity decision.
- For a **new social or sharing capability**, begin with RLS and the access matrix, then add UI. Android is not assumed to support it yet.
- For **Android-only enhancements**, make the storage scope clear: local-only data cannot be promised across web or other Android installations.
