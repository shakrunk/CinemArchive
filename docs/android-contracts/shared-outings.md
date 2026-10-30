# Shared outings with linked friends

Invite accepted friends to an upcoming cinema outing so it shows on their Up Next once they accept
(#206). It is the foundation for group reactions, polls and sending tickets (#207, #203, #220).
Implemented by migration `20261031000000_shared_outings.sql`; the clients are Android
(`SharedOutingsRepository`, `OutingInviteSection`, `SharedOutingsSection`) and web
(`lib/sharedOutings.ts`, `OutingInvites.tsx`, `SharedWithYou.tsx`).

## Why a separate record

`cinema_outings` stays owner-only: its booking reference, price, notes, seats and ticket reference
are never shared, and friends get no RLS read on it. An invitation instead creates a **shared
outing** that carries only the snapshot the in-app plan sharing already exposes
([outing-plan-sharing.md](outing-plan-sharing.md)): `tmdb_id`, `type`, `title`, `year`,
`poster_url`, `venue`, `format` and companion display names, plus the showtime and end time. It never
contains companion account identifiers, seats or tickets.

## Tables

- `shared_outings(id, owner_id, outing_id, status, showtime, ends_at, snapshot, ...)`. One per
  outing (unique `outing_id`). `status` is `scheduled`, `completed` or `cancelled`. `outing_id` is set
  to null, and `status` to `cancelled`, if the owner deletes the outing.
- `shared_outing_members(shared_outing_id, user_id, status, invited_at, responded_at)` with status
  `invited`, `accepted` or `declined`.

Clients have **read-only** access. A shared outing is readable by its owner and by a member who is
invited or accepted (a declined member loses sight of it). A member reads only their own membership
row; the owner reads every member. All writes go through the RPCs below.

## RPCs

All require an authenticated caller. Errors use SQLSTATE `42501` for "not yours or not found",
`22023` for invalid input or a closed outing.

- `invite_friends_to_outing(p_outing_id, p_friend_ids[])`. The caller must own the outing, which must
  be scheduled and not ended, and every id must be an accepted friend other than the caller. Creates
  the shared outing if needed, invites each friend, and notifies the newly invited. **Idempotent**:
  a friend already invited or accepted is left alone and not notified again; a friend who declined is
  invited again and notified. At most 8 people may be invited or accepted at once.
- `respond_to_outing_invite(p_shared_outing_id, p_accept)`. Only the invited member. `invited` to
  `accepted` or `declined`, or `accepted` to `declined` (leaving). Repeating the same answer returns
  the status without notifying. A declined member cannot re-accept without a new invitation, and
  nothing can be answered once the outing is cancelled, completed or ended. Notifies the owner.
- `remove_outing_member(p_shared_outing_id, p_user_id)`. Owner only.
- `get_shared_outings()`. The caller's invited or accepted outings that ended less than two days ago
  or are upcoming, with `owner_name`, `my_status` and the snapshot.
- `get_outing_members(p_outing_id)`. The owner's members with display names and status.

## Staying in step

The owner's edits to showtime, end time, venue, format, companions and status update the snapshot. A
cancelled or "didn't make it" outing marks the shared one `cancelled`; a completed one marks it
`completed`. Members see the current data; they are not notified of ordinary edits. Unfriending or
blocking removes the members between those two people in both directions.

## Notifications

Two new types: `outing_invite` (to the invitee, payload is the snapshot plus `shared_outing_id`,
`showtime` and `ends_at`) and `outing_invite_response` (to the owner, payload `shared_outing_id`,
`accepted`, `title`, `showtime`). An invite opens Up Next on both clients; a response opens the title.

## Consent and privacy

Invite then accept: being named on someone's outing never puts it on a friend's Up Next without
their say-so. Accepting adds nothing to the member's own library or ledger. Online-only: there is no
local copy on either client, so Up Next shows the section when the server can be reached.

## Not included

The outing's own companion names are unchanged: the invited members are the link, and an accepted
member is not yet written into the owner's `companions` as a linked friend. Group ratings, polls and
sending tickets build on this record in later steps.

## Evidence

`apps/web/scripts/shared-outings.test.mjs` (embedded PostgreSQL, part of `npm run test:db`) pins
the snapshot contents, validation, idempotency, answers, visibility, owner edits, unfriending and
the direct-write refusals. Android pins the rules in `SharedOutingRulesTest` and the requests and
parsing in `SharedOutingsRepositoryTest`; web pins the same in `sharedOutings.test.ts` and
`SharedWithYou.test.tsx`. Nothing here has been run against a live Supabase project, on a device, or
in a browser. Deploy the migration before shipping either client.
