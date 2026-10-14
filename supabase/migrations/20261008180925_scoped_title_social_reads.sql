-- Scoped title social reads (20261008180925)
-- NULL auth IDs must never bypass a friends-only predicate. Social reads
-- follow the same title scope as the library, including blocked relationships.
create or replace function cinemarchive_private.list_title_comments(p_title_id uuid)
returns table (id uuid, author_id uuid, body text, created_at timestamptz, display_name text, username text)
language sql security definer stable set search_path = '' as $$
  select c.id, c.author_id, c.body, c.created_at, p.display_name, p.username
  from public.title_comments c
  join public.titles t on t.id = c.title_id
  join public.profiles p on p.user_id = c.author_id
  where t.id = p_title_id and auth.uid() is not null
    and (t.user_id = auth.uid() or public.can_view_title(t.user_id, t.genres, t.status))
  order by c.created_at, c.id;
$$;
revoke all on function cinemarchive_private.list_title_comments(uuid) from public, anon;
grant execute on function cinemarchive_private.list_title_comments(uuid) to authenticated;

create or replace function public.list_title_comments(p_title_id uuid)
returns table (id uuid, author_id uuid, body text, created_at timestamptz, display_name text, username text)
language sql security invoker stable set search_path = '' as $$
  select * from cinemarchive_private.list_title_comments(p_title_id);
$$;
revoke all on function public.list_title_comments(uuid) from public, anon;
grant execute on function public.list_title_comments(uuid) to authenticated;

create or replace function cinemarchive_private.list_title_reactions(p_title_id uuid)
returns table (author_id uuid, emoji text, display_name text, username text)
language sql security definer stable set search_path = '' as $$
  select r.author_id, r.emoji, p.display_name, p.username
  from public.title_reactions r
  join public.titles t on t.id = r.title_id
  join public.profiles p on p.user_id = r.author_id
  where t.id = p_title_id and auth.uid() is not null
    and (t.user_id = auth.uid() or public.can_view_title(t.user_id, t.genres, t.status))
  order by r.created_at, r.author_id;
$$;
revoke all on function cinemarchive_private.list_title_reactions(uuid) from public, anon;
grant execute on function cinemarchive_private.list_title_reactions(uuid) to authenticated;

create or replace function public.list_title_reactions(p_title_id uuid)
returns table (author_id uuid, emoji text, display_name text, username text)
language sql security invoker stable set search_path = '' as $$
  select * from cinemarchive_private.list_title_reactions(p_title_id);
$$;
revoke all on function public.list_title_reactions(uuid) from public, anon;
grant execute on function public.list_title_reactions(uuid) to authenticated;
