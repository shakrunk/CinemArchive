# Canonical outing completion

The shared completion core is implemented in migration `20261008194606_canonical_outing_completion.sql`. Native integration, guarded revert integration and device acceptance remain in progress; existing client completion paths are not yet fully convergent.

An offline native completion creates a provisional viewing and one durable completion command in the same Room transaction. It records the current outing revision, a stable operation UUID, the provisional viewing UUID, and the device's IANA timezone at that moment. Retries never substitute a newly observed timezone. The server derives the viewing date from the locked outing showtime and recorded zone.

```text
complete_cinema_outing(p_outing_id uuid, p_operation_id uuid,
  p_provisional_viewing_id uuid, p_expected_updated_at timestamptz,
  p_tz text default 'UTC', p_expected_operation_id uuid default null) -> jsonb
```

The server locks the owner outing and title. First completion requires a still-scheduled, due outing with the expected revision. Already-completed outings return their canonical viewing identity without creating a second event. Rescheduled, cancelled, missed or uncertain historical plans require reconciliation. Historical independent viewings are never heuristically merged or deleted.

Exactly one precondition source is required: `p_expected_updated_at` for a known server revision, or `p_expected_operation_id` with a null timestamp for a preceding owned command receipt. The latter resolves the outing revision from that receipt's canonical row, retaining causal ordering for an offline create/edit without guessing the server timestamp. A missing or unrelated receipt fails; an intervening edit still conflicts. New native outing writes must use receipt-backed commands before serving as dependencies. Both the dependency UUID and captured zone are part of the immutable completion intent.

Private completion metadata retains canonical viewing identity and the title revision produced by completion, even if the viewing or outing is later removed. An operation receipt preserves immutable intent and accepted identity; its response also reads current owned outing/viewing/title state. A missing current viewing remains absent. A retry must not recreate it from an old receipt snapshot. The existing web `complete_due_outings` entry point uses the same private completion core.

Migration `20261008213850_outing_completion_viewing_revision.sql` also records the viewing revision at its original creation. New receipts expose that immutable minimal viewing effect (`id`, `user_id`, `title_id`, `outing_id`, `updated_at`) for dependent edits, including when another device later receives `already_completed`. This never substitutes a newer current viewing revision: a later note or rating still causes an older dependent edit to conflict. Historical completions have no inferred version and omit this effect; already accepted receipts remain unchanged. Those cases need explicit review before retargeting pending provisional edits. New completion notifications identify both `outingId` and `canonicalViewingId`; clients must not delete unrelated or unidentifiable historical notifications by title alone.

Native acknowledgment atomically stores the provisional-to-canonical alias, replaces only a proven unpushed provisional event, retargets pending exact-event edits/deletes, reapplies pending values, and acknowledges the command. Aliases remain available for stale UI references and retry after process death. A conflict retains the user's provisional history for explicit review.

Migration `20261008195605_canonical_outing_revert.sql` adds:

```text
revert_cinema_outing(p_outing_id uuid, p_operation_id uuid,
  p_expected_updated_at timestamptz, p_expected_viewing_id uuid,
  p_expected_viewing_updated_at timestamptz,
  p_expected_operation_id uuid default null,
  p_expected_viewing_operation_id uuid default null) -> jsonb
```

Revert compares the outing revision, canonical viewing identity and viewing revision. A newer note/date edit or any rating conflicts rather than being silently deleted. A previously removed event uses a null viewing revision and retains its canonical identity. It restores the prior title status only when the title revision still matches the one produced by completion and no other viewing or completed trip remains, preserving later intentional same-status changes. Older completions without a proven title revision do not restore title status. Legacy completion queues lack sufficient intent and enter explicit recovery rather than being replayed as full-row updates.

Migration `20261008211455_causal_outing_revert.sql` adds the two optional receipt dependencies. For the outing, exactly one of the literal revision or preceding operation UUID is required. For a present viewing, use either its observed revision or its own preceding operation UUID; an absent viewing uses neither. Dependencies must identify the exact owned row in the preceding receipt and cannot refer to the reversal itself. A deleted-row receipt is not a revision. Later edits still conflict, and a rated viewing remains protected even when its rating is in the referenced receipt. The original five-argument calls and their accepted receipts remain compatible.

Both APIs return `status` (`applied`, `conflict`, `missing`, or completion-only `already_completed`), `operationId`, immutable `request`, `outingId`, `canonicalViewingId`, and current `outing`, `viewing`, and `title` objects. The compact title contains `id`, `status`, and `updated_at`. Accepted results retain receipt `rows`; these are original causal versions, not the current graph. Revert also reports `titleStatusRestored`. Fresh current fields, not old receipt rows, govern reconciliation. Applied operation retries are immutable; conflict/missing results are not retained as accepted receipts.

Required evidence: native-first and web-first convergence, two devices and lost responses, timezone capture and midnight boundaries, reschedule/cancel conflicts, exact viewing deletion before retry, pending edits/deletes retargeted after aliasing, restart recovery, owner switches, and no historical duplicate cleanup. Local PostgreSQL tests alone cannot establish production concurrency or device lifecycle behavior.

Local embedded PostgreSQL coverage now passes native-first/web-first convergence, immutable operation conflicts, captured-zone dates, current edited-viewing responses, deleted viewing/outing retries, rescheduled/cancelled/future/missed conflicts, ambiguous historical event retention, UUID collision rollback and owner/auth boundaries. These sequential local cases do not prove simultaneous pooled HTTP execution.
