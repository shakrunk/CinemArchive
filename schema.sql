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
  custom_watch_url  text,              -- owner override for "where to watch", shown preferentially in shared views
  collection_id     integer,           -- TMDB collection id (movies) — franchise grouping
  collection_name   text,              -- TMDB collection name, e.g. "The Lord of the Rings Collection"
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
  viewed_at   date,               -- null = watched before joining the platform (indeterminate date)
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
  watched_at  date,               -- null = watched before joining the platform (indeterminate date)
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
  episode_count   integer,
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
  episode_count   integer,
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

-- Wrapper exposing set_config via RPC (the pg_catalog builtin isn't
-- callable directly through PostgREST) so clients can set the
-- shared-token session setting that the "shared key read" policies check.
-- Also updates last_used_at on the matching key — this is the one RPC every
-- shared-link read actually calls, so it's the one place that can't be
-- forgotten (the previous touch_shared_key() required a second call site
-- that no client code ever made). $1 (positional) is used instead of the
-- bare parameter name because the parameter is named `token`, same as the
-- column being updated — referencing it by name would be ambiguous.
create or replace function set_shared_token(token text)
returns void language sql security definer as $$
  select set_config('app.shared_token', $1, false);
  update shared_access_keys
  set last_used_at = now()
  where shared_access_keys.token = $1
    and is_active = true;
$$;

-- Resolves a share link's owner independent of whether they own any titles
-- (fetchSharedLibrary previously derived this from the first returned title
-- row, which was null for an owner with zero titles).
create or replace function shared_key_owner(token_val text)
returns uuid language sql security definer stable as $$
  select user_id from shared_access_keys
  where token = token_val
    and is_active = true
    and (expires_at is null or expires_at > now())
  limit 1;
$$;

-- ============================================================
-- SHARE SCOPES — per-friend / per-link narrowing of library visibility
-- ============================================================
--
-- Absence of a row for a given link/friend means UNRESTRICTED — this is
-- opt-in narrowing only, never opt-in widening. Exercised by
-- scripts/verify-share-scope-logic.mjs.
create table share_scopes (
  id                uuid primary key default gen_random_uuid(),
  owner_user_id     uuid not null references auth.users(id) on delete cascade,
  shared_key_id     uuid references shared_access_keys(id) on delete cascade,
  friend_user_id    uuid references auth.users(id) on delete cascade,
  allowed_genres    text[],         -- null = all genres
  allowed_statuses  watch_status[], -- null = all statuses
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  constraint share_scopes_one_target check (
    (shared_key_id is not null and friend_user_id is null) or
    (shared_key_id is null and friend_user_id is not null)
  ),
  constraint share_scopes_unique_link unique (shared_key_id),
  constraint share_scopes_unique_friend unique (owner_user_id, friend_user_id)
);

create index share_scopes_owner_idx on share_scopes(owner_user_id);

alter table share_scopes enable row level security;

create policy "share_scopes: owner full access"
  on share_scopes for all
  using (auth.uid() = owner_user_id)
  with check (auth.uid() = owner_user_id);

create trigger share_scopes_updated_at
  before update on share_scopes
  for each row execute function update_updated_at();

-- Pure predicate, no table access — null on either side means "no
-- restriction on that dimension."
create or replace function title_in_scope(
  p_genres text[],
  p_status watch_status,
  p_allowed_genres text[],
  p_allowed_statuses watch_status[]
) returns boolean
language sql immutable as $$
  select (p_allowed_genres is null or p_genres && p_allowed_genres)
     and (p_allowed_statuses is null or p_status = any(p_allowed_statuses));
$$;

