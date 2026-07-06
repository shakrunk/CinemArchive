-- block_user() had no counterpart — once blocked, a relationship was stuck
-- forever (noted as a known gap in the friendships table comment). Unblocking
-- drops the row entirely (like decline_friend_request) rather than reverting
-- to 'pending'/'accepted' automatically — re-friending requires a fresh
-- send_friend_request from either party.
create or replace function unblock_user(target_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, target_user_id);
  b uuid := greatest(me, target_user_id);
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  delete from friendships
  where user_id_a = a and user_id_b = b
    and status = 'blocked'
    and blocked_by = me;

  if not found then
    raise exception 'No block from you on this user to remove';
  end if;
end;
$$;
