-- Ticket bytes live in a private Storage bucket. Immutable metadata and explicit
-- association commands prevent ordinary outing edits from replaying old photos.
create table public.ticket_attachments (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  outing_id uuid not null,
  object_key text not null unique,
  mime_type text not null check (mime_type in ('image/jpeg','image/png','image/webp')),
  byte_length bigint not null check (byte_length between 1 and 20971520),
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  barcode jsonb,
  state text not null default 'prepared' check (state in ('prepared','attached','retired','deleting','deleted')),
  created_at timestamptz not null default now(),
  last_prepared_at timestamptz not null default now(),
  retired_at timestamptz,
  cleanup_claimed_at timestamptz,
  check (object_key = user_id::text || '/' || id::text || '/original')
);
create index ticket_attachments_owner_idx on public.ticket_attachments(user_id, outing_id);
create index ticket_attachments_cleanup_idx on public.ticket_attachments(state, retired_at, created_at);
alter table public.ticket_attachments enable row level security;
revoke all on public.ticket_attachments from public, anon, authenticated;
grant select on public.ticket_attachments to authenticated;
create policy "ticket attachments: owner read" on public.ticket_attachments
  for select to authenticated using (user_id = (select auth.uid()));

alter table public.cinema_outings add column ticket_attachment_id uuid references public.ticket_attachments(id);
alter table public.cinema_outings add column ticket_attachment_managed boolean not null default false;
alter table public.cinema_outings add constraint managed_ticket_reference check (ticket_attachment_id is null or ticket_attachment_managed);
create unique index cinema_outings_ticket_attachment_idx on public.cinema_outings(ticket_attachment_id)
  where ticket_attachment_id is not null;

create function cinemarchive_private.ticket_descriptor(a public.ticket_attachments)
returns jsonb language sql immutable security invoker set search_path = '' as $$
  select jsonb_build_object('id',a.id,'objectKey',a.object_key,'mimeType',a.mime_type,
    'byteLength',a.byte_length,'sha256',a.sha256,'barcode',a.barcode);
$$;
revoke all on function cinemarchive_private.ticket_descriptor(public.ticket_attachments) from public, anon, authenticated;

create function cinemarchive_private.guard_ticket_association()
returns trigger language plpgsql security invoker set search_path = '' as $$
begin
  if (tg_op = 'INSERT' and (new.ticket_attachment_id is not null or new.ticket_attachment_managed)) or
     (tg_op = 'UPDATE' and (new.ticket_attachment_id is distinct from old.ticket_attachment_id or new.ticket_attachment_managed is distinct from old.ticket_attachment_managed)) then
    -- A session variable would be spoofable. Only the table owner's definer
    -- commands (or an administrator acting as that owner) may change this field.
    if current_user <> pg_get_userbyid((select relowner from pg_class where oid='public.ticket_attachments'::regclass)) then
      raise exception 'Use the ticket attachment API' using errcode='42501';
    end if;
    if new.ticket_attachment_id is not null and not exists (
      select 1 from public.ticket_attachments a where a.id=new.ticket_attachment_id
        and a.user_id=new.user_id and a.outing_id=new.id and a.state='attached'
    ) then raise exception 'Ticket attachment ownership required' using errcode='42501'; end if;
  end if;
  return new;
end;
$$;
revoke all on function cinemarchive_private.guard_ticket_association() from public, anon, authenticated;
create trigger guard_ticket_association before insert or update of ticket_attachment_id,ticket_attachment_managed on public.cinema_outings
  for each row execute function cinemarchive_private.guard_ticket_association();

create function cinemarchive_private.retire_outing_tickets()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  update public.ticket_attachments set state='retired', retired_at=coalesce(retired_at,now())
    where outing_id=old.id and user_id=old.user_id and state in ('prepared','attached');
  return old;
end;
$$;
revoke all on function cinemarchive_private.retire_outing_tickets() from public, anon, authenticated;
create trigger retire_outing_tickets before delete on public.cinema_outings
  for each row execute function cinemarchive_private.retire_outing_tickets();

