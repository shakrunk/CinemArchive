-- Third-party sync connections, secrets and provenance links (feat: Letterboxd/Simkl/Plex/Emby sync).

-- Third-party sync (Letterboxd, Simkl, Plex, Emby). Import-only by default:
-- `direction` flips to 'two_way' only after the user explicitly opts in to
-- sharing their activity back out. Deliberately NOT stored in user_prefs
-- (friends and share-token viewers can read that whole row).
--
-- integration_connections: owner-only, no friend/share policies. Holds nothing
-- secret, so the browser may read/write it.
-- integration_secrets: RLS on with NO policies — only the service role (Edge
-- Functions) can read or write it. Tokens never reach the browser.
-- external_title_links: provenance (provider id -> title) so re-syncs never
-- duplicate titles; viewings imported from a provider are deduped by date.

create type integration_provider as enum ('letterboxd', 'simkl', 'plex', 'emby');
create type integration_direction as enum ('import', 'two_way');

create table integration_connections (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references auth.users(id) on delete cascade,
  provider       integration_provider not null,
  direction      integration_direction not null default 'import',
  server_url     text,          -- Plex/Emby only
  account_label  text,          -- display name shown in the UI
  sync_cursor    text,          -- provider-specific incremental marker
  last_synced_at timestamptz,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),

  constraint integration_connections_unique_provider unique (user_id, provider)
);

alter table integration_connections enable row level security;

create policy "integration_connections: owner full access"
  on integration_connections for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create trigger integration_connections_updated_at before update on integration_connections
  for each row execute function update_updated_at();

create table integration_secrets (
  connection_id uuid primary key references integration_connections(id) on delete cascade,
  user_id       uuid not null references auth.users(id) on delete cascade,
  secrets       jsonb not null default '{}'::jsonb,  -- access/refresh tokens, expiry
  updated_at    timestamptz not null default now()
);

alter table integration_secrets enable row level security;  -- no policies: service role only

create table external_title_links (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  title_id      uuid not null references titles(id) on delete cascade,
  provider      integration_provider not null,
  external_id   text not null,
  created_at    timestamptz not null default now(),

  constraint external_title_links_unique unique (user_id, provider, external_id)
);

create index external_title_links_title_id_idx on external_title_links(title_id);

alter table external_title_links enable row level security;

create policy "external_title_links: owner full access"
  on external_title_links for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);
