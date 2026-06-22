# Up Next — Continue-Watching Tab

**Status:** Approved design — ready for implementation planning
**Date:** 2026-06-22
**Author:** Design session (brainstorming)

This is the first of a four-feature UX roadmap. The remaining three each get their own
spec → plan → build cycle later, in this recommended order:

1. **Up Next — continue-watching** ← *this spec*
2. Poster-wall quick actions (status / log without opening the drawer)
3. Command palette (Cmd+K) search / add / navigate
4. Richer Ledger insights (streaks, recap, heatmap)

---

## 1. Goal

CinemArchive is fundamentally a *logging* tool. Its single most common recurring
action — "I just watched the next episode of a show I'm part-way through" — is also its
highest-friction one, taking roughly six steps today (open Library → find the show →
open the drawer → pick the season → find the next episode → expand it → log a watch
event). The `watching` status and per-episode `watchEvents` data already exist, but
nothing surfaces "what's next."

**Up Next** is a dedicated home surface that collapses that loop to **one tap**.

---

## 2. Locked product decisions

These were settled during brainstorming and are not open questions:

| Decision | Choice |
|----------|--------|
| **Placement** | A new top-level **Up Next** tab (the home/landing surface), not a Library rail. |
| **Scope** | TV titles with `status === 'watching'` that have a next unwatched episode. Movies, watchlist, watched, dropped, and caught-up shows never appear. |
| **Tap action** | One tap logs a watch event dated *today* on the next episode (no dialog), the card advances, and a brief inline **`Watched ✓ · Undo`** allows reversal. |
| **Landing** | Smart: open Up Next when it has shows, otherwise fall to Library — never land on an empty screen. |

---

## 3. User-facing behavior

### 3.1 The tab
- New top-level view; in-app heading reads **Continue Watching**.
- Added to the desktop **TopBar** pill nav as the leftmost destination, and to the mobile
  **BottomNav**.
- Proposed nav icon: lucide `PlayCircle` (reads as "resume").

### 3.2 Eligibility
A title is shown iff **all** hold:
- `type === 'tv'`
- `status === 'watching'`
- `nextUnwatchedEpisode(seasons)` returns a non-null episode (see §5.1).

Caught-up shows (every episode watched), movies, and non-`watching` statuses are excluded.

### 3.3 Card anatomy (vertical list)
```
┌────────────────────────────────────────────────┐
│ ┌────┐  Severance                               │
│ │post│  S2 E4 · Next — "Woe's Hollow"           │
│ │ er │  ▓▓▓▓▓▓░░░  13/19 episodes               │
│ └────┘                         [ ✓ Mark watched ]│
└────────────────────────────────────────────────┘
```
- **Left:** the show poster via the existing `DynamicPoster` component (brand consistency).
- **Body:** show title; `S{n} E{m} · Next` plus the episode name (falls back to
  `Episode {n}` when unnamed); a thin progress bar with `watched/total` episode count.
- **Primary action:** an amber **Mark watched** button.
- **Card body / poster tap:** opens the existing `TitleDetailDrawer` for the title, where
  full logging (rating, review, re-watch, arbitrary season/episode) already lives.

### 3.4 One-tap flow
1. Tap **Mark watched**.
2. The next unwatched episode gets a watch event dated **today** (no dialog), synced
   optimistically to the DB via the existing path.
3. The button area swaps to a transient **`Watched ✓ · Undo`** confirmation for ~6
   seconds (also manually dismissible).
4. Underneath, the show has already advanced. When the confirmation clears without an
   undo, the card re-renders showing the *new* next episode.
5. **Undo** deletes the just-created watch event and restores the prior state.

### 3.5 Finale / caught-up
If the episode just logged was the series' final unwatched one:
- The card shows **`All caught up ✓`** with **Undo** and a subtle **`Mark series watched`**
  link.
