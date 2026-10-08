# Sharing: how the shared-link token reaches RLS (and whether Android can use it)

Status: historical analysis before the 2026-10-08 stateless RPC change. The replacement contract is
[shared-library-rpc.md](shared-library-rpc.md); the implementation below describes the retired flow.
Read from source only; **nothing here
was verified against the live Supabase project** (no network/DB access in the authoring session).
Android data layer: `data/.../SharingRepository.kt`, `SharingModels.kt`.

## 1. The mechanism

- The token is **not** a request header and not a JWT claim. `set_shared_token(token text)`
  (`security definer`, latest definition in `20260706090000_notifications.sql` / `schema.sql:286`) runs
  `set_config('app.shared_token', token, false)`. The third argument `false` means **session-level, not
  transaction-local**: the value lives on the *Postgres connection* until it is overwritten or the
  connection closes.
- RLS reads it back with `current_setting('app.shared_token', true)`:
  - `can_view_title(owner, genres, status)` (`schema.sql:381`) — used by the `titles`, `seasons`,
    `viewings`, `episodes`, `episode_*` (watch events, ratings, reviews, crew), and the cast/crew
    "shared/friend read" policies. It matches `shared_access_keys.token = current_setting(...)`,
    `user_id = owner`, `is_active`, not expired, **and** `title_in_scope(...)` against that link's
    `share_scopes` row (no row = unrestricted).
  - `user_prefs: shared key read` (`20260705000000_user_prefs.sql`) uses the older
    `is_valid_shared_token(current_setting('app.shared_token', true), user_id)` — **no scope filtering**;
    the whole row (today only `ledger_layout`) is exposed.
- No policy or function reads `request.headers` / `x-shared-token`; grep finds none.

## 2. How the web "makes it work", and why that is fragile

`fetchSharedLibrary` (`apps/web/src/lib/db.ts:263`) calls RPC `set_shared_token`, then in parallel
`select titles` and RPC `shared_key_owner`. These are **separate HTTP requests**. PostgREST runs each in
its own transaction on a connection taken from its internal pool, and does not reset session GUCs
between requests. So the `titles` GET sees the token **only if it is served by the same pooled DB
connection** that served the `set_shared_token` call. With low traffic and a small pool this usually
happens, which is why the web appears to work; it is not guaranteed. Consequences:

1. **Nondeterministic empty results** (a valid link intermittently showing no titles), more likely under
   concurrent load.
2. **Token leakage across requests**: the setting stays on that pooled connection afterwards, so an
   unrelated later request (any role) that lands on it can read the owner's scoped library. Low
   probability, but it is a real isolation weakness, and the token is not cleared after use.

Android has exactly the same constraint (stateless OkHttp REST calls, one transaction each), so it is
**no better and no worse than the web**. supabase-js adds nothing that changes this.

## 3. Can Android read a shared library with plain OkHttp REST calls?

Yes in the same best-effort sense as web, with no backend change needed to try. All calls are
anonymous: `apikey: <anon>` and `Authorization: Bearer <anon key>` (a signed-in session is not
required; policies apply to every role). Sequence implemented by `SharingRepository.fetchSharedLibrary`:

1. `POST /rest/v1/rpc/shared_key_owner` `{"token_val": "<token>"}` -> `"<owner-uuid>"` or `null`. Does
   **not** depend on the GUC (it takes the token as an argument), so it is reliable. `null` means
   unknown / revoked / expired -> `SharedLinkUnavailableException`; no titles are requested.
