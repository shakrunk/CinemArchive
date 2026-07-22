# Android Cinema Outings — Implementation Plan

**Status:** Draft — ready for task-level breakdown
**Date:** 2026-07-21
**Companion to:** [`docs/superpowers/plans/2026-07-11-cinema-outings.md`](./2026-07-11-cinema-outings.md) (the web
feature this ports), [`docs/android-parity-matrix.md`](../../android-parity-matrix.md)

> **For agentic workers:** verification gate for every phase is
> `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` (matches
> `.github/workflows/android.yml`) all green, plus a `CHANGELOG.md` `[Unreleased]` entry for
> user-facing phases, before committing.

---

## 1. Why this feature, and why it's different from the web port

The user experience this unlocks — buy tickets, forget about it, walk out of the theater to
find the title already marked watched with the theater and company logged, and a prompt
asking how it was — is the single highest-leverage moment CinemArchive can automate. It's
also the one place the **Android app can legitimately exceed the web app**: a phone can fire
a real system notification at the moment the movie lets out; a browser tab cannot.

But this is **not a straight port of the web plan**. The web plan assumes a live backend:
`complete_due_outings()` Postgres RPC, a `notifications` table with an inbox UI, and the
friends/social stack for plan-sharing. None of that exists live on Android today:

- Auth is still **Discovery** per the parity matrix — passkey sign-in isn't wired, so there
  is no authenticated Supabase session to call RPCs through.
- All Android data today is local Room, seeded by `DevFixtureSeed.kt` — there is no live
  read *or* write sync for any domain except a tested-but-unwired Ledger-layout writer
  (§4 below).
- There is no notifications inbox, no WorkManager/AlarmManager usage, and no bottom-sheet
  component anywhere in the codebase (confirmed by grep — zero matches for all three).

So this plan's spine is: **build the local-first version whose completion choke point is a
plain Kotlin function over Room**, ship the headline flow entirely offline, and explicitly
gate everything that needs a live backend (server-confirmed completion across devices, inbox
sync, in-app plan sharing) behind auth+sync landing. The Android version should be *narrower
than the web spec on backend-dependent surface, and wider on-device* (a real notification,
not just an in-app toast).

---

## 2. Current state (grounding facts)

| Area | State |
|---|---|
| `CinemaOutingEntity` (`core/database/Entities.kt:249`) | Deliberately minimal — `id, titleId, format, ticketPrice` only, mirroring the two owner-private columns the Ledger widget reads. No `showtime`, `status`, `venue`, `companions`, etc. |
| `CinemaOutingDao` (`core/database/Daos.kt:160`) | Read-only: `observeAllOutings()` + `upsertAll()` for fixture seeding. No CRUD for user-initiated writes. |
| Ledger "At the Movies" | **Already implemented** — `LedgerRepository.moviegoing()` (`data/LedgerRepository.kt:385`) computes trip counts, venues, favorite theater, companions, formats, spend, joined via `outingId` on `ViewingEntity`. Nothing to build here; note as done. |
| `feature:upnext` | One file, `UpNextScreen.kt`. `UpNextBoard` (`core/model/UpNextBoard.kt:14`) is just two lists (`watching`, `watchlist`) — no pluggable "sections" abstraction yet. |
| Navigation | No `androidx.navigation`. `MainActivity.kt`'s `CinemArchiveApp` (L192-278) uses a local `Tab` enum + a `sealed interface Overlay` (`Detail`, `Add`, `Profile`, `Appearance`, `About`) rendered as full-screen siblings of the `Scaffold`. No bottom-sheet infra anywhere. |
| `TitleDetailScreen.kt` | Status segmented control, star rating, "Log a viewing" button (L254), per-episode actions. This is where a ticket CTA and scheduled-outing banner attach. |
| Write-path pattern | `RemoteMutationWriter` interface + `OutboxEntity` queue already exist (`data/RemoteMutationWriter.kt`). `SupabaseLedgerLayoutWriter` is a real, tested implementation that is **deliberately not wired into the live app** — `UnconfiguredRemoteMutationWriter` (always `Retry`) is what's actually DI'd, pending a physical-device passkey session. This is the template Cinema Outings' write path should follow. |
| Notifications | Zero matches for `"Notification"` anywhere in `android/`. Must be built from scratch. |
| Background scheduling | Zero matches for `WorkManager`/`AlarmManager`/`androidx.work`. Must be added. |
| Runtime data | `TitleDetail.runtime: Int?` (`core/model/TitleDetail.kt:19`) already exists — the schedule form can prefill it, same as web. |

