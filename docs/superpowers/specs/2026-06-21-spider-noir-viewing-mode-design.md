# Spider Noir Viewing Mode — Design Spec

**Date:** 2026-06-21  
**Status:** Approved

---

## Overview

An easter egg feature tied exclusively to Spider-Man: Noir. When the user logs an episode watch event or a review for this title, they are asked a deliberate, cinematic question: *"How did you experience this?"* — choosing between **Authentic Black & White** or **True-Hue Full Color**. The choice is stored per-event and drives an app-wide visual transformation whenever Spider Noir's detail drawer is open.

---

## Detection

Spider-Man: Noir is identified by a single hardcoded constant:

```ts
const SPIDER_NOIR_TMDB_ID = 242484  // Spider-Man: Noir (2025, Amazon) — verify at implementation time
```

Detection is `title.tmdbId === SPIDER_NOIR_TMDB_ID`. No database flag, no new enum, no per-row marker on `titles`.

---

## Section 1 — Data Layer

### DB Migration

File: `supabase/migrations/20260621000000_spider_noir_color_mode.sql`

```sql
alter table episode_watch_events
  add column color_mode text check (color_mode in ('bw', 'color'));

alter table episode_reviews
  add column color_mode text check (color_mode in ('bw', 'color'));
```

Nullable by design. Existing rows are unaffected. Non–Spider Noir titles never populate this column.

### `schema.sql` sync

Add the same `color_mode` column comments to `schema.sql` to keep it in sync.

### Type changes (`src/store/mockData.ts`)

```ts
export interface EpisodeWatchEvent {
  id: string
  watchedAt: string
  notes?: string
  colorMode?: 'bw' | 'color'   // Spider Noir only
}

export interface EpisodeReview {
  id: string
  reviewText: string
  reviewedAt: string
  colorMode?: 'bw' | 'color'   // Spider Noir only
}
```

### DB mapping (`src/lib/db.ts`)

- `mapDbTitleToLocal`: map `we.color_mode` → `colorMode` on watch events; map `rv.color_mode` → `colorMode` on reviews.
- `logEpisodeToDb`: accept optional `colorMode?: 'bw' | 'color'` in `opts`; include in both `episode_watch_events` and `episode_reviews` inserts when present.

---

## Section 2 — The "How Did You Watch It?" Modal

### Component: `SpiderNoirModeModal`

Shown automatically after the user saves an episode watch event **or** a review for Spider Noir, before the episode log form closes.

**Trigger flow:**
1. User saves episode log / review in `TitleDetailDrawer` (existing flow).
2. If `isSpiderNoir(title)`, hold the event ID in local state and open `SpiderNoirModeModal`.
3. Modal presents two choices; user selects one (or dismisses).
4. On selection: call a new `patchEpisodeEventColorMode(eventId, table, colorMode)` function in `db.ts` to `update` the just-inserted row; update the Zustand store in-place.
5. Modal closes; the visual transformation immediately activates (see Section 3).

**Visual design:**

- Full-screen overlay, `bg-void/90 backdrop-blur-sm`, slow `fade-in` animation (300ms).
- Centered container, max-width `480px`.
- Headline: *"How did you experience this?"* in `Fraunces` serif, large.
- Two tall cards side by side (`gap-4`):
  - **Left — "Authentic Black & White"**: card itself rendered via `filter: grayscale(1)`, film-grain CSS texture, silver/white typography, spider icon in mono. Label: `◐ Authentic Black & White`.
  - **Right — "True-Hue Full Color"**: vivid amber-to-crimson gradient background, full-color amber spider icon, bold type. Label: `◈ True-Hue Full Color`.
- Selecting a card triggers the patch + closes the modal.
- Small `"not now"` text link at the bottom — skips without setting `colorMode` (stays null).

---

## Section 3 — App-Wide Transformation

### Mechanism

`TitleDetailDrawer`, when rendering Spider Noir's detail, runs a `useEffect` that:

1. Reads the most recent watch event or review (across all episodes of Spider Noir) with a non-null `colorMode`.
2. Adds `spider-noir-bw` or `spider-noir-color` to `document.body.classList`.
3. Returns a cleanup that removes both classes when the drawer unmounts.

