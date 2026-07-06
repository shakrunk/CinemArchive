-- The uncapped-invite-codes exception was created for the wrong email
-- (bioengineerkk@gmail.com instead of the actual owner account,
-- denkrishna@gmail.com). Recreate the insert policy with the correct email.
drop policy "invite_codes: capped insert" on invite_codes;

create policy "invite_codes: capped insert"
  on invite_codes for insert
  with check (
    auth.uid() = created_by
    and (
      auth.email() = 'denkrishna@gmail.com'
      or (select count(*) from invite_codes where created_by = auth.uid()) < 2
    )
  );
