# Shared library RPC — `get_shared_library`

Migration: `supabase/migrations/20261008180000_shared_library_rpc.sql`
Tests: `node apps/web/scripts/shared-library-rpc.test.mjs` (PGlite, local only, from the repo root).
Replaces the session-GUC mechanism analysed in [sharing-token-analysis.md](sharing-token-analysis.md).

## Request

PostgREST RPC `POST /rest/v1/rpc/get_shared_library` (anon key is enough; a signed-in JWT also works):

```json
{ "p_token": "<share token>", "p_offset": 0, "p_limit": 100 }
```

- `p_offset` default 0, clamped to `>= 0`. `p_limit` default 100, clamped to `[1, 200]`. `NULL` = default.
- Pass `p_offset = 0` for the first page. **Only a call with `p_offset = 0` counts as a "use"**
  (touches `last_used_at`, may notify the owner); later pages do not.

## Response (HTTP 200, one JSON object)

```json
{
  "ownerUserId": "uuid",
  "titles": [ { "...titles.* columns, snake_case, exactly as today...",
                "title_cast": [], "title_crew": [],
                "seasons":  [ { "...seasons.*": 0, "season_cast": [] } ],
                "viewings": [],
                "episodes": [ { "...episodes.*": 0, "episode_crew": [], "episode_watch_events": [],
                                "episode_ratings": [], "episode_reviews": [] } ] } ],
  "ledgerLayout": [ { "id": "w1" } ],
  "hasMore": false
}
```