The active mode re-derives whenever the title's events change (e.g., immediately after the modal patches a new event).

### CSS (`src/index.css`)

#### B&W Mode

```css
body.spider-noir-bw #root {
  filter: grayscale(1) contrast(1.15);
  transition: filter 600ms ease;
}

body.spider-noir-bw .grain {
  opacity: 0.18;   /* doubled from default ~0.09 */
}
```

Amber (`#e9b266`) survives grayscale at ~18% luminance — enough to remain faintly visible, producing the authentic noir look where warm tones bleed through silver nitrate.

#### Color Mode

```css
body.spider-noir-color #root {
  filter: saturate(2.2) contrast(1.05) hue-rotate(-8deg);
  transition: filter 600ms ease;
}

/* Shimmer on amber buttons — .btn-amber is the primary amber element class */
body.spider-noir-color .btn-amber {
  animation: spider-noir-shimmer 3s ease-in-out infinite;
}

/* Projector beam cycles through amber → crimson → violet via hue-rotate.
   Must include blur(30px) to preserve the element's existing filter. */
body.spider-noir-color .projector-beam {
  animation: flicker 7s ease-in-out infinite,
             spider-noir-beam-hue 8s ease-in-out infinite;
}

@keyframes spider-noir-shimmer {
  0%, 100% { box-shadow: 0 6px 20px -8px rgba(233, 178, 102, 0.7); }
  50%       { box-shadow: 0 6px 28px -6px rgba(233, 100, 40, 0.95); }
}

@keyframes spider-noir-beam-hue {
  0%, 100% { filter: blur(30px) hue-rotate(0deg); }
  33%       { filter: blur(30px) hue-rotate(25deg); }   /* amber → crimson */
  66%       { filter: blur(30px) hue-rotate(-90deg); }  /* → violet */
}
```

The `hue-rotate(-8deg)` on `#root` warms ambers into vivid orange-gold and deepens any blues to electric. The beam keyframe stacks with the existing `flicker` animation and shifts the glow color through amber → crimson → violet via `hue-rotate` (animating `filter` interpolates cleanly, unlike `background` gradients). The `.btn-amber` shimmer pulses the box-shadow from gold to a deeper orange-red, evoking ink bleeding into wet paper.

### Transition

Both modes apply via a 600ms `transition: filter` on `#root`, so the shift isn't jarring. The cleanup removes classes instantly on drawer close — the transition CSS handles the fade-out gracefully.

---

## Section 4 — Episode Timeline Badges

In the episode panel's watch event and review lists, when `colorMode` is set, render a small pill badge inline with the date:

| Mode | Badge |
|------|-------|
| `bw` | `◐ B&W` — muted silver/gray pill |
| `color` | `◈ Color` — amber pill |

This makes the per-event history scannable at a glance.

---

## New DB function (`src/lib/db.ts`)

```ts
export async function patchEpisodeEventColorMode(
  userId: string,
  table: 'episode_watch_events' | 'episode_reviews',
  eventId: string,
  colorMode: 'bw' | 'color'
): Promise<void>
```

Simple `update ... where id = eventId and user_id = userId`.

---

## Files Touched

| File | Change |
|------|--------|
| `supabase/migrations/20260621000000_spider_noir_color_mode.sql` | New migration |
| `schema.sql` | Add `color_mode` column to both tables |
| `src/store/mockData.ts` | Add `colorMode` to `EpisodeWatchEvent` and `EpisodeReview` |
| `src/lib/db.ts` | Map `color_mode`, extend `logEpisodeToDb`, add `patchEpisodeEventColorMode` |
| `src/components/TitleDetailDrawer.tsx` | Spider Noir detection, modal trigger, body class effect, timeline badges |
| `src/components/SpiderNoirModeModal.tsx` | New component |
| `src/index.css` | B&W and color transformation CSS + keyframes |

---

## Non-Goals

- No general "color mode" system for other titles.
- No server-side rendering of the CSS transformation.
- No change to the `titles` table or the library poster wall (transformation is drawer-scoped via body class while drawer is open).
