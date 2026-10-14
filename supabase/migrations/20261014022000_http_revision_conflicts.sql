-- PostgREST 14 retries SQLSTATE 40001 indefinitely, including deliberate CAS
-- rejections. Translate only at the HTTP boundary; private transaction rules,
-- immutable receipts, ownership checks and legacy SQL callers stay unchanged.
-- CREATE OR REPLACE retains each existing function's owner and EXECUTE grants.
create or replace function public.apply_library_command(p_operation_id uuid,p_operations jsonb)
returns jsonb language plpgsql security invoker set search_path = '' as $$
begin
  return cinemarchive_private.apply_causal_library_command(p_operation_id,p_operations);
exception when serialization_failure then
  raise sqlstate 'PT409' using message = SQLERRM;
end;
$$;

create or replace function public.complete_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_provisional_viewing_id uuid,
  p_expected_updated_at timestamptz,p_tz text default 'UTC',p_expected_operation_id uuid default null
) returns jsonb language plpgsql security invoker set search_path = '' as $$
begin
  return cinemarchive_private.complete_cinema_outing(p_outing_id,p_operation_id,p_provisional_viewing_id,p_expected_updated_at,p_tz,p_expected_operation_id);
exception when serialization_failure then
  raise sqlstate 'PT409' using message = SQLERRM;
end;
$$;

create or replace function public.revert_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_expected_updated_at timestamptz,
  p_expected_viewing_id uuid,p_expected_viewing_updated_at timestamptz,
  p_expected_operation_id uuid default null,p_expected_viewing_operation_id uuid default null
) returns jsonb language plpgsql security invoker set search_path = '' as $$
begin
  return cinemarchive_private.revert_cinema_outing(p_outing_id,p_operation_id,p_expected_updated_at,p_expected_viewing_id,p_expected_viewing_updated_at,p_expected_operation_id,p_expected_viewing_operation_id);
exception when serialization_failure then
  raise sqlstate 'PT409' using message = SQLERRM;
end;
$$;

create or replace function public.finalize_ticket_attachment(
  p_operation_id uuid,p_outing_id uuid,p_attachment_id uuid,p_expected_attachment_id uuid,
  p_expected_updated_at timestamptz default null,p_expected_operation_id uuid default null
) returns jsonb language plpgsql security invoker set search_path = '' as $$
begin
  return cinemarchive_private.mutate_ticket_attachment(p_operation_id,p_outing_id,p_attachment_id,p_expected_attachment_id,false,p_expected_updated_at,p_expected_operation_id);
exception when serialization_failure then
  raise sqlstate 'PT409' using message = SQLERRM;
end;
$$;

create or replace function public.detach_ticket_attachment(
  p_operation_id uuid,p_outing_id uuid,p_expected_attachment_id uuid,
  p_expected_updated_at timestamptz default null,p_expected_operation_id uuid default null
) returns jsonb language plpgsql security invoker set search_path = '' as $$
begin
  return cinemarchive_private.mutate_ticket_attachment(p_operation_id,p_outing_id,null,p_expected_attachment_id,true,p_expected_updated_at,p_expected_operation_id);
exception when serialization_failure then
  raise sqlstate 'PT409' using message = SQLERRM;
end;
$$;
