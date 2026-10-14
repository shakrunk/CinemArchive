# Saved outing review

Migration `20261008194039_outing_review_resolution.sql` provides the authenticated RPC:

```text
resolve_outing_fields(p_outing_id uuid, p_expected_updated_at timestamptz,
                     p_patch jsonb, p_operation_id uuid) -> jsonb
```

This resolves an explicitly reviewed selection of fields from a retained legacy change. It never creates a missing outing. Clients show the current remote plan beside the saved intent, start with no selected fields, and preserve an exportable owner-scoped copy before resolving or discarding anything.

The patch uses database names: `showtime`, `previews_minutes`, `runtime_minutes`, `venue`, `format`, `ticket_price`, `seat`, `auditorium`, `seat_row`, `seats`, `booking_ref`, `notes`, and `companions`. Omission preserves the current value; explicit null clears nullable fields. Companions are objects with `name` and optional UUID `friendUserId`. Selecting a legacy name-only list explicitly replaces any current linked identities. Schedule changes recompute `ends_at` on the server. Ownership, lifecycle, viewing IDs, ticket attachment fields and legacy ticket paths/barcodes are forbidden.

Results include `status` (`applied`, `conflict`, or `missing`), `operationId`, immutable `request`, and `outing` (the owned database row or null). A conflict returns the current plan without changing it. A missing or foreign plan returns null and is never recreated. A refreshed comparison and revised selection use a new operation UUID.

Applied results also include canonical `rows` and are stored in the shared owner/operation receipt namespace. Repeating the original request returns its original result, even after subsequent edits or deletion. Reusing an applied operation UUID for different input fails. A no-op selection creates a receipt without advancing the outing revision. Validation or database failure rolls back both the changes and receipt. Conflicts and missing rows do not retain a receipt.

**An applied receipt proves the selected intent was accepted; it is not the current remote state.** After an applied response, clients fetch the current owner row before reconciling their local mirror. Absence stays absent. Later pending local intent is reapplied without restoring the receipt's old snapshot. Persist the original envelope and attempted operation before sending so an interrupted response can be resolved after restart.

The public wrapper is an invoker; the private definer has an empty search path and grants execution only to authenticated users. Local PostgreSQL cases cover owner isolation, immutable retries, stale comparisons, deletion, explicit clears, private-field rejection, schedule recalculation, companions and rollback. They do not establish live pooled HTTP concurrency or native device recovery acceptance.
