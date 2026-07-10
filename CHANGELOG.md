# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and version numbers
follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

See `CLAUDE.md` → **Versioning** for how entries get here and how the version
number is chosen.

## [Unreleased]

## [1.0.0] - 2026-07-10

Baseline release. Marks the app as built, deployed, and in daily use — all
history before this point is retained in git log, not itemized here.

### Fixed
- Discover view: eliminated synchronous `setState` calls inside `useEffect` bodies
  (detail-modal hydration, "because you watched" / "more starring" defaults, studio
  search loading) in favor of derived state, per React's "you might not need an
  effect" guidance.
