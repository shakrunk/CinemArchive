# Android Self-Update UX — Implementation Plan

**Status:** Implemented through Phase 6 — see §10 for where the build differs from this plan
**Date:** 2026-10-25
**Scope:** Android only (sideloaded installs). Every shipped-source commit carries
`Parity: n/a: the web app has no installable package to update`.
**Reference implementation studied:** MorpheApp/morphe-manager v2.1.0
(`UpdateViewModel`, `ManagerUpdateRepository`, `ManagerChangelogDialog`, `UpdateCheckWorker`).

---

## 1. Goal

Make finding, downloading and installing an update a single, visible, recoverable flow:

1. The app tells the user an update exists without them visiting Settings → About.
2. They see what changed before downloading.
3. Download progress is real (percent and MB), not a spinner.
4. Declining or abandoning the system install prompt never forces a re-download.
5. Nothing ever silently stalls — every state has a visible next action.

Non-goals: Shizuku/root/external-installer support (Morphe has these; not useful here),
Play-installed builds (Play keeps updating them; the existing `InstallSource.PLAY_STORE`
gate stays).

---

## 2. Current state (grounding facts)

| Area | State today |
|---|---|
| `AppUpdateRepository` (`data/`) | Stateless `checkForUpdate()` against `releases/latest`. Parses `tag_name`, first `.apk` asset URL, `html_url`. Ignores `body` (release notes), asset `size`, `published_at`, and the `SHA256SUMS` asset CI publishes alongside the APK (`deploy.yml:217`). |
| Update result ownership | A `remember { mutableStateOf(...) }` in `AboutScreen.kt:372`. Nothing else can observe it. The launch-time check in `CinemArchiveApplication` discards its result. |
| `ApkInstaller` (`data/`) | Streams the HTTP body straight into a `PackageInstaller` session and commits. No progress reporting, no staged file, no integrity check. State: `Idle / Downloading / AwaitingConfirmation / Installed / Failed`. |
| `InstallStatusReceiver` (`app/`) | On `STATUS_PENDING_USER_ACTION` calls `startActivity(confirmation)` unconditionally. Declining (`STATUS_FAILURE_ABORTED`) resets to `Idle`, so a retry re-downloads. |
| Launch-time auto path | `CinemArchiveApplication.onCreate` checks and, if the install permission is granted, downloads and commits. `onCreate` also runs when the process is started in the background (alarms, `OutingCompletionReceiver`, `BootCompletedReceiver`). From the background, Android 10+ normally blocks the receiver's `startActivity`, so the prompt never appears and the state sits at `AwaitingConfirmation`. **Suspected, not yet reproduced on device** — verify as step 0 of Phase 3. |
| `UpdateInstallNotifier` | Low-importance notification, no `contentIntent` — tapping it does nothing, though its text says "Open CinemArchive to confirm the install." |
| UI | `UpdatesSection.kt` inside About: auto-check toggle, Check button, Install / Open release button, spinner + label while busy. No release notes, no progress, no entry point elsewhere. |
| Infra available | DataStore prefs (`PreferencesRepository`, has `autoCheckUpdates`). `MainActivity` already routes intent extras (`EXTRA_OPEN_TITLE_ID`, `onNewIntent`) and an `Overlay` sealed interface. **Not** available: WorkManager, `lifecycle-process`, any Markdown renderer. |
| Tests | Only `CompareVersionsTest`. No coverage of the repository, installer, or receiver. |

---

## 3. Target architecture

```
                 ┌────────────────────────────┐
 launch / resume │   AppUpdateController      │  app-scoped, in data/
 manual "Check" ─▶│  state: StateFlow<UpdateUi>│◀── InstallStatusReceiver
 background work │  check() download() install()│
                 └──────────┬─────────────────┘
                            │ observed by
        ┌───────────────────┼────────────────────┐
   Home banner        Update sheet          About row / notification
```

One app-scoped controller owns the whole lifecycle (check → download → staged → install →
result). Every surface reads the same `StateFlow`; no screen holds update state of its own.
This mirrors Morphe's `ManagerUpdateRepository` + single shared `UpdateViewModel`.

### State model (replaces `UpdateCheckResult` + `ApkInstallState` as the UI's input)

