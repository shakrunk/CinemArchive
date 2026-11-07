# Outing polls with friends

Offer a few movie and time candidates to linked friends, let everyone tick the ones that work for
them, close on a winner, and turn the winner into a real outing (#203). Implemented by migration
`20261102000000_outing_polls.sql`; the clients are Android (`OutingPollsRepository`,
`OutingPollsSheet`) and web (`lib/outingPolls.ts`, `OutingPolls.tsx`).

## Shape

- A poll has a question (optional, up to 100 characters, defaulting to "Which one works?"), **2 to 4
  options** and **1 to 8 invited friends**. The owner may have at most five polls open at once.
- An option is one of the owner's own **movies** (not a series) at a **future showtime** with an
  optional theater (up to 100 characters). The server copies the title, year, poster and TMDB id into
  the option, so friends see them without any access to the owner's library. Two options cannot be the
  same movie at the same time.
- Voting is **approval style**: a participant ticks every option that works. Casting votes replaces the
  caller's whole set, so an empty list clears them. Only the owner and the invited friends can vote,
  only while the poll is open, and only for options whose showtime is still ahead.
- The owner **closes** the poll on a winning option (also still ahead) or **cancels** it by naming
  none. The app shows who is leading, but the owner decides. Closing records the winner and tells the
  members; it writes nothing else.

## Making the outing

Closing does not create an outing. The owner's client offers **Schedule this**, which opens the usual
schedule sheet for the winner's movie with the showtime, theater and the friends who ticked it filled
in. From there the existing flows apply: the owner saves the outing and may invite the voters with
"Invite to join" ([shared-outings.md](shared-outings.md)). If the movie has since left the owner's
library, the schedule step cannot start from it.

## What is shared

Participants see the question, who owns it, the options, each option's count and the **display names**
of the people who ticked it, and (the owner) the invited friends. A participant can therefore see the
names of other invitees through their votes even if those two are not friends with each other. They
never see the owner's library, ratings, tickets, notes or anything else. The poll is not part of any
outing's share snapshot.

## Tables and RPCs

`outing_polls`, `outing_poll_options`, `outing_poll_members`, `outing_poll_votes`. RLS is on with **no
policy and no grant**, so clients have no table access at all; every read and write goes through these
RPCs (all require an authenticated caller; `42501` for "not yours or not found", `22023` for invalid
input or a closed poll):

- `create_outing_poll(p_question, p_options jsonb, p_friend_ids uuid[])`. Options are
  `{ "title_id", "showtime", "venue"? }`. Every friend must be an accepted friend; notifies each one
  (`outing_poll`). Returns `{ poll_id }`.
- `cast_poll_votes(p_poll_id, p_option_ids uuid[])`. Replaces the caller's votes.
- `close_outing_poll(p_poll_id, p_option_id default null)`. Owner only. Notifies every member
  (`outing_poll_closed`, with the winner or `cancelled`).
- `get_outing_polls()`. Polls the caller owns or was asked into that were created in the last 30 days,
  newest first, each with members and options carrying `votes`, `mine` and `voters`.

Unfriending or blocking removes the person from the owner's polls and deletes their votes, in both
directions. Both clients are **online-only**: polls are shared server records with no local copy.

## Not included

No reminders to vote, no automatic close, no change of options once sent, and no free-text options. A
poll cannot offer a movie that is not in the owner's library; add it first.

## Evidence

`apps/web/scripts/outing-polls.test.mjs` (embedded PostgreSQL, part of `npm run test:db`) pins creation
and its limits, approval voting and replacement, participant and showtime checks, closing and
cancelling with their notifications, the 30 day list window, the table refusals and the unfriending
cleanup. Android pins the rules in `PollRulesTest` and the requests and parsing in
`OutingPollsRepositoryTest`; web pins the same in `outingPolls.test.ts` and `OutingPolls.test.tsx`.
Nothing here has been run against a live Supabase project, on a device or in a browser. Deploy the
migration before shipping either client.
