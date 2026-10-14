-- Canonical outing completion (20261008194606)
-- Keep identity after event deletion; never deduplicate historical independent viewings.
create table cinemarchive_private.outing_completions (
  user_id uuid not null references auth.users(id) on delete cascade,
  outing_id uuid not null,
  title_id uuid not null,
  canonical_viewing_id uuid,
  completed_title_version timestamptz,
  previous_status public.watch_status,
  created_at timestamptz not null default now(),
  primary key(user_id,outing_id)
);
revoke all on cinemarchive_private.outing_completions from public,anon,authenticated;

create function cinemarchive_private.outing_completion_current(p_receipt jsonb)
returns jsonb language plpgsql security definer stable set search_path = '' as $$
declare
  me uuid := auth.uid();
  outing public.cinema_outings;
  viewing public.viewings;
  title public.titles;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  select * into outing from public.cinema_outings where id=(p_receipt->>'outingId')::uuid and user_id=me;
  if outing.id is not null then
    select * into title from public.titles where id=outing.title_id and user_id=me;
    select * into viewing from public.viewings where id=(p_receipt->>'canonicalViewingId')::uuid
      and user_id=me and outing_id=outing.id and title_id=outing.title_id;
  end if;
  return p_receipt || jsonb_build_object(
    'outing',case when outing.id is null then null else to_jsonb(outing) end,
    'viewing',case when viewing.id is null then null else to_jsonb(viewing) end,
    'title',case when title.id is null then null else jsonb_build_object('id',title.id,'status',title.status,'updated_at',title.updated_at) end);
end;
$$;
revoke all on function cinemarchive_private.outing_completion_current(jsonb) from public,anon,authenticated;

