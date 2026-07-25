-- ============================================================
-- RECOMMENDATION WATCH URL
-- ============================================================

-- Lets a sender attach a specific "where to watch" link to a recommendation —
-- distinct from titles.custom_watch_url (which is only shown to shared-view
-- visitors of the owner's own library). The send panel pre-fills this from
-- the title's custom_watch_url when set, but it's captured as its own
-- per-recommendation snapshot so it survives the title's link changing later
-- and can be overridden per recipient.
alter table recommendations add column watch_url text;

-- Signature changes (new param / new return column) require drop-then-create;
-- `create or replace` cannot add parameters or alter a table return shape.
drop function if exists send_recommendation(uuid, integer, media_type, text, integer, text, text);
drop function if exists list_recommendations();

create function send_recommendation(
  recipient_id uuid,
  p_tmdb_id integer,
  p_type media_type,
  p_title text,
  p_year integer,
  p_poster_url text,
  p_note text default null,
  p_watch_url text default null
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

  insert into recommendations (sender_user_id, recipient_user_id, tmdb_id, type, title, year, poster_url, note, watch_url)
  values (me, recipient_id, p_tmdb_id, p_type, p_title, p_year, p_poster_url, nullif(trim(p_note), ''), nullif(trim(p_watch_url), ''))
  on conflict (sender_user_id, recipient_user_id, tmdb_id, type)
  do update set title = excluded.title, year = excluded.year, poster_url = excluded.poster_url,
    note = excluded.note, watch_url = excluded.watch_url, status = 'unread', updated_at = now();

  insert into notifications (recipient_id, type, actor_id, payload)
  values (recipient_id, 'recommendation_received', me, jsonb_build_object('tmdb_id', p_tmdb_id, 'type', p_type, 'title', p_title));
end;
$$;

create function list_recommendations()
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
  note text,
  watch_url text,
  status text,
  created_at timestamptz
)
language sql security definer stable as $$
  select r.id, r.sender_user_id, p.display_name, p.username, r.tmdb_id, r.type,
    r.title, r.year, r.poster_url, r.note, r.watch_url, r.status, r.created_at
  from recommendations r
  join profiles p on p.user_id = r.sender_user_id
  where r.recipient_user_id = auth.uid()
  order by r.created_at desc;
$$;
