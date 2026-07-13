-- Allow the requester to withdraw an outgoing friend request.
-- The recipient uses decline_friend_request; both transitions remove the
-- pending relationship so either person can send a fresh request later.

create or replace function cancel_friend_request(recipient_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, recipient_user_id);
  b uuid := greatest(me, recipient_user_id);
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;
  if me = recipient_user_id then
    raise exception 'Cannot cancel a friend request to yourself';
  end if;

  delete from friendships
  where user_id_a = a and user_id_b = b
    and status = 'pending'
    and requested_by = me;

  if not found then
    raise exception 'No pending friend request to this user';
  end if;
end;
$$;
