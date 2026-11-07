-- Shared outings (#206): invite linked friends to a cinema outing so it shows on their Up Next.
--
-- cinema_outings stays owner-only (booking ref, price, notes, seats and the ticket reference are
-- never shared). Inviting friends creates a separate shared_outings record holding only the
-- minimal snapshot the in-app plan sharing already exposes (title, time, venue, format and
-- companion display names), plus one shared_outing_members row per invited friend. Polls, group
-- reactions and ticket sending (#203, #207, #220) attach to this record.
--
-- Consent is invite then accept: a friend is shown an invite and accepts or declines. Clients
-- have read-only table access; every change goes through the RPCs below, which require an
-- accepted friendship. The owner's later edits to time, venue, format, companions and status
-- flow into the snapshot, and unfriending or blocking removes the member.
create table public.shared_outings (
  id         uuid primary key default gen_random_uuid(),
  owner_id   uuid not null references auth.users(id) on delete cascade,
  -- Set null (not cascade) when the owner deletes the outing, so members see it as cancelled.
  outing_id  uuid references public.cinema_outings(id) on delete set null,
  status     text not null default 'scheduled' check (status in ('scheduled','completed','cancelled')),
  showtime   timestamptz not null,
  ends_at    timestamptz not null,
  snapshot   jsonb not null check (jsonb_typeof(snapshot) = 'object' and pg_column_size(snapshot) <= 4096),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create unique index shared_outings_outing_idx on public.shared_outings(outing_id) where outing_id is not null;
create index shared_outings_owner_idx on public.shared_outings(owner_id, showtime);

create table public.shared_outing_members (
  shared_outing_id uuid not null references public.shared_outings(id) on delete cascade,
  user_id          uuid not null references auth.users(id) on delete cascade,
  status           text not null default 'invited' check (status in ('invited','accepted','declined')),
  invited_at       timestamptz not null default now(),
  responded_at     timestamptz,
  primary key (shared_outing_id, user_id)
);
create index shared_outing_members_user_idx on public.shared_outing_members(user_id, status);

alter table public.shared_outings enable row level security;
alter table public.shared_outing_members enable row level security;
revoke all on public.shared_outings, public.shared_outing_members from public, anon, authenticated;
grant select on public.shared_outings, public.shared_outing_members to authenticated;
grant all on public.shared_outings, public.shared_outing_members to service_role;

create schema if not exists cinemarchive_private;

-- The two policies reference each other's table, so each check lives in a definer function to
-- avoid recursive row-level security.
create function cinemarchive_private.is_shared_outing_member(p_shared_outing_id uuid, p_user_id uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.shared_outing_members
    where shared_outing_id = p_shared_outing_id and user_id = p_user_id and status in ('invited','accepted'));
$$;
create function cinemarchive_private.owns_shared_outing(p_shared_outing_id uuid, p_user_id uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.shared_outings where id = p_shared_outing_id and owner_id = p_user_id);
$$;
revoke all on function cinemarchive_private.is_shared_outing_member(uuid, uuid) from public, anon;
revoke all on function cinemarchive_private.owns_shared_outing(uuid, uuid) from public, anon;
grant execute on function cinemarchive_private.is_shared_outing_member(uuid, uuid) to authenticated;
grant execute on function cinemarchive_private.owns_shared_outing(uuid, uuid) to authenticated;

-- A member sees a shared outing only while invited or accepted; the owner always sees theirs.
-- A member sees only their own membership row; the owner sees every member of their outing.
create policy "shared outings: owner or member read" on public.shared_outings
  for select to authenticated using (
    owner_id = (select auth.uid())
    or cinemarchive_private.is_shared_outing_member(id, (select auth.uid())));
create policy "shared outing members: self or owner read" on public.shared_outing_members
  for select to authenticated using (
    user_id = (select auth.uid())
    or cinemarchive_private.owns_shared_outing(shared_outing_id, (select auth.uid())));

-- Notification types for an invitation and the invitee's answer.
alter table public.notifications drop constraint notifications_type_check;
alter table public.notifications add constraint notifications_type_check check (type in (
  'friend_request_received', 'friend_request_accepted',
  'share_link_used', 'recommendation_received',
  'comment_received', 'reaction_received', 'invite_redeemed',
  'outing_completed', 'outing_plans_shared',
  'outing_invite', 'outing_invite_response'
));

-- Display names from either legacy string companions or {name, friendUserId?} objects, as the
-- plan-share snapshot builds them. Account identifiers are never included.
create function cinemarchive_private.companion_names(p_companions jsonb)
returns jsonb language sql stable set search_path = '' as $$
  select coalesce(jsonb_agg(v.name order by v.ordinality) filter (where v.name is not null and btrim(v.name) <> ''), '[]'::jsonb)
  from (
    select ordinality, case
      when jsonb_typeof(value) = 'string' then value #>> '{}'
      when jsonb_typeof(value) = 'object' and jsonb_typeof(value -> 'name') = 'string' then value ->> 'name'
      else null end as name
    from jsonb_array_elements(case when jsonb_typeof(p_companions) = 'array' then p_companions else '[]'::jsonb end) with ordinality
  ) v;
$$;

create function cinemarchive_private.shared_outing_snapshot(o public.cinema_outings, t public.titles)
returns jsonb language sql stable set search_path = '' as $$
  select jsonb_build_object(
    'tmdb_id', t.tmdb_id, 'type', t.type, 'title', t.title, 'year', t.year, 'poster_url', t.poster_url,
    'venue', o.venue, 'format', o.format, 'companions', cinemarchive_private.companion_names(o.companions));
$$;

create function cinemarchive_private.shared_outing_status(p_outing_status text)
returns text language sql immutable set search_path = '' as $$
  select case p_outing_status when 'scheduled' then 'scheduled' when 'completed' then 'completed' else 'cancelled' end;
$$;

-- Keep the shared record in step with the owner's outing.
create function cinemarchive_private.sync_shared_outing()
returns trigger language plpgsql security definer set search_path = '' as $$
declare t public.titles;
begin
  if not exists (select 1 from public.shared_outings where outing_id = new.id) then return new; end if;
  select * into t from public.titles where id = new.title_id;
  update public.shared_outings set
      status = cinemarchive_private.shared_outing_status(new.status),
      showtime = new.showtime, ends_at = new.ends_at,
      snapshot = cinemarchive_private.shared_outing_snapshot(new, t), updated_at = now()
    where outing_id = new.id;
  return new;
end;
$$;
revoke all on function cinemarchive_private.sync_shared_outing() from public, anon, authenticated;
create trigger cinema_outings_sync_shared
  after update of showtime, ends_at, venue, format, companions, status, title_id on public.cinema_outings
  for each row execute function cinemarchive_private.sync_shared_outing();

create function cinemarchive_private.cancel_shared_outing_on_delete()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  update public.shared_outings set status = 'cancelled', updated_at = now() where outing_id = old.id;
  return old;
end;
$$;
revoke all on function cinemarchive_private.cancel_shared_outing_on_delete() from public, anon, authenticated;
create trigger cinema_outings_cancel_shared
  before delete on public.cinema_outings
  for each row execute function cinemarchive_private.cancel_shared_outing_on_delete();

-- Unfriending or blocking ends any shared outing between the two people, in both directions.
create function cinemarchive_private.drop_members_when_unfriended()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'UPDATE' and not (old.status = 'accepted' and new.status <> 'accepted') then return new; end if;
  if tg_op = 'DELETE' and old.status <> 'accepted' then return old; end if;
  delete from public.shared_outing_members m using public.shared_outings s
    where m.shared_outing_id = s.id
      and ((s.owner_id = old.user_id_a and m.user_id = old.user_id_b)
        or (s.owner_id = old.user_id_b and m.user_id = old.user_id_a));
  return coalesce(new, old);
end;
$$;
revoke all on function cinemarchive_private.drop_members_when_unfriended() from public, anon, authenticated;
create trigger friendships_drop_shared_members
  after update of status or delete on public.friendships
  for each row execute function cinemarchive_private.drop_members_when_unfriended();

-- Invite accepted friends to an upcoming scheduled outing of the caller's. Idempotent: friends
-- already invited or accepted are left alone and not notified again; a friend who declined is
-- invited again. At most eight people may be invited or accepted at once.
create function cinemarchive_private.invite_friends_to_outing(p_outing_id uuid, p_friend_ids uuid[])
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  ids uuid[];
  outing public.cinema_outings;
  t public.titles;
  shared public.shared_outings;
  member public.shared_outing_members;
  friend uuid;
  newly uuid[] := '{}';
  payload jsonb;
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if p_outing_id is null or p_friend_ids is null or cardinality(p_friend_ids) = 0
     or array_position(p_friend_ids, null) is not null then
    raise exception 'At least one valid friend is required' using errcode = '22023';
  end if;
  select array_agg(distinct id order by id) into ids from unnest(p_friend_ids) id;
  if cardinality(ids) > 8 then raise exception 'An outing can invite at most 8 friends' using errcode = '22023'; end if;
  select * into outing from public.cinema_outings where id = p_outing_id and user_id = me;
  if outing.id is null then raise exception 'Outing not found' using errcode = '42501'; end if;
  if outing.status <> 'scheduled' or outing.ends_at <= now() then
    raise exception 'Can only invite friends to an upcoming scheduled outing' using errcode = '22023';
  end if;
  select * into t from public.titles where id = outing.title_id and user_id = me;
  if t.id is null then raise exception 'Title not found' using errcode = '42501'; end if;
  foreach friend in array ids loop
    if friend = me or not public.is_friend(me, friend) then
      raise exception 'Can only invite accepted friends' using errcode = '42501';
    end if;
  end loop;

  insert into public.shared_outings(owner_id, outing_id, showtime, ends_at, snapshot)
    values (me, outing.id, outing.showtime, outing.ends_at, cinemarchive_private.shared_outing_snapshot(outing, t))
    on conflict (outing_id) where outing_id is not null do update set
      status = 'scheduled', showtime = excluded.showtime, ends_at = excluded.ends_at,
      snapshot = excluded.snapshot, updated_at = now()
    returning * into shared;

  foreach friend in array ids loop
    select * into member from public.shared_outing_members
      where shared_outing_id = shared.id and user_id = friend for update;
    if member.user_id is null then
      insert into public.shared_outing_members(shared_outing_id, user_id) values (shared.id, friend);
      newly := newly || friend;
    elsif member.status = 'declined' then
      update public.shared_outing_members set status = 'invited', invited_at = now(), responded_at = null
        where shared_outing_id = shared.id and user_id = friend;
      newly := newly || friend;
    end if;
  end loop;

  if (select count(*) from public.shared_outing_members
        where shared_outing_id = shared.id and status in ('invited','accepted')) > 8 then
    raise exception 'An outing can invite at most 8 friends' using errcode = '22023';
  end if;

  payload := shared.snapshot || jsonb_build_object('shared_outing_id', shared.id, 'showtime', shared.showtime, 'ends_at', shared.ends_at);
  foreach friend in array newly loop
    insert into public.notifications(recipient_id, type, actor_id, title_id, payload)
      values (friend, 'outing_invite', me, outing.title_id, payload);
  end loop;
  return jsonb_build_object('shared_outing_id', shared.id, 'members',
    (select coalesce(jsonb_agg(jsonb_build_object('user_id', m.user_id, 'status', m.status) order by m.invited_at), '[]'::jsonb)
       from public.shared_outing_members m where m.shared_outing_id = shared.id));
end;
$$;

-- Accept or decline an invitation, or (accepted then declined) leave. Idempotent for the same
-- answer. A declined member needs a new invitation to rejoin.
create function cinemarchive_private.respond_to_outing_invite(p_shared_outing_id uuid, p_accept boolean)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  shared public.shared_outings;
  member public.shared_outing_members;
  want text := case when p_accept then 'accepted' else 'declined' end;
  title_id uuid;
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if p_shared_outing_id is null or p_accept is null then raise exception 'An outing and an answer are required' using errcode = '22023'; end if;
  select * into member from public.shared_outing_members where shared_outing_id = p_shared_outing_id and user_id = me for update;
  if member.user_id is null then raise exception 'Invitation not found' using errcode = '42501'; end if;
  select * into shared from public.shared_outings where id = p_shared_outing_id;
  if member.status = want then return jsonb_build_object('status', want); end if;
  if member.status = 'declined' then raise exception 'Ask for a new invitation to join' using errcode = '22023'; end if;
  if shared.status <> 'scheduled' or shared.ends_at <= now() then
    raise exception 'This outing is no longer open' using errcode = '22023';
  end if;
  update public.shared_outing_members set status = want, responded_at = now()
    where shared_outing_id = p_shared_outing_id and user_id = me;
  select o.title_id into title_id from public.cinema_outings o where o.id = shared.outing_id;
  insert into public.notifications(recipient_id, type, actor_id, title_id, payload)
    values (shared.owner_id, 'outing_invite_response', me, title_id,
      jsonb_build_object('shared_outing_id', shared.id, 'accepted', p_accept, 'title', shared.snapshot ->> 'title',
        'showtime', shared.showtime));
  return jsonb_build_object('status', want);
end;
$$;

-- The owner removes a member from their outing.
create function cinemarchive_private.remove_outing_member(p_shared_outing_id uuid, p_user_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if not exists (select 1 from public.shared_outings where id = p_shared_outing_id and owner_id = auth.uid()) then
    raise exception 'Outing not found' using errcode = '42501';
  end if;
  delete from public.shared_outing_members where shared_outing_id = p_shared_outing_id and user_id = p_user_id;
end;
$$;

-- Outings the caller is invited to or going to, recent and upcoming, with the owner's name.
create function cinemarchive_private.get_shared_outings()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  return (select coalesce(jsonb_agg(jsonb_build_object(
      'id', s.id, 'status', s.status, 'my_status', m.status, 'owner_id', s.owner_id,
      'owner_name', coalesce(nullif(p.display_name, ''), p.username, 'A friend'),
      'showtime', s.showtime, 'ends_at', s.ends_at, 'snapshot', s.snapshot) order by s.showtime), '[]'::jsonb)
    from public.shared_outing_members m
    join public.shared_outings s on s.id = m.shared_outing_id
    left join public.profiles p on p.user_id = s.owner_id
    where m.user_id = auth.uid() and m.status in ('invited','accepted')
      and s.ends_at > now() - interval '2 days');
end;
$$;

-- The owner's view of who is invited to one of their outings.
create function cinemarchive_private.get_outing_members(p_outing_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  return (select coalesce(jsonb_agg(jsonb_build_object(
      'shared_outing_id', s.id, 'user_id', m.user_id,
      'name', coalesce(nullif(p.display_name, ''), p.username, 'A friend'), 'status', m.status) order by m.invited_at), '[]'::jsonb)
    from public.shared_outings s
    join public.shared_outing_members m on m.shared_outing_id = s.id
    left join public.profiles p on p.user_id = m.user_id
    where s.outing_id = p_outing_id and s.owner_id = auth.uid());
end;
$$;

revoke all on function cinemarchive_private.invite_friends_to_outing(uuid, uuid[]) from public, anon;
revoke all on function cinemarchive_private.respond_to_outing_invite(uuid, boolean) from public, anon;
revoke all on function cinemarchive_private.remove_outing_member(uuid, uuid) from public, anon;
revoke all on function cinemarchive_private.get_shared_outings() from public, anon;
revoke all on function cinemarchive_private.get_outing_members(uuid) from public, anon;
grant execute on function cinemarchive_private.invite_friends_to_outing(uuid, uuid[]) to authenticated;
grant execute on function cinemarchive_private.respond_to_outing_invite(uuid, boolean) to authenticated;
grant execute on function cinemarchive_private.remove_outing_member(uuid, uuid) to authenticated;
grant execute on function cinemarchive_private.get_shared_outings() to authenticated;
grant execute on function cinemarchive_private.get_outing_members(uuid) to authenticated;

-- Public wrappers: PostgREST exposes only the public schema.
create function public.invite_friends_to_outing(p_outing_id uuid, p_friend_ids uuid[])
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.invite_friends_to_outing(p_outing_id, p_friend_ids);
$$;
create function public.respond_to_outing_invite(p_shared_outing_id uuid, p_accept boolean)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.respond_to_outing_invite(p_shared_outing_id, p_accept);
$$;
create function public.remove_outing_member(p_shared_outing_id uuid, p_user_id uuid)
returns void language sql security invoker set search_path = '' as $$
  select cinemarchive_private.remove_outing_member(p_shared_outing_id, p_user_id);
$$;
create function public.get_shared_outings()
returns jsonb language sql stable security invoker set search_path = '' as $$
  select cinemarchive_private.get_shared_outings();
$$;
create function public.get_outing_members(p_outing_id uuid)
returns jsonb language sql stable security invoker set search_path = '' as $$
  select cinemarchive_private.get_outing_members(p_outing_id);
$$;
revoke all on function public.invite_friends_to_outing(uuid, uuid[]) from public, anon;
revoke all on function public.respond_to_outing_invite(uuid, boolean) from public, anon;
revoke all on function public.remove_outing_member(uuid, uuid) from public, anon;
revoke all on function public.get_shared_outings() from public, anon;
revoke all on function public.get_outing_members(uuid) from public, anon;
grant execute on function public.invite_friends_to_outing(uuid, uuid[]) to authenticated;
grant execute on function public.respond_to_outing_invite(uuid, boolean) to authenticated;
grant execute on function public.remove_outing_member(uuid, uuid) to authenticated;
grant execute on function public.get_shared_outings() to authenticated;
grant execute on function public.get_outing_members(uuid) to authenticated;
