-- Per-user app preferences that should follow the account across devices.
-- One row per user; columns are added as new preference groups need to sync
-- (the Ledger board layout is the first).
create table user_prefs (
  user_id       uuid primary key references auth.users(id) on delete cascade,
  ledger_layout jsonb,  -- LedgerWidget[]: { id, panel, width, settings? }
  updated_at    timestamptz not null default now()
);

alter table user_prefs enable row level security;

create policy "user_prefs: owner full access"
  on user_prefs for all
  using  (auth.uid() = user_id)
  with check (auth.uid() = user_id);

-- Shared-token and friend viewers may READ the owner's prefs so their Ledger
-- renders with the owner's board arrangement (mirrors the titles policies).
-- NOTE: these read policies expose the whole row — don't add sensitive
-- columns to this table without revisiting them.
create policy "user_prefs: shared key read"
  on user_prefs for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

create policy "user_prefs: friend read"
  on user_prefs for select
  using (is_friend(auth.uid(), user_id));

create trigger user_prefs_updated_at
  before update on user_prefs
  for each row execute function update_updated_at();
