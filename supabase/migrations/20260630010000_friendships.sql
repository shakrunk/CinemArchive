-- Canonicalized pair (user_id_a < user_id_b) so each relationship has
-- exactly one row regardless of who acts on it. State machine:
--   (none)  -> pending   via send_friend_request
--   pending -> accepted  via accept_friend_request, or automatically if the
--              other party also sends a request (mutual request)
--   pending -> (removed) via decline_friend_request
--   any     -> blocked   via block_user (one-directional exit; only
--              blocked_by can act on it again — no unblock yet)
create table friendships (
  user_id_a    uuid not null references auth.users(id) on delete cascade,
  user_id_b    uuid not null references auth.users(id) on delete cascade,
  requested_by uuid not null references auth.users(id) on delete cascade,
  status       text not null default 'pending' check (status in ('pending', 'accepted', 'blocked')),
  blocked_by   uuid references auth.users(id) on delete cascade,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  primary key (user_id_a, user_id_b),
  constraint friendships_ordered_pair check (user_id_a < user_id_b),
  constraint friendships_requested_by_is_party check (requested_by in (user_id_a, user_id_b)),
  constraint friendships_blocked_by_is_party check (blocked_by is null or blocked_by in (user_id_a, user_id_b))
);

create index friendships_user_id_a_idx on friendships(user_id_a);
create index friendships_user_id_b_idx on friendships(user_id_b);

alter table friendships enable row level security;

-- Either party can read the relationship row; all mutations go through the
-- SECURITY DEFINER functions below (mirrors the is_valid_shared_token /
-- find_user_by_email pattern) so state-machine transitions can't be
-- bypassed by a raw insert/update from the client.
create policy "friendships: parties can read"
  on friendships for select
  using (auth.uid() = user_id_a or auth.uid() = user_id_b);

-- Used by Phase 3 friend-read RLS policies to check accepted friendship.
create or replace function is_friend(user_a uuid, user_b uuid)
returns boolean
language sql security definer stable as $$
  select exists (
    select 1 from friendships
    where user_id_a = least(user_a, user_b)
      and user_id_b = greatest(user_a, user_b)
      and status = 'accepted'
  );
$$;

create or replace function send_friend_request(target_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, target_user_id);
  b uuid := greatest(me, target_user_id);
  existing friendships;
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;
  if me = target_user_id then
    raise exception 'Cannot send a friend request to yourself';
  end if;

  select * into existing from friendships where user_id_a = a and user_id_b = b;

  if existing is null then
    insert into friendships (user_id_a, user_id_b, requested_by, status)
    values (a, b, me, 'pending');
    return;
  end if;

  if existing.status = 'blocked' then
    raise exception 'Cannot send a friend request to this user';
  end if;

  if existing.status = 'accepted' or existing.requested_by = me then
    return; -- already friends, or already requested — no-op
  end if;

  -- The other party already requested us — mutual request accepts it.
  update friendships
  set status = 'accepted', updated_at = now()
  where user_id_a = a and user_id_b = b;
end;
$$;

create or replace function accept_friend_request(requester_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, requester_user_id);
  b uuid := greatest(me, requester_user_id);
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  update friendships
  set status = 'accepted', updated_at = now()
  where user_id_a = a and user_id_b = b
    and status = 'pending'
    and requested_by = requester_user_id;

  if not found then
    raise exception 'No pending friend request from this user';
  end if;
end;
$$;

create or replace function decline_friend_request(requester_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, requester_user_id);
  b uuid := greatest(me, requester_user_id);
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  delete from friendships
  where user_id_a = a and user_id_b = b
    and status = 'pending'
    and requested_by = requester_user_id;
end;
$$;

create or replace function block_user(target_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, target_user_id);
  b uuid := greatest(me, target_user_id);
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;
  if me = target_user_id then
    raise exception 'Cannot block yourself';
  end if;

  insert into friendships (user_id_a, user_id_b, requested_by, status, blocked_by)
  values (a, b, me, 'blocked', me)
  on conflict (user_id_a, user_id_b)
  do update set status = 'blocked', blocked_by = me, updated_at = now();
end;
$$;

-- Friend list for the current user, joined against profiles for display —
-- avoids the client needing separate profile lookups per row (profiles has
-- no broad SELECT policy).
create or replace function list_friendships()
returns table (
  friend_user_id uuid,
  status text,
  requested_by uuid,
  blocked_by uuid,
  created_at timestamptz,
  updated_at timestamptz,
  display_name text,
  username text
)
language sql security definer stable as $$
  select
    case when f.user_id_a = auth.uid() then f.user_id_b else f.user_id_a end,
    f.status,
    f.requested_by,
    f.blocked_by,
    f.created_at,
    f.updated_at,
    p.display_name,
    p.username
  from friendships f
  join profiles p
    on p.user_id = case when f.user_id_a = auth.uid() then f.user_id_b else f.user_id_a end
  where f.user_id_a = auth.uid() or f.user_id_b = auth.uid();
$$;
