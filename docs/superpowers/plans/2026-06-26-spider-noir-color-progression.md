# Spider-Noir Color Mode Progression — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a mode selector, pin mechanic, and animated silk-thread spider web to the Spider-Man: Noir title drawer, backed by a cross-device-synced Supabase table.

**Architecture:** Unlocked/earned modes are computed client-side from already-loaded watch events (`getUnlockedModes`, `getEarnedModes` in `episodeUtils.ts`). The only new persistent value is which mode (if any) is pinned, stored in a new `user_title_pins` Supabase table loaded at auth time alongside the library. An inline-SVG `SpiderWebOverlay` component replaces the old static background-image div, animating individual spokes outward then drawing concentric rings via `stroke-dashoffset` CSS keyframes.

**Tech Stack:** React + TypeScript, Zustand, Supabase JS client, CSS keyframe animations (`stroke-dashoffset`), Lucide icons.

## Global Constraints

- No test runner — verify pure logic via `scripts/verify-*.mjs` (node); verify UI code via `npx tsc --noEmit` + `npm run lint` + `npm run build`
- Follow existing optimistic-write pattern: update Zustand store first, then fire DB write as fire-and-forget with `console.error` on failure
- No new npm packages
- All `style={}` props use existing CSS variables (`var(--amber)`, `var(--line)`, etc.)
- RLS: every new Supabase table must have `enable row level security` + a policy in the migration
- `pinnedModes` must NOT be added to the `partialize` list in the Zustand `persist` config (it's loaded from Supabase, not localStorage)
- The `SPIDER_NOIR_TMDB_ID` constant in `TitleDetailDrawer.tsx` is `220102`
- Easter egg key for this feature is the string `'spider_noir_color'`

---

### Task 1: DB migration and schema.sql sync

**Files:**
- Create: `supabase/migrations/20260626000001_user_title_pins.sql`
- Modify: `schema.sql`

**Interfaces:**
- Produces: `user_title_pins` table available in Supabase (used by Task 2 DB helpers)

- [ ] **Step 1: Write the migration file**

Create `supabase/migrations/20260626000001_user_title_pins.sql`:

```sql
-- Extensible per-user, per-title easter-egg pin storage.
-- One row per (user, title, easter_egg_key). Deleting the row = unpinned.
create table user_title_pins (
  user_id        uuid not null references auth.users on delete cascade,
  title_id       uuid not null references titles(id) on delete cascade,
  easter_egg_key text not null,
  pinned_variant text check (pinned_variant in ('bw', 'color')),
  updated_at     timestamptz not null default now(),
  primary key (user_id, title_id, easter_egg_key)
);

alter table user_title_pins enable row level security;

create policy "user_title_pins: owner full access"
  on user_title_pins for all
  using  (auth.uid() = user_id)
  with check (auth.uid() = user_id);
```

- [ ] **Step 2: Sync schema.sql**

Append the same table definition to `schema.sql`, right before the `-- API CACHE` section:

```sql
-- ============================================================
-- USER TITLE PINS (easter egg pin storage)
-- ============================================================

create table user_title_pins (
  user_id        uuid not null references auth.users on delete cascade,
  title_id       uuid not null references titles(id) on delete cascade,
  easter_egg_key text not null,
  pinned_variant text check (pinned_variant in ('bw', 'color')),
  updated_at     timestamptz not null default now(),
  primary key (user_id, title_id, easter_egg_key)
);

alter table user_title_pins enable row level security;

create policy "user_title_pins: owner full access"
  on user_title_pins for all
  using  (auth.uid() = user_id)
  with check (auth.uid() = user_id);
```

- [ ] **Step 3: Commit**

```bash
rtk git add supabase/migrations/20260626000001_user_title_pins.sql schema.sql
rtk git commit -m "feat: add user_title_pins migration for easter egg pin storage"
```

---

### Task 2: DB helper functions

**Files:**
- Modify: `src/lib/db.ts`

**Interfaces:**
- Produces:
  - `fetchAllTitlePins(userId: string): Promise<Array<{ titleId: string; easterEggKey: string; pinnedVariant: 'bw' | 'color' }>>`
  - `upsertTitlePin(userId: string, titleId: string, easterEggKey: string, pinnedVariant: 'bw' | 'color'): Promise<void>`
  - `deleteTitlePin(userId: string, titleId: string, easterEggKey: string): Promise<void>`

- [ ] **Step 1: Add the three helper functions to `src/lib/db.ts`**

Append after the last export in the file:

```ts
// ─── User Title Pins ──────────────────────────────────────────────────────────

export async function fetchAllTitlePins(
  userId: string
): Promise<Array<{ titleId: string; easterEggKey: string; pinnedVariant: 'bw' | 'color' }>> {
  if (!supabase) return []
  const { data, error } = await supabase
    .from('user_title_pins')
    .select('title_id, easter_egg_key, pinned_variant')
    .eq('user_id', userId)
  if (error) {
    console.error('fetchAllTitlePins:', error)
    return []
  }
  return (data ?? []).map((row) => ({
    titleId: row.title_id as string,
    easterEggKey: row.easter_egg_key as string,
    pinnedVariant: row.pinned_variant as 'bw' | 'color',
  }))
}

export async function upsertTitlePin(
  userId: string,
  titleId: string,
  easterEggKey: string,
  pinnedVariant: 'bw' | 'color'
): Promise<void> {
  if (!supabase) return
  const { error } = await supabase
    .from('user_title_pins')
    .upsert({
      user_id: userId,
      title_id: titleId,
      easter_egg_key: easterEggKey,
      pinned_variant: pinnedVariant,
      updated_at: new Date().toISOString(),
    })
  if (error) console.error('upsertTitlePin:', error)
}

export async function deleteTitlePin(
  userId: string,
  titleId: string,
  easterEggKey: string
): Promise<void> {
  if (!supabase) return
  const { error } = await supabase
    .from('user_title_pins')
    .delete()
    .eq('user_id', userId)
    .eq('title_id', titleId)
    .eq('easter_egg_key', easterEggKey)
  if (error) console.error('deleteTitlePin:', error)
}
```

- [ ] **Step 2: Type-check**

```bash
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
rtk git add src/lib/db.ts
rtk git commit -m "feat: add fetchAllTitlePins, upsertTitlePin, deleteTitlePin to db.ts"
```

---

### Task 3: Episode utility functions + verify script

**Files:**
- Modify: `src/store/episodeUtils.ts`
- Create: `scripts/verify-episode-modes.mjs`

**Interfaces:**
- Consumes: `Title`, `Season`, `Episode` shapes (from `src/store/mockData.ts`)
- Produces:
  - `getUnlockedModes(title: Title): Set<'bw' | 'color'>` — any episode has a watch event in that mode
  - `getEarnedModes(title: Title): Set<'bw' | 'color'>` — every episode has at least one watch event in that mode

- [ ] **Step 1: Write the verify script (pure JS mirror)**

Create `scripts/verify-episode-modes.mjs`:

```js
/**
 * Verify getUnlockedModes and getEarnedModes logic.
 * Run with: node scripts/verify-episode-modes.mjs
 */

function getUnlockedModes(title) {
  const modes = new Set()
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) {
      for (const we of ep.watchEvents) {
        if (we.colorMode) modes.add(we.colorMode)
      }
    }
  }
  return modes
}

function getEarnedModes(title) {
  const allEps = []
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) allEps.push(ep)
  }
  if (allEps.length === 0) return new Set()
  const earned = new Set()
  for (const mode of ['bw', 'color']) {
    if (allEps.every((ep) => ep.watchEvents.some((we) => we.colorMode === mode))) {
      earned.add(mode)
    }
  }
  return earned
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

let passed = 0, failed = 0
function assert(label, cond) {
  if (cond) { console.log(`  ✓ ${label}`); passed++ }
  else { console.error(`  ✗ ${label}`); failed++ }
}
const ep = (n, watchEvents) => ({ id: `e${n}`, episodeNumber: n, watchEvents, ratings: [], reviews: [] })
const we = (colorMode) => ({ id: 'w1', watchedAt: '2026-01-01', colorMode })
const season = (episodes) => ({ seasonNumber: 1, episodes })
const title = (seasons) => ({ seasons })

// ─── getUnlockedModes ────────────────────────────────────────────────────────

console.log('\ngetUnlockedModes')

assert('no watch events → empty',
  getUnlockedModes(title([season([ep(1, [])])])).size === 0)

assert('one bw event → bw unlocked, not color',
  (() => { const s = getUnlockedModes(title([season([ep(1, [we('bw')])])])); return s.has('bw') && !s.has('color') })())

assert('one color event → color unlocked, not bw',
  (() => { const s = getUnlockedModes(title([season([ep(1, [we('color')])])])); return s.has('color') && !s.has('bw') })())

assert('bw on ep1 + color on ep2 → both unlocked',
  (() => { const s = getUnlockedModes(title([season([ep(1, [we('bw')]), ep(2, [we('color')])])])); return s.has('bw') && s.has('color') })())

assert('undefined colorMode on event → not counted',
  getUnlockedModes(title([season([ep(1, [we(undefined)])])])).size === 0)

// ─── getEarnedModes ──────────────────────────────────────────────────────────

console.log('\ngetEarnedModes')

assert('no episodes → empty',
  getEarnedModes(title([])).size === 0)

assert('ep1 watched bw, ep2 not watched → bw not earned',
  !getEarnedModes(title([season([ep(1, [we('bw')]), ep(2, [])])])).has('bw'))

assert('ep1 + ep2 both watched bw → bw earned',
  getEarnedModes(title([season([ep(1, [we('bw')]), ep(2, [we('bw')])])])).has('bw'))

assert('all bw but none color → only bw earned',
  (() => { const s = getEarnedModes(title([season([ep(1, [we('bw')]), ep(2, [we('bw')])])])); return s.has('bw') && !s.has('color') })())

assert('all watched in both modes → both earned',
  (() => { const s = getEarnedModes(title([season([ep(1, [we('bw'), we('color')]), ep(2, [we('bw'), we('color')])])])); return s.has('bw') && s.has('color') })())

assert('multiple seasons: bw earned only when all seasons complete',
  (() => {
    const t = title([
      season([ep(1, [we('bw')])]),
      season([ep(2, [])]),  // ep2 in season 2 not watched
    ])
    return !getEarnedModes(t).has('bw')
  })())

// ─── Result ──────────────────────────────────────────────────────────────────

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
```

- [ ] **Step 2: Run the verify script — expect failure**

```bash
node scripts/verify-episode-modes.mjs
```

Expected: exits with code 1 (functions not defined).

- [ ] **Step 3: Add the functions to `src/store/episodeUtils.ts`**

Append to the end of `src/store/episodeUtils.ts`:

```ts
// ─── Spider-Noir color mode progression ──────────────────────────────────────

import type { Title } from './mockData'

/** Modes with at least one episode watch event recorded in that mode. */
export function getUnlockedModes(title: Title): Set<'bw' | 'color'> {
  const modes = new Set<'bw' | 'color'>()
  for (const season of title.seasons ?? []) {
    for (const ep of season.episodes ?? []) {
      for (const we of ep.watchEvents) {
        if (we.colorMode) modes.add(we.colorMode)
      }
    }
  }
  return modes
}

/** Modes where every episode has at least one watch event in that mode. */
export function getEarnedModes(title: Title): Set<'bw' | 'color'> {
  const allEps = (title.seasons ?? []).flatMap((s) => s.episodes ?? [])
  if (allEps.length === 0) return new Set()
  const earned = new Set<'bw' | 'color'>()
  for (const mode of ['bw', 'color'] as const) {
    if (allEps.every((ep) => ep.watchEvents.some((we) => we.colorMode === mode))) {
      earned.add(mode)
    }
  }
  return earned
}
```

Note: `Title` is already imported transitively via the existing `Season`/`Episode` types in this file — but it is not explicitly imported. Add the import at the top of the file if `Title` isn't already available. The existing file imports `Episode` and `Season` from `./mockData`; add `Title` to that import:

Change the existing first line:
```ts
import type { Episode, Season } from './mockData'
```
to:
```ts
import type { Episode, Season, Title } from './mockData'
```

- [ ] **Step 4: Run the verify script — expect pass**

```bash
node scripts/verify-episode-modes.mjs
```

Expected: all assertions pass, exits with code 0.

- [ ] **Step 5: Type-check**

```bash
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 6: Commit**

```bash
rtk git add src/store/episodeUtils.ts scripts/verify-episode-modes.mjs
rtk git commit -m "feat: add getUnlockedModes and getEarnedModes to episodeUtils"
```

---

### Task 4: Zustand pinnedModes slice

**Files:**
- Modify: `src/store/useAppStore.ts`

**Interfaces:**
- Consumes: `fetchAllTitlePins`, `upsertTitlePin`, `deleteTitlePin` from `src/lib/db.ts` (Task 2)
- Produces:
  - `pinnedModes: Record<string, 'bw' | 'color'>` — key format: `"${titleId}:${easterEggKey}"`
  - `setPinnedMode(titleId: string, easterEggKey: string, variant: 'bw' | 'color' | null): void`
  - `loadPinnedModes(): Promise<void>`

- [ ] **Step 1: Add `PinsSlice` interface**

In `src/store/useAppStore.ts`, add this interface after the `AuthSlice` interface (around line 125):

```ts
interface PinsSlice {
  pinnedModes: Record<string, 'bw' | 'color'>
  setPinnedMode: (titleId: string, easterEggKey: string, variant: 'bw' | 'color' | null) => void
  loadPinnedModes: () => Promise<void>
}
```

- [ ] **Step 2: Add `PinsSlice` to `AppStore` type**

Find the line:
```ts
type AppStore = LibrarySlice & LedgerSlice & UISlice & AuthSlice
```

Replace with:
```ts
type AppStore = LibrarySlice & LedgerSlice & UISlice & AuthSlice & PinsSlice
```

- [ ] **Step 3: Add the three new DB helpers to the import**

Find the existing import from `'../lib/db'` at the top of the file and add the new functions:

```ts
import {
  fetchUserLibrary, fetchSharedLibrary, insertTitleToDb, updateTitleInDb,
  deleteTitleFromDb, logEpisodeToDb, deleteViewingFromDb,
  deleteEpisodeWatchEventFromDb,
  fetchAllTitlePins, upsertTitlePin, deleteTitlePin,
} from '../lib/db'
```

- [ ] **Step 4: Add initial state and actions inside the `create` call**

In the `create<AppStore>()(...(set, get) => ({ ... }))` body, add the following block immediately before the closing of the `AuthSlice` section (after `loadSharedLibrary` but before the closing `}),` of the `persist` callback). Find the line that reads `// ── Auth ───────────────────────────────────────────────────` and add the pins section after the auth section ends:

