# Android Ticket Capture (#219) — Implementation Plan

**Status:** Draft — ready for task-level breakdown
**Date:** 2026-08-19
**Companion to:** [`docs/adr/0003-ticket-ingestion-via-forwarding-address.md`](../adr/0003-ticket-ingestion-via-forwarding-address.md)
(shared ingestion design, #223), [`docs/superpowers/plans/2026-07-21-android-cinema-outings.md`](./2026-07-21-android-cinema-outings.md)
(the outing feature this attaches to), [`apps/android/core/designsystem/.../TicketScreen.kt`](../../../apps/android/core/designsystem/src/main/kotlin/work/kumarfamilynet/cinemarchive/core/designsystem/TicketScreen.kt)
(the screen this feeds)

---

## 1. What #219 actually is, and what it deliberately is not

Issue #219 asks for: photograph a ticket, decode its barcode on-device, store the decoded
payload plus the image, and show it full-screen for scanning at entry.

ADR 0003 draws a shared pipeline — forwarded email **and** ticket photo both feed a parser
into a `ticket_import_candidates` review queue that produces a `CinemaOuting`. Neither
`ticket_import_candidates` nor the parser exists yet (confirmed: zero matches in
`schema.sql` / migrations). Building that shared queue is real, multi-day, cross-client
infra — and #219's actual proposed solution never asks for it: it never asks to *extract
title/showtime/seats from the photo and create an outing*. It asks to attach the real
scannable code to an outing the user already scheduled by hand.

**So this plan scopes #219 as: attach a captured ticket (photo + decoded barcode payload +
format) to an existing `CinemaOuting`, and render the real payload in `TicketScreen`
instead of the synthesized QR it draws today from `bookingRef`.** Structured-field
extraction (auto-filling venue/seat/showtime from the photo) is explicitly deferred to
whichever plan stands up the shared `ticket_import_candidates` pipeline — likely when #223
lands, since the parser is shared. This isn't a new decision so much as reading #219's own
proposed solution literally instead of the superset ADR 0003 gestures at.

This keeps v1 consistent with ADR 0003 (it doesn't contradict the shared-parser future,
it's a strict subset that doesn't need it) and with the existing Android outings plan's
principle: local-first, narrow on backend-dependent surface, ship the on-device part now.

---

## 2. Current state (grounding facts)