-- Single predicate replacing the separate is_valid_shared_token(...)/
-- is_friend(...) USING clauses that used to live across every shareable
-- content table. SECURITY DEFINER so it can read shared_access_keys/
-- share_scopes (owner-only RLS) and friendships regardless of the caller's
-- own visibility into those tables. Takes the title's columns as parameters
-- rather than a title_id + an internal re-select from `titles`: this
-- function is invoked from `titles`' own SELECT policy, and reading
-- `titles` again internally would be a self-referential RLS evaluation on
-- the very table whose policy calls it. Child tables join to titles once to
-- fetch genres/status instead (see their policies below).
create or replace function can_view_title(
  p_owner_user_id uuid,
  p_genres text[],
  p_status watch_status
) returns boolean
language sql security definer stable as $$
  select
    exists (
      select 1
      from shared_access_keys k
      left join share_scopes s on s.shared_key_id = k.id
      where k.token = current_setting('app.shared_token', true)
        and k.user_id = p_owner_user_id
        and k.is_active = true
        and (k.expires_at is null or k.expires_at > now())
        and title_in_scope(p_genres, p_status, s.allowed_genres, s.allowed_statuses)
    )
    or (
      is_friend(auth.uid(), p_owner_user_id)
      and title_in_scope(
        p_genres,
        p_status,
        (select allowed_genres from share_scopes where friend_user_id = auth.uid() and owner_user_id = p_owner_user_id),
        (select allowed_statuses from share_scopes where friend_user_id = auth.uid() and owner_user_id = p_owner_user_id)
      )
    );
$$;

-- -----------------------------------------------------------
-- TITLES policies
-- -----------------------------------------------------------

-- Authenticated owner: full CRUD
create policy "titles: owner full access"
  on titles for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

-- Shared-link visitor or accepted friend, subject to any share_scopes
-- narrowing (see can_view_title above). Replaces the former separate
-- "shared key read" / "friend read" policy pair.
create policy "titles: shared/friend read"
  on titles for select
  using (can_view_title(user_id, genres, status));

-- -----------------------------------------------------------
-- SEASONS policies
-- -----------------------------------------------------------

