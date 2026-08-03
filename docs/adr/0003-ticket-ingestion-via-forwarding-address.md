# ADR 0003: Ticket ingestion via a forwarding address, not Gmail OAuth

- **Status:** Accepted — not yet implemented
- **Date:** 2026-08-03

## Context

Cinema tickets arrive as vendor confirmation emails (Fandango, AMC, Cinemark, …). Today
every field of a `cinema_outings` row — title, showtime, venue, auditorium, row, seats,
booking reference — is typed in by hand, even though the email already contains all of it
in machine-readable form.

GitHub [#223](https://github.com/shakrunk/CinemArchive/issues/223) originally proposed
reaching that data with Google OAuth: a `gmail.readonly` grant, tokens stored server-side,
an Edge Function polling Gmail for known vendor senders and parsing the `schema.org`
JSON-LD out of matching messages.

Two other ingestion paths are already filed and want the same destination:
[#219](https://github.com/shakrunk/CinemArchive/issues/219) (photograph the ticket, decode
the barcode on-device) and manual entry (status quo).

## Decision

**Ingest forwarded email at a per-user address we control, and do not build the Gmail
OAuth integration.**

Concretely:

1. A per-user opaque address, `tix-<token>@tickets.kumarfamilynet.work`. The token — not
   the `From:` header, which is trivially forgeable — is what attributes an inbound
   message to an account.
2. Cloudflare Email Routing receives mail on that subdomain and hands it to a Worker,
   which forwards the raw message to a Supabase Edge Function. We already own
   `kumarfamilynet.work` (see `apps/web/public/CNAME`).
3. The Edge Function parses the message into a **candidate**, never directly into a
   `cinema_outings` row, and the user confirms it in-app before anything is created.
4. Settings gains the address, a copy button, and a rotate action (rotating invalidates
   the old token, which is the revocation story).

Parsing is a separate concern from ingestion and is shared by every path:

```
forwarded email ─┐
ticket photo (#219) ─┼→ parser → ticket_import_candidates → user confirms → CinemaOuting
manual entry ────────┘  (bypasses the parser; goes straight to the outing form)
```

## Rationale

**The compliance surface is an order of magnitude smaller.** `gmail.readonly` is a Google
*restricted* scope, not merely a sensitive one. An unverified app is capped at 100 users
and, while its OAuth consent screen is in testing status, issues refresh tokens that
expire on a ~7-day cycle — meaning the user re-consents roughly weekly, forever. Moving to
production status with a restricted scope pulls in Google's OAuth verification process and
a recurring third-party security assessment. For a personal app with a handful of users
that is a permanent tax on a feature that saves a minute of typing. (Google's exact
current requirements should be re-checked before anyone revisits this, but the direction
has been stable for years.)

**We never take custody of inbox credentials.** With OAuth we would hold an encrypted
token granting read access to a user's entire mailbox, and inherit the obligation to
protect, scope, audit and revoke it. With forwarding we only ever receive the specific
messages a user chose to send us. The blast radius of a compromise is a handful of movie
tickets rather than a mailbox.

**The friction argument against forwarding does not survive contact with mail filters.**
#223 rejected this option because it "requires the user to manually forward each email."
It does not: the user writes **one** filter, once —

> `from:(fandango OR amc OR cinemark) → Forward to tix-<token>@tickets.kumarfamilynet.work`

— and every future ticket arrives automatically. Gmail, Outlook, iCloud, Proton and
Fastmail all support this; OAuth would have covered Gmail alone.

**Forwarding gets the attachments, and the attachments are the good data.** These
confirmations frequently carry an Apple/Google Wallet pass. A `.pkpass` is a zip
containing `pass.json` with the *actual barcode payload* plus structured seat, row and
auditorium fields — dramatically more reliable than scraping `schema.org` markup out of
marketing HTML, and the only route to a code that will actually open a turnstile. The
Gmail API could fetch attachments too, but the point stands that the highest-value payload
is one the forwarding path gets for free.

**Ingestion is pluggable, so this is not a one-way door.** The parser and
`ticket_import_candidates` are shared. If forwarding proves insufficient in practice, a
Gmail adapter can be added in front of the same pipeline without redoing the expensive
part.

## Consequences

- A new inbound-mail dependency (Cloudflare Email Routing + a Worker) joins the stack. It
  is the first piece of infrastructure in this project outside Supabase and GitHub Pages.
- The per-user token is a bearer secret in an email address, which will appear in plain
  text in the user's own filter configuration and in `To:` headers along the delivery path.
  It therefore grants exactly one capability — *submit a candidate for review* — and
  nothing that acts without confirmation. Rate limiting is required.
- Anyone who learns a user's address can submit spam candidates. Because nothing is
  auto-created, the worst outcome is noise in a review queue, cleared by rotating the
  token.
- Users on providers without forwarding rules fall back to forwarding by hand, which is
  still less work than retyping the ticket.
- We inherit the usual inbound-email chores: size limits, MIME parsing, quoted-printable,
  and the fact that "forwarded" formatting varies by client.
- #223 is rewritten to describe this approach; the OAuth design is retained there as the
  rejected alternative so the reasoning is not lost.

## Alternatives considered

- **Gmail OAuth (`gmail.readonly`)** — the original #223 proposal. Lowest per-use friction
  and uniquely able to backfill inbox history. Rejected on the verification, assessment,
  re-consent and token-custody costs above.
- **IMAP polling** — avoids Google's review process but requires the user to hand over a
  mail password or app password, a strictly worse trust model than either alternative.
- **Manual entry only (status quo)** — zero cost, but the friction is the entire point of
  the issue.
