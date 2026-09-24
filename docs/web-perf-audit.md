# Web App Performance Audit

Static audit of `apps/web` (build output + source read) as of `v1.12.0`. No functional or
visual change implied by any item below — these are all "same behavior, less work" fixes.
Ranked by expected impact on load time / interaction smoothness for a real library (hundreds
of titles, seasons, episodes), not on the tiny dev fixture data.

## Summary

The app ships one 578&nbsp;KB (153&nbsp;KB gzip) JS chunk containing every view, every modal, and
the command palette. Poster and backdrop images are requested at fixed TMDB sizes regardless
of how large they're actually drawn on screen. The library also fetches every nested relation
(cast, crew, seasons, episodes, per-episode watch events/ratings/reviews) for every title on
every load — but that turns out to be load-bearing for the default sort and for filters/search
that can match any title, not dead weight scoped to a detail view (see `#2`), so it's tracked
here rather than "fixed" in this pass. The image and bundle-size items (`#1`, `#3`, `#4`, `#5`)
plus the search-input fix (`#8`) are where the actual wins in this pass are.

## Findings

### 1. No code splitting — everything ships in one bundle

`apps/web/src/App.tsx` statically imports all seven views (`UpNext`, `Library`, `Ledger`,
`Discover`, `Lists`, `Profile`, `Friends`) and every modal/sheet (`AddTitleWorkflow`,
`TitleDetailDrawer`, `RefreshMetadataModal`, `OutingScheduleSheet`, `PostShowSheet`,
`AppCommandPalette`, `KeyboardShortcutsHelp`), whether or not the current view or an open
modal needs them. `vite.config.ts` only manually chunks `react` and `@radix-ui`; everything
else — all app code — lands in one `index-*.js`:

```
dist/assets/index-CGl1U_0z.js   578.24 kB │ gzip: 152.97 kB
```

Only one view and at most one modal are ever visible at a time. A first-time visitor pays
for parsing/executing all of it before they can use any of it.

**Fix:** `React.lazy` + `Suspense` per view (switch on `currentView`) and per modal (mount
lazily behind their `isXOpen` flags, which are already booleans in the store). Keep `react`
and `radix` manual chunks; consider adding a `supabase` chunk since `@supabase/supabase-js`
is sizeable and only needed once auth/data code runs.

**Impact:** High — cuts initial JS payload roughly in proportion to (views + modals not
needed for the landing/first view), likely 40–60% off the first-load JS.

**Effort:** Low–Medium. Mechanical per-view/per-modal wrapping; no logic changes.

---

### 2. Full library fetched with every nested relation, every load — DESCOPED, see below

`fetchUserLibrary` (`apps/web/src/lib/db.ts:220-254`) runs one query per login/session:

```
TITLE_SELECT = `
  *,
  title_cast (*),
  title_crew (*),
  seasons (*, season_cast (*)),
  viewings (*),
  episodes (*, episode_crew (*), episode_watch_events (*), episode_ratings (*), episode_reviews (*))
`
```

This pulls cast, crew, season cast, every episode, and every episode's crew/watch
events/ratings/reviews for *every title in the library*, on app open, before any screen
renders — even though the initial view (`discover` by default, or `upnext`) shows none of
it, and the poster wall only needs title/year/poster/rating/status. For a library with deep
TV back-catalogs this is the largest network+parse cost in the app and it's paid unconditionally.

