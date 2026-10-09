# Library backup / import contract (#323)

Status: **codec verified** (`data/.../LibraryBackupCodec.kt`): 91 JVM cases and 5 instrumented
cases passed on Android API 36, along with app build and lint. Shared fixtures live in
`docs/fixtures/library-backup/`. Installed-app JSON export is wired through **Import & sync →
Library JSON backup → Export JSON**; final checkpoint verification is recorded with its commit.
Installed-app JSON restore is wired through **Import & sync → Restore JSON**.

## Installed restore

The platform document picker opens a bounded archive for preview before any writes. Confirmation
saves each new title, its complete supported graph and one immutable owner/project-scoped
`apply_library_command` request in the same Room transaction. A later storage failure reports
earlier saved titles and the remaining count. Reopening the original file skips those titles.

The installed flow skips the **entire duplicate title graph, including every associated outing**,
matching shipped web behavior; the codec's generic outing-retargeting mode is not used. It rejects
an individual title graph before admission when references or values cannot be represented without
loss. Notices identify unsupported envelope data. Lists, settings, ticket photos and unknown row
fields are not silently restored. The original file is never modified. Original companion objects,
literal names and repeated same-name friend links are retained after copy identities are generated.

Delivery retries the exact saved operation, then validates its receipt and current owned rows.
A deleted current title is not resurrected by an old receipt. Later queued edits use the import's
exact row receipt as their predecessor; protected changes are replayed through a durable sync epoch.
Unknown outcomes allow retry and export of the saved original graph. A definitively rejected import
can be explicitly removed with its reviewed, never-dispatched dependent edits; changed comparisons,
unknown later payloads and potentially dispatched dependencies prevent removal.

Each title is bounded to 50,000 operations and a conservative 16 MiB PostgreSQL JSONB request,
including exponent expansion and exact numeric range checks. This is a per-title atomic restore,
not history merging or a portable backup of private photos.

The backup is a JSON archive of one account's library, readable and writable by both clients. It is
**untrusted input on restore** — it may be hand-edited, come from another account, or be hostile.

## Formats

| Format | Shape | Reader | Writer |
| --- | --- | --- | --- |
| v1 (installed clients) | `{version:1, exportedAt:"YYYY-MM-DD", titles:Title[], outings?:CinemaOuting[]}` | both | web and Android |
| bare array | `Title[]` | both | — |
| v2 (codec only) | v1 + `format:"cinemarchive-library"`, `version:2`, ISO-datetime `exportedAt`, `client:{platform,version}`, `lists:List[]`, `localOnly:{venueNotes,theaterInterest}` | Android codec | Android codec; no installed export flow |

* A reader MUST accept v1 and bare arrays, MUST tolerate a missing `outings`/`lists`, and MUST reject
  `version` greater than it understands (message names the version).
* Entities are JSON trees. **Unknown fields are preserved verbatim** through parse → encode (lossless
  document round trip, e.g. web → Android → web, even for fields Room has no column for). Unknown
  top-level keys are kept in `extra` and never written into the library by a restore.
* Writers emit sorted keys, 2-space indent, trailing newline. Encoding is canonical **per runtime**; byte identity across Android/JVM is not promised (JSON-equivalence is). Any restore-idempotency hash is `sha256Hex` over the **original input bytes**, never re-encoded JSON.

`List` = `{id, name, description|null, createdAt, updatedAt, items:[{titleId, position?, addedAt?}]}`.

## Identity: archive vs copy

* The **archive** keeps original identities verbatim (it is a faithful snapshot).
* **Restore as copy** (the only restore mode in this contract) gives every owner-row a fresh id —
  title, season, episode, episode watch/rating/review, viewing, outing, list — and rewrites every
  reference through the same maps: `viewing.titleId`, `outing.titleId`, `outing.completedViewingId`,
  `viewing.outingId`, `list.items[].titleId`. No archive id appears in the restored data.
* **Ambiguous identities**: if an id appears more than once in a table (title, season, episode, watch, rating, review, viewing, outing, list) EVERY row carrying it is rejected, nothing is bound to it, and rows referencing it are rejected (outings) or have the reference dropped and counted (viewing.outingId, list items). Distinct title ids that share `(tmdbId,type)` are not ambiguous: the later one is skipped with an explicit mapping to the first/existing title.
* **Completed outings** may have no surviving viewing pointer after a history deletion. A present pointer must reference a viewing nested in the same title; otherwise it is rejected. Generic codec planning may re-point non-completed outings/list items on a skipped title; installed restore instead skips that entire graph. Histories of a skipped title are never silently bound to another title.
* `physicalMedia[].id` is owner-authored logical identity: preserved in the archive, regenerated in admitted copies (format/edition/notes retained). Ids inside opaque `ext` data are kept verbatim as inert data.
* **Dedupe** by `(tmdbId, type)` against the library: an existing title is **skipped with a report**
  (not merged — merge is a later option). Outings and list items that referenced the archive's copy
  are re-pointed at the existing title.

