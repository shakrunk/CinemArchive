# Android Ledger Tab — Web Parity Plan

**Status:** Draft — ready for task-level breakdown
**Date:** 2026-07-23
**Companion to:** [`docs/superpowers/plans/2026-06-28-ledger-v2.md`](./2026-06-28-ledger-v2.md) (the web
design this measures against), [`docs/superpowers/specs/2026-06-28-ledger-v2-design.md`](../specs/2026-06-28-ledger-v2-design.md),
[`docs/android-contracts/ledger.md`](../../android-contracts/ledger.md) (the data/behavior contract —
**normative**; this plan does not restate it, only references it), [`docs/android-parity-matrix.md`](../../android-parity-matrix.md),
[`docs/android-implementation-status.md`](../../android-implementation-status.md)

> **For agentic workers:** verification gate for every phase is
> `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest` (matches
> `.github/workflows/android.yml`) all green, plus a `CHANGELOG.md` `[Unreleased]` entry for
> user-facing phases, before committing. `docs/android-implementation-status.md` and
> `docs/android-parity-matrix.md`'s Ledger row should be updated to reflect each phase's
> outcome — both currently understate what's shipped (see §1) and will go stale again if this
> plan lands without updating them.

---

## 0. Correction to the record before planning starts

`docs/android-implementation-status.md` and `docs/android-parity-matrix.md` describe the
Ledger board as functionally complete but blocked on "no real sign-in flow." **That is no
longer true.** As of the current `main`/worktree state:

- Magic-link email sign-in is real and wired (`AuthRepository`, `LoginScreen.kt`); passkey and
  QR-pairing panes are interactive UI but explicitly stubbed (`LoginScreen.kt`: snackbar "isn't
  wired up yet"). This affects Authentication generally, not Ledger specifically.
- `CinemArchiveApplication.kt` DIs `SupabaseRemoteMutationWriter` and
  `SupabaseLedgerLayoutWriter` for real — `UnconfiguredRemoteMutationWriter` is dead code, only
  referenced in comments now.
- `LedgerLayoutRepository.setLayout()` fire-and-forgets a real `user_prefs.ledger_layout`
  upsert on every edit when a session exists.

So this plan starts from **a materially more complete baseline** than the existing docs
suggest, and is scoped to the gaps that survive that correction — not a re-litigation of
already-shipped work. §1 is the accurate current-state grounding table this plan works from.

---

## 1. Current state (grounding facts)

| Area | State | Source |
| --- | --- | --- |
| Widget coverage | All 20 widgets implemented, fixed-order-configurable board, responsive 12-col grid at `lg`+. | `LedgerScreen.kt`, `LedgerRepository.kt` |
| Accessibility | **Exceeds web** — every chart-shaped widget pairs a decorative Canvas primitive with a real, focusable list of the same data. Web itself is tooltip-only on 5 widgets (ledger.md §5). | `LedgerCharts.kt` kdoc, ledger.md §5 |
| Layout persistence — write | Real: local DataStore write (source of truth for reads) + fire-and-forget remote upsert to `user_prefs.ledger_layout` when signed in. | `LedgerLayoutRepository.kt:23-24,45` |
| Layout persistence — read | **Local-only.** `observeLayout()` never hits the network — no pull-on-sign-in, no "server wins on load" (ledger.md §4's documented contract). A layout customized on web is invisible to Android until the *next* Android-side edit overwrites the server row entirely. | `LedgerLayoutRepository.kt:23`, confirmed by direct read |
| `timeRange`/`scope` widget settings | Persist and round-trip through normalization (`LedgerLayoutRules.normalize`), exposed nowhere in UI, and **not read by `LedgerRepository`'s computation at all** (zero references, confirmed by grep). Only `topN`/`title` are applied, as a post-hoc UI-layer `take(n)`/header-override. | `LedgerLayout.kt:44-48` kdoc, `LedgerScreen.kt:302-305` |
| Editor UX | Flat list: tap-to-select, up/down move, tap-to-cycle width, remove, title text field, topN stepper. No drag, no resize-by-drag, no palette-drop targeting, no duplicate, no reset-to-default, no preview thumbnails, no side-panel chrome. | `LedgerEditModeContent`, `LedgerScreen.kt:428-548` |
| Chart visuals | Deliberately decorative-only Canvas primitives: `BarChartCanvas` (flat bars), `HeatmapRow` (week-granularity, single row, alpha-shaded), `DeltaScatterCanvas` (points + midline). No line-chart primitive at all. No tooltips, gradients, or animation anywhere. | `LedgerCharts.kt` |
| Hero / stat row | Static "THE NUMBERS" label + 4-tile 2×2 grid (Movies, Series, Hours, Avg rating). No date kicker, no narrative sentence, no total-screenings stat, no days-in-the-dark stat, no friend-view text variant. | `LedgerScreen.kt:271-284` vs `LedgerHero.tsx` |
| Friend/shared viewing | **Does not exist anywhere in the Android app.** No `viewerContext` equivalent, no shared-token auth, no friend graph. Explicit kdoc: "Android has no friend/shared viewer mode yet, so this always renders the full (owner) view." This is an app-wide gap (Friends/Sharing are both "Discovery" in the parity matrix), not Ledger-scoped. | `LedgerWidgets.kt:47-48`, `android-parity-matrix.md` |
| Test coverage | `LedgerLayoutRulesTest.kt` (9 cases, pure clamp/normalize logic) is the only Ledger test. Zero tests of `LedgerRepository`'s widget-computation logic against the shared `docs/android-contracts/fixtures/ledger.json` fixture the parity matrix requires. Zero Compose UI tests for `LedgerScreen`/edit mode (an app-wide gap — `androidTest/` doesn't exist at all yet). | Direct search |

---

## 2. What "parity" means for this plan, and what's explicitly out of scope

**In scope** — gaps where the web Ledger does something real that Android's Ledger tab does
not, and closing the gap is achievable without first building an unrelated cross-cutting
feature:

1. Layout sync correctness (pull-on-sign-in, server-wins-on-load) — a documented contract
   Android currently violates, not a feature gap.
2. `timeRange`/`scope` settings actually filtering widget data.
3. Editor UX capabilities: drag reorder, drag resize, palette-drop placement, duplicate,
   reset-to-default, preview thumbnails.
4. Chart visual/interactive fidelity uplift, **without regressing Android's existing
   accessible-list advantage**.
5. Hero/stat row content parity (date kicker, narrative sentence, full 5-stat set).
6. Shared-fixture test coverage for `LedgerRepository`, matching the parity matrix's stated
   "Required verification" for the Ledger row.

**Explicitly out of scope for this plan**, with rationale:

- **Friend/shared Ledger viewing.** This needs a friend graph, shared-token auth, and a
  viewer-context concept that spans every feature module (Library, Title detail, Up Next),
  not just Ledger — it's tracked at the Friends/Sharing rows of
  `docs/android-parity-matrix.md`, both still "Discovery." Building it here would mean either
  a throwaway Ledger-only stub or scope creep into an app-wide initiative. §8 below records
  the minimal Ledger-side hook (a `forUserId`/read-only source parameter and the `moviegoing`
  degradation rule from ledger.md §3) to pre-plan the shape so this phase is cheap *once*
  Friends/Sharing lands, without building UI now.
- **Pixel-identical chart rendering** (SVG-quality gradients, spring animations, exact radar
  geometry). Android has no charting library and, per the existing implementation notes,
  intentionally targets *data* parity over *pixel* parity — same posture as the original
  20-widget build. §6 closes the specific *interactive/informational* gaps (tooltips → the
  existing list fallback already covers this; missing chart *shapes* like line charts; the
  30-night dot grid) without chasing web's exact visual treatment.
- **Passkey/QR sign-in.** Real, tracked, but an Authentication-domain gap, not a Ledger one.
  Nothing in this plan is blocked on it — magic-link sign-in already produces a real session
  for every write path Ledger needs.

---

## 3. Locked decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Editor UX pattern | **Capability parity, not chrome parity.** Add drag-reorder/drag-resize/duplicate/reset/preview as capabilities, but keep them in Android-idiomatic surfaces (the existing inline edit-mode list, extended; a `ModalBottomSheet` for widget settings/details — the same primitive the cinema-outings work already introduced to `core:designsystem`) rather than porting the web's floating dual-side-panel desktop chrome. | Android is phone-first; the web's floating palette/details panels assume desktop pointer + screen real estate this app doesn't have. Matches the precedent already set for charts (data parity, not pixel parity) and for `ModalBottomSheet` being the established transient-UI primitive over forcing everything through the existing full-screen `Overlay` pattern. |
| `timeRange`/`scope` computation architecture | Precompute filtered `LedgerSources` per **distinct `(scope, timeRange)` pair actually present in the current layout** (memoized on the combine chain), not per-widget from scratch and not one eager cross-product of all 12 combinations. | Mirrors the web's per-panel `scopedTitles(panelId, titles, settings)` `useMemo` pattern (filter-then-aggregate) without either recomputing the whole board N times for N widgets sharing the same settings, or wastefully precomputing combinations nothing on the board uses. |
| Layout read/write reconciliation | Pull-on-sign-in and pull-on-app-launch (when signed in), applying ledger.md §4's exact rule: non-null server layout always wins over the client's current layout; a null server value means the client's current (possibly first-run-default) layout is written up as the initial sync. No merge, no per-field reconciliation — same blind last-write-wins posture the web app itself has and ledger.md §4 explicitly says is an acceptable inherited gap. | Don't build a fancier reconciliation than the system this is achieving parity with actually has. The bug being fixed is "Android never pulls at all," not "Android's conflict resolution is unsophisticated" — the latter is a known, accepted gap on both platforms already. |
| New chart primitives | Add exactly one: a line-chart Canvas primitive (stroke path + optional gradient fill) in `LedgerCharts.kt`, reused by every widget that needs a trend line (Shifting Standards, Premieres & Revivals-equivalent). Upgrade `HeatmapRow` to a true daily 52-cell-per-week grid rather than week-granularity. Add a 30-cell "last 30 nights" grid for The Marathon. Leave Screening Nights as a bar chart (not a radar) — radar geometry has no accessible-parity benefit over a bar chart's already-accessible-list pairing and would be pure visual chase. | Spend the budget on primitives that add *information density* (daily granularity, a trend line) over ones that only add *visual style* (radar vs. bar convey the same 7 numbers). |
| Test strategy | Port `docs/android-contracts/fixtures/ledger.json` into a `LedgerRepositoryTest` (JVM unit test, Room in-memory or fake DAOs) that asserts every one of the 20 widgets' output against the same fixture the web side's tests already use, per the parity matrix's "Required verification: Shared calculation fixtures." Defer Compose UI tests for `LedgerScreen` — no `androidTest/` infra exists anywhere in the app yet; standing that up is a cross-cutting investment, not a Ledger-specific one. | Closes the specific, named verification gap the parity matrix already requires for this row, without taking on unrelated infra work. |

---

## 4. Phases and ordering

Dependency shorthand: **[blocks: X]** means this phase's output is required before X can start.
**[independent]** means no other phase in this plan gates it — start whenever capacity allows.

### Phase A — Layout sync correctness *(independent, do first — small, fixes a live contract violation)*

- `LedgerLayoutRepository` gains a pull path: on `AuthRepository` session becoming non-null
  (sign-in) and on each app-launch reconciliation pass (alongside the existing
  `librarySyncRepository.syncNow()` call in `CinemArchiveApplication.onCreate()`), fetch
  `user_prefs.ledger_layout` via a new read method on `SupabaseLedgerLayoutWriter` (or a
  sibling reader), run it through `LedgerLayoutRules.normalize()`, and apply ledger.md §4's
  rule: non-null → overwrite local DataStore; null → push the current local layout up as the
  initial sync.
- Guard against clobbering an in-flight local edit: only pull on sign-in/launch, never mid-edit
  session (edit mode is a bounded local interaction already).
- **Verification:** unit test the merge rule (server-null → push-current; server-non-null →
  overwrite-local) against a fake writer, matching `MutationOutboxTest`'s existing style. Live
  check on the emulator: edit the layout on web for a test account, launch/relaunch Android
  signed into the same account, confirm the web layout appears without any Android-side edit.
- **Blocks:** nothing downstream in this plan, but should land before Phase C's editor-UX work
  gets far, since testing drag/resize by hand is more useful once layout state is actually
  trustworthy across sessions.

### Phase B — `LedgerRepository` fixture test harness *(independent, do early — de-risks Phase D)*

- Port `docs/android-contracts/fixtures/ledger.json` into JVM-loadable fixture data (fake DAOs
  or an in-memory Room instance seeded from the fixture, matching the existing
  `DevFixtureSeed` shape).
- Write `LedgerRepositoryTest` asserting each of the 20 widgets' computed output against the
  fixture, at minimum reproducing the exact hand-verified numbers already recorded in
  `docs/android-implementation-status.md`'s Phase 3 verification notes (e.g. Second Opinions'
  "Us 9.0 vs IMDb 4.4 (Δ4.6)" for Inception) as regression assertions instead of one-off manual
  checks.