2. `POST /rest/v1/rpc/set_shared_token` `{"token": "<token>"}` -> 204/200 void.
3. `GET /rest/v1/titles?select=<light cols>&order=added_at.desc,id.asc&limit=1000&offset=N`.
   Steps 2-3 repeat per page (a page can land on another connection). Because an empty first page
   from a *valid* link is indistinguishable from the pool miss, the first page is retried (default 3
   attempts, re-calling step 2 each time); a genuinely empty/fully-scoped library costs two extra
   round trips. Pages after the first are not retried, so a pool miss mid-library could truncate it
   silently (only matters beyond 1000 titles; Supabase's default `max-rows` is 1000).

Owner Ledger board: after `set_shared_token`, `GET /rest/v1/user_prefs?select=ledger_layout&user_id=eq.<owner>`
(`fetchSharedLedgerLayout`), same caveat.

The HTTP details live behind the small `SharedLibraryTransport` interface (`rpc`, `get`), wired to
`SupabaseRestClient` with the anon key as bearer (the client already accepts any bearer string, so no
client change was needed). If the backend later adds a header-based or single-RPC path, only the
transport/repository internals change.

## 4. What the token exposes (server-side)

Per `share_scopes` for the link (`allowed_genres` overlaps `titles.genres`; `allowed_statuses` contains
`titles.status`; null = no restriction; **no row = unrestricted**), enforced in RLS so hidden titles
never reach the client: `titles`, `seasons`, `viewings`, `episodes`, `episode_watch_events`,
`episode_ratings`, `episode_reviews`, `episode_crew`, `title_cast`, `title_crew`, `season_cast`.
`user_prefs` (ledger layout) is exposed unscoped. Owner-private data stays private: `cinema_outings`,
`lists`/`list_items`, notifications, friendships (no shared policy). Note the Ledger derived from a
scoped link is computed from only the visible titles. Write access: none (owner-only policies).

## 5. Side effects and errors

- `set_shared_token` on an **active** key stamps `last_used_at = now()` and inserts a
  `share_link_used` notification for the owner (payload `{label}`), throttled to once per hour per
  key (`last_used_at < now() - 1h`). It is called once per page/retry, so the throttle matters.
  It does **not** check `expires_at`: an expired-but-active link still notifies and updates
  `last_used_at` even though reads then return nothing.
- Invalid / revoked / expired token: `set_shared_token` still returns success; reads return `[]` with
  HTTP 200; only `shared_key_owner` returns `null`. No error code distinguishes the three cases.
  A valid link whose scope hides everything yields `[]` with a non-null owner.
- Non-2xx (network, 5xx, bad anon key) surface as `SupabaseHttpException`.

## 6. Risks needing a backend change (not proposed here as edits; do not alter migrations in this pass)

1. **Session-level GUC over a pooled connection** (section 2): flaky reads + token leakage. Preferred
   fixes: (a) read the token from PostgREST's per-request `current_setting('request.headers', true)::json->>'x-shared-token'`
   in `can_view_title` / the `user_prefs` policy (needs custom-header support in `SupabaseRestClient` and
   in supabase-js `global.headers`), or (b) a `security definer` RPC such as `get_shared_library(token)`
   returning the scoped rows in one call, or (c) at minimum `set_config(..., true)` is *not* a fix on its
   own (it would be discarded before the follow-up request).
2. `createSharedKey` on web inserts `{label, expires_at}` **without `user_id`**, but
   `shared_access_keys.user_id` is `not null` with no default and I found no trigger supplying it.
   Either the live DB differs from the repo or web link creation fails. Android sends `user_id`
   explicitly (allowed by the owner `with check`), which is correct either way.
3. The 10-active-link cap is **client-side only** (web UI + Android repository); no DB constraint.
4. Expired-but-active keys still trigger notifications / `last_used_at` (section 5).

## 7. Mirrored web behaviour (Part B reference)

- Link format: `https://cinemarchive.kumarfamilynet.work/?share=<token>` (`apps/web/public/CNAME`, Vite
  base `/`; web builds it as `origin + pathname + ?share=`). Token = server default
  `encode(gen_random_bytes(32), 'hex')` (64 hex chars); the client never generates one.
- Expiry options: never / 24 h / 7 d / 30 d; `expires_at` = now + hours as ISO-8601.
- Cap: `is_active` keys >= 10 blocks creation (expired-but-active still count). Revoke = PATCH
  `is_active=false` (soft; row stays listed as revoked).
- `share_scopes` upsert `on_conflict`: links `shared_key_id`; friends `owner_user_id,friend_user_id`.
  Clearing scope deletes the row (never stores an allow-everything row).
