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
  studios       text[] not null default '{}',
  release_date  date,                  -- actual release/first-air date (drives Up Next when in the future)
  original_language text,              -- ISO 639-1 code, e.g. "en"
  content_rating    text,              -- age certification, e.g. "PG-13", "TV-MA"
  imdb_id           text,              -- e.g. "tt1375666" — enables an exact IMDb link
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

-- Episodes (child of seasons, identified by title + season + episode number)
create table episodes (
  id              uuid primary key default gen_random_uuid(),
  title_id        uuid not null references titles(id) on delete cascade,
  user_id         uuid not null references auth.users(id) on delete cascade,
  season_number   integer not null,
  episode_number  integer not null,
  episode_name    text,
  air_date        date,
  runtime         integer,
  synopsis        text,
  still_url       text,

  constraint unique_episode unique (title_id, season_number, episode_number)
);

-- Episode watch events — independent timeline entry, not tied to a rating
create table episode_watch_events (
  id          uuid primary key default gen_random_uuid(),
  episode_id  uuid not null references episodes(id) on delete cascade,
  user_id     uuid not null references auth.users(id) on delete cascade,
  watched_at  date not null,
  notes       text,
  color_mode  text check (color_mode in ('bw', 'color')),
  created_at  timestamptz not null default now()
);

-- Episode ratings — standalone historical log, timestamped when recorded
create table episode_ratings (
  id          uuid primary key default gen_random_uuid(),
  episode_id  uuid not null references episodes(id) on delete cascade,
  user_id     uuid not null references auth.users(id) on delete cascade,
  rating      numeric(3,1) not null check (rating >= 0 and rating <= 5),
  rated_at    timestamptz not null default now()  -- when the user records it, not when watched
);

-- Episode reviews — standalone historical log, timestamped when recorded
create table episode_reviews (
  id           uuid primary key default gen_random_uuid(),
  episode_id   uuid not null references episodes(id) on delete cascade,
  user_id      uuid not null references auth.users(id) on delete cascade,
  review_text  text not null,
  color_mode   text check (color_mode in ('bw', 'color')),
  reviewed_at  timestamptz not null default now()  -- independent of any watch event
);

-- Series/movie-level cast (top 10 by TMDB order)
create table title_cast (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  title_id        uuid not null references titles(id) on delete cascade,
  tmdb_person_id  integer not null,
  name            text not null,
  character_name  text,
  profile_url     text,
  cast_order      integer not null default 0,
  created_at      timestamptz default now(),
  constraint unique_title_cast unique (title_id, tmdb_person_id)
);

-- Series/movie-level crew
create table title_crew (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  title_id        uuid not null references titles(id) on delete cascade,
  tmdb_person_id  integer not null,
  name            text not null,
  job             text not null,
  department      text,
  profile_url     text,
  created_at      timestamptz default now(),
  constraint unique_title_crew unique (title_id, tmdb_person_id, job)
);

-- Season-level cast (regulars/guests billed in that season)
create table season_cast (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  title_id        uuid not null references titles(id) on delete cascade,
  season_id       uuid not null references seasons(id) on delete cascade,
  tmdb_person_id  integer not null,
  name            text not null,
  character_name  text,
  profile_url     text,
  cast_order      integer not null default 0,
  created_at      timestamptz default now(),
  constraint unique_season_cast unique (season_id, tmdb_person_id)
);

