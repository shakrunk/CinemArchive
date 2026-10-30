-- Outing polls (#203): propose a few title and time candidates to linked friends, collect their
-- votes, then close the poll on a winner that the owner turns into a real outing.
--
-- A poll is separate from cinema_outings and from shared_outings: it is a question put to a
-- handful of accepted friends. The owner chooses 2 to 4 candidates (a movie from their own library
-- plus a showtime and optional venue) and 1 to 8 friends. Voting is approval style: a participant
-- ticks every candidate that works for them. Closing picks one winning candidate; creating the
-- outing and inviting the voters then reuses the existing schedule and invite flows on the client,
-- so nothing here writes to cinema_outings.
--
-- Clients have no table access at all; every read and write goes through the RPCs below, which
-- require an accepted friendship. Participants see candidates, vote counts and the display names of
-- the people who voted for each, never the owner's library, ratings, tickets or notes.
create table public.outing_polls (
  id               uuid primary key default gen_random_uuid(),
  owner_id         uuid not null references auth.users(id) on delete cascade,
  question         text not null default 'Which one works?' check (char_length(question) between 1 and 100),
  status           text not null default 'open' check (status in ('open','closed','cancelled')),
  winner_option_id uuid,
  created_at       timestamptz not null default now(),
  closed_at        timestamptz
);
create index outing_polls_owner_idx on public.outing_polls(owner_id, created_at desc);

create table public.outing_poll_options (
  id         uuid primary key default gen_random_uuid(),
  poll_id    uuid not null references public.outing_polls(id) on delete cascade,
  position   smallint not null check (position between 1 and 4),
  tmdb_id    integer not null,
  media_type text not null default 'movie' check (media_type = 'movie'),
  title      text not null check (char_length(title) between 1 and 300),
  year       integer,
  poster_url text,
  showtime   timestamptz not null,
  venue      text check (venue is null or char_length(venue) between 1 and 100),
  unique (poll_id, position),
  unique (id, poll_id)
);

alter table public.outing_polls
  add constraint outing_polls_winner_fk foreign key (winner_option_id)
  references public.outing_poll_options(id) on delete set null;

create table public.outing_poll_members (
  poll_id    uuid not null references public.outing_polls(id) on delete cascade,
  user_id    uuid not null references auth.users(id) on delete cascade,
  invited_at timestamptz not null default now(),
  primary key (poll_id, user_id)
);
create index outing_poll_members_user_idx on public.outing_poll_members(user_id);

create table public.outing_poll_votes (
  poll_id  uuid not null,
  option_id uuid not null,
  user_id  uuid not null references auth.users(id) on delete cascade,
  voted_at timestamptz not null default now(),
  primary key (option_id, user_id),
  foreign key (option_id, poll_id) references public.outing_poll_options(id, poll_id) on delete cascade
);
create index outing_poll_votes_poll_idx on public.outing_poll_votes(poll_id, user_id);

-- RLS on, with no policy and no grant: only the service role and the definer RPCs touch these.
alter table public.outing_polls enable row level security;
alter table public.outing_poll_options enable row level security;
alter table public.outing_poll_members enable row level security;
alter table public.outing_poll_votes enable row level security;
revoke all on public.outing_polls, public.outing_poll_options, public.outing_poll_members, public.outing_poll_votes
  from public, anon, authenticated;
grant all on public.outing_polls, public.outing_poll_options, public.outing_poll_members, public.outing_poll_votes
  to service_role;

create schema if not exists cinemarchive_private;

-- Notification types for a new poll and for its result.
alter table public.notifications drop constraint notifications_type_check;
alter table public.notifications add constraint notifications_type_check check (type in (
  'friend_request_received', 'friend_request_accepted',
  'share_link_used', 'recommendation_received',
  'comment_received', 'reaction_received', 'invite_redeemed',
  'outing_completed', 'outing_plans_shared',
  'outing_invite', 'outing_invite_response',
  'outing_poll', 'outing_poll_closed'
));

-- The owner, or a friend the poll was put to.
create function cinemarchive_private.is_poll_participant(p_poll_id uuid, p_user_id uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.outing_polls where id = p_poll_id and owner_id = p_user_id)
      or exists (select 1 from public.outing_poll_members where poll_id = p_poll_id and user_id = p_user_id);
$$;
revoke all on function cinemarchive_private.is_poll_participant(uuid, uuid) from public, anon, authenticated;

-- Unfriending or blocking takes a person out of each other's polls, and their votes with them.
create function cinemarchive_private.drop_poll_members_when_unfriended()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'UPDATE' and not (old.status = 'accepted' and new.status <> 'accepted') then return new; end if;
  if tg_op = 'DELETE' and old.status <> 'accepted' then return old; end if;
  delete from public.outing_poll_votes v using public.outing_polls p
    where v.poll_id = p.id
      and ((p.owner_id = old.user_id_a and v.user_id = old.user_id_b)
        or (p.owner_id = old.user_id_b and v.user_id = old.user_id_a));
  delete from public.outing_poll_members m using public.outing_polls p
    where m.poll_id = p.id
      and ((p.owner_id = old.user_id_a and m.user_id = old.user_id_b)
        or (p.owner_id = old.user_id_b and m.user_id = old.user_id_a));
  return coalesce(new, old);
end;
$$;
revoke all on function cinemarchive_private.drop_poll_members_when_unfriended() from public, anon, authenticated;
create trigger friendships_drop_poll_members
  after update of status or delete on public.friendships
  for each row execute function cinemarchive_private.drop_poll_members_when_unfriended();

-- Put a poll to accepted friends. p_options is a JSON array of 2 to 4 objects
-- { "title_id": <the caller's movie>, "showtime": <future timestamp>, "venue": <optional text> }.
create function cinemarchive_private.create_outing_poll(p_question text, p_options jsonb, p_friend_ids uuid[])
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  question text := coalesce(nullif(btrim(coalesce(p_question, '')), ''), 'Which one works?');
  ids uuid[];
  friend uuid;
  poll public.outing_polls;
  opt jsonb;
  idx integer := 0;
  t public.titles;
  at timestamptz;
  venue text;
  seen text[] := '{}';
  key text;
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if char_length(question) > 100 then raise exception 'Keep the question to 100 characters' using errcode = '22023'; end if;
  if p_options is null or jsonb_typeof(p_options) <> 'array' or jsonb_array_length(p_options) not between 2 and 4 then
    raise exception 'Offer 2 to 4 options' using errcode = '22023';
  end if;
  if p_friend_ids is null or cardinality(p_friend_ids) = 0 or array_position(p_friend_ids, null) is not null then
    raise exception 'Choose at least one friend' using errcode = '22023';
  end if;
  select array_agg(distinct id order by id) into ids from unnest(p_friend_ids) id;
  if cardinality(ids) > 8 then raise exception 'A poll can include at most 8 friends' using errcode = '22023'; end if;
  foreach friend in array ids loop
    if friend = me or not public.is_friend(me, friend) then
      raise exception 'Can only poll accepted friends' using errcode = '42501';
    end if;
  end loop;
  if (select count(*) from public.outing_polls where owner_id = me and status = 'open') >= 5 then
    raise exception 'Close a poll before starting another' using errcode = '22023';
  end if;

  insert into public.outing_polls(owner_id, question) values (me, question) returning * into poll;

  for opt in select value from jsonb_array_elements(p_options) loop
    idx := idx + 1;
    if jsonb_typeof(opt) <> 'object' then raise exception 'Invalid option' using errcode = '22023'; end if;
    begin
      select * into t from public.titles where id = (opt ->> 'title_id')::uuid and user_id = me;
      at := (opt ->> 'showtime')::timestamptz;
    exception when invalid_text_representation or datetime_field_overflow or invalid_datetime_format then
      raise exception 'Invalid option' using errcode = '22023';
    end;
    if t.id is null then raise exception 'Title not found' using errcode = '42501'; end if;
    if t.type <> 'movie' then raise exception 'Polls are for movies' using errcode = '22023'; end if;
    if at is null or at <= now() then raise exception 'Showtimes must be in the future' using errcode = '22023'; end if;
    venue := nullif(btrim(coalesce(opt ->> 'venue', '')), '');
    if venue is not null and char_length(venue) > 100 then raise exception 'Keep the venue to 100 characters' using errcode = '22023'; end if;
    key := t.id::text || '@' || at::text;
    if key = any (seen) then raise exception 'Two options are the same' using errcode = '22023'; end if;
    seen := seen || key;
    insert into public.outing_poll_options(poll_id, position, tmdb_id, title, year, poster_url, showtime, venue)
      values (poll.id, idx, t.tmdb_id, t.title, t.year, t.poster_url, at, venue);
  end loop;

  foreach friend in array ids loop
    insert into public.outing_poll_members(poll_id, user_id) values (poll.id, friend);
    insert into public.notifications(recipient_id, type, actor_id, payload)
      values (friend, 'outing_poll', me, jsonb_build_object('poll_id', poll.id, 'question', poll.question, 'options', idx));
  end loop;
  return jsonb_build_object('poll_id', poll.id);
end;
$$;

-- Replace the caller's votes: every option they tick, none for an empty list. Only while the
-- poll is open, and only for showtimes still ahead.
create function cinemarchive_private.cast_poll_votes(p_poll_id uuid, p_option_ids uuid[])
returns void language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  poll public.outing_polls;
  ids uuid[];
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  select * into poll from public.outing_polls where id = p_poll_id for update;
  if poll.id is null or not cinemarchive_private.is_poll_participant(poll.id, me) then
    raise exception 'Poll not found' using errcode = '42501';
  end if;
  if poll.status <> 'open' then raise exception 'This poll is closed' using errcode = '22023'; end if;
  if p_option_ids is null or array_position(p_option_ids, null) is not null then
    raise exception 'Invalid options' using errcode = '22023';
  end if;
  select coalesce(array_agg(distinct id), '{}') into ids from unnest(p_option_ids) id;
  if (select count(*) from public.outing_poll_options where poll_id = poll.id and id = any (ids)) <> cardinality(ids) then
    raise exception 'Invalid options' using errcode = '22023';
  end if;
  if exists (select 1 from public.outing_poll_options where poll_id = poll.id and id = any (ids) and showtime <= now()) then
    raise exception 'That showtime has passed' using errcode = '22023';
  end if;
  delete from public.outing_poll_votes where poll_id = poll.id and user_id = me;
  insert into public.outing_poll_votes(poll_id, option_id, user_id) select poll.id, id, me from unnest(ids) id;
end;
$$;

-- The owner closes the poll on a winning option, or cancels it by passing no option. Members are told.
create function cinemarchive_private.close_outing_poll(p_poll_id uuid, p_option_id uuid default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  poll public.outing_polls;
  won public.outing_poll_options;
  member uuid;
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  select * into poll from public.outing_polls where id = p_poll_id and owner_id = me for update;
  if poll.id is null then raise exception 'Poll not found' using errcode = '42501'; end if;
  if poll.status <> 'open' then raise exception 'This poll is already closed' using errcode = '22023'; end if;
  if p_option_id is not null then
    select * into won from public.outing_poll_options where id = p_option_id and poll_id = poll.id;
    if won.id is null then raise exception 'Invalid option' using errcode = '22023'; end if;
    if won.showtime <= now() then raise exception 'That showtime has passed' using errcode = '22023'; end if;
  end if;
  update public.outing_polls
    set status = case when won.id is null then 'cancelled' else 'closed' end,
        winner_option_id = won.id, closed_at = now()
    where id = poll.id;
  for member in select user_id from public.outing_poll_members where poll_id = poll.id loop
    insert into public.notifications(recipient_id, type, actor_id, payload)
      values (member, 'outing_poll_closed', me, jsonb_build_object(
        'poll_id', poll.id, 'question', poll.question, 'cancelled', won.id is null,
        'title', won.title, 'showtime', won.showtime, 'venue', won.venue));
  end loop;
  return jsonb_build_object('status', case when won.id is null then 'cancelled' else 'closed' end, 'winner_option_id', won.id);
end;
$$;

-- Polls the caller owns or was asked into, from the last 30 days, with each option's tally.
create function cinemarchive_private.get_outing_polls()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  return (select coalesce(jsonb_agg(jsonb_build_object(
      'id', p.id, 'question', p.question, 'status', p.status, 'is_owner', p.owner_id = me,
      'owner_name', coalesce(nullif(op.display_name, ''), op.username, 'A friend'),
      'created_at', p.created_at, 'closed_at', p.closed_at, 'winner_option_id', p.winner_option_id,
      'members', (select coalesce(jsonb_agg(jsonb_build_object('user_id', m.user_id,
          'name', coalesce(nullif(mp.display_name, ''), mp.username, 'A friend')) order by m.invited_at), '[]'::jsonb)
        from public.outing_poll_members m left join public.profiles mp on mp.user_id = m.user_id where m.poll_id = p.id),
      'options', (select coalesce(jsonb_agg(jsonb_build_object(
          'id', o.id, 'position', o.position, 'tmdb_id', o.tmdb_id, 'title', o.title, 'year', o.year,
          'poster_url', o.poster_url, 'showtime', o.showtime, 'venue', o.venue,
          'votes', (select count(*) from public.outing_poll_votes v where v.option_id = o.id),
          'mine', exists (select 1 from public.outing_poll_votes v where v.option_id = o.id and v.user_id = me),
          'voters', (select coalesce(jsonb_agg(coalesce(nullif(vp.display_name, ''), vp.username, 'A friend') order by v.voted_at), '[]'::jsonb)
            from public.outing_poll_votes v left join public.profiles vp on vp.user_id = v.user_id where v.option_id = o.id)
        ) order by o.position), '[]'::jsonb) from public.outing_poll_options o where o.poll_id = p.id)
    ) order by p.created_at desc), '[]'::jsonb)
    from public.outing_polls p
    left join public.profiles op on op.user_id = p.owner_id
    where cinemarchive_private.is_poll_participant(p.id, me) and p.created_at > now() - interval '30 days');
end;
$$;

revoke all on function cinemarchive_private.create_outing_poll(text, jsonb, uuid[]) from public, anon;
revoke all on function cinemarchive_private.cast_poll_votes(uuid, uuid[]) from public, anon;
revoke all on function cinemarchive_private.close_outing_poll(uuid, uuid) from public, anon;
revoke all on function cinemarchive_private.get_outing_polls() from public, anon;
grant execute on function cinemarchive_private.create_outing_poll(text, jsonb, uuid[]) to authenticated;
grant execute on function cinemarchive_private.cast_poll_votes(uuid, uuid[]) to authenticated;
grant execute on function cinemarchive_private.close_outing_poll(uuid, uuid) to authenticated;
grant execute on function cinemarchive_private.get_outing_polls() to authenticated;

-- Public wrappers: PostgREST exposes only the public schema.
create function public.create_outing_poll(p_question text, p_options jsonb, p_friend_ids uuid[])
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.create_outing_poll(p_question, p_options, p_friend_ids);
$$;
create function public.cast_poll_votes(p_poll_id uuid, p_option_ids uuid[])
returns void language sql security invoker set search_path = '' as $$
  select cinemarchive_private.cast_poll_votes(p_poll_id, p_option_ids);
$$;
create function public.close_outing_poll(p_poll_id uuid, p_option_id uuid default null)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.close_outing_poll(p_poll_id, p_option_id);
$$;
create function public.get_outing_polls()
returns jsonb language sql stable security invoker set search_path = '' as $$
  select cinemarchive_private.get_outing_polls();
$$;
revoke all on function public.create_outing_poll(text, jsonb, uuid[]) from public, anon;
revoke all on function public.cast_poll_votes(uuid, uuid[]) from public, anon;
revoke all on function public.close_outing_poll(uuid, uuid) from public, anon;
revoke all on function public.get_outing_polls() from public, anon;
grant execute on function public.create_outing_poll(text, jsonb, uuid[]) to authenticated;
grant execute on function public.cast_poll_votes(uuid, uuid[]) to authenticated;
grant execute on function public.close_outing_poll(uuid, uuid) to authenticated;
grant execute on function public.get_outing_polls() to authenticated;