The `titles[]` element is byte-for-byte the shape PostgREST returned for
`titles.select(TITLE_SELECT)` (`apps/web/src/lib/db.ts`): `to_jsonb(row)` for the title and every child
(so `updated_at`, `user_id`, `outing_id`, `venue`, `companions` on viewings, etc. are present as before),
child collections nested under the table names, empty collections as `[]` (never `null`). The existing
web `mapDbTitleToLocal` and the Android mapper can consume each element unchanged.
Child ordering is deterministic: cast by `cast_order,id`; seasons by `season_number`; episodes by
`season_number,episode_number`; viewings by `viewed_at desc nulls last`; event/rating/review logs ascending.
`ledgerLayout` is the owner's `user_prefs.ledger_layout`, or `null` (no row / null). It is returned on every page
and is **not** narrowed by link scope (same as the old `user_prefs` shared-key policy).
`ownerUserId` is always present for a valid link, even when `titles` is `[]` (empty library, or the
link's `share_scopes` filter everything out).

### Example

```json
{"ownerUserId":"7f0c…","hasMore":true,"ledgerLayout":null,
 "titles":[{"id":"…","user_id":"7f0c…","tmdb_id":1399,"type":"tv","title":"Game of Thrones","genres":["Drama"],
   "status":"watched","added_at":"2026-01-03T00:00:00+00:00",
   "title_cast":[{"name":"Emilia Clarke","cast_order":0}],"title_crew":[],
   "seasons":[{"season_number":1,"season_cast":[]}],"viewings":[],
   "episodes":[{"season_number":1,"episode_number":1,"episode_crew":[],"episode_watch_events":[],
                "episode_ratings":[],"episode_reviews":[]}]}]}
```

## Errors

| Condition | Result |
|---|---|
| token NULL / empty / > 512 chars / unknown / `is_active = false` / `expires_at <= now()` | SQLSTATE `42501`, message exactly `Invalid or expired share link`. PostgREST maps 42501 to HTTP **401** for `anon` and **403** for an authenticated JWT; the JSON body carries `"code":"42501"`. Clients should branch on `code == "42501"` (not on HTTP status). All failure causes are deliberately indistinguishable. |
| valid token, empty / fully filtered library | 200, `titles: []`, `hasMore: false` |
| offset past the end | 200, `titles: []`, `hasMore: false` |
| removed `set_shared_token` call (old clients) | SQLSTATE `42501`, message `set_shared_token has been retired; call get_shared_library(p_token) instead` |

## Pagination contract

Order is `added_at DESC, id ASC` (id is the tiebreaker, so ties never reorder between pages). The server
fetches `limit + 1` rows to compute `hasMore` exactly (no phantom empty last page). Loop:
`offset += titles.length` while `hasMore`. Offset paging is not snapshot-isolated: titles added/removed by
the owner between pages can shift rows (a dupe or a skip is possible); de-duplicate by `id` and re-fetch
from 0 on pull-to-refresh. Cap: 200 titles per call; the nested graph for 200 large TV shows can be many MB,
so the default (100) is recommended and mobile clients may use 50.

## What is included / excluded

Included (allow-list, children restricted to the owner **and** the visible title, and the link's
`share_scopes` genre/status filter applies to the title and therefore every child): `titles`, `title_cast`,
`title_crew`, `seasons`, `season_cast`, `episodes`, `episode_crew`, `episode_watch_events`,
`episode_ratings`, `episode_reviews`, `viewings`, plus the owner's `ledger_layout`.

Excluded: `cinema_outings` (and all ticket fields), `lists`/`list_items`, `notifications`, pins
(`user_title_pins`), friendships, recommendations, comments/reactions, `shared_access_keys`, `share_scopes`
rows, `sync_tombstones`, and anything else not in the list above. Because rows are serialised with
`to_jsonb`, a column **added later** to one of the included tables is exposed to share links automatically —
the same property the old `select *` had. Also note pre-existing exposure that is preserved, not new:
`titles.notes`, `physical_media`, and `viewings.companions` (may contain `friendUserId`) are visible to link
holders today. Redact them here (one place) if that is not intended.

## Security design

- `public.get_shared_library` is a `SECURITY INVOKER` SQL wrapper with `search_path = ''`; `REVOKE ... FROM
  PUBLIC`, explicit `GRANT EXECUTE` to `anon, authenticated`.
- The implementation `cinemarchive_sharing.get_shared_library` is `SECURITY DEFINER`, `search_path = ''`,
  fully qualified names, no dynamic SQL, also executable only by `anon, authenticated`. It lives in a new
  non-exposed schema (`cinemarchive_sharing`), **not** in the existing `cinemarchive_private`, because an
  INVOKER wrapper runs as the caller, so the caller needs `USAGE` on the schema and `EXECUTE` on the function;
  `cinemarchive_private` intentionally withholds `USAGE` from `anon`. The schema holds only this function
  (no tables) and `anon` cannot create objects in it. Do **not** add `cinemarchive_sharing` to the
  Data API "Exposed schemas" (`db-schemas`) setting. If you would rather have `anon` with no schema access at
  all, make the public wrapper `SECURITY DEFINER` and revoke the schema usage/function execute from the API roles
  — equally safe, and Supabase's database linter will then flag the wrapper (0028/0029).
- RLS no longer reads `app.shared_token`: `can_view_title` keeps only the friend branch, the `user_prefs: shared
  key read` policy is dropped, `is_valid_shared_token` loses its API EXECUTE grants. A stale or pooled GUC
  grants nothing (tested as `anon` and as another signed-in user after `set_config('app.shared_token', <valid>)`).
- `is_friend` was re-created with `search_path = ''` (identical logic) because `can_view_title` now has an
  empty search_path and `is_friend` inherited the caller's.
- Throttled notification: serialised by `SELECT ... FOR UPDATE` on the key row (first page only); validity is
  re-checked under the lock; unchanged "at most once per hour per key" semantics. Expired-but-active keys no
  longer notify (old bug). Contention: calls with `p_offset = 0` for the *same* key queue for the duration of one
  call; different keys do not interact.
- `set_shared_token(token)` is a stub: never calls `set_config`, reads/writes no tables, **raises 42501**. A silent
  no-op would let an old client "succeed" and then render a misleading empty library; a touch-only variant would
  keep a token-validity oracle and notification side effects for no benefit. Grants kept so old clients get a clear
  error instead of 404.
- `shared_key_owner(token_val)` is kept (stateless, checks expiry/revocation, now `search_path = ''`) so cached
  clients do not 404 during rollout; it is **deprecated** — drop it in a later migration.
- `shared_access_keys.user_id` now `DEFAULT auth.uid()` (see createSharedKey below).

## Client changes the parent must make

Web `fetchSharedLibrary(token)` (`apps/web/src/lib/db.ts`): delete the `set_shared_token` and `shared_key_owner`
calls; page `supabase.rpc('get_shared_library', { p_token: token, p_offset, p_limit })` until `hasMore` is false,
concatenate `titles` (dedupe by id), map each with `mapDbTitleToLocal`, return `{ titles, ownerUserId,
ledgerLayout }` (take `ownerUserId`/`ledgerLayout` from page 1). Replace the follow-up `fetchLedgerLayout(ownerUserId)`
in `useAppStore.ts:~1450` for the shared path with `normalizeLedgerWidgets(ledgerLayout)` (a direct `user_prefs`
select is no longer allowed for link holders). Remove `setSharedToken` in `auth.ts`. Treat `error.code === '42501'`
as "link invalid, expired or revoked". Android `SharingRepository` does the same (single anonymous POST per page, no
session state). Friend libraries are unchanged (`fetchFriendLibrary`, friend `user_prefs` read still allowed).

### createSharedKey `user_id` issue (confirmed from schema)

`shared_access_keys.user_id` is `NOT NULL` with no default and no trigger anywhere in `schema.sql` or
`supabase/migrations`, while `createSharedKey` (`auth.ts:93`) inserts only `label`/`expires_at`. With the owner
policy `with check (auth.uid() = user_id)`, the insert fails (`23502`/`42501`). **Not verified against the live DB**
(drift possible: it may already have a default there). The migration sets `DEFAULT auth.uid()` (RLS still stops
minting keys for another user), and the client should also send `user_id` explicitly (get it from the session) so
it works regardless of migration order.

## Rollout / ordering

1. Ship the web and Android clients that call `get_shared_library` **in the same release** as this migration.
2. If the **migration lands first**: existing deployed web/Android builds still call `set_shared_token`, which now
   raises 42501 (their share view shows an error), and even if they ignored it, direct `titles` reads for link
   holders return nothing. Shared links are unavailable until the new client ships. Friend sharing and owner flows
   are unaffected.
3. If the **clients land first** (new RPC missing): `rpc/get_shared_library` returns 404 (PGRST202); the new
   clients cannot fall back to the old mechanism. So the migration must be applied before, or atomically with,
   the client deploy; since step 2 breaks old clients, deploy the client immediately after the migration
   (`deploy.yml` runs `supabase db push` before Pages, which is the right order). After `db push`, run
   `NOTIFY pgrst, 'reload schema'` if the schema cache does not refresh on its own.
4. Later cleanup migration: drop `shared_key_owner`, `is_valid_shared_token`, and the `set_shared_token` stub.

## Canonical schema and verification

The complete migration is appended under `-- Shared library RPC (20261008180000)` in
`schema.sql`. That final block replaces the earlier function definitions and removes the
earlier token-based preferences policy when the canonical schema is executed top to bottom.
The local database suite checks that the block matches the migration exactly. Run both
database suites with `npm run test:db` from `apps/web`.

PostgREST gives each request its own transaction on a borrowed pooled connection; its
request settings are transaction-scoped. These guarantees do not make a separate session
`set_config` call a reliable source of authority for a later request. See the official
[transaction documentation](https://postgrest.org/en/stable/references/transactions.html) and
[connection-pool documentation](https://postgrest.org/en/v11/references/connection_pool.html).

## Unverified / assumptions

- Run only against PGlite 0.5.8 (single connection, superuser owner). Real concurrency of the `FOR UPDATE`
  serialisation was reasoned from Postgres semantics (READ COMMITTED re-evaluation after lock); the repeated-call
  test is sequential/queued and does not prove multi-connection contention behavior.
- Live-DB drift (policies/functions edited outside migrations) is unchecked; run
  `select policyname from pg_policies where qual ilike '%shared_token%'` against production before/after.
- Supabase specifics from knowledge, not re-verified offline: the function owner `postgres` bypasses RLS on its own
  tables; schemas not listed in the project's Exposed schemas are not reachable via PostgREST; new functions in
  `public` get default EXECUTE for `anon`/`authenticated`/`service_role` (hence the explicit revokes); PostgREST maps
  42501 to 401/403.
- Large payload: no server-side byte cap besides the 200-title clamp.
