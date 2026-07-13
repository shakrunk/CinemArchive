# Title detail contract

Web sources: `src/components/TitleDetailDrawer.tsx` (not re-read line-by-line for this pass;
the data shape is fully defined by `mapDbTitleToLocal` in `src/lib/db.ts`, which is the
single mapping both the drawer and the library list consume), `src/lib/db.ts`
(`TITLE_SELECT`, `mapDbTitleToLocal`, `updateTitleInDb`, `deleteTitleFromDb`,
`upsertTitleCastInDb`/`upsertTitleCrewInDb`).

## 1. Fields, enums, timestamps

Extends the Library projection (`docs/android-contracts/library.md` §1) with the full
nested detail `TITLE_SELECT` fetches in one query:

| Field | Type | Source | Notes |
| --- | --- | --- | --- |
| `director` | text? | `titles.director` | movies; TV uses per-episode crew instead |
| `synopsis` | text? | `titles.synopsis` | |
| `runtime` | integer? | `titles.runtime` | minutes, movies only |
| `network` | text? | `titles.network` | TV only |
| `notes` | text? | `titles.notes` | owner's free-text notes |
| `releaseDate`, `originalLanguage`, `contentRating` | text? | `titles.*` | |
| `imdbId`, `rtUrl`, `customWatchUrl` | text? | `titles.*` | external links |
| `imdbRating`, `rtScore`, `metacriticScore` | numeric/int? | `titles.*` | badge scores |
| `awardsCount` | integer? | `titles.awards_count` | Wikidata-sourced, sparse |
| `bechdelOutcome` | enum `pass｜fail`? | `titles.bechdel_outcome` | |
| `bechdelScore` | text? | `titles.bechdel_score` | free-form "x/3" |
| `studios` | text[] | `titles.studios` | |
| `collectionId`, `collectionName` | int?/text? | `titles.collection_id/name` | TMDB franchise grouping |
| `inHomeCollection` | boolean | `titles.in_home_collection` | |
| `physicalMedia` | jsonb array | `titles.physical_media` | `[{ id, format, edition?, notes? }]`, no fixed schema beyond that shape |
| `cast[]` | array | `title_cast` | `{ tmdbPersonId, name, character?, episodeCount?, profileUrl?, order }`, sorted by `cast_order` |
| `crew[]` | array | `title_crew` | `{ tmdbPersonId, name, job, department?, profileUrl? }` |
| `seasons[]` | array | `seasons` + nested `episodes` | TV only; see episode-tracking.md for episode fields |
| `viewings[]` | array | `viewings` | see §2 below |

### Viewings (re-watch timeline, attached to a title)

| Field | Type | Source | Notes |
| --- | --- | --- | --- |
| `id` | uuid | `viewings.id` | |
| `date` | date? | `viewings.viewed_at` | null = watched before joining, sorts as oldest |
| `rating` | numeric? | `viewings.rating` | |
| `notes` | text? | `viewings.notes` | |
| `venue` | text? | `viewings.venue` | |
| `companions` | jsonb array? | `viewings.companions` | `[{ name, friendUserId? }]` |
| `outingId` | uuid? | `viewings.outing_id` | set when auto-completed from a `cinema_outings` row |

## 2. RLS authorization matrix

Same `can_view_title`-gated pattern as Library for `titles`/`seasons`/`viewings`/
`title_cast`/`title_crew`/`season_cast`/`episode_crew` (each has its own
`"<table>: owner full access"` + `"<table>: shared/friend read"` policy pair joining back
to `titles` — see `schema.sql` lines ~536–587). No table in this domain has a broader or
narrower policy than `titles` itself; a caller who can see the title can see all of its
nested cast/crew/seasons/viewings, and no caller can see nested data for a title they can't
see the parent of.

| Caller | Access |
| --- | --- |
| Owner | full CRUD on `titles`, `seasons`, `viewings`, `title_cast`, `title_crew`; write access to `season_cast`/`episode_crew` only via the episode-tracking domain |
| Friend / valid shared token, in scope | read-only, same scoping as Library |
| Anyone else | 0 rows (not an error) — same caveat as Library §3 |

## 3. Idempotency / atomic-command behavior

- `updateTitleInDb` is a single-row `update ... eq(id).eq(user_id)` for scalar fields, plus
  separate `upsert`/delete-reinsert calls for `seasons`, `viewings`, `cast`, `crew` **in the
  same function but not the same transaction** — a partial failure (e.g. scalar update
  succeeds, viewings upsert fails) currently leaves partial state client-visible. This is a
  known gap, not new to Android: any Android write path that edits a title must accept the
  same at-least-one-of-these-succeeded semantics until this is wrapped in a single RPC
  (tracked as follow-up, out of scope for this pass — flagging it here so it isn't lost).
- `deleteTitleFromDb` is a hard delete relying on `on delete cascade` for every child table.
  Per `docs/android-sync-contract.md` §3.2, this needs a `before delete` tombstone trigger
  per child table before Android can safely diff against it.
- Cast/crew refresh (`upsertTitleCastInDb`, `upsertTitleCrewInDb`) upserts on
  `(title_id, tmdb_person_id[, job])` — safe to retry as-is, already idempotent.

## 4. Android route states

Route: Title detail (drawer/screen reached from Library).

- **Loading:** row exists in Room from a prior sync but nested seasons/cast/crew haven't
  been fetched yet (if Android chooses to defer nested detail sync — see open question
  below) — otherwise this state doesn't exist, since bootstrap already includes full nesting.
- **Empty:** N/A at the field level (a title always has its core fields); applies to
  sub-sections only, e.g. "no viewings yet," "no cast data" (movies added without a TMDB
  match).
- **Offline:** render cached detail; edits queue locally and apply optimistically, same
  pattern as the web app's fire-and-forget writes.
- **Recoverable error:** a queued edit's write fails — surface per-field or per-action retry,
  not a full-screen error, since most of the screen is still valid cached data.

**Open question for implementation (not blocking this doc):** should Android's bootstrap
mirror the web app's single wide `TITLE_SELECT` query (simple, but pulls full cast/crew for
every title up front), or split Library (summary) and Title detail (nested) into separate
sync passes so the Library list stays lightweight? The sync contract in
`docs/android-sync-contract.md` supports either — `sync_library_changes` payload can carry
either shape — this is a client-side tradeoff, not a server contract decision.
