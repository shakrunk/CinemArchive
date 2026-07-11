# Cinema Outings — "I've Got Tickets" (Requirements + Implementation Plan)

**Status:** Requirements locked — ready for task-level breakdown
**Date:** 2026-07-11
**Author:** Design session (goal: ticket-tracked watchlist → watched transitions)

> **For agentic workers:** This document is the combined design spec *and* phased
> implementation plan. Implement phase-by-phase (§12); each phase ends with the
> verification gate `rtk tsc` + `rtk npm run lint` + `rtk vitest` + `rtk npm run build`
> all green before committing.

---

## 1. Goal & story

> *"Just got tickets to a movie that was in my watchlist… it should automatically be
> moved from watchlist to watched after the ticket time + previews + runtime is up.
> Might be nice to put what theater I watched it in and who I watched it with. Get a
> little notification after asking how I liked it / if I'd recommend it to my friends."*

CinemArchive already knows what's on the watchlist and (via TMDB) how long each movie
runs. What it can't represent today is the moment between *"I plan to watch this"* and
*"I watched this"*: **a booked cinema trip**. The gap forces manual bookkeeping at the
exact moment the user least wants to do it — walking out of a theater at 11 PM.

**Cinema Outings** closes that loop:

1. **Schedule** — "I've got tickets": pick the showtime, theater, format, and who's coming.
2. **Anticipate** — the outing lives on the Up Next marquee with a countdown and an
   add-to-calendar button; during the show it reads *Now Showing*.
3. **Rally** — share your ticket info with friends — in-app to accepted friends or as
   a text snippet (*"I'm in H12 — grab a seat nearby"*) — so they can book adjacent
   seats, one-tap-prefill their own outing, or add it to their calendar.
4. **Auto-complete** — when `showtime + previews + runtime` passes, the title moves
   `watchlist → watched` automatically, a **viewing is logged with the theater and
   companions**, and a notification lands in the bell inbox.
5. **Follow up** — the notification (and an Up Next card) asks *"How was it?"* — one
   tap to rate, jot a note, or send a recommendation to friends who weren't there.

### 1.1 Acceptance walkthrough (the headline flow, end to end)

> *Dune Part Three* is on the watchlist. Thursday I buy tickets for **Friday 7:30 PM
> at AMC Georgetown, IMAX, with Alex and Sam**. I open the title, tap **🎟 I've got
> tickets**, fill the form (runtime 166 min prefilled, previews 20), and see "Lets
> out ≈ 10:36 PM". Up Next now leads with a marquee card counting down; I tap **Add
> to calendar**. Sam hasn't bought his ticket yet, so I tap **Share plans**: his bell
> shows my showtime, theater, and seat, with an **I've got tickets too** button that
> prefills his own form. Friday at 7:30 the card reads **NOW SHOWING**. I leave my phone
> alone. Saturday morning I open the app: the title is **watched**, the timeline
> shows *Jul 17 — at AMC Georgetown · with Alex & Sam · IMAX*, and the bell shows
> *"Dune Part Three just let out — how was it?"* I tap it, rate ★★★★½, type "IMAX
> sandworms", hit **Recommend to friends**, and send it to Priya (Alex and Sam are
> annotated "was there with you"). Total bookkeeping done by me: zero.

If any step of that walkthrough requires more taps than described, the
implementation isn't done.

---

## 2. Locked product decisions

| Decision | Choice |
|----------|--------|
| **Feature name** | *Cinema Outings*. UI language: the Up Next section is **"On the Marquee"**; a completed outing renders as a **ticket stub** on the viewing timeline. |
| **Scope (v1)** | Movies only (`type === 'movie'`). TV theatrical events are a future note (§11). |
| **Auto vs. confirm** | **Auto-complete** (as requested): the transition happens without asking. Safety valve is a prominent **"Didn't make it"** revert on the post-show card/notification (§4.4). A "confirm first" preference is deferred (§11). |
| **End-of-show instant** | `ends_at = showtime + previews_minutes + runtime_minutes`. Previews default **20 min** (editable per outing); runtime snapshots `title.runtime` at scheduling (editable). |
| **Where the record lives** | A new `cinema_outings` table (one row per trip), plus **`venue` / `companions` columns on `viewings`** so the theater and company become part of the permanent viewing timeline — and any viewing (even a couch rewatch) can carry "watched with". |
| **Companions** | Chips: free-text names, with autocomplete from past companions **and** accepted friends (friend picks store `friendUserId`). No invite/social mechanics in v1. |
| **Sharing plans (pre-show)** | Explicit, per-outing, one-way (§4.10). Two channels: **in-app** to accepted friends via a `share_outing_plans` RPC that pushes a *snapshot* into their notification inbox (no RLS read grant on outings), with an **"I've got tickets too"** prefill CTA; and **out-of-app** via a copy/system-share text snippet + `.ics` for anyone. Snapshot semantics — later edits/cancellations don't propagate. |
| **Theater** | Free-text `venue` with autocomplete from the user's own outing/viewing history. No theater database or showtime API. |
| **Completion mechanism** | Client-triggered, server-executed: a `complete_due_outings()` SECURITY DEFINER RPC is the **single choke point** that logs the viewing, flips the status, and inserts the notification (same philosophy as the existing notification-writing RPCs). The client calls it on load / focus / `online` / a timer at the next `ends_at`. |
| **If the app is closed at end time** | Completion happens on next app open (JAMstack: no server cron in v1). Web Push is an explicitly-scoped stretch phase (§12, Phase G). |
| **Notifications** | New inbox type `outing_completed` (self-notification, no actor) + an ephemeral toast if the app is open at the moment of completion. |
| **Rewatches** | Scheduling an outing for an already-`watched` title is allowed; completion logs the viewing but never *downgrades* status. |
| **Privacy** | `cinema_outings` is owner-only under RLS (no shared-token/friend read). `viewings.venue/companions` inherit the existing viewing visibility (§9). |

