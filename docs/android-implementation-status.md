# Android implementation status

This checklist is the execution record for `docs/native-android-app-plan.md`. It adds two missing foundation steps: confirming the permanent application ID/Play owner before distribution, and proving the locally selected Android toolchain resolves in CI.

## Phase 0 — Discovery and foundations

- [x] Create an isolated native Android Gradle project in this monorepo.
- [x] Add a version catalog, Gradle wrapper, API 31+ support baseline, and JDK 17 build requirement.
- [x] Establish `app`, `core:model`, `core:designsystem`, `core:database`, `data`, and `feature:library` boundaries.
- [x] Add `CinemArchiveTheme` foundations for dark, light, noir, and matrix modes.
- [x] Build a Room-backed, read-only Library prototype with immutable UI state and a screen-scoped ViewModel.
- [x] Record the provisional package identity and toolchain decision in ADR 0001.
- [ ] Confirm permanent package name, Play owner, signing owner, privacy owner, and support channel.
- [ ] Audit the web contracts/RLS/media rules and publish the Android parity matrix and fixtures.
- [ ] Add protected Supabase test access, session restoration, verified App Links, and physical-device Credential Manager proof.
- [ ] Add a screenshot/golden-test harness and CI matrix after the first emulator image is chosen.

## Later phases

- [ ] Phase 1 — read-only product spine and incremental sync.
- [ ] Phase 2 — durable tracking mutations, outbox, and conflict handling.
- [ ] Phase 3 — Ledger, preferences, accessibility, and performance polish.
- [ ] Phase 4 — sharing, social, notifications, and push.
- [ ] Phase 5 — beta hardening and release operations.
