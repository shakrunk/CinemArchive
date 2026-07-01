-- ============================================================
-- RECOMMENDATIONS
-- ============================================================

-- A denormalized snapshot of a title sent from one friend to another. The
-- snapshot (title/year/poster) is captured at send time rather than joined
-- against the sender's `titles` row, so the recommendation stays legible
-- even if the sender later edits or removes it from their own library.
create table recommendations (
  id                uuid primary key default gen_random_uuid(),
  sender_user_id    uuid not null references auth.users(id) on delete cascade,
  recipient_user_id uuid not null references auth.users(id) on delete cascade,
  tmdb_id           integer not null,
  type              media_type not null,
  title             text not null,
  year              integer,
  poster_url        text,
  status            text not null default 'unread' check (status in ('unread', 'read', 'dismissed')),
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),

  constraint recommendations_not_to_self check (sender_user_id <> recipient_user_id)
);

create index recommendations_recipient_idx on recommendations(recipient_user_id);
create index recommendations_sender_idx on recommendations(sender_user_id);

-- Resending the same title to the same friend updates the existing row
-- (bumping it back to unread) instead of piling up duplicate inbox entries.
create unique index recommendations_unique_idx
  on recommendations(sender_user_id, recipient_user_id, tmdb_id, type);

alter table recommendations enable row level security;

-- Mutations only happen through the SECURITY DEFINER functions below (same
-- pattern as friendships) so a client can't forge a recommendation from
-- someone else or edit a snapshot after the fact.
create policy "recommendations: recipient can read"
  on recommendations for select
  using (auth.uid() = recipient_user_id);

create policy "recommendations: sender can read"
  on recommendations for select
  using (auth.uid() = sender_user_id);

create or replace function send_recommendation(
  recipient_id uuid,
  p_tmdb_id integer,
  p_type media_type,
  p_title text,
  p_year integer,
  p_poster_url text
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

  insert into recommendations (sender_user_id, recipient_user_id, tmdb_id, type, title, year, poster_url)
  values (me, recipient_id, p_tmdb_id, p_type, p_title, p_year, p_poster_url)
  on conflict (sender_user_id, recipient_user_id, tmdb_id, type)
  do update set title = excluded.title, year = excluded.year, poster_url = excluded.poster_url,
    status = 'unread', updated_at = now();
end;
$$;

create or replace function mark_recommendation_read(rec_id uuid)
returns void
language plpgsql security definer as $$
begin
  update recommendations
  set status = 'read', updated_at = now()
  where id = rec_id and recipient_user_id = auth.uid() and status = 'unread';
end;
$$;

create or replace function dismiss_recommendation(rec_id uuid)
returns void
language plpgsql security definer as $$
begin
  update recommendations
  set status = 'dismissed', updated_at = now()
  where id = rec_id and recipient_user_id = auth.uid();
end;
$$;

-- Inbox listing for the current user, joined against profiles for the
-- sender's display name — mirrors list_friendships().
create or replace function list_recommendations()
returns table (
  id uuid,
  sender_user_id uuid,
  sender_display_name text,
  sender_username text,
  tmdb_id integer,
  type media_type,
  title text,
  year integer,
  poster_url text,
  status text,
  created_at timestamptz
)
language sql security definer stable as $$
  select r.id, r.sender_user_id, p.display_name, p.username, r.tmdb_id, r.type,
    r.title, r.year, r.poster_url, r.status, r.created_at
  from recommendations r
  join profiles p on p.user_id = r.sender_user_id
  where r.recipient_user_id = auth.uid()
  order by r.created_at desc;
$$;
