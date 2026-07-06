-- Tracks redeem-invite Edge Function attempts for lightweight rate limiting.
-- Service-role-only (same pattern as api_cache): RLS enabled with zero
-- policies denies all client access; only the Edge Function's service-role
-- key can read/write it.
create table invite_redeem_attempts (
  id           uuid primary key default gen_random_uuid(),
  ip_hash      text,
  email        text,
  attempted_at timestamptz not null default now()
);

create index invite_redeem_attempts_ip_hash_idx on invite_redeem_attempts(ip_hash, attempted_at);
create index invite_redeem_attempts_email_idx on invite_redeem_attempts(email, attempted_at);

alter table invite_redeem_attempts enable row level security;