---

## 3. Locked decisions for Android v1

| Decision | Choice | Why |
|---|---|---|
| **Completion authority** | A local, on-device Kotlin function (`completeDueOutings(now)`) reconciles Room state — the Android analogue of the web's SQL RPC, minus the network round-trip. It is idempotent and safe to call redundantly. | No live backend to call yet; this is the thing that *can* ship today. |
| **When reconciliation runs** | App launch, `onResume`, **and** a real exact-alarm fire at `ends_at` (§6) — a superset of the web triggers, since the alarm covers "app was fully closed," which the web PWA can't do without Web Push. | Matches web parity on foreground triggers, exceeds it on the closed-app case. |
| **Notifications inbox / cross-device sync** | **Deferred**, gated on Auth landing. v1's "how was it?" follow-up lives entirely in the Up Next "Fresh from the lobby" card (in-app) plus a real Android system notification (device-local, §6) — no server-side `notifications` table involvement. | The web inbox is a synced, multi-device concept; Android has no session to sync through. Building it now would be unused scaffolding. |
| **Plan sharing (§4.10 of the web plan)** | **Deferred**, gated on Auth + friends stack landing (both currently Discovery). | Depends on `is_friend()` / friend RLS that doesn't exist on Android yet. Out-of-app sharing (system share sheet with a text snippet + `.ics`) has no such dependency and ships in v1. |
| **Bottom sheet** | Introduce `ModalBottomSheet` (Material3, already on the Compose BOM) into `core:designsystem` now. Used 2×+ in this feature alone (schedule form, post-show card) and is a better mobile pattern than a full-screen `Overlay` for a short form. | The existing `Overlay` pattern was designed for full destinations (title detail, add-title), not transient forms; forcing every sheet through it doesn't scale and the codebase has no sheet primitive yet — better to add the missing primitive than to keep overloading `Overlay`. |
| **Scope** | Movies only (`MediaType.MOVIE`), matching web v1. | Same rationale as web §2 — TV theatrical events are a future note. |
| **Write path to Supabase** | Build `SupabaseCinemaOutingWriter : RemoteMutationWriter` for real (tested against the live/staging project like `SupabaseRemoteMutationWriterLiveTest`), route all outing mutations through the existing `OutboxEntity` queue, but DI only `UnconfiguredRemoteMutationWriter` into the live app until passkey auth exists. | Exact template already established for Ledger layout sync — don't invent a second pattern. |

---

## 4. Data model additions

### 4.1 `core:model` — new domain types

```kotlin
enum class OutingStatus { SCHEDULED, COMPLETED, MISSED, CANCELLED }
enum class CinemaFormat { STANDARD, IMAX, THREE_D, DOLBY, SEVENTY_MM, DRIVE_IN, OTHER }
data class Companion(val name: String, val friendUserId: String? = null)

data class CinemaOuting(
    val id: String,
    val titleId: String,
    val showtime: Instant,
    val previewsMinutes: Int,
    val runtimeMinutes: Int,
    val endsAt: Instant,          // recomputed on every edit, not just at creation
    val venue: String?,
    val companions: List<Companion>,
    val format: CinemaFormat?,
    val ticketPrice: Double?,
    val seat: String?,
    val bookingRef: String?,
    val notes: String?,
    val status: OutingStatus,
    val previousStatus: LibraryStatus?,   // captured at completion, for "didn't make it" revert
    val completedViewingId: String?,
    val followUpDismissedAt: Instant?,
    val createdAt: Instant,
)
```

`Viewing` (`core/model/TitleDetail.kt:47`) already has `venue`; add `companions: List<Companion>`
and `outingId: String?` to match `ViewingEntity`, which already carries both at the Room layer
(`Entities.kt:169-174`) — the model is currently behind the entity here.