create function cinemarchive_private.prepare_ticket_attachment(p_attachment_id uuid,p_outing_id uuid,p_metadata jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  a public.ticket_attachments;
  descriptor jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_attachment_id is null or p_outing_id is null or jsonb_typeof(p_metadata) is distinct from 'object'
    or exists(select 1 from jsonb_object_keys(p_metadata) k where k not in ('mimeType','byteLength','sha256','barcode'))
    or not (p_metadata ?& array['mimeType','byteLength','sha256','barcode'])
    or jsonb_typeof(p_metadata->'mimeType') is distinct from 'string'
    or p_metadata->>'mimeType' not in ('image/jpeg','image/png','image/webp')
    or jsonb_typeof(p_metadata->'byteLength') is distinct from 'number'
    or (p_metadata->>'byteLength')::numeric not between 1 and 20971520
    or trunc((p_metadata->>'byteLength')::numeric) <> (p_metadata->>'byteLength')::numeric
    or jsonb_typeof(p_metadata->'sha256') is distinct from 'string'
    or p_metadata->>'sha256' !~ '^[0-9a-f]{64}$'
  then raise exception 'Invalid ticket metadata' using errcode='22023'; end if;
  if p_metadata->'barcode' <> 'null'::jsonb then
    if jsonb_typeof(p_metadata->'barcode') is distinct from 'object'
      or not (p_metadata->'barcode' ?& array['payload','format'])
      or exists(select 1 from jsonb_object_keys(p_metadata->'barcode') k where k not in ('payload','format'))
      or jsonb_typeof(p_metadata->'barcode'->'payload') is distinct from 'string'
      or octet_length(p_metadata->'barcode'->>'payload') not between 1 and 32768
      or jsonb_typeof(p_metadata->'barcode'->'format') is distinct from 'string'
      or p_metadata->'barcode'->>'format' not in ('QR_CODE','CODE_128','PDF_417','AZTEC','ITF','CODABAR','OTHER')
    then raise exception 'Invalid ticket barcode' using errcode='22023'; end if;
  end if;
  -- Lock the outing first, matching finalize and deletion ordering.
  perform 1 from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if not found then raise exception 'Outing ownership required' using errcode='42501'; end if;
  insert into public.ticket_attachments(id,user_id,outing_id,object_key,mime_type,byte_length,sha256,barcode)
    values(p_attachment_id,me,p_outing_id,me::text||'/'||p_attachment_id::text||'/original',
      p_metadata->>'mimeType',(p_metadata->>'byteLength')::bigint,p_metadata->>'sha256',nullif(p_metadata->'barcode','null'::jsonb))
    on conflict do nothing;
  select * into a from public.ticket_attachments where id=p_attachment_id for update;
  if a.user_id is distinct from me then raise exception 'Ticket attachment ownership required' using errcode='42501'; end if;
  descriptor := cinemarchive_private.ticket_descriptor(a);
  if a.outing_id <> p_outing_id or descriptor - 'id' - 'objectKey' <> p_metadata then
    raise exception 'Attachment identifier already used for different content' using errcode='22023';
  end if;
  if a.state='prepared' then
    update public.ticket_attachments set last_prepared_at=now() where id=a.id;
  end if;
  return jsonb_build_object('attachment',descriptor,'state',case when a.state in ('deleting','deleted') then 'retired' else a.state end);
end;
$$;

create function cinemarchive_private.get_ticket_command_receipt(p_operation_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare r cinemarchive_private.library_command_receipts;
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
  select * into r from cinemarchive_private.library_command_receipts where user_id=auth.uid() and operation_id=p_operation_id;
  if not found then return null; end if;
  if r.operations->0->>'kind' not in ('ticket.attach','ticket.detach') or not (r.operations->0 ? 'kind') then
    raise exception 'Operation identifier belongs to another command' using errcode='22023';
  end if;
  return r.result;
end;
$$;

create function cinemarchive_private.mutate_ticket_attachment(
  p_operation_id uuid,p_outing_id uuid,p_attachment_id uuid,p_expected_attachment_id uuid,p_detach boolean
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  outing public.cinema_outings;
  attachment public.ticket_attachments;
  object_metadata jsonb;
  v_result jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_operation_id is null or p_outing_id is null or p_detach is null or (not p_detach and p_attachment_id is null) or (p_detach and p_attachment_id is not null) then
    raise exception 'Invalid ticket command' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind',case when p_detach then 'ticket.detach' else 'ticket.attach' end,
    'outingId',p_outing_id,'attachmentId',p_attachment_id,'expectedAttachmentId',p_expected_attachment_id));
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations <> operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result <> '{}'::jsonb then return receipt.result; end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if not found then raise exception 'Outing ownership required' using errcode='42501'; end if;
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
    'outingUpdatedAt',outing.updated_at,'request',operations->0,
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',p_outing_id),'row',to_jsonb(outing))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return v_result;
end;
$$;

create function public.prepare_ticket_attachment(p_attachment_id uuid,p_outing_id uuid,p_metadata jsonb)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.prepare_ticket_attachment(p_attachment_id,p_outing_id,p_metadata);
$$;
create function public.get_ticket_command_receipt(p_operation_id uuid)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.get_ticket_command_receipt(p_operation_id);
$$;
create function public.finalize_ticket_attachment(p_operation_id uuid,p_outing_id uuid,p_attachment_id uuid,p_expected_attachment_id uuid)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.mutate_ticket_attachment(p_operation_id,p_outing_id,p_attachment_id,p_expected_attachment_id,false);
$$;
create function public.detach_ticket_attachment(p_operation_id uuid,p_outing_id uuid,p_expected_attachment_id uuid)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.mutate_ticket_attachment(p_operation_id,p_outing_id,null,p_expected_attachment_id,true);
$$;
revoke all on function cinemarchive_private.prepare_ticket_attachment(uuid,uuid,jsonb),
  cinemarchive_private.get_ticket_command_receipt(uuid),cinemarchive_private.mutate_ticket_attachment(uuid,uuid,uuid,uuid,boolean),
  public.prepare_ticket_attachment(uuid,uuid,jsonb),public.get_ticket_command_receipt(uuid),
  public.finalize_ticket_attachment(uuid,uuid,uuid,uuid),public.detach_ticket_attachment(uuid,uuid,uuid) from public,anon;
grant execute on function cinemarchive_private.prepare_ticket_attachment(uuid,uuid,jsonb),
  cinemarchive_private.get_ticket_command_receipt(uuid),cinemarchive_private.mutate_ticket_attachment(uuid,uuid,uuid,uuid,boolean),
  public.prepare_ticket_attachment(uuid,uuid,jsonb),public.get_ticket_command_receipt(uuid),
  public.finalize_ticket_attachment(uuid,uuid,uuid,uuid),public.detach_ticket_attachment(uuid,uuid,uuid) to authenticated;

create function cinemarchive_private.can_upload_ticket_object(p_name text)
returns boolean language plpgsql volatile security definer set search_path = '' as $$
declare permitted boolean;
begin
  if auth.uid() is null then return false; end if;
  -- Hold a metadata lock through the Storage insertion transaction so cleanup
  -- cannot finish just before a delayed upload inserts its object metadata.
  select state='prepared' into permitted from public.ticket_attachments
    where object_key=p_name and user_id=auth.uid() for share;
  return coalesce(permitted,false);
end;
$$;
revoke all on function cinemarchive_private.can_upload_ticket_object(text) from public,anon;
grant execute on function cinemarchive_private.can_upload_ticket_object(text) to authenticated;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
  values('ticket-attachments','ticket-attachments',false,20971520,array['image/jpeg','image/png','image/webp'])
  on conflict(id) do update set public=false,file_size_limit=excluded.file_size_limit,allowed_mime_types=excluded.allowed_mime_types;
create policy "ticket bytes: prepared owner upload" on storage.objects for insert to authenticated with check (
  bucket_id='ticket-attachments' and cinemarchive_private.can_upload_ticket_object(name));
create policy "ticket bytes: owner read" on storage.objects for select to authenticated using (
  bucket_id='ticket-attachments' and exists(select 1 from public.ticket_attachments a
    where a.user_id=(select auth.uid()) and a.object_key=name and a.state in ('prepared','attached','retired')));
-- No UPDATE/DELETE policy: immutable uploads never overwrite existing bytes.
-- Retirement cleanup must claim metadata first and remove bytes through Storage API.

-- Restrictive fences prevent an unrelated pre-existing permissive bucket policy
-- from granting access to this private namespace.
create policy "ticket bytes: anonymous fence" on storage.objects as restrictive for all to anon
  using (bucket_id <> 'ticket-attachments') with check (bucket_id <> 'ticket-attachments');
create policy "ticket bytes: upload fence" on storage.objects as restrictive for insert to authenticated with check (
  bucket_id <> 'ticket-attachments' or cinemarchive_private.can_upload_ticket_object(name));
create policy "ticket bytes: read fence" on storage.objects as restrictive for select to authenticated using (
  bucket_id <> 'ticket-attachments' or exists(select 1 from public.ticket_attachments a
    where a.user_id=(select auth.uid()) and a.object_key=name and a.state in ('prepared','attached','retired')));
create policy "ticket bytes: overwrite fence" on storage.objects as restrictive for update to authenticated
  using (bucket_id <> 'ticket-attachments') with check (bucket_id <> 'ticket-attachments');
create policy "ticket bytes: deletion fence" on storage.objects as restrictive for delete to authenticated
  using (bucket_id <> 'ticket-attachments');

create function cinemarchive_private.get_outing_ticket_attachments()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
  return (select coalesce(jsonb_agg(jsonb_build_object('outingId',o.id,
      'managed',true,'attachment',case when a.id is null then null else cinemarchive_private.ticket_descriptor(a) end) order by o.id),'[]'::jsonb)
    from public.cinema_outings o left join public.ticket_attachments a on a.id=o.ticket_attachment_id and a.user_id=auth.uid() and a.state='attached'
    where o.user_id=auth.uid() and o.ticket_attachment_managed);
end;
$$;
create function public.get_outing_ticket_attachments()
returns jsonb language sql stable security invoker set search_path = '' as $$
  select cinemarchive_private.get_outing_ticket_attachments();
$$;
revoke all on function public.get_outing_ticket_attachments(),cinemarchive_private.get_outing_ticket_attachments() from public,anon;
grant execute on function public.get_outing_ticket_attachments(),cinemarchive_private.get_outing_ticket_attachments() to authenticated;

-- Service-only cleanup claims cannot race a finalize: both lock the metadata.
-- Metadata tombstones remain after deleting bytes so an attachment UUID can
-- never be recycled for different content. The original command receipt remains.
grant usage on schema cinemarchive_private to service_role;
create function cinemarchive_private.claim_ticket_attachment_cleanup(p_limit integer)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a public.ticket_attachments; results jsonb := '[]'::jsonb;
begin
  for a in select t.* from public.ticket_attachments t where
    ((t.state='retired' and t.retired_at < now()-interval '7 days')
      or (t.state='prepared' and t.last_prepared_at < now()-interval '30 days')
      or (t.state='deleting' and t.cleanup_claimed_at < now()-interval '1 hour'))
    and not exists(select 1 from public.cinema_outings o where o.ticket_attachment_id=t.id)
    order by t.created_at limit greatest(1,least(coalesce(p_limit,100),100)) for update skip locked
  loop
    update public.ticket_attachments set state='deleting',cleanup_claimed_at=now() where id=a.id;
    results := results || jsonb_build_array(jsonb_build_object('attachmentId',a.id,'objectKey',a.object_key));
  end loop;
  return results;
end;
$$;
create function cinemarchive_private.finish_ticket_attachment_cleanup(p_attachment_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare a public.ticket_attachments;
begin
  select * into a from public.ticket_attachments where id=p_attachment_id for update;
  if not found or a.state='deleted' then return; end if;
  if a.state <> 'deleting' or exists(select 1 from public.cinema_outings where ticket_attachment_id=a.id) then
    raise exception 'Ticket cleanup was not claimed' using errcode='40001'; end if;
  if exists(select 1 from storage.objects where bucket_id='ticket-attachments' and name=a.object_key) then
    raise exception 'Remove ticket bytes through Storage API first' using errcode='22023'; end if;
  update public.ticket_attachments set state='deleted' where id=a.id;
end;
$$;
create function public.claim_ticket_attachment_cleanup(p_limit integer default 100)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.claim_ticket_attachment_cleanup(p_limit);
$$;
create function public.finish_ticket_attachment_cleanup(p_attachment_id uuid)
returns void language sql security invoker set search_path = '' as $$
  select cinemarchive_private.finish_ticket_attachment_cleanup(p_attachment_id);
$$;
revoke all on function public.claim_ticket_attachment_cleanup(integer),public.finish_ticket_attachment_cleanup(uuid),
  cinemarchive_private.claim_ticket_attachment_cleanup(integer),cinemarchive_private.finish_ticket_attachment_cleanup(uuid) from public,anon,authenticated;
grant execute on function public.claim_ticket_attachment_cleanup(integer),public.finish_ticket_attachment_cleanup(uuid),
  cinemarchive_private.claim_ticket_attachment_cleanup(integer),cinemarchive_private.finish_ticket_attachment_cleanup(uuid) to service_role;
