-- CinemArchive Database Schema
-- Supabase/PostgreSQL

-- Enable UUID generation
create extension if not exists "pgcrypto";

-- ============================================================
-- TABLES
-- ============================================================

create type media_type as enum ('movie', 'tv');
create type watch_status as enum ('watched', 'watchlist', 'watching', 'dropped');

-- Core titles table (movies and TV series)
create table titles (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  tmdb_id       integer not null,
  type          media_type not null,
  title         text not null,
  year          integer not null,
  director      text,
  genres        text[] not null default '{}',
  poster_url    text,
  backdrop_url  text,
  synopsis      text,
  runtime       integer,            -- minutes (movies only)
  network       text,               -- TV network/streamer
  status        watch_status not null default 'watchlist',
  rating        numeric(3,1) check (rating >= 0 and rating <= 5),
  notes         text,
  tags          text[] not null default '{}',
  imdb_rating   numeric(3,1),
  rt_score      integer check (rt_score >= 0 and rt_score <= 100),
  metacritic_score integer check (metacritic_score >= 0 and metacritic_score <= 100),
  added_at      timestamptz not null default now(),
  updated_at    timestamptz not null default now(),

  constraint unique_user_tmdb unique (user_id, tmdb_id, type)
);

-- TV seasons (child of titles)
create table seasons (
  id                uuid primary key default gen_random_uuid(),
  title_id          uuid not null references titles(id) on delete cascade,
  user_id           uuid not null references auth.users(id) on delete cascade,
  season_number     integer not null,
  episode_count     integer not null default 0,
  episodes_watched  integer not null default 0 check (episodes_watched >= 0),
  air_year          integer,

  constraint seasons_episodes_valid check (episodes_watched <= episode_count),
  constraint unique_title_season unique (title_id, season_number)
);

-- Viewing history (re-watch timeline per title)
create table viewings (
  id          uuid primary key default gen_random_uuid(),
  title_id    uuid not null references titles(id) on delete cascade,
  user_id     uuid not null references auth.users(id) on delete cascade,
  viewed_at   date not null,
  rating      numeric(3,1) check (rating >= 0 and rating <= 5),
  notes       text,
  created_at  timestamptz not null default now()
);

-- Time-bound read-only access tokens for sharing
create table shared_access_keys (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null references auth.users(id) on delete cascade,
  token         text not null unique default encode(gen_random_bytes(32), 'hex'),
  label         text,               -- optional friendly name
  expires_at    timestamptz,        -- null = never expires
  is_active     boolean not null default true,
  created_at    timestamptz not null default now(),
  last_used_at  timestamptz
);

-- ============================================================
-- INDEXES
-- ============================================================

create index titles_user_id_idx on titles(user_id);
create index titles_type_idx on titles(type);
create index titles_status_idx on titles(status);
create index titles_year_idx on titles(year);
create index titles_added_at_idx on titles(added_at desc);
create index seasons_title_id_idx on seasons(title_id);
create index viewings_title_id_idx on viewings(title_id);
create index viewings_user_id_idx on viewings(user_id);
create index viewings_viewed_at_idx on viewings(viewed_at desc);
create index shared_keys_token_idx on shared_access_keys(token);
create index shared_keys_user_id_idx on shared_access_keys(user_id);

-- ============================================================
-- UPDATED_AT TRIGGER
-- ============================================================

create or replace function update_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create trigger titles_updated_at
  before update on titles
  for each row execute function update_updated_at();

-- ============================================================
-- ROW LEVEL SECURITY
-- ============================================================

alter table titles enable row level security;
alter table seasons enable row level security;
alter table viewings enable row level security;
alter table shared_access_keys enable row level security;

-- Helper function: validate a shared access token for a given user_id
create or replace function is_valid_shared_token(token_val text, owner_id uuid)
returns boolean language sql security definer as $$
  select exists (
    select 1 from shared_access_keys
    where token = token_val
      and user_id = owner_id
      and is_active = true
      and (expires_at is null or expires_at > now())
  );
$$;

-- Update last_used_at when a shared key is validated
create or replace function touch_shared_key(token_val text)
returns void language sql security definer as $$
  update shared_access_keys
  set last_used_at = now()
  where token = token_val;
$$;

-- -----------------------------------------------------------
-- TITLES policies
-- -----------------------------------------------------------

-- Authenticated owner: full CRUD
create policy "titles: owner full access"
  on titles for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

-- Shared key holder: read-only (token passed via request header/setting)
create policy "titles: shared key read"
  on titles for select
  using (
    is_valid_shared_token(
      current_setting('app.shared_token', true),
      user_id
    )
  );

-- -----------------------------------------------------------
-- SEASONS policies
-- -----------------------------------------------------------

create policy "seasons: owner full access"
  on seasons for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "seasons: shared key read"
  on seasons for select
  using (
    is_valid_shared_token(
      current_setting('app.shared_token', true),
      user_id
    )
  );

-- -----------------------------------------------------------
-- VIEWINGS policies
-- -----------------------------------------------------------

create policy "viewings: owner full access"
  on viewings for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "viewings: shared key read"
  on viewings for select
  using (
    is_valid_shared_token(
      current_setting('app.shared_token', true),
      user_id
    )
  );

-- -----------------------------------------------------------
-- SHARED_ACCESS_KEYS policies
-- -----------------------------------------------------------

-- Only the owner can manage their own keys
create policy "shared_keys: owner full access"
  on shared_access_keys for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);
