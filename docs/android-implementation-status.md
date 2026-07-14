# Android implementation status

This checklist is the execution record for `docs/native-android-app-plan.md`. It adds two missing foundation steps: confirming the permanent application ID/Play owner before distribution, and proving the locally selected Android toolchain resolves in CI.

## Phase 0 — Discovery and foundations

- [x] Create an isolated native Android Gradle project in this monorepo.
- [x] Add a version catalog, Gradle wrapper, API 31+ support baseline, and JDK 17 build requirement.
- [x] Establish `app`, `core:model`, `core:designsystem`, `core:database`, `data`, and `feature:library` boundaries.
- [x] Add `CinemArchiveTheme` foundations for dark, light, noir, and matrix modes.
- [x] Build a Room-backed, read-only Library prototype with immutable UI state and a screen-scoped ViewModel.
- [x] Record the provisional package identity and toolchain decision in ADR 0001.
- [x] Publish the initial Android parity matrix with web sources, backend dependencies, and verification gates.
- [x] Confirm the local toolchain resolves: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` builds a debug APK and passes lint/unit tests (JDK 17, Android SDK 36 platform, build-tools 35.0.0).
- [ ] Confirm permanent package name, Play owner, signing owner, privacy owner, and support channel. **Blocked on user decision** — see below.
- [x] Audit the web contracts/RLS/media rules and publish the Android parity matrix and fixtures for the Library, Title detail, and Episode tracking domains (`docs/android-contracts/`, `docs/android-sync-contract.md`). Social/share/Discover domains remain at Discovery.
- [x] Provision a non-production Supabase test project (`cinemarchive-android-test`, ref
      `rgnthbiigfbfiuehteoe`, `us-west-1`, $0/month) — the 33 production migrations are
      applied and it's ready for RLS/owner/friend/blocked/share-token fixture tests.
- [ ] Add protected Supabase test access, session restoration, verified App Links, and physical-device Credential Manager proof. **Partially blocked** — the test project now exists; session restoration/App Links/Credential Manager still need a physical Android device.
- [ ] Add a screenshot/golden-test harness and CI matrix after the first emulator image is chosen.

### Blocked on user input

These can't proceed autonomously and aren't ordering-blocked by anything above:

1. **Permanent application ID / Play console owner / signing owner / privacy owner / support channel** — ADR 0001 uses a provisional `work.kumarfamilynet.cinemarchive` identity; changing it after any Play publication creates a new app identity, so this needs an explicit decision before distribution. **Still open** — user chose to keep the provisional identity and stay pre-distribution for now (2026-07-12).
2. ~~**Supabase test project**~~ — resolved 2026-07-12: `cinemarchive-android-test` exists and has the full production schema plus the sync-layer migration, both validated. See `docs/android-sync-contract.md`.
3. **Physical Android device** — Credential Manager/WebAuthn relying-party and Digital Asset Links verification cannot be done on an emulator.

## Later phases

- [ ] Phase 1 — read-only product spine and incremental sync. **In progress:**
  - [x] Room schema expanded from the 5-field Library-only stub to the full read-only
        contract from `docs/android-contracts/`: `TitleEntity`, `SeasonEntity`,
        `EpisodeEntity`, `EpisodeWatchEventEntity`, `EpisodeRatingEntity`, `ViewingEntity`,
        with DAOs and a `Converters` type converter for `genres`.
  - [x] `LibraryRepository` extended with `observeTitleDetail(titleId)`, combining the
        season/episode/watch-event/rating/viewing flows into a single `TitleDetail` domain
        model (watch counts and latest-rating-per-episode computed client-side, matching
        the "rollups aren't DB-derived" note in `docs/android-contracts/episode-tracking.md`).
  - [x] New Title detail screen (`TitleDetailScreen.kt`) — header, seasons/episodes with
        watch/rating indicators, viewing history — wired to the Library list via simple
        local nav state in `MainActivity` (no `androidx.navigation` dependency added yet;
        revisit when a third top-level route arrives).
  - [x] `DevFixtureSeed` seeds Room with the same three titles as
        `docs/android-contracts/fixtures/` on first launch, so the spine is visually
        verifiable before real sync exists. **Temporary — delete once `sync_library_changes`
        (`docs/android-sync-contract.md` §2) is implemented and wired up.**
  - [x] Verified: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` — build
        succeeded, 0 lint issues, debug APK produced (25.8 MB).
  - [x] Incremental sync design validated end-to-end against `cinemarchive-android-test`:
        `updated_at` triggers on the 10 previously-missing tables, `sync_tombstones` +
        `record_tombstone()`, and the `sync_library_changes` RPC. Verified bootstrap read,
        incremental cursor pickup on update, tombstone-on-delete, and RLS isolation between
        users. Found and fixed a real bug along the way (account-deletion FK cascade — see
        `docs/android-sync-contract.md` §3.3).
  - [x] Migration promoted: `supabase/migrations/20260713000000_android_sync_layer.sql` +
        `schema.sql` kept in sync. Re-validated as a committed file, not just ad-hoc SQL —
        the test project's `public` schema was dropped and all 34 migrations replayed from
        scratch, confirming the file applies cleanly and behaves correctly standalone.
  - [x] Applied to production (2026-07-13): [PR #92](https://github.com/shakrunk/CinemArchive/pull/92)
        merged to `main` (v1.5.0), then the "DB Migrate (manual)" GitHub Actions workflow
        was dispatched by explicit user confirmation — note this workflow is
        `workflow_dispatch`-only, *not* automatic on push to `main` despite `CLAUDE.md`
        describing it that way; worth fixing that doc separately. Verified directly against
        the live project (`taoyxhbacdvnhevqyrqm`): `sync_tombstones` table exists,
        `sync_library_changes` RPC exists, all 10 previously-missing `updated_at` columns
        are present, all alongside real production data (69 titles, etc.) — confirming a
        clean apply with no data disruption.
  - [ ] Cast/crew, physical media, badge scores (imdb/RT/Metacritic) — deferred from this
        pass to keep the local-only slice buildable and reviewable; not a hard blocker,
        just descoped.
- [ ] Phase 2 — durable tracking mutations, outbox, and conflict handling. **Started:**
  - [x] `mutation_outbox` Room table + `OutboxDao` — a durable queue of pending remote
        writes (client-generated id, entity type, operation, JSON payload, attempt count),
        separate from the local read-model write it accompanies so the outbox row only
        needs to survive process death, not be transactional with it.
  - [x] `MutationOutbox` (in `data`) — `enqueue()` + `flush()` against a `RemoteMutationWriter`
        interface. The real implementation isn't wired yet: `UnconfiguredRemoteMutationWriter`
        always returns `Retry`, so mutations stay durably queued (never dropped, never
        falsely marked synced) until a real Supabase client + auth session exist — blocked
        on the same physical-device Credential Manager gap as Phase 0/1's auth work.
  - [x] One real mutation wired end-to-end: `LibraryRepository.logEpisodeWatched()` writes
        optimistically to Room (client-generated id, matching the
        docs/android-sync-contract.md §4.2 idempotency contract) and enqueues the outbox
        entry. `TitleDetailScreen` exposes it as a "Mark watched" action on unwatched
        episodes.
  - [x] Verified: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` — 0 lint
        issues, build succeeds.
  - [x] Verified live on an Android Studio emulator (2026-07-13, `sdk_gphone64_x86_64`,
        API 36): installed the debug build, launched the app, confirmed the Library and
        Title detail screens render the seeded fixtures, then tapped "Mark watched" on a
        real unwatched episode. Pulled the on-device Room database (WAL-mode, so both the
        `.db` and `.db-wal` files were needed) and confirmed directly: a new
        `episode_watch_events` row was written with today's date, and a matching
        `mutation_outbox` row was enqueued (`entityType='episode_watch_event'`,
        `operation='upsert'`, correct JSON payload, `attemptCount=0`, no error). No crashes
        in logcat. This is the first confirmation of the outbox pattern working end-to-end
        on a running app rather than only in a unit test. (`DevFixtureSeed` gained a second,
        unwatched episode so this path has something to exercise — still temporary/dev-only
        data, same as the rest of the seed.)
  - [ ] Conflict handling — designed (last-write-wins by `updated_at`, per
        docs/android-sync-contract.md §4.2) but not implementable/testable until a real
        `RemoteMutationWriter` exists to actually produce conflicts against.
  - [x] Remaining tracking mutations wired end-to-end, each an optimistic Room write plus a
        queued outbox entry, same pattern as `logEpisodeWatched`:
        - `logEpisodeRating` / `logEpisodeReview` — append-only logs via the existing
          `EpisodeRatingDao` and a new `EpisodeReviewEntity`/`EpisodeReviewDao` (no local
          `episode_reviews` mirror existed before this pass; Room version bumped 1 → 2 with
          `fallbackToDestructiveMigration` — acceptable pre-distribution, since the only
          local data is `DevFixtureSeed`'s re-seed-on-empty fixtures).
        - `logViewing` — append-only re-watch timeline entry via the existing `ViewingDao`.
        - `updateTitleStatus` — the one in-place update rather than an append; outbox
          operation is `"update"`, and `updatedAt` is stamped fresh (feeds the last-write-wins
          conflict handling above, not optional).
        `TitleDetailScreen` exposes all four as bare text-button/star-tap/inline-textfield
        affordances (status pills, 5-star tap row, expandable review field, "Log a viewing"),
        matching the existing "Mark watched" austerity — this pass is the write path, not UI
        polish.
  - [x] Verified: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` — 0 lint
        issues, build succeeds.
  - [x] Verified live on the same Android Studio emulator (2026-07-13): cleared app data,
        confirmed the v1 → v2 destructive migration re-seeds cleanly, then exercised all four
        new mutations on real UI (rated an episode 4★, submitted an episode review, changed a
        title's status to WATCHED, logged a new viewing). Pulled the on-device database and
        confirmed each: `episode_ratings`/`episode_reviews`/`viewings` gained the expected new
        rows, `titles.status`/`updatedAt` updated in place, and `mutation_outbox` had four
        matching entries (`episode_rating`, `episode_review`, `title` (`operation='update'`),
        `viewing`), all `attemptCount=0` with no error. No crashes in logcat.
- [ ] Phase 3 — Ledger, preferences, accessibility, and performance polish. **Started:**
  - [x] Theme persistence: `PreferencesRepository` (`data` module) wraps a Preferences
        DataStore (`cinemarchive_prefs`) storing the selected `ArchiveThemeMode` — local-only,
        no Room/sync involvement, distinct from the still-unimplemented `user_prefs`-backed
        server persistence in `docs/android-parity-matrix.md`. `ArchiveThemeMode` moved from
        `core:designsystem` to `core:model` so the data layer can reference it without a
        Compose dependency. `MainActivity` collects the stored mode and applies it via
        `CinemArchiveTheme`; the Library top bar gained a bare cycle-through-four-themes text
        button (DARK → LIGHT → NOIR → MATRIX → …) as the only UI needed to exercise it — no
        dedicated settings screen yet.
  - [x] Verified: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` — 0 lint
        issues, build succeeds.
  - [x] Verified live on the same Android Studio emulator (2026-07-13): cycled the theme to
        NOIR, force-stopped the app (full process death, not just backgrounding), relaunched,
        and confirmed NOIR was still selected and applied. Confirmed
        `files/datastore/cinemarchive_prefs.preferences_pb` exists on-device. No crashes in
        logcat.
  - [x] Ledger contract documented: `docs/android-contracts/ledger.md` +
        `fixtures/ledger.json`, clearing the "Contract completion rules" gate
        `docs/android-parity-matrix.md` requires before any Ledger implementation (even
        read-only) can start. Covers the `LedgerWidget`/`user_prefs.ledger_layout` shape and
        its normalization/clamping rules, all 20 widgets (data inputs + non-obvious
        calculation rules, e.g. rating widgets read the title-level `Title.rating` only,
        never per-episode/per-viewing ratings; streak detection is the one date-bucketing
        panel that also folds in episode watch events), the RLS matrix (`user_prefs` plus the
        one exception — the "At the Movies" widget's owner-only `cinema_outings` join, which
        must visibly degrade rather than disappear for friend/shared viewers), the
        800ms-debounced last-write-wins layout persistence path and its known concurrency
        gaps, and a flagged accessibility shortfall in the *current web app* (five
        graphic-only widgets are mouse/touch-tooltip-only today) that Android should not
        port as sufficient. Corrected a matrix typo along the way:
        `user_prefs.ledger_prefs` → the real column, `ledger_layout`.
  - [x] Ledger implementation — started: a new `feature:ledger` module and `LedgerRepository`
        (`data` module) port `computeLedgerStats` from `ledgerStats.ts` (the hero stat ribbon
        only — total movies, total series, total viewings, average rating, movie minutes
        watched; TV minutes deferred, see `LedgerStats` kdoc), reachable from a new "Ledger"
        button on the Library top bar. Deliberately **not** the 20-widget customizable board:
        that needs `user_prefs.ledger_layout` sync, which needs a real `RemoteMutationWriter`
        (still stubbed, same physical-device gap as everything else touching real sync).
        `TitleDao.observeAllTitles()` and `ViewingDao.observeTotalViewingCount()` are new
        queries backing it; no schema/version change needed (existing tables only).
  - [x] Verified: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` — 0 lint
        issues, build succeeds.
  - [x] Verified live on the same Android Studio emulator (2026-07-13): tapped into the new
        Ledger screen and confirmed every stat matched the on-device Room data by hand (2
        movies, 1 series, 2 viewings, ★4.5 average — only Inception has a title-level rating
        — 148 movie-minutes watched — only Inception is a WATCHED movie, Fight Club is still
        watchlist). Confirmed the back button returns cleanly to Library. No crashes in
        logcat.
  - [ ] Full 20-widget customizable board — not started; today's slice is the hero ribbon
        only, ported from `ledgerStats.ts`. The remaining 19 widgets in `ledgerDerive.ts` /
        `ledgerPanels.ts`, the drag/resize/settings edit mode, and `user_prefs.ledger_layout`
        sync are all separate, larger follow-on work.
  - [ ] Accessibility and performance polish — not actionable yet; deferred until there's
        enough UI surface (the full Ledger board, a real settings screen) to apply them to.
- [ ] Phase 4 — sharing, social, notifications, and push.
- [ ] Phase 5 — beta hardening and release operations.