```ts
  // ── Pins ───────────────────────────────────────────────────
  pinnedModes: {},

  setPinnedMode: (titleId, easterEggKey, variant) => {
    const key = `${titleId}:${easterEggKey}`
    if (variant === null) {
      set((s) => {
        const next = { ...s.pinnedModes }
        delete next[key]
        return { pinnedModes: next }
      })
      const user = get().user
      if (user) {
        deleteTitlePin(user.id, titleId, easterEggKey).catch((e) =>
          console.error('deleteTitlePin failed:', e)
        )
      }
    } else {
      set((s) => ({ pinnedModes: { ...s.pinnedModes, [key]: variant } }))
      const user = get().user
      if (user) {
        upsertTitlePin(user.id, titleId, easterEggKey, variant).catch((e) =>
          console.error('upsertTitlePin failed:', e)
        )
      }
    }
  },

  loadPinnedModes: async () => {
    const user = get().user
    if (!user) return
    const pins = await fetchAllTitlePins(user.id)
    const pinnedModes: Record<string, 'bw' | 'color'> = {}
    for (const pin of pins) {
      pinnedModes[`${pin.titleId}:${pin.easterEggKey}`] = pin.pinnedVariant
    }
    set({ pinnedModes })
  },
```

- [ ] **Step 5: Call `loadPinnedModes` from `setUser`**

