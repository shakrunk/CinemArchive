# Native rich title storage

Room 18 adds nullable title columns for certification, external IDs/links, critic scores,
custom watch URL, home-collection membership, physical-copy JSON, awards and Bechdel data.
The migration only adds columns. Existing title revisions, histories, queues, recovery
receipts, completion aliases and ticket originals/associations/intents are unchanged.

`physicalMediaJson` retains the complete server array. Display projection preserves each
valid copy's stable ID, format, edition, notes and complete source object. Unknown format
names and extension fields survive storage and re-projection. Malformed legacy entries
remain in raw storage even when they cannot be displayed; an editor must update this raw
array deliberately rather than serialize only the visible subset and silently drop them.

Sync schema 10 replays from epoch until an actual title supplies `titleMetadataVersion: 1`.
An empty response cannot establish capability. The older person/tag capabilities remain
independent. Missing rich fields preserve local data during mixed backend versions;
explicit null, false, zero and empty arrays are real server values and clear/replace their
corresponding fields. A pending title write delays schema-10 acknowledgement so an older
legacy write cannot permanently hide unchanged rich fields during the backfill. The shared
wire contract is [title-metadata-sync.md](title-metadata-sync.md).

New-title creation stores the certification, IMDb ID and critic scores already fetched by
Android. Owner detail and isolated shared snapshots project the stored fields. Full-current
title metadata acknowledgements also retain them, preserving unrelated owner fields while
later edits remain queued. This checkpoint adds storage and projection; visible controls
and badge/link presentation are the following checkpoint.

Rollback keeps the expanded Room 18 schema and disables/reverts the new presentation or
writers; it must not drop these columns or downgrade an owner database to Room 17. No data
contraction or destructive downgrade is part of this change.

`RichTitleMigrationTest` validates the actual exported Room 17 title schema and retention
after upgrade/reopen. Earlier migration fixtures use their exported historical title table
instead of accidentally inheriting newer columns. `TitleRichMetadataTest`, the new-title
tests and `TransactionalRuntimeTest` cover mixed payloads, explicit clears, physical-copy
round trips, unchanged-row rollout and pending-write-safe backfill.
