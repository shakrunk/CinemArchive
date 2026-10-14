# Portable private ticket attachments

Status: implementation plan, not a parity claim. Audited 2026-10-08. No live backend changes were made for this plan.

## Current source contract

Both clients already edit and sync auditorium, row, seats, legacy seat and booking reference. Android additionally syncs `ticket_image_path`, `ticket_barcode_payload` and `ticket_barcode_format`; the web model, database mapper and offline journal omit those fields.

Android `OutingScheduleSheet` picks `image/*`, decodes with ZXing and writes original bytes to `filesDir/tickets/<outingId>.<extension>`. Those files are not owner scoped. Replacement overwrites the old file before Room persistence, capture callbacks do not await repository success, and no Storage uploader or bucket exists in source. The absolute local path is synced as text despite the original migration describing an object path. Clearing fields does not delete files. `TicketScreen` loads `File(imagePath)` and ignores decoded payload/format. Its no-photo fallback generates a QR from a booking reference, which does not establish a valid ticket barcode.

Room runtimes are owner scoped and retain pending work on sign-out. `LegacyArchive` preserves unknown-owner files and requires explicit recovery; attachment migration must preserve that policy. Android outings still use full-row upserts, so stale ordinary edits can overwrite ticket metadata unless that writer contract changes.

## Shared storage and API

- Add a private `ticket-attachments` bucket and owner-private `ticket_attachments` metadata: stable UUID, owner, immutable object key, MIME, byte count, SHA-256, creation time and upload/retirement state.
- Add nullable `cinema_outings.ticket_attachment_id` with owner-matching validation. Preserve legacy `ticket_image_path` for recovery and existing barcode fields during migration.
- Object keys are `<owner UUID>/<attachment UUID>/original`, without names, references or barcode content. Never overwrite an existing object.
- Implement idempotent prepare, finalize and detach operations. Finalize checks uploaded metadata and atomically associates the attachment/barcode with the outing using expected revision/previous attachment and an operation receipt. Reject foreign-owner references, reused IDs with different content and stale replacement/removal.
- Storage insert/read policies require current owner and matching prepared metadata. Only unreferenced retired/abandoned objects are eligible for deletion.
- Extend owner sync/read projections. Exclude attachments, barcode data and booking references from shared libraries, friend views and outing-plan notifications.
- Add delayed orphan cleanup with a claimed retirement state to prevent a finalize/delete race. Remove physical bytes through Storage API before metadata; deleting `storage.objects` rows alone is not file deletion.
- Download with authenticated owner requests. Never persist signed URLs, tokens or public object URLs.

Storage upload and database mutation are separate transactions. The client must retain durable source bytes through prepare/upload/finalize failures and unknown outcomes. Reusing stable IDs makes replay safe; an existing object must match its recorded content before being treated as uploaded.

## Web implementation

Add an `attachments` object store to the existing IndexedDB database, keyed by project, owner and attachment ID. Commit blob bytes, metadata and typed attachment command together. Anonymous blobs remain in the separate anonymous scope. UI success follows transaction completion, never precedes it.

Delivery runs prepare, immutable upload, finalize RPC and canonical acknowledgement through the existing coordinator lock and owner/generation fences. Keep conflict bytes and pending uploads recoverable. Cache authenticated downloads as owner-scoped blobs; revoke transient object URLs on close/account switch and exclude private downloads from global service-worker caches.

Prefetch current scheduled tickets after owner hydration. Show “Available offline” only after blob commit; provide download/retry/remove-local-copy actions. Never evict pending captures. Quota errors must preserve form state and explain that capture was not saved. Request persistent browser storage where supported without promising immunity from user deletion or browser eviction.

Primary seams: `lib/offline/{storage,commands,validation,entities,replay}.ts`, `lib/offlineRpc.ts`, `store/{offlineLibrary,libraryActions,useAppStore,mockData}.ts`, `lib/db.ts`, new `lib/tickets/*`, `OutingScheduleSheet`, title detail and Up Next.

## Android implementation and legacy migration

Add owner/project-scoped immutable files and Room attachment/upload-job records. Write temporary bytes, fsync/rename, then commit the Room job; conservatively recover crash leftovers. Use existing OkHttp with `FencedSessionSource` for authenticated binary Storage requests. Attach/detach uses the shared receipt/precondition API. Ordinary outing updates must not replay stale attachment fields or unrelated newer values.

Make capture/removal await observable repository results. Scope capture tasks and image loaders to the current runtime. Migrate only readable files attributable to that owner, validating canonical paths under the old tickets directory, copying to owner storage and recording a migration receipt before upload. A server-synced absolute path is not ownership proof. Unknown-owner archives retain explicit recovery; missing files remain a recoverable original-device-only state.

Keep original bytes until remote verification and local migration receipt. Never delete or upload unknown archives automatically. Sign-out detaches UI/jobs but preserves pending owner files; explicit account deletion includes private attachment cleanup.

## Capture, barcode and theater viewer

Provide add/replace/remove photo controls, camera capture and file selection. Preserve the original when decoding fails. Provide a zoomable original, actual decoded code where safely supported, plain booking reference, and Scan/Seats modes with auditorium, row, seats and legacy fallback.

Never turn a booking reference or unknown symbology into QR. Preserve format exactly. For ambiguous, malformed, binary or encoding-sensitive codes, prefer the original image. Android can reuse ZXing core. Web should use a pinned `zxing-wasm` dependency: bundle its WASM locally and precache it instead of using the default CDN. Its supported read/write formats cover QR, Code128, PDF417, Aztec, ITF and Codabar. Native `BarcodeDetector` alone does not cover all target browsers.

Web Scan uses a white presentation; Seats uses dark amber. Fullscreen and Screen Wake Lock are progressive enhancements. Hardware brightness remains a native capability.

## Retention and verification

Do not remove referenced photos when showtime passes. Pending captures and active references remain; retired/unreferenced objects use the documented grace period and cleanup workflow. An explicit owner export must include bytes and metadata, or clearly identify unavailable remote-only bytes; raw local paths are not a portable backup.

Required regression coverage:

- Backend owner/foreign/anonymous denial, retries, stale replace/detach, missing objects, cleanup race and private-field exclusion.
- Web blob+command atomic rollback, quota failure with retained draft, offline reload of photo/code, one cross-tab upload, account switch during requests, unknown outcomes, retained pending bytes and locally bundled WASM offline.
- Android crash recovery, Room migration, owner directories, unknown/missing legacy files, pending-file retention, stale outing writes and activity recreation.
- Cross-client capture on either device, download on the other, then offline viewing; replacement/removal convergence without premature deletion.

Checkpoints: shared backend contract; both durable attachment pipelines; complete capture/viewer UI; migration/retention and cross-client journeys. No checkpoint alone establishes ticket parity.

## Primary references

- [Supabase private buckets](https://supabase.com/docs/guides/storage/buckets/fundamentals)
- [Supabase immutable uploads and retry behavior](https://supabase.com/docs/guides/storage/uploads/standard-uploads)
- [ZXing WASM formats and local WASM loading](https://github.com/Sec-ant/zxing-wasm)
- [BarcodeDetector browser availability](https://developer.mozilla.org/en-US/docs/Web/API/BarcodeDetector)
- [Screen Wake Lock](https://developer.mozilla.org/en-US/docs/Web/API/Screen_Wake_Lock_API)
