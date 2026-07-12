-- ============================================================
-- CINEMA OUTINGS — "I've got tickets" (booked cinema trips)
-- ============================================================
--
-- Closes the gap between "I plan to watch this" and "I watched this": a
-- scheduled cinema trip that auto-completes into a watched title + a logged
-- viewing once showtime + previews + runtime has passed. Owner-only by
-- design (no shared-token/friend read) — nobody can see where you *will*
-- be. See docs/superpowers/plans/2026-07-11-cinema-outings.md §6 for the
-- full design rationale.

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

-- ============================================================
-- VIEWINGS extensions — theater/company become part of the permanent
-- viewing timeline for any viewing, not just outing-logged ones.
-- ============================================================

alter table viewings
  add column venue      text,
  add column companions jsonb not null default '[]',
  add column outing_id  uuid references cinema_outings(id) on delete set null;

-- ============================================================
-- NOTIFICATIONS — widen the type constraint for the two new inbox types
-- ============================================================

alter table notifications drop constraint notifications_type_check;
alter table notifications add constraint notifications_type_check check (type in (
  'friend_request_received','friend_request_accepted','share_link_used',
  'recommendation_received','comment_received','reaction_received',
  'invite_redeemed','outing_completed','outing_plans_shared'
));

-- ============================================================
-- RPC — complete_due_outings: the single choke point for auto-completion
-- ============================================================
--
-- Client-triggered, server-executed: called on load/focus/online/timer by
-- the reconciler (never a client-side fake completion). Idempotent — only
-- ever touches the caller's own 'scheduled' rows whose ends_at has passed —
-- and safe under multi-device races via "for update skip locked" (a device
-- that loses the race simply completes nothing for that outing).
create or replace function complete_due_outings(p_tz text default 'UTC')
returns table (
  outing_id uuid,
  title_id uuid,
  viewing_id uuid,
  new_title_status watch_status,
  previous_status watch_status
)
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  v_tz text;
  rec cinema_outings;
  v_viewing_id uuid;
  v_prev_status watch_status;
  v_companion_names jsonb;
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  -- Validate the client-supplied IANA zone against pg_timezone_names; a
  -- bogus/spoofed value silently falls back to UTC rather than erroring the
  -- whole reconciliation pass.
  select name into v_tz from pg_timezone_names where name = p_tz;
  if v_tz is null then
    v_tz := 'UTC';
  end if;

  for rec in
    select * from cinema_outings
    where user_id = me
      and status = 'scheduled'
      and ends_at <= now()
    for update skip locked
  loop
    v_companion_names := (
      select coalesce(jsonb_agg(c ->> 'name'), '[]'::jsonb)
      from jsonb_array_elements(rec.companions) c
    );

    -- 1. Log the viewing — viewed_at is the showtime's calendar date in the
    --    user's timezone (an absolute instant needs a zone to become a date;
    --    see plan §5.1), carrying the outing's theater/company forward.
    insert into viewings (title_id, user_id, viewed_at, venue, companions, outing_id)
    values (rec.title_id, me, (rec.showtime at time zone v_tz)::date, rec.venue, rec.companions, rec.id)
    returning id into v_viewing_id;

    -- 2. Flip the title to watched unless it already is (rule §5.9: a
    --    rewatch of a watched title, or a ticket for a dropped one, both
    --    resolve to 'watched' — previous_status remembers what it was for
    --    a faithful "Didn't make it" revert).
    select status into v_prev_status from titles where id = rec.title_id;
    if v_prev_status <> 'watched' then
      update titles set status = 'watched' where id = rec.title_id;
    end if;

    -- 3. Self-notification (no actor) — the "How was it?" prompt.
    insert into notifications (recipient_id, type, title_id, payload)
    values (me, 'outing_completed', rec.title_id, jsonb_build_object('venue', rec.venue, 'companions', v_companion_names));

    -- 4. Close out the outing.
    update cinema_outings
    set status = 'completed', previous_status = v_prev_status, completed_viewing_id = v_viewing_id
    where id = rec.id;

    outing_id := rec.id;
    title_id := rec.title_id;
    viewing_id := v_viewing_id;
    new_title_status := 'watched';
    previous_status := v_prev_status;
    return next;
  end loop;
end;
$$;

-- ============================================================
-- RPC — share_outing_plans: one-way plan-sharing snapshot (§4.10)
-- ============================================================
--
-- Mirrors send_recommendation's shape: verifies ownership + status, then
-- is_friend() per recipient, and inserts one notification per recipient
-- whose payload is a denormalized snapshot — never a read grant on the
-- outing itself, and never the booking ref (that's effectively the ticket).
-- Later edits/cancellations don't propagate (rule §5.15); re-sharing sends
-- a fresh notification.
create or replace function share_outing_plans(p_outing_id uuid, p_recipient_ids uuid[])
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  outing cinema_outings;
  t titles;
  v_companion_names jsonb;
  recipient uuid;
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  select * into outing from cinema_outings where id = p_outing_id and user_id = me;
  if outing.id is null then
    raise exception 'Outing not found';
  end if;
  if outing.status <> 'scheduled' then
    raise exception 'Can only share plans for a scheduled outing';
  end if;

  select * into t from titles where id = outing.title_id;

  v_companion_names := (
    select coalesce(jsonb_agg(c ->> 'name'), '[]'::jsonb)
    from jsonb_array_elements(outing.companions) c
  );

  foreach recipient in array coalesce(p_recipient_ids, '{}') loop
    if recipient = me then
      raise exception 'Cannot share plans with yourself';
    end if;
    if not is_friend(me, recipient) then
      raise exception 'Can only share plans with accepted friends';
    end if;

    insert into notifications (recipient_id, type, actor_id, title_id, payload)
    values (
      recipient, 'outing_plans_shared', me, outing.title_id,
      jsonb_build_object(
        'tmdb_id', t.tmdb_id,
        'type', t.type,
        'title', t.title,
        'year', t.year,
        'poster_url', t.poster_url,
        'showtime', outing.showtime,
        'ends_at', outing.ends_at,
        'venue', outing.venue,
        'format', outing.format,
        'seat', outing.seat,
        'companions', v_companion_names
      )
    );
  end loop;
end;
$$;
