-- Immutable delivery receipts make an unknown plan-share outcome safe to retry.
create table if not exists cinemarchive_private.outing_share_receipts (
  user_id uuid not null references auth.users(id) on delete cascade,
  operation_id uuid not null,
  outing_id uuid not null,
  recipient_ids uuid[] not null,
  snapshot jsonb,
  created_at timestamptz not null default now(),
  primary key (user_id, operation_id)
);
alter table cinemarchive_private.outing_share_receipts enable row level security;
revoke all on cinemarchive_private.outing_share_receipts from public, anon, authenticated;

create or replace function cinemarchive_private.share_outing_plans(
  p_outing_id uuid, p_recipient_ids uuid[], p_operation_id uuid
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  recipients uuid[];
  receipt cinemarchive_private.outing_share_receipts;
  outing public.cinema_outings;
  t public.titles;
  recipient uuid;
  names jsonb;
  inserted integer;
  payload jsonb;
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if p_operation_id is null or p_outing_id is null then
    raise exception 'Operation and outing identifiers are required' using errcode = '22023';
  end if;
  if p_recipient_ids is null or cardinality(p_recipient_ids) = 0
     or array_position(p_recipient_ids, null) is not null then
    raise exception 'At least one valid recipient is required' using errcode = '22023';
  end if;
  select array_agg(distinct id order by id) into recipients from unnest(p_recipient_ids) id;
  insert into cinemarchive_private.outing_share_receipts(user_id, operation_id, outing_id, recipient_ids)
    values(me, p_operation_id, p_outing_id, recipients) on conflict do nothing;
  get diagnostics inserted = row_count;
  -- ON CONFLICT waits for the first transaction. A rollback lets this request
  -- insert; a committed receipt acknowledges the original immutable snapshot.
  if inserted = 0 then
    select * into receipt from cinemarchive_private.outing_share_receipts
      where user_id = me and operation_id = p_operation_id;
    if receipt.outing_id is distinct from p_outing_id or receipt.recipient_ids is distinct from recipients then
      raise exception 'Operation identifier was already used for different plans' using errcode = '22023';
    end if;
    return receipt.snapshot;
  end if;
  select * into outing from public.cinema_outings where id = p_outing_id and user_id = me;
  if outing.id is null then raise exception 'Outing not found' using errcode = '42501'; end if;
  if outing.status <> 'scheduled' or outing.ends_at <= now() then
    raise exception 'Can only share plans for an upcoming scheduled outing' using errcode = '22023';
  end if;
  select * into t from public.titles where id = outing.title_id and user_id = me;
  if t.id is null then raise exception 'Title not found' using errcode = '42501'; end if;
  -- Native legacy rows contain string names; newer clients send name objects.
  select coalesce(jsonb_agg(v.name order by v.ordinality)
      filter (where v.name is not null and btrim(v.name) <> ''), '[]'::jsonb) into names
    from (
      select ordinality, case
        when jsonb_typeof(value) = 'string' then value #>> '{}'
        when jsonb_typeof(value) = 'object' and jsonb_typeof(value -> 'name') = 'string' then value ->> 'name'
        else null end as name
      from jsonb_array_elements(outing.companions) with ordinality
    ) v;
  foreach recipient in array recipients loop
    if recipient = me or not public.is_friend(me, recipient) then
      raise exception 'Can only share plans with accepted friends' using errcode = '42501';
    end if;
  end loop;
  payload := jsonb_build_object(
        'tmdb_id', t.tmdb_id, 'type', t.type, 'title', t.title, 'year', t.year,
        'poster_url', t.poster_url, 'showtime', outing.showtime, 'ends_at', outing.ends_at,
        'venue', outing.venue, 'format', outing.format, 'seat', outing.seat, 'companions', names);
  foreach recipient in array recipients loop
    insert into public.notifications(recipient_id, type, actor_id, title_id, payload)
      values(recipient, 'outing_plans_shared', me, outing.title_id, payload);
  end loop;
  update cinemarchive_private.outing_share_receipts set snapshot = payload
    where user_id = me and operation_id = p_operation_id;
  return payload;
end;
$$;
revoke all on function cinemarchive_private.share_outing_plans(uuid, uuid[], uuid) from public, anon;
grant execute on function cinemarchive_private.share_outing_plans(uuid, uuid[], uuid) to authenticated;

create or replace function public.share_outing_plans(p_outing_id uuid, p_recipient_ids uuid[], p_operation_id uuid)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.share_outing_plans(p_outing_id, p_recipient_ids, p_operation_id);
$$;
-- Older clients retain explicit resend behavior; upgraded clients reuse an ID.
create or replace function public.share_outing_plans(p_outing_id uuid, p_recipient_ids uuid[])
returns void language sql security invoker set search_path = '' as $$
  select cinemarchive_private.share_outing_plans(p_outing_id, p_recipient_ids, gen_random_uuid());
$$;
revoke all on function public.share_outing_plans(uuid, uuid[]) from public, anon;
revoke all on function public.share_outing_plans(uuid, uuid[], uuid) from public, anon;
grant execute on function public.share_outing_plans(uuid, uuid[]) to authenticated;
grant execute on function public.share_outing_plans(uuid, uuid[], uuid) to authenticated;
