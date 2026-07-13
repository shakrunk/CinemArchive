# Library contract

Web sources: `src/lib/db.ts` (`fetchUserLibrary`, `mapDbTitleToLocal`), `src/views/Library.tsx`,
`src/store/useAppStore.ts`. Backend: `titles` table + `sync_library_changes` RPC (proposed,
see `docs/android-sync-contract.md`).

## 1. Fields, enums, timestamps

Poster-wall/list row (a projection of the `titles` row — Android does not need the nested
`seasons`/`episodes`/cast/crew for the list view, only for Title detail):

| Field | Type | Source column | Notes |
| --- | --- | --- | --- |
| `id` | uuid | `titles.id` | client-generated on insert (see idempotency §4) |
| `tmdbId` | integer | `titles.tmdb_id` | |
| `type` | enum `movie｜tv` | `titles.type` | Postgres enum `media_type` |
| `title` | text | `titles.title` | |
| `year` | integer | `titles.year` | |
| `posterUrl` | text? | `titles.poster_url` | nullable |
| `status` | enum `watched｜watchlist｜watching｜dropped` | `titles.status` | Postgres enum `watch_status` |
| `rating` | numeric(3,1)? | `titles.rating` | 0–5, nullable |
| `genres` | text[] | `titles.genres` | never null, default `{}` |
| `tags` | text[] | `titles.tags` | never null, default `{}` |
| `addedAt` | date | `titles.added_at` | client maps to `YYYY-MM-DD`; DB is `timestamptz` |
| `updatedAt` | timestamptz | `titles.updated_at` | sync cursor field (§2) |

## 2. Pagination / sync

- **Bootstrap:** unfiltered `select` over all of the caller's `titles`, no pagination — the
  parity matrix's target library sizes (personal collection, low thousands at most) don't
  need bootstrap paging. See `docs/android-sync-contract.md` §2.1.
- **Incremental:** `sync_library_changes(p_since, p_limit)`, keyset-paginated on
  `(updated_at, id)`. `entity_type = 'title'` rows carry the full projection above in
  `payload`; `entity_type = 'tombstone'` rows (payload null) with
  `payload->>'entity_type' = 'title'` mean the title (and its cascade-deleted children) was
  removed — see `docs/android-sync-contract.md` §3.2.

## 3. RLS authorization matrix

Source: `schema.sql` policies `"titles: owner full access"` and
`"titles: shared/friend read"`, via `can_view_title(user_id, genres, status)`.

| Caller | Access | Condition |
| --- | --- | --- |
| Authenticated owner (`auth.uid() = titles.user_id`) | full CRUD | always |
| Authenticated friend, `is_friend(auth.uid(), owner) = true` | read-only | subject to `share_scopes` narrowing for that friend (`allowed_genres`/`allowed_statuses`); absence of a scope row = unrestricted |
| Anonymous / authenticated non-friend with a valid shared token (`set_shared_token` called first) | read-only | token must be `is_active = true` and unexpired; subject to `share_scopes` narrowing for that link |
| Anonymous / authenticated non-friend, no valid token | **no rows returned** | RLS `can_view_title` evaluates false — not a 403, an empty result set (PostgREST/Supabase RLS behavior) |
| Unauthorized direct REST (no session, no shared token header) | **no rows returned** | same as above — `select` succeeds with 0 rows, not an error |

Android must not treat "0 rows" from an RLS-filtered read as an error state distinct from
"empty library" — the two are indistinguishable at the HTTP layer by design. If Android
needs to warn the user their session/token is invalid, it must check auth state
separately (`getCurrentUser()` / token validity), not infer it from an empty list.

## 4. Idempotency

Insert path (`insertTitleToDb`) is keyed on the client-generated `title.id` plus the
`unique_user_tmdb (user_id, tmdb_id, type)` constraint — a retried "add to library" for the
same TMDB id/type either no-ops (if the same client id is resent) or hits `23505` (if a
*different* client id collided on the same `tmdb_id`+`type`, e.g. two devices adding the
same movie offline). Android's write path must treat that specific `23505` as "already in
library," not a generic failure, and reconcile to the existing row's id rather than
retrying with a new one.

## 5. Android route states

Route: Library (poster wall / list).

- **Loading:** first paint before Room has any rows *and* no cached `syncedAt` cursor exists
  (cold start). Once a cursor exists, render cached rows immediately and sync in the
  background — never block the list on network.
- **Empty:** authenticated, sync completed at least once, zero titles. Distinct from "not
  yet synced" — an empty state must not flash before the first sync completes.
- **Offline:** no network; render the last-synced cached list with a non-blocking staleness
  indicator (no explicit `lastSyncedAt` surfaced to the user in the web app today, so
  Android's indicator is new UI, not a ported one — keep it low-key, e.g. a small icon, not
  a banner).
- **Recoverable error:** sync RPC fails (network/5xx) — retry with backoff, keep showing
  cached data; only surface an error UI if the cache itself is empty and sync keeps failing.
