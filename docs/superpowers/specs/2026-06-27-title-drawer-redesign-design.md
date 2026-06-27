# Title Drawer Redesign — Design Spec

**Date:** 2026-06-27  
**Status:** Approved  
**Scope:** Visual and structural redesign of `TitleDetailDrawer` to match a streaming-platform aesthetic, with component extraction for maintainability.

---

## Goals

- Cinematic hero backdrop for TV series titles
- Episode carousel with thumbnail cards replacing the text accordion list
- Inline logging panel that expands below the carousel when a card is clicked
- Trailers section linking to YouTube
- Larger, more uniform cast layout
- Smart season selector (pills for ≤3 seasons, dropdown for >3)
- Extract the three new major UI surfaces into dedicated component files to reduce `TitleDetailDrawer.tsx` (currently 1820 lines)

---

## Architecture

### New files

| File | Purpose |
|---|---|
| `src/components/ui/hero-backdrop.tsx` | Full-bleed backdrop hero for TV series |
| `src/components/ui/episode-card.tsx` | Single episode card + inline logging panel |
| `src/components/ui/trailer-row.tsx` | Horizontal trailer thumbnail scroll row |

### Modified files

| File | Change |
|---|---|
| `src/components/TitleDetailDrawer.tsx` | Wire new components; upgrade movie hero blur; remove `EpisodeRow` / `EpisodePanel`; add trailer fetch |
| `src/components/TitleDetailDrawer.tsx` → `CastCrewSection` | Larger avatars, uniform card widths |
| `src/components/TitleDetailDrawer.tsx` → `TVSeriesSection` | Replace episode list with `EpisodeCarousel`; smart season selector |
| `supabase/functions/media-proxy/index.ts` | Add `action=videos` handler |
| `src/lib/media.ts` | Add `fetchTitleVideos()` |

### No DB migration required

Trailer video data is ephemeral TMDB metadata — fetched lazily on drawer open, stored in component `useState`, not persisted to Supabase or the `Title` type.

---

## Section-by-section design

### 1. Hero Section

**Movies (unchanged layout, upgraded atmosphere)**

The existing poster-left + title-right layout is preserved. The blurred background currently uses the poster image; swap it for `title.backdropUrl` (already fetched at w780, stored on `Title`). This makes the atmospheric blur more cinematic without changing the structure.

**TV Series (new full-bleed backdrop)**

A `HeroBackdrop` component renders above the modal scroll body:

- `backdropUrl` image fills the full modal width at ~220px tall with `object-cover`
- Gradient overlay: `from-transparent to-card` fading from top to bottom, so the image bleeds into the modal background naturally
- Poster floats bottom-left, partially overlapping the gradient transition (negative bottom margin pulls it into the content area below)
- Title, year, network badge, and star rating sit to the right of the poster over the gradient
- `PosterLightbox` trigger stays on the poster as now

Implementation note: `HeroBackdrop` receives `title: Title` and `onPosterClick: () => void`. It is only rendered when `title.type === 'tv'` and `title.backdropUrl` is set; falls back to the existing movie-style hero if the backdrop is absent.

---

### 2. Cast Section

Increase avatar size from `w-14 h-14` to `w-16 h-16` for the title-level cast. Give each cast item a fixed outer width (`w-[72px]`) so columns are uniform regardless of name length. Character name rendered more prominently (increase from `9px` to `10px`, slightly lighter color).

Season-level cast (inside `TVSeriesSection`) stays at the current `w-12 h-12` — it's secondary information.

No functional change — clicking opens `PersonDetailPanel` as before.

---

### 3. Season Selector (TV only)

Lives below the `SeriesGraph`, above the episode carousel, within `TVSeriesSection`.

**≤3 seasons:** Existing horizontal pill tabs. Tidy the active state slightly (cleaner amber border). Each pill still shows `S1 · 14% · ★3.5`.

**>3 seasons:** A `<select>` dropdown. Each `<option>` label: `"Season 1 · 14% · ★3.5"`. Styled to match the modal's dark theme via Tailwind (bg-secondary, border-amber/30, font-mono, text-sm).

The season count is known from `title.seasons.length`. The component renders one or the other — no toggle needed.

---

### 4. SeriesGraph

No change. Stays in its current position above the season selector, within `TVSeriesSection`. The heatmap continues to serve as a visual overview of the entire series before drilling into a season.

---

### 5. Episode Carousel

Replaces the current `EpisodeRow` accordion list. Lives below the `SeriesGraph` + season selector within `TVSeriesSection`.

**Carousel layout**

- Horizontal scroll container with `overflow-x-auto scrollbar-none`
- Cards are ~240px wide with a fixed aspect-ratio still image (16:9)
- ~3–4 cards visible at once; right edge of last visible card clips to hint at scroll
- Left / Right arrow buttons (`ChevronLeft` / `ChevronRight`) positioned absolutely at the carousel edges; hidden when scroll is at the respective boundary (tracked via scroll event + `scrollLeft`)
- Arrows scroll by one card width (`scrollBy({ left: cardWidth, behavior: 'smooth' })`)

**Episode card (`EpisodeCard` component)**