Find the existing `setUser` action (around line 612). It currently reads:

```ts
setUser: (user) => {
  set({ user })
  if (user) {
    get().loadUserLibrary()
  } else {
```

Change it to:

```ts
setUser: (user) => {
  set({ user })
  if (user) {
    get().loadUserLibrary()
    get().loadPinnedModes()
  } else {
    set((s) => ({ ...s, pinnedModes: {} }))
```

Also add `pinnedModes: {}` to the logout branch (where mockTitles are restored) — find the object that resets state on logout and add `pinnedModes: {}` to it.

- [ ] **Step 6: Type-check**

```bash
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 7: Commit**

```bash
rtk git add src/store/useAppStore.ts
rtk git commit -m "feat: add pinnedModes slice to Zustand store, load from Supabase on auth"
```

---

### Task 5: SpiderWebOverlay component + CSS keyframes

**Files:**
- Create: `src/components/SpiderWebOverlay.tsx`
- Modify: `src/index.css`

**Interfaces:**
- Produces: `<SpiderWebOverlay mode={'bw' | 'color'} />` — renders animated silk-thread web, auto-removes after ~2000ms via CSS animation (caller controls when to mount/unmount via `noirAnim` state)

- [ ] **Step 1: Add new keyframes to `src/index.css` and remove old `spider-web-cast`**

Find and delete the `@keyframes spider-web-cast` block in `src/index.css`:

```css
/* DELETE THIS ENTIRE BLOCK: */
@keyframes spider-web-cast {
  0%   { opacity: 0;    transform: scale(0.04) rotate(-18deg); }
  18%  { opacity: 0.9;  transform: scale(1)    rotate(3deg);  }
  60%  { opacity: 0.55; transform: scale(1.04) rotate(0deg);  }
  100% { opacity: 0;    transform: scale(1.22) rotate(9deg);  }
}
```

Add these two new keyframes in its place:

```css
/* ── Silk-thread web animation ── */
@keyframes spider-silk-draw {
  from { stroke-dashoffset: var(--dash-len); }
  to   { stroke-dashoffset: 0; }
}

