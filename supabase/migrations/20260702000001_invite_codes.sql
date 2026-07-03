-- Invite-only signup: an account is only created when a valid, unredeemed
-- invite code is presented to the redeem-invite Edge Function. Regular
-- sign-in (supabase.auth.signInWithOtp with shouldCreateUser: false, see
-- src/lib/auth.ts) fails for any email that isn't already a known user.
--
-- Each account may generate at most 2 invite codes, ever — except the owner
-- account (auth.email() = 'bioengineerkk@gmail.com'), which is uncapped.
create table invite_codes (
  id           uuid primary key default gen_random_uuid(),
  code         text not null unique,
  created_by   uuid not null references auth.users(id) on delete cascade,
  created_at   timestamptz not null default now(),
  redeemed_by  uuid references auth.users(id) on delete set null,
  redeemed_at  timestamptz
);

create index invite_codes_created_by_idx on invite_codes(created_by);

alter table invite_codes enable row level security;

create policy "invite_codes: owner can view own"
  on invite_codes for select
  using (auth.uid() = created_by);

create policy "invite_codes: capped insert"
  on invite_codes for insert
  with check (
    auth.uid() = created_by
    and (
      auth.email() = 'bioengineerkk@gmail.com'
      or (select count(*) from invite_codes where created_by = auth.uid()) < 2
    )
  );

create policy "invite_codes: owner can delete own unredeemed"
  on invite_codes for delete
  using (auth.uid() = created_by and redeemed_by is null);

-- No update policy: redemption is written exclusively by the redeem-invite
-- Edge Function using the service role key, which bypasses RLS.
