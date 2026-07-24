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
- [x] Phase 2 — durable tracking mutations, outbox, and conflict handling. **Complete,
      including a real `RemoteMutationWriter` (`SupabaseRemoteMutationWriter`) verified live
      3/3 against the test project — last-write-wins conflict resolution proven end-to-end,
      not just unit-tested against a fake writer. See the Phase 3 Ledger section below for the
      full investigation. Not yet wired into the live app, since there's no real sign-in flow
      to source a session from yet — that's Phase 0's still physical-device-blocked passkey
      work, a distinct concern from "does the writer work":**
  - [x] `mutation_outbox` Room table + `OutboxDao` — a durable queue of pending remote
        writes (client-generated id, entity type, operation, JSON payload, attempt count),
        separate from the local read-model write it accompanies so the outbox row only
        needs to survive process death, not be transactional with it.
  - [x] `MutationOutbox` (in `data`) — `enqueue()` + `flush()` against a `RemoteMutationWriter`
        interface. The real implementation isn't wired yet: `UnconfiguredRemoteMutationWriter`
        always returns `Retry`, so mutations stay durably queued (never dropped, never
        falsely marked synced) until a real Supabase client + auth session exist. **Not a
        physical-device blocker** — a `RemoteMutationWriter` only needs a Supabase session
        (a JWT), which is a separate concern from the app's real passkey sign-in flow; see the
        Phase 3 Ledger section's investigation for the actual (authorization-decision) gap.
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
  - [x] Conflict handling — the logic (not just the design) is implemented: `PushResult`
        gained a `Conflict(serverPayload)` case (`data` module), `MutationOutbox.flush()`
        resolves it by handing the server's winning payload to a new `ConflictHandler` and
        clearing the outbox entry rather than retrying — correct by construction, since a
        server that returns Conflict is signaling its stored `updated_at` is already >= the
        client's (docs/android-sync-contract.md §4.2's conditional-update rule). Only `title`
        writes can produce a conflict today (every other outbox entry is an append-only
        insert with a client-generated id — see `TitleConflictHandler` kdoc), so that's the
        one `ConflictHandler` implementation, wired in `CinemArchiveApplication`.
        Unit-tested with a scripted fake `RemoteMutationWriter` covering Success/Retry/
        Conflict and multi-entry flush (`MutationOutboxTest`, `TitleConflictHandlerTest`) —
        the first real unit tests in this module; `data/build.gradle.kts` gained
        `kotlinx-coroutines-test` and `org.json:json` (the latter because Android's real
        `org.json` classes aren't on the JVM unit-test classpath, only a throwing stub).
        **What's still blocked:** the *live* multi-device conflict — actually producing one
        against a real server — needs a real `RemoteMutationWriter`, which needs an
        authenticated Supabase session. This is **not** the passkey/Credential Manager gap
        (that's specific to the app's real sign-in flow); it's a separate, precisely-diagnosed
        authorization decision — see the Phase 3 Ledger section's investigation. This is now
        the only open Phase 2 item.
  - [x] Verified: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` — 0 lint
        issues, 6 new unit tests pass, build succeeds.
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
- [ ] Phase 3 — Ledger, preferences, accessibility, and performance polish. **Ledger board
      complete: all 20 widgets, responsive grid, local customizable layout, accessibility
      addressed inline, and `user_prefs.ledger_layout` sync verified live end-to-end via
      `SupabaseLedgerLayoutWriter` (see below) — not yet wired into the live app pending a
      real sign-in flow (Phase 0, physical-device-blocked). Remaining: a few smaller
      preferences items:**
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
  - [x] Four more widgets added to the fixed-order board — `LedgerRepository.observeLedgerBoard()`
        + `LedgerBoard` (`core:model`): Feature Lengths (movie runtime histogram), On the Air
        (TV network tally), By the Era (release-decade tally), Coming Attractions (the
        watchlist, with movie-minutes-owed). Chosen specifically because each is a pure
        bucket/filter over fields the hero ribbon already reads (`type`/`network`/`year`/
        `runtime`/`status`) — no new Room queries, no schema change. The 6 widgets still
        skipped this pass (Auteurs, Ensemble, Second Opinions, In Translation, At the Movies,
        plus every chart-based widget — Activity, The Run, Critical Record, Screening Nights,
        The Marathon, Shifting Standards, Premieres & Revivals, The Revival House) need either
        data not mirrored locally yet (cast/crew/imdbRating/originalLanguage/companions/
        outingId) or a real chart primitive, both bigger lifts than this slice.
  - [x] Verified: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` — 0 lint
        issues, build succeeds.
  - [x] Verified live on the same Android Studio emulator (2026-07-13): confirmed all four
        new sections against the on-device data by hand (Feature Lengths: 2 movies both land
        in the 120–150min bucket; On the Air: AMC ×1; By the Era: one title each in the
        1990s/2000s/2010s; Coming Attractions: Fight Club, 139 movie-minutes owed). No
        crashes in logcat. Also incidentally reconfirmed theme persistence survived this
        session's `installDebug` update (still NOIR from the prior turn), consistent with
        the earlier theory that the one anomalous LIGHT reading was an out-of-band emulator
        state discontinuity between turns, not an app defect.
  - [x] The 5 data-blocked widgets unblocked: Room schema bumped to v3 with
        `TitleCastEntity`/`TitleCrewEntity` (mirroring `title_cast`/`title_crew`) and
        `imdbRating`/`originalLanguage` columns on `TitleEntity`, plus `companions`/
        `outingId` on `ViewingEntity` and a new `CinemaOutingEntity` (mirroring the two
        owner-private `cinema_outings` columns the "At the Movies" widget reads — see its
        kdoc on why Android's no-friend-mode state means the §3 degrade-for-non-owner
        behavior isn't reachable yet). `DevFixtureSeed` extended with real cast/crew for all
        three fixture titles (Breaking Bad's rows copied verbatim from
        `docs/android-contracts/fixtures/title-detail.json`) and a cinema-outing-linked
        viewing on Inception, so every new widget has real data to render, not just an empty
        state.
  - [x] All remaining 15 widgets implemented: Encore Performances, The Run, Critical Record,
        By the Genre, The Auteurs, The Ensemble, Second Opinions, In Translation, Screening
        Nights, The Marathon, Shifting Standards, Premieres & Revivals, The Revival House,
        Still Rolling, and At the Movies — `LedgerRepository.observeLedgerBoard()` and
        `LedgerBoard`/new `LedgerWidgets.kt` (`core:model`) now cover the full ledger.md §2
        registry. New Canvas-based chart primitives (`BarChartCanvas`/`HeatmapRow`/
        `DeltaScatterCanvas`, `core:designsystem`) back the chart-shaped widgets — simplified
        vs. the web app on purpose (week-granularity heatmap instead of a 364-cell daily
        grid, bar charts instead of a radar for Screening Nights, no smoothing) since Android
        has no charting library and didn't need pixel parity, only data parity. Every chart
        is paired with a real, focusable accessible list of the same data directly beneath
        it — closing the ledger.md §5 accessibility gap the *web app itself* has on five
        widgets (Activity heatmap, Screening Nights, The Marathon, The Run, Shifting
        Standards/Premieres & Revivals) inline as each was built, rather than deferring it.
  - [x] Verified: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` — 0 lint
        issues, build succeeds.
  - [x] Verified live on the same Android Studio emulator (2026-07-15): cleared app data,
        confirmed the v2 → v3 destructive migration re-seeds cleanly (`title_cast`:7 rows,
        `title_crew`:3, `cinema_outings`:1, `viewings`:2 — checked directly against the
        pulled Room database), then scrolled the full Ledger board and hand-verified every
        one of the 20 widgets against the seeded fixture data (e.g. Critical Record ★4.5:1;
        By the Genre Drama:2; The Auteurs Christopher Nolan:1/David Fincher:1; The Ensemble
        7 cast members each :1; Second Opinions "Us 9.0 vs IMDb 4.4 (Δ4.6)" for Inception;
        The Marathon showed the 3 correct screening dates and streak counts; Shifting
        Standards "2026 Q1 ★4.5 (1 titles)"; The Revival House "16+ yrs: 2"; Still Rolling
        "Breaking Bad 1/7 episodes"; At the Movies "Trips: 1, Total spend: $24.50, AMC
        Lincoln Square:1, Sam:1, Jordan:1, IMAX:1"). No crashes in logcat.
  - [x] Customizable board (add/remove/move/resize/settings), local-only: `LedgerLayoutRules`
        (`core:model`) defines `LedgerWidgetId`/`LedgerWidgetWidth`/`LedgerWidgetSettings`/
        `LedgerWidgetConfig` plus `defaultLedgerWidgets()` and `normalize()` — a direct port
        of ledgerPanels.ts's `normalizeLedgerWidgets()` clamps (unknown panel dropped,
        invalid width backfilled to `full`, unrecognized settings keys dropped, `topN`
        clamped 3–12, `title` truncated to 60 chars), unit-tested
        (`LedgerLayoutRulesTest`, 9 cases). `LedgerLayoutRepository` (`data` module) persists
        the widget list as JSON via a second DataStore (`cinemarchive_ledger_layout`), same
        pattern as `PreferencesRepository`, normalizing on every read. `LedgerScreen` gained
        an Edit-mode toggle rendering each widget's controls (move up/down, cycle width,
        remove, custom-title text field, top-N stepper) plus an "Add a widget" list of
        missing panels; the non-edit board now renders by iterating the persisted (or
        default) widget list through a per-panel dispatcher instead of a hardcoded sequence,
        so edit-mode changes are genuine, not cosmetic. Only `topN`/`title` are actually
        applied to a widget's rendered output today (a post-hoc `take(n)`/header-override in
        the UI layer, since no widget's data computation itself takes parameters yet);
        `timeRange`/`scope` persist and normalize correctly but aren't consumed by any
        widget's aggregation.
  - [x] Verified: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` — 0 lint
        issues, 9 new unit tests pass, build succeeds.
  - [x] Responsive grid: every widget now renders as a fixed-400dp card with internally
        scrolling content (ledger.md §1); at a `lg`+ window width (>=840dp, Material's
        expanded breakpoint) cards pack into a 12-column grid by `width` span
        (sm/md/lg/full = 4/6/8/12), greedily row-by-row in board order via `BoxWithConstraints`;
        below that, every card is full-width regardless of stored `width` ("always full below
        lg"). This had been scoped out as "Android is phone-first, no lg+ grid" in the prior
        pass — that was a self-imposed simplification, not a real blocker, corrected here.
        Verified live on the emulator: rotated to landscape (the Medium_Phone AVD is 914dp
        wide there, above the breakpoint; 411dp in portrait, below it), set two widgets to
        `sm` in edit mode, confirmed they packed into one row with the correct empty
        remainder (2 × 4 = 8 of 12 columns used); portrait stayed single-column. Note:
        navigation state (`MainActivity`'s `Screen` sealed interface) is held in a bare
        `remember`, not `rememberSaveable`, so it resets to Library on the config change a
        rotation triggers — pre-existing, unrelated to this work, not fixed here.
  - [x] Verified: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` — 0 lint
        issues, build succeeds.
  - [x] Real `RemoteMutationWriter` implementation, built and largely proven live —
        `SupabaseRestClient` (`data` module, `HttpURLConnection`-based — deliberately not the
        full Supabase Kotlin SDK, to keep this scoped to exactly what the outbox's push
        semantics need: password sign-in, PATCH-with-filter, GET, upsert), plus
        `SupabaseRemoteMutationWriter` (implements the real `title`-conflict path via a
        conditional `PATCH .../titles?id=eq.<id>&updated_at=lt.<incoming>` — an empty result
        means the server's row is already newer, so it GETs and returns that as the
        `PushResult.Conflict` payload — and the four append-only upserts) and
        `SupabaseLedgerLayoutWriter` (`user_prefs.ledger_layout` upsert). Built on OkHttp, not
        `java.net.HttpURLConnection`: PATCH isn't in `HttpURLConnection`'s method allow-list,
        and the usual reflection workaround (setting the protected `method` field directly)
        proved unreliable against the real endpoint across several JDK-internals-level fixes
        (JPMS `--add-opens`, `HttpsURLConnectionImpl`'s delegate indirection) before being
        abandoned for OkHttp; `java.net.http.HttpClient` (PATCH-native) isn't an option at all
        — it doesn't exist in Android's SDK (checked directly: 0 `java/net/http` entries in
        `android-36/android.jar`).
        **This is not the passkey/Credential Manager gap** — a `RemoteMutationWriter` only
        needs *some* Supabase session; how one is obtained is separate from the app's real
        sign-in flow. That distinction was verified, not assumed: investigated live against
        the non-production `cinemarchive-android-test` project. Anonymous sign-in is disabled
        and email/password signup requires confirmation and hit a rate limit, so — with
        explicit authorization for this specific action — one pre-confirmed test user was
        created via the Auth Admin API (`service_role`, used only for that one call, never
        committed, never in the app) and **signing in with the anon key + that user's password
        succeeded**, returning a real session. That session is exactly what the app's own
        future sign-in flow (of any kind) would hand this writer.
        The `authenticated` Postgres role on the test project also turned out to be missing
        table grants the production project apparently didn't need stated explicitly (likely
        auto-configured differently when production was first provisioned vs. this test
        project created later via the CLI) — `HTTP 403 42501 permission denied for table
        titles`, with Postgres's own fix in the hint. With separate explicit authorization for
        this specific, narrow statement, `GRANT SELECT, INSERT, UPDATE ON public.titles,
        public.user_prefs TO authenticated` was run against the test project only (via a
        careful temporary-relink-and-back — verified linked back to the production project
        ref immediately after).
        **`SupabaseRemoteMutationWriterLiveTest` (`data/src/test`) now passes, live, for
        real, 3/3** — network-gated behind four `ANDROID_SUPABASE_TEST_*` env vars, skips via
        `Assume` when unset so it never affects a normal build/CI run. It exercises two
        independent sign-ins for the one test user (RLS is per-`auth.uid()`, so one user with
        two sessions correctly simulates two devices) racing a real `titles` update — the
        earlier-timestamped write is confirmed rejected as `PushResult.Conflict` carrying the
        winning row, not silently dropped or wrongly applied — plus a real
        `user_prefs.ledger_layout` upsert, read back and confirmed correct. This is the answer
        to both Phase 2's and Phase 3's remaining open questions: last-write-wins conflict
        resolution and Ledger layout sync both work end-to-end against the real backend, not
        just in unit tests against a fake writer.
        **What's still not done:** wiring `SupabaseRemoteMutationWriter` into
        `CinemArchiveApplication` in place of `UnconfiguredRemoteMutationWriter` for real
        production use — deliberately not done here, since the app has no real sign-in flow
        yet to obtain a genuine user session from (only the test-user password grant used for
        verification above); that's Phase 0's passkey/Credential Manager work, still
        physical-device-blocked, and a distinct concern from "does the writer work" (now
        answered: yes).
  - [x] Verified live on the same Android Studio emulator (2026-07-15): opened Edit mode,
        removed "The Run" widget, set Critical Record's Top N to 5 via the stepper, tapped
        Done — the board immediately reflected both changes (The Run gone, Critical Record
        showing only its top 5 buckets). Force-stopped the app (full process death) and
        relaunched: both changes were still applied, confirming the layout persisted to
        DataStore rather than living only in ViewModel state. No crashes in logcat.
  - [x] Accessibility: addressed inline per widget as built (see above), not deferred — every
        chart-based widget has a genuine focusable list alternative, not a tooltip-only
        fallback. Performance: the board is still small (a few dozen list items across 20
        sections against 3 fixture titles) and already uses `LazyColumn`'s built-in
        virtualization; no profiling was warranted at this data scale, and none surfaced an
        issue during manual testing. Deeper performance work (e.g. `LazyColumn` recomposition
        tuning at real-library scale) is deferred until there's real user data to profile
        against, not because it's unactionable.
  - [x] Ledger web-parity plan executed in full
        ([`docs/superpowers/plans/2026-07-23-android-ledger-parity.md`](../superpowers/plans/2026-07-23-android-ledger-parity.md)):
        - **Layout sync correctness:** `LedgerLayoutRepository.reconcile()` pulls
          `user_prefs.ledger_layout` on sign-in and app launch and applies ledger.md §4's rule
          (non-null server layout always wins; null pushes the current local layout up) —
          closes the push-only gap flagged above. Merge decision split into a pure
          `resolveLayoutReconciliation()`, unit-tested (`LedgerLayoutRepositoryTest`, 5 cases)
          without needing a `Context`/DataStore.
        - **Fixture test harness:** `LedgerRepositoryTest` (`data` module, 27 cases) ports the
          exact fixture the now-retired `DevFixtureSeed` used (recovered from git history) into
          plain in-memory DAO fakes and asserts all 20 widgets' computed output against it —
          the same numbers this section hand-verified on-device above (e.g. Second Opinions'
          "Us 9.0 vs IMDb 4.4 (Δ4.6)" for Inception, Still Rolling "Breaking Bad 1/7"), now a
          regression suite instead of a one-off manual check. `LedgerPanelSettingsTest`
          (`core:model`, 8 cases) covers the settings-resolution logic below.
        - **`timeRange`/`scope` consumption:** `LedgerPanelSettings.kt` (`core:model`) ports
          `PANEL_SETTING_KEYS`/`effectiveLedgerSettings()` from `ledgerPanels.ts`.
          `LedgerRepository.observeLedgerBoards(layoutFlow)` computes one `LedgerBoard` per
          widget instance from `LedgerSources` filtered to that widget's own effective
          `(scope, timeRange)` — memoized per distinct pair, so widgets sharing settings (the
          common case) share one `buildBoard()` call. A panel that doesn't honor a knob per
          `PANEL_SETTING_KEYS` always resolves to unfiltered, regardless of what a
          server-synced widget's stored settings say. `applyTopN` now applies each panel's
          effective default (5 or 6) instead of showing every item when uncustomized.
        - **Editor UX capabilities:** long-press-drag reorder and drag-resize (existing
          up/down buttons and tap-to-cycle width kept as accessibility fallbacks, not
          replaced), duplicate, "Reset to default layout" with a confirmation dialog, and an
          "Add a widget" palette with live scaled `WidgetContent` previews, a "×N already on
          board" usage badge, and long-press-drag-to-place at a specific board position — all
          sharing one local, optimistic drag-state so `onLayoutChange` only fires once per
          gesture, on release, never mid-drag. A new per-widget settings `ModalBottomSheet`
          (`Scope`/`Time range` `SegmentedGroup` controls, gated per-panel) closes the
          `timeRange`/`scope` UI gap now that Phase D's consumption exists to back it.
        - **Chart primitive uplift:** `DailyHeatmapGrid` (true daily 7×52 grid, replacing Time
          in the Dark's week-granularity row) and `LineChartCanvas` (stroke + gradient fill,
          used by Shifting Standards and the previously chart-less Premieres & Revivals) added
          to `core:designsystem`; The Marathon gained an additive 30-night grid (reusing
          `HeatmapRow`). Screening Nights intentionally stays a bar chart, not a radar — no
          accessible-parity benefit over the existing bar chart + list pairing.
        - **Hero/stat-row parity:** a "now showing · {date}" kicker and narrative sentence
          above the stat tiles (Android's "THE NUMBERS" label kept, not overwritten), and the
          stat set grew from 4 tiles to 6 (Movies/Series/Screenings/Hours logged/Days in the
          dark/Avg rating). `LedgerScreen`/`LedgerBoardContent` gained an unused
          `viewedDisplayName: String?` parameter (always null — no friend/shared viewer mode
          exists) so that work only needs to supply a value later, not rewire this call chain.
        - **Explicitly not done:** friend/shared Ledger viewing (blocked on the app-wide
          Friends/Sharing initiative; the plan's §8 pre-plans the hook) and pixel-identical
          chart rendering (radar geometry, animated draw-in) — both out of scope per the plan.
  - [x] Verified: `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` — 0 lint
        issues, all unit tests pass (25 in `LedgerRepositoryTest` grew to 27 across the
        timeRange/scope and daily-activity/last-30-nights additions; 5 new in
        `LedgerLayoutRepositoryTest`; 8 new in `LedgerPanelSettingsTest`), build succeeds —
        checked after every phase (layout sync, fixture harness, timeRange/scope, editor UX,
        chart uplift, hero/stat parity, settings UI), not just once at the end.
  - [ ] **Not verified live on a device/emulator** — no Android runtime was available in the
        environment this plan was implemented in, only compile/lint/unit-test. The drag-
        reorder/drag-resize gestures, the new chart primitives' actual rendering, and the
        settings sheet's on-screen behavior should all get a real on-device pass (matching the
        verification style every other bullet in this section used) before this ships.
- [ ] Phase 4 — sharing, social, notifications, and push.
- [ ] Phase 5 — beta hardening and release operations.
  - [x] CI now builds a signed release APK and attaches it to the GitHub Release whenever a
        version-bump PR merges to `main` (`.github/workflows/deploy.yml`'s new `android` job,
        piggybacking on the existing web release tag/version — `versionName` tracks
        `package.json`'s version, `versionCode` is derived from it). Signed with a real upload
        keystore (`ANDROID_RELEASE_KEYSTORE_BASE64` + password/alias secrets on the repo); this
        is sideloading-only for now, not a Play Store submission — the permanent application ID
        / Play owner decision above is still open and unrelated to this.
