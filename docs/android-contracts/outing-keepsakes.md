# Outing keepsake photos

A photo kept with a cinema outing (#208): a ticket stub, a poster board, a popcorn selfie. It is
distinct from the single scannable ticket attachment ([ticket-attachment-api.md](ticket-attachment-api.md)):
no barcode, up to eight per outing, and plain owner-private data. Implemented by migration
`20261030000000_outing_keepsakes.sql`; the clients are Android (`KeepsakeRepository`,
`KeepsakesRoute`) and web (`lib/keepsakes.ts`, `OutingKeepsakes.tsx`).

## Online-only

Unlike tickets, keepsakes have **no offline queue and no durable command journal**. Adding, viewing
and removing a photo needs a connection, and each call fails with a clear message when it cannot
reach the account; nothing is saved to retry later. This keeps the feature to a table, a bucket and a
cleanup job. Offline capture, a local copy and sync are a separate, larger step if wanted.

## Storage and rows

- `outing_keepsakes(id, user_id, outing_id, object_key, mime_type, byte_length, created_at)`.
  `object_key` is `<owner id>/<keepsake id>` (a CHECK ties it to `user_id` and `id`). `mime_type` is
  JPEG, PNG or WebP; `byte_length` is 1 to 10 MiB. Rows are **immutable** (no UPDATE) and owner-only:
  RLS grants SELECT, INSERT and DELETE on the owner's own rows, and INSERT also requires the outing
  to be the caller's. No friend, shared-token or anonymous read exists, and keepsakes are not part of
  an outing's share snapshot.
- Bytes live in the private `outing-keepsakes` bucket (10 MiB, image types). An owner may upload
  only to their own folder with a UUID name, never overwrite, and hold at most 500 objects in the
  bucket. Reading an object requires a row of the caller's that points at it. Restrictive fences keep
  an unrelated permissive policy from widening any of this, and clients have **no** UPDATE or DELETE
  on bytes.
- Cascades: deleting an outing or account removes the rows. The bytes stay until cleanup.

## Adding a photo

1. Client downscales to at most 2048 px on the long side (EXIF rotation applied) and re-encodes as
   JPEG, lowering quality until it is under 10 MiB. Re-encoding drops embedded metadata such as GPS.
2. Upload to `<owner id>/<new uuid>` with `x-upsert: false`.
3. Insert the row. A BEFORE INSERT trigger checks that the caller owns the row, the stored object
   exists, its size and MIME type match the row, and the outing holds fewer than eight (SQLSTATE
   `22023` for a missing or mismatched object, `54000` for a ninth, `42501` for someone else's).

A failed step 3 leaves an unreferenced object, which cleanup removes. Clients do not try to delete it.

## Viewing and removing

A photo is read through a short-lived signed link (web, ten minutes) or an authenticated download
(Android), both subject to the read policy above. Removing deletes the row, which makes the bytes
unreadable **at once**; their physical deletion waits for cleanup, so they can exist for up to about
two days afterwards.

## Cleanup

`claim_keepsake_cleanup(p_limit)` is service-role only and returns at most 100 object names in the
bucket that have had no row for over a day. The daily workflow
`.github/workflows/cleanup-ticket-attachments.yml` runs `scripts/cleanup-keepsake-photos.mjs` on
`main`, which deletes each through the Storage API (never by deleting the metadata row). A deleted
object leaves the claim by being gone, so there is no acknowledgement step; a failed deletion is
simply claimed again the next day. It needs `SUPABASE_PROJECT_REF` and the server-only
`SUPABASE_SERVICE_ROLE_KEY`. **Deploy the migration before shipping the clients or the cleanup step**;
until then the album shows a load error.

## Entry points

Android: a "Photos" button beside "View ticket" in title detail, for each of the title's outings.
Web: a "Photos" button beside the ticket button on the Up Next outing cards and in a title's viewing
timeline. Neither client shows it in a shared or friend view.

## Evidence

`apps/web/scripts/outing-keepsakes.test.mjs` (embedded PostgreSQL, part of `npm run test:db`) pins
the owner fences, upload folder and name rules, row guards, the eight and 500 limits, immutability
and cleanup claims. `scripts/cleanup-keepsake-photos.test.mjs` pins the request ordering. Android
pins the rules in `KeepsakeRulesTest` and the requests and owner fencing in `KeepsakeRepositoryTest`;
web pins the same in `keepsakes.test.ts` and `OutingKeepsakes.test.tsx`. The Storage model in the SQL
tests covers policies only, not the HTTP upload service or physical bytes, and nothing here has been
run against a live Supabase project or on a device.
