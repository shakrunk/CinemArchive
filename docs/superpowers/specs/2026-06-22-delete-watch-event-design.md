# Delete Watch Event — Design Spec

**Date:** 2026-06-22  
**Status:** Approved (user: "just go ahead")

---

## Problem

Users have no way to remove an accidentally-logged watch event (movie viewing or TV episode watch event). The only existing destructive action is removing an entire title, which uses `window.confirm`.

---

## Scope

Two surfaces need delete support:

| Surface | Type | Location |
|---|---|---|
| Movie viewings | `Viewing` | `ViewingTimeline` in `TitleDetailDrawer` |
| TV episode watch events | `EpisodeWatchEvent` | `EpisodePanel` "Watched" column |

Delete is **hidden in shared view** (consistent with all other write actions).

---

## Friction Pattern: Inline Two-Click Confirm

Each watch event entry gets a small trash icon. Clicking it does **not** fire the delete immediately — instead it transforms that entry inline into a compact confirmation bar:

```
Jun 12, 2025   [Delete forever]  [Cancel]
```

- Only one entry can be in pending-delete state at a time
- Clicking trash on a second entry dismisses the first
- Pressing Escape or clicking Cancel dismisses
- "Delete forever" fires the delete

This is the right level of friction: requires two deliberate clicks, doesn't interrupt the user with a modal for a small action, fits in tight layouts.

---

## Architecture

### Store (`src/store/useAppStore.ts`)

Two new actions added to `LibrarySlice`:

```typescript
removeViewing: (titleId: string, viewingId: string) => void
deleteEpisodeWatchEvent: (titleId: string, seasonNumber: number, episodeNumber: number, watchEventId: string) => void
```

Both are **optimistic**: update Zustand state immediately, then fire async DB call (fire-and-forget with error logging, matching all existing patterns).

`removeViewing` also recalculates stats and filteredTitles (same as other mutating actions).

`deleteEpisodeWatchEvent` must also recompute `season.episodesWatched` for the affected season (currently set to `episodes.filter(e => e.watchEvents.length > 0).length`).

### DB (`src/lib/db.ts`)

Two new exported functions:

```typescript
export async function deleteViewingFromDb(userId: string, viewingId: string): Promise<void>
export async function deleteEpisodeWatchEventFromDb(userId: string, watchEventId: string): Promise<void>
```

Both are simple `DELETE ... WHERE id = ? AND user_id = ?` calls (RLS enforces user ownership, but we filter by user_id as a belt-and-suspenders defense). No migration needed — existing tables.

### UI (`src/components/TitleDetailDrawer.tsx`)

**`ViewingTimeline`** — receives two new optional props:
- `onDeleteViewing?: (viewingId: string) => void`
- `pendingDeleteId?: string | null` (lifted up so a single item is pending at a time)
- `onPendingDeleteChange?: (id: string | null) => void`

Actually simpler: manage `pendingDeleteId` state locally inside `ViewingTimeline` since it's self-contained and doesn't need coordination with episode watch events.

**`EpisodePanel`** — manages its own `pendingDeleteWatchEventId` local state. Adds a `deleteEpisodeWatchEvent` call from the store.

---

## Visual Design

- Trash icon: `Trash2` from lucide-react (already imported)
- Icon color: `var(--paper-faint)` at rest, `var(--ember)` on hover
- Confirm bar replaces the entry row:  
  - `[Delete forever]` button: `var(--ember)` text, small, font-mono  
  - `[Cancel]` button: `var(--paper-faint)` text  
- Transition: instant (no animation — consistent with the rest of the app's interaction style)

---

## Edge Cases

- **Last watch event for an episode:** deleting it sets `episodesWatched` for that season down by 1. The Eye icon on the episode row disappears. No further cascading effects.
- **Last viewing for a movie:** the ViewingTimeline shows the "No viewings logged yet" empty state. The `viewings.length` stat card goes to 0. The `status` field is NOT auto-changed (user may have set it to 'watched' intentionally and can update it manually).
- **Shared view:** trash icons are not rendered (gated by `isSharedView` prop, consistent with the log form gate).

---

## What's NOT in scope

- Deleting episode ratings or reviews (separate concern, not requested)
- Undo/redo
- Bulk delete