This is a deliberate architectural tradeoff already recorded in `CLAUDE.md` ("all
filtering/sorting is client-side… no DB queries for filter changes") — the fix below keeps
that model, it just defers the expensive nested part.

**Original proposal (retracted):** split into a lightweight summary fetch for the poster
wall/list and a lazy per-title detail fetch (cast/crew/seasons/episodes) on first
`TitleDetailDrawer` open, on the assumption that "nothing outside the detail drawer reads
episode-level rows."

**That assumption is false — verified by grep, not by inspection this time:**
- `titleLastInteractionAt` (`useAppStore.ts:423`) walks every season's episodes' watch
  events/ratings/reviews to compute "most recent interaction." It backs `sortField:
  'lastInteraction'`, which is `defaultFilters`' **default sort** (`useAppStore.ts:393`) —
  so it runs for every title, on every load, before a user has opened anything.
- `titleHasPerson` (`useAppStore.ts:402`) walks season cast and episode crew to support the
  library's person filter ("Featuring X" chip) and `TheEnsemble`/`TheAuteurs` Ledger panels
  that jump into it — again, needs it for titles that were never opened.
- The library search box also matches against `t.cast` (`useAppStore.ts:449`).
- `TitleDetailDrawer` doesn't fetch on open at all — it reads the title already sitting in
  the Zustand `titles` array (`openDetailDrawer` just sets a selected id). There is no
  per-title fetch path to hang a "lazy detail load" off of without adding one from scratch.

So the nested relations aren't dead weight scoped to the detail drawer — they're load-bearing
for the *default* library view (default sort) and for filters/search that can fire from any
title, not just an opened one. A client-side lazy-fetch split would either silently break the
default sort order and person/cast search the first time a page of titles hasn't been
detail-fetched yet, or require fetching detail for the whole library anyway to keep sort/search
correct — which defeats the point.

A real fix here needs a product/schema decision, not just a client change: e.g. a
server-side rollup (a generated column or materialized view supplying
`last_interaction_at`/a person-membership index per title) so the client stops needing the
full nested join to answer "what order/which titles," while still lazy-loading the verbose
per-episode detail rows only for an opened title. That's a `supabase/migrations/` change plus
a store/`db.ts` rework, reviewed against `supabase-postgres-best-practices`, not a
drop-in-place perf patch — **left out of this pass**, tracked here for a follow-up.

---

### 3. Poster images always requested at a fixed size larger than needed

Every poster write path (`apps/web/src/lib/media.ts`) hardcodes `w500`:

```
posterUrl: item.poster_path ? `${TMDB_IMG}/w500${item.poster_path}` : undefined
```

The poster wall (`apps/web/src/views/Library.tsx`, `GRID_SIZES`) renders posters as small as
**130px** wide (`compact`) and at most **260px** (`large`). A 500px-wide JPEG is 2–4× more
pixel data than the compact/default grid ever displays, downloaded and decoded for every
poster in the visible set, on every library load.

**Fix:** Store (or serve via `<img srcset>`) a smaller TMDB size for the grid —
`w185`/`w342` cover the compact/default densities comfortably at typical device pixel
ratios — and reserve `w500` for contexts that actually render it large (e.g. the detail
drawer's poster). Simplest version: switch the grid `<img>` to `srcset` with `w185`/`w342`/`w500`
and a `sizes` attribute matching `GRID_SIZES`, so the browser picks per density without a
schema/write-path change. Bump the PWA `tmdb-images` cache's `maxEntries` (`vite.config.ts:43`,
currently 200) when landing this — three cached variants per poster instead of one drops
effective cached-poster coverage to a third unless the budget grows with it.

**Impact:** Medium–High — scales with library size; on a large poster wall this is the
single biggest image-transfer reduction available.

**Effort:** Low (srcset-only fix) to Medium (if also changing what's stored).

---

### 4. Backdrop images forced to TMDB's largest ("original") size

`hiResBackdrop()` in `apps/web/src/components/ui/hero-backdrop.tsx` rewrites any stored
backdrop URL's size segment to `/original/` unconditionally:

```
function hiResBackdrop(url?: string): string | undefined {
  if (!url) return url
  return url.replace(/\/t\/p\/(w\d+|original)\//, '/t/p/original/')
}
```

TMDB's `original` backdrops are frequently 1920×1080+ and several hundred KB to a few MB.
The banner renders at `aspect-[16/8]` inside a modal/drawer that's rarely wider than
~900–1000px in practice — original resolution is overkill on every device, and *especially*
wasteful on mobile, which is this app's primary surface (bottom-nav, bottom-sheet modals).

**Fix:** Cap at `w1280` (TMDB's next size down from `original`) — visually indistinguishable
in this layout, meaningfully smaller transfer. If crisper hero art on large desktop screens
matters, use `srcset` with `w1280`/`original` rather than forcing the largest unconditionally.
Note `backdropOverride` (the images-endpoint best-rated backdrop) bypasses `hiResBackdrop()`
entirely and is documented as already being "at original resolution" — the cap has to be
applied on that path too, not just inside `hiResBackdrop()`, or every title with an override
still ships the largest size.

**Impact:** Medium — one large image per detail-drawer open, so impact scales with how often
users open title details (frequently, per the app's core loop).

**Effort:** Low. One-line change plus optional `srcset`.

---

### 5. No `preconnect` to the TMDB image host

`index.html` preconnects to `fonts.googleapis.com`/`fonts.gstatic.com` but not to
`image.tmdb.org` — which is where the actual largest, most render-critical bytes (every
poster and every backdrop) come from. The DNS/TLS handshake for that origin only starts
once the browser discovers the first `<img src="https://image.tmdb.org/...">`, adding a
full round trip of latency to the first poster/backdrop paint.

**Fix:**
```html
<link rel="preconnect" href="https://image.tmdb.org" />
```
(No `crossorigin` — `<img>` requests to TMDB aren't CORS requests, so a `crossorigin`
preconnect would open a connection the actual image requests can't reuse, wasting it. Keep
`crossorigin` on the `fonts.gstatic.com` preconnect — font fetches *are* CORS.)

**Impact:** Low–Medium, but essentially free.

**Effort:** Trivial.

---

### 6. Poster wall has no virtualization (already flagged, worth prioritizing)

`apps/web/CLAUDE.md` already notes: *"the poster wall renders the full filtered set (grid
virtualization was planned but is not implemented)."* Confirmed in
`apps/web/src/views/Library.tsx` — `PosterWall` maps over the entire `filteredTitles` array
into live DOM nodes. Images are `loading="lazy"` (good — network is already deferred for
offscreen posters), but the DOM node count, layout, and paint cost still scale linearly with
library size, and every node carries a CSS `animation: poster-in … var(--poster-delay)`
stagger-in that the browser must schedule even for elements never scrolled into view.

**Fix:** Windowed rendering (e.g. `@tanstack/react-virtual`) for the grid and the ledger
`<table>`, keeping the existing markup/styling per cell — this is a rendering-strategy change,
not a visual one.

**Impact:** Medium, growing with library size; matters most for long-time users with large
archives, which is this app's target lifecycle.

**Effort:** Medium–High — the franchise-grouped layout (`FranchiseSections`) adds section
headers interleaved with grid items, which windowing libraries handle less trivially than a
flat list.

---

### 7. Decorative full-viewport overlays animate continuously and forever

`.grain`, `.projector-beam`, and `.dust` (`apps/web/src/index.css:374-426`) are fixed,
full-viewport, `pointer-events: none` layers with infinite CSS animations:

- `.grain` uses an inline SVG `feTurbulence` filter with `mix-blend-mode: overlay`,
  animating every 0.6s, forever. `mix-blend-mode` forces the browser to keep recompositing
  everything beneath it each frame, not just the grain layer itself.
- `.projector-beam` runs a 7s `filter: blur(30px)` flicker animation, forever.
- `.dust` runs a 26s background-position drift, forever.

These are already correctly disabled under `prefers-reduced-motion`
(`apps/web/src/index.css:1436-1439`), so accessibility is covered — this is purely about
steady-state CPU/GPU/battery cost for everyone else, running on every screen, all the time,
whether or not the tab is focused.

**Fix:** Pause all three (`animation-play-state: paused` or toggling an `is-active` class)
when `document.visibilityState !== 'visible'` via a `visibilitychange` listener. Optionally
also drop `mix-blend-mode: overlay` on `.grain` in favor of a cheaper composite (e.g. a
pre-baked semi-transparent noise texture at low opacity with `mix-blend-mode` removed) if the
visual result is close enough — that trade is aesthetic-adjacent enough to want a visual
check before taking it.

**Impact:** Low–Medium — doesn't affect load time, but reduces continuous CPU/GPU draw and
battery use for the whole session, which matters on mobile (the app's primary surface).

**Effort:** Low for the visibility-pause part; the `mix-blend-mode` swap is optional and
needs a visual gut-check first.

---

### 8. Every search keystroke synchronously filters + sorts the whole library

`Library.tsx`'s search input is bound straight to the store: `onChange={(e) =>
setFilter('search', e.target.value)}`. `setFilter` (`useAppStore.ts:668-672`) runs
`applyFiltersToTitles` — a filter pass *and* a sort pass over the entire `titles` array —
synchronously in the same tick, then the unvirtualized poster wall (finding `#6`) re-renders
its full result. On a large library this is the app's worst input-latency path: every
keystroke, not just the final one, pays for a full library scan.

**Fix:** Decouple the input's visible value from the store commit — keep the `<input>`
controlled by local component state (updates instantly, so typing never feels delayed), and
debounce the `setFilter('search', …)` call into the store by ~150–200ms, the same pattern
already used for the ledger layout save (`LEDGER_SAVE_DEBOUNCE_MS`, `useAppStore.ts:583`).
External writers of `filters.search` (the Ledger's "search this actor/director" jumps in
`TheEnsemble`/`TheAuteurs`/`SecondOpinions`) call `setFilter` directly and should keep
resolving immediately — only the library search box's own typing needs the debounce, so the
local state must re-sync whenever `filters.search` changes from outside.

**Impact:** Medium-High for interaction smoothness — cheap fix, and matters more than the
bundle-size items for anyone actually typing in the search box on a real library.

**Effort:** Low.

---

## Suggested order of work

Ordered by risk, ascending, not by impact — earlier items are cheap to revert if something
goes wrong, so problems surface (and get fixed) before the riskier structural changes land.

1. `preconnect` to `image.tmdb.org` (`#5`) + `srcset`/smaller poster sizes (`#3`) + cap
   backdrop resolution both paths (`#4`) — one PR, all image/network, trivially reviewable.
2. Debounce library search (`#8`) — cheap, isolated, big interaction-smoothness win.
3. Pause atmosphere animations when the tab is hidden (`#7`) — cheap, independent of
   everything else. Not touching `mix-blend-mode` — that's an aesthetic trade, out of scope
   for a "no visual change" pass.
4. Code-split views + modals (`#1`) — biggest win, more moving parts (see note below).
5. ~~Split the library fetch~~ — descoped, see `#2`.
6. Virtualize the poster wall / ledger table (`#6`) — highest effort, and see the note below
   before landing it.

**Note on `#1`:** the first-time-visitor entry path is `LandingScreen` + `ProfileModal`
(`App.tsx:224-228`), not any of the seven views — those two stay in the eager entry chunk.
Modals with exit transitions (sheets/drawers using CSS transitions on close) should
lazy-load on first open but then **stay mounted** afterward — unmounting on close would kill
the close animation, which is a visual regression the task rules out.

**Note on `#6`:** `PosterWall` gives every poster a CSS entrance stagger keyed off its index
(`staggerDelays`, `animation: poster-in … var(--poster-delay)`). A naive virtualization swap
means posters scrolled into view later replay that entrance animation on every scroll instead
of once on initial load — a real behavior change, not a neutral one. Flag this to whoever
signs off before landing it; it isn't a call to make silently under "no functionality/aesthetic
change."