```
┌─────────────────────────────────┐
│ [E01]            [eye icon]     │  ← badges absolutely positioned over still
│                                 │
│         still image             │
│         (16:9, w-full)          │
│                                 │
├─────────────────────────────────┤
│ Episode Name                    │  ← font-sans, text-sm, font-medium
│ 2024 · 64m                      │  ← font-mono, text-xs, paper-faint
│ Synopsis line 1                 │  ← 2 lines max, text-xs, clamp-2
│ Synopsis line 2...              │
│ ─────────────────────────────── │  ← amber progress bar, shown if watched
└─────────────────────────────────┘
```

- `[E01]` badge: `font-mono text-xs bg-black/60 rounded px-1.5 py-0.5`, top-left absolute
- Eye icon: `Eye` from lucide-react, top-right absolute; `text-amber` if `episode.watchEvents.length > 0`, else `text-paper-faint/50`; clicking calls `logEpisode(titleId, seasonNumber, episodeNumber, { watchedAt: today })` — no expand, no confirmation
- Progress bar: full-width 2px amber bar at card bottom, visible only when `watched`
- Card border: `border border-line` normally; `border-amber/50` when selected (expanded)
- Card is a `<button>` for accessibility; clicking the card body (not the eye icon) toggles the inline panel

**`EpisodeCard` props:**
```ts
interface EpisodeCardProps {
  episode: Episode
  season: Season
  titleId: string
  isSelected: boolean
  onSelect: () => void
  isSharedView: boolean
  isSpiderNoir: boolean
}
```

**Inline logging panel**

- Rendered below the carousel container (`mt-2`), full modal width
- Shows only when `selectedEpisodeId !== null`
- Content is extracted from the current `EpisodePanel` component — same sections: director/writers, still + synopsis (full text), watch event history with delete, per-episode star ratings, review entry textarea
- Animated: `max-height` transition from 0 to auto (or use a CSS `data-expanded` attribute with a transition)
- Clicking the active card again, pressing Escape, or clicking another card collapses it (the new card's panel expands instead)
- `selectedEpisodeId` is local state in `TVSeriesSection` (or its parent); resets to `null` on season change

---

### 6. Trailers Section

Appears at the bottom of the modal scroll body, above the delete/refresh footer actions. Rendered for both movies and TV when videos are available.

**Data fetching**

New `action=videos` in the Edge Function:
```
media-proxy?action=videos&id={tmdbId}&type={movie|tv}
```
Hits TMDB `/{type}/{id}/videos` endpoint. Returns the `results` array.

New function in `src/lib/media.ts`:
```ts
export interface TitleVideo {
  key: string     // YouTube video ID
  name: string
  type: 'Trailer' | 'Teaser' | string
  official: boolean
}

export async function fetchTitleVideos(tmdbId: number, type: MediaType): Promise<TitleVideo[]>
```

Filters to `site === 'YouTube'` and `type` in `['Trailer', 'Teaser']`. Returns up to 4 results, sorted: official trailers first, then official teasers, then unofficial.

**In the drawer**

Fetched lazily in a `useEffect` when the drawer opens (`isDetailDrawerOpen && title.tmdbId`). Stored in `const [videos, setVideos] = useState<TitleVideo[]>([])`. Not persisted. Section hidden if fetch returns empty or errors.

**`TrailerRow` component**

- Horizontal scroll row (`overflow-x-auto scrollbar-none`)
- Each card ~160px wide, fixed
- Thumbnail: `https://img.youtube.com/vi/{key}/hqdefault.jpg` with `object-cover` at 16:9
- Play icon overlay (centered, semi-transparent `bg-black/40 rounded-full p-2`)
- Video name below (font-sans, text-xs, 1-line clamp)
- Type badge (`Trailer` / `Teaser`) below name in font-mono text-[10px] paper-faint
- Entire card is an `<a href="https://www.youtube.com/watch?v={key}" target="_blank" rel="noopener noreferrer">`

---

## Data flow summary

```
TitleDetailDrawer opens
  ├─ title.backdropUrl → HeroBackdrop (TV) or blurred backdrop (movie) — already available
  ├─ episode.stillUrl → EpisodeCard stills — already available on Episode type
  └─ fetchTitleVideos(tmdbId, type) → videos useState → TrailerRow
       └─ media-proxy?action=videos → TMDB /{type}/{id}/videos
```

No new DB columns. No migration. No changes to the `Title` or `Episode` types.

---

## Out of scope

- "Resume S2:E5" CTA button (requires tracking last-watched episode across sessions — separate feature)
- Trailer video playback in-modal (links to YouTube externally)
- Episode search within a season
- Virtualization of the episode carousel (not needed at typical season lengths)

---

## Success criteria

- TV series drawer opens with cinematic backdrop hero
- Movie drawer hero unchanged in layout, backdrop used for atmospheric blur
- Episode carousel scrolls horizontally with left/right arrows; stills render from TMDB
- Clicking the eye icon on a card logs a watch event immediately, no panel
- Clicking a card body opens the logging panel below the carousel
- Season pill tabs shown for ≤3 seasons; dropdown shown for >3
- Trailers section appears when TMDB returns video results; clicking opens YouTube in new tab
- Cast avatars are larger and uniformly spaced
- SeriesGraph position and behavior unchanged
- No regressions on the Spider-Noir easter egg flow
- `tsc` and `lint` pass clean
