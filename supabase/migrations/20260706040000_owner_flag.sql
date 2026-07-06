-- The uncapped-invite exception was checked against auth.email() in one RLS
-- policy and duplicated as a literal string constant in client code — the
-- exact class of bug 20260706000000_fix_invite_owner_email.sql already had to
-- fix once (the two copies drifted out of sync). Collapse to one source of
-- truth: a flag on profiles, set once at signup, read everywhere else.
alter table profiles add column is_owner boolean not null default false;

update profiles set is_owner = true where lower(email) = 'denkrishna@gmail.com';

create or replace function handle_new_user()
returns trigger language plpgsql security definer as $$
begin
  insert into public.profiles (user_id, email, display_name, is_owner)
  values (new.id, new.email, split_part(new.email, '@', 1), lower(new.email) = 'denkrishna@gmail.com')
  on conflict (user_id) do nothing;
  return new;
end;
$$;

drop policy "invite_codes: capped insert" on invite_codes;

create policy "invite_codes: capped insert"
  on invite_codes for insert
  with check (
    auth.uid() = created_by
    and (
      (select is_owner from profiles where user_id = auth.uid()) = true
      or (select count(*) from invite_codes where created_by = auth.uid()) < 2
    )
  );
