# Ledger v2 — "The Projection Room" Design Spec

**Date:** 2026-06-28  
**Status:** Approved  
**File:** `src/views/Ledger.tsx`

---

## Philosophy

Every panel should feel like a spread in a film annual. Data-as-editorial, not dashboard. The page rewards lingering — atmosphere over information density.

---

## Changes Overview

| # | Change | Type |
|---|--------|------|
| 1 | Stat strip → Editorial ribbon | Replace |
| 2 | Timeline column chart → Activity heatmap | Replace |
| 3 | Decade filmstrip | New panel |
| 4 | Encore performances (replaces MediaBreakdown) | Replace |
| 5 | Rating distribution average marker | Enhance |
| 6 | Directors + genres become interactive | Enhance |
| 7 | Layout rhythm overhaul | Structural |

---

## Section Specs

### 1. Editorial Ribbon (replaces `StatStrip`)

**Current:** 6 individual cards in a `auto-fit` grid — Films, Series, Screenings, Avg Rating, Screen Time, In the Dark.

**New:** A single horizontal strip, borderless, no card backgrounds. Stats laid out inline, separated by amber `·` dividers. Numbers in `font-display` (`Fraunces`), labels in `font-mono` 10px uppercase. Film/TV split absorbed as an inline sub-stat ("47 films · 12 series").

**Layout:** Horizontal scroll on mobile. On desktop, all stats fit in one line.

**Stats shown:** Total titles (with film/TV breakdown inline), Total screenings, Avg rating (★ suffix), Total hours (h suffix), Days in the dark (d suffix).

**Component:** `StatRibbon` — replaces `StatStrip`.

---

### 2. Activity Heatmap (replaces `Timeline`)

**Current:** A `<div>` column chart of screenings grouped by month — minimal height, hard to read dates.

**New:** A 52-week × 7-day calendar grid (GitHub-style, but amber). Each cell is a day; days with ≥1 viewing glow amber (intensity scales with count for days with multiple viewings). Empty days are a dim wash square. Month labels (`Jan`, `Feb`, etc.) float above their column range. The grid scrolls horizontally if needed.

**Data source:** Compute from `useAppStore(s => s.titles)` → flatten `title.viewings[]` → build a `Set<string>` of `YYYY-MM-DD` dates (and a `Map<string, number>` for counts). Do NOT use `viewingsByMonth` from stats — we need per-day resolution.

**Date range:** Last 365 days from today.

**Empty state:** A fully dim grid with "No screenings in the last year" label centered.

**Component:** `ActivityHeatmap` — replaces `Timeline`.

---

### 3. Decade Filmstrip (new)

**Panel title:** "By the era" / hint: "decade breakdown"

**Visualization:** Horizontal bar chart. Y-axis: decade labels (1920s–2020s, omitting decades with 0 titles). X-axis: count. Bars are the same amber gradient as other bars. Decade with the most titles gets full-width bar; others scale proportionally.

**Data source:** Computed inline from `useAppStore(s => s.titles)` — group by `Math.floor(t.year / 10) * 10`, format as `"1970s"` etc.

**Layout:** Full-width panel (`col-span-12`), but bars are horizontal so it reads as a filmstrip.

**Component:** `DecadeFilmstrip`.

---

### 4. Encore Performances (replaces `MediaBreakdown`)

**Panel title:** "Encore performances" / hint: "most revisited"

**Content:** Titles where `viewings.length >= 2`, sorted descending by viewing count. Show up to 6 entries. Each row: rank number (mono, amber-deep), title (serif), year (mono, paper-dim), viewing count rendered as filled dots (same dot pattern as TheAuteurs).

**Empty state:** "No title has screened twice yet" in paper-faint.

**What it removes:** The donut chart panel (`MediaBreakdown`) — film/TV split numbers are absorbed into the Editorial Ribbon.

**Data source:** `useAppStore(s => s.titles)` filtered and sorted inline.

**Component:** `EncorePerformances`.

---

### 5. Rating Distribution — Average Marker

**Enhancement to existing `RatingDistribution`.**

**Change:** After the bar for each rating, render a thin amber vertical rule (1px, full-height of the bar track) at the position corresponding to `stats.avgRating`. Float the numeric average (`★ 4.1`) in a small chip above the rule.

**Implementation:** Position the marker absolutely over the bar track area. The bar track column already has a fixed height; the marker is `position: absolute` inside a `position: relative` wrapper on the bar-track div.

**No other changes** to RatingDistribution.

---

### 6. Interactive Directors + Genres

**TheAuteurs:** Each director `<li>` becomes a `<button>`. On click:
```ts
setFilter('search', director.name)
requestView('library')
```
The existing search filter matches `t.director?.toLowerCase().includes(q)`, so this navigates to the Library filtered to that director's titles. Add `cursor-pointer` and a hover underline.

**GenreBars:** Each genre row becomes a `<button>`. On click:
```ts
setFilter('genres', [genre])
requestView('library')
```
Also change the rightmost column from `%` to show both count and percentage: `12 · 34%`. This gives actual cardinality context.

---

### 7. Layout Rhythm

**Current:** Uniform `grid-cols-12` with all panels at similar heights.

**New layout (desktop):**

```
[StatRibbon ─────────────────────────── col-12]

[ActivityHeatmap ─────── col-8] [EncorePerformances col-4]

[RatingDistribution col-5] [GenreBars ─────────── col-7]

[DecadeFilmstrip ───────────────────────col-12]

[TheAuteurs ────────────────────────────col-12]
```

`TheAuteurs` on desktop gets a horizontal layout: instead of a vertical `<ol>`, render 3 columns of directors side by side (using CSS columns or a 3-col grid), giving it a "credits page" feel.

---

## What's Not Included

- **External scores panel** (IMDb/RT/MC averages): requires new stat computation infrastructure and doesn't add to the magazine feel.
- **Prose hero generator**: too risky with sparse data — hero text stays as-is.
- **Virtualization / performance**: library is small; no need.

---

## Component Map

| Component | Status | Notes |
|-----------|--------|-------|
| `DashHero` | Keep as-is | No changes |
| `StatStrip` | → `StatRibbon` | Rewrite |
| `Timeline` | → `ActivityHeatmap` | Rewrite |
| `RatingDistribution` | Enhance | Add avg marker |
| `GenreBars` | Enhance | Add click-through, show count |
| `DecadeFilmstrip` | New | New component |
| `EncorePerformances` | New | New component |
| `MediaBreakdown` | Remove | Absorbed into ribbon + new panel |
| `TheAuteurs` | Enhance | Add click-through, horizontal layout |
| `Panel` | Keep | Unchanged shell component |

---

## Data Dependencies

All new data is computed from `useAppStore(s => s.titles)` — no new store state, no new `LedgerStats` fields, no new DB queries.

| Panel | Source |
|-------|--------|
| ActivityHeatmap | `titles[].viewings[].date` |
| DecadeFilmstrip | `titles[].year` |
| EncorePerformances | `titles[].viewings.length` |
| StatRibbon | `stats` (existing) |
| Avg marker | `stats.avgRating` (existing) |