---

## 3. Glossary

- **Outing** — one planned cinema trip: a title + showtime + place + people. States:
  `scheduled → completed | missed | cancelled` (§4.2).
- **Now Showing** — derived presentation state while `showtime ≤ now < ends_at`.
- **Marquee** — the Up Next section listing scheduled/now-showing/just-completed outings.
- **Post-show follow-up** — the "How was it?" flow (rate / note / recommend / didn't-make-it).
- **Ticket stub** — the viewing-timeline rendering of a completed outing
  (`at AMC Georgetown · with Alex & Sam · IMAX`).
- **Plan share** — a one-way snapshot of an outing's details (showtime, venue, format,
  seat) pushed to a friend's inbox or shared as text, so they can book nearby seats
  (§4.10). Not an invitation — no RSVP, no shared object.

---

## 4. User-facing behavior

### 4.1 Scheduling — "I've got tickets"

**Entry points**
1. **Title detail drawer** — for a movie with `status === 'watchlist'` (or `watching`),
   a primary action **`🎟 I've got tickets`** next to the existing log-viewing action.
   For `watched` movies the same action appears in the overflow as **`Plan a cinema
   trip`** (rewatch).
2. **Up Next** — a small ticket icon action on watchlist `UpcomingCard`s.
3. **Command palette** — new command **"I've got tickets…"** → picks from watchlist
   movies (all movies searchable) → opens the form.
