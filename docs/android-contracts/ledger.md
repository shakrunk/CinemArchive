# Ledger contract

Web sources: `src/store/ledgerDerive.ts` (per-panel derivation), `src/store/ledgerStats.ts`
(whole-library hero rollup), `src/lib/ledgerPanels.ts` (widget registry, `LedgerWidget`
shape, settings sanitizer), `src/views/ledger/Ledger.tsx` / `LedgerHero.tsx` /
`LedgerSkeleton.tsx` (route, edit mode, persistence), `src/components/LedgerCharts.tsx`
(chart primitives), `src/store/outings.ts` (`deriveAtTheMovies`, the one widget that reaches
outside the core domain tables). Backend: `user_prefs` table (layout persistence only —
every widget's *data* is a client-side aggregation over tables `library.md`/
`title-detail.md`/`episode-tracking.md` already cover, plus `cinema_outings` for one widget;
see §3).

Ledger doesn't fit the other three docs' shape exactly — it's 20 read-only aggregation
widgets over already-synced data, not a write-heavy log — so §1 documents the widget
registry instead of a DB row projection, and §4 covers the one write path (layout
persistence) instead of per-entity atomic-command behavior.

## 1. Widget registry, `LedgerWidget` shape, and derivation inputs

```ts
interface LedgerWidget {
  id: string
  panel: LedgerPanelId       // one of the 20 ids in §2
  width: 'sm' | 'md' | 'lg' | 'full'   // 4/6/8/12 of 12 cols at lg+; always full below lg
  settings?: LedgerWidgetSettings
}
interface LedgerWidgetSettings {
  timeRange?: 'all' | '12mo' | 'ytd' | '5y'
  scope?: 'all' | 'movies' | 'tv'
  topN?: number   // clamped 3–12
  title?: string  // custom card title, truncated to 60 chars
}
```

Every widget renders at a fixed 400px card height regardless of `width` — only width
varies; content scrolls/compresses internally. Missing `settings` keys fall back to a
per-panel default; a panel ignores any settings key not in its own allowlist.
`normalizeLedgerWidgets(raw)` is the sanitizer run on both localStorage rehydrate and DB
fetch — drops unknown `panel` values, backfills missing/invalid `width`, keeps only
well-typed settings keys, re-clamps `topN`/`title`. **Android's own layout parser must apply
the identical clamps**, so a malformed or legacy payload degrades the same way on both
platforms rather than crashing one and silently repairing on the other.

All 20 widgets are pure functions over already-synced `Title[]` (the same shape
`library.md`/`title-detail.md` document, including denormalized `seasons[].episodesWatched`
rollups — not recomputed from watch events) plus, for one widget, `CinemaOuting[]`. No
widget calls a DB/RPC directly; everything is `useMemo`'d client-side aggregation.

Two consistency notes Android must replicate exactly, not "fix":

- **Rating widgets read the title-level rating, not episode/viewing ratings.** Critical
  Record, Second Opinions, and Shifting Standards all aggregate `Title.rating` (the single
  `titles.rating` column) — unlike `episode-tracking.md`'s independent per-episode
  watch/rating/review streams, these three widgets never touch `episode_ratings` or
  `viewings.rating`.
- **Streak detection is the only date-bucketing panel that folds in episode watch events.**
  The Marathon (`streaks`) counts a screening date as `viewings[].date` **or** any episode
  `watchedAt`. Every other date-bucketed panel (Activity heatmap, The Run, Screening Nights)
  counts `viewings[].date` only. A TV-heavy user can correctly see their streak count
  disagree with their heatmap/run totals on days where only an episode (no title-level
  viewing) was logged — this is expected, not a bug to reconcile.

## 2. The 20 widgets

