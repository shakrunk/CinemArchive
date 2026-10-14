# Private moviegoing preferences

Venue logistics notes and interest in seeing a movie in theaters belong to the signed-in
owner. They are separate from outing notes and from a booked trip. Neither table is part
of friend or anonymous shared-library snapshots.

## Shared storage and commands

`venue_notes` has a server UUID, owner, exact trimmed venue name, notes, and creation/update
timestamps. `(user_id, venue)` is unique. Case is preserved: two differently spelled venue
names are not silently merged. Names are 1–512 characters with no outer ASCII spaces;
notes permit an empty string and at most 20,000 characters. Client inputs must be validated
before admission. An empty note is a stored value; removing a note is an explicit delete.

Use the existing `apply_library_command` endpoint and immutable operation IDs:

```json
{"table":"venue_notes","action":"insert","key":{"venue":"O'Brien Cinema"},"values":{"notes":"Park opposite"}}
```

Updates and deletes require one observed `expectedUpdatedAt` or preceding matching
`expectedOperationId`. A concurrent first save with different text returns `23505`, retains
the existing note, and stores no accepted receipt. Equal text can adopt its canonical ID.
After acceptance, the original receipt is replayed even if the current note changed or was
removed. Clients must read the current owned row before projecting an ACK; historical
receipts do not recreate current data. Rejected edits need explicit comparison; uncertain
outcomes retain the original operation and body for confirmation.

`theater_interest` represents desired presence. Its `id` and `title_id` are both the exact
owned title UUID. Insert values include `title_id` and may preserve `created_at`; delete
values are empty. There is no update/put action. Another owner's title cannot be used.
Title deletion cascades interest deletion. These flags never create an outing, change a
title's status, or alter a release date. A scheduled outing suppresses duplicate watchlist
cards; scheduling prompts follow the existing released-movie rules.

Both tables allow owner-only authenticated reads. Writes use the receipt endpoint;
authenticated direct insert/update/delete and anonymous access are denied. Private
function execution still derives its owner from `auth.uid()`. Compound failures roll back
all writes and the receipt placeholder.

## Incremental sync

`sync_library_changes` adds `venue_note` and `theater_interest` entity types. Venue payloads
contain `id`, `venue`, `notes`, `createdAt`, and `updatedAt`. Interest payloads contain `id`,
`titleId`, `createdAt`, and `updatedAt`; their parent ID is the title. Both carry
`moviegoingPreferencesVersion: 1`; title payloads also carry this capability marker.
Existing title timestamps do not change merely to advertise the capability.

Deletion emits an owner-private tombstone with the corresponding entity type. Venue
mirrors must retain the canonical server UUID so its tombstone can remove the correct
natural-key row. Interest tombstone identity is the title UUID. Re-added interest uses
the same identity, so clients must process changes in revision order and preserve pending
intent. A page can include both historical tombstones and newer rows for an identity.

New clients must backfill from epoch, protect pending natural keys/identities during pulls,
and persist replay before ACK or recovery removes protection. Missing capability on an
older server is not evidence that local notes or flags should be cleared.

## Existing Android data and remaining integration

The preexisting Room notes and flags are local-only and must survive upgrades. They have
no observed server revision. Do not manufacture one from local timestamps or overwrite
newer server notes. First admission must either create a missing value or compare/review
an existing conflicting value; retain the original until confirmed. Unknown-owner legacy
archives remain outside automatic sync. Backup readers retain their historic `localOnly`
representation as source data; restoring it to shared storage requires the same explicit
owner-scoped admission.

This backend checkpoint does not activate either client. Native admission/recovery and
projection, web durable journal and controls, migration of local-only values, shared backup
mapping, and cross-client device acceptance are still required.

## Verification

`apps/web/scripts/moviegoing-preferences.test.mjs` executes the migration in PostgreSQL
fixtures and covers natural identity, owner/anonymous boundaries, direct-write denial,
literal/causal edits, competing first-save review, immutable receipts, compound rollback,
capability payloads and cascading deletion. The fixtures do not prove live pooled-HTTP
concurrency or a signed-in cross-device journey.
