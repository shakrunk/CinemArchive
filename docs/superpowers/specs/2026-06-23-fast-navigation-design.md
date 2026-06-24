# Fast Navigation — URL routing + Command palette

**Date:** 2026-06-23
**Status:** Approved (direction chosen by user: deep-linking/back-button + command palette)

## Problem

CinemArchive records and browses what you watch very well, but *getting around* it has two friction points:

1. **No URL state / broken back button.** The active view (`upnext`/`library`/`ledger`) lives in React state and the detail drawer lives in the Zustand store. Nothing is reflected in the URL. Consequences:
   - A refresh always drops you back on the smart-landing view and closes any open title.
   - On mobile, the system back gesture/button exits the whole app (or navigates away) instead of closing the open detail drawer — the single most common "trap" in the app.
   - You cannot link to a specific title.
2. **No fast access.** Reaching a specific title means switching to Library, typing in the search box, and clicking. There is no keyboard-first "jump to anything" affordance, and no quick way to fire common actions (add a title, switch view).

## Goal

Make every destination in the app addressable and instantly reachable:

- **Part A — URL routing & back button:** the active view and the open title become URL query state. Refresh restores them; the back button closes the open drawer/add modal instead of leaving the app; a title is linkable.
- **Part B — Command palette (⌘K / Ctrl+K):** a keyboard-first overlay to search titles and jump to them, and to run common actions (add title, switch view, toggle layout).

These compose: selecting a title in the palette opens the drawer, which pushes a history entry, so back closes it.

## Non-goals (YAGNI)

- No real path-based routing or a router library. Vite `base` is `/CinemArchive/` and SPA fallback is a `404.html` redirect; query params are already used (`?share=`). We stay with query params.
- No fuzzy-search library. A small, predictable scored substring match is enough.
- The nested **Refresh metadata** modal stays plain state (it lives inside the drawer and closes with it). The **profile** modal stays plain state (transient, not worth linking).
- No persistence of palette history / recent commands.

---

## Part A — URL routing & back button

### URL shape

A single canonical serialization, always preserving an existing `share` token:

```
?view=library
?view=library&title=<id>
?view=upnext&add=1
?share=<token>&view=ledger&title=<id>
```

- `view` — `upnext | library | ledger`. Omitted value / unknown → falls back to smart landing on first load only.
- `title` — id of the open detail drawer. Present ⟺ drawer open.
- `add` — `1` when the Add-Title modal is open. Present ⟺ add modal open.
- `share` — existing read-only token; never dropped by navigation writes.

### Pure module: `src/lib/navigation.ts`

Framework-free, unit-testable:

```ts
export type AppView = 'upnext' | 'library' | 'ledger'
export interface NavState { view: AppView; title: string | null; add: boolean }

// Parse a query string (location.search) into NavState. `fallbackView` is used
// when `view` is absent/invalid. Unknown params are ignored; `share` is read
// separately and never part of NavState.
export function parseNav(search: string, fallbackView: AppView): NavState

// Serialize NavState back into a query string, merging in any params that must
// be preserved (i.e. `share`). Omits title/add when not set. Deterministic key
// order so equality checks are stable.
export function serializeNav(nav: NavState, preserved: Record<string, string>): string

// Convenience: read the `share` token (or other preserved params) from a search string.
export function preservedParams(search: string): Record<string, string>
```

Rules encoded (and covered by `scripts/verify-navigation-logic.mjs`):
- `parseNav('', 'library')` → `{view:'library', title:null, add:false}`.
- Unknown `view` falls back: `parseNav('?view=bogus', 'upnext').view === 'upnext'`.
- `title` round-trips; absent → `null`.
- `add=1` → `true`; any other/absent → `false`.
- `serializeNav` preserves `share`: `serializeNav({view:'ledger',title:null,add:false}, {share:'tok'})` contains both `share=tok` and `view=ledger`.
- `parseNav(serializeNav(n, p)...)` is a round-trip identity for `n` (ignoring preserved).
- Deterministic ordering: same input → byte-identical output (no spurious history writes).

### React glue: `useNavigationSync()` hook (in `src/lib/useNavigationSync.ts`)

Mounted once in `App`. It bridges URL ⟷ (App `currentView` + store drawer/add flags) without feedback loops.

Inputs/outputs it touches:
- App owns `currentView` (lifted, initialized from `parseNav(location.search, smartLanding())`).
- Store owns `isDetailDrawerOpen`/`selectedTitleId` (drawer) and `isAddTitleOpen` (add).

Mechanics:
- **Initial mount:** read URL. If `title` present and that id exists in `titles`, call `openDetailDrawer(title)`. If `add` present, `openAddTitle()`. Reflect via `replaceState` to normalize the URL.
- **State → URL (effect):** compute desired `NavState` from current `{currentView, drawer, add}`. Compare against `parseNav(location.search…)`. If different, write:
  - **push** when a modal transitions *closed → open* (so back closes it): drawer opened or add opened.
  - **replace** otherwise (view switches, modal closes, normalization). View switches also clear `title`/`add`.
  - Previous values tracked in a ref to classify the transition.
- **URL → State (`popstate` listener):** parse URL and reconcile: set `currentView`; if `title` absent but drawer open → `closeDetailDrawer()`; if present and drawer closed (and id exists) → `openDetailDrawer`; same for `add`. This is what makes the back button close the drawer.