### 4.2 `core:database` — expand the entity, bump the schema

Replace the minimal `CinemaOutingEntity` (`Entities.kt:249`) with the full shape (showtime,
previewsMinutes, runtimeMinutes, endsAt, venue, companions as JSON via `Converters`, format,
ticketPrice, seat, bookingRef, notes, status, previousStatus, completedViewingId,
followUpDismissedAt, createdAt, updatedAt). This is a Room schema version bump — add a
migration from the current schema (`schemas/.../3.json`) to the next, plus a
`MigrationTestHelper` test asserting the new columns and that existing rows (format,
ticketPrice, id, titleId) survive with the new columns nullable/defaulted.

`CinemaOutingDao` gains: `upsert(entity)`, `observeScheduled(): Flow<List<CinemaOutingEntity>>`,
`observeById`, `updateStatus(id, status, ...)`, `delete(id)` — full CRUD, not just
`upsertAll`/`observeAllOutings`.

### 4.3 `data` — `OutingsRepository`

New repository (sibling to `LibraryRepository`/`LedgerRepository`), owning:
- CRUD delegating to `CinemaOutingDao`, each write also enqueuing an `OutboxEntity` row
  (same shape/flow as existing outbox writes elsewhere in `data/`) so the eventual
  `SupabaseCinemaOutingWriter` has something to flush once wired live.
- `completeDueOutings(now: Instant): List<OutingTransition>` — the local completion function
  (§5): pure enough to unit test with a fake clock, but it does perform the Room writes
  (viewing insert, title status update, outing status update) transactionally.

### 4.4 Pure derivations module (new, `data` or `core:model`, unit-tested, no UI)

Mirrors the web's `src/store/outings.ts`:
- `computeMarqueeEntries(outings, titles, now)` — scheduled + now-showing + pending-follow-up,
  soonest-first.
- `outingPresentation(outing, now)` — countdown bucket: `in 12 days` → weekday+time (≤7d) →
  `TOMORROW` → `TONIGHT · 7:30 PM` → `NOW SHOWING`.
- `nextTransitionAt(outings)` — drives the alarm re-arm (§6).
- Exclusion rule: a title with a scheduled outing must not also render as a plain watchlist
  card in `UpNextBoard.watchlist`.

---

## 5. Completion engine (the choke point)

`OutingsRepository.completeDueOutings(now)`, called from every reconciliation trigger, for
each `status = SCHEDULED` row with `endsAt <= now`:

1. Insert a `ViewingEntity`: `date` = the *calendar date of `showtime`* (device's current
   default `ZoneId`; no per-outing zone storage needed since this never crosses devices in
   v1), `venue`, `companions`, `outingId` back-reference.
2. Capture `title.status` into `previousStatus`; set `title.status = WATCHED` iff not already
   watched (same "unwatched/watching/dropped all flip, watched stays" rule as web §4.2).
3. Update the outing: `status = COMPLETED`, `completedViewingId` set.
4. Return an `OutingTransition` so the UI can show the in-app toast / "Fresh from the lobby"
   card without a separate query.

Runs inside a single Room `@Transaction` per outing so a process death mid-completion can't
half-apply. Idempotent by construction (`WHERE status = 'scheduled' AND endsAt <= :now`) —
safe to call from launch, resume, *and* the alarm without double-logging.

"Didn't make it" revert (web §5.6) is the inverse: delete the auto-logged viewing, restore
`previousStatus` iff current status is still `WATCHED`, set outing → `MISSED`. Hidden once the
viewing has a rating, exactly as web specifies.

---

## 6. Local notification — the Android-only payoff

This is the one piece with no web equivalent and no backend dependency, so it's scoped as its
own phase (§9, Phase 5) deliberately kept separate from the completion logic in §5:

- On scheduling/editing an outing, schedule an **exact alarm** (`AlarmManager.setExactAndAllowWhileIdle`,
  API 31+ needs `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` handling) for `endsAt`, plus cancel/
  reschedule on edit or cancellation.
