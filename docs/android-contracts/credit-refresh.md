# Provider credit refresh

`apply_library_command` accepts `put` for `title_cast`, `title_crew`, `season_cast`
and `episode_crew`, in addition to its existing preference tables. This is a
metadata refresh operation, not a general entity upsert.

| Table | Natural identity | Parent requirement |
| --- | --- | --- |
| `title_cast` | `title_id`, `tmdb_person_id` | Owned title |
| `title_crew` | `title_id`, `tmdb_person_id`, `job` | Owned title |
| `season_cast` | `season_id`, `tmdb_person_id` | Owned season under the supplied `title_id` |
| `episode_crew` | `episode_id`, `tmdb_person_id`, `job` | Owned episode under the supplied `title_id` |

Values use the existing command field allowlists. Include `title_id` when putting
season or episode credits so absent rows can be inserted. An existing credit's
parent and UUID never change. Supplied metadata updates only its named fields;
omitted fields remain, while explicit JSON null clears nullable fields. A missing
credit gets a server UUID. The response includes the natural key and canonical
row for every operation. Foreign-owner legacy rows occupying a natural identity
cause a conflict rather than being adopted or overwritten. All operations and
their receipt commit together, or all roll back.

Persist an immutable operation UUID and payload before dispatch. Replaying that
operation returns its original receipt, even if another device subsequently
updates or deletes the credit. A *new* refresh may insert a previously removed
provider credit; retrying an old accepted refresh cannot resurrect one.

Clients reconcile provisional credit UUIDs by exact natural identity and owner in
the same local transaction that acknowledges the queued command. Receipt metadata
describes the accepted operation, not necessarily current server state: it must
not overwrite a later local refresh or recreate a locally removed row. Keep
pending title-scoped credit changes protected from pull until reconciliation;
unknown server UUIDs can otherwise duplicate provisional rows. Existing parent
identities, watch history, user notes, ratings and tags remain outside this refresh.

The ordinary causal `expectedOperationId` wrapper remains unchanged for updates
and deletes. Credit `put` does not imply an optimistic version precondition;
these fields are provider metadata. User-authored entities still reject `put`.

Evidence: `apps/web/scripts/library-command.test.mjs` exercises every credit table,
identity retention, explicit clears, retry after edits/deletion, parent and owner
fences, rollback, restricted entities and causal command compatibility. These are
local PostgreSQL fixture tests, not a claim of live simultaneous HTTP testing.

## Missing catalog parents

Migration `20261008214554_ensure_episode_catalog_parents.sql` adds `ensure` for
`seasons` keyed by `{title_id, season_number}` and `episodes` keyed by
`{title_id, season_number, episode_number}`. Missing rows receive server UUIDs;
existing owned rows return unchanged, including their UUID, metadata, progress
and timestamps. Season values may contain `episode_count` and `air_year`;
episode values may contain `episode_name`, `air_date`, `runtime`, `synopsis` and
`still_url`. Progress, history, explicit UUIDs and revision guards are rejected.
The owned title and, for an episode, its owned natural season are locked through
insertion. A foreign legacy row occupying a natural identity is a conflict.

Ensure parents before credit operations. Persist the request before dispatch;
receipts return exact natural keys and canonical rows. Retrying an accepted
operation never recreates a parent deleted later. A new intentional ensure may
create a new parent identity. Clients therefore fetch current owned parents
before local acknowledgment, materialize only confirmed live parents, and queue
their credit commands atomically using those canonical IDs. Never expose guessed
editable parent IDs or infer watches from coarse progress. Library command tests
cover canonical adoption, untouched history/progress/metadata, noncontiguous
Specials, rollback, deleted-parent retries and ownership conflicts.