-- Per-episode crew: Director and Writer(s)
create table episode_crew (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  title_id        uuid not null references titles(id) on delete cascade,
  episode_id      uuid not null references episodes(id) on delete cascade,
  tmdb_person_id  integer not null,
  name            text not null,
  job             text not null,
  created_at      timestamptz default now(),
  constraint unique_episode_crew unique (episode_id, tmdb_person_id, job)
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
create index seasons_user_id_idx on seasons(user_id);
create index viewings_title_id_idx on viewings(title_id);
create index viewings_user_id_idx on viewings(user_id);
create index viewings_viewed_at_idx on viewings(viewed_at desc);
create index shared_keys_token_idx on shared_access_keys(token);
create index shared_keys_user_id_idx on shared_access_keys(user_id);
create index episodes_title_id_idx on episodes(title_id);
create index episodes_user_id_idx on episodes(user_id);
create index ep_watch_events_episode_id_idx on episode_watch_events(episode_id);
create index ep_watch_events_user_id_idx on episode_watch_events(user_id);
create index ep_ratings_episode_id_idx on episode_ratings(episode_id);
create index ep_ratings_user_id_idx on episode_ratings(user_id);
create index ep_reviews_episode_id_idx on episode_reviews(episode_id);
create index ep_reviews_user_id_idx on episode_reviews(user_id);
create index title_cast_title_id_idx   on title_cast(title_id);
create index title_cast_person_id_idx  on title_cast(tmdb_person_id);
create index title_crew_title_id_idx   on title_crew(title_id);
create index title_crew_person_id_idx  on title_crew(tmdb_person_id);
create index season_cast_season_id_idx on season_cast(season_id);
create index season_cast_person_id_idx on season_cast(tmdb_person_id);
create index ep_crew_episode_id_idx    on episode_crew(episode_id);
create index ep_crew_person_id_idx     on episode_crew(tmdb_person_id);

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
alter table episodes enable row level security;
alter table episode_watch_events enable row level security;
alter table episode_ratings enable row level security;
alter table episode_reviews enable row level security;
alter table title_cast   enable row level security;
alter table title_crew   enable row level security;
alter table season_cast  enable row level security;
alter table episode_crew enable row level security;

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

-- Wrapper exposing set_config via RPC (the pg_catalog builtin isn't
-- callable directly through PostgREST) so clients can set the
-- shared-token session setting that the "shared key read" policies check.
create or replace function set_shared_token(token text)
returns void language sql security definer as $$
  select set_config('app.shared_token', token, false)
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
-- EPISODES / EPISODE_WATCH_EVENTS / EPISODE_RATINGS / EPISODE_REVIEWS policies
-- (same pattern as seasons: owner full access + shared key read)
-- -----------------------------------------------------------

create policy "episodes: owner full access"
  on episodes for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "episodes: shared key read"
  on episodes for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

create policy "episode_watch_events: owner full access"
  on episode_watch_events for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "episode_watch_events: shared key read"
  on episode_watch_events for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

create policy "episode_ratings: owner full access"
  on episode_ratings for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "episode_ratings: shared key read"
  on episode_ratings for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

create policy "episode_reviews: owner full access"
  on episode_reviews for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "episode_reviews: shared key read"
  on episode_reviews for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

-- -----------------------------------------------------------
-- TITLE_CAST / TITLE_CREW / SEASON_CAST / EPISODE_CREW policies
-- -----------------------------------------------------------

create policy "title_cast: owner full access"
  on title_cast for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "title_cast: shared key read"
  on title_cast for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

create policy "title_crew: owner full access"
  on title_crew for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "title_crew: shared key read"
  on title_crew for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

create policy "season_cast: owner full access"
  on season_cast for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "season_cast: shared key read"
  on season_cast for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

create policy "episode_crew: owner full access"
  on episode_crew for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "episode_crew: shared key read"
  on episode_crew for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

-- -----------------------------------------------------------
-- SHARED_ACCESS_KEYS policies
-- -----------------------------------------------------------

-- Only the owner can manage their own keys
create policy "shared_keys: owner full access"
  on shared_access_keys for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

-- ============================================================
-- USER TITLE PINS (easter egg pin storage)
-- ============================================================

create table user_title_pins (
  user_id        uuid not null references auth.users on delete cascade,
  title_id       uuid not null references titles(id) on delete cascade,
  easter_egg_key text not null,
  pinned_variant text check (pinned_variant in ('bw', 'color')),
  updated_at     timestamptz not null default now(),
  primary key (user_id, title_id, easter_egg_key)
);

alter table user_title_pins enable row level security;

create policy "user_title_pins: owner full access"
  on user_title_pins for all
  using  (auth.uid() = user_id)
  with check (auth.uid() = user_id);

-- ============================================================
-- API CACHE (used by media-proxy Edge Function)
-- ============================================================

create table if not exists api_cache (
  cache_key   text primary key,
  response    jsonb not null,
  expires_at  timestamptz not null
);

alter table api_cache enable row level security;

create index if not exists api_cache_expires_at_idx on api_cache(expires_at);