- The alarm's `BroadcastReceiver` calls the same `completeDueOutings(now)` function (§5), then
  posts a real notification on a new `cinema_outings` channel: *"{title} just let out — how
  was it?"*, tapping it opens the app straight to the post-show sheet (deep-link via an
  `Intent` extra the `MainActivity` reads into `Overlay.PostShow(outingId)`).
- Requires the API 33+ `POST_NOTIFICATIONS` runtime permission — request it contextually
  (at first outing scheduled), not at app launch.
- If the exact alarm is denied/unavailable (user disabled it in system settings), degrade
  gracefully to foreground-only reconciliation (§5) — never crash or silently drop the outing.

No WorkManager needed for v1: a single one-shot exact alarm per outing (re-armed on edit) is
simpler than a periodic worker and matches "one thing happens at one specific time," which is
exactly what `AlarmManager` is for.

---

## 7. UI additions

| Surface | Change |
|---|---|
| `core:designsystem` | New `ModalBottomSheet`-based components: `OutingScheduleSheet`, `PostShowSheet` (see §3 decision). |
| `TitleDetailScreen.kt` | "🎟 I've got tickets" button next to "Log a viewing" (L254) for watchlist/watching movies; overflow "Plan a cinema trip" for watched (rewatch); scheduled-outing banner under the header while `SCHEDULED`; ticket-stub line on viewing timeline entries that carry `venue`/`companions`. |
| `UpNextScreen.kt` / `UpNextBoard` | New `upcomingOutings: List<CinemaOuting>` field; new "ON THE MARQUEE" section rendered first (ahead of "CONTINUE WATCHING"), with a `MarqueeCard` showing the countdown chip and an **Add to calendar** action (`.ics` via Android's `ACTION_INSERT` calendar intent — no ICS file needed on-device, unlike the web's hand-rolled file, since Android has a native Calendar Provider intent for this). "Fresh from the lobby" card replaces a marquee card post-completion until dismissed/rated (14-day window, same as web). |
| Library poster wall (`LibraryScreen.kt`) | Small amber 🎟 corner badge on posters with a scheduled outing (same tier as existing poster badges), matching web §4.6. |
| `MainActivity.kt` | Extend `Overlay` sealed interface with `ScheduleOuting(titleId)` and `PostShow(outingId)` cases (or route them through the new bottom-sheet layer instead of the overlay stack — pick sheets per §3; only use `Overlay` for anything that needs to feel like a destination, not a transient form). |
| Ledger | Nothing — "At the Movies" already ships (§2). |

Out-of-app sharing (system share sheet + calendar intent) needs no new schema/backend and can
land in the same phase as scheduling — it's the one piece of §4.10 from the web plan that has
zero Android dependency gaps.

---

## 8. What's explicitly deferred (and what unblocks it)

