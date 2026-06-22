# Up Next — Continue-Watching Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated "Up Next" home tab that lists in-progress TV shows with their next unwatched episode and a one-tap "mark watched" (with undo) that logs today and advances, collapsing the core ~6-step logging loop to a single tap.

**Architecture:** A new top-level view (`src/views/UpNext.tsx`) driven by a pure selector (`computeUpNextShows`) over the existing Zustand `titles` state. The next-episode math lives in `episodeUtils.ts`; a new store action `logNextEpisodeWatch` logs the next episode optimistically (reusing the existing DB sync path) and returns the new watch-event id so the UI can offer Undo. The global detail-drawer modal is hoisted to `App` so both Library and Up Next can open it. Smart landing picks Up Next when shows are in progress, else Library.

**Tech Stack:** Vite + React 19 + TypeScript, Zustand (`persist`), Tailwind v3, lucide-react, Supabase (optimistic fire-and-forget writes). No test runner — logic is verified with standalone Node scripts per repo convention (`scripts/verify-*.mjs`).

## Global Constraints

- **No new dependencies.** Use `lucide-react` (already present) for icons; the Up Next nav icon is `PlayCircle`.
- **Imports use the `src/...` path alias** (e.g. `import { useAppStore } from 'src/store/useAppStore'`), matching every existing file.
- **DB writes are optimistic and fire-and-forget** with `.catch((err) => console.error(...))`, never awaited in the store. Mock/logged-out mode (`state.user === null`) performs no DB write but still updates the store.
- **Eligibility is exact:** a title appears in Up Next iff `type === 'tv'` **and** `status === 'watching'` **and** `nextUnwatchedEpisode(seasons) !== null`.
- **Watch-event ids must be real UUIDs** (`crypto.randomUUID()`), because `episode_watch_events.id` is `uuid` and Undo deletes by id — the store id and the DB row id must match.
- **Design tokens / classes (reuse, do not invent):** colors via Tailwind theme (`text-amber`, `text-paper`, `text-paper-dim`, `text-paper-faint`, `bg-amber`) and CSS vars (`var(--line)`, `var(--ink-1)`, `var(--amber)`); fonts `font-serif` / `font-sans` / `font-mono`; existing utility classes `btn-amber`, `kicker`, `dot`, `display-title`.
- **Shared (read-only) view:** all mutations are gated behind `!isSharedView`, matching `TitleDetailDrawer`.
- **No `PERSIST_VERSION` bump** — the persisted shape (`titles`, `filters`, `viewMode`) is unchanged; Up Next is fully derived.
- **Verification commands:** `node scripts/verify-upnext-logic.mjs` (logic), `npm run build` (`tsc -b` + vite, type safety), `npm run lint` (ESLint). End every commit message with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

## Task 1: `nextUnwatchedEpisode` helper

**Files:**
- Create: `scripts/verify-upnext-logic.mjs`
- Modify: `src/store/episodeUtils.ts` (append a new exported function)

**Interfaces:**
- Produces: `nextUnwatchedEpisode(seasons: Season[]): { season: Season; episode: Episode } | null` — first episode by ascending season→episode order whose `watchEvents` is empty, skipping seasons with no `episodes[]`; `null` when none.

- [ ] **Step 1: Write the failing verification script (stubbed helper)**

Create `scripts/verify-upnext-logic.mjs` with the helper body **stubbed to `return null`** so the assertions fail:

```js
/**
 * Runtime logic verification for the "Up Next" continue-watching feature.
 * Run with: node scripts/verify-upnext-logic.mjs
 *
 * Mirrors the pure logic of src/store/episodeUtils.ts (nextUnwatchedEpisode)
 * and src/store/upNext.ts (computeUpNextShows), matching the convention of
 * scripts/verify-episode-logic.mjs.
 */

let pass = 0, fail = 0
function assert(label, actual, expected) {
  if (actual === expected) { console.log(`  ✓ ${label}: ${actual}`); pass++ }
  else { console.error(`  ✗ ${label}: expected ${expected}, got ${actual}`); fail++ }
}
function assertNull(label, actual) {
  if (actual === null) { console.log(`  ✓ ${label}: null`); pass++ }
  else { console.error(`  ✗ ${label}: expected null, got ${JSON.stringify(actual)}`); fail++ }
}

// ── Pure logic mirror: nextUnwatchedEpisode (mirrors episodeUtils.ts) ──
function nextUnwatchedEpisode(seasons) {
  return null // STUB — replaced in Step 3
}

// ── Fixtures ──
const we = (n) => ({ id: `we-${n}`, watchedAt: '2026-01-' + String(n).padStart(2, '0') })
function ep(num, watched) {
  return { id: `e${num}`, episodeNumber: num, episodeName: `Ep ${num}`, watchEvents: watched ? [we(num)] : [], ratings: [], reviews: [] }
}

const partialSeasons = [
  { id: 's1', seasonNumber: 1, episodeCount: 3, episodesWatched: 3, episodes: [ep(1, true), ep(2, true), ep(3, true)] },
  { id: 's2', seasonNumber: 2, episodeCount: 5, episodesWatched: 3, episodes: [ep(1, true), ep(2, true), ep(3, true), ep(4, false), ep(5, false)] },
]
const caughtUpSeasons = [
  { id: 's1', seasonNumber: 1, episodeCount: 2, episodesWatched: 2, episodes: [ep(1, true), ep(2, true)] },
]
const unstartedSeasons = [
  { id: 's1', seasonNumber: 1, episodeCount: 3, episodesWatched: 0, episodes: [ep(1, false), ep(2, false), ep(3, false)] },
]
const coarseThenEpisodes = [
  { id: 's1', seasonNumber: 1, episodeCount: 10, episodesWatched: 5, episodes: undefined },
  { id: 's2', seasonNumber: 2, episodeCount: 2, episodesWatched: 0, episodes: [ep(1, false), ep(2, false)] },
]
const coarseOnly = [
  { id: 's1', seasonNumber: 1, episodeCount: 10, episodesWatched: 5, episodes: undefined },
]

console.log('\n── 1. nextUnwatchedEpisode ──')
const n1 = nextUnwatchedEpisode(partialSeasons)
assert('partial: season = 2', n1 ? n1.season.seasonNumber : null, 2)
assert('partial: episode = 4', n1 ? n1.episode.episodeNumber : null, 4)
assertNull('caught up: null', nextUnwatchedEpisode(caughtUpSeasons))
const n2 = nextUnwatchedEpisode(unstartedSeasons)
assert('unstarted: episode = 1', n2 ? n2.episode.episodeNumber : null, 1)
const n3 = nextUnwatchedEpisode(coarseThenEpisodes)
assert('coarse-then-episodes: skips coarse, season = 2', n3 ? n3.season.seasonNumber : null, 2)
assert('coarse-then-episodes: episode = 1', n3 ? n3.episode.episodeNumber : null, 1)
assertNull('coarse-only: null (no episode rows)', nextUnwatchedEpisode(coarseOnly))

// ── Summary ──  (Tasks 2 and 3 insert their sections ABOVE this block)
console.log(`\n${'─'.repeat(50)}`)
console.log(`Result: ${pass} passed, ${fail} failed`)
if (fail > 0) process.exit(1)
```

