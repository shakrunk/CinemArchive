# Spider-Noir Color Mode Progression — Design Spec

**Date:** 2026-06-26
**Status:** Approved

---

## Overview

Three additions to the Spider-Man: Noir easter egg system:

1. **Mode selector** inside the title drawer — a segmented control that grows as you watch episodes in different color modes, letting you manually switch the active filter.
2. **Pin mechanic** — finishing every episode in a given mode earns the ability to pin that filter so it persists even after the drawer closes, synced across devices via Supabase.
3. **Animated silk-thread web** — replaces the existing static SVG overlay with an inline SVG where individual spokes shoot outward from centre and concentric rings draw in after them.

This is designed as the first instance of a general "easter egg pinning" system (`user_title_pins`) that future title-specific themes can reuse without schema changes.

---

## Section 1 — Data Model

### New migration: `supabase/migrations/20260626000001_user_title_pins.sql`

```sql
create table user_title_pins (
  user_id        uuid not null references auth.users on delete cascade,
  title_id       uuid not null references titles(id) on delete cascade,
  easter_egg_key text not null,
  pinned_variant text check (pinned_variant in ('bw', 'color')),
  updated_at     timestamptz not null default now(),
  primary key (user_id, title_id, easter_egg_key)
);

alter table user_title_pins enable row level security;

create policy "Users manage their own pins"
  on user_title_pins for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);
```

One row per `(user, title, easter_egg_key)`. The key for Spider-Noir is `'spider_noir_color'`. `pinned_variant = null` means the row has been deleted (we upsert/delete rather than toggling null). RLS ensures users only touch their own rows.

### `schema.sql` sync

Add the same table definition to keep `schema.sql` current.

### Client-side computed progression (no new columns needed)

Two pure functions over already-loaded episode data, added to `src/store/episodeUtils.ts`:

```ts
/** Modes with at least one watch event logged in that mode. */
export function getUnlockedModes(title: Title): Set<'bw' | 'color'>

/** Modes where every episode has at least one watch event in that mode. */
export function getEarnedModes(title: Title): Set<'bw' | 'color'>
```

`getEarnedModes` counts only episodes that exist in the DB (those with an `id`). An episode with zero watch events means the mode is not fully earned.

### Zustand slice additions (`src/store/useAppStore.ts`)

```ts
pinnedModes: Record<string, 'bw' | 'color'>  // key: `${titleId}:${easterEggKey}`
setPinnedMode: (titleId: string, easterEggKey: string, variant: 'bw' | 'color' | null) => void
loadPinnedModes: () => Promise<void>
```

`loadPinnedModes` runs once on app init alongside the library load — one query: `select * from user_title_pins where user_id = $uid`. `setPinnedMode` with `variant = null` deletes the row and removes the key from the store. Writes are optimistic (store first, then DB).

### DB helpers (`src/lib/db.ts`)

```ts
export async function upsertTitlePin(
  userId: string, titleId: string, easterEggKey: string, pinnedVariant: 'bw' | 'color'
): Promise<void>

export async function deleteTitlePin(
  userId: string, titleId: string, easterEggKey: string
): Promise<void>

export async function fetchAllTitlePins(
  userId: string
): Promise<Array<{ titleId: string; easterEggKey: string; pinnedVariant: 'bw' | 'color' }>>
```

---

## Section 2 — Mode Selector UI

### Component: `SpiderNoirModeSelector`

A compact segmented control rendered inside `TitleDetailDrawer`'s hero section, just below the year/director metadata line, only when `isSpiderNoir && unlockedModes.size > 0`.

**Segment states:**

| Unlocked modes | Segments shown |
|---|---|
| none | *(selector hidden)* |
| `{'bw'}` | Normal · ◐ B&W |
| `{'color'}` | Normal · ◈ Color |
| `{'bw', 'color'}` | Normal · ◐ B&W · ◈ Color |

Normal is always the first option and is always available (it means "no filter").

**Pin affordance:**

When a non-Normal segment is selected **and** that mode is earned, a small pin icon appears inside the segment label. Clicking the pin icon toggles pinned state. When pinned: the icon renders in amber and a micro-label below the selector reads *"Filter stays on when you leave"*. Only one mode can be pinned at a time; pinning a new mode unpins the previous one.

**Interaction:**

- Switching segments fires the animated silk thread effect (Section 3) + body class change via `transitionSpiderNoir`.
- The selected segment is React-local state within `TitleDetailDrawer`.
- On drawer open: initial selection = pinned mode (if set) → else derived from last watch event → else Normal.
- On drawer close: if a mode is pinned, leave `spider-noir-bw` / `spider-noir-color` on `document.body`; otherwise remove (existing cleanup behaviour).

**Visual design:**

Small pill row with 8px gap. Each segment is a `<button>` — active segment has `background: amber/15, border: 1px solid amber/50, color: amber`; inactive has subtle border and muted text. The segment width is consistent so the row doesn't jump when a pin icon appears. Pin icon: `📌` or a Lucide `Pin` at 10px — amber when active, paper-faint otherwise.

---

## Section 3 — Animated Silk Thread Web

