# Outing plan delivery

The shared migration `20261008184253_outing_share_receipts.sql` adds an authenticated, receipt-backed overload:

```text
share_outing_plans(p_outing_id uuid, p_recipient_ids uuid[], p_operation_id uuid) -> jsonb
```

The result is the actual public snapshot placed in each recipient notification: `tmdb_id`, `type`, `title`, `year`, `poster_url`, `showtime`, `ends_at`, `venue`, `format`, `seat`, and companion display names. It contains no booking reference, ticket data, private notes, price, or companion account identifiers.

Clients allocate one operation UUID for each deliberate send intent and retain it after a failed or unknown response. Retrying that UUID with the same outing and recipient set returns the first committed snapshot without delivering another notification. Recipient order and duplicate entries do not change the request identity. Reusing the UUID for a different outing or recipient set fails. An explicit **Share again** creates a new UUID and sends the current server snapshot.

If the first attempt never committed, a retry can send a newer server snapshot. Therefore clients display the returned snapshot as confirmation rather than assuming that their original local preview was delivered. Clients currently retain attempts only for the lifetime of the sharing dialog/controller; this is not a restart-durable notification queue. They do not automatically retry notifications after restart.

For new delivery, the server checks the caller owns the outing and title, the outing is scheduled and has not ended, and every recipient is an accepted friend other than the caller. Validation, immutable receipt, snapshot and notifications commit in one transaction. A recipient failure rolls everything back. The receipt primary key serializes simultaneous attempts; local PostgreSQL tests exercise retry and rollback, but a multi-connection production concurrency test has not been run.

An existing receipt acknowledges a delivery already completed, even if the outing or friendship later changes. It never sends again. Receipt rows are inaccessible to clients directly; the public wrapper is an invoker and the implementation is a definer in the unexposed private schema with an empty search path. Each owner has a separate operation namespace.

The two-argument overload remains available to older clients and allocates a new UUID on every call, preserving explicit resend behavior without supplying retry deduplication. Deploy the migration before the new clients; no production deployment is included in this implementation checkpoint.

Both clients check current account identity and current local outing state and refuse in-app delivery while relevant title/outing edits remain pending. Web additionally refreshes its base snapshot under the cross-tab delivery lock before sending and pins the request to that owner token. Native reads the local plan and pending queue in one Room transaction and rechecks after loading recipients. Server-side validation remains authoritative. A returned response is discarded if its account generation is no longer current.

Executable SQL evidence lives in `apps/web/scripts/outing-share-receipts.test.mjs`. Client fixtures cover pending edits, account switches, receipt retries and explicit resend. Broader outing completion reconciliation, companion account identity preservation, wire-format normalization and portable private ticket storage remain separate parity work.