| id | Label | Data needed | Non-obvious rule |
| --- | --- | --- | --- |
| `activity` | Time in the Dark | `viewings[].date` | 52-week heatmap; visible week count scales with widget width (26/39/52); undated viewings never appear |
| `encores` | Encore Performances | `title`, `year`, `viewings.length` | Only titles with ≥2 viewings qualify |
| `run` | The Run | `viewings[].date` | Monthly trend, gap-filled (zero-count months included); default window 12mo, not all |
| `ratings` | Critical Record | `Title.rating` | 0.5-star buckets, 5→1 descending |
| `genres` | By the Genre | `genres[]` | Bubble size ∝ √(count/max); becomes a ranked list at `sm` width + `lg`+ breakpoint |
| `decades` | By the Era | `year` | Release-decade bucketing |
| `auteurs` | The Auteurs | `director`, `crew[]` | Tallies by display string; recovers `tmdbPersonId` from crew where `job==='Director'` |
| `ensemble` | The Ensemble | `cast[]` (order < 5) | Only cast billed order < 5 counts as "leading"; keyed by `tmdbPersonId`, name fallback |
| `runtimes` | Feature Lengths | `type==='movie'`, `runtime` | Fixed buckets <90/90–120/120–150/150+; TV excluded, no scope/timeRange knob |
| `networks` | On the Air | `type==='tv'`, `network` | No time/scope settings — always all TV titles |
| `verdicts` | Second Opinions | `Title.rating`, `imdbRating` | `rating×2` (0–5★ → 0–10) to compare against IMDb's 0–10 scale; sorted by \|delta\|; requires both fields present |
| `languages` | In Translation | `originalLanguage` | ISO 639-1 code mapped to display name |
| `weekdays` | Screening Nights | `viewings[].date` | Day-of-week from **local** date components, not UTC-parsed |
| `streaks` | The Marathon | `viewings[].date` + episode `watchedAt` | See §1; "current" streak still counts if yesterday had activity |
| `trajectory` | Shifting Standards | `Title.rating`, `viewings[].date`, `addedAt` | Title lands in the quarter of its first *dated* viewing, falling back to `addedAt` if none |
| `revivals` | Premieres & Revivals | `viewings[]` (chronological, all) | Undated viewings sort first so a dated rewatch of a pre-platform title still counts as a revival; only dated viewings render into a month bucket |
| `timewarp` | The Revival House | `year`, `viewings[].date` | Age = viewing year − release year, floored at 0; 5 fixed buckets |
| `progress` | Still Rolling | `type==='tv'`, `status`, `seasons[].{episodeCount,episodesWatched}` | Included if `status==='watching'` **or** partial progress, even if status says otherwise |
| `attractions` | Coming Attractions | `status==='watchlist'`, `type`, `runtime`, `genres` | `hoursOwed` sums movie runtimes only — TV excluded from the estimate |
| `moviegoing` | At the Movies | `viewings[].{venue,companions,outingId}` + `CinemaOuting[]` | See §3 — the one widget reaching outside the core four domain tables |

## 3. RLS authorization matrix

`user_prefs` (schema.sql:959) is the only table Ledger itself introduces:
`{ user_id (pk), ledger_layout jsonb, updated_at }`. Every other widget's data lives in
tables `library.md`/`title-detail.md`/`episode-tracking.md` already cover.

| Caller | Access | Condition |
| --- | --- | --- |
| Owner | full CRUD | always |
| Friend (`is_friend`) | read-only | whole row — no column-level narrowing |
| Valid shared token | read-only | whole row |
| Anyone else | 0 rows | — |

The schema comment on `user_prefs`'s read policies explicitly warns not to add sensitive
columns to this table without revisiting them, since a friend/shared read exposes the
*entire* row, not just `ledger_layout` — worth remembering if Android's future
navigation/theme prefs (`docs/android-parity-matrix.md`'s Preferences row) end up on the
same table.

**The `moviegoing` widget is the one exception to "no new DB surface."** It's fed by
`cinema_outings` (owner-only RLS — no friend/shared-token read policy at all, a
deliberate "v1 privacy stance") and three columns added to `viewings`
(`venue`, `companions jsonb`, `outing_id`), which inherit `viewings`' existing RLS (owner +
friend + shared-token read). Net effect: in a friend/shared view of someone's Ledger, the
moviegoing widget **degrades, it does not disappear** — trip counts, the year trend, venues,
and companions still render (they live on the shared `viewings` row), but format and total
spend vanish, since those two fields come only from the owner-private `cinema_outings` join,
which the web client never even fetches for a friend/shared viewer. **Android must replicate
this exact partial degradation**, not treat the widget as all-or-nothing per viewer.

## 4. Persistence / idempotency (layout only — widget data itself has no write path)

