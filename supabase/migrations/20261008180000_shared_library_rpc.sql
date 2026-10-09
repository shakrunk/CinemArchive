-- Shared library RPC (20261008180000)
-- Replaces the session-GUC share-link mechanism (set_shared_token ->
-- set_config('app.shared_token', ..., false) + RLS reading it) with ONE
-- stateless RPC, get_shared_library(p_token, p_offset, p_limit).
--
-- Why: the GUC lives on a pooled Postgres connection, so the token can be
-- missing for the follow-up read or LEAK to an unrelated later request.
-- After this migration NO RLS policy or function consults app.shared_token:
-- a stale/pooled GUC grants nothing. Friend (is_friend + share_scopes) and
-- owner access are unchanged.
--
-- ROLLOUT: web and Android must ship the get_shared_library client in the
-- same release. Old clients' set_shared_token call now raises 42501 (see
-- docs/android-contracts/shared-library-rpc.md).
--
-- Idempotent: safe to re-run (create or replace / if not exists / drop if exists).

-- ------------------------------------------------------------
-- 1. Non-exposed schema for the SECURITY DEFINER implementation.
--    Kept separate from cinemarchive_private (whose USAGE is deliberately
--    withheld from anon) because the INVOKER wrapper below runs as the
--    caller and anonymous share-link visitors must be able to reach it.
--    Do NOT add this schema to PostgREST's exposed schemas (db-schemas).
-- ------------------------------------------------------------
create schema if not exists cinemarchive_sharing;
revoke all on schema cinemarchive_sharing from public;
grant usage on schema cinemarchive_sharing to anon, authenticated;

-- ------------------------------------------------------------
-- 2. Remove every RLS dependence on app.shared_token.
-- ------------------------------------------------------------

-- 2a0. is_friend: same logic, now with an empty search_path. REQUIRED because
--      can_view_title (below) has an empty search_path and a function without
--      its own SET inherits the caller's, which would break the unqualified
--      `friendships` reference.
create or replace function public.is_friend(user_a uuid, user_b uuid)
returns boolean
language sql security definer stable set search_path = '' as $$
  select exists (
    select 1 from public.friendships
    where user_id_a = least(user_a, user_b)
      and user_id_b = greatest(user_a, user_b)
      and status = 'accepted'
  );
$$;

-- 2a. can_view_title: friend branch only. Same signature, so the ~20
--     "shared/friend read" policies keep working untouched. Hardened with an
--     empty search_path (every reference qualified).
create or replace function public.can_view_title(
  p_owner_user_id uuid,
  p_genres text[],
  p_status public.watch_status
) returns boolean
language sql security definer stable set search_path = '' as $$
  select
    public.is_friend(auth.uid(), p_owner_user_id)
    and public.title_in_scope(
      p_genres,
      p_status,
      (select s.allowed_genres from public.share_scopes s
        where s.friend_user_id = auth.uid() and s.owner_user_id = p_owner_user_id),
      (select s.allowed_statuses from public.share_scopes s
        where s.friend_user_id = auth.uid() and s.owner_user_id = p_owner_user_id)
    );
$$;