- [ ] **Step 2: Run the script to verify it fails**

Run: `node scripts/verify-upnext-logic.mjs`
Expected: FAIL — e.g. `✗ partial: season = 2: expected 2, got null` and `Result: ... failed`, exit code 1.

- [ ] **Step 3: Implement the mirror (replace the stub)**

In `scripts/verify-upnext-logic.mjs`, replace the stub body with:

```js
function nextUnwatchedEpisode(seasons) {
  const orderedSeasons = [...seasons].sort((a, b) => a.seasonNumber - b.seasonNumber)
  for (const season of orderedSeasons) {
    if (!season.episodes || season.episodes.length === 0) continue
    const orderedEpisodes = [...season.episodes].sort((a, b) => a.episodeNumber - b.episodeNumber)
    for (const episode of orderedEpisodes) {
      if (episode.watchEvents.length === 0) return { season, episode }
    }
  }
  return null
}
```

- [ ] **Step 4: Run the script to verify it passes**

Run: `node scripts/verify-upnext-logic.mjs`
Expected: PASS — all `✓`, `Result: 7 passed, 0 failed`, exit code 0.

- [ ] **Step 5: Port the verified logic into TypeScript**

Append to `src/store/episodeUtils.ts` (after the runtime helpers, end of file):

```ts
// ─── Up Next: next unwatched episode ─────────────────────────────────────────

/** First episode (ascending season → episode) with no watch events. Seasons
 *  lacking an `episodes[]` array (coarse-only progress) are skipped. */
export function nextUnwatchedEpisode(
  seasons: Season[]
): { season: Season; episode: Episode } | null {
  const orderedSeasons = [...seasons].sort((a, b) => a.seasonNumber - b.seasonNumber)
  for (const season of orderedSeasons) {
    if (!season.episodes || season.episodes.length === 0) continue
    const orderedEpisodes = [...season.episodes].sort((a, b) => a.episodeNumber - b.episodeNumber)
    for (const episode of orderedEpisodes) {
      if (episode.watchEvents.length === 0) return { season, episode }
    }
  }
  return null
}
```

- [ ] **Step 6: Type-check**

Run: `npm run build`
Expected: PASS — `tsc -b` and vite build complete with no errors.

- [ ] **Step 7: Commit**

```bash
git add scripts/verify-upnext-logic.mjs src/store/episodeUtils.ts
git commit -m "$(cat <<'EOF'
feat(upnext): add nextUnwatchedEpisode helper + verification script

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `computeUpNextShows` selector logic

**Files:**
- Create: `src/store/upNext.ts`
- Modify: `scripts/verify-upnext-logic.mjs` (insert a section above the Summary block)

**Interfaces:**
- Consumes: `nextUnwatchedEpisode` (Task 1); `totalEpisodesWatched`, `totalEpisodeCount` from `episodeUtils.ts`.
- Produces:
  - `interface UpNextEntry { title: Title; season: Season; episode: Episode; watchedCount: number; totalCount: number; lastWatchedAt: string | null }`
  - `computeUpNextShows(titles: Title[]): UpNextEntry[]` — in-progress TV with a next episode, sorted by `(lastWatchedAt ?? addedAt)` descending.

- [ ] **Step 1: Write the failing verification section (stubbed)**

In `scripts/verify-upnext-logic.mjs`, **insert immediately above** the `// ── Summary ──` line. Start with the mirror **stubbed to `return []`**:

```js
// ── Pure logic mirror: computeUpNextShows (mirrors upNext.ts) ──
function episodesWatchedInSeason(season) {
  if (season.episodes?.length) return season.episodes.filter((e) => e.watchEvents.length > 0).length
  return season.episodesWatched
}
function totalEpisodesWatched(seasons) { return seasons.reduce((s, x) => s + episodesWatchedInSeason(x), 0) }
function totalEpisodeCount(seasons) { return seasons.reduce((s, x) => s + x.episodeCount, 0) }
function lastWatchedAtForTitle(title) {
  let max = null
  for (const season of title.seasons ?? [])
    for (const e of season.episodes ?? [])
      for (const w of e.watchEvents)
        if (max === null || w.watchedAt > max) max = w.watchedAt
  return max
}
function computeUpNextShows(titles) {
  return [] // STUB — replaced in Step 3
}

// Fixtures: a representative library
const T = (over) => ({ type: 'tv', status: 'watching', seasons: [], addedAt: '2026-01-01', ...over })
const upNextTitles = [
  T({ id: 't1', seasons: partialSeasons, addedAt: '2026-01-01' }),          // include: next S2E4, lastWatched 2026-01-03
  T({ id: 't2', seasons: caughtUpSeasons }),                                // exclude: caught up
  T({ id: 't3', status: 'watched', seasons: partialSeasons }),             // exclude: status
  T({ id: 't4', type: 'movie', status: 'watchlist', seasons: undefined }), // exclude: movie/watchlist
  T({ id: 't5', status: 'watchlist', seasons: unstartedSeasons }),         // exclude: status
  T({ id: 't6', seasons: unstartedSeasons, addedAt: '2026-03-01' }),       // include: next S1E1, no watch events
  T({ id: 't7', seasons: coarseOnly }),                                    // exclude: no episode rows
]

console.log('\n── 2. computeUpNextShows ──')
const up = computeUpNextShows(upNextTitles)
assert('count = 2 (only in-progress TV with a next ep)', up.length, 2)
assert('order[0] = t6 (newer effective ts: addedAt 2026-03-01)', up[0] ? up[0].title.id : null, 't6')
assert('order[1] = t1 (lastWatched 2026-01-03)', up[1] ? up[1].title.id : null, 't1')
const t1Entry = up.find((e) => e.title.id === 't1')
assert('t1 next season = 2', t1Entry ? t1Entry.season.seasonNumber : null, 2)
assert('t1 next episode = 4', t1Entry ? t1Entry.episode.episodeNumber : null, 4)
assert('t1 watchedCount = 6', t1Entry ? t1Entry.watchedCount : null, 6)
assert('t1 totalCount = 8', t1Entry ? t1Entry.totalCount : null, 8)
```

- [ ] **Step 2: Run the script to verify the new section fails**

