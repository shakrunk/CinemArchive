# Older list membership recovery

New list edits use receipt-backed natural `(list_id, title_id)` commands. Older native queue entries used a surrogate membership UUID, which can disagree with another device's canonical row after a concurrent add.

Profile's **Saved list changes** preserves those older entries for explicit review. The background writer never silently converts or dispatches them. The user can compare membership, queue the saved add/remove action, discard only the queued change, or export the original.

- Original payload bytes and queue metadata are archived before changing Room. An archive failure leaves the queue intact.
- A reviewed replacement receives a fresh command ID but keeps its FIFO position. Later commands and their original bytes are preserved. The ordinary membership transport supplies receipt validation, exact retry and canonical reconciliation.
- A changed comparison requires another review. Missing local parents are not recreated. An older ID-only deletion can recover its identity from the exact local or owner-scoped remote row; an unavailable identity is never guessed from another membership.
- Discard does not undo an already delivered write. It reconciles current membership only when another pending membership does not own the local projection. Durable replay releases deferred pulls after discard.
- Pending older known memberships protect their natural identity. ID-only entries conservatively defer list-member pulls and tombstones until recovery, with epoch replay afterward. Other entity types continue syncing.
- Owner checks surround reads and local mutation. Originals remain exportable after resolution; resolved status derives from queue membership, so an interrupted archive-status update cannot cause a second application.

Local Room, transport and device evidence belongs in `docs/full-parity-execution.md`. This contract does not claim live multi-device acceptance.
