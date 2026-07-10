# UI Consistency Audit — The Projection Room

## Where the *cinematic system* quietly disagrees with itself

A full pass over every view and component for drift in color, type, spacing, and interaction patterns — weighted toward the small, repeated things a user feels before they can name. Deliberate one-offs (brand marks, Easter-egg themes) are catalogued separately, not flagged.

---

## Scorecard
* **2** High-severity — 1 fixed, 1 open
* **3** Medium — fixed
* **3** Low / polish — fixed
* **6** Forgiven — intentional

---

## 01: The eyebrow label has no single author
**Severity:** High
**Status:** Open — deferred. Dozens of call sites across nearly every view; worth its own pass rather than bundling into the §02–06 cleanup.

The small uppercase "kicker" label — the thing that says *Cast*, *Genres*, *Widgets*, *Status*, *Details* above a block of content — is the single most repeated UI element in the app, and it's reimplemented by hand almost everywhere it appears. Two components already exist to own this exact role: `SubsectionLabel` and `StatLabel` in `typography.tsx`. Between them they're imported in zero view files.

### Four different font families for the same visual role
Some eyebrows use `font-mono` (DM Mono), others `font-sans` (Hanken Grotesk) — for labels that are typographically identical in intent. On a dense screen like the title drawer or the ledger editor, both show up within a few hundred pixels of each other.

| Family | Where |
| :--- | :--- |
| font-sans | `TitleDetailDrawer.tsx:1431` "Status", `TitleCommentsPanel.tsx:115` "Comments & Reactions", `AddTitleWorkflow.tsx` form labels, `section.tsx` |
| font-mono | `Library.tsx:59`, `ShareScopeEditor.tsx:138` "Genres", `Discover.tsx:833` "Cast", `WidgetPalette.tsx:71` "Widgets", `WidgetDetails.tsx` (×6), `TopBar.tsx:81` |

### Size and tracking drift across a 1.5× range with no scale behind it
Font sizes for this one role span `8px` to `text-xs` (12px) as arbitrary bracket values, and letter-spacing ranges from Tailwind's `tracking-widest` default up to a bespoke `tracking-[0.42em]` — none of it reused between neighbors.

**Sizes:** 8px · SecondOpinions.tsx:62 | 9px · ChartTip.tsx:85 | 9.5px · TopBar.tsx:81 | 10px · Library.tsx:59 | 11px · UpNext.tsx:306 | 12px (text-xs) · typography.tsx SubsectionLabel
**Tracking:** tracking-widest · typography.tsx | 0.06em · Library.tsx:460 | 0.1em · Discover.tsx:833 | 0.14em · WidgetDetails.tsx:80 | 0.18em · Library.tsx:59 | 0.36em · LandingScreen.tsx:29 | 0.42em · LandingScreen.tsx:18

### Three color tokens doing one job
`text-paper-dim`, `text-paper-faint`, and `text-muted-foreground` are all used for the identical "quiet caption" role — the first two are the app's own bespoke palette, the third is the shadcn semantic layer grafted on top. They render at visibly different contrast levels against `void`, so the same kind of label reads brighter or dimmer purely by which file wrote it.

