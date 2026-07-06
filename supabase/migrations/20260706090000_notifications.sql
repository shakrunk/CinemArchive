-- ============================================================
-- NOTIFICATIONS — persistent, per-recipient inbox
-- ============================================================
--
-- Distinct from the client's ephemeral pushNotification()/<NotificationStack/>
-- toast system, which is untouched and keeps handling transient success/error
-- feedback. This is a durable, dismissable, unread-counted inbox replacing
-- the activityFeedLastSeenAt/activityUnseenCount client-side watermark.
--
-- No client insert policy at all (same "single choke-point" philosophy as
-- friendships/recommendations/title_comments) — every row is inserted from
-- inside the existing SECURITY DEFINER action function that causes it, so
-- there's exactly one place per notification type that can create one.
create table notifications (
  id           uuid primary key default gen_random_uuid(),
  recipient_id uuid not null references auth.users(id) on delete cascade,
  type         text not null check (type in (
                 'friend_request_received', 'friend_request_accepted',
                 'share_link_used', 'recommendation_received',
                 'comment_received', 'reaction_received'
               )),
  actor_id     uuid references auth.users(id) on delete set null,
  title_id     uuid references titles(id) on delete set null,
  payload      jsonb not null default '{}',
  created_at   timestamptz not null default now(),
  read_at      timestamptz
);

create index notifications_recipient_idx on notifications(recipient_id, created_at desc);

alter table notifications enable row level security;

create policy "notifications: recipient can read"
  on notifications for select
  using (auth.uid() = recipient_id);

create policy "notifications: recipient can mark read/delete"
  on notifications for update
  using (auth.uid() = recipient_id)
  with check (auth.uid() = recipient_id);

create policy "notifications: recipient can delete"
  on notifications for delete
  using (auth.uid() = recipient_id);

-- ─── RPCs ───────────────────────────────────────────────────────────────────

create or replace function list_notifications(p_before timestamptz default null, p_limit integer default 30)
returns table (
  id uuid,
  type text,
  actor_id uuid,
  actor_display_name text,
  actor_username text,
  title_id uuid,
  tmdb_id integer,
  media_type media_type,
  title text,
  poster_url text,
  payload jsonb,
  created_at timestamptz,
  read_at timestamptz
)
language sql security definer stable as $$
  select
    n.id, n.type, n.actor_id, p.display_name, p.username,
    n.title_id, t.tmdb_id, t.type, t.title, t.poster_url,
    n.payload, n.created_at, n.read_at
  from notifications n
  left join profiles p on p.user_id = n.actor_id
  left join titles t on t.id = n.title_id
  where n.recipient_id = auth.uid()
    and (p_before is null or n.created_at < p_before)
  order by n.created_at desc
  limit least(coalesce(p_limit, 30), 50);
$$;

create or replace function mark_notification_read(p_id uuid)
returns void
language sql security definer as $$
  update notifications set read_at = now()
  where id = p_id and recipient_id = auth.uid() and read_at is null;
$$;

create or replace function mark_all_notifications_read()
returns void
language sql security definer as $$
  update notifications set read_at = now()
  where recipient_id = auth.uid() and read_at is null;
$$;

create or replace function unread_notification_count()
returns integer
language sql security definer stable as $$
  select count(*)::integer from notifications
  where recipient_id = auth.uid() and read_at is null;
$$;

-- ─── Extend existing action functions to populate notifications ───────────
-- Each insert happens inside the function that already validates and
-- performs the underlying action — a single choke point per trigger, not a
-- separate trigger bolted on afterward.

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
    insert into notifications (recipient_id, type, actor_id)
    values (target_user_id, 'friend_request_received', me);
    return;
  end if;

  if existing.status = 'blocked' then
    raise exception 'Cannot send a friend request to this user';
  end if;

  if existing.status = 'accepted' or existing.requested_by = me then
    return; -- already friends, or already requested — no-op
  end if;

  -- The other party already requested us — mutual request accepts it. From
  -- their perspective this IS an acceptance, so notify them as such.
  update friendships
  set status = 'accepted', updated_at = now()
  where user_id_a = a and user_id_b = b;

  insert into notifications (recipient_id, type, actor_id)
  values (target_user_id, 'friend_request_accepted', me);
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

  insert into notifications (recipient_id, type, actor_id)
  values (requester_user_id, 'friend_request_accepted', me);