create policy "seasons: owner full access"
  on seasons for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "seasons: shared/friend read"
  on seasons for select
  using (
    exists (
      select 1 from titles t
      where t.id = seasons.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

-- -----------------------------------------------------------
-- VIEWINGS policies
-- -----------------------------------------------------------

create policy "viewings: owner full access"
  on viewings for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "viewings: shared/friend read"
  on viewings for select
  using (
    exists (
      select 1 from titles t
      where t.id = viewings.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

-- -----------------------------------------------------------
-- EPISODES / EPISODE_WATCH_EVENTS / EPISODE_RATINGS / EPISODE_REVIEWS policies
-- (same pattern as seasons: owner full access + shared/friend read)
-- -----------------------------------------------------------

create policy "episodes: owner full access"
  on episodes for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "episodes: shared/friend read"
  on episodes for select
  using (
    exists (
      select 1 from titles t
      where t.id = episodes.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

create policy "episode_watch_events: owner full access"
  on episode_watch_events for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "episode_watch_events: shared/friend read"
  on episode_watch_events for select
  using (
    exists (
      select 1 from episodes e
      join titles t on t.id = e.title_id
      where e.id = episode_watch_events.episode_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

create policy "episode_ratings: owner full access"
  on episode_ratings for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "episode_ratings: shared/friend read"
  on episode_ratings for select
  using (
    exists (
      select 1 from episodes e
      join titles t on t.id = e.title_id
      where e.id = episode_ratings.episode_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

create policy "episode_reviews: owner full access"
  on episode_reviews for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "episode_reviews: shared/friend read"
  on episode_reviews for select
  using (
    exists (
      select 1 from episodes e
      join titles t on t.id = e.title_id
      where e.id = episode_reviews.episode_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

-- -----------------------------------------------------------
-- TITLE_CAST / TITLE_CREW / SEASON_CAST / EPISODE_CREW policies
-- -----------------------------------------------------------

create policy "title_cast: owner full access"
  on title_cast for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "title_cast: shared/friend read"
  on title_cast for select
  using (
    exists (
      select 1 from titles t
      where t.id = title_cast.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

create policy "title_crew: owner full access"
  on title_crew for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "title_crew: shared/friend read"
  on title_crew for select
  using (
    exists (
      select 1 from titles t
      where t.id = title_crew.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

create policy "season_cast: owner full access"
  on season_cast for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "season_cast: shared/friend read"
  on season_cast for select
  using (
    exists (
      select 1 from titles t
      where t.id = season_cast.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

create policy "episode_crew: owner full access"
  on episode_crew for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "episode_crew: shared/friend read"
  on episode_crew for select
  using (
    exists (
      select 1 from titles t
      where t.id = episode_crew.title_id
        and can_view_title(t.user_id, t.genres, t.status)
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

-- ============================================================
-- PROFILES & FRIEND LOOKUP
-- ============================================================

-- Public-ish per-user profile row, auto-populated on signup. Lets a friend be
-- resolved by email (see find_user_by_email below) without exposing
-- auth.users — which is not client-queryable — or granting broad SELECT
-- access over other users' data.
create table profiles (
  user_id       uuid primary key references auth.users(id) on delete cascade,
  email         text not null,
  username      text unique,
  display_name  text,
  created_at    timestamptz not null default now(),
  -- Single source of truth for the "uncapped invite codes" exception — was
  -- previously three independently-hardcoded email literals (one RLS policy,
  -- two client files) that had already drifted out of sync once.
  is_owner      boolean not null default false
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
  insert into public.profiles (user_id, email, display_name, is_owner)
  values (new.id, new.email, split_part(new.email, '@', 1), lower(new.email) = 'denkrishna@gmail.com')
  on conflict (user_id) do nothing;
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function handle_new_user();

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

-- ============================================================
-- FRIENDSHIPS
-- ============================================================

-- Canonicalized pair (user_id_a < user_id_b) so each relationship has
-- exactly one row regardless of who acts on it. State machine:
--   (none)  -> pending   via send_friend_request
--   pending -> accepted  via accept_friend_request, or automatically if the
--              other party also sends a request (mutual request)
--   pending -> (removed) via decline_friend_request
--   any     -> blocked   via block_user (only blocked_by can act on it again)
--   blocked -> (removed) via unblock_user (blocked_by only); re-friending
--              requires a fresh send_friend_request afterward
create table friendships (
  user_id_a    uuid not null references auth.users(id) on delete cascade,
  user_id_b    uuid not null references auth.users(id) on delete cascade,
  requested_by uuid not null references auth.users(id) on delete cascade,
  status       text not null default 'pending' check (status in ('pending', 'accepted', 'blocked')),
  blocked_by   uuid references auth.users(id) on delete cascade,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  primary key (user_id_a, user_id_b),
  constraint friendships_ordered_pair check (user_id_a < user_id_b),
  constraint friendships_requested_by_is_party check (requested_by in (user_id_a, user_id_b)),
  constraint friendships_blocked_by_is_party check (blocked_by is null or blocked_by in (user_id_a, user_id_b))
);

create index friendships_user_id_a_idx on friendships(user_id_a);
create index friendships_user_id_b_idx on friendships(user_id_b);

alter table friendships enable row level security;

-- Either party can read the relationship row; all mutations go through the
-- SECURITY DEFINER functions below (mirrors the is_valid_shared_token /
-- find_user_by_email pattern) so state-machine transitions can't be
-- bypassed by a raw insert/update from the client.
create policy "friendships: parties can read"
  on friendships for select
  using (auth.uid() = user_id_a or auth.uid() = user_id_b);

-- Used by Phase 3 friend-read RLS policies to check accepted friendship.
create or replace function is_friend(user_a uuid, user_b uuid)
returns boolean
language sql security definer stable as $$
  select exists (
    select 1 from friendships
    where user_id_a = least(user_a, user_b)
      and user_id_b = greatest(user_a, user_b)
      and status = 'accepted'
  );
$$;

create or replace function send_friend_request(target_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, target_user_id);
  b uuid := greatest(me, target_user_id);
  existing friendships;
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;
  if me = target_user_id then
    raise exception 'Cannot send a friend request to yourself';
  end if;

  select * into existing from friendships where user_id_a = a and user_id_b = b;

  if existing is null then
    insert into friendships (user_id_a, user_id_b, requested_by, status)
    values (a, b, me, 'pending');
    return;
  end if;

  if existing.status = 'blocked' then
    raise exception 'Cannot send a friend request to this user';
  end if;

  if existing.status = 'accepted' or existing.requested_by = me then
    return; -- already friends, or already requested — no-op
  end if;

  -- The other party already requested us — mutual request accepts it.
  update friendships
  set status = 'accepted', updated_at = now()
  where user_id_a = a and user_id_b = b;
end;
$$;

create or replace function accept_friend_request(requester_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, requester_user_id);
  b uuid := greatest(me, requester_user_id);
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  update friendships
  set status = 'accepted', updated_at = now()
  where user_id_a = a and user_id_b = b
    and status = 'pending'
    and requested_by = requester_user_id;

  if not found then
    raise exception 'No pending friend request from this user';
  end if;
end;
$$;

create or replace function decline_friend_request(requester_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, requester_user_id);
  b uuid := greatest(me, requester_user_id);
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  delete from friendships
  where user_id_a = a and user_id_b = b
    and status = 'pending'
    and requested_by = requester_user_id;
end;
$$;

create or replace function block_user(target_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, target_user_id);
  b uuid := greatest(me, target_user_id);
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;
  if me = target_user_id then
    raise exception 'Cannot block yourself';
  end if;

  insert into friendships (user_id_a, user_id_b, requested_by, status, blocked_by)
  values (a, b, me, 'blocked', me)
  on conflict (user_id_a, user_id_b)
  do update set status = 'blocked', blocked_by = me, updated_at = now();
end;
$$;

-- Drops the relationship entirely (like decline_friend_request) rather than
-- reverting to 'pending'/'accepted' automatically — re-friending requires a
-- fresh send_friend_request from either party. Only the blocking party may act.
create or replace function unblock_user(target_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, target_user_id);
  b uuid := greatest(me, target_user_id);
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  delete from friendships
  where user_id_a = a and user_id_b = b
    and status = 'blocked'
    and blocked_by = me;

  if not found then
    raise exception 'No block from you on this user to remove';
  end if;
end;
$$;

-- Friend list for the current user, joined against profiles for display —
-- avoids the client needing separate profile lookups per row (profiles has
-- no broad SELECT policy).
create or replace function list_friendships()
returns table (
  friend_user_id uuid,
  status text,
  requested_by uuid,
  blocked_by uuid,
  created_at timestamptz,
  updated_at timestamptz,
  display_name text,
  username text
)
language sql security definer stable as $$
  select
    case when f.user_id_a = auth.uid() then f.user_id_b else f.user_id_a end,
    f.status,
    f.requested_by,
    f.blocked_by,
    f.created_at,
    f.updated_at,
    p.display_name,
    p.username
  from friendships f
  join profiles p
    on p.user_id = case when f.user_id_a = auth.uid() then f.user_id_b else f.user_id_a end
  where f.user_id_a = auth.uid() or f.user_id_b = auth.uid();
$$;

-- ============================================================
-- FRIEND LIBRARY READ ACCESS
-- ============================================================
--
-- Friend read access for titles/seasons/viewings/episodes/etc. is unified
-- with shared-link read access into the single "<table>: shared/friend
-- read" policy defined alongside each table's owner policy above (see
-- can_view_title, defined near the SHARE SCOPES section). This section
-- previously held 11 separate "X: friend read" policies before that
-- unification shipped.

-- ============================================================
-- USER PREFS (account-synced preferences, e.g. Ledger board layout)
-- ============================================================

-- Per-user app preferences that should follow the account across devices.
-- One row per user; columns are added as new preference groups need to sync
-- (the Ledger board layout is the first). Placed after is_friend so this
-- file stays runnable top-to-bottom.
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

-- ============================================================
-- RECOMMENDATIONS
-- ============================================================

-- A denormalized snapshot of a title sent from one friend to another. The
-- snapshot (title/year/poster) is captured at send time rather than joined
-- against the sender's `titles` row, so the recommendation stays legible
-- even if the sender later edits or removes it from their own library.
create table recommendations (
  id                uuid primary key default gen_random_uuid(),
  sender_user_id    uuid not null references auth.users(id) on delete cascade,
  recipient_user_id uuid not null references auth.users(id) on delete cascade,
  tmdb_id           integer not null,
  type              media_type not null,
  title             text not null,
  year              integer,
  poster_url        text,
  note              text,
  status            text not null default 'unread' check (status in ('unread', 'read', 'dismissed')),
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),

  constraint recommendations_not_to_self check (sender_user_id <> recipient_user_id)
);

create index recommendations_recipient_idx on recommendations(recipient_user_id);
create index recommendations_sender_idx on recommendations(sender_user_id);

-- Resending the same title to the same friend updates the existing row
-- (bumping it back to unread) instead of piling up duplicate inbox entries.
create unique index recommendations_unique_idx
  on recommendations(sender_user_id, recipient_user_id, tmdb_id, type);

alter table recommendations enable row level security;

-- Mutations only happen through the SECURITY DEFINER functions below (same
-- pattern as friendships) so a client can't forge a recommendation from
-- someone else or edit a snapshot after the fact.
create policy "recommendations: recipient can read"
  on recommendations for select
  using (auth.uid() = recipient_user_id);

create policy "recommendations: sender can read"
  on recommendations for select
  using (auth.uid() = sender_user_id);

create or replace function send_recommendation(
  recipient_id uuid,
  p_tmdb_id integer,
  p_type media_type,
  p_title text,
  p_year integer,
  p_poster_url text,
  p_note text default null
)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;
  if me = recipient_id then
    raise exception 'Cannot send a recommendation to yourself';
  end if;
  if not is_friend(me, recipient_id) then
    raise exception 'Can only send recommendations to accepted friends';
  end if;

  insert into recommendations (sender_user_id, recipient_user_id, tmdb_id, type, title, year, poster_url, note)
  values (me, recipient_id, p_tmdb_id, p_type, p_title, p_year, p_poster_url, nullif(trim(p_note), ''))
  on conflict (sender_user_id, recipient_user_id, tmdb_id, type)
  do update set title = excluded.title, year = excluded.year, poster_url = excluded.poster_url,
    note = excluded.note, status = 'unread', updated_at = now();
end;
$$;

create or replace function mark_recommendation_read(rec_id uuid)
returns void
language plpgsql security definer as $$
begin
  update recommendations
  set status = 'read', updated_at = now()
  where id = rec_id and recipient_user_id = auth.uid() and status = 'unread';
end;
$$;

create or replace function dismiss_recommendation(rec_id uuid)
returns void
language plpgsql security definer as $$
begin
  update recommendations
  set status = 'dismissed', updated_at = now()
  where id = rec_id and recipient_user_id = auth.uid();
end;
$$;

-- Inbox listing for the current user, joined against profiles for the
-- sender's display name — mirrors list_friendships().
create or replace function list_recommendations()
returns table (
  id uuid,
  sender_user_id uuid,
  sender_display_name text,
  sender_username text,
  tmdb_id integer,
  type media_type,
  title text,
  year integer,
  poster_url text,
  note text,
  status text,
  created_at timestamptz
)
language sql security definer stable as $$
  select r.id, r.sender_user_id, p.display_name, p.username, r.tmdb_id, r.type,
    r.title, r.year, r.poster_url, r.note, r.status, r.created_at
  from recommendations r
  join profiles p on p.user_id = r.sender_user_id
  where r.recipient_user_id = auth.uid()
  order by r.created_at desc;
$$;

-- ============================================================
-- TITLE COMMENTS & REACTIONS — friends-only social layer
-- ============================================================
--
-- Deliberately friends-only: no shared-key-read policy exists anywhere on
-- these tables, so an anonymous share-link session (no auth.uid()) can never
-- read or write here. Flat comments (no replies), fixed emoji reaction set.
--
-- Same pattern as `recommendations`: no client-facing insert/update/delete
-- policy at all — every mutation goes exclusively through a SECURITY
-- DEFINER RPC below, which validates ownership/friendship itself and then
-- bypasses RLS for the write. Reads likewise go through
-- list_title_comments/list_title_reactions rather than a SELECT policy.

create table title_comments (
  id          uuid primary key default gen_random_uuid(),
  title_id    uuid not null references titles(id) on delete cascade,
  author_id   uuid not null references auth.users(id) on delete cascade,
  body        text not null check (char_length(body) between 1 and 1000),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index title_comments_title_id_idx on title_comments(title_id, created_at);

alter table title_comments enable row level security;

create table title_reactions (
  title_id    uuid not null references titles(id) on delete cascade,
  author_id   uuid not null references auth.users(id) on delete cascade,
  emoji       text not null check (emoji in ('👍', '❤️', '😂', '😮')),
  created_at  timestamptz not null default now(),
  primary key (title_id, author_id) -- one reaction per user per title; changing emoji replaces it
);

create index title_reactions_title_id_idx on title_reactions(title_id);

alter table title_reactions enable row level security;

create trigger title_comments_updated_at
  before update on title_comments
  for each row execute function update_updated_at();

create or replace function add_title_comment(p_title_id uuid, p_body text)
returns title_comments
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  owner_id uuid;
  result title_comments;
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  select user_id into owner_id from titles where id = p_title_id;
  if owner_id is null then
    raise exception 'Title not found';
  end if;
  if owner_id <> me and not is_friend(me, owner_id) then
    raise exception 'Not authorized to comment on this title';
  end if;

  insert into title_comments (title_id, author_id, body)
  values (p_title_id, me, p_body)
  returning * into result;

  return result;
end;
$$;

create or replace function delete_title_comment(p_comment_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  delete from title_comments c
  where c.id = p_comment_id
    and (
      c.author_id = me
      or exists (select 1 from titles t where t.id = c.title_id and t.user_id = me)
    );

  if not found then
    raise exception 'Comment not found or not authorized to delete it';
  end if;
end;
$$;

-- Upsert-or-delete-on-null: single call to add, change, or remove a reaction.
create or replace function set_title_reaction(p_title_id uuid, p_emoji text)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  owner_id uuid;
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  select user_id into owner_id from titles where id = p_title_id;
  if owner_id is null then
    raise exception 'Title not found';
  end if;

  if p_emoji is null then
    delete from title_reactions where title_id = p_title_id and author_id = me;
    return;
  end if;

  if owner_id <> me and not is_friend(me, owner_id) then
    raise exception 'Not authorized to react to this title';
  end if;

  insert into title_reactions (title_id, author_id, emoji)
  values (p_title_id, me, p_emoji)
  on conflict (title_id, author_id) do update set emoji = p_emoji, created_at = now();
end;
$$;

-- Joined against profiles for display, same shape as list_friendships().
-- Callable by owner or friend (checked here, not via a SELECT policy).
create or replace function list_title_comments(p_title_id uuid)
returns table (
  id uuid,
  author_id uuid,
  body text,
  created_at timestamptz,
  display_name text,
  username text
)
language plpgsql security definer stable as $$
declare
  me uuid := auth.uid();
  owner_id uuid;
begin
  select user_id into owner_id from titles where id = p_title_id;
  if owner_id is null or (owner_id <> me and not is_friend(me, owner_id)) then
    return;
  end if;

  return query
    select c.id, c.author_id, c.body, c.created_at, p.display_name, p.username
    from title_comments c
    join profiles p on p.user_id = c.author_id
    where c.title_id = p_title_id
    order by c.created_at asc;
end;
$$;

create or replace function list_title_reactions(p_title_id uuid)
returns table (
  author_id uuid,
  emoji text,
  display_name text,
  username text
)
language plpgsql security definer stable as $$
declare
  me uuid := auth.uid();
  owner_id uuid;
begin
  select user_id into owner_id from titles where id = p_title_id;
  if owner_id is null or (owner_id <> me and not is_friend(me, owner_id)) then
    return;
  end if;

  return query
    select r.author_id, r.emoji, p.display_name, p.username
    from title_reactions r
    join profiles p on p.user_id = r.author_id
    where r.title_id = p_title_id;
end;
$$;

-- ============================================================
-- FRIEND ACTIVITY FEED
-- ============================================================

-- Merges four activity kinds across the caller's accepted friends: titles
-- added, viewings logged, comments added, and reactions added. Runs as
-- SECURITY DEFINER (bypassing RLS on titles/viewings/profiles/title_comments/
-- title_reactions) and instead filters explicitly via
-- can_view_title(t.user_id, t.genres, t.status) per branch — the same
-- predicate the shared/friend-read RLS policies use, aggregated across every
-- friend at once instead of scoped to a single one, and (unlike the plain
-- is_friend() check this replaced) share_scopes-aware: a friend scoped to
-- e.g. Horror-only no longer sees feed entries about titles outside their
-- granted scope. The comment/reaction branches additionally exclude the
-- caller's own actions, matching how the first two branches already only
-- ever show friend-authored events (your own titles never match
-- is_friend(auth.uid(), auth.uid()) = false).
--
-- Keyset-paginated via p_before/p_limit (capped at 50) rather than a flat
-- limit, so the client can "Load more" instead of only ever seeing the
-- latest 50 events across all friends combined.
create or replace function friend_activity_feed(p_before timestamptz default null, p_limit integer default 30)
returns table (
  event_type text,
  event_at timestamptz,
  friend_user_id uuid,
  friend_display_name text,
  friend_username text,
  title_id uuid,
  tmdb_id integer,
  type media_type,
  title text,
  year integer,
  poster_url text,
  rating numeric(3,1)
)
language sql security definer stable as $$
  select * from (
    select
      'title_added' as event_type,
      t.added_at as event_at,
      t.user_id as friend_user_id,
      p.display_name as friend_display_name,
      p.username as friend_username,
      t.id as title_id,
      t.tmdb_id,
      t.type,
      t.title,
      t.year,
      t.poster_url,
      null::numeric(3,1) as rating
    from titles t
    join profiles p on p.user_id = t.user_id
    where can_view_title(t.user_id, t.genres, t.status)

    union all

    select
      'viewing_logged' as event_type,
      v.created_at as event_at,
      t.user_id as friend_user_id,
      p.display_name as friend_display_name,
      p.username as friend_username,
      t.id as title_id,
      t.tmdb_id,
      t.type,
      t.title,
      t.year,
      t.poster_url,
      v.rating
    from viewings v
    join titles t on t.id = v.title_id
    join profiles p on p.user_id = t.user_id
    where can_view_title(t.user_id, t.genres, t.status)

    union all

    select
      'comment_added' as event_type,
      c.created_at as event_at,
      c.author_id as friend_user_id,
      p.display_name as friend_display_name,
      p.username as friend_username,
      t.id as title_id,
      t.tmdb_id,
      t.type,
      t.title,
      t.year,
      t.poster_url,
      null::numeric(3,1) as rating
    from title_comments c
    join titles t on t.id = c.title_id
    join profiles p on p.user_id = c.author_id
    where can_view_title(t.user_id, t.genres, t.status)
      and c.author_id <> auth.uid()

    union all

    select
      'reaction_added' as event_type,
      r.created_at as event_at,
      r.author_id as friend_user_id,
      p.display_name as friend_display_name,
      p.username as friend_username,
      t.id as title_id,
      t.tmdb_id,
      t.type,
      t.title,
      t.year,
      t.poster_url,
      null::numeric(3,1) as rating
    from title_reactions r
    join titles t on t.id = r.title_id
    join profiles p on p.user_id = r.author_id
    where can_view_title(t.user_id, t.genres, t.status)
      and r.author_id <> auth.uid()
  ) feed
  where p_before is null or event_at < p_before
  order by event_at desc
  limit least(coalesce(p_limit, 30), 50);
$$;

-- ============================================================
-- INVITE CODES (invite-only signup)
-- ============================================================

-- An account is only created when a valid, unredeemed invite code is
-- presented to the redeem-invite Edge Function. Regular sign-in
-- (supabase.auth.signInWithOtp with shouldCreateUser: false, see
-- src/lib/auth.ts) fails for any email that isn't already a known user.
--
-- Each account may generate at most 2 invite codes, ever — except the owner
-- account (profiles.is_owner), which is uncapped.
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
      (select is_owner from profiles where user_id = auth.uid()) = true
      or (select count(*) from invite_codes where created_by = auth.uid()) < 2
    )
  );

create policy "invite_codes: owner can delete own unredeemed"
  on invite_codes for delete
  using (auth.uid() = created_by and redeemed_by is null);

-- No update policy: redemption is written exclusively by the redeem-invite
-- Edge Function using the service role key, which bypasses RLS.

-- Rate-limiting log for the redeem-invite Edge Function. Service-role-only
-- (same pattern as api_cache): RLS enabled with zero policies denies all
-- client access.
create table invite_redeem_attempts (
  id           uuid primary key default gen_random_uuid(),
  ip_hash      text,
  email        text,
  attempted_at timestamptz not null default now()
);

create index invite_redeem_attempts_ip_hash_idx on invite_redeem_attempts(ip_hash, attempted_at);
create index invite_redeem_attempts_email_idx on invite_redeem_attempts(email, attempted_at);

alter table invite_redeem_attempts enable row level security;
