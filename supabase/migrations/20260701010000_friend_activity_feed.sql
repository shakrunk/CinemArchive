-- ============================================================
-- FRIEND ACTIVITY FEED
-- ============================================================

-- Merges two activity kinds across the caller's accepted friends: titles
-- they've added to their library, and viewings they've logged. Runs as
-- SECURITY DEFINER (bypassing RLS on titles/viewings/profiles) and instead
-- filters explicitly via is_friend(auth.uid(), <owner>) per branch — the same
-- predicate as the friend-read RLS policies, just aggregated across every
-- friend at once instead of scoped to a single one. Capped to the 100 most
-- recent events across all friends combined.
create or replace function friend_activity_feed()
returns table (
  event_type text,
  event_at timestamptz,
  friend_user_id uuid,
  friend_display_name text,
  friend_username text,
  title_id uuid,
  tmdb_id integer,
  type media_type,
  title text,
  year integer,
  poster_url text,
  rating numeric(3,1)
)
language sql security definer stable as $$
  select * from (
    select
      'title_added' as event_type,
      t.added_at as event_at,
      t.user_id as friend_user_id,
      p.display_name as friend_display_name,
      p.username as friend_username,
      t.id as title_id,
      t.tmdb_id,
      t.type,
      t.title,
      t.year,
      t.poster_url,
      null::numeric(3,1) as rating
    from titles t
    join profiles p on p.user_id = t.user_id
    where is_friend(auth.uid(), t.user_id)

    union all

    select
      'viewing_logged' as event_type,
      v.created_at as event_at,
      t.user_id as friend_user_id,
      p.display_name as friend_display_name,
      p.username as friend_username,
      t.id as title_id,
      t.tmdb_id,
      t.type,
      t.title,
      t.year,
      t.poster_url,
      v.rating
    from viewings v
    join titles t on t.id = v.title_id
    join profiles p on p.user_id = t.user_id
    where is_friend(auth.uid(), t.user_id)
  ) feed
  order by event_at desc
  limit 100;
$$;
