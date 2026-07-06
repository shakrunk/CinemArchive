-- ============================================================
-- TITLE COMMENTS & REACTIONS — friends-only social layer
-- ============================================================
--
-- Deliberately friends-only: no shared-key-read policy exists anywhere on
-- these tables, so an anonymous share-link session (no auth.uid()) can never
-- read or write here. Flat comments (no replies), fixed emoji reaction set —
-- matches this app's scale.
--
-- Same pattern as `recommendations`: no client-facing insert/update/delete
-- policy at all — every mutation goes exclusively through a SECURITY
-- DEFINER RPC below (add_title_comment / delete_title_comment /
-- set_title_reaction), which validates ownership/friendship itself and then
-- bypasses RLS for the actual write. Reads likewise go through
-- list_title_comments/list_title_reactions rather than a SELECT policy, so
-- there is exactly one authorization check per operation, not a
-- policy-and-RPC pair that can drift out of sync.
--
-- NOT validated against a live Postgres in this environment (same caveat as
-- 20260706060000_share_scopes.sql) — run that migration's checklist plus a
-- comment/reaction smoke test (post as a friend, confirm an anonymous
-- ?share= session never sees write affordances) before merging to main.

create table title_comments (
  id          uuid primary key default gen_random_uuid(),
  title_id    uuid not null references titles(id) on delete cascade,
  author_id   uuid not null references auth.users(id) on delete cascade,
  body        text not null check (char_length(body) between 1 and 1000),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index title_comments_title_id_idx on title_comments(title_id, created_at);

alter table title_comments enable row level security;

create table title_reactions (
  title_id    uuid not null references titles(id) on delete cascade,
  author_id   uuid not null references auth.users(id) on delete cascade,
  emoji       text not null check (emoji in ('👍', '❤️', '😂', '😮')),
  created_at  timestamptz not null default now(),
  primary key (title_id, author_id) -- one reaction per user per title; changing emoji replaces it
);

create index title_reactions_title_id_idx on title_reactions(title_id);

alter table title_reactions enable row level security;

create trigger title_comments_updated_at
  before update on title_comments
  for each row execute function update_updated_at();

-- ─── RPCs (mirror the send_recommendation SECURITY DEFINER style) ──────────

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

  return result;
end;
$$;

create or replace function delete_title_comment(p_comment_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  delete from title_comments c
  where c.id = p_comment_id
    and (
      c.author_id = me
      or exists (select 1 from titles t where t.id = c.title_id and t.user_id = me)
    );

  if not found then
    raise exception 'Comment not found or not authorized to delete it';
  end if;
end;
$$;

-- Upsert-or-delete-on-null: single call to add, change, or remove a reaction.
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
end;
$$;

-- Joined against profiles for display, same shape as list_friendships().
-- Callable by owner or friend (checked here, not via a SELECT policy).
create or replace function list_title_comments(p_title_id uuid)
returns table (
  id uuid,
  author_id uuid,
  body text,
  created_at timestamptz,
  display_name text,
  username text
)
language plpgsql security definer stable as $$
declare
  me uuid := auth.uid();
  owner_id uuid;
begin
  select user_id into owner_id from titles where id = p_title_id;
  if owner_id is null or (owner_id <> me and not is_friend(me, owner_id)) then
    return;
  end if;

  return query
    select c.id, c.author_id, c.body, c.created_at, p.display_name, p.username
    from title_comments c
    join profiles p on p.user_id = c.author_id
    where c.title_id = p_title_id
    order by c.created_at asc;
end;
$$;

create or replace function list_title_reactions(p_title_id uuid)
returns table (
  author_id uuid,
  emoji text,
  display_name text,
  username text
)
language plpgsql security definer stable as $$
declare
  me uuid := auth.uid();
  owner_id uuid;
begin
  select user_id into owner_id from titles where id = p_title_id;
  if owner_id is null or (owner_id <> me and not is_friend(me, owner_id)) then
    return;
  end if;

  return query
    select r.author_id, r.emoji, p.display_name, p.username
    from title_reactions r
    join profiles p on p.user_id = r.author_id
    where r.title_id = p_title_id;
end;
$$;
