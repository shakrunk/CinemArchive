-- A preserved current title is not a causal effect of outing reversal (20261008223218).
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