Guard against loops: the effect only writes when the serialized URL actually differs; `popstate` only sets state (never writes URL).

### Touch points

- `src/App.tsx` — lift `currentView` init from URL; mount `useNavigationSync`; pass `currentView`/`setCurrentView` as today.
- Store: `openDetailDrawer`/`closeDetailDrawer`/`openAddTitle`/`closeAddTitle` are unchanged (the hook observes them). No `window.history` calls inside the store (keeps it pure).
- Closing the drawer via its own UI keeps working: the effect sees drawer closed and `replaceState`s `title` out; pressing back afterward is a no-op.

### Edge cases

- Title id in URL not found (deleted, or a shared link to a different library): ignore `title`, normalize URL — no drawer, no crash.
- `share` token present: every write preserves it so shared sessions keep working and become linkable to a specific title.
- Rapid view switches: each is a `replace`, so history isn't polluted with tab churn.

---

## Part B — Command palette (⌘K)

### Trigger & open state

- New store UI flags: `isCommandPaletteOpen`, `openCommandPalette()`, `closeCommandPalette()`.
- Global hotkey (listener in `App` or the palette): **⌘K** (mac) / **Ctrl+K** — toggles open. `Esc` closes (Radix handles this when built on `DialogPrimitive`). Ignore the hotkey when focus is already in a text input *other than* the palette's own input, so typing `k` in the search box is unaffected (⌘/Ctrl+K still works everywhere).
- Discoverable trigger in `TopBar`: a search-styled button showing a `⌘K` hint (desktop), and a search icon button on mobile.

### Component: `src/components/CommandPalette.tsx`

Built on `@radix-ui/react-dialog` (same primitive as `CinemaModal`) for focus trap + Escape + portal, but styled as a top-anchored palette (not the centered cinema sheet). Internals:
- Text input (autofocused on open).
- A flat, keyboard-navigable result list: ↑/↓ move the active index (with wraparound), `Enter` runs the active item, `Esc` closes, mouse hover sets active.
- Sectioned rendering: **Titles** (dynamic) and **Actions** (static), but a single linear index for keyboard nav.
- Empty state when nothing matches.
- Resets query + active index on close.

### Command model & ranking: `src/store/commands.ts` (pure)

```ts
export interface Command {
  id: string
  kind: 'title' | 'action'
  label: string
  hint?: string          // e.g. director · year, or 'view'
  keywords?: string      // extra match text (genres, 'settings', etc.)
}

// Score a single candidate against a lowercased query. Returns -1 for no match.
// Tiers (higher is better): exact > prefix > word-boundary > substring; ties
// broken by shorter label. Empty query → 0 for all (keep input order).
export function scoreCommand(cmd: Command, query: string): number

// Filter + sort. Title commands are built by the caller from store titles;
// action commands are passed in. Returns ranked, capped list.
export function rankCommands(commands: Command[], query: string, limit?: number): Command[]
```

Covered by `scripts/verify-command-logic.mjs`:
- Empty query keeps input order and returns up to `limit`.
- Prefix beats mid-substring: query `bla` ranks "Blade Runner" above "Border**bla**…"-style mid matches.
- Word-boundary (`run`) matches "Blade **Run**ner".
- `keywords` participate (genre `noir` finds a title tagged noir) but rank below a label match.
- No-match → excluded (score `-1`).
- Deterministic ordering for equal scores (shorter label, then id).

### Wiring (in `App`, where both view-setter and store live)

Build the command list each render:
- **Title commands** from `titles`: `label=title`, `hint=`​`${director ?? type} · ${year}`, `keywords=genres.join(' ')`. Action on select → `openDetailDrawer(id)` + close palette (drawer push gives back-button close via Part A).
- **Action commands** (static): Add a title; Go to Up Next / Library / Ledger; Toggle poster/list layout (library); Open profile. Each maps to an existing handler.
- Selection dispatch: a `Map<id, () => void>` built alongside the command list, or each command carries a `run` closure (kept out of the pure ranking type — ranking only needs label/keywords; the component pairs ranked ids back to handlers).

### Touch points

- `src/store/useAppStore.ts` — add palette flags/actions to UI slice.
- `src/components/TopBar.tsx` — add the ⌘K trigger button (desktop hint + mobile icon).
- `src/App.tsx` — global hotkey listener, build commands, render `<CommandPalette />`.
- `src/index.css` — palette styling (top-anchored content variant), reusing existing tokens.

---

## Testing & verification

Following the repo convention (no test runner; standalone Node mirrors):

- `scripts/verify-navigation-logic.mjs` — mirrors `parseNav`/`serializeNav` round-trips, fallback, `share` preservation, determinism.
- `scripts/verify-command-logic.mjs` — mirrors `scoreCommand`/`rankCommands` tiers and ordering.
- `npm run build` (tsc + vite) and `npm run lint` must pass.
- Manual smoke (documented in the plan): open title → back closes it; refresh restores view+title; ⌘K → type → Enter opens title; Esc closes; mobile back closes drawer.

## Build order

1. `navigation.ts` + its verify script (pure, no UI risk).
2. `useNavigationSync` + lift `currentView` in `App`; wire drawer/add/view to URL. Manual back-button check.
3. `commands.ts` + its verify script.
4. Store palette flags; `CommandPalette.tsx`; hotkey; TopBar trigger; CSS.
5. Build, lint, run both verify scripts; smoke test.

Each step is independently shippable; Part A delivers value even if Part B slips.
