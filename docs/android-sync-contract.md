# Android sync contract (implemented)

Status: **implemented in `schema.sql` and `supabase/migrations/20260713000000_android_sync_layer.sql`**,
validated end-to-end against a non-production Supabase project (`cinemarchire-android-test`,
ref `rgnthbiigfbfiuehteoe`, `us-west-1`, $0/month) both before and after being written as a
real migration file. Not yet live in production — the migration ships to production when
this branch's `dev` → `main` PR merges and `db-migrate.yml` runs `supabase db push`. This
document satisfies the first three "Immediate contract tasks" in
`docs/android-parity-matrix.md`: the bootstrap/incremental-sync cursor strategy, the
`updated_at`/tombstone behavior, and idempotency/atomic-command contracts for writes.

**Validation performed (2026-07-12/13):** all 33 production migrations from
`supabase/migrations/` were applied to the test project (via `supabase db push --linked`,
temporarily re-linking the local CLI — `supabase/.temp/project-ref` is gitignored, so this
never touched the committed repo), followed by the §3.1/§3.2/§2.2 schema below. Exercised
end-to-end with a synthetic test user: insert → `sync_library_changes` bootstrap read
(all 6 entity types returned correctly-shaped payloads), update → incremental pickup
(cursor since a mid-point timestamp returned only the changed row with the new value),
delete → tombstone (the deleted row's id appeared as an `entity_type: 'tombstone'` row),
and an RLS isolation check (a second, unrelated `auth.uid()` saw 0 rows). One real bug was
found in the process — see §3.3. After the fix, the test project's `public` schema was
fully dropped and all 34 migrations (33 production + the new file) were replayed from
scratch to confirm the *committed file itself* — not just the ad-hoc SQL used to iterate on
it — applies cleanly and behaves correctly.

## 1. Current state (as verified against source, 2026-07-12)

- The web client has no incremental sync today. `fetchUserLibrary()` (`src/lib/db.ts:236`)
  does a full `select *` over `titles` (with nested `seasons`/`episodes`/cast/crew/viewings)
  filtered by `user_id`, every time. There is no cursor, no pagination, no `since` parameter.
- Only three tables carry `updated_at` with a trigger: `titles`, `share_scopes`,
  `user_prefs`, plus `title_comments` and `cinema_outings`. `seasons`, `viewings`,
  `episodes`, `episode_watch_events`, `episode_ratings`, `episode_reviews`, `title_cast`,
  `title_crew`, `season_cast`, `episode_crew` have **no** `updated_at` column at all.
- Deletes are hard deletes with `on delete cascade` (e.g. `deleteTitleFromDb`,
  `deleteViewingFromDb`, `deleteEpisodeWatchEventFromDb` in `src/lib/db.ts`). There is no
  tombstone table anywhere in `schema.sql`. A client that cached rows locally has no way to
  learn a row was removed except by re-fetching the full parent set and diffing.
- Client-generated UUIDs are already the norm for inserts (`title.id`, `v.id`, `ep.id`,
  `opts.watchEventId` in `logEpisodeToDb`) — this is a foundation idempotency already relies
  on implicitly (a retried insert with the same id becomes a duplicate-key error today,
  not a silent double-write), but nothing currently treats that error as "already applied."

Android cannot build correct offline sync against full-refetch-and-diff at scale (the web
app can afford it because it's an online-first SPA against a small personal library; a
mobile client wants incremental reads and needs to distinguish "row missing because it
was deleted" from "row missing because of a network hiccup"). The sections below define
what to add.

## 2. Bootstrap + incremental sync

### 2.1 Bootstrap (first sync / re-install / cache-cleared)

Reuses today's full-library shape — no new endpoint needed for the initial pull:

- `GET`-equivalent: `supabase.from('titles').select(TITLE_SELECT).eq('user_id', userId)`
  (same query `fetchUserLibrary` already runs), plus `cinema_outings` for the owner.
- Response: `{ titles: Title[], outings: CinemaOuting[], syncedAt: string }` where
  `syncedAt` is the server's `now()` **captured before** the query runs (see §2.3 on why).
- Android persists `syncedAt` as the sync cursor after a successful bootstrap.

### 2.2 Incremental sync

New RPC: `sync_library_changes(p_since timestamptz, p_limit integer default 500)`.

- `SECURITY DEFINER`, scoped to `auth.uid()` — no `user_id` parameter, mirrors the
  `list_notifications`/`friend_activity_feed` pattern already in `schema.sql`.
- Returns one row per **changed entity** since `p_since`, across all synchronizable
  tables, keyset-paginated on `updated_at` (ties broken by `id`):

  ```
  entity_type   text      -- 'title' | 'season' | 'episode' | 'viewing' |
                           -- 'episode_watch_event' | 'episode_ratings' | ... | 'tombstone'
  entity_id     uuid
  parent_id     uuid      -- title_id, for entities that need it to route locally
  updated_at    timestamptz
  payload       jsonb     -- null when entity_type = 'tombstone'
  ```

- Client loop: call with `p_since` = last cursor, apply the page, advance the cursor to
  the last row's `updated_at`, repeat while a full page (`p_limit`) came back. Empty/partial
  page means caught up.
- `p_since` is exclusive; the row-level cursor is `(updated_at, id)` to make pagination
  stable when multiple rows share a timestamp (sub-millisecond writes in a batch import are
  the case that breaks a naive `updated_at`-only cursor).

### 2.3 Clock source and the "boundary write" problem

`p_since` must be the **server's** clock, never the device's. Bootstrap returns `syncedAt`
captured at query start (before reads) specifically so that a write which commits between
"data read" and "timestamp read" is never missed — capturing after the read risks
skipping a write that landed in that window. `complete_due_outings` and friend-request RPCs
already run server-side and stamp `now()`, so this is consistent with the existing pattern.

## 3. `updated_at` and tombstones

### 3.1 `updated_at` — extend to every synchronizable table

Add `updated_at timestamptz not null default now()` + the existing `update_updated_at()`
trigger to every table currently missing it that Android needs to sync:
`seasons`, `episodes`, `viewings`, `episode_watch_events`, `episode_ratings`,
`episode_reviews`, `title_cast`, `title_crew`, `season_cast`, `episode_crew`.

Rationale for the *watch/rating/review* tables specifically: these are append-mostly
(`logEpisodeToDb` inserts, never updates in place), so `created_at`/`rated_at`/`reviewed_at`
already double as a change marker for **inserts**. But `updated_at` is still needed because
`insert ... on conflict do update` upserts exist elsewhere (`user_title_pins`,
`share_scopes`) and because a future edit path (e.g. correcting a watch date) would
otherwise be invisible to sync.

`cinema_outings` already has `updated_at` — no change needed there. Cinema outings stay
**owner-only** per the existing privacy stance (§9 in `schema.sql`'s comments); the sync
RPC only ever returns them to the authenticated owner (already true, since it's
`SECURITY DEFINER` scoped to `auth.uid()`, not to a shared token).

### 3.2 Tombstones

New table:

```sql
create table sync_tombstones (
  id            uuid primary key default gen_random_uuid(),
  -- deliberately NOT a foreign key to auth.users — see §3.3
  user_id       uuid not null,
  entity_type   text not null,   -- matches sync_library_changes.entity_type values
  entity_id     uuid not null,
  deleted_at    timestamptz not null default now()
);

create index sync_tombstones_user_deleted_idx on sync_tombstones(user_id, deleted_at);
```

- Every existing hard-delete function (`deleteTitleFromDb`, `deleteViewingFromDb`,
  `deleteEpisodeWatchEventFromDb`, `deleteOutingFromDb`, cascade deletes of seasons/
  episodes/cast/crew when a title is deleted) additionally inserts a tombstone row for the
  deleted entity **and every cascade-deleted child** before or in the same statement as the
  delete. This means the cascade-delete triggers need a `before delete` trigger per child
  table (mirroring `update_updated_at`) rather than relying on the client to enumerate
  children — the client only ever calls `deleteTitleFromDb`, it doesn't know which seasons/
  episodes/cast rows existed.
- Retention: tombstones are pruned after 90 days (a scheduled job, out of scope for this
  doc) — a client that hasn't synced in 90 days falls back to a full bootstrap rather than
  incremental sync. `sync_library_changes` should reject `p_since` older than the retention
  window with a distinguishable error (e.g. `p_since_expired`) so Android knows to
  re-bootstrap instead of silently missing deletes.
- `sync_library_changes` unions tombstones into its result stream (`entity_type = 'tombstone'`
  rows carry `entity_id` + the *original* `entity_type` in `payload->>'entity_type'`, no
  other payload) so deletes and upserts interleave in one cursor-ordered stream instead of
  needing two separate polling loops.
- RLS: owner-read only, no client insert/update/delete policy — every row is written by
  `record_tombstone()` (`SECURITY DEFINER`), the same single-choke-point pattern
  `schema.sql` already uses for `friendships`/`recommendations`/`notifications`:
  ```sql
  alter table sync_tombstones enable row level security;
  create policy "sync_tombstones: owner read"
    on sync_tombstones for select
    using (auth.uid() = user_id);
  ```

### 3.3 Bug found during validation: account-deletion FK cascade

Discovered by testing against the `cinemarchive-android-test` project (§ validation note
above), not by inspection — deleting a synthetic test user's `auth.users` row (simulating
account deletion) threw:

```
ERROR: 23503: insert or update on table "sync_tombstones" violates foreign key
  constraint "sync_tombstones_user_id_fkey"
DETAIL: Key (user_id)=(...) is not present in table "users".
CONTEXT: ... record_tombstone() ... DELETE FROM ONLY "public"."titles" ...
```

**Root cause:** the original design had `sync_tombstones.user_id references auth.users(id)
on delete cascade`. Deleting a user cascades into `titles` (and its children), which fires
the `record_tombstone()` `BEFORE DELETE` trigger — but that trigger inserts a *new*
`sync_tombstones` row referencing a `user_id` whose `auth.users` parent is being deleted in
the same cascade chain, and Postgres doesn't guarantee that insert lands before the FK
parent is gone.

**Scope:** does not affect any normal write path — deleting a single title/episode/viewing
while the account still exists works correctly (verified). Only whole-account deletion
breaks.

**Fix applied (validated):** drop the foreign key on `sync_tombstones.user_id` entirely (see
the `sync_tombstones` DDL in §3.2 above, already updated). A tombstone row surviving after
its owning account is deleted is harmless — no client will ever sync as that user again, and
there's no cascade left to protect. This was re-tested end-to-end after the fix: user
deletion (after first removing the user's own rows, which is the app's actual account-
deletion order regardless) completes cleanly.

## 4. Idempotency and atomic-command contracts

### 4.1 What's already idempotent

Client-generated UUIDs on insert make **retries of the same insert** naturally idempotent
today for: `titles`, `seasons` (via `unique_title_season`), `episodes` (via
`unique_episode`), `viewings`, `episode_watch_events` (when `watchEventId` is supplied),
`title_cast`/`title_crew`/`season_cast`/`episode_crew` (via their unique constraints +
`upsert`). A retried write either upserts cleanly or hits a `23505` unique-violation that
the caller can treat as "already applied" rather than a real error.

Gaps: `episode_ratings` and `episode_reviews` inserts (`logEpisodeToDb`) have **no** unique
constraint and **no** client-supplied id — a retried call after a dropped response creates
a duplicate rating/review. This is the concrete gap Android's write path must not inherit
silently.

### 4.2 Contract for new Android writes

Every Android write RPC/insert must satisfy:

1. **Idempotency key = client-generated UUID**, sent as the row's primary key (not a
   separate header/param). This matches the existing web convention exactly, so both
   clients can share the same server-side conflict semantics.
2. **Retries upsert, they don't duplicate.** Any table Android writes to must have either
   a primary key it controls (already true for id-bearing tables) or an explicit unique
   constraint keyed on a natural key (fixes the `episode_ratings`/`episode_reviews` gap —
   proposal: add `unique (episode_id, user_id, rated_at)` is too fragile since retries
   would carry a *new* `rated_at` on the client if it stamps client-side; the fix instead
   is for Android to always generate and send the row id client-side for these two tables,
   same as `episode_watch_events.id`, and add `id` as an explicit upsert target
   (`on conflict (id) do nothing`) rather than a bare `insert`).
3. **Multi-row commands are one RPC, not N client calls.** `complete_due_outings` and
   `share_outing_plans` already model this correctly — a mutation that touches more than
   one table (e.g. "log episode watched + rated + reviewed in one action") must be a single
   `SECURITY DEFINER` function so a dropped connection mid-sequence can't leave partial
   state that a client-side retry then double-applies to the *other* half.
4. **Conflict resolution is last-write-wins by `updated_at`**, matching the trigger-driven
   `updated_at` this doc adds everywhere. Android does not need vector clocks or per-field
   merge for v1 — the parity matrix's own domains (Library, Title detail, Episode tracking)
   are single-owner, single-device-at-a-time in practice; multi-device conflicts are rare
   enough that LWW is an acceptable v1 answer, revisited only if usage data says otherwise.
5. **Errors are structured, not just thrown.** Today's client maps `23505`/`42501` to
   friendly messages ad hoc per call site (`createInviteCode`, `updateMyProfile`). Android's
   contract needs the same Postgres error codes documented per-endpoint (§5 fixtures) rather
   than inventing new ones, so both clients degrade the same way.

## 5. Fixtures and RLS matrices

See `docs/android-contracts/` for the JSON fixtures and RLS authorization matrices for the
three domains closest to ready (Library, Title detail, Episode tracking). Domains still at
"Discovery" (Discover/add, Up Next, Ledger, Sharing, Friends, Notifications) are out of
scope for this pass — see `docs/android-parity-matrix.md` for what's left.
