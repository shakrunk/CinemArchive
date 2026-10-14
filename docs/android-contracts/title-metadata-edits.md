# Native title tags and causal edits

The owner title detail editor saves tags locally and queues the same intent in one Room
transaction. New direct status and rating edits use the same path. Tags trim surrounding
whitespace and trailing commas, deduplicate exact-case strings, allow individual removal,
and require confirmation to clear all tags. Shared title views remain read-only.

## Command and acknowledgement

`title / metadata_v2` queues immutable `apply_library_command` requests for `titles / update`.
The allowed patch is `tags`, `status`, and `rating` (explicit null clears a rating). The first
command guards the observed server `updated_at`; later edits reference the preceding
command receipt with `expectedOperationId`. Optimistic edits never invent a server revision.
The operation ID and body survive retries and application restarts.

An accepted receipt validates the operation, owner, title key, and requested field values.
A separate current-owned read refreshes the projection; it never supplies an unseen revision
to a dependent command. Acknowledgement requires the exact queue head inside a Room
transaction. It preserves later queued edits and never resurrects a locally deleted title.
Failed local acknowledgement keeps the original request for another attempt. A failure
after receipt acceptance remains an uncertain retry, including errors from the current-row
read; it cannot become an editable conflict.

Existing legacy queued commands are unchanged. A new edit behind a legacy command becomes
a durable, never-dispatched review draft because that predecessor has no causal receipt.
The draft remains visible in title detail and Profile. Unknown outcomes only offer retry.
Definite pre-effect conflicts and never-dispatched drafts allow explicit comparison after
older same-title commands have finished.

## Explicit review

Comparison retains the exact displayed queue IDs and payloads. Applying saved edits uses
the displayed server revision and a fresh operation ID, replacing the earliest reviewed
FIFO slot and removing only the displayed, never-dispatched metadata descendants. Queue
changes invalidate the comparison. Other queued entries retain their order. Discarding
requires confirmation and preserves unrelated pending changes.

Sync persists an epoch cursor before flushing protected title edits. Recovery retry enters
that same serialized sync pipeline. Discard persists epoch under the sync mutex before it
removes queue protection, with sync-lock then outbox-lock ordering. An interrupted recovery
therefore still replays previously skipped unchanged rows and tombstones next time. A
missing server title cannot be recreated by applying a reviewed edit; its authoritative
tombstone is consumed by the replay.

The runtime supplies generation-fenced sessions and owner-specific storage. The new title
insert graph and remaining viewing/outing inline title producers are separate cutovers;
they must use this helper or an equivalent atomic causal command when activated, never
borrow optimistic timestamps as server revisions.

## Verification

`TitleMetadataRoomTest`, `TitleMetadataTransportTest`, and `TransactionalRuntimeTest` cover
causal chains, legacy drafts, rollback, immutable uncertain retries, queue-head enforcement,
explicit review, restart retention, account fencing, and durable replay after ACK/discard.
`TitleTagsEditorTest` shares the web tag normalization edge cases. `TitleMetadataScreenTest`
covers save failure/input retention, exact-case removal and clearing, title switching, and
explicit comparison/discard against a real Room queue on an Android device.