@keyframes spider-silk-fade {
  0%, 70% { opacity: 1; }
  100%    { opacity: 0; }
}
```

- [ ] **Step 2: Create `src/components/SpiderWebOverlay.tsx`**

```tsx
/**
 * Animated silk-thread spider web overlay.
 * 8 spokes shoot outward from screen centre (staggered 35ms each),
 * then 4 concentric rings draw in clockwise (staggered 150ms each).
 * The whole SVG fades out after ~1400ms. Total duration: ~2000ms.
 *
 * Mount this component when a mode transition fires; unmount after 2100ms
 * (a 100ms buffer past the CSS animation end).
 */
export function SpiderWebOverlay({ mode }: { mode: 'bw' | 'color' }) {
  const w = window.innerWidth
  const h = window.innerHeight
  const cx = w / 2
  const cy = h / 2
  // Extend past all four corners so the web covers the full viewport
  const spokeLen = Math.hypot(cx, cy) * 1.05

  const SPOKE_COUNT = 8
  const spokes = Array.from({ length: SPOKE_COUNT }, (_, i) => {
    const angle = (i / SPOKE_COUNT) * 2 * Math.PI - Math.PI / 2
    return {
      x2: cx + spokeLen * Math.cos(angle),
      y2: cy + spokeLen * Math.sin(angle),
      len: spokeLen,
      delay: i * 35,
    }
  })

  // Ring radii as fractions of spokeLen; start after last spoke begins (350ms)
  const ringFractions = [0.18, 0.38, 0.58, 0.78]
  const rings = ringFractions.map((frac, j) => {
    const r = spokeLen * frac
    return { r, circ: 2 * Math.PI * r, delay: 350 + j * 150 }
  })

  const stroke = mode === 'bw' ? 'rgba(255,255,255,0.80)' : 'rgba(233,178,102,0.90)'

  return (
    <svg
      aria-hidden="true"
      style={{
        position: 'fixed',
        inset: 0,
        width: '100vw',
        height: '100vh',
        pointerEvents: 'none',
        zIndex: 9998,
        animationName: 'spider-silk-fade',
        animationDuration: '2000ms',
        animationTimingFunction: 'ease',
        animationFillMode: 'forwards',
        ...(mode === 'color'
          ? { filter: 'drop-shadow(0 0 5px rgba(233,100,40,0.65))' }
          : {}),
      }}
    >
      {spokes.map((s, i) => (
        <line
          key={i}
          x1={cx}
          y1={cy}
          x2={s.x2}
          y2={s.y2}
          stroke={stroke}
          strokeWidth="1"
          style={
            {
              strokeDasharray: s.len,
              strokeDashoffset: s.len,
              '--dash-len': String(s.len),
              animationName: 'spider-silk-draw',
              animationDuration: '220ms',
              animationDelay: `${s.delay}ms`,
              animationTimingFunction: 'ease-out',
              animationFillMode: 'forwards',
            } as React.CSSProperties
          }
        />
      ))}
      {rings.map((r, j) => (
        <circle
          key={j}
          cx={cx}
          cy={cy}
          r={r.r}
          fill="none"
          stroke={stroke}
          strokeWidth="0.8"
          style={
            {
              strokeDasharray: r.circ,
              strokeDashoffset: r.circ,
              '--dash-len': String(r.circ),
              animationName: 'spider-silk-draw',
              animationDuration: '200ms',
              animationDelay: `${r.delay}ms`,
              animationTimingFunction: 'ease-out',
              animationFillMode: 'forwards',
            } as React.CSSProperties
          }
        />
      ))}
    </svg>
  )
}
```

- [ ] **Step 3: Type-check**

```bash
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
rtk git add src/components/SpiderWebOverlay.tsx src/index.css
rtk git commit -m "feat: add SpiderWebOverlay with animated silk-thread spider web"
```

---

### Task 6: SpiderNoirModeSelector component

**Files:**
- Create: `src/components/SpiderNoirModeSelector.tsx`

**Interfaces:**
- Produces: `<SpiderNoirModeSelector unlockedModes earnedModes selected pinned onSelect onTogglePin />`
  - `unlockedModes: Set<'bw' | 'color'>` — from `getUnlockedModes`
  - `earnedModes: Set<'bw' | 'color'>` — from `getEarnedModes`
  - `selected: 'normal' | 'bw' | 'color'`
  - `pinned: 'bw' | 'color' | null`
  - `onSelect: (mode: 'normal' | 'bw' | 'color') => void`
  - `onTogglePin: (mode: 'bw' | 'color') => void`

- [ ] **Step 1: Create `src/components/SpiderNoirModeSelector.tsx`**

```tsx
import { Pin } from 'lucide-react'
import { cn } from 'src/lib/utils'