- **Verification:** `./gradlew :data:testDebugUnitTest` (or the relevant module target) green.
- **Blocks:** Phase D (the `timeRange`/`scope` computation refactor) should not start until
  this harness exists — it's the regression net for that refactor. Not a hard technical
  dependency (Phase D could be built without it), but doing it in the other order means the
  riskiest phase in this plan ships with no safety net, which this plan does not recommend.

### Phase C — Editor UX capabilities *(mostly independent; one sub-item depends on Phase D)*

Independent sub-items — any order, can run concurrently with each other and with Phases A/B/D/E/F:

- **C1. Drag-to-reorder.** Long-press-and-drag within the existing edit-mode `LazyColumn`
  (Compose's `reorderable`-pattern via `Modifier.pointerInput` + item offset animation — no new
  dependency needed, same primitive family already used for the star-rating drag control per
  the app-shell redesign changelog entry). Replaces, not supplements, the up/down buttons
  (keep the buttons too, as a keyboard/switch-access-friendly fallback — don't regress
  accessibility to gain a gesture).
- **C2. Drag-resize.** A drag handle on each edit-mode row (or, if C1's row layout allows, an
  edge affordance) that cycles through the same four width presets as today's tap-to-cycle
  button, snapping to nearest on release — keep the tap-to-cycle button too, same
  fallback-preservation rationale as C1.
