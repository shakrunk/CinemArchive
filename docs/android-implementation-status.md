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
  - [ ] Conflict handling — designed (last-write-wins by `updated_at`, per
        docs/android-sync-contract.md §4.2) but not implementable/testable until a real
        `RemoteMutationWriter` exists to actually produce conflicts against.
  - [ ] Remaining tracking mutations (rating, review, status change, viewing log) — not yet
        wired; `logEpisodeWatched` is the one proof-of-pattern for this pass.
- [ ] Phase 3 — Ledger, preferences, accessibility, and performance polish.
- [ ] Phase 4 — sharing, social, notifications, and push.
- [ ] Phase 5 — beta hardening and release operations.
