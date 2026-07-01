-- Public-ish per-user profile row, auto-populated on signup. Lets a friend be
-- resolved by email (see find_user_by_email below) without exposing
-- auth.users — which is not client-queryable — or granting broad SELECT
-- access over other users' data.
create table profiles (
  user_id       uuid primary key references auth.users(id) on delete cascade,
  email         text not null,
  username      text unique,
  display_name  text,
  created_at    timestamptz not null default now()
);

create index profiles_email_idx on profiles(lower(email));

alter table profiles enable row level security;

-- Owner-only: no broad SELECT policy, so other users can't browse this
-- table directly. Friend lookup goes through find_user_by_email instead.
create policy "profiles: owner full access"
  on profiles for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

-- Auto-create a profile row whenever a new auth.users row is inserted.
create or replace function handle_new_user()
returns trigger language plpgsql security definer as $$
begin
  insert into public.profiles (user_id, email, display_name)
  values (new.id, new.email, split_part(new.email, '@', 1))
  on conflict (user_id) do nothing;
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function handle_new_user();

-- Backfill profiles for accounts that already existed before this migration.
insert into public.profiles (user_id, email, display_name)
select id, email, split_part(email, '@', 1)
from auth.users
on conflict (user_id) do nothing;

-- Resolve a friend's email to a user_id + display info without exposing the
-- profiles table (or auth.users) to broad client SELECT access. Exact match
-- only — no partial/prefix search surface, to keep enumeration limited to
-- "does this exact email have an account" rather than a directory browse.
-- Excludes the caller's own row (you can't friend-request yourself).
create or replace function find_user_by_email(lookup_email text)
returns table(user_id uuid, username text, display_name text)
language sql security definer stable as $$
  select p.user_id, p.username, p.display_name
  from profiles p
  where lower(p.email) = lower(trim(lookup_email))
    and p.user_id <> auth.uid()
  limit 1;
$$;
