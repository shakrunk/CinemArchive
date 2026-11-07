-- Owner-private moviegoing preferences that follow the account across devices
-- (docs/android-contracts/moviegoing-prefs.md): the spend budget today, with room for
-- the theater subscription plan and default region as those features land.
--
-- Deliberately NOT a column on user_prefs: that table's friend and shared-key read
-- policies expose the whole row, and a spending budget is not something to show friends.
-- This table has an owner policy only, so no friend, shared-token or anonymous read exists.
--
-- Shape of `prefs`: { budget?: { amount, period }, updatedAt, ... }. Clients resolve
-- conflicts last-write-wins on prefs.updatedAt, never on the row's updated_at (which any
-- write bumps), and ignore keys they do not know so a newer client's additions survive.
create table public.moviegoing_prefs (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  prefs      jsonb not null default '{}'::jsonb
             check (jsonb_typeof(prefs) = 'object' and pg_column_size(prefs) <= 8192),
  updated_at timestamptz not null default now()
);

alter table public.moviegoing_prefs enable row level security;

create policy "moviegoing_prefs: owner full access"
  on public.moviegoing_prefs for all
  using  ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

revoke all on public.moviegoing_prefs from public, anon;
grant select, insert, update, delete on public.moviegoing_prefs to authenticated;
grant all on public.moviegoing_prefs to service_role;

create trigger moviegoing_prefs_updated_at
  before update on public.moviegoing_prefs
  for each row execute function public.update_updated_at();
