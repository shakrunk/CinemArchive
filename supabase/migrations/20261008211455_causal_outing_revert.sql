-- A reversal queued after an edit uses that edit's immutable receipt revision.
-- Defaulted dependency arguments preserve the original five-argument call.
drop function public.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz);
drop function cinemarchive_private.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz);
create function cinemarchive_private.revert_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_expected_updated_at timestamptz,
  p_expected_viewing_id uuid,p_expected_viewing_updated_at timestamptz,
  p_expected_operation_id uuid default null,p_expected_viewing_operation_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  completion cinemarchive_private.outing_completions;
  outing public.cinema_outings;
  title public.titles;
  viewing public.viewings;
  canonical uuid;
  restored boolean := false;
  expected_outing_at timestamptz := p_expected_updated_at;
  expected_viewing_at timestamptz := p_expected_viewing_updated_at;
  v_result jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_outing_id is null or p_operation_id is null or (p_expected_updated_at is null) = (p_expected_operation_id is null)
    or (p_expected_updated_at is not null and not isfinite(p_expected_updated_at))
    or p_expected_operation_id = p_operation_id or p_expected_viewing_operation_id = p_operation_id
    or (p_expected_viewing_operation_id is not null and
      (p_expected_viewing_updated_at is not null or p_expected_viewing_id is null))
    or (p_expected_viewing_updated_at is not null and not isfinite(p_expected_viewing_updated_at)) then
    raise exception 'Invalid outing revert command' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind','outing.revert','outingId',p_outing_id,
    'expectedUpdatedAt',p_expected_updated_at,'expectedViewingId',p_expected_viewing_id,
    'expectedViewingUpdatedAt',p_expected_viewing_updated_at));
  -- Keep old no-dependency request signatures byte-for-byte compatible with receipts.
  if p_expected_operation_id is not null then
    operations := jsonb_set(operations,'{0,expectedOperationId}',to_jsonb(p_expected_operation_id)); end if;
  if p_expected_viewing_operation_id is not null then
    operations := jsonb_set(operations,'{0,expectedViewingOperationId}',to_jsonb(p_expected_viewing_operation_id)); end if;
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations<>operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result<>'{}'::jsonb then return cinemarchive_private.outing_completion_current(receipt.result); end if;
  if p_expected_operation_id is not null then
    select (effect.value->'row'->>'updated_at')::timestamptz into expected_outing_at
      from cinemarchive_private.library_command_receipts predecessor
      cross join lateral jsonb_array_elements(predecessor.result->'rows') with ordinality effect(value,ordinal)
      where predecessor.user_id=me and predecessor.operation_id=p_expected_operation_id
        and effect.value->>'table'='cinema_outings'
        and effect.value->'key' = jsonb_build_object('id',p_outing_id)
      order by effect.ordinal desc limit 1;
    if expected_outing_at is null then raise exception 'Preceding outing change has no matching revision' using errcode='40001'; end if;
  end if;
  if p_expected_viewing_operation_id is not null then
    select (effect.value->'row'->>'updated_at')::timestamptz into expected_viewing_at
      from cinemarchive_private.library_command_receipts predecessor
      cross join lateral jsonb_array_elements(predecessor.result->'rows') with ordinality effect(value,ordinal)
      where predecessor.user_id=me and predecessor.operation_id=p_expected_viewing_operation_id
        and effect.value->>'table'='viewings'
        and effect.value->'key' = jsonb_build_object('id',p_expected_viewing_id)
      order by effect.ordinal desc limit 1;
    if expected_viewing_at is null then raise exception 'Preceding viewing change has no matching revision' using errcode='40001'; end if;
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
  canonical := case when completion.outing_id is null then outing.completed_viewing_id else completion.canonical_viewing_id end;
  -- Read the identified row before deciding whether its link is still valid.
  select * into viewing from public.viewings where id=canonical and user_id=me for update;
  if outing.status<>'completed' or outing.updated_at<>expected_outing_at
    or canonical is distinct from p_expected_viewing_id
    or (outing.completed_viewing_id is not null and outing.completed_viewing_id is distinct from canonical)
    or (viewing.id is not null and (viewing.outing_id is distinct from outing.id or viewing.title_id<>title.id
      or viewing.updated_at is distinct from expected_viewing_at or viewing.rating is not null))
    or (viewing.id is null and expected_viewing_at is not null) then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return cinemarchive_private.outing_completion_current(jsonb_build_object('status','conflict','operationId',p_operation_id,
      'outingId',p_outing_id,'request',operations->0,'canonicalViewingId',canonical));
  end if;
  if viewing.id is not null then
    delete from public.viewings where id=viewing.id and user_id=me and outing_id=outing.id;
  end if;
  -- A later same-status write is still deliberate. Another completed trip or
  -- remaining viewing also prevents an old trip from rolling back the title.
  if title.status='watched' and completion.previous_status is not null
    and title.updated_at=completion.completed_title_version
    and not exists(select 1 from public.viewings v where v.user_id=me and v.title_id=title.id)
    and not exists(select 1 from public.cinema_outings o where o.user_id=me and o.title_id=title.id and o.id<>outing.id and o.status='completed') then
    if title.status is distinct from completion.previous_status then
      update public.titles set status=completion.previous_status where id=title.id and user_id=me returning * into title;
      restored := true;
    end if;
  end if;
  update public.cinema_outings set status='missed',completed_viewing_id=null
    where id=outing.id and user_id=me returning * into outing;
  v_result := jsonb_build_object('status','applied','operationId',p_operation_id,'outingId',outing.id,
    'request',operations->0,'canonicalViewingId',canonical,'titleStatusRestored',restored,
    'rows',jsonb_build_array(
      jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',outing.id),'row',to_jsonb(outing)),
      jsonb_build_object('table','titles','key',jsonb_build_object('id',title.id),'row',to_jsonb(title))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return cinemarchive_private.outing_completion_current(v_result);
end;
$$;
create function public.revert_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_expected_updated_at timestamptz,
  p_expected_viewing_id uuid,p_expected_viewing_updated_at timestamptz,
  p_expected_operation_id uuid default null,p_expected_viewing_operation_id uuid default null
) returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.revert_cinema_outing(p_outing_id,p_operation_id,p_expected_updated_at,p_expected_viewing_id,p_expected_viewing_updated_at,p_expected_operation_id,p_expected_viewing_operation_id);
$$;
revoke all on function public.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz,uuid,uuid),
  cinemarchive_private.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz,uuid,uuid) from public,anon;
grant execute on function public.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz,uuid,uuid),
  cinemarchive_private.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz,uuid,uuid) to authenticated;
