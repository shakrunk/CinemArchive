# Private ticket attachment API

Implementation: migration `20261008190944_private_ticket_attachments.sql`. This is a backend contract checkpoint; client capture/viewers, native file migration and production rollout remain required for complete ticket parity.

## Immutable metadata and owner projection

`prepare_ticket_attachment(p_attachment_id uuid, p_outing_id uuid, p_metadata jsonb)` returns `{attachment,state}`. The attachment is permanently bound to its authenticated owner and outing. Metadata has exactly `mimeType`, `byteLength`, `sha256` and `barcode`. Supported originals are JPEG, PNG and WebP, 1 byte through 20 MiB. SHA-256 is lowercase hexadecimal; barcode is null or `{payload,format}` using the seven existing native formats, with a nonempty payload up to 32 KiB in UTF-8. The original image remains authoritative for undecodable or unsupported barcode content.

The returned descriptor adds `id` and `objectKey`. The private bucket is `ticket-attachments`; keys are `<owner UUID>/<attachment UUID>/original`. Upload once with overwrite disabled. Only the owner of a prepared descriptor may insert that exact key. Restrictive policies prevent unrelated permissive bucket policies from exposing or replacing ticket bytes.

`get_outing_ticket_attachments()` returns `{outingId,managed:true,attachment:descriptor|null}[]` for the current owner's managed outings. Omitted outings still use legacy behavior. A managed outing with a null attachment has been explicitly cleared: never fall back to an old filesystem path. Legacy fields remain available for explicit recovery, but generic outing writes cannot change managed association fields.

## Association and durable retries

- `finalize_ticket_attachment(p_operation_id, p_outing_id, p_attachment_id, p_expected_attachment_id)` associates a prepared upload.
- `detach_ticket_attachment(p_operation_id, p_outing_id, p_expected_attachment_id)` clears the association and sets managed mode even when clearing legacy content.
- `get_ticket_command_receipt(p_operation_id)` returns the original receipt or null. Call this before retrying preparation/upload, because a previously successful attachment may since have been replaced and retired.

Association uses an expected previous attachment ID, including explicit null. A competing replacement fails with SQLSTATE `40001` and keeps both captures available for recovery. Unrelated outing changes do not cause attachment conflicts. Finalize checks that Storage metadata exists and its recorded size and MIME match. SHA-256 is client-supplied integrity metadata, not a server attestation of the physical bytes; clients validate original bytes and verify an existing object's digest before accepting an upload collision.

Receipts contain `operationId`, `outingId`, `attachment`, `outingUpdatedAt`, `request`, and `rows`. `request` is `{kind,outingId,attachmentId,expectedAttachmentId}`, with a null attachment ID for detach. Clients must compare this immutable intent before acknowledging a receipt lookup. `rows` contains the canonical `cinema_outings` row in the existing library-command result format, allowing subsequent ordinary edits to depend on the ticket operation. The shared receipt namespace rejects operation UUID reuse across different commands.

## Retirement and cleanup

Replacing, detaching or deleting an outing retires its attachments without immediately deleting bytes. Cleanup is service-only: retired objects wait seven days, abandoned preparations wait thirty days, and active references are never claimed. Upload policy checks hold a metadata lock through insertion; finalize and cleanup also lock metadata so a delayed upload cannot restore an object after cleanup finishes.

`claim_ticket_attachment_cleanup(p_limit)` returns at most 100 `{attachmentId,objectKey}` records and marks them as deleting. Remove each object's physical bytes through the Storage API, then call `finish_ticket_attachment_cleanup(p_attachment_id)`. Finishing while object metadata remains fails. A failed claim becomes retryable after an hour. Immutable metadata tombstones and command receipts remain, preventing ID reuse and preserving interrupted-request recovery.

The daily GitHub workflow runs `scripts/cleanup-ticket-attachments.mjs` on `main`. It requires `SUPABASE_PROJECT_REF` and the server-only `SUPABASE_SERVICE_ROLE_KEY` GitHub secret. Neither the workflow nor any live deletion has been run during implementation. Deploy the migration before enabling this workflow and shipping the clients. No service key belongs in either client.

Owner account deletion is not implemented in either client; any future account-deletion flow must remove private Storage objects before cascading away owner metadata.

## Evidence and limits

Local embedded PostgreSQL tests cover descriptor immutability, ownership, metadata validation, storage policy fences, association CAS, receipt collisions, generic-write causal chaining, legacy preservation and cleanup states. The Storage fixture models database metadata/RLS, not physical storage or HTTP upload transactions. Node cleanup tests verify claim/delete/finish ordering and failure retention with synthetic HTTP responses. Production Storage transfer and cross-device offline journeys remain required before claiming end-to-end ticket parity.

The contract follows Supabase's [private-bucket access model](https://supabase.com/docs/guides/storage/buckets/fundamentals), [Storage access controls](https://supabase.com/docs/guides/storage/security/access-control) and [object deletion API](https://supabase.com/docs/guides/storage/management/delete-objects).