### Component: `SpiderWebOverlay`

Replaces the current background-image `div` in `TitleDetailDrawer`. Renders an inline SVG centred on the viewport, pointer-events none, `position: fixed`, `z-index: 9998`.

**SVG structure** (viewBox centred at 0,0 for easy math):

- 8 `<line>` elements from `(0,0)` outward at 45° increments, each reaching the viewport diagonal.
- 4 `<circle>` elements at radii `r = 18%, 38%, 58%, 78%` of the viewport half-diagonal.

**Animation sequence:**

1. **Spokes shoot out** (0–400ms): Each spoke uses `stroke-dasharray = spokeLength` and `stroke-dashoffset` animating from `spokeLength` → `0` via `@keyframes spider-silk-draw`. Animation duration 220ms, stagger `i * 35ms` (spoke 0 starts at 0ms, spoke 7 at 245ms).

2. **Rings draw in** (350ms–950ms): Each ring uses `stroke-dasharray = circumference` and `stroke-dashoffset` from `circumference` → `0`. Duration 200ms, stagger `j * 150ms` offset from 350ms (ring 0 at 350ms, ring 3 at 800ms).

3. **Hold** (950ms–1350ms): Web is fully drawn, sits at peak opacity.

4. **Dissolve** (1350ms–2000ms): The entire SVG fades out via `@keyframes spider-silk-fade` on the `<svg>` element (650ms, ease-in).

Total: ~2000ms (down from the current 2100ms, but feels longer because of the sequential build-up).

**Styling per mode:**

| Mode | Stroke colour | Extra |
|---|---|---|
| B&W | `rgba(255,255,255,0.80)` | none |
| Color | `rgba(233,178,102,0.90)` | `filter: drop-shadow(0 0 5px rgba(233,100,40,0.65))` on SVG |

Stroke width: 1px for spokes, 0.8px for rings.

Size: SVG fills the viewport (`width="100vw" height="100vh"`), the geometry is calculated to reach the corners.

**CSS keyframes added to `index.css`:**

```css
@keyframes spider-silk-draw {
  from { stroke-dashoffset: var(--dash-len); }
  to   { stroke-dashoffset: 0; }
}

@keyframes spider-silk-fade {
  0%, 70% { opacity: 1; }
  100%    { opacity: 0; }
}
```

Each `<line>` / `<circle>` receives inline styles: `strokeDasharray`, `strokeDashoffset` (initial value = dash length), `animationDelay`, `animationDuration`, `animationFillMode: 'forwards'`. The `<svg>` itself gets the fade animation with `animationDelay` matching the hold start.

Remove the old `spider-web-cast` keyframe and the `SPIDER_WEB_SVG_BW` / `SPIDER_WEB_SVG_COLOR` SVG string constants from `TitleDetailDrawer.tsx`.

---

## Section 4 — Active Mode Resolution & Persistence

### Mode resolution order (in `TitleDetailDrawer`)

```
pinnedMode (from Zustand) → manualSelection (local state) → derivedFromLastEvent → null
```

`manualSelection` resets when the drawer closes (unless the selection was pinned, in which case it becomes the `pinnedMode` and persists).

### Body class lifecycle

- **On drawer open**: apply body class for resolved mode (existing behaviour).
- **While drawer open**: `manualSelection` changes immediately trigger `transitionSpiderNoir`.
- **On drawer close**: if `pinnedMode` is set, leave the body class on. Otherwise remove it.

The existing `useEffect` that manages body classes is refactored to read from the resolved mode (pinned or manual) rather than `getSpiderNoirActiveMode(title)`. The auto-derive (`getSpiderNoirActiveMode`) is only used to seed the initial `manualSelection` state when the drawer first opens without a pin.

---

## Files Touched

| File | Change |
|---|---|
| `supabase/migrations/20260626000001_user_title_pins.sql` | New migration |
| `schema.sql` | Add `user_title_pins` table + RLS |
| `src/store/useAppStore.ts` | `pinnedModes` slice, `setPinnedMode`, `loadPinnedModes` |
| `src/lib/db.ts` | `upsertTitlePin`, `deleteTitlePin`, `fetchAllTitlePins` |
| `src/store/episodeUtils.ts` | `getUnlockedModes`, `getEarnedModes` |
| `src/components/SpiderWebOverlay.tsx` | New animated silk-thread component (replaces static SVG div) |
| `src/components/SpiderNoirModeSelector.tsx` | New segmented control + pin affordance |
| `src/components/TitleDetailDrawer.tsx` | Wire selector, replace `noirAnim` div, update mode resolution + cleanup |
| `src/index.css` | Add `spider-silk-draw`, `spider-silk-fade`; remove `spider-web-cast` |

---

## Non-Goals

- No UI for browsing earned easter eggs across the library (future "achievements" view).
- No retroactive backfill of `getEarnedModes` — the computation is purely from client-loaded watch event data; it updates naturally as episodes are logged.
- No animation for the pin action itself (just an immediate state change with the existing body class handling).
- The `SpiderNoirModeSelector` is specific to Spider-Noir; the `user_title_pins` table is general but future easter eggs will implement their own selector components.
