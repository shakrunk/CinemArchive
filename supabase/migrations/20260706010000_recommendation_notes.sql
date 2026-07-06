-- ============================================================
-- RECOMMENDATION NOTES
-- ============================================================

-- Lets a sender attach a short personal note when recommending a title —
-- without it, the recipient sees only a title/poster with no context for
-- why it was sent.
alter table recommendations add column note text;

-- Signature changes (new param / new return column) require drop-then-create;
-- `create or replace` cannot add parameters or alter a table return shape.
drop function if exists send_recommendation(uuid, integer, media_type, text, integer, text);
drop function if exists list_recommendations();

create function send_recommendation(
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
  status text,
  created_at timestamptz
)
language sql security definer stable as $$
  select r.id, r.sender_user_id, p.display_name, p.username, r.tmdb_id, r.type,
    r.title, r.year, r.poster_url, r.note, r.status, r.created_at
  from recommendations r
  join profiles p on p.user_id = r.sender_user_id
  where r.recipient_user_id = auth.uid()
  order by r.created_at desc;
$$;
