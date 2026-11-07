# Group post-show reactions

After a shared outing ([shared-outings.md](shared-outings.md)), the people on it can share how it
went, a star rating and a short note, and see each other's (#207). Implemented by migration
`20261101000000_shared_outing_reactions.sql`; the clients are Android (`GroupReactionsSection`,
`SharedOutingsRepository`) and web (`GroupReactions.tsx`, `lib/sharedOutings.ts`).

## Shared on purpose

A reaction is **never** read from or written to anyone's private viewing log. The post-show sheet's
own rating and note stay private, exactly as before; they only **prefill** the group fields, and
nothing is shared until the person presses "Share with the group". Accepting an invitation does not
add the film to a member's library, and sharing a reaction does not either. Taking a reaction back
removes it for everyone; the private viewing is untouched.

## Who and when

- **Participants** are the owner and members whose status is `accepted`. Only they can write or read
  reactions. An invited-but-not-accepted member, a declined member and strangers get `42501`.
- **Open** once the show has ended (`ends_at` passed) or the owner's outing completed, and **closed**
  for a cancelled outing (`22023`). Before then the panel is not offered.
- A member who declines, is removed by the owner, or is unfriended takes their reaction with them. The
  owner's own reaction stays as long as the shared outing does.

## Table and RPCs

`shared_outing_reactions(shared_outing_id, user_id, rating, note, created_at, updated_at)`, one row per
person per outing. `rating` is half stars from 0.5 to 5.0 or null; `note` is 1 to 500 characters or
null; at least one is present. Clients have **no table access at all**: RLS is on and no grant is
given, so everything goes through three RPCs (all require an authenticated caller):

- `share_outing_reaction(p_shared_outing_id, p_rating, p_note)`. Upsert of the caller's own reaction.
  The note is trimmed and blank becomes null. Invalid input is `22023`. Repeating the same values
  changes nothing but `updated_at`.
- `withdraw_outing_reaction(p_shared_outing_id)`. Deletes the caller's own reaction. Idempotent.
- `get_outing_reactions(p_shared_outing_id)`. Everyone's reaction among the current participants,
  oldest first, each with the person's display name and `is_me`. A participant sees others'
  reactions whether or not they have shared their own.

## Where it appears

- **A member** sees "How was it?" on their going card in "Shared with you" once reactions are open.
  That card now stays in `get_shared_outings` for **seven days** after the show ends (it was two), so
  there is time to react.
- **The owner** sees the panel in the post-show sheet, but only for an outing they shared with at
  least one friend. Its rating and note fields are prefilled from the private ones.

Both clients are online-only here, as for shared outings.

## Not included

Reactions are not notified, edited into the shared snapshot, or shown outside an outing's
participants. There is no reveal-after-you-share rule, and no threaded replies.

## Evidence

`apps/web/scripts/shared-outing-reactions.test.mjs` (embedded PostgreSQL, part of `npm run test:db`)
pins the open and closed rules, participant checks, validation, the shared read, update and
withdrawal, the table refusals, the departed-member cleanup and the seven-day card window. Android
pins the rules in `ReactionRulesTest` and the requests and parsing in `SharedOutingsRepositoryTest`;
web pins the same in `sharedOutings.test.ts` and `GroupReactions.test.tsx`. Nothing here has been run
against a live Supabase project, on a device or in a browser. Deploy the migration before shipping
either client.
