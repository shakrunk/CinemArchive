-- Suggested friends via invite lineage (KP-026): surface the people connected
-- to the current user through an invite code — either they redeemed a code the
-- user created, or the user redeemed theirs — who aren't already in any
-- friendship state (pending/accepted/blocked) with them. SECURITY DEFINER for
-- the same reason as find_user_by_email: the row visibility needed here spans
-- both parties' invite_codes rows and other users' profiles.

create or replace function list_invite_connections()
returns table (
  user_id uuid,
  username text,
  display_name text,
  connection text  -- 'invited_by_you' | 'invited_you'
)
language sql security definer stable as $$
  select p.user_id, p.username, p.display_name, c.connection
  from (
    select ic.redeemed_by as other_user, 'invited_by_you'::text as connection
    from invite_codes ic
    where ic.created_by = auth.uid() and ic.redeemed_by is not null
    union
    select ic.created_by as other_user, 'invited_you'::text as connection
    from invite_codes ic
    where ic.redeemed_by = auth.uid()
  ) c
  join profiles p on p.user_id = c.other_user
  where c.other_user <> auth.uid()
    and not exists (
      select 1 from friendships f
      where f.user_id_a = least(auth.uid(), c.other_user)
        and f.user_id_b = greatest(auth.uid(), c.other_user)
    );
$$;
