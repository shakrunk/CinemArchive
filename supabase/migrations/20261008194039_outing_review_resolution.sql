-- Saved outing review resolution (20261008194039)
-- Explicit, version-checked selection from retained legacy intent. Never creates a plan.
create function cinemarchive_private.resolve_outing_fields(
  p_outing_id uuid, p_expected_updated_at timestamptz, p_patch jsonb, p_operation_id uuid
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  outing public.cinema_outings;
  changed public.cinema_outings;
  item record;
  value jsonb;
  v_result jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_outing_id is null or p_operation_id is null or p_expected_updated_at is null
    or not isfinite(p_expected_updated_at) or p_patch is null or jsonb_typeof(p_patch)<>'object'
    or octet_length(p_patch::text)>1048576 then
    raise exception 'Invalid outing review command' using errcode='22023'; end if;
  for item in select * from jsonb_each(p_patch) loop
    if item.key in ('venue','format','seat','auditorium','seat_row','booking_ref','notes') then
      if jsonb_typeof(item.value) not in ('string','null') then
        raise exception 'Expected text or null for %',item.key using errcode='22023'; end if;
    elsif item.key='showtime' then
      if jsonb_typeof(item.value)<>'string' or not isfinite((item.value#>>'{}')::timestamptz) then
        raise exception 'Expected a finite showtime' using errcode='22023'; end if;
    elsif item.key in ('previews_minutes','runtime_minutes') then
      if jsonb_typeof(item.value)<>'number' or (item.value#>>'{}')::numeric<>trunc((item.value#>>'{}')::numeric) then
        raise exception 'Expected whole minutes' using errcode='22023'; end if;
    elsif item.key='ticket_price' then
      if jsonb_typeof(item.value) not in ('number','null') then
        raise exception 'Expected a numeric price or null' using errcode='22023'; end if;
    elsif item.key='seats' then
      if jsonb_typeof(item.value)<>'array' then raise exception 'Expected seat array' using errcode='22023'; end if;
      if exists(select 1 from jsonb_array_elements(item.value) x where jsonb_typeof(x)<>'string') then
        raise exception 'Expected seat strings' using errcode='22023'; end if;
    elsif item.key='companions' then
      if jsonb_typeof(item.value)<>'array' then raise exception 'Expected companion array' using errcode='22023'; end if;
      for value in select * from jsonb_array_elements(item.value) loop
        if jsonb_typeof(value)<>'object' or not value ? 'name' or jsonb_typeof(value->'name')<>'string'
          or length(btrim(value->>'name'))=0 then
          raise exception 'Expected named companions' using errcode='22023'; end if;
        if exists(select 1 from jsonb_object_keys(value) k where k not in ('name','friendUserId')) then
          raise exception 'Unsupported companion fields' using errcode='22023'; end if;
        if value ? 'friendUserId' and (jsonb_typeof(value->'friendUserId')<>'string'
          or value->>'friendUserId' !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') then
          raise exception 'Invalid companion identity' using errcode='22023'; end if;
      end loop;
    else raise exception 'Unsupported outing review field: %',item.key using errcode='22023';
    end if;
  end loop;
  operations := jsonb_build_array(jsonb_build_object('kind','outing.resolve','outingId',p_outing_id,
    'expectedUpdatedAt',p_expected_updated_at,'patch',p_patch));
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations<>operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result<>'{}'::jsonb then return receipt.result; end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if not found or outing.updated_at<>p_expected_updated_at then
    v_result := jsonb_build_object('status',case when outing.id is null then 'missing' else 'conflict' end,
      'operationId',p_operation_id,'request',operations->0,'outing',case when outing.id is null then null else to_jsonb(outing) end);
    -- Only applied results are receipts. A refreshed comparison starts a new operation.
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return v_result;
  end if;
  changed := jsonb_populate_record(outing,p_patch);
  if p_patch ?| array['showtime','previews_minutes','runtime_minutes'] then
    changed.ends_at := changed.showtime + make_interval(mins=>changed.previews_minutes) + make_interval(mins=>changed.runtime_minutes);
  end if;
  if to_jsonb(changed)<>to_jsonb(outing) then
    update public.cinema_outings set
      showtime=changed.showtime,previews_minutes=changed.previews_minutes,runtime_minutes=changed.runtime_minutes,ends_at=changed.ends_at,
      venue=changed.venue,format=changed.format,ticket_price=changed.ticket_price,seat=changed.seat,
      auditorium=changed.auditorium,seat_row=changed.seat_row,seats=changed.seats,
      booking_ref=changed.booking_ref,notes=changed.notes,companions=changed.companions
      where id=p_outing_id and user_id=me returning * into outing;
  end if;
  v_result := jsonb_build_object('status','applied','operationId',p_operation_id,'request',operations->0,'outing',to_jsonb(outing),
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',p_outing_id),'row',to_jsonb(outing))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return v_result;
end;
$$;
create function public.resolve_outing_fields(
  p_outing_id uuid,p_expected_updated_at timestamptz,p_patch jsonb,p_operation_id uuid
) returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.resolve_outing_fields(p_outing_id,p_expected_updated_at,p_patch,p_operation_id);
$$;
revoke all on function public.resolve_outing_fields(uuid,timestamptz,jsonb,uuid),
  cinemarchive_private.resolve_outing_fields(uuid,timestamptz,jsonb,uuid) from public,anon;
grant execute on function public.resolve_outing_fields(uuid,timestamptz,jsonb,uuid),
  cinemarchive_private.resolve_outing_fields(uuid,timestamptz,jsonb,uuid) to authenticated;