-- 2b. user_prefs: the token-based read policy is removed (the RPC returns the
--     owner's ledger_layout instead). The "friend read" policy stays.
drop policy if exists "user_prefs: shared key read" on public.user_prefs;

-- 2c. is_valid_shared_token is no longer referenced by any policy. Keep the
--     function (harmless, avoids drop-dependency surprises on drifted DBs) but
--     remove it from the API surface.
revoke all on function public.is_valid_shared_token(text, uuid) from public, anon, authenticated;

-- 2d. set_shared_token: compatibility stub. Never touches the session GUC and
--     reads/writes no tables. It FAILS LOUDLY (42501) instead of silently
--     succeeding: a silent no-op would make an old client proceed to read
--     titles, get an empty library, and show a misleading "empty share"; and a
--     touch-only variant would give an unauthenticated token oracle plus
--     notification side-effects without any benefit. Grants are kept so old
--     clients get this clear error rather than a 404 "function not found".
create or replace function public.set_shared_token(token text)
returns void
language plpgsql security invoker set search_path = '' as $$
begin
  raise exception 'set_shared_token has been retired; call get_shared_library(p_token) instead'
    using errcode = '42501',
          hint = 'Update the client: shared links are now read through the stateless get_shared_library RPC.';
end;
$$;
revoke all on function public.set_shared_token(text) from public;
grant execute on function public.set_shared_token(text) to anon, authenticated;

-- 2e. shared_key_owner: stateless already (takes the token as an argument), so
--     it is KEPT for the rollout window (cached clients) but hardened
--     (empty search_path). Deprecated: get_shared_library returns ownerUserId.
--     Drop it in a later migration once no client calls it.
create or replace function public.shared_key_owner(token_val text)
returns uuid language sql security definer stable set search_path = '' as $$
  select k.user_id from public.shared_access_keys k
  where k.token = token_val
    and k.is_active = true
    and (k.expires_at is null or k.expires_at > now())
  limit 1;
$$;
revoke all on function public.shared_key_owner(text) from public;
grant execute on function public.shared_key_owner(text) to anon, authenticated;

-- 2f. shared_access_keys.user_id is NOT NULL with no default, and the web
--     createSharedKey() inserts without it (=> 23502 / RLS violation). Default
--     it to the caller. The RLS check (auth.uid() = user_id) still applies, so
--     nobody can mint a key for another user.
alter table public.shared_access_keys alter column user_id set default auth.uid();

-- ------------------------------------------------------------
-- 3. Private SECURITY DEFINER implementation.
-- ------------------------------------------------------------
create or replace function cinemarchive_sharing.get_shared_library(
  p_token text,
  p_offset integer default 0,
  p_limit integer default 100
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_key       public.shared_access_keys%rowtype;
  v_genres    text[];
  v_statuses  public.watch_status[];
  v_offset    integer := greatest(coalesce(p_offset, 0), 0);
  v_limit     integer := least(greatest(coalesce(p_limit, 100), 1), 200);
  v_ids       uuid[];
  v_has_more  boolean;
  v_titles    jsonb;
  v_layout    jsonb;
begin
  -- Unauthenticated-by-design entry point: every failure is the same stable
  -- error so the response is not an oracle for "exists but revoked" etc.
  if p_token is null or p_token = '' or length(p_token) > 512 then
    raise exception 'Invalid or expired share link' using errcode = '42501';
  end if;

  select * into v_key
  from public.shared_access_keys k
  where k.token = p_token
    and k.is_active = true
    and (k.expires_at is null or k.expires_at > now());
  if not found then
    raise exception 'Invalid or expired share link' using errcode = '42501';
  end if;

  -- Usage touch + throttled owner notification, first page only (later pages
  -- of one load are not new "uses"). The row lock serializes concurrent
  -- callers; the re-check under the lock sees the previous caller's
  -- last_used_at, so at most one notification per key per hour (same
  -- semantics as the old set_shared_token) and none for expired/revoked keys.
  if v_offset = 0 then
    select * into v_key
    from public.shared_access_keys k
    where k.id = v_key.id
      and k.is_active = true
      and (k.expires_at is null or k.expires_at > now())
    for update;
    if not found then
      raise exception 'Invalid or expired share link' using errcode = '42501';
    end if;

    if v_key.last_used_at is null or v_key.last_used_at < now() - interval '1 hour' then
      insert into public.notifications (recipient_id, type, payload)
      values (v_key.user_id, 'share_link_used', jsonb_build_object('label', v_key.label));
    end if;
    update public.shared_access_keys k set last_used_at = now() where k.id = v_key.id;
  end if;

  -- Link scope (no row = unrestricted).
  select s.allowed_genres, s.allowed_statuses into v_genres, v_statuses
  from public.share_scopes s where s.shared_key_id = v_key.id;

  -- Stable page of visible title ids (+1 row to compute hasMore).
  select array_agg(q.id order by q.added_at desc, q.id asc) into v_ids
  from (
    select t.id, t.added_at
    from public.titles t
    where t.user_id = v_key.user_id
      and public.title_in_scope(t.genres, t.status, v_genres, v_statuses)
    order by t.added_at desc, t.id asc
    offset v_offset limit v_limit + 1
  ) q;

  v_has_more := coalesce(cardinality(v_ids), 0) > v_limit;
  if v_has_more then
    v_ids := v_ids[1:v_limit];
  end if;

  -- Nested graph shaped like the web TITLE_SELECT (select *, all embeds), each
  -- child restricted to the owner AND the visible title. Explicit allow-list
  -- of tables: cinema_outings, lists, list_items, notifications, pins, etc.
  -- never appear.
  select coalesce(jsonb_agg(
    to_jsonb(t) || jsonb_build_object(
      'title_cast', coalesce((select jsonb_agg(to_jsonb(c) order by c.cast_order, c.id)
          from public.title_cast c where c.title_id = t.id and c.user_id = t.user_id), '[]'::jsonb),
      'title_crew', coalesce((select jsonb_agg(to_jsonb(c) order by c.id)
          from public.title_crew c where c.title_id = t.id and c.user_id = t.user_id), '[]'::jsonb),
      'seasons', coalesce((select jsonb_agg(
            to_jsonb(s) || jsonb_build_object('season_cast', coalesce((
              select jsonb_agg(to_jsonb(sc) order by sc.cast_order, sc.id)
              from public.season_cast sc
              where sc.season_id = s.id and sc.title_id = t.id and sc.user_id = t.user_id), '[]'::jsonb))
            order by s.season_number, s.id)
          from public.seasons s where s.title_id = t.id and s.user_id = t.user_id), '[]'::jsonb),
      'viewings', coalesce((select jsonb_agg(to_jsonb(v) order by v.viewed_at desc nulls last, v.id)
          from public.viewings v where v.title_id = t.id and v.user_id = t.user_id), '[]'::jsonb),
      'episodes', coalesce((select jsonb_agg(
            to_jsonb(e) || jsonb_build_object(
              'episode_crew', coalesce((select jsonb_agg(to_jsonb(x) order by x.id)
                  from public.episode_crew x
                  where x.episode_id = e.id and x.title_id = t.id and x.user_id = t.user_id), '[]'::jsonb),
              'episode_watch_events', coalesce((select jsonb_agg(to_jsonb(x) order by x.created_at, x.id)
                  from public.episode_watch_events x
                  where x.episode_id = e.id and x.user_id = t.user_id), '[]'::jsonb),
              'episode_ratings', coalesce((select jsonb_agg(to_jsonb(x) order by x.rated_at, x.id)
                  from public.episode_ratings x
                  where x.episode_id = e.id and x.user_id = t.user_id), '[]'::jsonb),
              'episode_reviews', coalesce((select jsonb_agg(to_jsonb(x) order by x.reviewed_at, x.id)
                  from public.episode_reviews x
                  where x.episode_id = e.id and x.user_id = t.user_id), '[]'::jsonb))
            order by e.season_number, e.episode_number, e.id)
          from public.episodes e where e.title_id = t.id and e.user_id = t.user_id), '[]'::jsonb)
    ) order by u.ord
  ), '[]'::jsonb) into v_titles
  from unnest(coalesce(v_ids, '{}'::uuid[])) with ordinality as u(id, ord)
  join public.titles t on t.id = u.id;

  select p.ledger_layout into v_layout
  from public.user_prefs p where p.user_id = v_key.user_id;

  return jsonb_build_object(
    'ownerUserId', v_key.user_id,
    'titles',      v_titles,
    'ledgerLayout', v_layout,   -- SQL NULL -> JSON null
    'hasMore',     v_has_more
  );
end;
$$;
revoke all on function cinemarchive_sharing.get_shared_library(text, integer, integer) from public;
grant execute on function cinemarchive_sharing.get_shared_library(text, integer, integer) to anon, authenticated;

-- ------------------------------------------------------------
-- 4. Public SECURITY INVOKER wrapper (the only thing PostgREST exposes).
-- ------------------------------------------------------------
create or replace function public.get_shared_library(
  p_token text,
  p_offset integer default 0,
  p_limit integer default 100
) returns jsonb
language sql security invoker set search_path = ''
as $$ select cinemarchive_sharing.get_shared_library(p_token, p_offset, p_limit); $$;
revoke all on function public.get_shared_library(text, integer, integer) from public;
grant execute on function public.get_shared_library(text, integer, integer) to anon, authenticated;
