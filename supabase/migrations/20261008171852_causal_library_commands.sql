-- Causal library commands (20261008171852)
-- A queued patch may depend on the immutable result of an earlier command.
-- Resolve that receipt's revision inside the same transaction as the write,
-- without changing the client's persisted payload after an unknown outcome.
create or replace function cinemarchive_private.apply_causal_library_command(
  p_operation_id uuid, p_operations jsonb
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid := auth.uid();
  v_op jsonb;
  v_expected text;
  v_operations jsonb := '[]'::jsonb;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if jsonb_typeof(p_operations) is distinct from 'array'
    or jsonb_array_length(p_operations) not between 1 and 2048
    or octet_length(p_operations::text) > 2097152 then
    raise exception 'Invalid library command' using errcode='22023';
  end if;
  for v_op in select value from jsonb_array_elements(p_operations) loop
    if v_op ? 'expectedOperationId' then
      if v_op ? 'expectedUpdatedAt' or v_op->>'action' not in ('update','delete')
        or jsonb_typeof(v_op->'expectedOperationId') is distinct from 'string'
        or (v_op->>'expectedOperationId')::uuid = p_operation_id then
        raise exception 'Invalid causal revision precondition' using errcode='22023';
      end if;
      v_expected := null;
      -- The last effect on this exact row is authoritative when a compound
      -- command touches it more than once. Another owner's receipt is invisible.
      select effect.value->'row'->>'updated_at' into v_expected
      from cinemarchive_private.library_command_receipts receipt
      cross join lateral jsonb_array_elements(receipt.result->'rows') with ordinality effect(value, ordinal)
      where receipt.user_id=v_owner
        and receipt.operation_id=(v_op->>'expectedOperationId')::uuid
        and effect.value->>'table'=v_op->>'table'
        and effect.value->'key'=v_op->'key'
      order by effect.ordinal desc limit 1;
      if v_expected is null then
        raise exception 'The preceding library change has no matching revision' using errcode='40001';
      end if;
      v_op := jsonb_set(v_op - 'expectedOperationId', '{expectedUpdatedAt}', to_jsonb(v_expected));
    end if;
    v_operations := v_operations || jsonb_build_array(v_op);
  end loop;
  return cinemarchive_private.apply_library_command(p_operation_id,v_operations);
end;
$$;
revoke all on function cinemarchive_private.apply_causal_library_command(uuid,jsonb) from public, anon;
grant execute on function cinemarchive_private.apply_causal_library_command(uuid,jsonb) to authenticated;

create or replace function public.apply_library_command(p_operation_id uuid,p_operations jsonb)
returns jsonb language sql security invoker set search_path = ''
as $$ select cinemarchive_private.apply_causal_library_command(p_operation_id,p_operations); $$;
revoke all on function public.apply_library_command(uuid,jsonb) from public, anon;
grant execute on function public.apply_library_command(uuid,jsonb) to authenticated;