- **C3. Palette drop-targeting.** Extend "Add a widget" so a long-press-drag from that list
  previews insertion at a specific position in the board list (not just append), mirroring the
  web's slot hit-testing at a much simpler list-reorder level (no free-form grid drop needed —
  Android's board is a single-column list in edit mode already).
- **C4. Live scaled preview thumbnails** in the "Add a widget" list — render each unplaced
  panel's actual `WidgetContent` composable at a fixed small scale (`graphicsLayer` scale
  transform) instead of a bare text row, using the fixture/current board data.
- **C5. Duplicate widget** action alongside remove in `EditableWidgetRow`.
- **C6. "Reset to default layout"** action, calling `LedgerLayoutRules.defaultLedgerWidgets()`
  and pushing through the same `onLayoutChange` path (with a confirmation dialog — this is
  destructive to any customization).
- **C7. Per-panel usage badge** (`×N` already on board) in the "Add a widget" list rows.

Depends on Phase D:

- **C8. `timeRange`/`scope` controls** in the widget-settings UI (a segmented control per
  setting, added to `EditableWidgetRow` or a new settings `ModalBottomSheet` per the Locked
  Decisions table). **Do not build this before Phase D lands** — shipping controls that don't
  affect rendered output is worse than not having them (ledger.md's whole posture is "match
  behavior exactly," and a no-op control breaks that silently).

**Verification per sub-item:** manual emulator pass (drag/resize/duplicate/reset/preview each
exercised, board persists correctly across a force-stop per the existing verification style in
`docs/android-implementation-status.md`) plus the standard Gradle gate. No new automated UI
test infra is assumed here (see §3's test-strategy decision) — if `androidTest/` scaffolding
happens to land from unrelated work before this phase, retrofit coverage then, but don't block
on standing it up specifically for this.

### Phase D — `timeRange`/`scope` consumption *(independent to start; blocks Phase C8)*

- Extend `LedgerRepository`'s combine chain to memoize a filtered `LedgerSources` per distinct
  `(scope, timeRange)` pair present in the current layout (per the Locked Decisions
  architecture), and thread the resolved per-widget settings through each of the 20 widget
  computation functions the same way `topN` already flows through the UI layer's `applyTopN` —
  except this filtering has to happen *before* aggregation, not after, since e.g. "movies
  only" changes which titles feed a ratings bucket, not just how many rows of an
  already-computed bucket list are shown.
- Port `effectiveLedgerSettings()`'s per-panel defaults from `ledgerPanels.ts` (e.g. `run` →
  `12mo` default, `trajectory` → `5y` default, `verdicts`/`languages`/etc. → `topN: 6` unless
  overridden) so an Android widget with no explicit settings behaves identically to its web
  counterpart, not just identically to its own pre-refactor default.
- Port `PANEL_SETTING_KEYS`' per-panel allowlist (which of `timeRange`/`scope`/`topN`/`title`
  each of the 20 panels actually honors) — most panels ignore `timeRange`, several
  (`runtimes`, `networks`, `attractions`, `moviegoing`) ignore both `timeRange` and `scope`
  entirely. Silently ignoring an inapplicable key (matching `normalize()`'s existing
  drop-unknown-keys behavior) rather than erroring is the correct behavior here, per ledger.md
  §1.