type ColorMode = 'bw' | 'color'
type SelectorMode = 'normal' | ColorMode

interface SpiderNoirModeSelectorProps {
  unlockedModes: Set<ColorMode>
  earnedModes: Set<ColorMode>
  selected: SelectorMode
  pinned: ColorMode | null
  onSelect: (mode: SelectorMode) => void
  onTogglePin: (mode: ColorMode) => void
}

const SEGMENTS: Array<{ mode: SelectorMode; icon: string; label: string }> = [
  { mode: 'normal', icon: '○', label: 'Normal' },
  { mode: 'bw',     icon: '◐', label: 'B&W'    },
  { mode: 'color',  icon: '◈', label: 'Color'  },
]

export function SpiderNoirModeSelector({
  unlockedModes,
  earnedModes,
  selected,
  pinned,
  onSelect,
  onTogglePin,
}: SpiderNoirModeSelectorProps) {
  if (unlockedModes.size === 0) return null

  const visible = SEGMENTS.filter(
    (s) => s.mode === 'normal' || unlockedModes.has(s.mode as ColorMode)
  )

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '5px' }}>
      <div style={{ display: 'flex', gap: '5px', flexWrap: 'wrap' }}>
        {visible.map(({ mode, icon, label }) => {
          const isActive   = selected === mode
          const isColor    = mode !== 'normal'
          const isEarned   = isColor && earnedModes.has(mode as ColorMode)
          const isPinned   = isColor && pinned === (mode as ColorMode)

          return (
            <div key={mode} style={{ display: 'flex', alignItems: 'center', gap: '2px' }}>
              <button
                type="button"
                onClick={() => onSelect(mode)}
                aria-pressed={isActive}
                className={cn(
                  'flex items-center gap-1.5 rounded-full font-mono transition-all',
                  isActive
                    ? 'bg-amber/15 text-amber'
                    : 'bg-transparent hover:text-paper'
                )}
                style={{
                  fontSize: '11px',
                  padding: '4px 10px',
                  border: `1px solid ${isActive ? 'rgba(233,178,102,0.50)' : 'var(--line)'}`,
                  color: isActive ? 'var(--amber)' : 'var(--paper-faint)',
                  letterSpacing: '0.04em',
                }}
              >
                <span>{icon}</span>
                <span>{label}</span>
              </button>

              {/* Pin button — only when this non-normal segment is active and earned */}
              {isColor && isActive && isEarned && (
                <button
                  type="button"
                  onClick={() => onTogglePin(mode as ColorMode)}
                  aria-label={isPinned ? `Unpin ${label} mode` : `Pin ${label} mode`}
                  title={isPinned ? 'Unpin — filter will reset when you leave' : 'Pin — filter stays on when you leave'}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    width: '22px',
                    height: '22px',
                    borderRadius: '50%',
                    border: `1px solid ${isPinned ? 'rgba(233,178,102,0.50)' : 'var(--line)'}`,
                    background: isPinned ? 'rgba(233,178,102,0.12)' : 'transparent',
                    color: isPinned ? 'var(--amber)' : 'var(--paper-faint)',
                    transition: 'color 0.15s, background 0.15s, border-color 0.15s',
                    cursor: 'pointer',
                  }}
                  onMouseEnter={(e) => {
                    e.currentTarget.style.color = 'var(--amber)'
                    e.currentTarget.style.borderColor = 'rgba(233,178,102,0.5)'
                  }}
                  onMouseLeave={(e) => {
                    e.currentTarget.style.color = isPinned ? 'var(--amber)' : 'var(--paper-faint)'
                    e.currentTarget.style.borderColor = isPinned ? 'rgba(233,178,102,0.5)' : 'var(--line)'
                  }}
                >
                  <Pin
                    className="w-3 h-3"
                    style={{ transform: isPinned ? 'none' : 'rotate(45deg)', transition: 'transform 0.2s' }}
                  />
                </button>
              )}
            </div>
          )
        })}
      </div>

      {pinned && (
        <div
          style={{
            fontFamily: 'var(--mono)',
            fontSize: '10px',
            color: 'var(--paper-faint)',
            letterSpacing: '0.06em',
          }}
        >
          filter stays on when you leave
        </div>
      )}
    </div>
  )
}
```

- [ ] **Step 2: Type-check**

```bash
npx tsc --noEmit
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
rtk git add src/components/SpiderNoirModeSelector.tsx
rtk git commit -m "feat: add SpiderNoirModeSelector segmented control with pin affordance"
```

---

### Task 7: Wire everything into TitleDetailDrawer

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx`

