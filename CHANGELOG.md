# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

See `CLAUDE.md` → **Versioning** for how entries get here and how the version
number is chosen.

## [Unreleased]

### Fixed

- Ledger responsive-widget backlog (KP-004–KP-022): revamped all 15 remaining
  affected panels one at a time with fluid charts, preset-aware list/grid
  reflow, adaptive labels and plot sizing, and full-card use at `sm`, `md`,
  `lg`, and `full` widths. Verified that every ledger card stays within its
  horizontal and vertical bounds at 1440×900 and 390×844 viewports.
- Ledger adaptive composition follow-up: widget cards now use one clipped layer
  with consistent rounded corners, vertically balance their contents, and never
  create internal scroll surfaces. Mobile always selects the compact visual
  mode regardless of the saved desktop width. Screening Nights now progresses
  from a compact radar chart to a synchronized radar-and-bar composition;
  Critical Record and The Ensemble likewise switch to purpose-built compact
  legends/lists instead of squeezing their desktop presentations.

## [1.0.2] - 2026-07-10

### Fixed

- Ledger — "By the Genre" widget: genre bubbles no longer overflow the card
  when `topN` is configured toward the high end of its 3–12 range. Bubble size
  (and the `sm`-preset ranked list's row height) is now derived from an
  estimated row count so the widget's fixed-height card fits any configured
  count without forcing an internal scrollbar.

## [1.0.1] - 2026-07-10

### Fixed

- Ledger — "The Run" widget: the monthly screening trend chart no longer forces
  a horizontal scrollbar (previously a fixed per-month pixel width could balloon
  past 6000px on a 10-year "all time" range). The chart now fills its card at
  every board-width preset, with axis labels thinned to fit — including a
  mobile-safe label budget below the `lg` breakpoint, where every preset renders
  full-width. Added at-a-glance total/peak/average stat chips above the chart.
- Ledger — "By the Genre" widget: the genre bubble cloud now fills its card at
  every board-width preset instead of leaving fixed-size bubbles stranded in a
  half-empty card. Bubble diameter is now proportional to the available column
  width (capped per breakpoint) and rows recenter automatically; the narrow `sm`
  preset swaps to a compact ranked bar list at the `lg` breakpoint and up, where
  a bubble cloud would otherwise crowd into a ~4-of-12-column card.
- UI consistency: consolidated the app's five hand-rolled modal close buttons
  (`cinema-modal`, `poster-lightbox`, `trailer-row`, `PersonDetailPanel` /
  `SendRecommendationPanel`, `ShareScopeEditor`, `KeyboardShortcutsHelp`) into a
  single shared `ModalCloseButton`, fixing `ShareScopeEditor`'s close button
  (previously had no hover state at all) and giving every close button a
  keyboard focus ring for free. Added a focus-visible ring to the Matrix/Spider-Noir
  `ChoiceCard` picker, which previously had no visible keyboard focus state.
  Also: `Ledger.tsx`'s two hand-retyped copies of the shared amber CTA button now
  import the constant instead of forking it; the awards badge no longer hardcodes
  a fixed gold hex (now themes correctly under Noir/Matrix); the B&W/Color episode
  pill's text color now routes through a token instead of a raw `#aaa`; and the
  unused shadcn `dialog.tsx` scaffold was removed.

## [1.0.0] - 2026-07-10

Baseline release. Marks the app as built, deployed, and in daily use — all
history before this point is retained in git log, not itemized here.

### Fixed

- Discover view: eliminated synchronous `setState` calls inside `useEffect` bodies
  (detail-modal hydration, "because you watched" / "more starring" defaults, studio
  search loading) in favor of derived state, per React's "you might not need an
  effect" guidance.
