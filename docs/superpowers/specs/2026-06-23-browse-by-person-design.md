# Browse by Person (Cast & Crew)

**Date:** 2026-06-23
**Status:** Approved

## Overview

Finish the "browse by actor / writer" capability that the extended-metadata feature
(`2026-06-21-extended-metadata-design.md`) deliberately laid the data foundation for but
never surfaced. The normalized `title_cast` / `title_crew` / `season_cast` / `episode_crew`
tables already store `tmdb_person_id` + names precisely so titles can be grouped by person.
This adds the missing UI + client-side filter: click a name anywhere cast/crew is shown and
the Library filters to every title that person appears in.

No schema, migration, Edge Function, or `db.ts` changes are required — the data already
round-trips into the Zustand store. This is a pure frontend feature.

## User Flow

1. In the title detail drawer's **Cast & Crew** section, every cast avatar and crew name is
   a button. The **Season Cast** chips (TV) are buttons too.
2. Clicking a person:
   - sets an active **person filter** keyed by `tmdbPersonId`,
   - closes the drawer,
   - switches the active view to **Library**.
3. The Library shows a dismissible banner — *"Featuring {name}"* with a count and an ✕ —
   above the results. Clearing it (✕ or the filter panel's *Clear filters*) removes the filter.
4. A title matches the person filter if that `tmdbPersonId` appears in **any** of:
   `title.cast`, `title.crew`, `season.cast`, or `episode.crew`.

## Architecture

### `src/store/useAppStore.ts`
- `export type PersonRef = { id: number; name: string }`.
- `LibraryFilters` gains `person: PersonRef | null` (default `null`). Backward compatible:
  old persisted filters lack the key → `undefined` → treated as "no filter". No persist
  version bump needed.
- `export function titleHasPerson(title, personId): boolean` — pure predicate scanning
  cast/crew/season-cast/episode-crew for the id.
- `applyFiltersToTitles` gains a person clause using `titleHasPerson`.
- `UISlice` gains `pendingView: AppView | null` + `requestView(v)` — lets non-`App`
  components request a top-level view change (the active view lives in `App` local state +
  URL, not the store).
- `browseByPerson(person: PersonRef)` — atomic action: set `filters.person`, recompute
  `filteredTitles`, close the detail drawer, and set `pendingView = 'library'`.

### `src/App.tsx`
- An effect watches `pendingView`; when set, calls `setCurrentView(pendingView)` and clears it.

### `src/views/Library.tsx`
- Active-person banner with clear button when `filters.person` is set.
- `person` counts toward `activeFilterCount`.

### `src/components/TitleDetailDrawer.tsx`
- Cast avatars, crew row names, and season-cast chips become buttons calling
  `browseByPerson({ id: member.tmdbPersonId, name: member.name })`.

## Scope / YAGNI

- **In:** title cast, title crew, season cast → clickable + filterable.
- **Out (v1):** episode-level director/writer lines (rendered as plain strings without ids)
  and the hero `dir.` line stay non-clickable. `episode.crew` ids still count toward matches,
  so episode-only credits are reachable via the title's own credits.
- No "person page", no cross-person AND, no URL persistence of the person filter.

## Testing

`scripts/verify-person-logic.mjs` mirrors `titleHasPerson` + the filter clause (repo has no
test runner; pure logic is mirrored in verify scripts). Plus `tsc`, `npm run lint`,
`npm run build`.
