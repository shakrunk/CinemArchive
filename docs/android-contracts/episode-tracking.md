# Episode tracking contract

Web sources: `src/lib/episodeUtils.ts` (not re-read line-by-line — the persisted shape is
fully defined by `logEpisodeToDb` and the nested `episodes` nodes `mapDbTitleToLocal`
produces), `src/lib/db.ts` (`logEpisodeToDb`, `insertPrePlatformWatchEventsToDb`,
`upsertEpisodeMetadataInDb`, `deleteEpisodeWatchEventFromDb`).

The core invariant this domain exists to preserve: **watch, rating, and review are three
independent, separately-timestamped logs**, not one row with three optional columns. A
title can be watched three times, rated once, and reviewed zero times, and the UI must
reconstruct correct rollups (e.g. "episodes watched" count, "current rating") from the
three streams rather than assuming 1:1:1.

## 1. Fields, enums, timestamps

Episode (child of a season):

| Field | Type | Source | Notes |
| --- | --- | --- | --- |
| `id` | uuid | `episodes.id` | client-generated |
| `episodeNumber` | integer | `episodes.episode_number` | |
| `episodeName` | text? | `episodes.episode_name` | |
| `airDate` | date? | `episodes.air_date` | |
| `runtime` | integer? | `episodes.runtime` | minutes |
| `synopsis`, `stillUrl` | text? | `episodes.*` | |
| `crew[]` | array | `episode_crew` | `{ tmdbPersonId, name, job }`; `director` = first crew with `job = 'Director'`, `writers[]` = crew with `job in ('Writer','Teleplay','Story')` (derived client-side, not stored) |

Independent per-episode logs — **all three keyed by `episode_id` + `user_id`, none
referencing each other**:

| Log | Fields | Timestamp semantics |
| --- | --- | --- |
| `episode_watch_events` | `id`, `watchedAt` (date, null = pre-platform/indeterminate), `notes`?, `colorMode` (`bw｜color`)? | `watchedAt` is *when the episode was watched*; `created_at` (not exposed to client mapping) is when the row was written — always create a fresh event per watch, never update one in place, so a rewatch is a second row |
| `episode_ratings` | `id`, `rating` (numeric 0–5), `ratedAt` (timestamptz, defaults `now()`) | `ratedAt` is *when the user recorded the rating*, independent of any watch event |
| `episode_reviews` | `id`, `reviewText`, `reviewedAt` (timestamptz, defaults `now()`), `colorMode`? | same independence as ratings |

Rollup fields on `seasons` (denormalized counters, not derived client-side from episode
rows): `episodeCount`, `episodesWatched` — written by `updateTitleInDb`'s seasons upsert,
constrained server-side by `seasons_episodes_valid check (episodes_watched <= episode_count)`.

## 2. RLS authorization matrix

Same pattern as the other two domains: `"episodes: owner full access"` /
`"episodes: shared/friend read"`, and identically-shaped pairs for
`episode_watch_events`, `episode_ratings`, `episode_reviews` — each read policy joins
`episodes → titles` and gates on `can_view_title`.

| Caller | Access |
| --- | --- |
| Owner | full CRUD on all four tables |
| Friend / valid shared token, in scope | read-only on all four |
| Anyone else | 0 rows |

No log is individually shareable narrower than its parent title — a friend who can see a
title's episodes can see every watch/rating/review on it. There is no per-log privacy
control (e.g. "share watches but not reviews").

## 3. Idempotency / atomic-command behavior

- `logEpisodeToDb` accepts an *optional* `watchEventId` for the watch-event insert
  specifically so the optimistic client store id matches the DB row — **this parameter does
  not exist for ratings or reviews today.** This is the concrete gap flagged in
  `docs/android-sync-contract.md` §4.2: Android must not port `logEpisodeToDb` as-is for
  those two logs. The contract for Android's write path is: always generate and send a
  client-side `id` for all three inserts (watch event, rating, review), and the insert
  becomes `upsert(..., { onConflict: 'id', ignoreDuplicates: true })` (or equivalent) rather
  than a bare `insert`, so a retried write after a dropped response is a no-op instead of a
  duplicate log entry.
- `logEpisodeToDb`'s three inserts (watch/rating/review) are three separate round-trips, not
  one transaction — a partial failure (watch event lands, rating insert fails) is possible
  today. Same flag as Title detail §3: acceptable to inherit for this pass, worth wrapping
  in one RPC later, not blocking.
- `insertPrePlatformWatchEventsToDb` is a true bulk idempotent insert (client-supplied ids
  per event) — this one is already a good model for the pattern above.
- Season rollup counters (`episodesWatched`/`episodeCount`) are **not** automatically kept
  in sync with the watch-event log by any trigger — the client computes and writes them
  explicitly via `updateTitleInDb`'s seasons upsert. Android must replicate this
  computation (count of episodes with ≥1 watch event) rather than trusting the DB to derive
  it, or the rollup will silently drift from the underlying watch events.

## 4. Android route states

Route: Title detail → episode list / episode expanded view (this domain has no standalone
route; it's a sub-surface of Title detail for TV titles).

- **Loading:** N/A beyond Title detail's own loading state (§4 in title-detail.md) — episode
  logs arrive nested in the same bootstrap payload.
- **Empty:** an episode with zero watch events / zero ratings / zero reviews is the default,
  common state, not an error — every new episode starts here.
- **Offline / retry:** logging a watch/rating/review while offline must queue with a
  client-generated id (per §3) so it can safely retry without duplicating; the UI shows the
  optimistic entry immediately, matching the web app's optimistic-update pattern in
  `useAppStore.ts`.
- **Process-death:** since the write path is idempotent on client-generated id (once the
  gap in §3 is fixed), an in-flight write that survives process death via a durable local
  outbox (e.g. WorkManager) is safe to replay on next launch without a dedup step beyond
  "did this id already sync" — this is the reason the id-based idempotency fix in §3 is a
  hard prerequisite for the Episode tracking domain's "offline/retry/process-death tests"
  gate in the parity matrix, not optional polish.
