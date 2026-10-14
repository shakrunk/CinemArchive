-- Guarded ticket operations and trusted causal revision evidence (20261008222209).
create function cinemarchive_private.library_causal_revision(
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
  select effect.value->'row'->>'updated_at' into revision
    from jsonb_array_elements(coalesce(receipt.result->'rows','[]'::jsonb)) with ordinality effect(value,ordinal)
    where effect.value->>'table'=p_table and effect.value->'key'=p_key
    order by effect.ordinal desc limit 1;
  if revision is null then raise exception 'The preceding change has no matching revision' using errcode='40001'; end if;
  return revision;
end;
$$;
-- Only the guarded entrypoints may opt into already-accepted replay. Never expose this helper.
revoke all on function cinemarchive_private.library_causal_revision(uuid,text,jsonb,boolean) from public,anon,authenticated;



create or replace function cinemarchive_private.apply_causal_library_command(
  p_operation_id uuid, p_operations jsonb
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid := auth.uid();
  v_op jsonb;
  v_expected text;
  v_accepted_replay boolean;
  v_operations jsonb[] := ARRAY[]::jsonb[];
begin
  if v_owner is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if jsonb_typeof(p_operations) is distinct from 'array'
    or jsonb_array_length(p_operations) not between 1 and 50000
    or octet_length(p_operations::text) > 16777216 then
    raise exception 'Invalid library command' using errcode='22023';
  end if;
  select exists(select 1 from cinemarchive_private.library_command_receipts
    where user_id=v_owner and operation_id=p_operation_id and result<>'{}'::jsonb) into v_accepted_replay;
  for v_op in select value from jsonb_array_elements(p_operations) loop
    if v_op ? 'expectedOperationId' then
      if v_op ? 'expectedUpdatedAt' or v_op->>'action' not in ('update','delete')
        or jsonb_typeof(v_op->'expectedOperationId') is distinct from 'string'
        or (v_op->>'expectedOperationId')::uuid = p_operation_id then
        raise exception 'Invalid causal revision precondition' using errcode='22023';
      end if;
      -- An accepted retry still passes the original resolved payload to the immutable receipt check.
      -- A changed payload fails there; no new write can bypass evidence validation this way.
      v_expected := cinemarchive_private.library_causal_revision(
        (v_op->>'expectedOperationId')::uuid,v_op->>'table',v_op->'key',v_accepted_replay);
      v_op := jsonb_set(v_op - 'expectedOperationId', '{expectedUpdatedAt}', to_jsonb(v_expected));
    end if;
    v_operations := array_append(v_operations,v_op);
  end loop;
  return cinemarchive_private.apply_library_command(p_operation_id,to_jsonb(v_operations));
end;
$$;

drop function public.finalize_ticket_attachment(uuid,uuid,uuid,uuid);
drop function public.detach_ticket_attachment(uuid,uuid,uuid);
drop function cinemarchive_private.mutate_ticket_attachment(uuid,uuid,uuid,uuid,boolean);


create or replace function cinemarchive_private.mutate_ticket_attachment(
  p_operation_id uuid,p_outing_id uuid,p_attachment_id uuid,p_expected_attachment_id uuid,p_detach boolean,
  p_expected_updated_at timestamptz default null,p_expected_operation_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  outing public.cinema_outings;
  attachment public.ticket_attachments;
  object_metadata jsonb;
  v_result jsonb;
  guarded boolean := p_expected_updated_at is not null or p_expected_operation_id is not null;
  expected_version timestamptz := p_expected_updated_at;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_operation_id is null or p_outing_id is null or p_detach is null or (not p_detach and p_attachment_id is null) or (p_detach and p_attachment_id is not null) then
    raise exception 'Invalid ticket command' using errcode='22023'; end if;
  if (p_expected_updated_at is not null and p_expected_operation_id is not null)
    or (p_expected_updated_at is not null and not isfinite(p_expected_updated_at))
    or p_expected_operation_id=p_operation_id then
    raise exception 'Invalid ticket outing revision guard' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind',case when p_detach then 'ticket.detach' else 'ticket.attach' end,
    'outingId',p_outing_id,'attachmentId',p_attachment_id,'expectedAttachmentId',p_expected_attachment_id));
  if guarded then
    operations := jsonb_set(operations,'{0,expectedUpdatedAt}',coalesce(to_jsonb(p_expected_updated_at),'null'::jsonb));
    operations := jsonb_set(operations,'{0,expectedOperationId}',coalesce(to_jsonb(p_expected_operation_id),'null'::jsonb));
  end if;
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations <> operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result <> '{}'::jsonb then return receipt.result; end if;
  if p_expected_operation_id is not null then
    expected_version := cinemarchive_private.library_causal_revision(p_expected_operation_id,'cinema_outings',jsonb_build_object('id',p_outing_id))::timestamptz;
  end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if not found then raise exception 'Outing ownership required' using errcode='42501'; end if;
  if guarded and outing.updated_at is distinct from expected_version then
    raise exception 'Outing changed on another device; review before saving the ticket' using errcode='40001'; end if;
  if outing.ticket_attachment_id is distinct from p_expected_attachment_id then
    raise exception 'Ticket changed on another device; review before replacing it' using errcode='40001'; end if;
  if not p_detach then
    select * into attachment from public.ticket_attachments where id=p_attachment_id for update;
    if not found or attachment.user_id <> me or attachment.outing_id <> p_outing_id then
      raise exception 'Ticket attachment ownership required' using errcode='42501'; end if;
    if attachment.state not in ('prepared','attached') then raise exception 'Ticket attachment is retired' using errcode='40001'; end if;
    select metadata into object_metadata from storage.objects
      where bucket_id='ticket-attachments' and name=attachment.object_key;
    if not found then raise exception 'Ticket upload is incomplete' using errcode='22023'; end if;
    if object_metadata->>'size' is distinct from attachment.byte_length::text
      or object_metadata->>'mimetype' is distinct from attachment.mime_type then
      raise exception 'Uploaded ticket does not match its metadata' using errcode='22023'; end if;
    update public.ticket_attachments set state='attached' where id=attachment.id;
  end if;
  if outing.ticket_attachment_id is not null and outing.ticket_attachment_id is distinct from p_attachment_id then
    update public.ticket_attachments set state='retired',retired_at=now() where id=outing.ticket_attachment_id;
  end if;
  update public.cinema_outings set ticket_attachment_managed=true,ticket_attachment_id=case when p_detach then null else p_attachment_id end
    where id=p_outing_id returning * into outing;
  v_result := jsonb_build_object('operationId',p_operation_id,'outingId',p_outing_id,
    'attachment',case when p_detach then null else cinemarchive_private.ticket_descriptor(attachment) end,
    'outingUpdatedAt',outing.updated_at,'request',operations->0,'outingRevisionGuarded',guarded,
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',p_outing_id),'row',to_jsonb(outing))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return v_result;
end;
$$;


create function public.finalize_ticket_attachment(
  p_operation_id uuid,p_outing_id uuid,p_attachment_id uuid,p_expected_attachment_id uuid,
  p_expected_updated_at timestamptz default null,p_expected_operation_id uuid default null
) returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.mutate_ticket_attachment(p_operation_id,p_outing_id,p_attachment_id,p_expected_attachment_id,false,p_expected_updated_at,p_expected_operation_id);
$$;
create function public.detach_ticket_attachment(
  p_operation_id uuid,p_outing_id uuid,p_expected_attachment_id uuid,
  p_expected_updated_at timestamptz default null,p_expected_operation_id uuid default null
) returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.mutate_ticket_attachment(p_operation_id,p_outing_id,null,p_expected_attachment_id,true,p_expected_updated_at,p_expected_operation_id);
$$;
revoke all on function public.finalize_ticket_attachment(uuid,uuid,uuid,uuid,timestamptz,uuid),
  public.detach_ticket_attachment(uuid,uuid,uuid,timestamptz,uuid),
  cinemarchive_private.mutate_ticket_attachment(uuid,uuid,uuid,uuid,boolean,timestamptz,uuid) from public,anon;
grant execute on function public.finalize_ticket_attachment(uuid,uuid,uuid,uuid,timestamptz,uuid),
  public.detach_ticket_attachment(uuid,uuid,uuid,timestamptz,uuid),
  cinemarchive_private.mutate_ticket_attachment(uuid,uuid,uuid,uuid,boolean,timestamptz,uuid) to authenticated;


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
    insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,canonical_viewing_version,completion_outing_version,completed_title_version,previous_status)
      values(me,outing.id,title.id,viewing.id,viewing.updated_at,outing.updated_at,title.updated_at,previous) returning * into completion;
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
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return cinemarchive_private.outing_completion_current(v_result);
end;
$$;

create or replace function cinemarchive_private.revert_cinema_outing(
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
    expected_outing_at := cinemarchive_private.library_causal_revision(p_expected_operation_id,'cinema_outings',jsonb_build_object('id',p_outing_id))::timestamptz;
  end if;
  if p_expected_viewing_operation_id is not null then
    expected_viewing_at := cinemarchive_private.library_causal_revision(p_expected_viewing_operation_id,'viewings',jsonb_build_object('id',p_expected_viewing_id))::timestamptz;
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