| Deferred | Blocked on |
|---|---|
| Cross-device completion (server RPC call, not just local Room) | Passkey auth landing (parity matrix: Authentication, still Discovery) |
| Notifications inbox sync, `outing_completed`/`outing_plans_shared` types | Auth + a general Android notifications-inbox feature (doesn't exist for *any* domain yet, not just outings) |
| In-app plan sharing (`ShareOutingPanel`, `share_outing_plans` RPC) | Auth + friends/social stack (parity matrix: Friends and social, still Discovery) |
| `confirm`-before-complete preference | `user_prefs` sync (same blocker as Ledger layout sync) |

None of these block the headline flow. Do not build inbox/sharing scaffolding speculatively —
follow the same discipline the Ledger-layout writer already models (§4, "tested but unwired").

---

## 9. Implementation phases

Each phase ends at the verification gate (`./gradlew :app:assembleDebug :app:lintDebug
testDebugUnitTest`), an atomic conventional commit, and a `CHANGELOG.md [Unreleased]` entry
where user-facing.

- [ ] **Phase 1 — Data layer.** Expand `CinemaOutingEntity` + Room migration + migration test;
      full `CinemaOutingDao` CRUD; `core:model` domain types (§4.1); `Viewing` model gains
      `companions`/`outingId`; `OutingsRepository` CRUD + outbox enqueue;
      `SupabaseCinemaOutingWriter` built and tested (live/staging) but wired only as
      `UnconfiguredRemoteMutationWriter` in the live app DI graph, per §3.
- [ ] **Phase 2 — Pure derivations + completion engine.** `outings` derivations module (§4.4)
      and `completeDueOutings()` (§5), both unit-tested with fake clocks/fixtures — no UI yet.
      Cover: countdown buckets, marquee exclusion from watchlist, idempotent re-invocation,
      previous-status revert rules.
- [ ] **Phase 3 — Scheduling UX.** `ModalBottomSheet` primitive in `core:designsystem`;
      `OutingScheduleSheet`; title-detail CTA + banner; out-of-app share (system share sheet +
      calendar intent). Ship gate: schedule/edit/cancel round-trip through Room and the
      outbox.
- [ ] **Phase 4 — Marquee + completion + post-show (headline flow, end to end).**
      `UpNextBoard.upcomingOutings` + "ON THE MARQUEE" section + countdown tick; app-launch/
      resume reconciliation wired to `completeDueOutings()`; in-app toast; "Fresh from the
      lobby" card; `PostShowSheet` (rate/note/didn't-make-it); poster-wall 🎟 badge; ticket
      stub on the viewing timeline.
- [x] **Phase 5 — Real device notification (Android-only payoff, §6).** Exact alarm scheduling
      on create/edit/cancel; `POST_NOTIFICATIONS` permission flow; notification channel;
      tap-to-deep-link into title detail; graceful degrade when exact alarms are unavailable.
      Kept separate from Phase 4 so completion logic isn't entangled with permission/alarm
      plumbing, per the advisor's sequencing note. The notification also carries the title's
      poster (`BigPictureStyle` + large icon, fetched via Coil, degrading to a plain text
      notification if the poster fails to load). Verified end-to-end on a physical device
      (2026-07-21): real exact alarm fired, notification posted with a genuine poster bitmap
      in both the collapsed thumbnail and expanded big-picture view, tap opened the app to the
      correct title, and the `POST_NOTIFICATIONS`-denied path degrades without crashing. Note:
      the tap deep-links into title detail, not directly into an open `PostShowSheet` as
      originally scoped here — the user still taps "Rate" on the viewing row once there.
- [ ] **Phase 6 *(gated)* — Backend wiring.** Flip `SupabaseCinemaOutingWriter` live once
      passkey auth lands; add `outing_completed` push handling if/when a general Android
      notifications-inbox feature exists; in-app plan sharing once the friends stack lands.
      Update `docs/android-parity-matrix.md` with a new "Cinema outings" row at that point —
      it isn't tracked there yet and should be added alongside Phase 1, not deferred to here.

---

## 10. Testing strategy

- **Room migration test** (Phase 1): old-schema → new-schema, asserting data survival.
- **Unit tests** (Phase 2): derivations module and `completeDueOutings()` against fixture
  clocks — the highest-value tests in this feature, since they encode every edge case from
  the web plan's §5 (rules & edge cases) that still applies locally: runtime drift, missing
  runtime default, double-booking, didn't-make-it revert, dropped-title un-drop.
  `DevFixtureSeed.kt` should gain a scheduled + a just-ended outing fixture so Phase 3/4 UI
  work has something to render against immediately.
- **Compose UI tests**: marquee card countdown states (including `NOW SHOWING` and reduced-
  motion), schedule sheet validation, post-show sheet.
- Manual device verification for Phase 5 (exact alarms cannot be meaningfully unit-tested):
  schedule an outing 1-2 minutes out, background the app, confirm the notification fires and
  deep-links correctly, on both a permission-granted and permission-denied device state.

---

## 11. Non-goals (mirrors web §10, restated for Android)

Showtime lookup/ticket import, seat maps, concessions/loyalty tracking, TV theatrical events,
festival passes, and (for now) any surface that requires a synced multi-device notion of
"friend" or "notification." Cross-device completion via the server RPC is a non-goal *for this
plan* only in the sense that it's Phase 6-gated, not abandoned.