**Interfaces:**
- Consumes:
  - `SpiderWebOverlay` from `src/components/SpiderWebOverlay.tsx` (Task 5)
  - `SpiderNoirModeSelector` from `src/components/SpiderNoirModeSelector.tsx` (Task 6)
  - `getUnlockedModes`, `getEarnedModes` from `src/store/episodeUtils.ts` (Task 3)
  - `pinnedModes`, `setPinnedMode` from `useAppStore` (Task 4)

- [ ] **Step 1: Update imports in `TitleDetailDrawer.tsx`**

Add `useMemo` to the existing React import (do not add a new import line):
```ts
// Change:
import { useState, useEffect, useRef } from 'react'
// To:
import { useState, useEffect, useRef, useMemo } from 'react'
```

Add these new imports after the existing component imports:
```ts
import { SpiderWebOverlay } from 'src/components/SpiderWebOverlay'
import { SpiderNoirModeSelector } from 'src/components/SpiderNoirModeSelector'
import { getUnlockedModes, getEarnedModes } from 'src/store/episodeUtils'
```

Remove the two SVG string constants (`SPIDER_WEB_SVG_BW` and `SPIDER_WEB_SVG_COLOR`) — the lines starting with `const SPIDER_WEB_SVG_BW = encodeURIComponent(` and `const SPIDER_WEB_SVG_COLOR = encodeURIComponent(`.

