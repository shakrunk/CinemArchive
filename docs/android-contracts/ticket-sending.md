# Sending a ticket to friends

One person buys the group's tickets, or a ticket has to reach someone meeting the group there (#220).
The owner sends an outing's attached ticket (the photo and its decoded barcode) to accepted friends,
sees who was sent it and who has opened it, and can take it back. Implemented by migration
`20261103000000_ticket_sending.sql`; the clients are Android (`TicketSharingRepository`,
`SendTicketSheet`, `ReceivedTicketsSection`) and web (`lib/ticketSending.ts`, `SentTickets.tsx`). It
builds on the private ticket attachments ([ticket-attachment-api.md](ticket-attachment-api.md)).

## What a send is

Tickets stay owner-private by default. A send creates a **grant** on one attached ticket attachment for
one friend. Nothing is copied: the friend reads the owner's existing private object. A grant lets that
friend read that attachment's bytes (through the Storage read policy) and descriptor (through the open
RPC) and **only while all of these hold**:

- it has not been taken back,
- the attachment is still the outing's **attached** ticket, so replacing or removing the ticket, or
  deleting the outing, ends every grant on the old one, and
- the owner and the friend are still **accepted friends**, so unfriending or blocking ends access, and
  re-accepting the friendship restores a grant that was not taken back.

Seats, the booking reference, price and notes are **never** shared, and neither does a notification or a
list carry the code: the barcode and the file come only from opening a ticket.

## Tables and RPCs

`ticket_grants(attachment_id, outing_id, owner_id, recipient_id, created_at, opened_at, revoked_at)`,
unique per attachment and friend. RLS is on with no grant, so clients have no table access. All RPCs
need an authenticated caller; `42501` is "not yours, not found or not allowed", `22023` is invalid input.

- `send_ticket(p_outing_id, p_recipient_ids[])`. The caller owns the outing and it has an attached
  ticket; every recipient is an accepted friend other than the caller; at most 8 live grants per ticket.
  **Idempotent**: a friend who already holds a live grant is left alone and not notified again; a friend
  whose grant was taken back is sent it again, `opened_at` resets, and they are notified. Returns the
  owner's grant list.
- `revoke_ticket_grant(p_grant_id)`. Owner only; idempotent; access ends at once.
- `get_ticket_grants(p_outing_id)`. The owner's live grants for the outing's current ticket with each
  friend's name, `sent_at` and `opened_at`.
- `get_received_tickets()`. Live grants sent to the caller for shows that end less than a day ago or
  later: sender, title, year, poster, showtime and venue. No barcode and no file.
- `open_received_ticket(p_grant_id)`. Returns the attachment descriptor (object key, type, size,
  digest, decoded barcode) with the title, showtime and venue. The **first** open records `opened_at`
  and notifies the sender once; opening again changes nothing.

The recipient then downloads the object with the normal authenticated Storage download; the read policy
admits the owner as before and a recipient with a live grant. Uploads, overwrites and deletions are
unchanged and still the owner's alone.

## Notifications

`ticket_received` (to the friend, payload `grant_id`, `title`, `showtime`, `venue`) and `ticket_opened`
(to the sender on the first open, payload `grant_id`, `title`). A received ticket opens Up Next on both
clients. The notification type list in this migration is a superset of every type defined so far, so
applying migrations out of order never drops one.

## Where it appears

- **The owner** sees "Send to a friend" on the private ticket screen (Android) or in the ticket viewer
  (web), only when the outing has an attached ticket. Each friend row shows Sent, "opened" and Take back.
- **A friend** sees "Tickets from friends" on Up Next. Opening one shows the photo and, when a code was
  read from it, the barcode rendered from that code.

Both clients are online-only here, like the other friend features.

## Not included

No sending of a ticket that is not yet attached or only on the legacy device-local path, no
per-friend seat, no expiry other than the show, and no notice to a friend when a ticket is taken back
(it simply stops opening). A friend could still copy what they saw; taking a ticket back stops later
access, not memory of it.

## Evidence

`apps/web/scripts/ticket-sending.test.mjs` (embedded PostgreSQL, part of `npm run test:db`) pins the
sender and recipient checks, that notifications and lists carry no code, the Storage read policy for a
recipient, the open and notify-once behavior, taking back and re-sending, replacing the ticket,
unfriending, the one-day list window and the table refusals; the existing ticket attachment and
causal-guard suites still pass with the changed policy. Android pins the rules in
`TicketSendingRulesTest` and the requests and parsing in `TicketSharingRepositoryTest`; web pins the
same in `ticketSending.test.ts` and `SentTickets.test.tsx`. The Storage model in the SQL tests covers
policies only, not the HTTP service, and nothing here has been run against a live Supabase project, on
a device or in a browser. Deploy the migration before shipping either client.
