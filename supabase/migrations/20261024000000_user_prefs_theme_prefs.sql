-- Opt-in appearance sync: the theme a signed-in user picked, shared across
-- their browsers and devices (docs/android-contracts/theme-sync.md).
-- Shape: { palette, mode, pureBlack, updatedAt } in the cross-client
-- vocabulary; clients resolve conflicts last-write-wins on updatedAt, never on
-- the row's updated_at (which any column change bumps).
--
-- Not sensitive: the friend read policy exposes the whole row, but both
-- clients' friend and shared-view reads select ledger_layout only.
alter table public.user_prefs add column if not exists theme_prefs jsonb;

comment on column public.user_prefs.theme_prefs is
  'Synced appearance: { palette, mode, pureBlack, updatedAt }. Written only by devices that opted in.';