- [ ] **Step 2: Add mode selector type alias near the top of the file**

After the `SPIDER_NOIR_TMDB_ID` constant, add:

```ts
type SelectorMode = 'normal' | 'bw' | 'color'
const EASTER_EGG_KEY = 'spider_noir_color'
```

- [ ] **Step 3: Update the main `TitleDetailDrawer` component — read pinned mode from store**

Inside `TitleDetailDrawer`, after the existing `const isSpiderNoir = ...` line, add:

```ts
const pinnedModeRaw = useAppStore((s) =>
  title ? (s.pinnedModes[`${title.id}:${EASTER_EGG_KEY}`] ?? null) : null
) as 'bw' | 'color' | null
const setPinnedMode = useAppStore((s) => s.setPinnedMode)

const unlockedModes = useMemo(
  () => (isSpiderNoir && title ? getUnlockedModes(title) : new Set<'bw' | 'color'>()),
  // eslint-disable-next-line react-hooks/exhaustive-deps
  [isSpiderNoir, title]
)
const earnedModes = useMemo(
  () => (isSpiderNoir && title ? getEarnedModes(title) : new Set<'bw' | 'color'>()),
  // eslint-disable-next-line react-hooks/exhaustive-deps
  [isSpiderNoir, title]
)
```

- [ ] **Step 4: Remove the `activeSpiderNoirMode` derived variable and add `manualMode` state**

