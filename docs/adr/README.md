# Architecture Decision Records

Structural decisions and the reasoning behind them, so the *why* survives the context in which it was
decided.

| # | Title | Status | Date |
|---|-------|--------|------|
| [0001](0001-android-foundation.md) | Native Android foundation and provisional identifiers | Accepted for foundation spike | 2026-07-11 |
| [0002](0002-multi-platform-repo-layout.md) | Multi-platform repo layout (`apps/*`) | Accepted — executed 2026-07-24 | 2026-07-23 |
| [0003](0003-ticket-ingestion-via-forwarding-address.md) | Ticket ingestion via a forwarding address, not Gmail OAuth | Accepted — not yet implemented | 2026-08-03 |

Summaries and cross-links: [Decision Records](https://github.com/shakrunk/CinemArchive/wiki/Decision-Records)
in the wiki. The files here are authoritative.

## Writing a new one

Add `NNNN-short-slug.md`, numbered sequentially, then add a row above.

```markdown
# ADR NNNN: Title

- **Status:** Proposed | Accepted | Superseded by ADR-XXXX
- **Date:** YYYY-MM-DD

## Decision

What is being decided, in the present tense, specifically enough to act on.

## Consequences

What follows — including the costs, what now has to be maintained, and the traps
this creates for someone changing it later.
```

Write an ADR when a choice is **structural and expensive to reverse**: a new platform, a new
persistence layer, an authorization model change, a build-system change. Routine library choices don't
need one — but "we evaluated X and deliberately dropped it" does, or it gets re-litigated in six
months.

Never rewrite an accepted ADR to reflect a later decision. Add a new one and mark the old
**Superseded by ADR-XXXX**; the record of what was believed at the time is the point.