- **Mark series watched** sets `status: 'watched'` (the *only* status change Up Next ever
  makes — never silent).
- After the undo window, an un-undone card animates out. The show simply leaves Up Next
  because it now has no next episode (it remains `watching` until the user marks it
  watched).

### 3.6 Ordering
Most recent watch activity first, by a single effective timestamp:
`effectiveTs = lastWatchedAt ?? addedAt` (where `lastWatchedAt` is `max(watchedAt)` across
the show's episode watch events). Sort by `effectiveTs` descending. This naturally places
actively-watched shows on top, while just-started shows with no watch events (next = S1E1)
sort by their `addedAt`.

### 3.7 Empty state
A cinematic empty state (consistent with the Library's `EmptyState` tone) with a
**Browse the Library** button that switches to the Library view.

### 3.8 Shared (read-only) view
Cards render so a shared viewer can see *what's in progress*, but **Mark watched**,
**Undo**, and **Mark series watched** are gated behind `!isSharedView`, consistent with how
`TitleDetailDrawer` already gates its mutations.

---

## 4. Architecture & components

### 4.1 New files
- **`src/views/UpNext.tsx`** — the view. Contains:
  - `UpNext` — reads `useUpNextShows()`, renders the list or the empty state.
  - `UpNextCard` — per-show card; owns the transient "just logged / undo" local state and
    the finale state.
- **`src/store/upNext.ts`** — title-level concern kept on its own boundary:
  - `computeUpNextShows(titles: Title[]): UpNextEntry[]` — **pure** eligibility filter +
    sort. Mirrors the pure-function style of `applyFiltersToTitles`.
  - `type UpNextEntry = { title: Title; season: Season; episode: Episode; watchedCount: number; totalCount: number; lastWatchedAt: string | null }`.

### 4.2 Changed files
- **`src/store/episodeUtils.ts`** — add
  `nextUnwatchedEpisode(seasons: Season[]): { season: Season; episode: Episode } | null`
  (see §5.1). Natural home alongside the other episode rollup helpers.
- **`src/store/useAppStore.ts`**:
  - Add action `logNextEpisodeWatch(titleId: string): { seasonNumber: number; episodeNumber: number; watchEventId: string } | null`.
    Computes the next episode via `nextUnwatchedEpisode`, constructs the watch event with a
    **known id** (so the id can be returned for Undo), appends it, recomputes
    `episodesWatched`, refreshes `filteredTitles` + `stats`, and fires the existing
    `logEpisodeToDb` path for the resolved episode. Returns the coordinates + id, or `null`
    if there is no next episode.
  - Add selector `useUpNextShows()` wrapping `computeUpNextShows(s.titles)`.
  - **Reuse** existing `deleteEpisodeWatchEvent` (Undo) and `updateTitle` (Mark series
    watched) — no new actions needed for those.
- **`src/App.tsx`**:
  - Add `'upnext'` to the `AppView` union; render `{currentView === 'upnext' && <UpNext />}`.
  - **Hoist** `<TitleDetailDrawer />` and `<RefreshMetadataModal />` from `Library.tsx` up to
    `App` (they are store-driven global modals — `isDetailDrawerOpen` /
    `isRefreshMetadataOpen`), so both Library and Up Next can open the drawer. `AddTitleWorkflow`
    already lives here, so this co-locates all global modals.
  - Smart landing via a lazy `useState` initializer (see §6).
- **`src/views/Library.tsx`** — remove the two now-hoisted modal mounts.
- **`src/components/TopBar.tsx`** — add Up Next as the leftmost nav destination.
- **`src/components/BottomNav.tsx`** — layout becomes
  `[Up Next] [Library] (＋ Add FAB) [Ledger]`.

### 4.3 Data flow
- `UpNext` → `useUpNextShows()` → `computeUpNextShows(titles)`.
- Card **Mark watched** → `logNextEpisodeWatch(title.id)` → optimistic store update + DB
  write; returns `{ seasonNumber, episodeNumber, watchEventId }` held in card-local state
  for the undo window.
- **Undo** → `deleteEpisodeWatchEvent(title.id, seasonNumber, episodeNumber, watchEventId)`
  (existing, DB-synced).
- **Mark series watched** → `updateTitle(title.id, { status: 'watched' })` (existing).

---

## 5. Core computations

### 5.1 `nextUnwatchedEpisode(seasons)`
- Iterate seasons in ascending `seasonNumber`; within each, episodes in ascending
  `episodeNumber`.
- Only consider seasons that have a populated `episodes[]` array (coarse-only seasons are
  skipped — see §7).
- Return the first episode whose `watchEvents.length === 0`, paired with its season.
- Return `null` when every episode (across episode-bearing seasons) is watched, or when no
  season has episode data.

### 5.2 `computeUpNextShows(titles)`
- Filter to `type === 'tv'` && `status === 'watching'` && `nextUnwatchedEpisode(seasons) !== null`.
- Build an `UpNextEntry` for each: include the resolved next `season`/`episode`,
  `watchedCount`/`totalCount` (reuse `totalEpisodesWatched` / `totalEpisodeCount`), and
  `lastWatchedAt` (max `watchedAt` across all episode watch events, or `null`).
- Sort by `lastWatchedAt ?? addedAt` descending (the single effective-timestamp rule
  from §3.6).

---

## 6. Smart landing

`App` computes the initial view **once**, at mount, from the already-rehydrated store
titles (Zustand `persist` exposes them synchronously on reload):

```ts
const [currentView, setCurrentView] = useState<AppView>(() =>
  computeUpNextShows(useAppStore.getState().titles).length > 0 ? 'upnext' : 'library'
)
```

It does **not** auto-switch after an async login/library load, to avoid a jarring
tab-jump; the user clicks the tab instead. `computeUpNextShows` is the single source of
truth for both the view and this landing decision.

---

## 7. Edge cases & deliberate boundaries

- **Coarse-only `watching` shows** (legacy/migrated rows with `episodesWatched` counts but
  no `episodes[]`) are **excluded**. There is no episode row to advance precisely or to
  undo. They become eligible automatically once opened in the detail drawer, where the
  existing TMDB backfill hydrates `episodes[]`. This is documented behavior, not silent
  magic.
- **Spider Noir** (the `tmdbId === 242484` special case): one-tap logs **without** the
  B&W/Color prompt (`colorMode` left undefined). The full noir-mode choice remains
  available in the drawer. Keeps one-tap genuinely one-tap.
- **DB writes** stay optimistic and fire-and-forget with `console.error` on failure,
  matching every other mutation in the store.

---

## 8. Testing & verification

No formal test runner is configured; the repo verifies logic via standalone Node scripts
(e.g. `scripts/verify-episode-logic.mjs`). Follow that convention:

- **`scripts/verify-upnext-logic.mjs`** — assert against fixtures:
  - `nextUnwatchedEpisode`: caught-up → `null`; partial → correct first unwatched episode;
    correct season ordering; coarse-only season skipped.
  - `computeUpNextShows`: includes only `watching` TV with a next episode; excludes movies,
    watchlist, dropped, watched, and caught-up; sorts by last activity then `addedAt`.
- **Manual:** `npm run dev` against the two seeded in-progress shows — verify the next
  episode is correct, Mark-watched advances + syncs, Undo restores, finale shows the
  caught-up state, and smart landing picks Up Next.
- **Type safety:** `npm run build` (`tsc -b`) and `npm run lint` clean.

---

## 9. Out of scope (YAGNI)

- Watchlist "start next" queue and any movie surfacing (these belong to later roadmap
  features).
- Manual reordering / pinning of cards.
- Next-episode still images on the card (possible later polish; the show poster is enough).
- Any automatic status changes beyond the explicit **Mark series watched** link.
