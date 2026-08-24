# Lists contract

Web sources: `apps/web/src/lib/db.ts` (`fetchLists`, `fetchListMemberships`, `mapDbListToLocal`),
`apps/web/src/views/Lists.tsx`, `apps/web/src/components/AddToListSheet.tsx`,
`apps/web/src/store/useAppStore.ts`'s `ListsSlice`. Backend: `lists`/`list_items` tables +
`sync_library_changes` RPC's `list`/`list_item` arms (`supabase/migrations/20260821000000_lists.sql`).

Private-only for v1 — no sharing with friends yet. A title can belong to any number of lists;
list membership is fully independent of `titles.status` (watched/watchlist/watching/dropped).

## 1. Fields, enums, timestamps

`lists` row:

| Field | Type | Source column | Notes |
| --- | --- | --- | --- |
| `id` | uuid | `lists.id` | client-generated on insert |
| `name` | text | `lists.name` | |
| `description` | text? | `lists.description` | nullable |
| `createdAt` | timestamptz | `lists.created_at` | |
| `updatedAt` | timestamptz | `lists.updated_at` | sync cursor field (§2) |

`list_items` row (the many-to-many join):

| Field | Type | Source column | Notes |
| --- | --- | --- | --- |
| `id` | uuid | `list_items.id` | client-generated; **not** a composite key (unlike `user_title_pins`) because this table is synced — see the migration's own comment for why |
| `listId` | uuid | `list_items.list_id` | FK to `lists.id`, cascade delete |
| `titleId` | uuid | `list_items.title_id` | FK to `titles.id`, cascade delete |
| `position` | integer? | `list_items.position` | reserved for a future manual-reorder feature; v1 always null, UI orders by `addedAt` |
| `addedAt` | timestamptz | `list_items.added_at` | |
| `updatedAt` | timestamptz | `list_items.updated_at` | sync cursor field (§2) |

Server enforces `unique(list_id, title_id)` — no duplicate membership. Writes should upsert
against that constraint (`onConflict: 'list_id,title_id', ignoreDuplicates: true` on web;
`findId` pre-check on Android) rather than relying on catching a 409.

## 2. Pagination / sync

- **Bootstrap:** unfiltered `select` over the caller's `lists` and `list_items` — same
  no-pagination rationale as Library (§2, `library.md`).
- **Incremental:** `sync_library_changes(p_since, p_limit)` gained two arms, `'list'` and
  `'list_item'`, each carrying the field set above in `payload`. `list_item`'s `parent_id` is
  its `list_id` (informational only — Android re-derives dependency from `payload` fields, same
  as every other entity).
- Android's `SYNC_SCHEMA_VERSION` bumped to 6 for these two brand-new arms (no real backlog —
  bumped anyway per that constant's own audit-trail policy).
- `list_item` is the first entity with **two** independent parents (`list` and `title`) rather
  than one — `LibrarySyncRepository.DeferredRows` holds a row back until both have landed
  locally, not just one.

## 3. RLS authorization matrix

Source: `schema.sql` policies `"lists: owner full access"` and `"list_items: owner full
access"` — single `for all using (auth.uid() = user_id) with check (auth.uid() = user_id)`
policy on each table, no friend/shared-link read policy (private-only for v1).

| Caller | Access | Condition |
| --- | --- | --- |
| Authenticated owner (`auth.uid() = user_id`) | full CRUD | always |
| Authenticated friend | **no rows returned** | no read policy exists yet — a future sharing pass needs one, mirroring `shared_access_keys`/`share_scopes` |
| Anonymous / shared-token holder | **no rows returned** | same — private-only |

## 4. Idempotency

- `lists`: id-keyed upsert, same shape as `titles`' own update path.
- `list_items`: id-keyed upsert plus the `unique(list_id, title_id)` constraint — a retried
  "add to list" for the same list+title either no-ops (same client id resent) or is rejected
  by the unique constraint (different client id, e.g. two devices adding the same title to the
  same list offline); the write path should treat that as "already a member," not a failure.
- Deletes (`removeTitleFromList`/`deleteList`) are plain id-keyed DELETEs — deleting an
  already-gone row is a 0-row success, not an error.

## 5. Android route states

Route: Lists tab (`feature:lists`'s `ListsRoute`) and the "Add to list" sheet
(`core:designsystem`'s `AddToListSheet`, opened from Title detail).

- **Loading:** first paint before Room has any `lists` rows and no sync has completed yet —
  same cold-start treatment as Library.
- **Empty:** zero lists — a dedicated empty state with a "Create your first list" action, not a
  bare blank screen.
- **Offline:** list/membership writes are Room-first + outbox, so creating a list or toggling
  membership works offline and syncs once connectivity returns — no offline-specific UI beyond
  the outbox's existing retry behavior.
- **Recoverable error:** sync RPC failure — retry with backoff, keep showing cached lists; only
  surface an error UI if the cache is empty and sync keeps failing (matches Library §5).