Find and **remove only this one line** (the `getSpiderNoirActiveMode` function itself stays — it's still used in Step 5):
```ts
const activeSpiderNoirMode = isSpiderNoir && title ? getSpiderNoirActiveMode(title) : null
```

Keep `prevNoirModeRef`, `noirAnim`, and `noirAnimTimerRef` declarations — they're still used. Add a `manualMode` state below the existing `noirAnim` state:

```ts
const [manualMode, setManualMode] = useState<SelectorMode>('normal')
```

- [ ] **Step 5: Add effect to initialise `manualMode` when drawer opens for Spider-Noir**

Add this effect after the existing noirAnim declarations:

```ts
// Seed manualMode from pinned → last watch event → normal when the drawer opens
useEffect(() => {
  if (!isSpiderNoir || !title || !isDetailDrawerOpen) return
  const derived = getSpiderNoirActiveMode(title)
  setManualMode(pinnedModeRaw ?? derived ?? 'normal')
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, [title?.id, isDetailDrawerOpen])
```

- [ ] **Step 6: Derive `effectiveNoirMode` and refactor the body-class effect**

Add this derived value after the `manualMode` state:

```ts
// While drawer is open: use manual selection. When closed: use pinned (if any).
const effectiveNoirMode: 'bw' | 'color' | null = isDetailDrawerOpen && isSpiderNoir
  ? (manualMode !== 'normal' ? (manualMode as 'bw' | 'color') : null)
  : (isSpiderNoir ? pinnedModeRaw : null)
```

Now **replace** the existing large `useEffect` that watches `[isSpiderNoir, activeSpiderNoirMode]` with this refactored version that watches `[effectiveNoirMode]`:

```ts
useEffect(() => {
  const ALL = ['spider-noir-bw', 'spider-noir-color', 'spider-noir-bw-enter', 'spider-noir-color-enter'] as const
  const prevMode = prevNoirModeRef.current
  const isVisualChange = prevMode !== undefined && prevMode !== effectiveNoirMode

  function applyClasses() {
    document.body.classList.remove(...ALL)
    if (effectiveNoirMode) {
      document.body.classList.add(effectiveNoirMode === 'bw' ? 'spider-noir-bw' : 'spider-noir-color')
    }
  }

  if (isVisualChange) {
    transitionSpiderNoir(applyClasses)
    if (noirAnimTimerRef.current) clearTimeout(noirAnimTimerRef.current)
    if (effectiveNoirMode) {
      noirAnimTimerRef.current = setTimeout(() => {
        setNoirAnim(effectiveNoirMode)
        noirAnimTimerRef.current = setTimeout(() => setNoirAnim(null), 2100)
      }, 0)
    } else {
      noirAnimTimerRef.current = setTimeout(() => setNoirAnim(null), 0)
    }
  } else {
    applyClasses()
  }

  prevNoirModeRef.current = effectiveNoirMode

  return () => {
    if (noirAnimTimerRef.current) clearTimeout(noirAnimTimerRef.current)
  }
}, [effectiveNoirMode]) // eslint-disable-line react-hooks/exhaustive-deps
```

- [ ] **Step 7: Add handler functions for the selector**

Add inside `TitleDetailDrawer`, before the `return` statement:

```ts
function handleModeSelect(mode: SelectorMode) {
  setManualMode(mode)
}

function handleTogglePin(mode: 'bw' | 'color') {
  if (!title) return
  const newVariant = pinnedModeRaw === mode ? null : mode
  setPinnedMode(title.id, EASTER_EGG_KEY, newVariant)
}
```

- [ ] **Step 8: Replace the static `noirAnim` div with `SpiderWebOverlay`**

Find the JSX block:
```tsx
{noirAnim && (
  <div
    aria-hidden="true"
    style={{
      position: 'fixed',
      inset: 0,
      zIndex: 9998,
      pointerEvents: 'none',
      backgroundImage: `url("data:image/svg+xml,${noirAnim === 'bw' ? SPIDER_WEB_SVG_BW : SPIDER_WEB_SVG_COLOR}")`,
      backgroundSize: 'min(80vw, 80vh)',
      backgroundRepeat: 'no-repeat',
      backgroundPosition: 'center',
      animation: 'spider-web-cast 2100ms ease forwards',
    }}
  />
)}
```

Replace it with:
```tsx
{noirAnim && <SpiderWebOverlay mode={noirAnim} />}
```

- [ ] **Step 9: Add `SpiderNoirModeSelector` to the hero section**

Find the hero section's metadata area — the `<div className="flex flex-wrap items-center gap-x-3 gap-y-1">` that contains year, director, and runtime. Add the selector immediately after that div (before `<StarRating ...>`):

```tsx
{isSpiderNoir && (
  <SpiderNoirModeSelector
    unlockedModes={unlockedModes}
    earnedModes={earnedModes}
    selected={manualMode}
    pinned={pinnedModeRaw}
    onSelect={handleModeSelect}
    onTogglePin={handleTogglePin}
  />
)}
```

- [ ] **Step 10: Lint and type-check**

```bash
npx tsc --noEmit && npm run lint
```

Expected: no errors. Fix any lint warnings about exhaustive-deps using the eslint-disable comments already in the existing codebase (`// eslint-disable-line react-hooks/exhaustive-deps`).

- [ ] **Step 11: Build**

```bash
npm run build
```

Expected: successful build with no errors.

- [ ] **Step 12: Commit**

```bash
rtk git add src/components/TitleDetailDrawer.tsx
rtk git commit -m "feat: wire SpiderNoirModeSelector and SpiderWebOverlay into TitleDetailDrawer"
```