end;
$$;

-- Notifies the link owner at most once per hour per key (keyed off the same
-- last_used_at column it was already updating), so repeat page loads by the
-- same visitor don't spam the owner's inbox.
create or replace function set_shared_token(token text)
returns void
language plpgsql security definer as $$
declare
  v_token text := token;
  key shared_access_keys;
begin
  perform set_config('app.shared_token', v_token, false);

  select * into key from shared_access_keys k where k.token = v_token and k.is_active = true;
  if key.id is null then
    return;
  end if;

  if key.last_used_at is null or key.last_used_at < now() - interval '1 hour' then
    insert into notifications (recipient_id, type, payload)
    values (key.user_id, 'share_link_used', jsonb_build_object('label', key.label));
  end if;

  update shared_access_keys k set last_used_at = now() where k.id = key.id;
end;
$$;

create or replace function send_recommendation(
  recipient_id uuid,
  p_tmdb_id integer,
  p_type media_type,
  p_title text,
  p_year integer,
  p_poster_url text,
  p_note text default null
)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;
  if me = recipient_id then
    raise exception 'Cannot send a recommendation to yourself';
  end if;
  if not is_friend(me, recipient_id) then
    raise exception 'Can only send recommendations to accepted friends';
  end if;

  insert into recommendations (sender_user_id, recipient_user_id, tmdb_id, type, title, year, poster_url, note)
  values (me, recipient_id, p_tmdb_id, p_type, p_title, p_year, p_poster_url, nullif(trim(p_note), ''))
  on conflict (sender_user_id, recipient_user_id, tmdb_id, type)
  do update set title = excluded.title, year = excluded.year, poster_url = excluded.poster_url,
    note = excluded.note, status = 'unread', updated_at = now();

  insert into notifications (recipient_id, type, actor_id, payload)
  values (recipient_id, 'recommendation_received', me, jsonb_build_object('tmdb_id', p_tmdb_id, 'type', p_type, 'title', p_title));
end;
$$;

create or replace function add_title_comment(p_title_id uuid, p_body text)
returns title_comments
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  owner_id uuid;
  result title_comments;
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  select user_id into owner_id from titles where id = p_title_id;
  if owner_id is null then
    raise exception 'Title not found';
  end if;
  if owner_id <> me and not is_friend(me, owner_id) then
    raise exception 'Not authorized to comment on this title';
  end if;

  insert into title_comments (title_id, author_id, body)
  values (p_title_id, me, p_body)
  returning * into result;

  if owner_id <> me then
    insert into notifications (recipient_id, type, actor_id, title_id)
    values (owner_id, 'comment_received', me, p_title_id);
  end if;

  return result;
end;
$$;

create or replace function set_title_reaction(p_title_id uuid, p_emoji text)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  owner_id uuid;
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  select user_id into owner_id from titles where id = p_title_id;
  if owner_id is null then
    raise exception 'Title not found';
  end if;

  if p_emoji is null then
    delete from title_reactions where title_id = p_title_id and author_id = me;
    return;
  end if;

  if owner_id <> me and not is_friend(me, owner_id) then
    raise exception 'Not authorized to react to this title';
  end if;

  insert into title_reactions (title_id, author_id, emoji)
  values (p_title_id, me, p_emoji)
  on conflict (title_id, author_id) do update set emoji = p_emoji, created_at = now();

  if owner_id <> me then
    insert into notifications (recipient_id, type, actor_id, title_id, payload)
    values (owner_id, 'reaction_received', me, p_title_id, jsonb_build_object('emoji', p_emoji));
  end if;
end;
$$;
