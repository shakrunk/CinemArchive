# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

See `CLAUDE.md` → **Versioning** for how entries get here and how the version
number is chosen.

## [Unreleased]

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

## [1.0.0] - 2026-07-10

Baseline release. Marks the app as built, deployed, and in daily use — all
history before this point is retained in git log, not itemized here.

### Fixed
- Discover view: eliminated synchronous `setState` calls inside `useEffect` bodies
  (detail-modal hydration, "because you watched" / "more starring" defaults, studio
  search loading) in favor of derived state, per React's "you might not need an
  effect" guidance.