## Untrusted-input rules (implemented in the codec)

* Never imported, at any nesting level of title/season/episode/viewing/outing/list rows:
  `ticketAttachment`, `ticketManaged` (managed ticket descriptors/object keys/operation ids are
  provenance only), `user_id`, `userId`, `ownerUserId`, `owner_user_id`, `outbox`, `receipts`,
  `aliases`, `operationId`, `operationIds`, `tombstones`, `syncCursor`, `shareToken`, `shareTokens`,
  `token`. Each removal is counted in `report.untrustedFieldsDropped`.
* Limits: 50 000 titles, 100 000 outings, 1 000 lists, 200 000 list items, 200 seasons/title, 2 000
  episodes/season, 5 000 viewings/title, 100 tags, ids ≤ 128 chars, any string ≤ 20 000 chars.
* Per row: title needs `tmdbId` (positive int), `type` ∈ {movie,tv}, non-blank `title`; `status` ∈
  {watched,watchlist,watching,dropped} (unknown ⇒ **reject the row**, never default); ratings ∈ [0,5];
  outing needs a string `titleId`, ISO `showtime`/`endsAt`, status ∈ {scheduled,completed,missed,cancelled},
  previews ∈ [0,120], runtime is a positive integer. Unknown outing `format` and odd date forms are
  codec warnings; installed admission additionally checks concrete database representations.
* A rejected row never aborts the rest; every rejection carries a path and reason in the report.

## Dates

Undated stays undated: a viewing or episode watch event with no date is restored with no date — it
is never replaced by "today". Historical dates and both date (`YYYY-MM-DD`) and datetime forms are
preserved exactly as written.

## Companions

Canonical `{name, friendUserId?}` (web `normalizeCompanions`): strings become `{name}`, objects keep
`friendUserId`, blank names are dropped, names trimmed. Android Room 20 retains the original array
alongside display names; sync schema 12 backfills it without replacing pending owner edits. Export
uses that array, preserving duplicate names and linked friend identities. A nonempty legacy name list
with no original array blocks export with a **Sync and try export again** action; empty lists and new
empty libraries do not require backfill. Unknown pending data may need its existing recovery flow
before sync can recover the original array. Name edits retain matching occurrences; retaining an
unknown old name while changing the list requires sync first. Unrelated edits remain available.

## Installed Android export

`LibraryBackupRepository` reads the current owner's complete Room graph in one transaction, including
optimistic pending changes. It does not fetch a server-only replacement or change the outbox. Titles,
Specials and regular seasons, episodes, independent watch/rating/review logs, retained credits,
viewings and outings use the shipped web v1 field names. Missing dates stay missing. Physical-media
JSON uses the codec's exact-number parser; opaque retained values are not rounded by `org.json`.

The system `CreateDocument(application/json)` picker selects the destination. Bytes are prepared for
that exact runtime, checked again before opening/writing the destination, and written off the main
thread. Success appears only after write, flush and close succeed. Cancellation is not success;
write errors warn that the chosen file may be incomplete and allow a new export attempt. Account
switches invalidate prepared exports. The existing 64 MiB / depth / node bounds apply.

Ticket photos, managed attachment authority, device-local photo paths, lists and account settings
are excluded. Actual retained barcode data is portable; a managed removal suppresses old legacy
barcode fallback. Archive files contain no outbox, sync cursors, local completion aliases or receipts.

## Tickets

* A backup contains **no ticket bytes** unless the user explicitly opts in; until that exists the UI
  must say "ticket photos are not included" (no complete-photo-backup claim).
* Archive descriptors (`ticketAttachment`, object keys, hashes, operation ids, aliases) are untrusted
  provenance: never used as authorization, never adopted. When bytes are included, import
  **recaptures** them under a **new** local attachment UUID and enqueues a **new** owner-confirmed
  command (ticket repository contract). *PLANNED.*

## Restore semantics (*PLANNED*, later batches)

1. Parse + validate + plan completely in memory (no writes). Show the report before confirming.
2. One Room transaction via the runtime `LocalTransactor` writes the rows **and** their outbox
   entries so imported titles sync; nothing partial on failure.