Every layout-editing action (add/duplicate/remove/move/reorder widget, resize, change
settings, reset) updates local state synchronously — instant UI feedback, persisted to
`localStorage` immediately — then schedules a debounced remote write.

- **Debounce:** 800ms, so a drag or resize gesture's rapid intermediate states collapse to
  one write.
- **Write shape:** a full-object `upsert` on `user_prefs` —
  `{ user_id, ledger_layout: widgets, updated_at }`. Not a merge, no per-row version or
  conditional-write check.
- Skipped entirely for friend/shared viewers — they render the **owner's** synced layout
  (falling back to `defaultLedgerWidgets()` if the owner has never synced one) and never
  write.
- On load, the server layout — if one exists — **always wins** over the client's current
  (possibly first-run-default) layout; a `null` server value means the client's current
  layout is written up as the initial sync.
- On write failure, a retryable toast is surfaced; the local copy is already durable in
  `localStorage` regardless of the remote write's outcome.

**Concurrency gaps, flagged the same way `title-detail.md` §3 and `episode-tracking.md` §3
flag their own known gaps — acceptable to inherit for this pass, not blocking:**

- The debounced upsert is idempotent for retries of the *same* write (identical payload
  replaces identical payload), unlike the episode logs' append-only risk — but it is a blind
  last-write-wins overwrite with no `updated_at`/version comparison, so two devices editing
  concurrently will silently clobber one another.
- An edit made in the final <800ms before app close or process death is captured in
  `localStorage` but never reaches the DB.
- "Server wins on load" means any local-only edits made while logged out, or before the
  account's first sync, are discarded the instant a server-side layout exists.

Android's outbox (`docs/android-sync-contract.md` §4's general pattern) needs an explicit
merge or last-write-timestamp comparison for this one entity if this is ever worth
hardening — same posture the other docs take on their own inherited gaps: known, not fixed
here.

## 5. Accessibility

The parity matrix's Ledger row requires "accessible alternatives" alongside the visual
widgets. **The web app does not uniformly satisfy this today** — Android should not assume
porting the current web behavior is sufficient:

- **Naturally accessible, no extra work needed:** Encore Performances, The Auteurs, The
  Ensemble (list mode), On the Air, In Translation, Second Opinions, Runtime Spectrum, Still
  Rolling, Coming Attractions, Rating Distribution's legend, The Revival House's legend, and
  Genre Bars' ranked-list variant (which is already the default at `sm` width and swaps in at
  `lg`+ too). These render every figure as real, focusable, screen-reader-reachable text.
- **Graphic-only today — a real gap Android should not replicate:** Activity Heatmap and
  Screening Nights' radar (per-datum values are mouse-hover/tooltip-only, bound to
  non-focusable elements — a keyboard/screen-reader user gets only one static, data-free
  `aria-label` for the whole widget), The Marathon's 30-night dot grid (no per-night label at
  all, only the aggregate stats above it), and The Run / Shifting Standards / Premieres &
  Revivals (axis labels are thinned for space, so most individual data points are reachable
  only via mouse/touch tooltip).

Android should give every widget a genuine accessible alternative — e.g. a per-datum list a
screen reader can traverse — for these five, rather than mirroring the web app's current
tooltip-only fallback. This is a case where the web implementation itself falls short of the
parity bar the matrix sets, not an existing pattern to carry forward.

## 6. Android route states

Route: Ledger (dashboard of widget cards).

- **Loading:** `loadingUser && titles.length === 0` → full-page skeleton (shimmer hero +
  stat ribbon + placeholder cards). Gated on both conditions so a reload with already-cached
  titles never re-flashes the skeleton.
- **Hard error:** a library load error with zero cached titles — hero still renders, the
  board is replaced by a single error card with a retry action (owner view only; shared/
  friend views have no retry since they can't trigger the owner's sync).
- **Empty board:** the user has removed every widget — a placeholder inviting them to add
  one back (edit mode only exists for the owner; friend/shared viewers can't reach it).
- **Empty library, non-empty board:** each widget independently renders its own empty state
  (message + call to action) since it individually has no data — this is per-widget, not a
  single route-level empty state.
- **Friend / shared view:** read-only board using the owner's synced layout (never the
  viewer's local layout); no edit affordances reachable at all; `moviegoing` widget present
  but degraded per §3.
