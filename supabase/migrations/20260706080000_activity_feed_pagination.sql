-- Extends friend_activity_feed() with:
--   1. Keyset pagination (p_before/p_limit) replacing the hardcoded limit 100
--      — the feed UI can now "Load more" instead of only ever showing the
--      latest 100 events across all friends combined.
--   2. Two new event kinds sourced from Phase 2's title_comments/
--      title_reactions, additionally excluding the caller's own comments/
--      reactions so this stays "what my friends have been up to," never
--      "what I did," matching how title_added/viewing_logged already only
--      ever show friend-authored events (your own titles never match
--      is_friend(auth.uid(), auth.uid()) = false).
--   3. Every branch now filters through can_view_title(t.user_id, t.genres,
--      t.status) instead of the bare is_friend(auth.uid(), t.user_id) check
--      the original version used — can_view_title (added by
--      20260706060000_share_scopes.sql) additionally respects share_scopes,
--      so a friend scoped to e.g. Horror-only no longer sees feed entries
--      about titles outside their granted scope. (The shared-link branch
--      inside can_view_title is a no-op here since this function is only
--      ever called by an authenticated friend browsing their own Friends
--      hub, never via an anonymous app.shared_token session.)
-- Each event row carries title_id/tmdb_id/poster info so the client can
-- click through without a second fetch.
create or replace function friend_activity_feed(p_before timestamptz default null, p_limit integer default 30)
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
    where can_view_title(t.user_id, t.genres, t.status)

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
    where can_view_title(t.user_id, t.genres, t.status)

    union all

    select
      'comment_added' as event_type,
      c.created_at as event_at,
      c.author_id as friend_user_id,
      p.display_name as friend_display_name,
      p.username as friend_username,
      t.id as title_id,
      t.tmdb_id,
      t.type,
      t.title,
      t.year,
      t.poster_url,
      null::numeric(3,1) as rating
    from title_comments c
    join titles t on t.id = c.title_id
    join profiles p on p.user_id = c.author_id
    where can_view_title(t.user_id, t.genres, t.status)
      and c.author_id <> auth.uid()

    union all

    select
      'reaction_added' as event_type,
      r.created_at as event_at,
      r.author_id as friend_user_id,
      p.display_name as friend_display_name,
      p.username as friend_username,
      t.id as title_id,
      t.tmdb_id,
      t.type,
      t.title,
      t.year,
      t.poster_url,
      null::numeric(3,1) as rating
    from title_reactions r
    join titles t on t.id = r.title_id
    join profiles p on p.user_id = r.author_id
    where can_view_title(t.user_id, t.genres, t.status)
      and r.author_id <> auth.uid()
  ) feed
  where p_before is null or event_at < p_before
  order by event_at desc
  limit least(coalesce(p_limit, 30), 50);
$$;