- **Verification:** extend Phase B's fixture tests with `timeRange`/`scope` variants per panel
  that honors them — this is exactly the kind of regression Phase B's harness exists to catch.
- **Blocks:** Phase C8 only. Nothing else in this plan depends on it.

### Phase E — Chart primitive uplift *(independent)*

- Daily 52-week `HeatmapRow` upgrade (7×52 cells, not one row) for Activity — keep the
  existing accessible list beneath it untouched; this only changes the decorative primitive's
  resolution.
- New line-chart Canvas primitive in `LedgerCharts.kt` (stroke path, optional gradient fill,
  no animation requirement — Compose Canvas doesn't get web's CSS `pathLength` draw-in for
  free, and chasing that specific effect isn't worth the implementation cost here) for
  Shifting Standards / Premieres & Revivals-style trend widgets.
- 30-cell "last 30 nights" grid for The Marathon, alongside the existing streak-count text
  (additive, not a replacement).
- **Explicitly not doing:** Screening Nights radar geometry (per Locked Decisions — no
  information gain over the existing bar chart + list pairing).
- **Verification:** visual check on the emulator at both `sm` (full-width below `lg`) and
  packed grid-column widths; confirm the paired accessible list still renders every datum the
  new/upgraded chart shows (don't regress the one place Android already exceeds web).

### Phase F — Hero / stat row parity *(independent)*

- Add the "now showing · {date}" kicker line and narrative sentence (title count, screening
  count, hours) above the existing stat-tile grid, matching `DashHero`'s copy structure (not
  literal text — Android's existing voice/tone in `LedgerScreen.kt`'s "THE NUMBERS" label
  should be kept, not overwritten with the web's copy verbatim).
- Expand the stat set from 4 tiles to the full 5-stat set: add total-screenings and
  days-in-the-dark, matching `StatRibbon`'s five items. Keep Android's existing tile-grid
  layout (2×2 → whatever grid fits 5, e.g. 2+2+1 or a horizontal scroll row) rather than
  porting the web's horizontal-scroll ribbon literally — same capability-not-chrome posture as
  Phase C.
- **Do not build the friend-view text variant** (`"{name}'s ledger"` / `"An evening with
  {name}"`) in this phase — there is no `viewerContext` to source a display name from yet (see
  §2's explicit scoping and §8). Structure the hero's copy-generation so adding that variant
  later is a data-availability change, not a rewrite (e.g. accept an optional
  `viewedDisplayName: String?` parameter now, unused/always-null until Phase Friends-Sharing
  lands, rather than hardcoding the owner-only string inline everywhere).
- **Verification:** visual check; confirm the added stats compute correctly against
  `LedgerStats` (total screenings and days-in-the-dark are arithmetic over fields
  `LedgerStats` likely already carries or needs one field added — check `LedgerStats.kt`
  before assuming a new query is needed).

---

## 5. Concurrency summary

```
Phase A (layout sync fix)         ─────────────────────────────────▶  done, independent
Phase B (fixture test harness)    ──────────────▶  (feeds into D)
Phase D (timeRange/scope)                          ─────────▶  (feeds into C8)
Phase C1–C7 (editor UX, minus C8) ─────────────────────────────────▶  done, independent
Phase C8 (timeRange/scope UI)                                   ─────▶  (needs D done)
Phase E (chart uplift)            ─────────────────────────────────▶  done, independent
Phase F (hero/stat parity)        ─────────────────────────────────▶  done, independent
```

**Hard ordering constraints (the only two in this plan):**

1. Phase D must land before Phase C8 (shipping no-op settings controls is worse than not
   having them).
2. Phase B should land before Phase D gets far (regression net for the highest-risk phase) —
   soft dependency, not a hard blocker, but strongly recommended sequencing.

**Everything else — Phase A, Phase B's start, Phases C1–C7, Phase E, Phase F, and Phase D's
start — has no cross-phase dependency and can run fully concurrently**, whether that means
multiple engineers/agents in parallel or one engineer interleaving them in whatever order suits
capacity. A reasonable single-track order that respects the two constraints while front-loading
the highest-value/lowest-risk work: **A → B → D → (C1–C7 and E and F, any order/interleaving) →
C8**, with C1–C7/E/F genuinely fine to start immediately after A/B rather than waiting for D.

---

## 6. Non-goals recap (see §2 for full rationale)

- Friend/shared Ledger viewing — blocked on the app-wide Friends/Sharing initiative, not this
  plan. §8 pre-plans the hook.
- Pixel-identical chart rendering (radar geometry, CSS-style animated draw-in, gradient theming
  system).
- Passkey/QR sign-in — Authentication-domain, not Ledger-domain, and not a blocker for anything
  here.
- Compose UI test infra for `LedgerScreen` — cross-cutting investment (`androidTest/` doesn't
  exist anywhere in the app), out of scope for a Ledger-specific plan.

---

## 7. Updating the record

Once phases land, update (not optional — this is how §0's staleness happened in the first
place):

- `docs/android-implementation-status.md`'s Phase 3 Ledger section.
- `docs/android-parity-matrix.md`'s Ledger row — in particular its "Remaining" clause, which
  currently only names the `timeRange`/`scope` gap this plan's Phase D closes.
- `CHANGELOG.md` `[Unreleased]` per user-facing phase (A, C, E, F at minimum — D and B are
  internal/test-only unless C8 ships in the same pass).

---

## 8. Pre-planned hook for future friend/shared Ledger viewing (not built here)

Recorded so the eventual Friends/Sharing initiative doesn't have to re-derive this from
scratch, and so nothing built in Phases A–F accidentally forecloses it:

- `LedgerRepository` should accept an optional read-scope parameter (owner vs. a specific
  friend/shared-token `userId`) rather than always reading the signed-in user's own DAOs —
  mirrors how the web's `viewedLedgerWidgets`/`viewerContext` swap the data source, not the
  computation logic.
- The `moviegoing` widget must implement ledger.md §3's exact degradation: trip count/venues/
  companions/year-trend still render (backed by `viewings` columns, which inherit friend/
  shared-token read RLS), but `format` and `totalSpend` must vanish (backed by owner-only
  `cinema_outings`, no friend/shared-token RLS policy at all) — this is a per-field
  conditional, not an all-or-nothing widget hide.
- The board itself must render **read-only** in a friend/shared context: no edit-mode entry
  point at all (not just disabled buttons), and the layout shown is the *owner's* synced
  `user_prefs.ledger_layout` (falling back to `defaultLedgerWidgets()` if the owner never
  synced one) — never the viewer's own local layout.
- Phase F's `viewedDisplayName: String?` hero parameter (added but unused in this plan) is
  ready to receive a real value once a viewer-context concept exists.