create function cinemarchive_private.complete_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_provisional_viewing_id uuid,
  p_expected_updated_at timestamptz,p_tz text default 'UTC',p_expected_operation_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  completion cinemarchive_private.outing_completions;
  outing public.cinema_outings;
  title public.titles;
  viewing public.viewings;
  previous public.watch_status;
  v_result jsonb;
  names jsonb;
  outcome text;
  expected_version timestamptz;
  dependency jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_outing_id is null or p_operation_id is null or p_provisional_viewing_id is null
    or ((p_expected_updated_at is null) = (p_expected_operation_id is null))
    or (p_expected_updated_at is not null and not isfinite(p_expected_updated_at))
    or p_expected_operation_id=p_operation_id
    or p_tz is null or not exists(select 1 from pg_catalog.pg_timezone_names where name=p_tz) then
    raise exception 'Invalid outing completion command' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind','outing.complete','outingId',p_outing_id,
    'provisionalViewingId',p_provisional_viewing_id,'expectedUpdatedAt',p_expected_updated_at,
    'expectedOperationId',p_expected_operation_id,'timezone',p_tz));
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations<>operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result<>'{}'::jsonb then return cinemarchive_private.outing_completion_current(receipt.result); end if;
  expected_version := p_expected_updated_at;
  if p_expected_operation_id is not null then
    select result into dependency from cinemarchive_private.library_command_receipts
      where user_id=me and operation_id=p_expected_operation_id;
    select (effect->'row'->>'updated_at')::timestamptz into expected_version
      from jsonb_array_elements(coalesce(dependency->'rows','[]'::jsonb)) effect
      where effect->>'table'='cinema_outings' and effect->'key'->>'id'=p_outing_id::text
        and effect->'row'->>'id'=p_outing_id::text limit 1;
    if expected_version is null then raise exception 'Outing dependency is not acknowledged' using errcode='40001'; end if;
  end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if outing.id is null then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return jsonb_build_object('status','missing','operationId',p_operation_id,'outingId',p_outing_id,
      'request',operations->0,'canonicalViewingId',null,'outing',null,'viewing',null,'title',null);
  end if;
  select * into title from public.titles where id=outing.title_id and user_id=me for update;
  if title.id is null then raise exception 'Outing title ownership required' using errcode='42501'; end if;
  select * into completion from cinemarchive_private.outing_completions where user_id=me and outing_id=p_outing_id;
  if outing.status='completed' then
    if completion.outing_id is null then
      -- Older completion: preserve the explicit link only, including absence after deletion.
      if outing.completed_viewing_id is not null and not exists(select 1 from public.viewings v
        where v.id=outing.completed_viewing_id and v.user_id=me and v.title_id=outing.title_id and v.outing_id=outing.id) then
        raise exception 'Completed outing link requires review' using errcode='40001'; end if;
      insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,previous_status)
        values(me,outing.id,outing.title_id,outing.completed_viewing_id,outing.previous_status) returning * into completion;
    end if;
    outcome := 'already_completed';
  elsif outing.status<>'scheduled' or outing.ends_at>now() or outing.updated_at<>expected_version
    or completion.outing_id is not null or exists(select 1 from public.viewings v where v.user_id=me and v.outing_id=outing.id) then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return cinemarchive_private.outing_completion_current(jsonb_build_object('status','conflict','operationId',p_operation_id,
      'outingId',p_outing_id,'request',operations->0,'canonicalViewingId',completion.canonical_viewing_id));
  else
    previous := title.status;
    insert into public.viewings(id,title_id,user_id,viewed_at,venue,companions,outing_id)
      values(p_provisional_viewing_id,outing.title_id,me,(outing.showtime at time zone p_tz)::date,outing.venue,outing.companions,outing.id)
      returning * into viewing;
    if title.status<>'watched' then
      update public.titles set status='watched' where id=title.id and user_id=me returning * into title;
    end if;
    update public.cinema_outings set status='completed',previous_status=previous,completed_viewing_id=viewing.id
      where id=outing.id and user_id=me returning * into outing;
    insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,completed_title_version,previous_status)
      values(me,outing.id,title.id,viewing.id,title.updated_at,previous) returning * into completion;
    select coalesce(jsonb_agg(case when jsonb_typeof(c)='string' then c#>>'{}' else c->>'name' end order by n),'[]'::jsonb)
      into names from jsonb_array_elements(outing.companions) with ordinality as x(c,n);
    insert into public.notifications(recipient_id,type,title_id,payload)
      values(me,'outing_completed',title.id,jsonb_build_object('venue',outing.venue,'companions',names));
    outcome := 'applied';
  end if;
  v_result := jsonb_build_object('status',outcome,'operationId',p_operation_id,'outingId',outing.id,
    'request',operations->0,'canonicalViewingId',completion.canonical_viewing_id,
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',outing.id),'row',to_jsonb(outing))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return cinemarchive_private.outing_completion_current(v_result);
end;
$$;

create function public.complete_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_provisional_viewing_id uuid,
  p_expected_updated_at timestamptz,p_tz text default 'UTC',p_expected_operation_id uuid default null
) returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.complete_cinema_outing(p_outing_id,p_operation_id,p_provisional_viewing_id,p_expected_updated_at,p_tz,p_expected_operation_id);
$$;
revoke all on function public.complete_cinema_outing(uuid,uuid,uuid,timestamptz,text,uuid),
  cinemarchive_private.complete_cinema_outing(uuid,uuid,uuid,timestamptz,text,uuid) from public,anon;
grant execute on function public.complete_cinema_outing(uuid,uuid,uuid,timestamptz,text,uuid),
  cinemarchive_private.complete_cinema_outing(uuid,uuid,uuid,timestamptz,text,uuid) to authenticated;

create function cinemarchive_private.complete_due_outings(p_tz text default 'UTC')
returns table(outing_id uuid,title_id uuid,viewing_id uuid,new_title_status public.watch_status,previous_status public.watch_status)
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  zone text;
  candidate public.cinema_outings;
  result jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  select name into zone from pg_catalog.pg_timezone_names where name=p_tz;
  zone := coalesce(zone,'UTC'); -- Preserve older callers' documented fallback.
  for candidate in select * from public.cinema_outings co where co.user_id=me and co.status='scheduled'
    and co.ends_at<=now() order by co.id for update skip locked loop
    result := cinemarchive_private.complete_cinema_outing(candidate.id,gen_random_uuid(),gen_random_uuid(),candidate.updated_at,zone);
    if result->>'status'='applied' then
      outing_id := candidate.id; title_id := candidate.title_id;
      viewing_id := (result->>'canonicalViewingId')::uuid;
      new_title_status := (result->'title'->>'status')::public.watch_status;
      previous_status := (result->'outing'->>'previous_status')::public.watch_status;
      return next;
    end if;
  end loop;
end;
$$;
create or replace function public.complete_due_outings(p_tz text default 'UTC')
returns table(outing_id uuid,title_id uuid,viewing_id uuid,new_title_status public.watch_status,previous_status public.watch_status)
language sql security invoker set search_path = '' as $$
  select * from cinemarchive_private.complete_due_outings(p_tz);
$$;
revoke all on function public.complete_due_outings(text),cinemarchive_private.complete_due_outings(text) from public,anon;
grant execute on function public.complete_due_outings(text),cinemarchive_private.complete_due_outings(text) to authenticated;
