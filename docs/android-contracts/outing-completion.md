# Canonical outing completion

Planned shared contract for the current full-parity work. Implementation and device acceptance are still in progress; existing client completion paths are not yet convergent.

An offline native completion creates a provisional viewing and one durable completion command in the same Room transaction. It records the current outing revision, a stable operation UUID, the provisional viewing UUID, and the device's IANA timezone at that moment. Retries never substitute a newly observed timezone. The server derives the viewing date from the locked outing showtime and recorded zone.

```text
complete_cinema_outing(p_outing_id uuid, p_operation_id uuid,
  p_provisional_viewing_id uuid, p_expected_updated_at timestamptz,
  p_tz text default 'UTC') -> jsonb
```

The server locks the owner outing and title. First completion requires a still-scheduled, due outing with the expected revision. Already-completed outings return their canonical viewing identity without creating a second event. Rescheduled, cancelled, missed or uncertain historical plans require reconciliation. Historical independent viewings are never heuristically merged or deleted.

Private completion metadata retains canonical viewing identity and the title revision produced by completion, even if the viewing or outing is later removed. An operation receipt preserves immutable intent and accepted identity; its response also reads current owned outing/viewing/title state. A missing current viewing remains absent. A retry must not recreate it from an old receipt snapshot. The existing web `complete_due_outings` entry point uses the same private completion core.

Native acknowledgment atomically stores the provisional-to-canonical alias, replaces only a proven unpushed provisional event, retargets pending exact-event edits/deletes, reapplies pending values, and acknowledges the command. Aliases remain available for stale UI references and retry after process death. A conflict retains the user's provisional history for explicit review.

The follow-up revert API must compare outing revision, canonical viewing identity and viewing revision. A newer rating, note or date edit must not be silently deleted. It restores the prior title status only when the title revision still matches the one produced by completion, preserving later intentional same-status changes. Legacy completion queues lack sufficient intent and enter explicit recovery rather than being replayed as full-row updates.

Required evidence: native-first and web-first convergence, two devices and lost responses, timezone capture and midnight boundaries, reschedule/cancel conflicts, exact viewing deletion before retry, pending edits/deletes retargeted after aliasing, restart recovery, owner switches, and no historical duplicate cleanup. Local PostgreSQL tests alone cannot establish production concurrency or device lifecycle behavior.