Run: `node scripts/verify-upnext-logic.mjs`
Expected: FAIL — `✗ count = 2 ...: expected 2, got 0`, exit code 1. (Task 1's section still passes.)

- [ ] **Step 3: Implement the mirror (replace the stub)**

Replace the stubbed `computeUpNextShows` in the script with:

```js
function computeUpNextShows(titles) {
  const entries = []
  for (const title of titles) {
    if (title.type !== 'tv' || title.status !== 'watching') continue
    if (!title.seasons || title.seasons.length === 0) continue
    const next = nextUnwatchedEpisode(title.seasons)
    if (!next) continue
    entries.push({
      title,
      season: next.season,
      episode: next.episode,
      watchedCount: totalEpisodesWatched(title.seasons),
      totalCount: totalEpisodeCount(title.seasons),
      lastWatchedAt: lastWatchedAtForTitle(title),
    })
  }
  entries.sort((a, b) => {
    const aTs = a.lastWatchedAt ?? a.title.addedAt
    const bTs = b.lastWatchedAt ?? b.title.addedAt
    if (aTs < bTs) return 1
    if (aTs > bTs) return -1
    return 0
  })
  return entries
}
```

- [ ] **Step 4: Run the script to verify it passes**

Run: `node scripts/verify-upnext-logic.mjs`
Expected: PASS — `Result: 15 passed, 0 failed`, exit code 0.

- [ ] **Step 5: Create the TypeScript module**

Create `src/store/upNext.ts`:

```ts
import type { Title, Season, Episode } from './mockData'
import { nextUnwatchedEpisode, totalEpisodesWatched, totalEpisodeCount } from './episodeUtils'

export interface UpNextEntry {
  title: Title
  season: Season
  episode: Episode
  watchedCount: number
  totalCount: number
  lastWatchedAt: string | null
}

/** Latest `watchedAt` across all of a title's episode watch events, or null. */
function lastWatchedAtForTitle(title: Title): string | null {
  let max: string | null = null
  for (const season of title.seasons ?? []) {
    for (const episode of season.episodes ?? []) {
      for (const we of episode.watchEvents) {
        if (max === null || we.watchedAt > max) max = we.watchedAt
      }
    }
  }
  return max
}

/** In-progress TV shows (status 'watching') that have a next unwatched episode,
 *  sorted most-recent-activity first by `(lastWatchedAt ?? addedAt)` descending. */
export function computeUpNextShows(titles: Title[]): UpNextEntry[] {
  const entries: UpNextEntry[] = []
  for (const title of titles) {
    if (title.type !== 'tv' || title.status !== 'watching') continue
    if (!title.seasons || title.seasons.length === 0) continue
    const next = nextUnwatchedEpisode(title.seasons)
    if (!next) continue
    entries.push({
      title,
      season: next.season,
      episode: next.episode,
      watchedCount: totalEpisodesWatched(title.seasons),
      totalCount: totalEpisodeCount(title.seasons),
      lastWatchedAt: lastWatchedAtForTitle(title),
    })
  }
  entries.sort((a, b) => {
    const aTs = a.lastWatchedAt ?? a.title.addedAt
    const bTs = b.lastWatchedAt ?? b.title.addedAt
    if (aTs < bTs) return 1
    if (aTs > bTs) return -1
    return 0
  })
  return entries
}
```

- [ ] **Step 6: Type-check**

Run: `npm run build`
Expected: PASS — no errors.

- [ ] **Step 7: Commit**

```bash
git add scripts/verify-upnext-logic.mjs src/store/upNext.ts
git commit -m "$(cat <<'EOF'
feat(upnext): add computeUpNextShows selector logic + UpNextEntry type

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Store action `logNextEpisodeWatch` + `useUpNextShows` selector

**Files:**
- Modify: `src/lib/db.ts` (extend `logEpisodeToDb` opts with an optional client `watchEventId`)
- Modify: `src/store/useAppStore.ts` (imports, `LibrarySlice` interface, action impl, exported selector)
- Modify: `scripts/verify-upnext-logic.mjs` (insert an advance-invariant section above the Summary block)

**Interfaces:**
- Consumes: `nextUnwatchedEpisode` (Task 1); `computeUpNextShows`, `UpNextEntry` (Task 2); existing `logEpisodeToDb`, `applyFiltersToTitles`, `computeLedgerStats`, `deleteEpisodeWatchEvent`.
- Produces:
  - `logNextEpisodeWatch(titleId: string): { seasonNumber: number; episodeNumber: number; watchEventId: string } | null` on the store.
  - `useUpNextShows(): UpNextEntry[]` exported selector hook.
  - `logEpisodeToDb(userId, episodeId, opts)` now accepts `opts.watchEventId?: string`.

- [ ] **Step 1: Extend `logEpisodeToDb` to accept a client watch-event id**

In `src/lib/db.ts`, in `logEpisodeToDb`, add `watchEventId?: string` to the `opts` type and include it in the `episode_watch_events` insert. Replace the opts type and the watch-event insert block (around lines 516–537):

```ts
  opts: {
    watchedAt?: string
    watchNotes?: string
    rating?: number
    reviewText?: string
    colorMode?: 'bw' | 'color'
    watchEventId?: string // client-supplied uuid so the optimistic store id matches the DB row (enables reliable delete/undo)
  }
): Promise<void> {
  if (!supabase) return

  if (opts.watchedAt) {
    const { error } = await supabase.from('episode_watch_events').insert({
      ...(opts.watchEventId ? { id: opts.watchEventId } : {}),
      episode_id: episodeId,
      user_id: userId,
      watched_at: opts.watchedAt,
      notes: opts.watchNotes || undefined,
      color_mode: opts.colorMode ?? null,
    })
    if (error) {
      console.error('Error inserting episode watch event:', error)
      throw error
    }
  }
```

(Leave the `rating` and `reviewText` blocks below unchanged.)

- [ ] **Step 2: Type-check the db change**

Run: `npm run build`
Expected: PASS — no errors (the new opts field is optional, so existing callers are unaffected).

- [ ] **Step 3: Add imports and the action to the store**

In `src/store/useAppStore.ts`:

Add to the imports near the top (after the existing `./ledgerStats` import):

```ts
import { nextUnwatchedEpisode } from './episodeUtils'
import { computeUpNextShows, type UpNextEntry } from './upNext'
```

Add this line to the `LibrarySlice` interface (after `deleteEpisodeWatchEvent`):

```ts
  logNextEpisodeWatch: (titleId: string) => { seasonNumber: number; episodeNumber: number; watchEventId: string } | null
```

Add the action implementation inside the store object, immediately after the `logEpisode: (...) => set(...)` block (before `removeViewing`):

```ts
  logNextEpisodeWatch: (titleId) => {
    const state = get()
    const title = state.titles.find((t) => t.id === titleId)
    if (!title || !title.seasons) return null
    const next = nextUnwatchedEpisode(title.seasons)
    if (!next) return null

    const seasonNumber = next.season.seasonNumber
    const episodeNumber = next.episode.episodeNumber
    const episodeId = next.episode.id
    const watchEventId = crypto.randomUUID()
    const watchedAt = new Date().toISOString().slice(0, 10)

    if (state.user) {
      logEpisodeToDb(state.user.id, episodeId, { watchedAt, watchEventId }).catch((err) =>
        console.error('Failed to sync quick episode log to DB:', err)
      )
    }

    set((s) => {
      const titles = s.titles.map((t) => {
        if (t.id !== titleId) return t
        const seasons = (t.seasons ?? []).map((season) => {
          if (season.seasonNumber !== seasonNumber || !season.episodes) return season
          const episodes = season.episodes.map((ep) =>
            ep.episodeNumber === episodeNumber
              ? { ...ep, watchEvents: [...ep.watchEvents, { id: watchEventId, watchedAt }] }
              : ep
          )
          const episodesWatched = episodes.filter((e) => e.watchEvents.length > 0).length
          return { ...season, episodes, episodesWatched }
        })
        return { ...t, seasons }
      })
      return {
        titles,
        filteredTitles: applyFiltersToTitles(titles, s.filters),
        stats: computeLedgerStats(titles),
      }
    })

    return { seasonNumber, episodeNumber, watchEventId }
  },
```

Add the selector hook at the bottom of the file, alongside the other exported selectors (after `useAllTags`):

```ts
export const useUpNextShows = (): UpNextEntry[] => {
  const titles = useAppStore((s) => s.titles)
  return computeUpNextShows(titles)
}
```

- [ ] **Step 4: Add the advance-invariant verification section**

In `scripts/verify-upnext-logic.mjs`, **insert immediately above** the `// ── Summary ──` line:

```js
// ── Advance invariant: logging the next episode advances to the following one,
//    and logging the final episode yields null (finale). This is the contract
//    the store action `logNextEpisodeWatch` must uphold. ──
function appendWatch(seasons, sn, en) {
  return seasons.map((s) =>
    s.seasonNumber !== sn || !s.episodes
      ? s
      : { ...s, episodes: s.episodes.map((e) => e.episodeNumber === en ? { ...e, watchEvents: [...e.watchEvents, { id: 'we-new', watchedAt: '2026-06-22' }] } : e) }
  )
}

console.log('\n── 3. log-next advances / detects finale ──')
let seq = partialSeasons
const firstNext = nextUnwatchedEpisode(seq)            // S2E4
seq = appendWatch(seq, firstNext.season.seasonNumber, firstNext.episode.episodeNumber)
const afterFirst = nextUnwatchedEpisode(seq)
assert('after logging S2E4, next episode = 5', afterFirst ? afterFirst.episode.episodeNumber : null, 5)
seq = appendWatch(seq, afterFirst.season.seasonNumber, afterFirst.episode.episodeNumber)  // log S2E5 (final)
assertNull('after logging final episode, next = null (finale)', nextUnwatchedEpisode(seq))
```

- [ ] **Step 5: Run the verification script**

Run: `node scripts/verify-upnext-logic.mjs`
Expected: PASS — `Result: 17 passed, 0 failed`, exit code 0.

- [ ] **Step 6: Type-check and lint**

Run: `npm run build && npm run lint`
Expected: PASS — no type errors, no lint errors.

- [ ] **Step 7: Commit**

```bash
git add src/lib/db.ts src/store/useAppStore.ts scripts/verify-upnext-logic.mjs
git commit -m "$(cat <<'EOF'
feat(upnext): add logNextEpisodeWatch action + useUpNextShows selector

Mints a real uuid for the watch event and threads it through logEpisodeToDb
so the optimistic store id matches the DB row, enabling reliable undo.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Navigation, App shell, smart landing, and the read-only Up Next surface

**Files:**
- Create: `src/views/UpNext.tsx` (read-only surface — interactions arrive in Task 5)
- Modify: `src/App.tsx` (view union, render Up Next, hoist global modals, smart landing)
- Modify: `src/views/Library.tsx` (remove the now-hoisted modals)
- Modify: `src/components/TopBar.tsx` (add Up Next nav destination)
- Modify: `src/components/BottomNav.tsx` (add Up Next destination)

**Interfaces:**
- Consumes: `useUpNextShows`, `useAppStore` (`openDetailDrawer`), `computeUpNextShows` (Tasks 2–3); `DynamicPoster`; `UpNextEntry`.
- Produces: `UpNext` component with props `{ onBrowseLibrary: () => void }`. `AppView` type widened to `'upnext' | 'library' | 'ledger'` across `App`, `TopBar`, `BottomNav`.

- [ ] **Step 1: Create the read-only Up Next view**

Create `src/views/UpNext.tsx`:

```tsx
import { PlayCircle } from 'lucide-react'
import { useUpNextShows, useAppStore } from 'src/store/useAppStore'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import type { UpNextEntry } from 'src/store/upNext'

function EmptyState({ onBrowseLibrary }: { onBrowseLibrary: () => void }) {
  return (
    <div className="text-center py-24 px-5 text-paper-faint">
      <PlayCircle className="w-14 h-14 mx-auto mb-5 text-amber-deep opacity-50" />
      <p className="font-serif text-2xl text-paper-dim font-light">Nothing in progress.</p>
      <p className="font-sans text-sm mt-2 opacity-70">
        Start a series and set it to “Watching” to see your next episode here.
      </p>
      <button
        onClick={onBrowseLibrary}
        className="mt-6 inline-flex items-center gap-2 rounded-md px-4 py-2 text-sm font-sans border border-amber/30 text-amber hover:bg-amber/10 transition-colors"
      >
        Browse the Library
      </button>
    </div>
  )
}

function UpNextCard({ entry }: { entry: UpNextEntry }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const { title, season, episode, watchedCount, totalCount } = entry
  const pct = totalCount > 0 ? Math.round((watchedCount / totalCount) * 100) : 0
  const epName = episode.episodeName ?? `Episode ${episode.episodeNumber}`

  return (
    <div
      className="flex gap-4 rounded-xl p-3 sm:p-4"
      style={{ border: '1px solid var(--line)', background: 'linear-gradient(180deg, var(--ink-1), rgba(17,13,11,0.4))' }}
    >
      <button onClick={() => openDetailDrawer(title.id)} className="w-16 sm:w-20 shrink-0" aria-label={`Open ${title.title}`}>
        <DynamicPoster title={title} />
      </button>
      <div className="flex-1 min-w-0 flex flex-col">
        <button onClick={() => openDetailDrawer(title.id)} className="text-left">
          <h3 className="font-serif text-lg sm:text-xl font-medium text-paper truncate" style={{ fontVariationSettings: '"opsz" 30' }}>
            {title.title}
          </h3>
        </button>
        <p className="font-mono text-xs text-amber mt-0.5">S{season.seasonNumber} E{episode.episodeNumber} · Next</p>
        <p className="font-sans text-sm text-paper-dim truncate">{epName}</p>
        <div className="mt-auto pt-3">
          <div className="flex items-center gap-2">
            <div className="flex-1 h-1.5 rounded-full overflow-hidden" style={{ background: 'rgba(255,255,255,0.06)' }}>
              <div className="h-full rounded-full bg-amber transition-all" style={{ width: `${pct}%` }} />
            </div>
            <span className="font-mono text-[11px] text-paper-faint shrink-0">{watchedCount}/{totalCount}</span>
          </div>
        </div>
      </div>
    </div>
  )
}

export function UpNext({ onBrowseLibrary }: { onBrowseLibrary: () => void }) {
  const shows = useUpNextShows()

  return (
    <div className="max-w-[1100px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <header className="mb-6">
        <p className="kicker"><span className="dot" /> continue watching</p>
        <h1 className="display-title text-[clamp(32px,6vw,56px)] mt-3">Up Next</h1>
      </header>
      {shows.length === 0 ? (
        <EmptyState onBrowseLibrary={onBrowseLibrary} />
      ) : (
        <div className="space-y-3">
          {shows.map((entry) => (
            <UpNextCard key={entry.title.id} entry={entry} />
          ))}
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 2: Wire Up Next into `App`, hoist modals, add smart landing**

Replace the entire contents of `src/App.tsx` with:

```tsx
import { useState, useEffect } from 'react'
import { TopBar } from 'src/components/TopBar'
import { BottomNav } from 'src/components/BottomNav'
import { AddTitleWorkflow } from 'src/components/AddTitleWorkflow'
import { UpNext } from 'src/views/UpNext'
import { Library } from 'src/views/Library'
import { Ledger } from 'src/views/Ledger'
import { TitleDetailDrawer } from 'src/components/TitleDetailDrawer'
import { RefreshMetadataModal } from 'src/components/RefreshMetadataModal'
import { isSupabaseConfigured, onAuthStateChange } from 'src/lib/auth'
import { useAppStore } from 'src/store/useAppStore'
import { computeUpNextShows } from 'src/store/upNext'
import { ProfileModal } from 'src/components/ProfileModal'

type AppView = 'upnext' | 'library' | 'ledger'

export default function App() {
  // Smart landing: open Up Next when shows are in progress, else Library.
  // Computed once from the synchronously-rehydrated persisted titles.
  const [currentView, setCurrentView] = useState<AppView>(() =>
    computeUpNextShows(useAppStore.getState().titles).length > 0 ? 'upnext' : 'library'
  )
  const [isProfileOpen, setIsProfileOpen] = useState(false)
  const setUser = useAppStore((s) => s.setUser)
  const loadSharedLibrary = useAppStore((s) => s.loadSharedLibrary)

  useEffect(() => {
    if (!isSupabaseConfigured) return

    const params = new URLSearchParams(window.location.search)
    const shareToken = params.get('share')

    if (shareToken) {
      loadSharedLibrary(shareToken)
      return
    }

    const subscription = onAuthStateChange((user) => {
      setUser(user)
    })

    return () => {
      subscription.unsubscribe()
    }
  }, [setUser, loadSharedLibrary])

  return (
    <div className="relative min-h-screen">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:z-[300] focus:top-3 focus:left-3 focus:px-4 focus:py-2 focus:rounded-md focus:bg-amber focus:text-void focus:font-sans focus:text-sm focus:font-medium focus:shadow-lg"
      >
        Skip to content
      </a>

      {/* ── Atmosphere layers (fixed, full-viewport) ── */}
      <div className="projector-beam" aria-hidden="true" />
      <div className="dust" aria-hidden="true" />
      <div className="vignette" aria-hidden="true" />
      <div className="grain" aria-hidden="true" />

      <TopBar
        currentView={currentView}
        onViewChange={setCurrentView}
        onProfileClick={() => setIsProfileOpen(true)}
      />

      <main id="main-content" key={currentView} className="animate-view-in pb-24 sm:pb-12">
        {currentView === 'upnext' && <UpNext onBrowseLibrary={() => setCurrentView('library')} />}
        {currentView === 'library' && <Library />}
        {currentView === 'ledger' && <Ledger />}
      </main>

      <BottomNav currentView={currentView} onViewChange={setCurrentView} />
      <AddTitleWorkflow />
      <TitleDetailDrawer />
      <RefreshMetadataModal />
      <ProfileModal open={isProfileOpen} onClose={() => setIsProfileOpen(false)} />
    </div>
  )
}
```

- [ ] **Step 3: Remove the hoisted modals from `Library`**

In `src/views/Library.tsx`, delete these two import lines (near the top):

```tsx
import { TitleDetailDrawer } from 'src/components/TitleDetailDrawer'
import { RefreshMetadataModal } from 'src/components/RefreshMetadataModal'
```

And delete the two JSX mounts at the end of the `Library` component so the tail reads:

```tsx
      <FilterPanel open={filterOpen} onClose={() => setFilterOpen(false)} activeFilterCount={activeFilterCount} />
    </div>
  )
}
```

- [ ] **Step 4: Add Up Next to the desktop TopBar**

In `src/components/TopBar.tsx`:

Add `PlayCircle` to the lucide import:

```tsx
import { Plus, LayoutGrid, List, BarChart3, User, PlayCircle } from 'lucide-react'
```

Widen the props type:

```tsx
interface TopBarProps {
  currentView: 'upnext' | 'library' | 'ledger'
  onViewChange: (view: 'upnext' | 'library' | 'ledger') => void
  onProfileClick: () => void
}
```

Replace the `NAV` constant with (Up Next leftmost):

```tsx
const NAV: { id: 'upnext' | 'library' | 'ledger'; label: string; Icon: typeof BarChart3 }[] = [
  { id: 'upnext', label: 'Up Next', Icon: PlayCircle },
  { id: 'library', label: 'The Library', Icon: LayoutGrid },
  { id: 'ledger', label: 'The Ledger', Icon: BarChart3 },
]
```

(The grid/list view-mode toggle stays gated on `currentView === 'library'` — no change.)

- [ ] **Step 5: Add Up Next to the mobile BottomNav**

Replace the entire contents of `src/components/BottomNav.tsx` with:

```tsx
import { LayoutGrid, BarChart3, Plus, PlayCircle } from 'lucide-react'
import { useAppStore } from 'src/store/useAppStore'
import { cn } from 'src/lib/utils'

interface BottomNavProps {
  currentView: 'upnext' | 'library' | 'ledger'
  onViewChange: (view: 'upnext' | 'library' | 'ledger') => void
}

function NavTab({
  active,
  onClick,
  label,
  Icon,
}: {
  active: boolean
  onClick: () => void
  label: string
  Icon: typeof LayoutGrid
}) {
  return (
    <button
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'flex flex-col items-center gap-1 px-3 pt-3 pb-1 relative transition-colors',
        active ? 'text-amber' : 'text-paper-faint'
      )}
    >
      <span
        className={cn(
          'absolute top-0 left-1/2 -translate-x-1/2 h-0.5 rounded-b-full bg-amber transition-all duration-300',
          active ? 'w-8 shadow-[0_0_8px_rgba(233,178,102,0.6)]' : 'w-0'
        )}
      />
      <Icon className="w-5 h-5" />
      <span className="text-[11px] font-sans">{label}</span>
    </button>
  )
}

export function BottomNav({ currentView, onViewChange }: BottomNavProps) {
  const openAddTitle = useAppStore((s) => s.openAddTitle)

  return (
    <nav
      className="fixed bottom-0 inset-x-0 z-[200] sm:hidden border-t"
      style={{
        borderColor: 'var(--line)',
        background: 'linear-gradient(0deg, rgba(11,9,7,0.96), rgba(11,9,7,0.78))',
        backdropFilter: 'blur(14px)',
        WebkitBackdropFilter: 'blur(14px)',
      }}
    >
      <div className="flex items-center justify-around h-16 px-2">
        <NavTab active={currentView === 'upnext'} onClick={() => onViewChange('upnext')} label="Up Next" Icon={PlayCircle} />
        <NavTab active={currentView === 'library'} onClick={() => onViewChange('library')} label="Library" Icon={LayoutGrid} />

        <button onClick={openAddTitle} className="flex flex-col items-center gap-0.5 px-3 py-2" aria-label="Add Title">
          <div
            className="w-11 h-11 rounded-full flex items-center justify-center -mt-6 amber-glow transition-transform active:scale-95"
            style={{ background: 'linear-gradient(180deg, var(--amber-bright), var(--amber))' }}
          >
            <Plus className="w-5 h-5 text-void" strokeWidth={2.5} />
          </div>
          <span className="text-[11px] font-sans text-paper-faint mt-0.5">Add</span>
        </button>

        <NavTab active={currentView === 'ledger'} onClick={() => onViewChange('ledger')} label="Ledger" Icon={BarChart3} />
      </div>
    </nav>
  )
}
```

- [ ] **Step 6: Type-check and lint**

Run: `npm run build && npm run lint`
Expected: PASS — no type errors, no lint errors.

- [ ] **Step 7: Manual verification**

Run: `npm run dev`, then open `http://localhost:5173/CinemArchive/` in a **fresh/incognito** window (so no prior `localStorage` overrides the seeded mock data).

Verify:
1. The app lands on **Up Next** (smart landing — Severance is seeded mid-season).
2. Up Next shows **one card: Severance**, reading `S2 E6 · Next` with a progress bar `14/19`. (Black Mirror is `watching` but caught up at 13/13, so it is correctly **absent**.)
3. The TopBar pill nav (desktop width) shows `Up Next · The Library · The Ledger`; clicking each switches views.
4. Narrow the window below `sm` (640px): the BottomNav shows `Up Next · Library · (＋) · Ledger`; each tab works and the active tab shows the amber underline.
5. Clicking the Severance card (poster or title) opens the detail drawer from the Up Next view (confirms the hoisted drawer works).
6. Switch to Library, open any title's drawer — it still opens (hoist didn't break Library).

- [ ] **Step 8: Commit**

```bash
git add src/views/UpNext.tsx src/App.tsx src/views/Library.tsx src/components/TopBar.tsx src/components/BottomNav.tsx
git commit -m "$(cat <<'EOF'
feat(upnext): add Up Next home tab, nav entries, and smart landing

Hoists the detail-drawer and refresh-metadata modals to App so both
Library and Up Next can open them. Up Next renders a read-only
continue-watching surface; one-tap logging arrives next.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: One-tap Mark-watched, Undo, and the finale / caught-up flow

**Files:**
- Modify: `src/views/UpNext.tsx` (replace the read-only card with interactive cards + parent retention of finished shows)

**Interfaces:**
- Consumes: `logNextEpisodeWatch`, `deleteEpisodeWatchEvent`, `updateTitle`, `openDetailDrawer`, `isSharedView` from the store; `nextUnwatchedEpisode` (Task 1); `UpNextEntry` (Task 2); `Title` type.
- Produces: final `UpNext` view with interactive `LiveCard` and `CaughtUpCard`.

**Behavior recap (from the spec):** Tapping **Mark watched** logs today on the next episode and advances. A non-final log shows an inline `Watched ✓ · Undo` for ~6s on the same (now-advanced) card. Logging the *final* episode removes the show from the live list, so the parent retains a transient `CaughtUpCard` (`All caught up ✓` + `Mark series watched` + `Undo`) for ~6s. All actions are hidden when `isSharedView`.

- [ ] **Step 1: Replace `src/views/UpNext.tsx` with the interactive version**

Replace the entire file with:

```tsx
import { useState, useRef, useEffect, useCallback } from 'react'
import { PlayCircle, Check, Undo2 } from 'lucide-react'
import { useUpNextShows, useAppStore } from 'src/store/useAppStore'
import { nextUnwatchedEpisode } from 'src/store/episodeUtils'
import { DynamicPoster } from 'src/components/ui/dynamic-poster'
import type { UpNextEntry } from 'src/store/upNext'
import type { Title } from 'src/store/mockData'

const UNDO_WINDOW_MS = 6000

type PendingUndo = { seasonNumber: number; episodeNumber: number; watchEventId: string; label: string }
type FinishedCard = { snapshot: UpNextEntry; undo: PendingUndo }

// ─── Shared frame (poster + clickable title) ─────────────────────────────────

function CardFrame({ title, onOpen, children }: { title: Title; onOpen: () => void; children: React.ReactNode }) {
  return (
    <div
      className="flex gap-4 rounded-xl p-3 sm:p-4"
      style={{ border: '1px solid var(--line)', background: 'linear-gradient(180deg, var(--ink-1), rgba(17,13,11,0.4))' }}
    >
      <button onClick={onOpen} className="w-16 sm:w-20 shrink-0" aria-label={`Open ${title.title}`}>
        <DynamicPoster title={title} />
      </button>
      <div className="flex-1 min-w-0 flex flex-col">
        <button onClick={onOpen} className="text-left">
          <h3 className="font-serif text-lg sm:text-xl font-medium text-paper truncate" style={{ fontVariationSettings: '"opsz" 30' }}>
            {title.title}
          </h3>
        </button>
        {children}
      </div>
    </div>
  )
}

function ProgressBar({ watched, total }: { watched: number; total: number }) {
  const pct = total > 0 ? Math.round((watched / total) * 100) : 0
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 h-1.5 rounded-full overflow-hidden" style={{ background: 'rgba(255,255,255,0.06)' }}>
        <div className="h-full rounded-full bg-amber transition-all" style={{ width: `${pct}%` }} />
      </div>
      <span className="font-mono text-[11px] text-paper-faint shrink-0">{watched}/{total}</span>
    </div>
  )
}

// ─── Empty state ─────────────────────────────────────────────────────────────

function EmptyState({ onBrowseLibrary }: { onBrowseLibrary: () => void }) {
  return (
    <div className="text-center py-24 px-5 text-paper-faint">
      <PlayCircle className="w-14 h-14 mx-auto mb-5 text-amber-deep opacity-50" />
      <p className="font-serif text-2xl text-paper-dim font-light">Nothing in progress.</p>
      <p className="font-sans text-sm mt-2 opacity-70">
        Start a series and set it to “Watching” to see your next episode here.
      </p>
      <button
        onClick={onBrowseLibrary}
        className="mt-6 inline-flex items-center gap-2 rounded-md px-4 py-2 text-sm font-sans border border-amber/30 text-amber hover:bg-amber/10 transition-colors"
      >
        Browse the Library
      </button>
    </div>
  )
}

// ─── Live (in-progress) card ─────────────────────────────────────────────────

function LiveCard({ entry, onFinale }: { entry: UpNextEntry; onFinale: (snapshot: UpNextEntry, undo: PendingUndo) => void }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const logNextEpisodeWatch = useAppStore((s) => s.logNextEpisodeWatch)
  const deleteEpisodeWatchEvent = useAppStore((s) => s.deleteEpisodeWatchEvent)
  const isSharedView = useAppStore((s) => s.isSharedView)

  const { title, season, episode, watchedCount, totalCount } = entry
  const epName = episode.episodeName ?? `Episode ${episode.episodeNumber}`

  const [pendingUndo, setPendingUndo] = useState<PendingUndo | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => { if (timerRef.current) clearTimeout(timerRef.current) }, [])

  function handleMarkWatched() {
    const label = `S${season.seasonNumber} E${episode.episodeNumber}`
    const result = logNextEpisodeWatch(title.id)
    if (!result) return
    const undo: PendingUndo = { ...result, label }
    const updated = useAppStore.getState().titles.find((t) => t.id === title.id)
    const isFinale = !updated?.seasons || nextUnwatchedEpisode(updated.seasons) === null
    if (isFinale) {
      // This card is about to unmount (the show leaves the live list); hand the
      // finished state to the parent so it can show a caught-up card.
      onFinale(entry, undo)
      return
    }
    setPendingUndo(undo)
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => setPendingUndo(null), UNDO_WINDOW_MS)
  }

  function handleUndo() {
    if (!pendingUndo) return
    deleteEpisodeWatchEvent(title.id, pendingUndo.seasonNumber, pendingUndo.episodeNumber, pendingUndo.watchEventId)
    if (timerRef.current) clearTimeout(timerRef.current)
    setPendingUndo(null)
  }

  return (
    <CardFrame title={title} onOpen={() => openDetailDrawer(title.id)}>
      <p className="font-mono text-xs text-amber mt-0.5">S{season.seasonNumber} E{episode.episodeNumber} · Next</p>
      <p className="font-sans text-sm text-paper-dim truncate">{epName}</p>
      <div className="mt-auto pt-3">
        <ProgressBar watched={watchedCount} total={totalCount} />
        {!isSharedView && (
          pendingUndo ? (
            <div className="flex items-center justify-between mt-3">
              <span className="font-mono text-xs text-amber inline-flex items-center gap-1.5">
                <Check className="w-3.5 h-3.5" /> Watched {pendingUndo.label}
              </span>
              <button onClick={handleUndo} className="font-mono text-xs text-paper-faint hover:text-paper inline-flex items-center gap-1 transition-colors">
                <Undo2 className="w-3.5 h-3.5" /> Undo
              </button>
            </div>
          ) : (
            <button onClick={handleMarkWatched} className="btn-amber inline-flex items-center justify-center gap-2 rounded-md w-full mt-3 py-2 text-[13px] font-bold">
              <Check className="w-4 h-4" /> Mark watched
            </button>
          )
        )}
      </div>
    </CardFrame>
  )
}

// ─── Caught-up (just-finished) card ──────────────────────────────────────────

function CaughtUpCard({ snapshot, undo, onDismiss }: { snapshot: UpNextEntry; undo: PendingUndo; onDismiss: (titleId: string) => void }) {
  const openDetailDrawer = useAppStore((s) => s.openDetailDrawer)
  const deleteEpisodeWatchEvent = useAppStore((s) => s.deleteEpisodeWatchEvent)
  const updateTitle = useAppStore((s) => s.updateTitle)
  const isSharedView = useAppStore((s) => s.isSharedView)
  const { title } = snapshot

  // Keep the latest onDismiss without resetting the dismissal timer each render.
  const onDismissRef = useRef(onDismiss)
  onDismissRef.current = onDismiss
  useEffect(() => {
    const id = setTimeout(() => onDismissRef.current(title.id), UNDO_WINDOW_MS)
    return () => clearTimeout(id)
  }, [title.id])

  function handleUndo() {
    deleteEpisodeWatchEvent(title.id, undo.seasonNumber, undo.episodeNumber, undo.watchEventId)
    onDismiss(title.id)
  }
  function handleMarkSeriesWatched() {
    updateTitle(title.id, { status: 'watched' })
    onDismiss(title.id)
  }

  return (
    <CardFrame title={title} onOpen={() => openDetailDrawer(title.id)}>
      <p className="font-mono text-xs text-amber mt-0.5 inline-flex items-center gap-1.5">
        <Check className="w-3.5 h-3.5" /> All caught up
      </p>
      <p className="font-sans text-sm text-paper-dim truncate">You finished {title.title}.</p>
      {!isSharedView && (
        <div className="mt-auto pt-3 flex items-center justify-between gap-2">
          <button onClick={handleMarkSeriesWatched} className="font-mono text-xs text-amber hover:opacity-80 transition-opacity">
            Mark series watched
          </button>
          <button onClick={handleUndo} className="font-mono text-xs text-paper-faint hover:text-paper inline-flex items-center gap-1 transition-colors">
            <Undo2 className="w-3.5 h-3.5" /> Undo
          </button>
        </div>
      )}
    </CardFrame>
  )
}

// ─── Up Next view ────────────────────────────────────────────────────────────

export function UpNext({ onBrowseLibrary }: { onBrowseLibrary: () => void }) {
  const shows = useUpNextShows()
  const [finished, setFinished] = useState<FinishedCard[]>([])

  const dismissFinished = useCallback((titleId: string) => {
    setFinished((f) => f.filter((c) => c.snapshot.title.id !== titleId))
  }, [])

  const handleFinale = useCallback((snapshot: UpNextEntry, undo: PendingUndo) => {
    setFinished((f) => [...f.filter((c) => c.snapshot.title.id !== snapshot.title.id), { snapshot, undo }])
  }, [])

  // A title can't be both live and finished; if a finished show reappears live
  // (e.g. after Undo) drop its caught-up card so it isn't rendered twice.
  const liveIds = new Set(shows.map((s) => s.title.id))
  const finishedToShow = finished.filter((c) => !liveIds.has(c.snapshot.title.id))

  const isEmpty = shows.length === 0 && finishedToShow.length === 0

  return (
    <div className="max-w-[1100px] mx-auto px-4 sm:px-8 pt-6 sm:pt-10">
      <header className="mb-6">
        <p className="kicker"><span className="dot" /> continue watching</p>
        <h1 className="display-title text-[clamp(32px,6vw,56px)] mt-3">Up Next</h1>
      </header>
      {isEmpty ? (
        <EmptyState onBrowseLibrary={onBrowseLibrary} />
      ) : (
        <div className="space-y-3">
          {shows.map((entry) => (
            <LiveCard key={entry.title.id} entry={entry} onFinale={handleFinale} />
          ))}
          {finishedToShow.map((c) => (
            <CaughtUpCard key={c.snapshot.title.id} snapshot={c.snapshot} undo={c.undo} onDismiss={dismissFinished} />
          ))}
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 2: Type-check and lint**

Run: `npm run build && npm run lint`
Expected: PASS — no type errors, no lint errors.

- [ ] **Step 3: Manual verification (core one-tap + undo)**

Run: `npm run dev`, open `http://localhost:5173/CinemArchive/` in a **fresh/incognito** window. On the Up Next tab with the Severance card (`S2 E6 · Next`, `14/19`):
1. Click **Mark watched**. The button area becomes `Watched S2 E6` with an **Undo**; the card's "Next" line advances to `S2 E7` and the progress reads `15/19`.
2. Wait ~6s — the `Watched/Undo` row disappears, leaving the advanced card (`S2 E7`, `15/19`).
3. Click **Mark watched** again, then click **Undo** within 6s. The card reverts to the prior episode and the progress count drops back by one.

- [ ] **Step 4: Manual verification (finale / caught-up)**

Continuing on Severance:
1. Click **Mark watched** repeatedly (waiting briefly or undoing as needed) until you log **S2 E10** (the final episode). The live card is replaced by a **caught-up card**: `All caught up · You finished Severance.` with **Mark series watched** and **Undo**.
2. Click **Undo** on the caught-up card within ~6s → Severance reappears as a live card showing `S2 E10 · Next` again, `18/19`.
3. Log S2 E10 again to return to the caught-up card; this time click **Mark series watched** → the card disappears and Severance leaves Up Next. Switch to Library and confirm Severance's status is now **Watched**.
4. If you let the caught-up card sit untouched for ~6s, it disappears on its own (the show stays `watching` with no next episode, so it simply isn't listed).

- [ ] **Step 5: Manual verification (empty + shared read-only)**

1. With Severance marked Watched (from Step 4) and Black Mirror already caught up, the Up Next tab now shows the **empty state** with a **Browse the Library** button; clicking it switches to Library.
2. (If Supabase + a shared link is available) open the app with a `?share=<token>` URL: the Up Next cards render but **no Mark watched / Undo / Mark series watched** buttons appear (read-only), consistent with the drawer.

- [ ] **Step 6: Commit**

```bash
git add src/views/UpNext.tsx
git commit -m "$(cat <<'EOF'
feat(upnext): one-tap mark-watched with undo and finale handling

LiveCard logs today on the next episode and advances with an inline undo;
finishing the last episode hands off to a retained CaughtUpCard offering
undo or an explicit "mark series watched", gated to non-shared views.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Plan Self-Review

**Spec coverage** (each spec section → task):
- §2 placement / new tab → Task 4 (TopBar, BottomNav, App render).
- §3.1 tab + icon → Task 4. §3.2 eligibility → Tasks 1–2 (`nextUnwatchedEpisode`, `computeUpNextShows`). §3.3 card anatomy → Task 4 (read-only) refined in Task 5. §3.4 one-tap flow + undo → Task 5 (`LiveCard`). §3.5 finale / caught-up + "Mark series watched" → Task 5 (`CaughtUpCard` + parent retention). §3.6 ordering `(lastWatchedAt ?? addedAt)` desc → Task 2. §3.7 empty state → Tasks 4/5 (`EmptyState`). §3.8 shared read-only → Task 5 (`isSharedView` gates).
- §4 smart landing → Task 4 (lazy `useState` initializer). §5.1/§5.2 computations → Tasks 1–2. §6 hoist drawer + landing → Task 4. §7 coarse-only excluded → Task 1 (skip seasons without `episodes[]`), verified; Spider Noir no-prompt → inherent (Up Next calls `logNextEpisodeWatch` directly, never the drawer's noir flow, so no `colorMode` is set). §8 verification → `scripts/verify-upnext-logic.mjs` (Tasks 1–3) + build/lint/manual.
- §9 out-of-scope items are not implemented (no watchlist queue, movies, reordering, stills, auto-status beyond the explicit link). ✓

**Placeholder scan:** No TBD/TODO; every code step contains complete code; every command lists expected output. ✓

**Type consistency:** `nextUnwatchedEpisode(seasons) → { season, episode } | null` (Task 1) is consumed identically in Tasks 2, 3, 5. `UpNextEntry` shape (Task 2) is consumed by `useUpNextShows` (Task 3) and `UpNext`/cards (Tasks 4–5). `logNextEpisodeWatch(titleId) → { seasonNumber, episodeNumber, watchEventId } | null` (Task 3) matches `PendingUndo` construction and `deleteEpisodeWatchEvent(titleId, seasonNumber, episodeNumber, watchEventId)` (existing signature) in Task 5. `AppView = 'upnext' | 'library' | 'ledger'` is identical across `App`, `TopBar`, `BottomNav` (Task 4). ✓