| Area | State |
|---|---|
| `TicketScreen.kt` | Scan mode already renders a QR of `CinemaOuting.bookingRef` via a hand-rolled `QRCodeWriter`. Its own doc comment names #219 as "where it will render once that lands" — i.e. this screen is the intended consumer, not a new screen. |
| `TicketQrCode` | Hardcodes `BarcodeFormat.QR_CODE` and a 1:1 `aspectRatio`. Real vendor tickets are commonly Code 128 / PDF417 / Aztec / ITF, not QR — this will need to become format-aware (§5). |
| `CinemaOuting` (`core/model`) | No ticket-image or barcode-payload fields. `bookingRef` is a manually-typed confirmation code, not a scanned payload — the KDoc on `TicketScreen` already flags this distinction. |
| `cinema_outings` (Postgres) | No ticket-image or barcode-payload columns. Delta-sync projection (`schema.sql:1989-2001`, inside the sync RPC) enumerates outing columns explicitly by name — new columns need to be added there too, not just to the table. |
| Supabase Storage | **Unused anywhere in this repo** — no bucket, no client-side Storage call, on either platform. This is greenfield, not "add to an existing pattern." |
| `RemoteMutationWriter` / `OutboxEntity` | Existing write-path template (`SupabaseLedgerLayoutWriter` is the reference implementation: built and tested, but DI'd as `UnconfiguredRemoteMutationWriter` in the live app pending passkey auth). It queues **row mutations**. A binary image upload doesn't obviously fit that shape — flagged as an open design point in §6, not assumed to slot in. |
| ML Kit / zxing deps | `mlkit-barcode` (17.3.0) and `zxing-core` (3.5.3) are both already declared in `libs.versions.toml` (barcode already used for decode by nothing yet — 3.5.3 is currently used only for *encoding* in `TicketScreen.kt`). Neither is wired into any module's `build.gradle.kts` — the catalog entries are unused today. |
| CameraX | `camerax = "1.4.1"` in the catalog; not currently wired into any module either (confirmed no build.gradle.kts references). |
| Auth | Still Discovery — no live passkey session (`docs/android-implementation-status.md`). Same constraint the outings plan hit: nothing here can be live-wired to Supabase yet, only built and tested. |
| Parity matrix | No `#219` / ticket-capture row yet. Issue body says "Cross-platform parity: Yes — add to the parity matrix once implemented," meaning web has no equivalent and this ships Android-only in v1. |

---

## 3. Locked decisions for v1

| Decision | Choice | Why |
|---|---|---|
| **Target of the capture** | An *existing* `CinemaOuting` (scheduled by hand or, later, by #223's queue) — capture attaches to it, never creates one. | Matches #219's literal proposed solution; avoids needing the unbuilt candidate pipeline. |
| **Where the barcode gets decoded** | On-device, ML Kit `barcode-scanning` from a captured/picked image — not a live camera scan loop. | Issue explicitly says "upload a photo... decode it," not a real-time scanner UI; simpler, and reuses the same capture surface (camera or gallery) either way. |
| **What gets stored** | Three new fields on `CinemaOuting`: the image (local file first, Storage path once wired), the decoded payload string, and the decoded `BarcodeFormat`. Format is stored **alongside** payload, not inferred later — needed to redraw the correct symbology instead of guessing QR. | A Code 128 payload re-encoded as QR will not open a turnstile; see §5. |
| **Write path** | Follow the `SupabaseLedgerLayoutWriter` template: build `SupabaseCinemaOutingTicketWriter` for real, test it against the live/staging project, but do not DI it into the live app until passkey auth lands. Local Room write ships live immediately (offline-first, same as the rest of outings). | Established precedent; don't invent a second pattern for this feature. |
| **Structured-field extraction from the photo** | Out of scope for v1. | That's the shared `ticket_import_candidates` parser ADR 0003 describes — cross-client, multi-day, and not what #219 asks for. Revisit when #223's infra lands. |

---

## 4. Data model additions

### `core:model`
```kotlin
enum class TicketBarcodeFormat { QR_CODE, CODE_128, PDF_417, AZTEC, ITF, CODABAR, OTHER }
```
Add to `CinemaOuting`:
- `ticketImagePath: String?` — local file URI in v1 (app-private storage); becomes a
  Supabase Storage object path once the writer is wired live. One field serves both by
  convention (a `content://`/`file://` prefix vs a bare storage path), matching how
  `bookingRef` already collapses "no code yet" into null rather than a separate flag.
- `ticketBarcodePayload: String?`
- `ticketBarcodeFormat: TicketBarcodeFormat?`

### `core:database`
- New columns on `CinemaOutingEntity` (mirrors the three above), plus a Room migration.
- No DAO surface changes beyond the existing outing update path.

### Postgres (new migration, `supabase/migrations/2026081900000_cinema_outing_ticket_capture.sql`)
- `ticket_image_path text`, `ticket_barcode_payload text`, `ticket_barcode_format text`
  (`check (... in (...))` mirroring the `format` column's free-text-from-fixed-list style)
  on `cinema_outings`.
- Update `schema.sql`'s canonical copy of the table.
- **Update the delta-sync projection** (`schema.sql` sync RPC, `cinema_outing` branch) to
  include the three new fields in its `jsonb_build_object` — the sync function enumerates
  columns explicitly, so a new column is invisible to Android until this is also touched.
  Three places, not one: table, `schema.sql`, sync projection.

---

## 5. `TicketScreen.kt` changes

- `TicketQrCode` becomes format-aware: `zxing-core`'s `MultiFormatWriter` (already a
  dependency, currently only exercised for `QR_CODE`) needs to cover whichever formats ML
  Kit's `Barcode.format` can actually return — verify the overlap before assuming all six
  enum cases above are re-encodable.
- Aspect ratio needs to vary by format: 1D symbologies (Code 128, ITF, Codabar) render as a
  wide strip, not the current hardcoded 1:1 square used for QR/Aztec/PDF417.
- **Fallback:** if the decoded format can't be cleanly re-encoded, or re-encoding would be
  lossy, fall back to displaying the stored *photo* directly instead of a redrawn code —
  this is the reason the image is captured at all, not just a backup for the payload.
- Scan mode should prefer `ticketBarcodePayload`/`ticketBarcodeFormat` over `bookingRef`
  when both exist; `bookingRef` remains the fallback for outings without a captured ticket
  (manual entry, or outings created before this ships).

---

## 6. Open design points to resolve before implementation

1. **Image upload doesn't obviously fit `RemoteMutationWriter`.** That queue is shaped for
   row mutations (`OutboxEntity`), not binary blobs. Options: (a) a separate
   upload-then-reference-write flow — upload to Storage first, then queue a normal row
   mutation carrying just the resulting path; (b) extend the outbox to carry a local file
   reference and let the writer do both steps. Needs a decision, not an assumption, before
   `SupabaseCinemaOutingTicketWriter` is written.
2. **Storage bucket shape.** Bucket name (e.g. `ticket-photos`), path convention
   (`«user_id»/«outing_id».jpg` matches the table's owner-only RLS posture — "no
   shared-token/friend read" per the outings design doc), and `storage.objects` RLS
   policies restricting reads/writes to the owning `auth.uid()`.
3. **ML Kit delivery variant.** Bundled-model vs Play-services-delivered
   `barcode-scanning` artifact — affects APK size, which the release pipeline tracks.
   Verify exact artifact coordinates against `libs.versions.toml`'s `mlkit-barcode = "17.3.0"`
   rather than assuming.
4. **Which module owns the capture UI** (camera/gallery picker → ML Kit decode → confirm).
   Candidates: `core:designsystem` (co-locate with `TicketScreen`) vs a new
   `feature:tickets` module. CameraX (1.4.1) also needs wiring wherever this lands.

---

## 7. Also required

- `docs/android-parity-matrix.md` — new row for ticket capture, noting Android-only in v1
  (web has no equivalent; issue body explicitly asks for this once implemented).
- `CHANGELOG.md` `[Unreleased]` entry once the user-facing capture flow ships.
- Verification gate matches the existing outings plan's convention:
  `./gradlew :app:assembleDebug :app:lintDebug testDebugUnitTest`, plus the new migration
  applied and delta-sync projection checked against staging before the write path is
  considered done (even though it stays unwired in the live app until auth lands).

---

## 8. Suggested task breakdown

1. `core:model` + `core:database` additions (§4) — new fields, Room migration, no UI yet.
2. Postgres migration + `schema.sql` + delta-sync projection (§4) — the three-places fix.
3. Capture UI: image picker/camera → ML Kit decode → confirm sheet → write to the existing
   outing (local Room only at this stage).
4. `TicketScreen.kt` format-aware rendering + photo fallback (§5).
5. `SupabaseCinemaOutingTicketWriter` + Storage bucket/RLS, tested against staging, DI'd as
   `UnconfiguredRemoteMutationWriter` in the live app (matches Ledger precedent) — resolve
   open point §6.1 first.
6. Parity matrix row + CHANGELOG entry.
