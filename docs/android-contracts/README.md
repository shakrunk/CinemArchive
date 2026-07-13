# Android contract fixtures

Per-domain contracts for the parity matrix rows closest to "ready" (see
`docs/android-parity-matrix.md`). Each domain doc covers the five completion rules from
that matrix: field/enum/timestamp/pagination/error shape, the RLS authorization matrix,
idempotency/retry behavior, a fixture file, and the Android route's loading/empty/offline/
error states. Fixture JSON lives alongside each doc in `fixtures/`.

| Domain | Doc | Fixture |
| --- | --- | --- |
| Library | [library.md](./library.md) | [fixtures/library.json](./fixtures/library.json) |
| Title detail | [title-detail.md](./title-detail.md) | [fixtures/title-detail.json](./fixtures/title-detail.json) |
| Episode tracking | [episode-tracking.md](./episode-tracking.md) | [fixtures/episode-tracking.json](./fixtures/episode-tracking.json) |

These fixtures are hand-authored from the current `schema.sql` and `src/lib/db.ts` shapes,
not exported from a live database — they are for shape/contract testing, not production
data. Regenerate them if `TITLE_SELECT` in `src/lib/db.ts` or the underlying tables change.
