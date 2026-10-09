-- Retain the original title revision only when completion actually changed status.
-- Do not backfill historical records from a current or merely preserved revision.
alter table cinemarchive_private.outing_completions
  add column completion_title_version timestamptz;

create or replace function cinemarchive_private.complete_cinema_outing(
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
    expected_version := cinemarchive_private.library_causal_revision(p_expected_operation_id,'cinema_outings',jsonb_build_object('id',p_outing_id))::timestamptz;
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
    insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,canonical_viewing_version,completion_outing_version,completed_title_version,previous_status,completion_title_version)
      values(me,outing.id,title.id,viewing.id,viewing.updated_at,outing.updated_at,title.updated_at,previous,
        case when previous <> 'watched' then title.updated_at else null end) returning * into completion;
    select coalesce(jsonb_agg(case when jsonb_typeof(c)='string' then c#>>'{}' else c->>'name' end order by n),'[]'::jsonb)
      into names from jsonb_array_elements(outing.companions) with ordinality as x(c,n);
    insert into public.notifications(recipient_id,type,title_id,payload)
      values(me,'outing_completed',title.id,jsonb_build_object('venue',outing.venue,'companions',names,
        'outingId',outing.id,'canonicalViewingId',viewing.id));
    outcome := 'applied';
  end if;
  v_result := jsonb_build_object('status',outcome,'operationId',p_operation_id,'outingId',outing.id,
    'request',operations->0,'canonicalViewingId',completion.canonical_viewing_id,
    'completionOutingVersion',completion.completion_outing_version,
    'completionTitleVersion',completion.completion_title_version,
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',outing.id),'row',jsonb_build_object(
      'id',outing.id,'user_id',me,'title_id',outing.title_id,
      'updated_at',completion.completion_outing_version))));
  -- This baseline is the revision created by completion, never a later read of the event.
  -- Historical completions remain unproven; accepted older receipts return above unchanged.
  if completion.canonical_viewing_id is not null and completion.canonical_viewing_version is not null then
    v_result := jsonb_set(v_result,'{rows}',(v_result->'rows') || jsonb_build_array(jsonb_build_object(
      'table','viewings','key',jsonb_build_object('id',completion.canonical_viewing_id),
      'row',jsonb_build_object('id',completion.canonical_viewing_id,'user_id',me,
        'title_id',completion.title_id,'outing_id',completion.outing_id,
        'updated_at',completion.canonical_viewing_version))));
  end if;
  -- Only the title revision actually produced by this completion can authorize
  -- a dependent title edit. Preserved and historical title snapshots are not effects.
  if completion.completion_title_version is not null then
    v_result := jsonb_set(v_result,'{rows}',(v_result->'rows') || jsonb_build_array(jsonb_build_object(
      'table','titles','key',jsonb_build_object('id',completion.title_id),
      'row',jsonb_build_object('id',completion.title_id,'user_id',me,
        'updated_at',completion.completion_title_version))));
  end if;
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return cinemarchive_private.outing_completion_current(v_result);
end;
$$;

create or replace function cinemarchive_private.library_causal_revision(
  p_operation_id uuid, p_table text, p_key jsonb, p_accepted_replay boolean default false
) returns text language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  receipt cinemarchive_private.library_command_receipts;
  revision text;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id;
  if p_table='cinema_outings' and not p_accepted_replay then
    if receipt.operations->0->>'kind' in ('ticket.attach','ticket.detach')
      and receipt.result->>'outingRevisionGuarded' is distinct from 'true' then
      raise exception 'Ticket association receipt is not an outing revision guard; review required' using errcode='40001';
    end if;
    if receipt.operations->0->>'kind'='outing.complete'
      and receipt.result->>'completionOutingVersion' is null then
      raise exception 'Historical completion has no proven outing revision; review required' using errcode='40001';
    end if;
  end if;
  if p_table='titles' and not p_accepted_replay
    and receipt.operations->0->>'kind'='outing.complete'
    and receipt.result->>'completionTitleVersion' is null then
    raise exception 'Completion has no proven title effect; refresh or review before editing' using errcode='40001';
  end if;
  if p_table='titles' and not p_accepted_replay
    and receipt.operations->0->>'kind'='outing.revert'
    and receipt.result->>'titleStatusRestored' is distinct from 'true' then
    raise exception 'Reversal preserved the title; refresh or review its revision before editing' using errcode='40001';
  end if;
  select effect.value->'row'->>'updated_at' into revision
    from jsonb_array_elements(coalesce(receipt.result->'rows','[]'::jsonb)) with ordinality effect(value,ordinal)
    where effect.value->>'table'=p_table and effect.value->'key'=p_key
    order by effect.ordinal desc limit 1;
  if revision is null then raise exception 'The preceding change has no matching revision' using errcode='40001'; end if;
  return revision;
end;
$$;