```kotlin
data class UpdateUi(
    val check: CheckState,      // Idle | Checking | UpToDate | Available(release) | Failed(msg)
    val install: InstallStage,  // see below; only meaningful when check is Available
)

sealed interface InstallStage {
    data object NotStarted
    data class Downloading(val bytes: Long, val total: Long?)   // total null → indeterminate
    data class Ready(val file: File, val version: String)       // staged + verified
    data object Committing             // writing the staged APK into the session; Install disabled
    data object AwaitingConfirmation   // the receiver actually launched the system prompt
    data class Failed(val message: String, val stage: Stage)    // Stage.DOWNLOAD | Stage.INSTALL
}
```

There is deliberately no in-process `Installed` stage. Replacing the app's own package kills
the process, so `STATUS_SUCCESS` is almost never delivered (today's `ApkInstallState.Installed`
kdoc already notes it is "rarely observed"). Success is detected **after the restart**:
`install()` persists `pendingUpdateVersion` right before `commit()`; on the next launch, if
`BuildConfig.VERSION_NAME` equals it, the app shows a one-time "Updated to vX — What's new"
snackbar (opening the sheet's notes), deletes the staged file and clears the key. An
`ACTION_MY_PACKAGE_REPLACED` receiver does the same cleanup even if the user doesn't open the
app straight away. If the versions don't match, the attempt failed or was abandoned and the
key is just cleared.

```kotlin

data class Release(
    val version: String,
    val notes: String,          // GitHub release body (Markdown)
    val publishedAt: Instant?,
    val apkUrl: String?,        // null → "not ready yet" (APK still uploading) or no asset
    val apkSize: Long?,         // from the asset, used when Content-Length is missing
    val checksumUrl: String?,   // SHA256SUMS asset
    val pageUrl: String,
)
```

`Failed(stage = INSTALL)` keeps the staged file, so Retry goes straight to install
(Morphe's FAILED → Install behaviour). `Failed(stage = DOWNLOAD)` deletes the partial file.

---

## 4. Phases

Each phase is independently shippable and reviewable; land them in order.

### Phase 1 — Shared update state (no visible change yet)

- Add `AppUpdateController` in `data/`, created once in `CinemArchiveApplication`
  (alongside the existing `appUpdateRepository` / `apkInstaller` lazies).
- Extend `AppUpdateRepository` parsing: `body`, `published_at`, APK asset `size`,
  `SHA256SUMS` asset URL. Extract the JSON→`Release` mapping into a pure function so it can
  be unit tested against fixture JSON.
- Deduplicate concurrent checks with a `Mutex` + in-flight `Deferred`, and reuse a result
  for ~15 s (Morphe's `RESULT_REUSE_WINDOW_MILLIS`) so launch + About + banner don't fire
  three requests.
- `AboutScreen` reads `controller.state` instead of its local `remember`.
- The launch path publishes into the controller instead of discarding its result.

**Tests:** release parsing (with/without APK asset, with/without checksum, prerelease tag,
404 → up to date), dedupe (two concurrent `check()` calls → one HTTP call, via
`MockWebServer` or a fake client).

### Phase 2 — Download, then install as a separate step

Split `ApkInstaller.downloadAndInstall` into:

1. **`download(release)`** → streams to `cacheDir/updates/CinemArchive-v<version>.apk.part`,
   emitting `Downloading(bytes, total)` (throttled to ~10 updates/s). `total` falls back
   to `release.apkSize` when the server omits `Content-Length`. On completion:
   - check the ZIP header (`PK\u0003\u0004`), as Morphe does;
   - verify SHA-256 against `SHA256SUMS` when present (CI already publishes it);
   - read `PackageManager.getPackageArchiveInfo` and require
     `packageName == context.packageName` and a version newer than the installed one;
   - rename `.part` → `.apk` and move to `Ready`.
   Any failure deletes the file and lands in `Failed(DOWNLOAD)`.
2. **`install(file)`** → moves to `Committing` straight away (the button disables, so a
   double tap can't open two sessions), abandons any previous unfinished session, persists
   `pendingUpdateVersion`, copies the staged file into a new `PackageInstaller` session and
   commits. Tracks `sessionId`. Copying ~40 MB takes a few seconds, which is why
   `Committing` is its own stage rather than reusing `AwaitingConfirmation`.

Staged file lifecycle:
- On startup, if a staged APK exists whose version equals the latest release and still
  verifies, restore `Ready` instead of re-downloading.
- Delete it on the post-restart success check (§3) or from the `MY_PACKAGE_REPLACED`
  receiver, once the installed `versionName` ≥ the staged version, or when a newer release
  supersedes it. Not on `STATUS_SUCCESS`, which the dying process usually never receives.

Receiver changes: `STATUS_PENDING_USER_ACTION` → launch the prompt, then set a
`promptLaunched` flag and move to `AwaitingConfirmation`. `STATUS_FAILURE_ABORTED` → back
to `Ready` (not `NotStarted`). Other failures → `Failed(INSTALL)` with the staged file kept.

**Tests:** checksum match/mismatch, non-APK body rejected, wrong package rejected,
`.part` cleanup on failure, restore-`Ready`-on-startup, post-restart success detection
(matching and non-matching `pendingUpdateVersion`).

### Phase 3 — Never raise the install prompt from the background

Step 0: reproduce the suspected stall (kill app; trigger a background process start, e.g.
an outing alarm, with an update available and auto-check on). Record the result in the PR.

Then, regardless of the repro outcome, remove the hazard by design:
- **Automatic paths stop at `Ready`.** Only an explicit user tap calls `install()`. Since
  that tap happens in the foreground, the receiver's `startActivity` is always allowed.
- The receiver still guards: if `STATUS_PENDING_USER_ACTION` arrives while no activity is
  resumed (track via a small `ActivityLifecycleCallbacks` counter in the Application —
  avoids adding `lifecycle-process`), it stashes the confirmation intent on the controller
  and posts a notification instead; the sheet launches the stashed intent on next open.
- **Abandoned prompt detection** (Morphe's `AbandonedInstallGrace` + `installHandedOff`):
  when the activity resumes, the stage is `AwaitingConfirmation` **and** `promptLaunched` is
  set, wait 1.5 s; if nothing has changed, abandon the session and drop back to `Ready`.
  Keying on `promptLaunched` rather than the stage alone keeps the grace from firing while
  the session is still being written (`Committing`).

### Phase 3b — Skip the system prompt for self-updates

Verified against the AOSP `PackageInstaller.SessionParams.setRequireUserAction` Javadoc:
an installer holding `REQUEST_INSTALL_PACKAGES` can install with **no confirmation dialog**
when all of these hold:

1. the session sets `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` (API 31+, and our
   `minSdk` is 31, so always available);
2. the app being installed meets the platform's targetSdk floor — 29+ on Android 12, 30+ on
   13, 31+ on 14, 33+ on 15, "may advance" later (we target 36);
3. the installer is the update owner / installer of record, **or the app is updating
   itself** — our case;
4. the manifest declares `android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION`.

So after this lands, every update is "tap Install → app restarts on the new version", with
no system dialog. The receiver must still handle `STATUS_PENDING_USER_ACTION`, because the
platform can still ask (the Javadoc says so explicitly, and the targetSdk floor will rise).
Everything in Phase 3 stays as the fallback.

This makes the process kill immediate, so `install()` should be user-initiated (or happen
while the app is in the background — see §9). The sheet's Install button copy becomes
"Install and restart".

### Phase 4 — Update sheet

New `UpdateSheet` composable (`feature/settings/`, `ModalBottomSheet`) driven solely by
`UpdateUi`. One body and one action set per state, crossfaded (Morphe's
`UpdateDialogContent` pattern — states that share a body map to the same key so they don't
re-animate):

| Stage | Body | Actions |
|---|---|---|
| Available, not started | "1.44.0 → 1.45.0", release date, release notes | **Download** · Open release |
| Available, `apkUrl == null` | Warning notice: "Release is still being published" | **Check again** · Open release |
| No install permission | Short explanation | **Allow installs** · Open release (re-checks permission on resume) |
| Downloading | Progress ring/bar, `42%`, `18.3 / 43.6 MB`; indeterminate only when no size is known at all | Close (download continues) |
| Ready | "Downloaded and verified" + notes collapsed | **Install and restart** |
| Committing | "Preparing install…" | none (button disabled) |
| AwaitingConfirmation | "Confirm in the system dialog" (fallback path only) | none |
| Failed (download) | Error notice | **Retry download** · Open release |
| Failed (install) | Error notice (existing `describe()` copy from the receiver) | **Retry install** · Open release |
| After restart | One-time "Updated to vX — What's new" snackbar on launch; tapping it opens the notes | — |

Release notes: no Markdown renderer exists. Start with a minimal in-house renderer for the
subset `CHANGELOG.md`/release bodies actually use (`##`/`###` headings, `-` bullets,
`**bold**`, links); see §9 for the alternative of adding a library.

Accessibility: progress announced via `semantics { progressBarRangeInfo }`; state changes
announced with `liveRegion = Polite`.

### Phase 5 — Entry points

- **Home banner:** slim card at the top of the main scaffold content when
  `check is Available` and the user hasn't dismissed this version. "Update to 1.45.0 —
  View". Dismissal persisted as `dismissedUpdateVersion` in `PreferencesRepository` so it
  returns for the next release. While `Downloading`/`Ready`, the banner shows that instead
  ("Downloading 42%" / "Ready to install") and can't be dismissed.
- **About:** `UpdatesSection` shrinks to the auto-check toggle, a Check row, and a status
  row that opens the sheet. Install/progress UI moves into the sheet.
- **Notifications:** every update notification gets a `contentIntent` to `MainActivity`
  with a new `EXTRA_OPEN_UPDATE`, handled in `onCreate`/`onNewIntent` by opening the sheet
  (same routing as `EXTRA_OPEN_TITLE_ID`). Copy changes: "Update 1.45.0 available",
  "Downloading 1.45.0 (42%)" with determinate notification progress, "Update ready — tap to
  install", "Update failed — tap for details".

### Phase 6 — Check and auto-download policy

- Move the automatic check out of `Application.onCreate` to the first activity start of a
  process, throttled by a persisted `lastUpdateCheckAt` (e.g. at most every 6 h). This stops
  background process starts from doing network work.
- Replace the single toggle with "Updates": **Notify only** / **Download on Wi-Fi**
  (default, see §9) / **Download on any network**. Auto-download only ever reaches `Ready`
  (Phase 3).
- Manual Download on a metered network shows a confirm dialog with the size, unless the
  policy is "any network" (Morphe's `MeteredDownloadDialog`).
- Optional periodic background check via WorkManager (`androidx.work:work-runtime-ktx`,
  new dependency) with `NetworkType.CONNECTED` and a daily interval, posting the
  "available" notification. Gated on §9.
- Automatic downloads that start in the foreground run in the controller's app scope and
  die if the process is killed while backgrounded (fine: `.part` is discarded and the next
  start re-downloads). If WorkManager is adopted, run auto-downloads as a worker instead
  (with an unmetered constraint for "Download on Wi-Fi") so they survive process death.

### Phase 7 — Docs and release bookkeeping

- `CHANGELOG.md` `[Unreleased]` entries for the user-facing phases (4, 5, 6).
- Update `docs/android-implementation-status.md` with on-device verification notes.
- Remove now-dead code: `UpdateInstallNotifier`'s old copy, the launch path in
  `CinemArchiveApplication`, `UpdateCheckResult`/`ApkInstallState` once nothing reads them.

---

## 5. Commit / PR breakdown

| PR | Contents | Type |
|---|---|---|
| 1 | Phase 1 | `refactor(android)` (no behaviour change) |
| 2 | Phase 2 + Phase 3 | `fix(android)`: no re-download after a declined prompt; no background prompt stall |
| 2b | Phase 3b | `feat(android)`: install self-updates without the system prompt |
| 3 | Phase 4 + Phase 5 | `feat(android)`: update sheet, banner, tappable notifications |
| 4 | Phase 6 | `feat(android)`: update download policy |
| 5 | Phase 7 cleanup | `chore(android)` |

Stack PRs 1–3 with `gh stack` (each depends on the previous). Every PR runs
`./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest`.

---

## 6. On-device verification checklist

Needs two signed release builds with consecutive versions (debug → release fails with
`STATUS_FAILURE_CONFLICT` — different signing keys).

1. Fresh launch with an update out: banner appears; one network request in logcat.
2. Sheet shows notes; Download shows percent and MB rising; Close mid-download, reopen →
   progress continues.
3. (Before Phase 3b, or with the permission removed) Install → decline in the system
   dialog → sheet shows Install again, no re-download.
4. (Same) Install → press Home on the system dialog → return after > 1.5 s → back to
   Install. Double-tapping Install opens only one session.
4b. (Phase 3b) Install → no system dialog; app restarts on the new version and shows the
   "Updated to vX" snackbar once.
5. Corrupt the staged file (adb) → restart → verification fails → re-download offered.
6. Airplane mode mid-download → Failed (download) with Retry; partial file removed.
7. Kill app, trigger an outing alarm with auto-download on → APK reaches Ready, a
   notification appears, **no** system prompt appears from the background; tapping the
   notification opens the sheet on Install.
8. Revoke "install unknown apps" → sheet shows the permission state; grant and return →
   sheet updates without a manual refresh.
9. Metered network + "Download on Wi-Fi" → no auto-download; manual Download asks first.
10. After a successful install and relaunch, the staged APK is gone from
    `cacheDir/updates/` and `pendingUpdateVersion` is cleared.
11. Install, then don't reopen the app → the `MY_PACKAGE_REPLACED` receiver still removes
    the staged APK.

---

## 7. Risks

- **Staged APK in `cacheDir` can be purged by the system** under storage pressure. Handled:
  `Ready` is re-derived from the file on startup and falls back to downloading.
- **GitHub API rate limit (60/h unauthenticated)** — the dedupe and 6 h throttle keep usage
  well under it; a 403 maps to a friendly "try again later" message.
- **`getPackageArchiveInfo` on a ~40 MB APK** is fast but must stay off the main thread.
- **Abandoned-session detection false positives** (user still reading the dialog when the
  grace fires): only runs on resume, i.e. after the dialog has already been left.

---

## 8. Out of scope

- Delta/partial updates, installer backends other than `PackageInstaller`, pre-release
  channel selection. (A prompt-free install *is* in scope — Phase 3b.)

---

## 9. Decisions (recorded 2026-10-25)

1. **Default auto-download policy:** **Download on Wi-Fi.** Closest to today's behaviour,
   without the background prompt.
2. **Periodic background check:** **Include WorkManager** in Phase 6. The Wi-Fi default
   needs an unmetered-constrained download that survives process death, which an app-scope
   coroutine can't provide; the same worker runs the daily check.
3. **Release notes rendering:** **Minimal in-house Markdown subset** (headings, bullets,
   bold, links). No new dependency.
4. **Banner placement:** **Top of the main scaffold, so it shows on every tab.** It is
   dismissible per version, so it costs one tap at most per release, and an update the user
   only finds on one tab is the discoverability gap this plan exists to close.
5. **Fully automatic install:** **Wait for the user to tap Install** in v1. A background
   install kills the process and could lose unsaved input.

---

## 10. As built (2026-10-25)

Where the implementation differs from the phases above, and why:

- **No 6-hour throttle (Phase 6).** The automatic check runs on the first activity start of a
  process, i.e. when the user opens the app, instead of in `Application.onCreate`. That alone
  stops alarm and boot-receiver process starts from checking, and a throttle would have left
  the banner empty on most launches (the shared state starts at `Idle` each process). Daily
  checks for users who rarely open the app come from `UpdateCheckWorker`.
- **Toggle kept, policy added (Phase 6).** "Automatically check for updates" still governs
  checking; a separate "Download updates automatically" setting (Wi-Fi only / Any network /
  Never) governs downloading. Folding both into one three-way choice hid the fact that "Never"
  still checks.
- **"Updated to vX" is a dialog (Phase 4).** The app has no snackbar host; a one-time dialog
  with a "What's new" link to the release page does the same job.
- **Re-checks don't flash `Checking` (Phase 1 follow-up).** A re-check over an available update
  keeps that update in `AppUpdateController.state` and reports progress via `isChecking`, so the
  banner and sheet don't blink away when the daily check runs.
- **PR breakdown.** Phases 4, 5 and 6 share most of their files (the sheet, About, preferences)
  and landed as one PR rather than two. A debug-only local update feed
  (`-Pcinemarchive.updateFeedUrl`, see `app/build.gradle.kts`) was added so the flow can be
  verified on a device without publishing a release.
- **Step 0 of Phase 3 not reproduced.** Reproducing the background-start stall needs a build of
  the pre-fix code, which installs the real GitHub release and would have updated the user's
  live release install. The stall is removed by design instead: automatic paths never commit an
  install session.