3. A durable per-owner restore receipt keyed by the archive's content hash is committed in the same
   transaction, so a repeated or crash-interrupted restore is idempotent and never re-queues.
4. Owner-scoped: restore targets only the signed-in account's runtime database.
5. Archived `localOnly` venue notes and theater interest remain inert until explicitly admitted.
   Future restore must follow `moviegoing-preferences.md` for the shared private tables, preserving
   existing local data and requiring proven revisions for replacements.

## Restore mapping: titles (`BackupRestoreMapper`, foundation only)

Pure function from one `planCopy` title row (fresh identity already admitted) plus a caller-supplied,
frozen `admittedAt` instant to a Room `TitleEntity` draft and one canonical
`{table:"titles",action:"insert",key:{id},values}` operation (golden:
`docs/fixtures/library-backup/expected-restore-title.json`). It does not generate ids, choose operation
ids, chunk, persist or activate anything; the repository owns those and must freeze the exact operation
bytes with the admitted plan. An operation id is never derived from archive hash/owner/chunk alone, and one
id is never reused with different bytes.

`TitleMapping.operationJson` is the immutable canonical JSON to encode as UTF-8 for future queue
admission. `operation` returns a detached inspection tree, not the byte serialization contract. Both
the operation and Room's physical-media JSON use the codec's exact writer rather than Android
`JSONObject.toString()`, preserving opaque `BigInteger`/`BigDecimal` values recursively without mutating
the input. No parser/serializer is duplicated in the mapper.

* `values` is limited to the server `titles` allowlist (snake_case; lower-case `type`/`status`; no
  `user_id`, `id` or `updated_at`). Room stores the same facts (upper-case enums, `updatedAt = admittedAt`).
  That draft timestamp is local bookkeeping, **not an observed server revision or CAS proof**; activation
  must reconcile the insert receipt before dependent edits can use a server baseline.
* Required: UUID id, `tmdbId`>0, `type`, `status` (never defaulted), non-blank `title` without NUL, and an
  32-bit integer `year` (server column is NOT NULL; `0` is the clients' valid unknown-year value; no
  invented 1–9999 restriction). Failure ⇒ `REJECTED`, no entity, no operation.
* Optional fields keep `null`/`false`/`0`/`""`/`[]` distinctions. A value of the wrong type, out of range, with
  NUL, or beyond `numeric(3,1)` precision (e.g. rating 4.25) is **never coerced or rounded**: it is left out of
  both outputs and reported `LOSSY` with its path. `releaseDate` must be `YYYY-MM-DD`; datetimes are not truncated.
* `addedAt` must be an ISO-8601 instant with offset and is kept verbatim. Missing or date-only values are not
  reinterpreted in any timezone: the frozen admission time is used and reported `LOSSY`.
* `physicalMedia` retains the complete array (all raw element fields, regenerated element ids from the plan) when its text is representable by PostgreSQL JSONB. A NUL or unpaired surrogate in a nested value or key produces a path-specific LOSSY report and omits the whole field from both the operation and Room draft; the source archive remains unchanged.
  Absent `inHomeCollection` is `false` (server NOT NULL default).
* Reported by path, never written: `DEFERRED` seasons, viewings, cast, crew (non-empty), plan outings
  (completed outings need a reviewed restore contract and are never downgraded) and lists; `UNMAPPED` `ext` and
  unknown fields. Ticket bytes, authority keys, receipts and operation ids are never part of the mapping.
* This mapping does not make a restore complete or lossless; the UI must still list everything reported.
* `mapPlan` requires the original `BackupDocument` alongside its `CopyPlan`: it reports each
  `localOnly.<key>` and unknown top-level path as `UNMAPPED`, and retains the original admission
  `copyReport` (including skipped/rejected rows and stripped authority fields). Neither report may be
  omitted by future restore UI. Private data remains in the original archive, never in title operations.

## Report

`titlesNew, titlesSkippedExisting, titlesRejected, outingsNew, outingsRejected, listsNew,
listItemsNew, listItemsDropped, untrustedFieldsDropped, unknownTopLevelKeysIgnored, warnings[],
rejections[]` — each issue is `{path, message, fatal}`.

## Gaps (web vs Android) this contract makes explicit