4. **Add Title workflow (polish)** — after adding a movie to the watchlist, the success
   step offers a *"Got tickets already?"* link into the same form (covers "a friend
   invited me to a movie I hadn't tracked yet").

**The form** (bottom-sheet on mobile / `CinemaModal` on desktop, consistent with
existing modals):

| Field | Required | Default / behavior |
|-------|----------|--------------------|
| Date + showtime | ✔ | Today 19:00, minute-granular. Stored as an absolute instant (`timestamptz`) from the user's local wall time. |
| Theater (`venue`) | — | Text input, autocomplete from distinct past venues (outings + viewings), most-recent first. |
| Companions | — | Chip input; suggestions merge past companion names + accepted friends (friends show avatar/username and store `friendUserId`). |
| Format | — | Segmented/select from fixed list: `Standard · IMAX · 3D · Dolby · 70mm · Drive-in · Other`. |
| Previews buffer | ✔ | Minutes stepper, default 20 (0–120). |
| Runtime | ✔ | Prefilled from `title.runtime`; editable. If the title has no runtime, prefill 120 and show a hint linking to Refresh Metadata. |
| Ticket price | — | Optional currency amount — feeds the Ledger panel's "spent at the movies" stat. |
| Seat | — | Optional short text ("H12"). |
| Booking ref | — | Optional short text ("AMC-4X9KQ2") — the confirmation code you'll need at the box office / kiosk. Shown on the marquee card's overflow and the drawer banner while scheduled. |
| Notes | — | Optional. |

Below the fields, a live line computes the plan: **"Lets out around 9:52 PM."**

**Validation & edge behavior**
- A showtime whose `ends_at` is already in the past is allowed — the form's submit
  button becomes **"Log this outing"** and completion runs immediately (covers "I
  forgot to enter it before the movie").
- A second scheduled outing for the same title is allowed but shows a notice
  ("You already have tickets for {date}").
- Shared/friend views (`isSharedView`) never render scheduling actions.

### 4.2 Outing lifecycle

```
                 ┌────────────┐   ends_at passes    ┌───────────┐
   create ─────► │ scheduled  │ ──────────────────► │ completed │──┐
                 └────────────┘   (auto, via RPC)   └───────────┘  │ "Didn't make it"
                    │      │                              ▲        ▼
        user cancels│      │ user edits showtime          │   ┌────────┐
                    ▼      └──────────(stays)─────────────┘   │ missed │
              ┌───────────┐                 reschedule ◄──────└────────┘
              │ cancelled │                 (back to scheduled, new time)
              └───────────┘
```

- **scheduled** → *Now Showing* is purely derived (`showtime ≤ now < ends_at`); no
  separate state.
- **completed** (the automatic transition) performs, atomically in the RPC:
  1. Insert a `viewings` row: `viewed_at` = the showtime's calendar date **in the
     user's timezone** (client passes its IANA zone), `venue`, `companions`,
     `outing_id` back-reference. No rating yet.
  2. Record the title's current status as `previous_status` on the outing, then set
     the title to `watched` **iff** it isn't already (`watchlist`/`watching`/`dropped`
     all flip — buying a ticket to a `dropped` movie clearly un-drops it; a rewatch of
     a `watched` title changes nothing).
  3. Insert an `outing_completed` notification (recipient = owner, no actor).
  4. Mark the outing `completed` and link `completed_viewing_id`.
- **missed** — reached only from `completed` via **"Didn't make it"** (§4.4): the
  auto-logged viewing is deleted, status reverts (see rules §5.6), and a
  **Reschedule** CTA reopens the form prefilled to create the next attempt (same row,
  back to `scheduled` with a new showtime).
- **cancelled** — user cancels before the show ends. Kept as a row (history), hidden
  from all surfaces except the outing editor's history hint; no stats impact.

### 4.3 Completion timing engine

- The store derives `nextTransitionAt = min(ends_at)` over scheduled outings and arms
  a single `setTimeout` while the app is open — completion fires within a second of
  the credits (well, of the estimate).
- Reconciliation triggers: **app load** (right after `loadUserLibrary`), window
  `focus` / `visibilitychange`, `online` event, and the armed timer. Each trigger
  calls `complete_due_outings(p_tz)`; the RPC is idempotent (`status = 'scheduled'
  and ends_at <= now()`), returns the transitions it made, and the client applies
  them to the store (title status, new viewing, outing state) and refreshes the
  unread notification count.
- **Offline:** a Now Showing/ended outing renders "Finalizing when back online…" and
  completes on the `online` event. No client-side fake completion (single choke point).

### 4.4 Post-show follow-up — "How was it?"

Immediately after completion:

- **Toast** (if the app is open): *"Marked The Long Reel watched — hope it was worth
  the popcorn."*
- **Inbox notification** (`outing_completed`): rendered without an actor —
  poster + *"**{title}** just let out — how was it?"* Tapping opens the **post-show
  sheet**.
- **Up Next card** ("Fresh from the lobby") replaces the marquee card until handled.

**The post-show sheet** (also reachable from the drawer's outing banner):
- ★ star rating (same 0–5 control as log-viewing) → writes `viewing.rating` and
  updates `title.rating` (same semantics as `logViewing`).
- Quick note → `viewing.notes`.
- **`Recommend to friends`** → opens the existing `SendRecommendationPanel` prefilled
  with the title; friends who were companions on this outing are listed but
  de-emphasized ("was there with you") since they've just seen it.
- **`Didn't make it`** → revert path (§5.6) + offer reschedule.
- Dismissing the sheet without rating keeps the Up Next card for up to **14 days**
  (or until rated / explicitly dismissed via its ✕, which stamps
  `follow_up_dismissed_at`).

### 4.5 Up Next — "On the Marquee"

New section ordered **first** among the watchlist groups (most time-sensitive):
Live shows → *On the Marquee* → On your watchlist → Coming soon.

Card anatomy (reuses `CardFrame`):

```
┌──────────────────────────────────────────────────┐
│ ┌────┐  The Long Reel                    (⋯)     │
│ │post│  🎟 TONIGHT · 7:30 PM · IMAX              │
│ │ er │  AMC Georgetown · with Alex & Sam         │
│ └────┘  lets out ≈ 9:52 PM   [ + Add to calendar ]│
└──────────────────────────────────────────────────┘
```

- **Countdown chip:** `in 12 days` → `Friday · 7:30 PM` (≤7 days) → `TOMORROW` →
  `TONIGHT · 7:30 PM` → **`NOW SHOWING`** (amber, subtle pulse; the projection-room
  brand moment) → card swaps to *Fresh from the lobby* on completion.
- **Add to calendar:** downloads a hand-rolled `.ics` VEVENT (no dependency):
  `DTSTART` = showtime, `DTEND` = ends_at, `SUMMARY` = `🎬 {title} — {venue}`,
  `LOCATION` = venue, `DESCRIPTION` = companions + seat + booking ref + notes, plus a
  `VALARM` **2 hours before showtime** — the only pre-show reminder v1 can offer
  (no server push until Phase G), so the calendar does the reminding. Filename
  `{title-slug}-{yyyy-mm-dd}.ics`.
- **Overflow (⋯):** Share plans (§4.10) · Edit tickets · Cancel outing (with confirm;
  cancel is soft, §4.2).
- Watchlist cards for a title with a scheduled outing move out of "On your watchlist"
  into the marquee (no duplicate cards).
- Countdown labels re-derive on a **one-minute interval tick** while any marquee card
  is mounted (a single shared `now` state in the section, not per-card timers); the
  completion itself is driven by the reconciler (§4.3), never by this cosmetic tick.
- Marquee entries count toward the app's smart-landing check (Up Next is the landing
  view when it has content — tickets are content).
- Shared/friend views: the marquee section is not rendered at all (owner-only data).

### 4.6 Title detail drawer

- **Scheduled banner** under the header while an outing is scheduled/now-showing:
  `🎟 Friday, Jul 17 · 7:30 PM · AMC Georgetown · with Alex & Sam — Edit · Cancel`.
- **Viewing timeline** entries render the ticket stub line when present:
  `Jul 17, 2026 · ★★★★½ — at AMC Georgetown · with Alex & Sam · IMAX`. Venue and
  companions become editable in the existing viewing editor (and in manual
  log-viewing) — so home viewings can also record company. This is the generalized
  payoff of putting `venue`/`companions` on `viewings`.
- Post-show sheet opens from the banner while follow-up is pending.
- **Library poster wall:** movies with a scheduled outing get a small amber 🎟 corner
  badge (same treatment tier as existing poster badges) so tickets are visible at a
  glance without opening anything.

### 4.7 Notifications

- New `NotificationType` members `'outing_completed'` and `'outing_plans_shared'`;
  `TYPE_META` gains two entries:
  - `outing_completed` — renders **without the actor prefix** (actor is null): icon
    `Clapperboard`, text *"**{title}** just let out — how was it?"* Click → open
    drawer + post-show sheet.
  - `outing_plans_shared` — renders **with** the actor: icon `Ticket`, text
    *"**{actor}** has tickets to **{title}** — Fri 7:30 PM · AMC Georgetown · seat
    H12"* (fields from the payload snapshot, omitting whatever wasn't filled in).
    Inline CTAs: **I've got tickets too** and **Add to calendar** (§4.10).
- DB: extend the `notifications.type` check constraint (drop
  `notifications_type_check`, re-add with the new values).
- Existing toast stack (`pushNotification`) is used for the moment-of-completion toast.

### 4.8 Ledger — "At the Movies" panel (Phase F)

New opt-in board widget (registered in `panelRegistry` / `WidgetPalette`).
**Source of truth is `viewings`** (what actually happened — includes manually-logged
theater trips and survives outing edits); each viewing joins its outing via
`outingId` for the fields only outings carry (`format`, `ticket_price`). A completed
outing and its viewing are one trip, never two.

- Trips total / this year; a tiny per-year strip.
- **Favorite theater** (most visits) and venue list with counts.
- **Most frequent companion**.
- Format breakdown (IMAX/3D/… chips with counts).
- **Spent at the movies** (sum of `ticket_price` where logged; hidden when none).
  Single-currency by design — amounts render with the user's locale symbol;
  multi-currency is out of scope.

### 4.9 Command palette & shortcuts

- `I've got tickets…` (schedule) and `On the Marquee` (jump to Up Next) commands in
  `src/store/commands.ts`.

### 4.10 Sharing plans — "grab a seat next to me"

The moment after buying tickets is exactly when you want friends to buy theirs. This
is **one-way plan sharing**, not a social outing: no shared object, no RSVP, and no
state added to the sender's outing.

**Entry points:** the marquee card overflow (**Share plans**), the drawer's scheduled
banner, and the schedule form's save confirmation (*"Tickets saved — share your
plans?"*).

**In-app (accepted friends)** — `ShareOutingPanel`, a friend picker modeled on
`SendRecommendationPanel`:
- Calls `share_outing_plans(p_outing_id, p_recipient_ids uuid[])` — a SECURITY
  DEFINER RPC in the exact mold of `send_recommendation`: verifies `auth.uid()` owns
  the outing and `is_friend()` for each recipient, then inserts one
  `outing_plans_shared` notification per recipient whose **payload is a snapshot**:
  `tmdb_id, type, title, year, poster_url, showtime, ends_at, venue, format, seat,
  companion names`. (Booking ref is deliberately **not** shared — it's effectively
  the ticket.)
- The recipient's inbox item (§4.7) offers:
  - **I've got tickets too** — opens *their own* `OutingScheduleSheet` prefilled with
    showtime / venue / format from the payload (seat left blank — that's the part
    they go buy). If the title isn't in their library, it's added via the existing
    add-title path (payload carries `tmdb_id`) — same resolution the recommendation
    inbox already does. Their outing is an independent row; nothing links back.
  - **Add to calendar** — the `.ics` is built client-side from the payload; no access
    to the sender's outing needed.
- **Snapshot semantics:** later edits or a cancellation do **not** propagate or
  retract. Re-sharing after an edit sends a fresh notification. This keeps the RLS
  stance intact (outings stay owner-only; sharing pushes a copy, never grants reads)
  and avoids all sync machinery.

**Out-of-app (anyone)** — **Copy details / system share**: a human-formatted snippet
via the Web Share API (`navigator.share`) where available, clipboard fallback
otherwise:

> 🎬 Dune Part Three — Fri Jul 17, 7:30 PM · AMC Georgetown · IMAX · I'm in H12 —
> grab a seat nearby!

with the `.ics` offered alongside. This covers friends who aren't on CinemArchive —
most of them, realistically — with zero schema involvement.

---

## 5. Rules & edge cases

1. **Timezones/DST:** `showtime`/`ends_at` are `timestamptz` (absolute instants);
   completion is timezone-proof. Only the *viewing date* needs a zone — the client
   passes its IANA zone to the RPC, which derives `viewed_at = (showtime AT TIME ZONE
   p_tz)::date`. A midnight-screening ticket bought for "Thu 12:05 AM" therefore logs
   on Friday's date correctly — whatever the wall clock said.
2. **Runtime drift:** the outing snapshots `runtime_minutes` at scheduling. Refreshing
   title metadata later never silently moves `ends_at`; editing the outing does.
3. **Missing runtime:** form prefixes 120 min with a nudge to Refresh Metadata; the
   snapshot keeps whatever the user confirms.
4. **Double features / duplicates:** multiple scheduled outings per title allowed
   (warned). Each completes independently and logs its own viewing.
5. **Editing after the show started:** allowed until completion (you left the trailers
   late, showtime slipped). After `completed`, timing fields are frozen, but the
   **receipt fields** — `format`, `ticket_price`, `seat`, `booking_ref` — stay
   editable (surfaced in the viewing editor's stub section): the Ledger reads
   `format`/`ticket_price` off the outing, and a typo'd price shouldn't be permanent.
   Everything else post-completion goes through the viewing editor.
6. **"Didn't make it" revert:** deletes the auto viewing
   (`deleteViewingFromDb` + store update), sets outing → `missed`, reverts title
   status to `previous_status` **iff** the current status is still `watched` (if the
   user manually changed status in between, leave it alone), and deletes the now-stale
   `outing_completed` inbox item. Available from the post-show sheet and the inbox
   item for 14 days — but **hidden once the viewing has a rating** (rating asserts
   you saw it; un-rating first re-exposes the escape hatch).
7. **Cancel vs. missed:** cancelling before `ends_at` → `cancelled` (never completed,
   nothing to revert). Missing is only ever the result of reverting a completion.
8. **Title deletion:** `cinema_outings.title_id` cascades; `viewings.outing_id` is
   `on delete set null` so deleting an outing never deletes the viewing (the watch
   happened; the stub just loses its back-reference).
   Conversely, deleting the auto-logged **viewing** directly from the timeline leaves
   the outing `completed` (it's history, not a claim about the library) and ends any
   pending follow-up — the post-show card/sheet requires the viewing to exist.
9. **Dropped titles:** buying a ticket for a `dropped` movie is allowed; completion
   flips it to `watched` like any non-watched status (recorded in `previous_status`
   for faithful revert).
10. **Clock skew:** completion authority is the DB's `now()`; the client timer merely
    *asks*. A fast local clock can call the RPC early — it simply completes nothing
    and the timer re-arms from the next `ends_at`.
11. **Multi-device:** another device may have already completed the outing; the RPC
    returning zero rows + a store refresh of that outing's state handles it.
12. **Undated/pre-platform semantics unaffected:** outing-logged viewings always carry
    a date.
13. **Export/import:** outings ride along in the JSON export; viewing `venue` /
    `companions` round-trip. Imports tolerate their absence (older exports). The
    outing ⇄ viewing links (`completed_viewing_id`, `viewings.outing_id`) must
    survive the import's ID regeneration — insert outings first, remap both foreign
    keys alongside the existing title-ID remapping.
14. **Companions ≠ accounts:** free-text companions are just names; deleting a friend
    later leaves past companion names intact (they're copies, not joins).
15. **Shared plans are snapshots:** editing or cancelling an outing after sharing
    (§4.10) neither updates nor retracts the recipients' notifications — the sender
    re-shares if the plan changed. A recipient acting on a stale share just gets a
    prefilled form they can correct before saving. Unfriending after sharing leaves
    the delivered notification intact (it's a copy, consistent with rule 14).
16. **"I've got tickets too" on an untracked title:** the CTA first adds the title to
    the recipient's library (watchlist) from the payload's `tmdb_id`, then opens the
    prefilled schedule form; abandoning the form keeps the title on the watchlist
    (harmless — they clearly intend to see it).

---

## 6. Data model

### 6.1 New table — `cinema_outings`

```sql
create table cinema_outings (
  id                      uuid primary key default gen_random_uuid(),
  user_id                 uuid not null references auth.users(id) on delete cascade,
  title_id                uuid not null references titles(id) on delete cascade,
  showtime                timestamptz not null,
  previews_minutes        integer not null default 20 check (previews_minutes between 0 and 120),
  runtime_minutes         integer not null check (runtime_minutes > 0),
  -- plain column (not generated: timestamptz + interval isn't immutable);
  -- written by client/RPC, guarded by the check below
  ends_at                 timestamptz not null,
  venue                   text,
  companions              jsonb not null default '[]',  -- [{ name, friendUserId? }]
  format                  text,          -- from the fixed UI list; free text at rest
  ticket_price            numeric(6,2) check (ticket_price >= 0),
  seat                    text,
  booking_ref             text,
  notes                   text,
  status                  text not null default 'scheduled'
                            check (status in ('scheduled','completed','missed','cancelled')),
  previous_status         watch_status,  -- title status captured at completion (for revert)
  completed_viewing_id    uuid references viewings(id) on delete set null,
  follow_up_dismissed_at  timestamptz,
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  constraint outing_ends_after_start check (ends_at > showtime)
);

create index cinema_outings_user_idx  on cinema_outings(user_id, status, ends_at);
create index cinema_outings_title_idx on cinema_outings(title_id);

alter table cinema_outings enable row level security;
create policy "cinema_outings: owner full access" on cinema_outings
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
-- deliberately NO shared-token / friend read policies (v1 privacy stance)

create trigger cinema_outings_updated_at
  before update on cinema_outings
  for each row execute function update_updated_at();
```

### 6.2 `viewings` extensions

```sql
alter table viewings
  add column venue      text,
  add column companions jsonb not null default '[]',
  add column outing_id  uuid references cinema_outings(id) on delete set null;
```

### 6.3 Notification type

```sql
alter table notifications drop constraint notifications_type_check;
alter table notifications add constraint notifications_type_check check (type in (
  'friend_request_received','friend_request_accepted','share_link_used',
  'recommendation_received','comment_received','reaction_received',
  'invite_redeemed','outing_completed','outing_plans_shared'
));
```

### 6.4 RPC — the single choke point

```sql
create or replace function complete_due_outings(p_tz text default 'UTC')
returns table (outing_id uuid, title_id uuid, viewing_id uuid,
               new_title_status watch_status, previous_status watch_status)
language plpgsql security definer as $$
-- for each cinema_outings row of auth.uid() with status='scheduled' and ends_at <= now():
--   1. insert viewings (viewed_at = (showtime at time zone p_tz)::date,
--      venue, companions, outing_id)
--   2. capture titles.status into previous_status;
--      update titles set status='watched' where status <> 'watched'
--   3. insert notifications (recipient_id, type 'outing_completed', title_id,
--      payload jsonb: venue, companion names)
--   4. update the outing: status='completed', previous_status,
--      completed_viewing_id
--   return one row per transition
$$;
```

Validation guards: `auth.uid()` non-null; `p_tz` validated against `pg_timezone_names`
(fallback `'UTC'`); due outings selected `for update skip locked` so two devices
reconciling simultaneously can't double-log a viewing. All four writes per outing
happen in the function → atomic per call; no client insert policy on `notifications`
is ever needed.

### 6.5 RPC — `share_outing_plans`

```sql
create or replace function share_outing_plans(p_outing_id uuid, p_recipient_ids uuid[])
returns void
language plpgsql security definer as $$
-- guards: auth.uid() non-null; the outing exists, belongs to auth.uid(), and is
--   status = 'scheduled'; every recipient passes is_friend(auth.uid(), recipient)
--   and isn't the sender (mirrors send_recommendation's checks).
-- for each recipient: insert notifications (recipient_id, type 'outing_plans_shared',
--   actor_id = auth.uid(), payload jsonb snapshot: tmdb_id, type, title, year,
--   poster_url, showtime, ends_at, venue, format, seat, companion names).
--   booking_ref is intentionally excluded from the payload.
$$;
```

### 6.6 Migration & sync

- One migration file `supabase/migrations/20260711000000_cinema_outings.sql`
  containing §6.1–6.5; mirror everything into `schema.sql`.
- Deploys via the existing `db-migrate.yml` on push to `main`. No Edge Function
  changes (v1 uses PostgREST RPC, not `media-proxy`).

---

## 7. Client architecture

### 7.1 Types (`src/store/mockData.ts`)

```ts
export type OutingStatus = 'scheduled' | 'completed' | 'missed' | 'cancelled'
export const CINEMA_FORMATS = ['Standard','IMAX','3D','Dolby','70mm','Drive-in','Other'] as const
export type CinemaFormat = (typeof CINEMA_FORMATS)[number]

export interface Companion { name: string; friendUserId?: string }

export interface CinemaOuting {
  id: string
  titleId: string
  showtime: string            // ISO datetime (absolute)
  previewsMinutes: number
  runtimeMinutes: number
  endsAt: string              // ISO datetime (denormalized; recomputed on edit)
  venue?: string
  companions: Companion[]
  format?: CinemaFormat
  ticketPrice?: number
  seat?: string
  bookingRef?: string
  notes?: string
  status: OutingStatus
  previousStatus?: WatchStatus
  completedViewingId?: string
  followUpDismissedAt?: string
  createdAt: string
}

// Viewing gains:
//   venue?: string
//   companions?: Companion[]
//   outingId?: string
```

### 7.2 Store (`useAppStore` — library slice additions)

- State: `outings: CinemaOuting[]` (loaded with the library; owner sessions only).
- Actions (optimistic-first, `syncToDb` pattern like titles):
  `addOuting`, `updateOuting` (edit/reschedule; recomputes `endsAt`),
  `cancelOuting`, `dismissOutingFollowUp`, `shareOutingPlans` (RPC call, §6.5),
  `revertOutingCompletion` (didn't-make-it: viewing delete + status revert + `missed`),
  `reconcileOutings` (calls the RPC, applies returned transitions, pushes the toast,
  bumps `unreadNotificationCount`).
- Pure derivations in a new `src/store/outings.ts` (unit-testable, like `upNext.ts`):
  `computeMarqueeEntries(outings, titles, now)` (scheduled + now-showing + pending
  follow-up, sorted soonest-first), `outingPresentation(outing, now)` (countdown
  label buckets), `nextTransitionAt(outings)`, `companionSuggestions(outings,
  viewings, friends)`, `venueSuggestions(...)`.
- Timer wiring in a small `useOutingReconciler()` hook mounted in `App.tsx`
  (load/focus/online/timeout triggers, §4.3).
- `computeUpcomingTitles` excludes titles that have a scheduled outing (they render
  on the marquee instead).

### 7.3 DB layer (`src/lib/db.ts`)

- Row mappers `CinemaOutingRow ⇄ CinemaOuting`; include outings in
  `fetchUserLibrary` (owner only — never in shared/friend fetches).
- `insertOutingToDb / updateOutingInDb / completeDueOutings(tz) /
  shareOutingPlans(outingId, recipientIds) / ...`.
- `updateTitleInDb`'s viewings upsert carries the new columns.

### 7.4 New/changed UI

| File | Change |
|------|--------|
| `src/components/OutingScheduleSheet.tsx` | new — the tickets form (§4.1) |
| `src/components/PostShowSheet.tsx` | new — rate / note / recommend / didn't-make-it (§4.4) |
| `src/components/ShareOutingPanel.tsx` | new — friend picker + copy/system-share snippet (§4.10) |
| `src/lib/ics.ts` | new — hand-rolled VEVENT builder (incl. VALARM) + download helper + share-snippet formatter |
| `src/views/UpNext.tsx` | marquee section: `MarqueeCard`, `FreshFromLobbyCard` |
| `src/components/TitleDetailDrawer.tsx` | tickets CTA, scheduled banner, stub line on timeline, venue/companions in the log/edit-viewing form |
| `src/components/NotificationCenter.tsx` | `outing_completed` + `outing_plans_shared` rendering, click routing, inline CTAs |
| `src/store/commands.ts` | palette commands (§4.9) |
| `src/lib/export-import.ts` | outings + new viewing fields |
| `src/views/ledger/panels/AtTheMovies.tsx` | new panel (Phase F) + registry entries |

Design language: amber ticket-stub chip with a perforated left edge for the marquee
badge and the timeline stub — `DM Mono` for times, existing tokens only, no new
dependencies anywhere in this feature.

---

## 8. Post-show recommendation flow (detail)

The existing friends stack already provides `send_recommendation` +
`SendRecommendationPanel` + the `recommendation_received` inbox type. The post-show
sheet simply deep-links into it with the title preselected. One refinement: friends
whose `friendUserId` appears in the outing's companions are annotated *"was there
with you"* and sorted last. No schema work needed.

---

## 9. Privacy & sharing

- **Outings are owner-private** (RLS: owner-only; fetched only in owner sessions; no
  marquee in shared/friend views). Nobody can see where you *will* be — deliberate.
  The one exception is **explicit, per-outing sharing** (§4.10), which pushes a
  snapshot into a chosen friend's inbox via SECURITY DEFINER RPC — a copy the sender
  authored, never a read grant, and never the booking ref.
- **Past venue/companions** ride on `viewings`, which friends/share-token viewers can
  already read via existing policies; the timeline stub therefore shows to them. This
  matches current behavior for viewing notes (equally personal) — acceptable for v1,
  and a "redact stubs from shared views" share-scope toggle is noted as future work.
- `user_prefs` is friend-readable by design — **do not** put outing data there.

---

## 10. What was deliberately left out (non-goals)

- **Showtime lookup / ticket import** (Fandango, email parsing, wallet passes): no
  free reliable API; manual entry is one short form.
- **Social outings** (shared outing objects, RSVP, invite acceptance): companions are
  labels, not invitations. One-way **plan sharing** (§4.10) *is* in scope — the line
  is drawn at anything requiring two accounts to agree on shared state.
- **Seat maps, concessions tracking, loyalty programs.**
- **TV episodes in cinemas**, festival multi-film passes (a pass = several outings).
- **Server-side scheduling in v1** — see Phase G for the push-notification stretch.

## 11. Future options (explicitly deferred, with hooks in place)

- **`confirm` completion mode** — a `user_prefs` toggle turning auto-complete into a
  "Did you make it?" prompt; the RPC would gain a per-user guard.
- **Web Push** (Phase G sketch): `push_subscriptions` table + VAPID keys, `pg_cron` →
  Edge Function sweeping due outings server-side (calling the same completion
  routine), pushing "How was it?" to the PWA. iOS requires A2HS + 16.4+.
- **Share-scope redaction** of stubs; **theater favorites** pinning; **TMDB
  release-date nudge** ("The Long Reel opens Friday — got tickets?" from the
  existing Coming Soon data).

---

## 12. Implementation phases

Each phase is independently shippable and ends at the verification gate
(`rtk tsc` · `rtk npm run lint` · `rtk vitest` · `rtk npm run build`), an atomic
conventional commit, and — where user-facing — a `CHANGELOG.md` `[Unreleased]` entry.

- [ ] **Phase A — Schema & data layer.** Migration §6 (+`schema.sql` sync), types
      §7.1, `db.ts` mappers/CRUD/RPC wrapper, export/import round-trip. Unit tests
      for mappers and export/import.
- [ ] **Phase B — Store & reconciliation.** `outings.ts` pure module + store actions +
      `useOutingReconciler` hook. Unit tests: `computeMarqueeEntries`, countdown
      buckets, `nextTransitionAt`, exclusion from `computeUpcomingTitles`, revert
      rules (§5.6).
- [ ] **Phase C — Scheduling UX.** `OutingScheduleSheet` + drawer CTA/banner + Up Next
      entry point + palette command + AddTitle success-step link + the **out-of-app
      share** (snippet formatter, `navigator.share`/clipboard) and `.ics` with VALARM.
      Ship gate: schedule, edit, cancel all round-trip to the DB.
- [ ] **Phase D — Completion & follow-up.** Marquee section cards (countdown / NOW
      SHOWING / Fresh-from-the-lobby), toast, `outing_completed` inbox rendering +
      routing, `PostShowSheet` with recommendation deep-link, didn't-make-it +
      reschedule. This phase delivers the headline requirement end-to-end.
- [ ] **Phase D2 — In-app plan sharing.** `ShareOutingPanel`, `share_outing_plans`
      wiring (RPC ships with the Phase A migration), `outing_plans_shared` inbox
      rendering with the **I've got tickets too** prefill CTA (incl. add-title
      resolution, rule §5.16). Depends on C (the form to prefill) and D's inbox work.
- [ ] **Phase E — Timeline & viewing editor.** Ticket-stub line on the drawer
      timeline; venue/companions editable on any viewing (incl. manual logs).
- [ ] **Phase F — Ledger "At the Movies" panel** + ICS export button (if not already
      landed in D) + polish sweep (§13) + docs (README feature list; CLAUDE.md data
      model gains `cinema_outings`).
- [ ] **Phase G *(stretch, separate decision)* — Web Push** per §11.

Rough sizing: A+B one sitting; C and D the bulk; D2/E/F small. No new npm
dependencies at any phase.

---

## 13. Polish checklist (the "stand out" details)

- [ ] Countdown chip language is human: `TONIGHT · 7:30 PM`, not `2026-07-11T19:30`.
- [ ] `NOW SHOWING` pulse uses the projector-beam amber, not a generic spinner.
- [ ] "Lets out ≈ 9:52 PM" live-updates while editing the form's time/runtime fields.
- [ ] `.ics` includes venue as `LOCATION` so phone calendars offer directions, and a
      `VALARM` so the phone reminds you to leave.
- [ ] Share snippet reads like a text you'd actually send ("I'm in H12 — grab a seat
      nearby!"), not a data dump; fields the user left blank are simply omitted.
- [ ] "I've got tickets too" lands the recipient in a form where the only thing left
      to type is their seat.
- [ ] Companion chips render friend avatars when linked; plain initials otherwise.
- [ ] Venue autocomplete is most-recent-first (your usual theater is one tap).
- [ ] Post-show sheet is one screen — rate, note, recommend, all without navigation.
- [ ] Recommend list annotates companions ("was there with you") instead of hiding them.
- [ ] Ticket stub on the timeline uses the perforated-edge motif; degrades gracefully
      when only venue *or* companions is present.
- [ ] Empty marquee never renders a header with no cards.
- [ ] Poster-wall 🎟 badge appears the moment tickets are scheduled and clears on
      completion/cancel.
- [ ] Undo affordances everywhere a state flips: cancel-outing confirm, didn't-make-it
      within 14 days, viewing editable forever.
- [ ] Reduced-motion users get a static NOW SHOWING chip (respect
      `prefers-reduced-motion`, as existing atmospherics do).
- [ ] Screen-reader labels on all new icon buttons (`aria-label`, matching existing
      patterns); the countdown chip carries a full-text `aria-label`.