> **Verdict:** **Fix shape:** route all of these through `SubsectionLabel` (block labels) and `StatLabel` (inline/stat captions) — both already exist and already encode a real, considered spec (`font-sans text-xs font-semibold uppercase tracking-widest text-paper-dim`). The `font-mono` variants read intentional in a few places (e.g. under stat numbers, matching `StatLabel`'s own choice) — worth keeping those, retiring the rest.

---

## 02: Five different close buttons
**Severity:** High
**Status:** Fixed — collapsed into a shared `ModalCloseButton` (`src/components/ui/modal-close-button.tsx`), wired into all six call sites via `scrim`/`plain` variants.

Every modal needs an "×" in the corner. The app has arrived at that button five separate times, and no two agree on size, background, or how the hover/focus state is produced.

| Implementation | Size / shape | Background | Hover | Focus ring |
| :--- | :--- | :--- | :--- | :--- |
| `cinema-modal.tsx:61` | 32px, pill | bg-black/60 + blur | Tailwind `hover:` | yes, amber |
| `poster-lightbox.tsx:20` | 32px, pill | inline rgba(0,0,0,.6), no blur | manual JS mouseenter/leave | none |
| `trailer-row.tsx:292` | 32px, pill | inline rgba + border + blur | manual JS, recolors border too | yes, amber |
| PersonDetailPanel / SendRecommendationPanel | 28px, pill | none — bare glyph | manual JS mouseenter/leave | none |
| `ShareScopeEditor.tsx:93` | 28px, pill | none — bare glyph | **absent entirely** | none |
| `KeyboardShortcutsHelp.tsx:77` | 28px, square-ish (`rounded`, not `-full`) | none, inline in header row | Tailwind `hover:` | none |

### ShareScopeEditor's close button doesn't respond to hover at all
It's a near-verbatim copy of the PersonDetailPanel / SendRecommendationPanel close button — same size, same bare-glyph treatment — but the copy dropped the `onMouseEnter`/`onMouseLeave` pair that gives the icon its brightening hover state elsewhere. Sitting the three side by side, this one is the button that visually "does nothing" until clicked.

> **Verdict:** **Fix shape:** the app already has a shared, undecorated backdrop (`ModalBackdrop`) that three of these five call into — a matching `ModalCloseButton` next to it would collapse five hand-rolled variants into one, and incidentally fix the missing hover state for free.

---

## 03: Shared button classes exist, and are re-typed by hand anyway
**Severity:** Medium
**Status:** Fixed — both `Ledger.tsx` call sites now import `SECONDARY_AMBER_BUTTON`.

`src/lib/utils.ts` exports `SECONDARY_AMBER_BUTTON` and `SECONDARY_AMBER_BUTTON_LG` — named, reusable Tailwind strings for the outlined amber CTA. `Ledger.tsx` reconstructs both by hand instead of importing them.

`Ledger.tsx:140` and `Ledger.tsx:185` retype the exact class list character-for-character (down to the same `focus-visible:ring-amber/60`) rather than referencing the constant. It matches today only because someone was careful; the next edit to the shared constant won't reach these two call sites, and they'll silently start to diverge.

> **Verdict:** **Fix shape:** `import { SECONDARY_AMBER_BUTTON, SECONDARY_AMBER_BUTTON_LG } from 'src/lib/utils'` at both call sites — no visual change, just removes the fork.

---

## 04: `.icon-btn` is a well-designed utility that mostly goes unused
**Severity:** Medium
**Status:** Fixed for its main offender — `ModalCloseButton` (§02) now backs it by construction. General adoption elsewhere still opt-in.

`index.css` defines a genuinely nice icon-button primitive — quiet by default, amber wash on hover, scale-down on press, a proper focus ring — used in only five files (`TopBar`, `AccountMenu`, `NotificationCenter`, `Library`, `Profile`). Everywhere else, icon-only buttons (modal close buttons chief among them, see §02) reinvent a hover/focus treatment locally, usually a weaker one.

> **Verdict:** **Fix shape:** not a rewrite — just the default reach for any new bare-icon button, and a natural base for the close-button consolidation above.

---

## 05: Keyboard focus is visible in some places and invisible in others
**Severity:** Medium
**Status:** Fixed — `ChoiceCard` now has a `focus-visible` ring, and the §02 close-button consolidation gave the three bare-glyph buttons one for free.

`focus-visible:ring` shows up in 22 of roughly 90 component/view files. Where it's present, it's a consistent amber ring — a real, considered convention. But several hand-built interactive elements have no focus state at all, so tabbing through those screens produces an invisible cursor for a few beats: `ChoiceCard` in `choice-modal.tsx` (the Matrix-pill / Spider-Noir picker cards — mouse-only hover, no `:focus-visible` equivalent), and the three bare-glyph close buttons in §02.

> **Verdict:** **Fix shape:** same root cause as §02/§04 — a shared interactive-element base would carry the focus ring along automatically instead of it being opt-in per file.

---

## 06: Small, isolated one-offs worth a look
**Severity:** Low
**Status:** Fixed — all three.

### An entire unused modal primitive
`src/components/ui/dialog.tsx` — the plain shadcn Radix Dialog scaffold — isn't imported anywhere in `src`. `cinema-modal.tsx` and `sheet.tsx` cover every real modal in the app. Harmless today, but it's a live trap: the next contributor who greps for "dialog" and finds this file first will build a sixth close-button variant on top of it. *(Removed.)*

### Awards badge color doesn't travel with the theme
`media-badges.tsx:55` hardcodes the "awards" badge to `#d4a72c` — a gold close to, but distinct from, the `amber` token. Every other accent in the app derives from `--amber-rgb`, which is exactly what flips to silver under the Noir theme and green under Matrix. This one won't; it'll sit there gold while everything around it goes monochrome or phosphor-green.

*   `#d4a72c` (awards, fixed)
*   `#e9b266` (--amber, themed)

*(Now `var(--amber)`; verified it resolves to silver under Noir and green under Matrix.)*

### One pill mixes a raw hex with a CSS variable
`episode-card.tsx:25` — `ColorModePill`'s B&W state uses literal `#aaa` for text color while its Color state uses `var(--amber)`. Not visually wrong (grey is a reasonable stand-in), but it's the only spot in the badge/pill family that doesn't route color through a token. *(Now `var(--paper-dim)`.)*

---

## Forgiven, on purpose

Things that look like violations of the token system but are the right call for what they're doing.

*   **Third-party brand colors** (IMDb `#F5C518`, Rotten Tomatoes `#FA320A`, Metacritic `#6ebc24`, TMDB's gradient logo) in `media-badges.tsx` — these have to stay exact regardless of theme; that's what makes them recognizable as the brand.
*   **Bechdel pass/fail colors** (green/red) — universal semantic colors that should arguably stay fixed even in the Noir/Matrix themes, unlike the awards-gold case above which has no such semantic reason to resist theming.
*   **The Matrix and Noir "Easter egg" themes** — fully token-driven via `[data-theme='matrix'|'noir']` in `index.css`, not a parallel one-off system. This is the correct way to add a bespoke visual mode.
*   **`#080503` poster-card scrim gradients** in `Discover.tsx` — explicitly commented as intentionally theme-independent, matching the documented behavior of `.poster.has-img`. Poster art is a fixed "cinema artifact" by design; the scrim over it should stay dark even in light mode.
*   **`dynamic-poster.tsx`'s desaturated hash-color palette** — a deliberately muted, non-amber set used to color-code untitled/placeholder art by hash; being outside the theme palette is the point, since it needs to stay legible and distinct independent of theme.
*   **Ledger panel empty-states** not routing through the shared `EmptyState` component — that component is written for page-level "nothing here yet" moments with a CTA; the two dozen ledger widgets each need a compact, panel-scoped "no data" line instead. Different job, correctly not sharing the component.

---

*Scope: src/components, src/views, src/index.css, tailwind.config.js — read directly, no build-time analysis. Grep-verified counts reflect current tree at audit time.*