| Data | Web export | Android Room today |
| --- | --- | --- |
| lists + membership | **not exported/imported** | stored; v2 adds them |
| companion friend ids | kept | Room 20 retains original arrays; installed export/restore preserves links |
| physicalMedia, customWatchUrl, inHomeCollection, rtUrl, awardsCount, bechdel*, contentRating, imdbId, scores | kept | Room v18 retains rich title metadata; restore and re-export mappings still require verification per field |
| tags | kept | column exists |
| ticket bytes | not exported | managed by ticket repository |
| venue notes, theater interest | n/a | local data remains; shared private storage exists, with client admission pending; `localOnly` in v2 preserves archive provenance |

## Completeness claims (binding on UI copy and reports)

* A codec round trip is **not** a lossless installed-app restore. A restore is only called lossless when
  every archive value either has durable, owner-scoped storage that survives restore **and re-export**, or
  the report lists it as unsupported. Until storage exists the feature says "incomplete support" and lists
  what was omitted; it never says "complete backup".
* `ext` / unknown fields carry no authority: they are inert data (no receipts, outbox entries, aliases,
  descriptors or ids are ever re-activated from them).
* Skip-with-report dedupe matches current web and is NOT a lossless restore of existing-title histories:
  every omitted title, history row and list reference is reported and the source archive is preserved.
  Merging histories into an existing title is an enhancement beyond current web parity, not a requirement for matching the shipped web restore flow.
* Companion friend links must not be permanently dropped as final parity: companion identity storage is
  owned by the outing-integrity worker; this contract consumes whatever API/storage it provides.
* Archive identities stay separate from copy-admission identities; the archive is never rewritten.
* Ticket photos: no "complete portable backup" claim until explicit byte inclusion + recapture exists. The shipped web JSON backup excludes photo bytes, so that enhancement is separate from JSON backup parity and ordinary per-ticket original export.

## Read bounds

A reader enforces a **byte limit before allocating** the document text (reject oversized files from the file
size or a bounded read, e.g. 64 MiB), then parses. The codec materializes the whole tree in memory
(`JSONObject`): it is **bounded, not streaming**, and must not be described as streaming.

## Shared fixtures (`docs/fixtures/library-backup/`)

* `v1-web-export.json` — web envelope: TV graph, viewing↔outing links, companions with friend ids,
  managed + legacy ticket fields, unknown future fields, physical media/tags.
* `undated-and-companions.json` — v2 envelope: undated viewing + undated episode watch event,
  mixed companion forms, a list with a duplicate and a dangling item, `localOnly`, unknown top-level key.
* `hostile.json` — duplicate/non-string ids, rating 9, unknown status, bad tmdbId, untrusted keys,
  dangling `completedViewingId`/`outingId`, outing with missing title / bad showtime, blank list name.

## Strict parsing (implemented)

Own RFC 8259 parser: trailing tokens, comments, single quotes, unquoted keys, NaN/Infinity, leading zeros, raw control
characters, bad escapes and **duplicate keys** are rejected; exactly one leading BOM is stripped; depth ≤ 64, nodes ≤ 4M;
`parseBytes` checks the byte limit first and decodes UTF-8 with a REPORTing decoder. Integers use `Long` or exact `BigInteger`,
and decimals/exponents use exact `BigDecimal`. Numeric tokens are limited to 64 characters and must have a finite
`doubleValue` for Android framework compatibility; the stored value is never rounded. Unpaired UTF-16 surrogates are
rejected. `tmdbId` must be an
integer in 1..Int.MAX (`1.5`, `603.0`, `1e3`, `"603"` are rejected). A present, non-null container (`seasons`, `episodes`,
`viewings`, `watchEvents`, `ratings`, `reviews`, `companions`, `tags`, `genres`, `seats`, list `items`) must be an array.
Season/episode numbers are integers ≥ 0 and unique within their parent.

## Admitted rows vs archive (implemented)

In **planCopy output only**, each row is rebuilt from a per-entity allowlist of known fields; everything else moves verbatim
into a single inert `ext` object. Untrusted keys (the list above **plus `ticketImagePath`**, matched case-insensitively) are
removed at any depth, including inside `ext`, and counted. The archive document and `encode` stay lossless.

## Report fields (additional)

`listsRejected, historyRowsOmittedForSkippedTitles, viewingOutingRefsDropped, outingViewingRefsDropped, outingsRetargeted,
listItemsRetargeted, completedOutingsRejected, ambiguousIdRows`.

## Additional fixtures

`trailing-garbage`, `duplicate-keys`, `float-tmdb`, `malformed-containers`, `duplicate-natural-keys`, `ambiguous-ids`,
`completed-without-history`, `skipped-title-completed-outing`, `ticket-path-and-ext`, `bare-array`, `v2-android-export`.
