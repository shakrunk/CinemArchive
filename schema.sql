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
  rt_url            text,              -- Rotten Tomatoes page URL, resolved via Wikidata (P1258) from imdb_id
  awards_count      integer,           -- count of "award received" (P166) statements on Wikidata; sparse outside high-profile titles
  bechdel_outcome   text check (bechdel_outcome in ('pass', 'fail')),  -- Wikidata P5021/P9259 assessment outcome
  bechdel_score     text,              -- optional "x/3" breakdown (Wikidata P444 qualifier), mainly present on fails
  custom_watch_url  text,              -- owner override for "where to watch", shown preferentially in shared views
  in_home_collection boolean not null default false,  -- owned locally — surfaces a "Home Collection" source in Where to Watch
  physical_media    jsonb not null default '[]'::jsonb,  -- cataloged physical copies: [{ id, format, edition?, notes? }]
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
-- that no client code ever made) — and notifies the owner (see NOTIFICATIONS
-- section below), at most once per hour per key so repeat page loads by the
-- same visitor don't spam their inbox. Uses a local v_token variable rather
-- than referencing the `token` parameter by name in the same statement as
-- the `token` column, which would be ambiguous.
create or replace function set_shared_token(token text)
returns void
language plpgsql security definer as $$
declare
  v_token text := token;
  key shared_access_keys;
begin
  perform set_config('app.shared_token', v_token, false);

  select * into key from shared_access_keys k where k.token = v_token and k.is_active = true;
  if key.id is null then
    return;
  end if;

  if key.last_used_at is null or key.last_used_at < now() - interval '1 hour' then
    insert into notifications (recipient_id, type, payload)
    values (key.user_id, 'share_link_used', jsonb_build_object('label', key.label));
  end if;

  update shared_access_keys k set last_used_at = now() where k.id = key.id;
end;
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
  using (case when user_id = (select auth.uid()) then false else
    can_view_title(user_id, genres, status)
  end);

-- -----------------------------------------------------------
-- SEASONS policies
-- -----------------------------------------------------------

create policy "seasons: owner full access"
  on seasons for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "seasons: shared/friend read"
  on seasons for select
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from titles t
      where t.id = seasons.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  end);

-- -----------------------------------------------------------
-- VIEWINGS policies
-- -----------------------------------------------------------

create policy "viewings: owner full access"
  on viewings for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "viewings: shared/friend read"
  on viewings for select
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from titles t
      where t.id = viewings.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  end);

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
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from titles t
      where t.id = episodes.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  end);

create policy "episode_watch_events: owner full access"
  on episode_watch_events for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "episode_watch_events: shared/friend read"
  on episode_watch_events for select
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from episodes e
      join titles t on t.id = e.title_id
      where e.id = episode_watch_events.episode_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  end);

create policy "episode_ratings: owner full access"
  on episode_ratings for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "episode_ratings: shared/friend read"
  on episode_ratings for select
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from episodes e
      join titles t on t.id = e.title_id
      where e.id = episode_ratings.episode_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  end);

create policy "episode_reviews: owner full access"
  on episode_reviews for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create policy "episode_reviews: shared/friend read"
  on episode_reviews for select
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from episodes e
      join titles t on t.id = e.title_id
      where e.id = episode_reviews.episode_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  end);

-- -----------------------------------------------------------
-- TITLE_CAST / TITLE_CREW / SEASON_CAST / EPISODE_CREW policies
-- -----------------------------------------------------------

create policy "title_cast: owner full access"
  on title_cast for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "title_cast: shared/friend read"
  on title_cast for select
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from titles t
      where t.id = title_cast.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  end);

create policy "title_crew: owner full access"
  on title_crew for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "title_crew: shared/friend read"
  on title_crew for select
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from titles t
      where t.id = title_crew.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  end);

create policy "season_cast: owner full access"
  on season_cast for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "season_cast: shared/friend read"
  on season_cast for select
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from titles t
      where t.id = season_cast.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  end);

create policy "episode_crew: owner full access"
  on episode_crew for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "episode_crew: shared/friend read"
  on episode_crew for select
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from titles t
      where t.id = episode_crew.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  end);

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
-- LISTS (user-created custom title lists)
-- ============================================================
--
-- Private-only for v1 — owner-only RLS, no sharing. A title can belong to many lists
-- and a list can hold many titles (many-to-many via list_items), fully additive to
-- titles.status (the watchlist/watched/watching/dropped enum). Naming deliberately
-- "list"/"list_items", not "collection" (already TMDB franchise grouping and
-- in_home_collection/physical_media) and not "watchlist" (already a titles.status
-- value).
--
-- list_items uses a surrogate `id uuid` primary key, not a composite key like
-- user_title_pins, because unlike user_title_pins it must be synced: record_tombstone()
-- needs a single old.id column, and Android's outbox contract is id-keyed upsert
-- throughout. Duplicate membership is instead prevented by an explicit unique
-- constraint. list_items also carries a redundant user_id (like seasons/episodes do
-- despite their own FK chain) because record_tombstone() reads old.user_id directly.
--
-- list_items.position is nullable and unused in v1 (UI orders by added_at) — reserved
-- for a future manual-reorder feature so it never needs a schema migration or forced
-- Android resync-from-epoch later.

create table lists (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  name        text not null,
  description text,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);

create index lists_user_id_idx on lists(user_id);

alter table lists enable row level security;

create policy "lists: owner full access"
  on lists for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

create table list_items (
  id         uuid primary key default gen_random_uuid(),
  list_id    uuid not null references lists(id) on delete cascade,
  title_id   uuid not null references titles(id) on delete cascade,
  user_id    uuid not null references auth.users(id) on delete cascade,
  position   integer,                        -- reserved for a future manual-reorder
                                              -- feature; v1 always writes null
  added_at   timestamptz not null default now(),
  updated_at timestamptz not null default now(),

  constraint list_items_unique_membership unique (list_id, title_id)
);

create index list_items_list_id_idx on list_items(list_id);
create index list_items_title_id_idx on list_items(title_id);

alter table list_items enable row level security;

create policy "list_items: owner full access"
  on list_items for all
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

-- ============================================================
-- THIRD-PARTY SYNC (Letterboxd, Simkl, Plex, Emby)
-- ============================================================

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
--   pending -> (removed) via decline_friend_request or cancel_friend_request
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
    insert into notifications (recipient_id, type, actor_id)
    values (target_user_id, 'friend_request_received', me);
    return;
  end if;

  if existing.status = 'blocked' then
    raise exception 'Cannot send a friend request to this user';
  end if;

  if existing.status = 'accepted' or existing.requested_by = me then
    return; -- already friends, or already requested — no-op
  end if;

  -- The other party already requested us — mutual request accepts it. From
  -- their perspective this IS an acceptance, so notify them as such.
  update friendships
  set status = 'accepted', updated_at = now()
  where user_id_a = a and user_id_b = b;

  insert into notifications (recipient_id, type, actor_id)
  values (target_user_id, 'friend_request_accepted', me);
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

  insert into notifications (recipient_id, type, actor_id)
  values (requester_user_id, 'friend_request_accepted', me);
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

create or replace function cancel_friend_request(recipient_user_id uuid)
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  a uuid := least(me, recipient_user_id);
  b uuid := greatest(me, recipient_user_id);
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;
  if me = recipient_user_id then
    raise exception 'Cannot cancel a friend request to yourself';
  end if;

  delete from friendships
  where user_id_a = a and user_id_b = b
    and status = 'pending'
    and requested_by = me;

  if not found then
    raise exception 'No pending friend request to this user';
  end if;
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
  watch_url         text,
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
  p_note text default null,
  p_watch_url text default null
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

  insert into recommendations (sender_user_id, recipient_user_id, tmdb_id, type, title, year, poster_url, note, watch_url)
  values (me, recipient_id, p_tmdb_id, p_type, p_title, p_year, p_poster_url, nullif(trim(p_note), ''), nullif(trim(p_watch_url), ''))
  on conflict (sender_user_id, recipient_user_id, tmdb_id, type)
  do update set title = excluded.title, year = excluded.year, poster_url = excluded.poster_url,
    note = excluded.note, watch_url = excluded.watch_url, status = 'unread', updated_at = now();

  insert into notifications (recipient_id, type, actor_id, payload)
  values (recipient_id, 'recommendation_received', me, jsonb_build_object('tmdb_id', p_tmdb_id, 'type', p_type, 'title', p_title));
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
  watch_url text,
  status text,
  created_at timestamptz
)
language sql security definer stable as $$
  select r.id, r.sender_user_id, p.display_name, p.username, r.tmdb_id, r.type,
    r.title, r.year, r.poster_url, r.note, r.watch_url, r.status, r.created_at
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

  if owner_id <> me then
    insert into notifications (recipient_id, type, actor_id, title_id)
    values (owner_id, 'comment_received', me, p_title_id);
  end if;

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

  if owner_id <> me then
    insert into notifications (recipient_id, type, actor_id, title_id, payload)
    values (owner_id, 'reaction_received', me, p_title_id, jsonb_build_object('emoji', p_emoji));
  end if;
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
-- NOTIFICATIONS — persistent, per-recipient inbox
-- ============================================================
--
-- Distinct from the client's ephemeral pushNotification()/<NotificationStack/>
-- toast system, which is untouched and keeps handling transient success/
-- error feedback. This is a durable, dismissable, unread-counted inbox that
-- replaces the earlier client-side activityFeedLastSeenAt/activityUnseenCount
-- watermark.
--
-- No client insert policy at all (same "single choke-point" philosophy as
-- friendships/recommendations/title_comments) — every row is inserted from
-- inside the existing SECURITY DEFINER action function that causes it.
create table notifications (
  id           uuid primary key default gen_random_uuid(),
  recipient_id uuid not null references auth.users(id) on delete cascade,
  type         text not null check (type in (
                 'friend_request_received', 'friend_request_accepted',
                 'share_link_used', 'recommendation_received',
                 'comment_received', 'reaction_received', 'invite_redeemed',
                 'outing_completed', 'outing_plans_shared'
               )),
  actor_id     uuid references auth.users(id) on delete set null,
  title_id     uuid references titles(id) on delete set null,
  payload      jsonb not null default '{}',
  created_at   timestamptz not null default now(),
  read_at      timestamptz
);

create index notifications_recipient_idx on notifications(recipient_id, created_at desc);

alter table notifications enable row level security;

create policy "notifications: recipient can read"
  on notifications for select
  using (auth.uid() = recipient_id);

create policy "notifications: recipient can mark read/delete"
  on notifications for update
  using (auth.uid() = recipient_id)
  with check (auth.uid() = recipient_id);

create policy "notifications: recipient can delete"
  on notifications for delete
  using (auth.uid() = recipient_id);

create or replace function list_notifications(p_before timestamptz default null, p_limit integer default 30)
returns table (
  id uuid,
  type text,
  actor_id uuid,
  actor_display_name text,
  actor_username text,
  title_id uuid,
  tmdb_id integer,
  media_type media_type,
  title text,
  poster_url text,
  payload jsonb,
  created_at timestamptz,
  read_at timestamptz
)
language sql security definer stable as $$
  select
    n.id, n.type, n.actor_id, p.display_name, p.username,
    n.title_id, t.tmdb_id, t.type, t.title, t.poster_url,
    n.payload, n.created_at, n.read_at
  from notifications n
  left join profiles p on p.user_id = n.actor_id
  left join titles t on t.id = n.title_id
  where n.recipient_id = auth.uid()
    and (p_before is null or n.created_at < p_before)
  order by n.created_at desc
  limit least(coalesce(p_limit, 30), 50);
$$;

create or replace function mark_notification_read(p_id uuid)
returns void
language sql security definer as $$
  update notifications set read_at = now()
  where id = p_id and recipient_id = auth.uid() and read_at is null;
$$;

create or replace function mark_all_notifications_read()
returns void
language sql security definer as $$
  update notifications set read_at = now()
  where recipient_id = auth.uid() and read_at is null;
$$;

create or replace function unread_notification_count()
returns integer
language sql security definer stable as $$
  select count(*)::integer from notifications
  where recipient_id = auth.uid() and read_at is null;
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
-- apps/web/src/lib/auth.ts) fails for any email that isn't already a known user.
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

-- Suggested friends via invite lineage: people connected to the current user
-- through an invite code (they redeemed yours, or you redeemed theirs) who
-- aren't already in any friendship state with them. SECURITY DEFINER for the
-- same reason as find_user_by_email: the visibility needed spans both parties'
-- invite_codes rows and other users' profiles.
create or replace function list_invite_connections()
returns table (
  user_id uuid,
  username text,
  display_name text,
  connection text  -- 'invited_by_you' | 'invited_you'
)
language sql security definer stable as $$
  select p.user_id, p.username, p.display_name, c.connection
  from (
    select ic.redeemed_by as other_user, 'invited_by_you'::text as connection
    from invite_codes ic
    where ic.created_by = auth.uid() and ic.redeemed_by is not null
    union
    select ic.created_by as other_user, 'invited_you'::text as connection
    from invite_codes ic
    where ic.redeemed_by = auth.uid()
  ) c
  join profiles p on p.user_id = c.other_user
  where c.other_user <> auth.uid()
    and not exists (
      select 1 from friendships f
      where f.user_id_a = least(auth.uid(), c.other_user)
        and f.user_id_b = greatest(auth.uid(), c.other_user)
    );
$$;

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

-- ============================================================
-- CINEMA OUTINGS — "I've got tickets" (booked cinema trips)
-- ============================================================
--
-- Closes the gap between "I plan to watch this" and "I watched this": a
-- scheduled cinema trip that auto-completes into a watched title + a logged
-- viewing once showtime + previews + runtime has passed. Owner-only by
-- design (no shared-token/friend read) — nobody can see where you *will*
-- be. See docs/superpowers/plans/2026-07-11-cinema-outings.md §6.

create table cinema_outings (
  id                      uuid primary key default gen_random_uuid(),
  user_id                 uuid not null references auth.users(id) on delete cascade,
  title_id                uuid not null references titles(id) on delete cascade,
  showtime                timestamptz not null,
  previews_minutes        integer not null default 20 check (previews_minutes between 0 and 120),
  runtime_minutes         integer not null check (runtime_minutes > 0),
  -- plain column (not generated: timestamptz + interval isn't immutable);
  -- written by client/RPC, guarded by the check below
  ends_at                 timestamptz not null,
  venue                   text,
  companions              jsonb not null default '[]',  -- [{ name, friendUserId? }]
  format                  text,          -- from the fixed UI list; free text at rest
  ticket_price            numeric(6,2) check (ticket_price >= 0),
  -- Legacy free-text seat string. Still written by the web app and still the
  -- display fallback; the structured trio below is preferred where present.
  seat                    text,
  auditorium              text,          -- "7", "Theatre 7", "Grand Hall"
  seat_row                text,          -- "F" (not `row` — reserved keyword)
  seats                   jsonb not null default '[]',  -- ["12", "13"]
  booking_ref             text,
  -- Captured ticket (issue #219) — the real scannable code, decoded on-device from a photo,
  -- distinct from booking_ref above (a manually-typed confirmation code). Format stored
  -- alongside payload so it can be redrawn as the right symbology, not guessed as QR.
  ticket_image_path       text,
  ticket_barcode_payload  text,
  ticket_barcode_format   text,
  notes                   text,
  status                  text not null default 'scheduled'
                            check (status in ('scheduled','completed','missed','cancelled')),
  previous_status         watch_status,  -- title status captured at completion (for revert)
  completed_viewing_id    uuid references viewings(id) on delete set null,
  follow_up_dismissed_at  timestamptz,
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  constraint outing_ends_after_start check (ends_at > showtime),
  constraint cinema_outings_seats_is_array check (jsonb_typeof(seats) = 'array')
);

create index cinema_outings_user_idx  on cinema_outings(user_id, status, ends_at);
create index cinema_outings_title_idx on cinema_outings(title_id);

alter table cinema_outings enable row level security;
create policy "cinema_outings: owner full access" on cinema_outings
  for all using (auth.uid() = user_id) with check (auth.uid() = user_id);
-- deliberately NO shared-token / friend read policies (v1 privacy stance)

create trigger cinema_outings_updated_at
  before update on cinema_outings
  for each row execute function update_updated_at();

-- Theater/company become part of the permanent viewing timeline for any
-- viewing, not just outing-logged ones. Added via ALTER (rather than inline
-- on the `viewings` table above) since cinema_outings.id didn't exist yet
-- at that point in this file.
alter table viewings
  add column venue      text,
  add column companions jsonb not null default '[]',
  add column outing_id  uuid references cinema_outings(id) on delete set null;

-- ─── RPCs ───────────────────────────────────────────────────────────────────

-- The single choke point for auto-completion: client-triggered, server-
-- executed. Called on load/focus/online/timer by the reconciler (never a
-- client-side fake completion). Idempotent — only ever touches the caller's
-- own 'scheduled' rows whose ends_at has passed — and safe under
-- multi-device races via "for update skip locked" (a device that loses the
-- race simply completes nothing for that outing).
create or replace function complete_due_outings(p_tz text default 'UTC')
returns table (
  outing_id uuid,
  title_id uuid,
  viewing_id uuid,
  new_title_status watch_status,
  previous_status watch_status
)
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  v_tz text;
  rec cinema_outings;
  v_viewing_id uuid;
  v_prev_status watch_status;
  v_companion_names jsonb;
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  -- Validate the client-supplied IANA zone against pg_timezone_names; a
  -- bogus/spoofed value silently falls back to UTC rather than erroring the
  -- whole reconciliation pass.
  select name into v_tz from pg_timezone_names where name = p_tz;
  if v_tz is null then
    v_tz := 'UTC';
  end if;

  for rec in
    select * from cinema_outings
    where user_id = me
      and status = 'scheduled'
      and ends_at <= now()
    for update skip locked
  loop
    v_companion_names := (
      select coalesce(jsonb_agg(c ->> 'name'), '[]'::jsonb)
      from jsonb_array_elements(rec.companions) c
    );

    -- 1. Log the viewing — viewed_at is the showtime's calendar date in the
    --    user's timezone (an absolute instant needs a zone to become a date;
    --    see plan §5.1), carrying the outing's theater/company forward.
    insert into viewings (title_id, user_id, viewed_at, venue, companions, outing_id)
    values (rec.title_id, me, (rec.showtime at time zone v_tz)::date, rec.venue, rec.companions, rec.id)
    returning id into v_viewing_id;

    -- 2. Flip the title to watched unless it already is (rule §5.9: a
    --    rewatch of a watched title, or a ticket for a dropped one, both
    --    resolve to 'watched' — previous_status remembers what it was for
    --    a faithful "Didn't make it" revert).
    select status into v_prev_status from titles where id = rec.title_id;
    if v_prev_status <> 'watched' then
      update titles set status = 'watched' where id = rec.title_id;
    end if;

    -- 3. Self-notification (no actor) — the "How was it?" prompt.
    insert into notifications (recipient_id, type, title_id, payload)
    values (me, 'outing_completed', rec.title_id, jsonb_build_object('venue', rec.venue, 'companions', v_companion_names));

    -- 4. Close out the outing.
    update cinema_outings
    set status = 'completed', previous_status = v_prev_status, completed_viewing_id = v_viewing_id
    where id = rec.id;

    outing_id := rec.id;
    title_id := rec.title_id;
    viewing_id := v_viewing_id;
    new_title_status := 'watched';
    previous_status := v_prev_status;
    return next;
  end loop;
end;
$$;

-- One-way plan-sharing snapshot (§4.10). Mirrors send_recommendation's
-- shape: verifies ownership + status, then is_friend() per recipient, and
-- inserts one notification per recipient whose payload is a denormalized
-- snapshot — never a read grant on the outing itself, and never the
-- booking ref (that's effectively the ticket). Later edits/cancellations
-- don't propagate (rule §5.15); re-sharing sends a fresh notification.
create or replace function share_outing_plans(p_outing_id uuid, p_recipient_ids uuid[])
returns void
language plpgsql security definer as $$
declare
  me uuid := auth.uid();
  outing cinema_outings;
  t titles;
  v_companion_names jsonb;
  recipient uuid;
begin
  if me is null then
    raise exception 'Not authenticated';
  end if;

  select * into outing from cinema_outings where id = p_outing_id and user_id = me;
  if outing.id is null then
    raise exception 'Outing not found';
  end if;
  if outing.status <> 'scheduled' then
    raise exception 'Can only share plans for a scheduled outing';
  end if;

  select * into t from titles where id = outing.title_id;

  v_companion_names := (
    select coalesce(jsonb_agg(c ->> 'name'), '[]'::jsonb)
    from jsonb_array_elements(outing.companions) c
  );

  foreach recipient in array coalesce(p_recipient_ids, '{}') loop
    if recipient = me then
      raise exception 'Cannot share plans with yourself';
    end if;
    if not is_friend(me, recipient) then
      raise exception 'Can only share plans with accepted friends';
    end if;

    insert into notifications (recipient_id, type, actor_id, title_id, payload)
    values (
      recipient, 'outing_plans_shared', me, outing.title_id,
      jsonb_build_object(
        'tmdb_id', t.tmdb_id,
        'type', t.type,
        'title', t.title,
        'year', t.year,
        'poster_url', t.poster_url,
        'showtime', outing.showtime,
        'ends_at', outing.ends_at,
        'venue', outing.venue,
        'format', outing.format,
        'seat', outing.seat,
        'companions', v_companion_names
      )
    );
  end loop;
end;
$$;

-- ============================================================
-- ANDROID SYNC LAYER — bootstrap + incremental sync support for the
-- native Android client (docs/android-sync-contract.md,
-- docs/android-contracts/). Validated against a non-production Supabase
-- project before promotion; see supabase/migrations/20260713000000_android_sync_layer.sql.
-- ============================================================

alter table seasons              add column updated_at timestamptz not null default now();
alter table episodes             add column updated_at timestamptz not null default now();
alter table viewings             add column updated_at timestamptz not null default now();
alter table episode_watch_events add column updated_at timestamptz not null default now();
alter table episode_ratings      add column updated_at timestamptz not null default now();
alter table episode_reviews      add column updated_at timestamptz not null default now();
alter table title_cast           add column updated_at timestamptz not null default now();
alter table title_crew           add column updated_at timestamptz not null default now();
alter table season_cast          add column updated_at timestamptz not null default now();
alter table episode_crew         add column updated_at timestamptz not null default now();

create trigger seasons_updated_at              before update on seasons              for each row execute function update_updated_at();
create trigger episodes_updated_at             before update on episodes             for each row execute function update_updated_at();
create trigger viewings_updated_at             before update on viewings             for each row execute function update_updated_at();
create trigger episode_watch_events_updated_at before update on episode_watch_events for each row execute function update_updated_at();
create trigger episode_ratings_updated_at      before update on episode_ratings      for each row execute function update_updated_at();
create trigger episode_reviews_updated_at      before update on episode_reviews      for each row execute function update_updated_at();
create trigger title_cast_updated_at           before update on title_cast           for each row execute function update_updated_at();
create trigger title_crew_updated_at           before update on title_crew           for each row execute function update_updated_at();
create trigger season_cast_updated_at          before update on season_cast          for each row execute function update_updated_at();
create trigger episode_crew_updated_at         before update on episode_crew         for each row execute function update_updated_at();
create trigger lists_updated_at                before update on lists                for each row execute function update_updated_at();
create trigger list_items_updated_at           before update on list_items           for each row execute function update_updated_at();

-- Tombstones — deliberately NOT a foreign key on user_id (account-deletion
-- cascade bug found during validation; see docs/android-sync-contract.md §3.3).
create table sync_tombstones (
  id            uuid primary key default gen_random_uuid(),
  user_id       uuid not null,
  entity_type   text not null,
  entity_id     uuid not null,
  deleted_at    timestamptz not null default now()
);

create index sync_tombstones_user_deleted_idx on sync_tombstones(user_id, deleted_at);

alter table sync_tombstones enable row level security;

create policy "sync_tombstones: owner read"
  on sync_tombstones for select
  using (auth.uid() = user_id);

create or replace function record_tombstone()
returns trigger language plpgsql security definer as $$
begin
  insert into sync_tombstones (user_id, entity_type, entity_id)
  values (old.user_id, tg_argv[0], old.id);
  return old;
end;
$$;

create trigger titles_tombstone               before delete on titles               for each row execute function record_tombstone('title');
create trigger seasons_tombstone              before delete on seasons              for each row execute function record_tombstone('season');
create trigger episodes_tombstone             before delete on episodes             for each row execute function record_tombstone('episode');
create trigger viewings_tombstone             before delete on viewings             for each row execute function record_tombstone('viewing');
create trigger episode_watch_events_tombstone before delete on episode_watch_events for each row execute function record_tombstone('episode_watch_event');
create trigger episode_ratings_tombstone      before delete on episode_ratings      for each row execute function record_tombstone('episode_rating');
create trigger episode_reviews_tombstone      before delete on episode_reviews      for each row execute function record_tombstone('episode_review');
create trigger cinema_outings_tombstone       before delete on cinema_outings       for each row execute function record_tombstone('cinema_outing');
create trigger title_cast_tombstone           before delete on title_cast           for each row execute function record_tombstone('title_cast');
create trigger title_crew_tombstone           before delete on title_crew           for each row execute function record_tombstone('title_crew');
create trigger lists_tombstone                before delete on lists                for each row execute function record_tombstone('list');
create trigger list_items_tombstone           before delete on list_items           for each row execute function record_tombstone('list_item');

create or replace function sync_library_changes(p_since timestamptz, p_limit integer default 500)
returns table (
  entity_type text,
  entity_id uuid,
  parent_id uuid,
  updated_at timestamptz,
  payload jsonb
)
language sql security definer stable as $$
  with changes as (
    select 'title'::text as entity_type, t.id as entity_id, null::uuid as parent_id, t.updated_at as updated_at,
      jsonb_build_object(
        'id', t.id, 'tmdbId', t.tmdb_id, 'type', t.type, 'title', t.title, 'year', t.year,
        'director', t.director, 'genres', t.genres, 'posterUrl', t.poster_url,
        'backdropUrl', t.backdrop_url, 'synopsis', t.synopsis, 'runtime', t.runtime,
        'network', t.network, 'status', t.status, 'rating', t.rating, 'notes', t.notes,
        'addedAt', t.added_at, 'updatedAt', t.updated_at, 'releaseDate', t.release_date,
        'imdbRating', t.imdb_rating, 'originalLanguage', t.original_language
      ) as payload
    from titles t where t.user_id = auth.uid() and t.updated_at > p_since

    union all

    select 'season'::text, s.id, s.title_id, s.updated_at,
      jsonb_build_object(
        'id', s.id, 'titleId', s.title_id, 'seasonNumber', s.season_number,
        'episodeCount', s.episode_count, 'episodesWatched', s.episodes_watched, 'airYear', s.air_year
      )
    from seasons s where s.user_id = auth.uid() and s.updated_at > p_since

    union all

    select 'episode'::text, e.id, e.title_id, e.updated_at,
      jsonb_build_object(
        'id', e.id, 'titleId', e.title_id, 'seasonNumber', e.season_number,
        'episodeNumber', e.episode_number, 'episodeName', e.episode_name,
        'airDate', e.air_date, 'runtime', e.runtime,
        'synopsis', e.synopsis, 'stillUrl', e.still_url
      )
    from episodes e where e.user_id = auth.uid() and e.updated_at > p_since

    union all

    -- Only the columns the Android mirror holds (core/database/Entities.kt);
    -- profile_url/episode_count stay out rather than inflate a payload nothing reads.
    select 'title_cast'::text, tc.id, tc.title_id, tc.updated_at,
      jsonb_build_object(
        'id', tc.id, 'titleId', tc.title_id, 'tmdbPersonId', tc.tmdb_person_id,
        'name', tc.name, 'characterName', tc.character_name, 'castOrder', tc.cast_order
      )
    from title_cast tc where tc.user_id = auth.uid() and tc.updated_at > p_since

    union all

    select 'title_crew'::text, cw.id, cw.title_id, cw.updated_at,
      jsonb_build_object(
        'id', cw.id, 'titleId', cw.title_id, 'tmdbPersonId', cw.tmdb_person_id,
        'name', cw.name, 'job', cw.job, 'department', cw.department
      )
    from title_crew cw where cw.user_id = auth.uid() and cw.updated_at > p_since

    union all

    select 'viewing'::text, v.id, v.title_id, v.updated_at,
      jsonb_build_object(
        'id', v.id, 'titleId', v.title_id, 'date', v.viewed_at, 'rating', v.rating,
        'notes', v.notes, 'venue', v.venue, 'companions', v.companions, 'outingId', v.outing_id
      )
    from viewings v where v.user_id = auth.uid() and v.updated_at > p_since

    union all

    select 'episode_watch_event'::text, we.id, we.episode_id, we.updated_at,
      jsonb_build_object('id', we.id, 'episodeId', we.episode_id, 'watchedAt', we.watched_at)
    from episode_watch_events we where we.user_id = auth.uid() and we.updated_at > p_since

    union all

    select 'episode_rating'::text, er.id, er.episode_id, er.updated_at,
      jsonb_build_object('id', er.id, 'episodeId', er.episode_id, 'rating', er.rating, 'ratedAt', er.rated_at)
    from episode_ratings er where er.user_id = auth.uid() and er.updated_at > p_since

    union all

    select 'episode_review'::text, rv.id, rv.episode_id, rv.updated_at,
      jsonb_build_object('id', rv.id, 'episodeId', rv.episode_id, 'reviewText', rv.review_text, 'reviewedAt', rv.reviewed_at)
    from episode_reviews rv where rv.user_id = auth.uid() and rv.updated_at > p_since

    union all

    select 'cinema_outing'::text, co.id, co.title_id, co.updated_at,
      jsonb_build_object(
        'id', co.id, 'titleId', co.title_id, 'showtime', co.showtime,
        'previewsMinutes', co.previews_minutes, 'runtimeMinutes', co.runtime_minutes,
        'endsAt', co.ends_at, 'venue', co.venue, 'companions', co.companions,
        'format', co.format, 'ticketPrice', co.ticket_price, 'seat', co.seat,
        'auditorium', co.auditorium, 'seatRow', co.seat_row, 'seats', co.seats,
        'bookingRef', co.booking_ref, 'ticketImagePath', co.ticket_image_path,
        'ticketBarcodePayload', co.ticket_barcode_payload, 'ticketBarcodeFormat', co.ticket_barcode_format,
        'notes', co.notes, 'status', co.status,
        'previousStatus', co.previous_status, 'completedViewingId', co.completed_viewing_id,
        'followUpDismissedAt', co.follow_up_dismissed_at, 'createdAt', co.created_at,
        'updatedAt', co.updated_at
      )
    from cinema_outings co where co.user_id = auth.uid() and co.updated_at > p_since

    union all

    select 'list'::text, l.id, null::uuid, l.updated_at,
      jsonb_build_object(
        'id', l.id, 'name', l.name, 'description', l.description,
        'createdAt', l.created_at, 'updatedAt', l.updated_at
      )
    from lists l where l.user_id = auth.uid() and l.updated_at > p_since

    union all

    select 'list_item'::text, li.id, li.list_id, li.updated_at,
      jsonb_build_object(
        'id', li.id, 'listId', li.list_id, 'titleId', li.title_id,
        'position', li.position, 'addedAt', li.added_at, 'updatedAt', li.updated_at
      )
    from list_items li where li.user_id = auth.uid() and li.updated_at > p_since

    union all

    select 'tombstone'::text, st.entity_id, null::uuid, st.deleted_at,
      jsonb_build_object('entityType', st.entity_type)
    from sync_tombstones st where st.user_id = auth.uid() and st.deleted_at > p_since
  ),
  ordered as (
    select c.*, row_number() over (order by c.updated_at, c.entity_id) as rn
    from changes c
  )
  -- The limit is a floor, not a ceiling: take every row up to and including the
  -- last one sharing the limit-th row's `updated_at`, so a same-timestamp group is
  -- never split across pages. Both `updated_at` defaults are the *transaction*
  -- timestamp, so a title's whole cast lands on one microsecond, while the client's
  -- cursor is a single watermark advanced with a strict `>` — a split group would
  -- lose its tail permanently and silently
  -- (supabase/migrations/20260726000000_sync_cast_crew_and_scores.sql).
  select o.entity_type, o.entity_id, o.parent_id, o.updated_at, o.payload
  from ordered o
  where o.updated_at <= coalesce(
    (select o2.updated_at from ordered o2 where o2.rn = least(coalesce(p_limit, 500), 500)),
    'infinity'::timestamptz
  )
  order by o.updated_at, o.entity_id;
$$;

-- Atomic library commands (20261008162831)
-- Durable clients submit one immutable operation ID for one transaction. Receipts
-- and privileged implementation live outside the exposed Data API schema.
-- Deletion triggers must also work when invoked from a hardened empty path.
create or replace function public.record_tombstone()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.sync_tombstones (user_id, entity_type, entity_id)
  values (old.user_id, tg_argv[0], old.id);
  return old;
end;
$$;

create schema if not exists cinemarchive_private;
revoke all on schema cinemarchive_private from public, anon;
grant usage on schema cinemarchive_private to authenticated;

create table cinemarchive_private.library_command_receipts (
  user_id uuid not null references auth.users(id) on delete cascade,
  operation_id uuid not null,
  operations jsonb not null,
  result jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  primary key (user_id, operation_id)
);
alter table cinemarchive_private.library_command_receipts enable row level security;
revoke all on cinemarchive_private.library_command_receipts from public, anon, authenticated;

create or replace function cinemarchive_private.apply_library_command(
  p_operation_id uuid, p_operations jsonb
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_owner uuid := auth.uid();
  v_receipt cinemarchive_private.library_command_receipts%rowtype;
  v_op jsonb;
  v_table text;
  v_action text;
  v_key jsonb;
  v_values jsonb;
  v_row jsonb;
  v_existing jsonb;
  v_allowed text[];
  v_keys text[];
  v_actual_keys text[];
  v_column text;
  v_where text;
  v_columns text;
  v_select text;
  v_assign text;
  v_conflict text;
  v_parent uuid;
  v_rows jsonb := '[]'::jsonb;
  v_result jsonb;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if p_operation_id is null or jsonb_typeof(p_operations) is distinct from 'array'
     or jsonb_array_length(p_operations) not between 1 and 2048
     or octet_length(p_operations::text) > 2097152 then
    raise exception 'Invalid library command' using errcode = '22023';
  end if;

  -- Concurrent retries serialize on this primary key. Failure anywhere below
  -- rolls back the placeholder along with every mutation in the command.
  insert into cinemarchive_private.library_command_receipts(user_id, operation_id, operations)
    values (v_owner, p_operation_id, p_operations) on conflict do nothing;
  select * into strict v_receipt from cinemarchive_private.library_command_receipts
    where user_id = v_owner and operation_id = p_operation_id for update;
  if v_receipt.operations <> p_operations then
    raise exception 'Operation ID reused with different data' using errcode = '22023';
  end if;
  if v_receipt.result <> '{}'::jsonb then return v_receipt.result; end if;

  for v_op in select value from jsonb_array_elements(p_operations) loop
    if jsonb_typeof(v_op) is distinct from 'object' or
       exists(select 1 from jsonb_object_keys(v_op) k where k not in ('table','action','key','values','expectedUpdatedAt')) then
      raise exception 'Invalid command operation' using errcode = '22023';
    end if;
    v_table := v_op->>'table';
    v_action := v_op->>'action';
    v_key := v_op->'key';
    v_values := coalesce(v_op->'values', '{}'::jsonb);
    if v_action is null or v_action not in ('insert','update','delete','put') or
       jsonb_typeof(v_key) is distinct from 'object' or jsonb_typeof(v_values) is distinct from 'object' then
      raise exception 'Invalid command action or fields' using errcode = '22023';
    end if;
    v_keys := array['id'];
    -- This is an explicit API allowlist, not arbitrary SQL/table access. Owner
    -- identity is always injected from auth.uid(), never accepted from JSON.
    case v_table
      when 'titles' then v_allowed := array['tmdb_id','type','title','year','director','genres','poster_url','backdrop_url','synopsis','runtime','network','status','rating','notes','tags','imdb_rating','rt_score','metacritic_score','studios','added_at','release_date','original_language','content_rating','imdb_id','rt_url','awards_count','bechdel_outcome','bechdel_score','custom_watch_url','in_home_collection','physical_media','collection_id','collection_name'];
      when 'seasons' then v_allowed := array['title_id','season_number','episode_count','episodes_watched','air_year'];
      when 'episodes' then v_allowed := array['title_id','season_number','episode_number','episode_name','air_date','runtime','synopsis','still_url'];
      when 'viewings' then v_allowed := array['title_id','viewed_at','rating','notes','venue','companions','outing_id','created_at'];
      when 'episode_watch_events' then v_allowed := array['episode_id','watched_at','notes','color_mode','created_at'];
      when 'episode_ratings' then v_allowed := array['episode_id','rating','rated_at'];
      when 'episode_reviews' then v_allowed := array['episode_id','review_text','reviewed_at','color_mode'];
      when 'cinema_outings' then v_allowed := array['title_id','showtime','previews_minutes','runtime_minutes','ends_at','venue','companions','format','ticket_price','seat','auditorium','seat_row','seats','booking_ref','ticket_image_path','ticket_barcode_payload','ticket_barcode_format','notes','status','previous_status','completed_viewing_id','follow_up_dismissed_at','created_at'];
      when 'lists' then v_allowed := array['name','description','created_at'];
      when 'list_items' then v_keys := array['list_id','title_id']; v_allowed := array['position','added_at'];
      when 'user_title_pins' then v_keys := array['title_id','easter_egg_key']; v_allowed := array['pinned_variant'];
      when 'user_prefs' then v_keys := array[]::text[]; v_allowed := array['ledger_layout'];
      when 'title_cast' then v_keys := array['title_id','tmdb_person_id']; v_allowed := array['name','character_name','episode_count','profile_url','cast_order'];
      when 'title_crew' then v_keys := array['title_id','tmdb_person_id','job']; v_allowed := array['name','department','profile_url'];
      when 'season_cast' then v_keys := array['season_id','tmdb_person_id']; v_allowed := array['title_id','name','character_name','episode_count','profile_url','cast_order'];
      when 'episode_crew' then v_keys := array['episode_id','tmdb_person_id','job']; v_allowed := array['title_id','name'];
      else raise exception 'Unsupported library entity' using errcode = '22023';
    end case;
    if v_action = 'put' and v_table not in ('user_title_pins','user_prefs') then
      raise exception 'Put is restricted to singleton preferences' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_table in ('title_cast','title_crew') and v_key ? 'title_id' and not v_key ? 'tmdb_person_id' then v_keys := array['title_id']; end if;
    if v_action = 'delete' and v_table = 'season_cast' and v_key ? 'season_id' and not v_key ? 'tmdb_person_id' then v_keys := array['season_id']; end if;
    if v_action = 'delete' and v_table = 'episode_crew' and v_key ? 'episode_id' and not v_key ? 'tmdb_person_id' then v_keys := array['episode_id']; end if;
    select coalesce(array_agg(k order by k), array[]::text[]) into v_actual_keys from jsonb_object_keys(v_key) k;
    if not (v_actual_keys @> v_keys and v_keys @> v_actual_keys) or
       exists(select 1 from jsonb_each(v_key) e where e.value = 'null'::jsonb or jsonb_typeof(e.value) not in ('number','string')) or
       exists(select 1 from jsonb_object_keys(v_values) k where not k = any(v_allowed)) then
      raise exception 'Unsupported library fields or identity' using errcode = '22023';
    end if;
    if v_action in ('update','put') and exists(select 1 from jsonb_object_keys(v_values) k
      where k in ('title_id','episode_id','season_id','season_number','episode_number','created_at','added_at')) then
      raise exception 'Parent and creation fields are immutable' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_values <> '{}'::jsonb then raise exception 'Delete has no values' using errcode = '22023'; end if;
    v_where := 'r.user_id = $2';
    foreach v_column in array v_keys loop
      v_where := v_where || format(' and r.%I = (jsonb_populate_record(null::public.%I,$1)).%I', v_column, v_table, v_column);
    end loop;
    v_existing := null;
    execute format('select to_jsonb(r) from public.%I r where %s limit 1 for update', v_table, v_where)
      into v_existing using v_key, v_owner;
    if v_op ? 'expectedUpdatedAt' and (v_existing is null or (v_existing->>'updated_at')::timestamptz is distinct from (v_op->>'expectedUpdatedAt')::timestamptz) then
      raise exception 'Library record changed on another device' using errcode = '40001';
    end if;
    if v_action = 'delete' then
      execute format('delete from public.%I r where %s', v_table, v_where) using v_key, v_owner;
      v_rows := v_rows || jsonb_build_array(jsonb_build_object('table',v_table,'key',v_key,'deleted',true));
      continue;
    end if;
    if v_action = 'update' and v_existing is null then raise exception 'Library record no longer exists' using errcode = 'P0002'; end if;
    if v_action = 'insert' and v_existing is not null then
      foreach v_column in array array['title_id','episode_id','season_id','season_number','episode_number','tmdb_id','type'] loop
        if v_values ? v_column and v_values->v_column is distinct from v_existing->v_column then
          raise exception 'Library identity reused for another record' using errcode='23505';
        end if;
      end loop;
    end if;
    v_row := coalesce(v_existing, '{}'::jsonb) || v_values || v_key || jsonb_build_object('user_id',v_owner);

    -- Parent ownership is checked even where historical RLS only checked the
    -- child's user_id. A caller cannot attach their row to another user's graph.
    if v_row ? 'title_id' then
      v_parent := (v_row->>'title_id')::uuid;
      if not exists(select 1 from public.titles where id=v_parent and user_id=v_owner) then raise exception 'Title ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'episode_id' then
      v_parent := (v_row->>'episode_id')::uuid;
      if not exists(select 1 from public.episodes where id=v_parent and user_id=v_owner and
        (not v_row ? 'title_id' or title_id=(v_row->>'title_id')::uuid)) then raise exception 'Episode ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'season_id' then
      v_parent := (v_row->>'season_id')::uuid;
      if not exists(select 1 from public.seasons where id=v_parent and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Season ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'list_id' and not exists(select 1 from public.lists where id=(v_row->>'list_id')::uuid and user_id=v_owner) then raise exception 'List ownership required' using errcode='42501'; end if;
    if v_table='episodes' and not exists(select 1 from public.seasons where title_id=(v_row->>'title_id')::uuid and season_number=(v_row->>'season_number')::integer and user_id=v_owner) then raise exception 'Episode season required' using errcode='23503'; end if;
    if v_row->>'outing_id' is not null and not exists(select 1 from public.cinema_outings where id=(v_row->>'outing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Outing ownership required' using errcode='42501'; end if;
    if v_row->>'completed_viewing_id' is not null and not exists(select 1 from public.viewings where id=(v_row->>'completed_viewing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Viewing ownership required' using errcode='42501'; end if;

    if v_action = 'update' or (v_action='put' and v_existing is not null) then
      select string_agg(format('%I = p.%I', k, k), ',') into v_assign from jsonb_object_keys(v_values) k;
      if v_assign is not null then
        execute format('update public.%I r set %s from jsonb_populate_record(null::public.%I,$3) p where %s returning to_jsonb(r)',v_table,v_assign,v_table,v_where)
          into v_row using v_key,v_owner,v_values;
      else v_row := v_existing; end if;
    elsif v_existing is not null then
      -- An insert retry must not undo an edit made after the original insert.
      v_row := v_existing;
    else
      v_row := v_values || v_key || jsonb_build_object('user_id',v_owner);
      select string_agg(format('%I',k),','),string_agg(format('p.%I',k),',') into v_columns,v_select from jsonb_object_keys(v_row) k;
      v_conflict := 'do nothing';
      if v_action='put' then
        select string_agg(format('%I = excluded.%I',k,k),',') into v_assign from jsonb_object_keys(v_values) k;
        if v_assign is null then raise exception 'Put requires values' using errcode='22023'; end if;
        select string_agg(format('%I',k),',') into v_conflict from unnest(array['user_id'] || v_keys) k;
        v_conflict := '(' || v_conflict || ') do update set ' || v_assign;
      end if;
      execute format('insert into public.%I (%s) select %s from jsonb_populate_record(null::public.%I,$1) p on conflict %s returning to_jsonb(%I)',v_table,v_columns,v_select,v_table,v_conflict,v_table)
        into v_row using v_row;
      if v_row is null then
        execute format('select to_jsonb(r) from public.%I r where %s',v_table,v_where) into v_row using v_key,v_owner;
        if v_row is null then raise exception 'Library identity conflicts with an existing record' using errcode='23505'; end if;
      end if;
    end if;
    v_rows := v_rows || jsonb_build_array(jsonb_build_object('table',v_table,'key',v_key,'row',v_row));
  end loop;
  v_result := jsonb_build_object('operationId',p_operation_id,'rows',v_rows);
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=v_owner and operation_id=p_operation_id;
  return v_result;
end;
$$;
revoke all on function cinemarchive_private.apply_library_command(uuid,jsonb) from public, anon;
grant execute on function cinemarchive_private.apply_library_command(uuid,jsonb) to authenticated;

create or replace function public.apply_library_command(p_operation_id uuid,p_operations jsonb)
returns jsonb language sql security invoker set search_path = ''
as $$ select cinemarchive_private.apply_library_command(p_operation_id,p_operations); $$;
revoke all on function public.apply_library_command(uuid,jsonb) from public, anon;
grant execute on function public.apply_library_command(uuid,jsonb) to authenticated;

-- Causal library commands (20261008171852)
-- A queued patch may depend on the immutable result of an earlier command.
-- Resolve that receipt's revision inside the same transaction as the write,
-- without changing the client's persisted payload after an unknown outcome.
create or replace function cinemarchive_private.apply_causal_library_command(
  p_operation_id uuid, p_operations jsonb
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid := auth.uid();
  v_op jsonb;
  v_expected text;
  v_operations jsonb := '[]'::jsonb;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if jsonb_typeof(p_operations) is distinct from 'array'
    or jsonb_array_length(p_operations) not between 1 and 2048
    or octet_length(p_operations::text) > 2097152 then
    raise exception 'Invalid library command' using errcode='22023';
  end if;
  for v_op in select value from jsonb_array_elements(p_operations) loop
    if v_op ? 'expectedOperationId' then
      if v_op ? 'expectedUpdatedAt' or v_op->>'action' not in ('update','delete')
        or jsonb_typeof(v_op->'expectedOperationId') is distinct from 'string'
        or (v_op->>'expectedOperationId')::uuid = p_operation_id then
        raise exception 'Invalid causal revision precondition' using errcode='22023';
      end if;
      v_expected := null;
      -- The last effect on this exact row is authoritative when a compound
      -- command touches it more than once. Another owner's receipt is invisible.
      select effect.value->'row'->>'updated_at' into v_expected
      from cinemarchive_private.library_command_receipts receipt
      cross join lateral jsonb_array_elements(receipt.result->'rows') with ordinality effect(value, ordinal)
      where receipt.user_id=v_owner
        and receipt.operation_id=(v_op->>'expectedOperationId')::uuid
        and effect.value->>'table'=v_op->>'table'
        and effect.value->'key'=v_op->'key'
      order by effect.ordinal desc limit 1;
      if v_expected is null then
        raise exception 'The preceding library change has no matching revision' using errcode='40001';
      end if;
      v_op := jsonb_set(v_op - 'expectedOperationId', '{expectedUpdatedAt}', to_jsonb(v_expected));
    end if;
    v_operations := v_operations || jsonb_build_array(v_op);
  end loop;
  return cinemarchive_private.apply_library_command(p_operation_id,v_operations);
end;
$$;
revoke all on function cinemarchive_private.apply_causal_library_command(uuid,jsonb) from public, anon;
grant execute on function cinemarchive_private.apply_causal_library_command(uuid,jsonb) to authenticated;

create or replace function public.apply_library_command(p_operation_id uuid,p_operations jsonb)
returns jsonb language sql security invoker set search_path = ''
as $$ select cinemarchive_private.apply_causal_library_command(p_operation_id,p_operations); $$;
revoke all on function public.apply_library_command(uuid,jsonb) from public, anon;
grant execute on function public.apply_library_command(uuid,jsonb) to authenticated;

-- Episode watch notes sync (20261008173528)
-- Include existing watch notes in the Android incremental sync payload.
-- Clients resync once after upgrading; pagination and owner filters are unchanged.
-- The private schema and its authenticated USAGE grant are established by
-- 20261008162831_atomic_library_commands.sql; it must remain outside exposed schemas.
create or replace function cinemarchive_private.sync_library_changes(p_since timestamptz, p_limit integer default 500)
returns table (
  entity_type text,
  entity_id uuid,
  parent_id uuid,
  updated_at timestamptz,
  payload jsonb
)
language sql security definer stable set search_path = '' as $$
  with changes as (
    select 'title'::text as entity_type, t.id as entity_id, null::uuid as parent_id, t.updated_at as updated_at,
      jsonb_build_object(
        'id', t.id, 'tmdbId', t.tmdb_id, 'type', t.type, 'title', t.title, 'year', t.year,
        'director', t.director, 'genres', t.genres, 'posterUrl', t.poster_url,
        'backdropUrl', t.backdrop_url, 'synopsis', t.synopsis, 'runtime', t.runtime,
        'network', t.network, 'status', t.status, 'rating', t.rating, 'notes', t.notes,
        'addedAt', t.added_at, 'updatedAt', t.updated_at, 'releaseDate', t.release_date,
        'imdbRating', t.imdb_rating, 'originalLanguage', t.original_language
      ) as payload
    from public.titles t where t.user_id = auth.uid() and t.updated_at > p_since

    union all

    select 'season'::text, s.id, s.title_id, s.updated_at,
      jsonb_build_object(
        'id', s.id, 'titleId', s.title_id, 'seasonNumber', s.season_number,
        'episodeCount', s.episode_count, 'episodesWatched', s.episodes_watched, 'airYear', s.air_year
      )
    from public.seasons s where s.user_id = auth.uid() and s.updated_at > p_since

    union all

    select 'episode'::text, e.id, e.title_id, e.updated_at,
      jsonb_build_object(
        'id', e.id, 'titleId', e.title_id, 'seasonNumber', e.season_number,
        'episodeNumber', e.episode_number, 'episodeName', e.episode_name,
        'airDate', e.air_date, 'runtime', e.runtime,
        'synopsis', e.synopsis, 'stillUrl', e.still_url
      )
    from public.episodes e where e.user_id = auth.uid() and e.updated_at > p_since

    union all

    -- Only the columns the Android mirror holds (core/database/Entities.kt);
    -- profile_url/episode_count stay out rather than inflate a payload nothing reads.
    select 'title_cast'::text, tc.id, tc.title_id, tc.updated_at,
      jsonb_build_object(
        'id', tc.id, 'titleId', tc.title_id, 'tmdbPersonId', tc.tmdb_person_id,
        'name', tc.name, 'characterName', tc.character_name, 'castOrder', tc.cast_order
      )
    from public.title_cast tc where tc.user_id = auth.uid() and tc.updated_at > p_since

    union all

    select 'title_crew'::text, cw.id, cw.title_id, cw.updated_at,
      jsonb_build_object(
        'id', cw.id, 'titleId', cw.title_id, 'tmdbPersonId', cw.tmdb_person_id,
        'name', cw.name, 'job', cw.job, 'department', cw.department
      )
    from public.title_crew cw where cw.user_id = auth.uid() and cw.updated_at > p_since

    union all

    select 'viewing'::text, v.id, v.title_id, v.updated_at,
      jsonb_build_object(
        'id', v.id, 'titleId', v.title_id, 'date', v.viewed_at, 'rating', v.rating,
        'notes', v.notes, 'venue', v.venue, 'companions', v.companions, 'outingId', v.outing_id
      )
    from public.viewings v where v.user_id = auth.uid() and v.updated_at > p_since

    union all

    select 'episode_watch_event'::text, we.id, we.episode_id, we.updated_at,
      jsonb_build_object('id', we.id, 'episodeId', we.episode_id, 'watchedAt', we.watched_at, 'notes', we.notes)
    from public.episode_watch_events we where we.user_id = auth.uid() and we.updated_at > p_since

    union all

    select 'episode_rating'::text, er.id, er.episode_id, er.updated_at,
      jsonb_build_object('id', er.id, 'episodeId', er.episode_id, 'rating', er.rating, 'ratedAt', er.rated_at)
    from public.episode_ratings er where er.user_id = auth.uid() and er.updated_at > p_since

    union all

    select 'episode_review'::text, rv.id, rv.episode_id, rv.updated_at,
      jsonb_build_object('id', rv.id, 'episodeId', rv.episode_id, 'reviewText', rv.review_text, 'reviewedAt', rv.reviewed_at)
    from public.episode_reviews rv where rv.user_id = auth.uid() and rv.updated_at > p_since

    union all

    select 'cinema_outing'::text, co.id, co.title_id, co.updated_at,
      jsonb_build_object(
        'id', co.id, 'titleId', co.title_id, 'showtime', co.showtime,
        'previewsMinutes', co.previews_minutes, 'runtimeMinutes', co.runtime_minutes,
        'endsAt', co.ends_at, 'venue', co.venue, 'companions', co.companions,
        'format', co.format, 'ticketPrice', co.ticket_price, 'seat', co.seat,
        'auditorium', co.auditorium, 'seatRow', co.seat_row, 'seats', co.seats,
        'bookingRef', co.booking_ref, 'ticketImagePath', co.ticket_image_path,
        'ticketBarcodePayload', co.ticket_barcode_payload, 'ticketBarcodeFormat', co.ticket_barcode_format,
        'notes', co.notes, 'status', co.status,
        'previousStatus', co.previous_status, 'completedViewingId', co.completed_viewing_id,
        'followUpDismissedAt', co.follow_up_dismissed_at, 'createdAt', co.created_at,
        'updatedAt', co.updated_at
      )
    from public.cinema_outings co where co.user_id = auth.uid() and co.updated_at > p_since

    union all

    select 'list'::text, l.id, null::uuid, l.updated_at,
      jsonb_build_object(
        'id', l.id, 'name', l.name, 'description', l.description,
        'createdAt', l.created_at, 'updatedAt', l.updated_at
      )
    from public.lists l where l.user_id = auth.uid() and l.updated_at > p_since

    union all

    select 'list_item'::text, li.id, li.list_id, li.updated_at,
      jsonb_build_object(
        'id', li.id, 'listId', li.list_id, 'titleId', li.title_id,
        'position', li.position, 'addedAt', li.added_at, 'updatedAt', li.updated_at
      )
    from public.list_items li where li.user_id = auth.uid() and li.updated_at > p_since

    union all

    select 'tombstone'::text, st.entity_id, null::uuid, st.deleted_at,
      jsonb_build_object('entityType', st.entity_type)
    from public.sync_tombstones st where st.user_id = auth.uid() and st.deleted_at > p_since
  ),
  ordered as (
    select c.*, row_number() over (order by c.updated_at, c.entity_id) as rn
    from changes c
  )
  -- The limit is a floor, not a ceiling: take every row up to and including the
  -- last one sharing the limit-th row's `updated_at`, so a same-timestamp group is
  -- never split across pages. Both `updated_at` defaults are the *transaction*
  -- timestamp, so a title's whole cast lands on one microsecond, while the client's
  -- cursor is a single watermark advanced with a strict `>` — a split group would
  -- lose its tail permanently and silently
  -- (supabase/migrations/20260726000000_sync_cast_crew_and_scores.sql).
  select o.entity_type, o.entity_id, o.parent_id, o.updated_at, o.payload
  from ordered o
  where o.updated_at <= coalesce(
    (select o2.updated_at from ordered o2 where o2.rn = least(coalesce(p_limit, 500), 500)),
    'infinity'::timestamptz
  )
  order by o.updated_at, o.entity_id;
$$;

revoke all on function cinemarchive_private.sync_library_changes(timestamptz,integer) from public, anon;
grant execute on function cinemarchive_private.sync_library_changes(timestamptz,integer) to authenticated;

create or replace function public.sync_library_changes(p_since timestamptz, p_limit integer default 500)
returns table (
  entity_type text,
  entity_id uuid,
  parent_id uuid,
  updated_at timestamptz,
  payload jsonb
)
language sql security invoker stable set search_path = '' as $$
  select * from cinemarchive_private.sync_library_changes(p_since, p_limit);
$$;
revoke all on function public.sync_library_changes(timestamptz,integer) from public, anon;
grant execute on function public.sync_library_changes(timestamptz,integer) to authenticated;

-- Durable import links (20261008174436)
-- Provenance shares the title transaction. Insert identity checks reject
-- retargeting an existing provider/external ID to another title.
create or replace function cinemarchive_private.apply_library_command(
  p_operation_id uuid, p_operations jsonb
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_owner uuid := auth.uid();
  v_receipt cinemarchive_private.library_command_receipts%rowtype;
  v_op jsonb;
  v_table text;
  v_action text;
  v_key jsonb;
  v_values jsonb;
  v_row jsonb;
  v_existing jsonb;
  v_allowed text[];
  v_keys text[];
  v_actual_keys text[];
  v_column text;
  v_where text;
  v_columns text;
  v_select text;
  v_assign text;
  v_conflict text;
  v_parent uuid;
  v_rows jsonb := '[]'::jsonb;
  v_result jsonb;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if p_operation_id is null or jsonb_typeof(p_operations) is distinct from 'array'
     or jsonb_array_length(p_operations) not between 1 and 2048
     or octet_length(p_operations::text) > 2097152 then
    raise exception 'Invalid library command' using errcode = '22023';
  end if;

  -- Concurrent retries serialize on this primary key. Failure anywhere below
  -- rolls back the placeholder along with every mutation in the command.
  insert into cinemarchive_private.library_command_receipts(user_id, operation_id, operations)
    values (v_owner, p_operation_id, p_operations) on conflict do nothing;
  select * into strict v_receipt from cinemarchive_private.library_command_receipts
    where user_id = v_owner and operation_id = p_operation_id for update;
  if v_receipt.operations <> p_operations then
    raise exception 'Operation ID reused with different data' using errcode = '22023';
  end if;
  if v_receipt.result <> '{}'::jsonb then return v_receipt.result; end if;

  for v_op in select value from jsonb_array_elements(p_operations) loop
    if jsonb_typeof(v_op) is distinct from 'object' or
       exists(select 1 from jsonb_object_keys(v_op) k where k not in ('table','action','key','values','expectedUpdatedAt')) then
      raise exception 'Invalid command operation' using errcode = '22023';
    end if;
    v_table := v_op->>'table';
    v_action := v_op->>'action';
    v_key := v_op->'key';
    v_values := coalesce(v_op->'values', '{}'::jsonb);
    if v_action is null or v_action not in ('insert','update','delete','put') or
       jsonb_typeof(v_key) is distinct from 'object' or jsonb_typeof(v_values) is distinct from 'object' then
      raise exception 'Invalid command action or fields' using errcode = '22023';
    end if;
    v_keys := array['id'];
    -- This is an explicit API allowlist, not arbitrary SQL/table access. Owner
    -- identity is always injected from auth.uid(), never accepted from JSON.
    case v_table
      when 'titles' then v_allowed := array['tmdb_id','type','title','year','director','genres','poster_url','backdrop_url','synopsis','runtime','network','status','rating','notes','tags','imdb_rating','rt_score','metacritic_score','studios','added_at','release_date','original_language','content_rating','imdb_id','rt_url','awards_count','bechdel_outcome','bechdel_score','custom_watch_url','in_home_collection','physical_media','collection_id','collection_name'];
      when 'seasons' then v_allowed := array['title_id','season_number','episode_count','episodes_watched','air_year'];
      when 'episodes' then v_allowed := array['title_id','season_number','episode_number','episode_name','air_date','runtime','synopsis','still_url'];
      when 'viewings' then v_allowed := array['title_id','viewed_at','rating','notes','venue','companions','outing_id','created_at'];
      when 'episode_watch_events' then v_allowed := array['episode_id','watched_at','notes','color_mode','created_at'];
      when 'episode_ratings' then v_allowed := array['episode_id','rating','rated_at'];
      when 'episode_reviews' then v_allowed := array['episode_id','review_text','reviewed_at','color_mode'];
      when 'cinema_outings' then v_allowed := array['title_id','showtime','previews_minutes','runtime_minutes','ends_at','venue','companions','format','ticket_price','seat','auditorium','seat_row','seats','booking_ref','ticket_image_path','ticket_barcode_payload','ticket_barcode_format','notes','status','previous_status','completed_viewing_id','follow_up_dismissed_at','created_at'];
      when 'external_title_links' then v_keys := array['provider','external_id']; v_allowed := array['title_id'];
      when 'lists' then v_allowed := array['name','description','created_at'];
      when 'list_items' then v_keys := array['list_id','title_id']; v_allowed := array['position','added_at'];
      when 'user_title_pins' then v_keys := array['title_id','easter_egg_key']; v_allowed := array['pinned_variant'];
      when 'user_prefs' then v_keys := array[]::text[]; v_allowed := array['ledger_layout'];
      when 'title_cast' then v_keys := array['title_id','tmdb_person_id']; v_allowed := array['name','character_name','episode_count','profile_url','cast_order'];
      when 'title_crew' then v_keys := array['title_id','tmdb_person_id','job']; v_allowed := array['name','department','profile_url'];
      when 'season_cast' then v_keys := array['season_id','tmdb_person_id']; v_allowed := array['title_id','name','character_name','episode_count','profile_url','cast_order'];
      when 'episode_crew' then v_keys := array['episode_id','tmdb_person_id','job']; v_allowed := array['title_id','name'];
      else raise exception 'Unsupported library entity' using errcode = '22023';
    end case;
    if v_action = 'put' and v_table not in ('user_title_pins','user_prefs') then
      raise exception 'Put is restricted to singleton preferences' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_table in ('title_cast','title_crew') and v_key ? 'title_id' and not v_key ? 'tmdb_person_id' then v_keys := array['title_id']; end if;
    if v_action = 'delete' and v_table = 'season_cast' and v_key ? 'season_id' and not v_key ? 'tmdb_person_id' then v_keys := array['season_id']; end if;
    if v_action = 'delete' and v_table = 'episode_crew' and v_key ? 'episode_id' and not v_key ? 'tmdb_person_id' then v_keys := array['episode_id']; end if;
    select coalesce(array_agg(k order by k), array[]::text[]) into v_actual_keys from jsonb_object_keys(v_key) k;
    if not (v_actual_keys @> v_keys and v_keys @> v_actual_keys) or
       exists(select 1 from jsonb_each(v_key) e where e.value = 'null'::jsonb or jsonb_typeof(e.value) not in ('number','string')) or
       exists(select 1 from jsonb_object_keys(v_values) k where not k = any(v_allowed)) then
      raise exception 'Unsupported library fields or identity' using errcode = '22023';
    end if;
    if v_action in ('update','put') and exists(select 1 from jsonb_object_keys(v_values) k
      where k in ('title_id','episode_id','season_id','season_number','episode_number','created_at','added_at')) then
      raise exception 'Parent and creation fields are immutable' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_values <> '{}'::jsonb then raise exception 'Delete has no values' using errcode = '22023'; end if;
    v_where := 'r.user_id = $2';
    foreach v_column in array v_keys loop
      v_where := v_where || format(' and r.%I = (jsonb_populate_record(null::public.%I,$1)).%I', v_column, v_table, v_column);
    end loop;
    v_existing := null;
    execute format('select to_jsonb(r) from public.%I r where %s limit 1 for update', v_table, v_where)
      into v_existing using v_key, v_owner;
    if v_op ? 'expectedUpdatedAt' and (v_existing is null or (v_existing->>'updated_at')::timestamptz is distinct from (v_op->>'expectedUpdatedAt')::timestamptz) then
      raise exception 'Library record changed on another device' using errcode = '40001';
    end if;
    if v_action = 'delete' then
      execute format('delete from public.%I r where %s', v_table, v_where) using v_key, v_owner;
      v_rows := v_rows || jsonb_build_array(jsonb_build_object('table',v_table,'key',v_key,'deleted',true));
      continue;
    end if;
    if v_action = 'update' and v_existing is null then raise exception 'Library record no longer exists' using errcode = 'P0002'; end if;
    if v_action = 'insert' and v_existing is not null then
      foreach v_column in array array['title_id','episode_id','season_id','season_number','episode_number','tmdb_id','type'] loop
        if v_values ? v_column and v_values->v_column is distinct from v_existing->v_column then
          raise exception 'Library identity reused for another record' using errcode='23505';
        end if;
      end loop;
    end if;
    v_row := coalesce(v_existing, '{}'::jsonb) || v_values || v_key || jsonb_build_object('user_id',v_owner);

    -- Parent ownership is checked even where historical RLS only checked the
    -- child's user_id. A caller cannot attach their row to another user's graph.
    if v_row ? 'title_id' then
      v_parent := (v_row->>'title_id')::uuid;
      if not exists(select 1 from public.titles where id=v_parent and user_id=v_owner) then raise exception 'Title ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'episode_id' then
      v_parent := (v_row->>'episode_id')::uuid;
      if not exists(select 1 from public.episodes where id=v_parent and user_id=v_owner and
        (not v_row ? 'title_id' or title_id=(v_row->>'title_id')::uuid)) then raise exception 'Episode ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'season_id' then
      v_parent := (v_row->>'season_id')::uuid;
      if not exists(select 1 from public.seasons where id=v_parent and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Season ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'list_id' and not exists(select 1 from public.lists where id=(v_row->>'list_id')::uuid and user_id=v_owner) then raise exception 'List ownership required' using errcode='42501'; end if;
    if v_table='episodes' and not exists(select 1 from public.seasons where title_id=(v_row->>'title_id')::uuid and season_number=(v_row->>'season_number')::integer and user_id=v_owner) then raise exception 'Episode season required' using errcode='23503'; end if;
    if v_row->>'outing_id' is not null and not exists(select 1 from public.cinema_outings where id=(v_row->>'outing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Outing ownership required' using errcode='42501'; end if;
    if v_row->>'completed_viewing_id' is not null and not exists(select 1 from public.viewings where id=(v_row->>'completed_viewing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Viewing ownership required' using errcode='42501'; end if;

    if v_action = 'update' or (v_action='put' and v_existing is not null) then
      select string_agg(format('%I = p.%I', k, k), ',') into v_assign from jsonb_object_keys(v_values) k;
      if v_assign is not null then
        execute format('update public.%I r set %s from jsonb_populate_record(null::public.%I,$3) p where %s returning to_jsonb(r)',v_table,v_assign,v_table,v_where)
          into v_row using v_key,v_owner,v_values;
      else v_row := v_existing; end if;
    elsif v_existing is not null then
      -- An insert retry must not undo an edit made after the original insert.
      v_row := v_existing;
    else
      v_row := v_values || v_key || jsonb_build_object('user_id',v_owner);
      select string_agg(format('%I',k),','),string_agg(format('p.%I',k),',') into v_columns,v_select from jsonb_object_keys(v_row) k;
      v_conflict := 'do nothing';
      if v_action='put' then
        select string_agg(format('%I = excluded.%I',k,k),',') into v_assign from jsonb_object_keys(v_values) k;
        if v_assign is null then raise exception 'Put requires values' using errcode='22023'; end if;
        select string_agg(format('%I',k),',') into v_conflict from unnest(array['user_id'] || v_keys) k;
        v_conflict := '(' || v_conflict || ') do update set ' || v_assign;
      end if;
      execute format('insert into public.%I (%s) select %s from jsonb_populate_record(null::public.%I,$1) p on conflict %s returning to_jsonb(%I)',v_table,v_columns,v_select,v_table,v_conflict,v_table)
        into v_row using v_row;
      if v_row is null then
        execute format('select to_jsonb(r) from public.%I r where %s',v_table,v_where) into v_row using v_key,v_owner;
        if v_row is null then raise exception 'Library identity conflicts with an existing record' using errcode='23505'; end if;
      end if;
    end if;
    v_rows := v_rows || jsonb_build_array(jsonb_build_object('table',v_table,'key',v_key,'row',v_row));
  end loop;
  v_result := jsonb_build_object('operationId',p_operation_id,'rows',v_rows);
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=v_owner and operation_id=p_operation_id;
  return v_result;
end;
$$;

-- Shared library RPC (20261008180000)
-- Shared library RPC (20261008180000)
-- Replaces the session-GUC share-link mechanism (set_shared_token ->
-- set_config('app.shared_token', ..., false) + RLS reading it) with ONE
-- stateless RPC, get_shared_library(p_token, p_offset, p_limit).
--
-- Why: the GUC lives on a pooled Postgres connection, so the token can be
-- missing for the follow-up read or LEAK to an unrelated later request.
-- After this migration NO RLS policy or function consults app.shared_token:
-- a stale/pooled GUC grants nothing. Friend (is_friend + share_scopes) and
-- owner access are unchanged.
--
-- ROLLOUT: web and Android must ship the get_shared_library client in the
-- same release. Old clients' set_shared_token call now raises 42501 (see
-- docs/android-contracts/shared-library-rpc.md).
--
-- Idempotent: safe to re-run (create or replace / if not exists / drop if exists).

-- ------------------------------------------------------------
-- 1. Non-exposed schema for the SECURITY DEFINER implementation.
--    Kept separate from cinemarchive_private (whose USAGE is deliberately
--    withheld from anon) because the INVOKER wrapper below runs as the
--    caller and anonymous share-link visitors must be able to reach it.
--    Do NOT add this schema to PostgREST's exposed schemas (db-schemas).
-- ------------------------------------------------------------
create schema if not exists cinemarchive_sharing;
revoke all on schema cinemarchive_sharing from public;
grant usage on schema cinemarchive_sharing to anon, authenticated;

-- ------------------------------------------------------------
-- 2. Remove every RLS dependence on app.shared_token.
-- ------------------------------------------------------------

-- 2a0. is_friend: same logic, now with an empty search_path. REQUIRED because
--      can_view_title (below) has an empty search_path and a function without
--      its own SET inherits the caller's, which would break the unqualified
--      `friendships` reference.
create or replace function public.is_friend(user_a uuid, user_b uuid)
returns boolean
language sql security definer stable set search_path = '' as $$
  select exists (
    select 1 from public.friendships
    where user_id_a = least(user_a, user_b)
      and user_id_b = greatest(user_a, user_b)
      and status = 'accepted'
  );
$$;

-- 2a. can_view_title: friend branch only. Same signature, so the ~20
--     "shared/friend read" policies keep working untouched. Hardened with an
--     empty search_path (every reference qualified).
create or replace function public.can_view_title(
  p_owner_user_id uuid,
  p_genres text[],
  p_status public.watch_status
) returns boolean
language sql security definer stable set search_path = '' as $$
  select
    public.is_friend(auth.uid(), p_owner_user_id)
    and public.title_in_scope(
      p_genres,
      p_status,
      (select s.allowed_genres from public.share_scopes s
        where s.friend_user_id = auth.uid() and s.owner_user_id = p_owner_user_id),
      (select s.allowed_statuses from public.share_scopes s
        where s.friend_user_id = auth.uid() and s.owner_user_id = p_owner_user_id)
    );
$$;

-- 2b. user_prefs: the token-based read policy is removed (the RPC returns the
--     owner's ledger_layout instead). The "friend read" policy stays.
drop policy if exists "user_prefs: shared key read" on public.user_prefs;

-- 2c. is_valid_shared_token is no longer referenced by any policy. Keep the
--     function (harmless, avoids drop-dependency surprises on drifted DBs) but
--     remove it from the API surface.
revoke all on function public.is_valid_shared_token(text, uuid) from public, anon, authenticated;

-- 2d. set_shared_token: compatibility stub. Never touches the session GUC and
--     reads/writes no tables. It FAILS LOUDLY (42501) instead of silently
--     succeeding: a silent no-op would make an old client proceed to read
--     titles, get an empty library, and show a misleading "empty share"; and a
--     touch-only variant would give an unauthenticated token oracle plus
--     notification side-effects without any benefit. Grants are kept so old
--     clients get this clear error rather than a 404 "function not found".
create or replace function public.set_shared_token(token text)
returns void
language plpgsql security invoker set search_path = '' as $$
begin
  raise exception 'set_shared_token has been retired; call get_shared_library(p_token) instead'
    using errcode = '42501',
          hint = 'Update the client: shared links are now read through the stateless get_shared_library RPC.';
end;
$$;
revoke all on function public.set_shared_token(text) from public;
grant execute on function public.set_shared_token(text) to anon, authenticated;

-- 2e. shared_key_owner: stateless already (takes the token as an argument), so
--     it is KEPT for the rollout window (cached clients) but hardened
--     (empty search_path). Deprecated: get_shared_library returns ownerUserId.
--     Drop it in a later migration once no client calls it.
create or replace function public.shared_key_owner(token_val text)
returns uuid language sql security definer stable set search_path = '' as $$
  select k.user_id from public.shared_access_keys k
  where k.token = token_val
    and k.is_active = true
    and (k.expires_at is null or k.expires_at > now())
  limit 1;
$$;
revoke all on function public.shared_key_owner(text) from public;
grant execute on function public.shared_key_owner(text) to anon, authenticated;

-- 2f. shared_access_keys.user_id is NOT NULL with no default, and the web
--     createSharedKey() inserts without it (=> 23502 / RLS violation). Default
--     it to the caller. The RLS check (auth.uid() = user_id) still applies, so
--     nobody can mint a key for another user.
alter table public.shared_access_keys alter column user_id set default auth.uid();

-- ------------------------------------------------------------
-- 3. Private SECURITY DEFINER implementation.
-- ------------------------------------------------------------
create or replace function cinemarchive_sharing.get_shared_library(
  p_token text,
  p_offset integer default 0,
  p_limit integer default 100
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_key       public.shared_access_keys%rowtype;
  v_genres    text[];
  v_statuses  public.watch_status[];
  v_offset    integer := greatest(coalesce(p_offset, 0), 0);
  v_limit     integer := least(greatest(coalesce(p_limit, 100), 1), 200);
  v_ids       uuid[];
  v_has_more  boolean;
  v_titles    jsonb;
  v_layout    jsonb;
begin
  -- Unauthenticated-by-design entry point: every failure is the same stable
  -- error so the response is not an oracle for "exists but revoked" etc.
  if p_token is null or p_token = '' or length(p_token) > 512 then
    raise exception 'Invalid or expired share link' using errcode = '42501';
  end if;

  select * into v_key
  from public.shared_access_keys k
  where k.token = p_token
    and k.is_active = true
    and (k.expires_at is null or k.expires_at > now());
  if not found then
    raise exception 'Invalid or expired share link' using errcode = '42501';
  end if;

  -- Usage touch + throttled owner notification, first page only (later pages
  -- of one load are not new "uses"). The row lock serializes concurrent
  -- callers; the re-check under the lock sees the previous caller's
  -- last_used_at, so at most one notification per key per hour (same
  -- semantics as the old set_shared_token) and none for expired/revoked keys.
  if v_offset = 0 then
    select * into v_key
    from public.shared_access_keys k
    where k.id = v_key.id
      and k.is_active = true
      and (k.expires_at is null or k.expires_at > now())
    for update;
    if not found then
      raise exception 'Invalid or expired share link' using errcode = '42501';
    end if;

    if v_key.last_used_at is null or v_key.last_used_at < now() - interval '1 hour' then
      insert into public.notifications (recipient_id, type, payload)
      values (v_key.user_id, 'share_link_used', jsonb_build_object('label', v_key.label));
    end if;
    update public.shared_access_keys k set last_used_at = now() where k.id = v_key.id;
  end if;

  -- Link scope (no row = unrestricted).
  select s.allowed_genres, s.allowed_statuses into v_genres, v_statuses
  from public.share_scopes s where s.shared_key_id = v_key.id;

  -- Stable page of visible title ids (+1 row to compute hasMore).
  select array_agg(q.id order by q.added_at desc, q.id asc) into v_ids
  from (
    select t.id, t.added_at
    from public.titles t
    where t.user_id = v_key.user_id
      and public.title_in_scope(t.genres, t.status, v_genres, v_statuses)
    order by t.added_at desc, t.id asc
    offset v_offset limit v_limit + 1
  ) q;

  v_has_more := coalesce(cardinality(v_ids), 0) > v_limit;
  if v_has_more then
    v_ids := v_ids[1:v_limit];
  end if;

  -- Nested graph shaped like the web TITLE_SELECT (select *, all embeds), each
  -- child restricted to the owner AND the visible title. Explicit allow-list
  -- of tables: cinema_outings, lists, list_items, notifications, pins, etc.
  -- never appear.
  select coalesce(jsonb_agg(
    to_jsonb(t) || jsonb_build_object(
      'title_cast', coalesce((select jsonb_agg(to_jsonb(c) order by c.cast_order, c.id)
          from public.title_cast c where c.title_id = t.id and c.user_id = t.user_id), '[]'::jsonb),
      'title_crew', coalesce((select jsonb_agg(to_jsonb(c) order by c.id)
          from public.title_crew c where c.title_id = t.id and c.user_id = t.user_id), '[]'::jsonb),
      'seasons', coalesce((select jsonb_agg(
            to_jsonb(s) || jsonb_build_object('season_cast', coalesce((
              select jsonb_agg(to_jsonb(sc) order by sc.cast_order, sc.id)
              from public.season_cast sc
              where sc.season_id = s.id and sc.title_id = t.id and sc.user_id = t.user_id), '[]'::jsonb))
            order by s.season_number, s.id)
          from public.seasons s where s.title_id = t.id and s.user_id = t.user_id), '[]'::jsonb),
      'viewings', coalesce((select jsonb_agg(to_jsonb(v) order by v.viewed_at desc nulls last, v.id)
          from public.viewings v where v.title_id = t.id and v.user_id = t.user_id), '[]'::jsonb),
      'episodes', coalesce((select jsonb_agg(
            to_jsonb(e) || jsonb_build_object(
              'episode_crew', coalesce((select jsonb_agg(to_jsonb(x) order by x.id)
                  from public.episode_crew x
                  where x.episode_id = e.id and x.title_id = t.id and x.user_id = t.user_id), '[]'::jsonb),
              'episode_watch_events', coalesce((select jsonb_agg(to_jsonb(x) order by x.created_at, x.id)
                  from public.episode_watch_events x
                  where x.episode_id = e.id and x.user_id = t.user_id), '[]'::jsonb),
              'episode_ratings', coalesce((select jsonb_agg(to_jsonb(x) order by x.rated_at, x.id)
                  from public.episode_ratings x
                  where x.episode_id = e.id and x.user_id = t.user_id), '[]'::jsonb),
              'episode_reviews', coalesce((select jsonb_agg(to_jsonb(x) order by x.reviewed_at, x.id)
                  from public.episode_reviews x
                  where x.episode_id = e.id and x.user_id = t.user_id), '[]'::jsonb))
            order by e.season_number, e.episode_number, e.id)
          from public.episodes e where e.title_id = t.id and e.user_id = t.user_id), '[]'::jsonb)
    ) order by u.ord
  ), '[]'::jsonb) into v_titles
  from unnest(coalesce(v_ids, '{}'::uuid[])) with ordinality as u(id, ord)
  join public.titles t on t.id = u.id;

  select p.ledger_layout into v_layout
  from public.user_prefs p where p.user_id = v_key.user_id;

  return jsonb_build_object(
    'ownerUserId', v_key.user_id,
    'titles',      v_titles,
    'ledgerLayout', v_layout,   -- SQL NULL -> JSON null
    'hasMore',     v_has_more
  );
end;
$$;
revoke all on function cinemarchive_sharing.get_shared_library(text, integer, integer) from public;
grant execute on function cinemarchive_sharing.get_shared_library(text, integer, integer) to anon, authenticated;

-- ------------------------------------------------------------
-- 4. Public SECURITY INVOKER wrapper (the only thing PostgREST exposes).
-- ------------------------------------------------------------
create or replace function public.get_shared_library(
  p_token text,
  p_offset integer default 0,
  p_limit integer default 100
) returns jsonb
language sql security invoker set search_path = ''
as $$ select cinemarchive_sharing.get_shared_library(p_token, p_offset, p_limit); $$;
revoke all on function public.get_shared_library(text, integer, integer) from public;
grant execute on function public.get_shared_library(text, integer, integer) to anon, authenticated;

-- Library command import capacity (20261008180456)
-- Keep one imported title graph atomic, including long-running TV series.
-- Accumulate in PostgreSQL arrays to avoid repeatedly copying growing JSONB.
-- Bound operation count and PostgreSQL JSONB text size before any writes.
create or replace function cinemarchive_private.apply_library_command(
  p_operation_id uuid, p_operations jsonb
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_owner uuid := auth.uid();
  v_receipt cinemarchive_private.library_command_receipts%rowtype;
  v_op jsonb;
  v_table text;
  v_action text;
  v_key jsonb;
  v_values jsonb;
  v_row jsonb;
  v_existing jsonb;
  v_allowed text[];
  v_keys text[];
  v_actual_keys text[];
  v_column text;
  v_where text;
  v_columns text;
  v_select text;
  v_assign text;
  v_conflict text;
  v_parent uuid;
  v_rows jsonb[] := ARRAY[]::jsonb[];
  v_result jsonb;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if p_operation_id is null or jsonb_typeof(p_operations) is distinct from 'array'
     or jsonb_array_length(p_operations) not between 1 and 50000
     or octet_length(p_operations::text) > 16777216 then
    raise exception 'Invalid library command' using errcode = '22023';
  end if;

  -- Concurrent retries serialize on this primary key. Failure anywhere below
  -- rolls back the placeholder along with every mutation in the command.
  insert into cinemarchive_private.library_command_receipts(user_id, operation_id, operations)
    values (v_owner, p_operation_id, p_operations) on conflict do nothing;
  select * into strict v_receipt from cinemarchive_private.library_command_receipts
    where user_id = v_owner and operation_id = p_operation_id for update;
  if v_receipt.operations <> p_operations then
    raise exception 'Operation ID reused with different data' using errcode = '22023';
  end if;
  if v_receipt.result <> '{}'::jsonb then return v_receipt.result; end if;

  for v_op in select value from jsonb_array_elements(p_operations) loop
    if jsonb_typeof(v_op) is distinct from 'object' or
       exists(select 1 from jsonb_object_keys(v_op) k where k not in ('table','action','key','values','expectedUpdatedAt')) then
      raise exception 'Invalid command operation' using errcode = '22023';
    end if;
    v_table := v_op->>'table';
    v_action := v_op->>'action';
    v_key := v_op->'key';
    v_values := coalesce(v_op->'values', '{}'::jsonb);
    if v_action is null or v_action not in ('insert','update','delete','put') or
       jsonb_typeof(v_key) is distinct from 'object' or jsonb_typeof(v_values) is distinct from 'object' then
      raise exception 'Invalid command action or fields' using errcode = '22023';
    end if;
    v_keys := array['id'];
    -- This is an explicit API allowlist, not arbitrary SQL/table access. Owner
    -- identity is always injected from auth.uid(), never accepted from JSON.
    case v_table
      when 'titles' then v_allowed := array['tmdb_id','type','title','year','director','genres','poster_url','backdrop_url','synopsis','runtime','network','status','rating','notes','tags','imdb_rating','rt_score','metacritic_score','studios','added_at','release_date','original_language','content_rating','imdb_id','rt_url','awards_count','bechdel_outcome','bechdel_score','custom_watch_url','in_home_collection','physical_media','collection_id','collection_name'];
      when 'seasons' then v_allowed := array['title_id','season_number','episode_count','episodes_watched','air_year'];
      when 'episodes' then v_allowed := array['title_id','season_number','episode_number','episode_name','air_date','runtime','synopsis','still_url'];
      when 'viewings' then v_allowed := array['title_id','viewed_at','rating','notes','venue','companions','outing_id','created_at'];
      when 'episode_watch_events' then v_allowed := array['episode_id','watched_at','notes','color_mode','created_at'];
      when 'episode_ratings' then v_allowed := array['episode_id','rating','rated_at'];
      when 'episode_reviews' then v_allowed := array['episode_id','review_text','reviewed_at','color_mode'];
      when 'cinema_outings' then v_allowed := array['title_id','showtime','previews_minutes','runtime_minutes','ends_at','venue','companions','format','ticket_price','seat','auditorium','seat_row','seats','booking_ref','ticket_image_path','ticket_barcode_payload','ticket_barcode_format','notes','status','previous_status','completed_viewing_id','follow_up_dismissed_at','created_at'];
      when 'external_title_links' then v_keys := array['provider','external_id']; v_allowed := array['title_id'];
      when 'lists' then v_allowed := array['name','description','created_at'];
      when 'list_items' then v_keys := array['list_id','title_id']; v_allowed := array['position','added_at'];
      when 'user_title_pins' then v_keys := array['title_id','easter_egg_key']; v_allowed := array['pinned_variant'];
      when 'user_prefs' then v_keys := array[]::text[]; v_allowed := array['ledger_layout'];
      when 'title_cast' then v_keys := array['title_id','tmdb_person_id']; v_allowed := array['name','character_name','episode_count','profile_url','cast_order'];
      when 'title_crew' then v_keys := array['title_id','tmdb_person_id','job']; v_allowed := array['name','department','profile_url'];
      when 'season_cast' then v_keys := array['season_id','tmdb_person_id']; v_allowed := array['title_id','name','character_name','episode_count','profile_url','cast_order'];
      when 'episode_crew' then v_keys := array['episode_id','tmdb_person_id','job']; v_allowed := array['title_id','name'];
      else raise exception 'Unsupported library entity' using errcode = '22023';
    end case;
    if v_action = 'put' and v_table not in ('user_title_pins','user_prefs') then
      raise exception 'Put is restricted to singleton preferences' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_table in ('title_cast','title_crew') and v_key ? 'title_id' and not v_key ? 'tmdb_person_id' then v_keys := array['title_id']; end if;
    if v_action = 'delete' and v_table = 'season_cast' and v_key ? 'season_id' and not v_key ? 'tmdb_person_id' then v_keys := array['season_id']; end if;
    if v_action = 'delete' and v_table = 'episode_crew' and v_key ? 'episode_id' and not v_key ? 'tmdb_person_id' then v_keys := array['episode_id']; end if;
    select coalesce(array_agg(k order by k), array[]::text[]) into v_actual_keys from jsonb_object_keys(v_key) k;
    if not (v_actual_keys @> v_keys and v_keys @> v_actual_keys) or
       exists(select 1 from jsonb_each(v_key) e where e.value = 'null'::jsonb or jsonb_typeof(e.value) not in ('number','string')) or
       exists(select 1 from jsonb_object_keys(v_values) k where not k = any(v_allowed)) then
      raise exception 'Unsupported library fields or identity' using errcode = '22023';
    end if;
    if v_action in ('update','put') and exists(select 1 from jsonb_object_keys(v_values) k
      where k in ('title_id','episode_id','season_id','season_number','episode_number','created_at','added_at')) then
      raise exception 'Parent and creation fields are immutable' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_values <> '{}'::jsonb then raise exception 'Delete has no values' using errcode = '22023'; end if;
    v_where := 'r.user_id = $2';
    foreach v_column in array v_keys loop
      v_where := v_where || format(' and r.%I = (jsonb_populate_record(null::public.%I,$1)).%I', v_column, v_table, v_column);
    end loop;
    v_existing := null;
    execute format('select to_jsonb(r) from public.%I r where %s limit 1 for update', v_table, v_where)
      into v_existing using v_key, v_owner;
    if v_op ? 'expectedUpdatedAt' and (v_existing is null or (v_existing->>'updated_at')::timestamptz is distinct from (v_op->>'expectedUpdatedAt')::timestamptz) then
      raise exception 'Library record changed on another device' using errcode = '40001';
    end if;
    if v_action = 'delete' then
      execute format('delete from public.%I r where %s', v_table, v_where) using v_key, v_owner;
      v_rows := array_append(v_rows, jsonb_build_object('table',v_table,'key',v_key,'deleted',true));
      continue;
    end if;
    if v_action = 'update' and v_existing is null then raise exception 'Library record no longer exists' using errcode = 'P0002'; end if;
    if v_action = 'insert' and v_existing is not null then
      foreach v_column in array array['title_id','episode_id','season_id','season_number','episode_number','tmdb_id','type'] loop
        if v_values ? v_column and v_values->v_column is distinct from v_existing->v_column then
          raise exception 'Library identity reused for another record' using errcode='23505';
        end if;
      end loop;
    end if;
    v_row := coalesce(v_existing, '{}'::jsonb) || v_values || v_key || jsonb_build_object('user_id',v_owner);

    -- Parent ownership is checked even where historical RLS only checked the
    -- child's user_id. A caller cannot attach their row to another user's graph.
    if v_row ? 'title_id' then
      v_parent := (v_row->>'title_id')::uuid;
      if not exists(select 1 from public.titles where id=v_parent and user_id=v_owner) then raise exception 'Title ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'episode_id' then
      v_parent := (v_row->>'episode_id')::uuid;
      if not exists(select 1 from public.episodes where id=v_parent and user_id=v_owner and
        (not v_row ? 'title_id' or title_id=(v_row->>'title_id')::uuid)) then raise exception 'Episode ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'season_id' then
      v_parent := (v_row->>'season_id')::uuid;
      if not exists(select 1 from public.seasons where id=v_parent and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Season ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'list_id' and not exists(select 1 from public.lists where id=(v_row->>'list_id')::uuid and user_id=v_owner) then raise exception 'List ownership required' using errcode='42501'; end if;
    if v_table='episodes' and not exists(select 1 from public.seasons where title_id=(v_row->>'title_id')::uuid and season_number=(v_row->>'season_number')::integer and user_id=v_owner) then raise exception 'Episode season required' using errcode='23503'; end if;
    if v_row->>'outing_id' is not null and not exists(select 1 from public.cinema_outings where id=(v_row->>'outing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Outing ownership required' using errcode='42501'; end if;
    if v_row->>'completed_viewing_id' is not null and not exists(select 1 from public.viewings where id=(v_row->>'completed_viewing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Viewing ownership required' using errcode='42501'; end if;

    if v_action = 'update' or (v_action='put' and v_existing is not null) then
      select string_agg(format('%I = p.%I', k, k), ',') into v_assign from jsonb_object_keys(v_values) k;
      if v_assign is not null then
        execute format('update public.%I r set %s from jsonb_populate_record(null::public.%I,$3) p where %s returning to_jsonb(r)',v_table,v_assign,v_table,v_where)
          into v_row using v_key,v_owner,v_values;
      else v_row := v_existing; end if;
    elsif v_existing is not null then
      -- An insert retry must not undo an edit made after the original insert.
      v_row := v_existing;
    else
      v_row := v_values || v_key || jsonb_build_object('user_id',v_owner);
      select string_agg(format('%I',k),','),string_agg(format('p.%I',k),',') into v_columns,v_select from jsonb_object_keys(v_row) k;
      v_conflict := 'do nothing';
      if v_action='put' then
        select string_agg(format('%I = excluded.%I',k,k),',') into v_assign from jsonb_object_keys(v_values) k;
        if v_assign is null then raise exception 'Put requires values' using errcode='22023'; end if;
        select string_agg(format('%I',k),',') into v_conflict from unnest(array['user_id'] || v_keys) k;
        v_conflict := '(' || v_conflict || ') do update set ' || v_assign;
      end if;
      execute format('insert into public.%I (%s) select %s from jsonb_populate_record(null::public.%I,$1) p on conflict %s returning to_jsonb(%I)',v_table,v_columns,v_select,v_table,v_conflict,v_table)
        into v_row using v_row;
      if v_row is null then
        execute format('select to_jsonb(r) from public.%I r where %s',v_table,v_where) into v_row using v_key,v_owner;
        if v_row is null then raise exception 'Library identity conflicts with an existing record' using errcode='23505'; end if;
      end if;
    end if;
    v_rows := array_append(v_rows, jsonb_build_object('table',v_table,'key',v_key,'row',v_row));
  end loop;
  v_result := jsonb_build_object('operationId',p_operation_id,'rows',v_rows);
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=v_owner and operation_id=p_operation_id;
  return v_result;
end;
$$;

-- Causal library commands (20261008171852)
-- A queued patch may depend on the immutable result of an earlier command.
-- Resolve that receipt's revision inside the same transaction as the write,
-- without changing the client's persisted payload after an unknown outcome.
create or replace function cinemarchive_private.apply_causal_library_command(
  p_operation_id uuid, p_operations jsonb
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid := auth.uid();
  v_op jsonb;
  v_expected text;
  v_operations jsonb[] := ARRAY[]::jsonb[];
begin
  if v_owner is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if jsonb_typeof(p_operations) is distinct from 'array'
    or jsonb_array_length(p_operations) not between 1 and 50000
    or octet_length(p_operations::text) > 16777216 then
    raise exception 'Invalid library command' using errcode='22023';
  end if;
  for v_op in select value from jsonb_array_elements(p_operations) loop
    if v_op ? 'expectedOperationId' then
      if v_op ? 'expectedUpdatedAt' or v_op->>'action' not in ('update','delete')
        or jsonb_typeof(v_op->'expectedOperationId') is distinct from 'string'
        or (v_op->>'expectedOperationId')::uuid = p_operation_id then
        raise exception 'Invalid causal revision precondition' using errcode='22023';
      end if;
      v_expected := null;
      -- The last effect on this exact row is authoritative when a compound
      -- command touches it more than once. Another owner's receipt is invisible.
      select effect.value->'row'->>'updated_at' into v_expected
      from cinemarchive_private.library_command_receipts receipt
      cross join lateral jsonb_array_elements(receipt.result->'rows') with ordinality effect(value, ordinal)
      where receipt.user_id=v_owner
        and receipt.operation_id=(v_op->>'expectedOperationId')::uuid
        and effect.value->>'table'=v_op->>'table'
        and effect.value->'key'=v_op->'key'
      order by effect.ordinal desc limit 1;
      if v_expected is null then
        raise exception 'The preceding library change has no matching revision' using errcode='40001';
      end if;
      v_op := jsonb_set(v_op - 'expectedOperationId', '{expectedUpdatedAt}', to_jsonb(v_expected));
    end if;
    v_operations := array_append(v_operations,v_op);
  end loop;
  return cinemarchive_private.apply_library_command(p_operation_id,to_jsonb(v_operations));
end;
$$;
revoke all on function cinemarchive_private.apply_causal_library_command(uuid,jsonb) from public, anon;
grant execute on function cinemarchive_private.apply_causal_library_command(uuid,jsonb) to authenticated;

create or replace function public.apply_library_command(p_operation_id uuid,p_operations jsonb)
returns jsonb language sql security invoker set search_path = ''
as $$ select cinemarchive_private.apply_causal_library_command(p_operation_id,p_operations); $$;
revoke all on function public.apply_library_command(uuid,jsonb) from public, anon;
grant execute on function public.apply_library_command(uuid,jsonb) to authenticated;

-- Scoped title social reads (20261008180925)
-- NULL auth IDs must never bypass a friends-only predicate. Social reads
-- follow the same title scope as the library, including blocked relationships.
create or replace function cinemarchive_private.list_title_comments(p_title_id uuid)
returns table (id uuid, author_id uuid, body text, created_at timestamptz, display_name text, username text)
language sql security definer stable set search_path = '' as $$
  select c.id, c.author_id, c.body, c.created_at, p.display_name, p.username
  from public.title_comments c
  join public.titles t on t.id = c.title_id
  join public.profiles p on p.user_id = c.author_id
  where t.id = p_title_id and auth.uid() is not null
    and (t.user_id = auth.uid() or public.can_view_title(t.user_id, t.genres, t.status))
  order by c.created_at, c.id;
$$;
revoke all on function cinemarchive_private.list_title_comments(uuid) from public, anon;
grant execute on function cinemarchive_private.list_title_comments(uuid) to authenticated;

create or replace function public.list_title_comments(p_title_id uuid)
returns table (id uuid, author_id uuid, body text, created_at timestamptz, display_name text, username text)
language sql security invoker stable set search_path = '' as $$
  select * from cinemarchive_private.list_title_comments(p_title_id);
$$;
revoke all on function public.list_title_comments(uuid) from public, anon;
grant execute on function public.list_title_comments(uuid) to authenticated;

create or replace function cinemarchive_private.list_title_reactions(p_title_id uuid)
returns table (author_id uuid, emoji text, display_name text, username text)
language sql security definer stable set search_path = '' as $$
  select r.author_id, r.emoji, p.display_name, p.username
  from public.title_reactions r
  join public.titles t on t.id = r.title_id
  join public.profiles p on p.user_id = r.author_id
  where t.id = p_title_id and auth.uid() is not null
    and (t.user_id = auth.uid() or public.can_view_title(t.user_id, t.genres, t.status))
  order by r.created_at, r.author_id;
$$;
revoke all on function cinemarchive_private.list_title_reactions(uuid) from public, anon;
grant execute on function cinemarchive_private.list_title_reactions(uuid) to authenticated;

create or replace function public.list_title_reactions(p_title_id uuid)
returns table (author_id uuid, emoji text, display_name text, username text)
language sql security invoker stable set search_path = '' as $$
  select * from cinemarchive_private.list_title_reactions(p_title_id);
$$;
revoke all on function public.list_title_reactions(uuid) from public, anon;
grant execute on function public.list_title_reactions(uuid) to authenticated;

-- Library discovery sync fields (20261008183232)
-- Expose existing tag, studio and franchise metadata to native library filters.
-- Preserve owner isolation, watch notes, incremental ordering and pagination.
create or replace function cinemarchive_private.sync_library_changes(p_since timestamptz, p_limit integer default 500)
returns table (
  entity_type text,
  entity_id uuid,
  parent_id uuid,
  updated_at timestamptz,
  payload jsonb
)
language sql security definer stable set search_path = '' as $$
  with changes as (
    select 'title'::text as entity_type, t.id as entity_id, null::uuid as parent_id, t.updated_at as updated_at,
      jsonb_build_object(
        'id', t.id, 'tmdbId', t.tmdb_id, 'type', t.type, 'title', t.title, 'year', t.year,
        'director', t.director, 'genres', t.genres, 'posterUrl', t.poster_url,
        'backdropUrl', t.backdrop_url, 'synopsis', t.synopsis, 'runtime', t.runtime,
        'network', t.network, 'status', t.status, 'rating', t.rating, 'notes', t.notes,
        'addedAt', t.added_at, 'updatedAt', t.updated_at, 'releaseDate', t.release_date,
        'imdbRating', t.imdb_rating, 'originalLanguage', t.original_language,
        'tags', t.tags, 'studios', t.studios,
        'collectionId', t.collection_id, 'collectionName', t.collection_name
      ) as payload
    from public.titles t where t.user_id = auth.uid() and t.updated_at > p_since

    union all

    select 'season'::text, s.id, s.title_id, s.updated_at,
      jsonb_build_object(
        'id', s.id, 'titleId', s.title_id, 'seasonNumber', s.season_number,
        'episodeCount', s.episode_count, 'episodesWatched', s.episodes_watched, 'airYear', s.air_year
      )
    from public.seasons s where s.user_id = auth.uid() and s.updated_at > p_since

    union all

    select 'episode'::text, e.id, e.title_id, e.updated_at,
      jsonb_build_object(
        'id', e.id, 'titleId', e.title_id, 'seasonNumber', e.season_number,
        'episodeNumber', e.episode_number, 'episodeName', e.episode_name,
        'airDate', e.air_date, 'runtime', e.runtime,
        'synopsis', e.synopsis, 'stillUrl', e.still_url
      )
    from public.episodes e where e.user_id = auth.uid() and e.updated_at > p_since

    union all

    -- Only the columns the Android mirror holds (core/database/Entities.kt);
    -- profile_url/episode_count stay out rather than inflate a payload nothing reads.
    select 'title_cast'::text, tc.id, tc.title_id, tc.updated_at,
      jsonb_build_object(
        'id', tc.id, 'titleId', tc.title_id, 'tmdbPersonId', tc.tmdb_person_id,
        'name', tc.name, 'characterName', tc.character_name, 'castOrder', tc.cast_order
      )
    from public.title_cast tc where tc.user_id = auth.uid() and tc.updated_at > p_since

    union all

    select 'title_crew'::text, cw.id, cw.title_id, cw.updated_at,
      jsonb_build_object(
        'id', cw.id, 'titleId', cw.title_id, 'tmdbPersonId', cw.tmdb_person_id,
        'name', cw.name, 'job', cw.job, 'department', cw.department
      )
    from public.title_crew cw where cw.user_id = auth.uid() and cw.updated_at > p_since

    union all

    select 'viewing'::text, v.id, v.title_id, v.updated_at,
      jsonb_build_object(
        'id', v.id, 'titleId', v.title_id, 'date', v.viewed_at, 'rating', v.rating,
        'notes', v.notes, 'venue', v.venue, 'companions', v.companions, 'outingId', v.outing_id
      )
    from public.viewings v where v.user_id = auth.uid() and v.updated_at > p_since

    union all

    select 'episode_watch_event'::text, we.id, we.episode_id, we.updated_at,
      jsonb_build_object('id', we.id, 'episodeId', we.episode_id, 'watchedAt', we.watched_at, 'notes', we.notes)
    from public.episode_watch_events we where we.user_id = auth.uid() and we.updated_at > p_since

    union all

    select 'episode_rating'::text, er.id, er.episode_id, er.updated_at,
      jsonb_build_object('id', er.id, 'episodeId', er.episode_id, 'rating', er.rating, 'ratedAt', er.rated_at)
    from public.episode_ratings er where er.user_id = auth.uid() and er.updated_at > p_since

    union all

    select 'episode_review'::text, rv.id, rv.episode_id, rv.updated_at,
      jsonb_build_object('id', rv.id, 'episodeId', rv.episode_id, 'reviewText', rv.review_text, 'reviewedAt', rv.reviewed_at)
    from public.episode_reviews rv where rv.user_id = auth.uid() and rv.updated_at > p_since

    union all

    select 'cinema_outing'::text, co.id, co.title_id, co.updated_at,
      jsonb_build_object(
        'id', co.id, 'titleId', co.title_id, 'showtime', co.showtime,
        'previewsMinutes', co.previews_minutes, 'runtimeMinutes', co.runtime_minutes,
        'endsAt', co.ends_at, 'venue', co.venue, 'companions', co.companions,
        'format', co.format, 'ticketPrice', co.ticket_price, 'seat', co.seat,
        'auditorium', co.auditorium, 'seatRow', co.seat_row, 'seats', co.seats,
        'bookingRef', co.booking_ref, 'ticketImagePath', co.ticket_image_path,
        'ticketBarcodePayload', co.ticket_barcode_payload, 'ticketBarcodeFormat', co.ticket_barcode_format,
        'notes', co.notes, 'status', co.status,
        'previousStatus', co.previous_status, 'completedViewingId', co.completed_viewing_id,
        'followUpDismissedAt', co.follow_up_dismissed_at, 'createdAt', co.created_at,
        'updatedAt', co.updated_at
      )
    from public.cinema_outings co where co.user_id = auth.uid() and co.updated_at > p_since

    union all

    select 'list'::text, l.id, null::uuid, l.updated_at,
      jsonb_build_object(
        'id', l.id, 'name', l.name, 'description', l.description,
        'createdAt', l.created_at, 'updatedAt', l.updated_at
      )
    from public.lists l where l.user_id = auth.uid() and l.updated_at > p_since

    union all

    select 'list_item'::text, li.id, li.list_id, li.updated_at,
      jsonb_build_object(
        'id', li.id, 'listId', li.list_id, 'titleId', li.title_id,
        'position', li.position, 'addedAt', li.added_at, 'updatedAt', li.updated_at
      )
    from public.list_items li where li.user_id = auth.uid() and li.updated_at > p_since

    union all

    select 'tombstone'::text, st.entity_id, null::uuid, st.deleted_at,
      jsonb_build_object('entityType', st.entity_type)
    from public.sync_tombstones st where st.user_id = auth.uid() and st.deleted_at > p_since
  ),
  ordered as (
    select c.*, row_number() over (order by c.updated_at, c.entity_id) as rn
    from changes c
  )
  -- The limit is a floor, not a ceiling: take every row up to and including the
  -- last one sharing the limit-th row's `updated_at`, so a same-timestamp group is
  -- never split across pages. Both `updated_at` defaults are the *transaction*
  -- timestamp, so a title's whole cast lands on one microsecond, while the client's
  -- cursor is a single watermark advanced with a strict `>` — a split group would
  -- lose its tail permanently and silently
  -- (supabase/migrations/20260726000000_sync_cast_crew_and_scores.sql).
  select o.entity_type, o.entity_id, o.parent_id, o.updated_at, o.payload
  from ordered o
  where o.updated_at <= coalesce(
    (select o2.updated_at from ordered o2 where o2.rn = least(coalesce(p_limit, 500), 500)),
    'infinity'::timestamptz
  )
  order by o.updated_at, o.entity_id;
$$;

revoke all on function cinemarchive_private.sync_library_changes(timestamptz,integer) from public, anon;
grant execute on function cinemarchive_private.sync_library_changes(timestamptz,integer) to authenticated;

create or replace function public.sync_library_changes(p_since timestamptz, p_limit integer default 500)
returns table (
  entity_type text,
  entity_id uuid,
  parent_id uuid,
  updated_at timestamptz,
  payload jsonb
)
language sql security invoker stable set search_path = '' as $$
  select * from cinemarchive_private.sync_library_changes(p_since, p_limit);
$$;
revoke all on function public.sync_library_changes(timestamptz,integer) from public, anon;
grant execute on function public.sync_library_changes(timestamptz,integer) to authenticated;


-- Outing share receipts (20261008184253)
-- Immutable delivery receipts make an unknown plan-share outcome safe to retry.
create table if not exists cinemarchive_private.outing_share_receipts (
  user_id uuid not null references auth.users(id) on delete cascade,
  operation_id uuid not null,
  outing_id uuid not null,
  recipient_ids uuid[] not null,
  snapshot jsonb,
  created_at timestamptz not null default now(),
  primary key (user_id, operation_id)
);
alter table cinemarchive_private.outing_share_receipts enable row level security;
revoke all on cinemarchive_private.outing_share_receipts from public, anon, authenticated;

create or replace function cinemarchive_private.share_outing_plans(
  p_outing_id uuid, p_recipient_ids uuid[], p_operation_id uuid
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  recipients uuid[];
  receipt cinemarchive_private.outing_share_receipts;
  outing public.cinema_outings;
  t public.titles;
  recipient uuid;
  names jsonb;
  inserted integer;
  payload jsonb;
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if p_operation_id is null or p_outing_id is null then
    raise exception 'Operation and outing identifiers are required' using errcode = '22023';
  end if;
  if p_recipient_ids is null or cardinality(p_recipient_ids) = 0
     or array_position(p_recipient_ids, null) is not null then
    raise exception 'At least one valid recipient is required' using errcode = '22023';
  end if;
  select array_agg(distinct id order by id) into recipients from unnest(p_recipient_ids) id;
  insert into cinemarchive_private.outing_share_receipts(user_id, operation_id, outing_id, recipient_ids)
    values(me, p_operation_id, p_outing_id, recipients) on conflict do nothing;
  get diagnostics inserted = row_count;
  -- ON CONFLICT waits for the first transaction. A rollback lets this request
  -- insert; a committed receipt acknowledges the original immutable snapshot.
  if inserted = 0 then
    select * into receipt from cinemarchive_private.outing_share_receipts
      where user_id = me and operation_id = p_operation_id;
    if receipt.outing_id is distinct from p_outing_id or receipt.recipient_ids is distinct from recipients then
      raise exception 'Operation identifier was already used for different plans' using errcode = '22023';
    end if;
    return receipt.snapshot;
  end if;
  select * into outing from public.cinema_outings where id = p_outing_id and user_id = me;
  if outing.id is null then raise exception 'Outing not found' using errcode = '42501'; end if;
  if outing.status <> 'scheduled' or outing.ends_at <= now() then
    raise exception 'Can only share plans for an upcoming scheduled outing' using errcode = '22023';
  end if;
  select * into t from public.titles where id = outing.title_id and user_id = me;
  if t.id is null then raise exception 'Title not found' using errcode = '42501'; end if;
  -- Native legacy rows contain string names; newer clients send name objects.
  select coalesce(jsonb_agg(v.name order by v.ordinality)
      filter (where v.name is not null and btrim(v.name) <> ''), '[]'::jsonb) into names
    from (
      select ordinality, case
        when jsonb_typeof(value) = 'string' then value #>> '{}'
        when jsonb_typeof(value) = 'object' and jsonb_typeof(value -> 'name') = 'string' then value ->> 'name'
        else null end as name
      from jsonb_array_elements(outing.companions) with ordinality
    ) v;
  foreach recipient in array recipients loop
    if recipient = me or not public.is_friend(me, recipient) then
      raise exception 'Can only share plans with accepted friends' using errcode = '42501';
    end if;
  end loop;
  payload := jsonb_build_object(
        'tmdb_id', t.tmdb_id, 'type', t.type, 'title', t.title, 'year', t.year,
        'poster_url', t.poster_url, 'showtime', outing.showtime, 'ends_at', outing.ends_at,
        'venue', outing.venue, 'format', outing.format, 'seat', outing.seat, 'companions', names);
  foreach recipient in array recipients loop
    insert into public.notifications(recipient_id, type, actor_id, title_id, payload)
      values(recipient, 'outing_plans_shared', me, outing.title_id, payload);
  end loop;
  update cinemarchive_private.outing_share_receipts set snapshot = payload
    where user_id = me and operation_id = p_operation_id;
  return payload;
end;
$$;
revoke all on function cinemarchive_private.share_outing_plans(uuid, uuid[], uuid) from public, anon;
grant execute on function cinemarchive_private.share_outing_plans(uuid, uuid[], uuid) to authenticated;

create or replace function public.share_outing_plans(p_outing_id uuid, p_recipient_ids uuid[], p_operation_id uuid)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.share_outing_plans(p_outing_id, p_recipient_ids, p_operation_id);
$$;
-- Older clients retain explicit resend behavior; upgraded clients reuse an ID.
create or replace function public.share_outing_plans(p_outing_id uuid, p_recipient_ids uuid[])
returns void language sql security invoker set search_path = '' as $$
  select cinemarchive_private.share_outing_plans(p_outing_id, p_recipient_ids, gen_random_uuid());
$$;
revoke all on function public.share_outing_plans(uuid, uuid[]) from public, anon;
revoke all on function public.share_outing_plans(uuid, uuid[], uuid) from public, anon;
grant execute on function public.share_outing_plans(uuid, uuid[]) to authenticated;
grant execute on function public.share_outing_plans(uuid, uuid[], uuid) to authenticated;


-- Private ticket attachments (20261008190944)
-- Ticket bytes live in a private Storage bucket. Immutable metadata and explicit
-- association commands prevent ordinary outing edits from replaying old photos.
create table public.ticket_attachments (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  outing_id uuid not null,
  object_key text not null unique,
  mime_type text not null check (mime_type in ('image/jpeg','image/png','image/webp')),
  byte_length bigint not null check (byte_length between 1 and 20971520),
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  barcode jsonb,
  state text not null default 'prepared' check (state in ('prepared','attached','retired','deleting','deleted')),
  created_at timestamptz not null default now(),
  last_prepared_at timestamptz not null default now(),
  retired_at timestamptz,
  cleanup_claimed_at timestamptz,
  check (object_key = user_id::text || '/' || id::text || '/original')
);
create index ticket_attachments_owner_idx on public.ticket_attachments(user_id, outing_id);
create index ticket_attachments_cleanup_idx on public.ticket_attachments(state, retired_at, created_at);
alter table public.ticket_attachments enable row level security;
revoke all on public.ticket_attachments from public, anon, authenticated;
grant select on public.ticket_attachments to authenticated;
create policy "ticket attachments: owner read" on public.ticket_attachments
  for select to authenticated using (user_id = (select auth.uid()));

alter table public.cinema_outings add column ticket_attachment_id uuid references public.ticket_attachments(id);
alter table public.cinema_outings add column ticket_attachment_managed boolean not null default false;
alter table public.cinema_outings add constraint managed_ticket_reference check (ticket_attachment_id is null or ticket_attachment_managed);
create unique index cinema_outings_ticket_attachment_idx on public.cinema_outings(ticket_attachment_id)
  where ticket_attachment_id is not null;

create function cinemarchive_private.ticket_descriptor(a public.ticket_attachments)
returns jsonb language sql immutable security invoker set search_path = '' as $$
  select jsonb_build_object('id',a.id,'objectKey',a.object_key,'mimeType',a.mime_type,
    'byteLength',a.byte_length,'sha256',a.sha256,'barcode',a.barcode);
$$;
revoke all on function cinemarchive_private.ticket_descriptor(public.ticket_attachments) from public, anon, authenticated;

create function cinemarchive_private.guard_ticket_association()
returns trigger language plpgsql security invoker set search_path = '' as $$
begin
  if (tg_op = 'INSERT' and (new.ticket_attachment_id is not null or new.ticket_attachment_managed)) or
     (tg_op = 'UPDATE' and (new.ticket_attachment_id is distinct from old.ticket_attachment_id or new.ticket_attachment_managed is distinct from old.ticket_attachment_managed)) then
    -- A session variable would be spoofable. Only the table owner's definer
    -- commands (or an administrator acting as that owner) may change this field.
    if current_user <> pg_get_userbyid((select relowner from pg_class where oid='public.ticket_attachments'::regclass)) then
      raise exception 'Use the ticket attachment API' using errcode='42501';
    end if;
    if new.ticket_attachment_id is not null and not exists (
      select 1 from public.ticket_attachments a where a.id=new.ticket_attachment_id
        and a.user_id=new.user_id and a.outing_id=new.id and a.state='attached'
    ) then raise exception 'Ticket attachment ownership required' using errcode='42501'; end if;
  end if;
  return new;
end;
$$;
revoke all on function cinemarchive_private.guard_ticket_association() from public, anon, authenticated;
create trigger guard_ticket_association before insert or update of ticket_attachment_id,ticket_attachment_managed on public.cinema_outings
  for each row execute function cinemarchive_private.guard_ticket_association();

create function cinemarchive_private.retire_outing_tickets()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  update public.ticket_attachments set state='retired', retired_at=coalesce(retired_at,now())
    where outing_id=old.id and user_id=old.user_id and state in ('prepared','attached');
  return old;
end;
$$;
revoke all on function cinemarchive_private.retire_outing_tickets() from public, anon, authenticated;
create trigger retire_outing_tickets before delete on public.cinema_outings
  for each row execute function cinemarchive_private.retire_outing_tickets();

create function cinemarchive_private.prepare_ticket_attachment(p_attachment_id uuid,p_outing_id uuid,p_metadata jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  a public.ticket_attachments;
  descriptor jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_attachment_id is null or p_outing_id is null or jsonb_typeof(p_metadata) is distinct from 'object'
    or exists(select 1 from jsonb_object_keys(p_metadata) k where k not in ('mimeType','byteLength','sha256','barcode'))
    or not (p_metadata ?& array['mimeType','byteLength','sha256','barcode'])
    or jsonb_typeof(p_metadata->'mimeType') is distinct from 'string'
    or p_metadata->>'mimeType' not in ('image/jpeg','image/png','image/webp')
    or jsonb_typeof(p_metadata->'byteLength') is distinct from 'number'
    or (p_metadata->>'byteLength')::numeric not between 1 and 20971520
    or trunc((p_metadata->>'byteLength')::numeric) <> (p_metadata->>'byteLength')::numeric
    or jsonb_typeof(p_metadata->'sha256') is distinct from 'string'
    or p_metadata->>'sha256' !~ '^[0-9a-f]{64}$'
  then raise exception 'Invalid ticket metadata' using errcode='22023'; end if;
  if p_metadata->'barcode' <> 'null'::jsonb then
    if jsonb_typeof(p_metadata->'barcode') is distinct from 'object'
      or not (p_metadata->'barcode' ?& array['payload','format'])
      or exists(select 1 from jsonb_object_keys(p_metadata->'barcode') k where k not in ('payload','format'))
      or jsonb_typeof(p_metadata->'barcode'->'payload') is distinct from 'string'
      or octet_length(p_metadata->'barcode'->>'payload') not between 1 and 32768
      or jsonb_typeof(p_metadata->'barcode'->'format') is distinct from 'string'
      or p_metadata->'barcode'->>'format' not in ('QR_CODE','CODE_128','PDF_417','AZTEC','ITF','CODABAR','OTHER')
    then raise exception 'Invalid ticket barcode' using errcode='22023'; end if;
  end if;
  -- Lock the outing first, matching finalize and deletion ordering.
  perform 1 from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if not found then raise exception 'Outing ownership required' using errcode='42501'; end if;
  insert into public.ticket_attachments(id,user_id,outing_id,object_key,mime_type,byte_length,sha256,barcode)
    values(p_attachment_id,me,p_outing_id,me::text||'/'||p_attachment_id::text||'/original',
      p_metadata->>'mimeType',(p_metadata->>'byteLength')::bigint,p_metadata->>'sha256',nullif(p_metadata->'barcode','null'::jsonb))
    on conflict do nothing;
  select * into a from public.ticket_attachments where id=p_attachment_id for update;
  if a.user_id is distinct from me then raise exception 'Ticket attachment ownership required' using errcode='42501'; end if;
  descriptor := cinemarchive_private.ticket_descriptor(a);
  if a.outing_id <> p_outing_id or descriptor - 'id' - 'objectKey' <> p_metadata then
    raise exception 'Attachment identifier already used for different content' using errcode='22023';
  end if;
  if a.state='prepared' then
    update public.ticket_attachments set last_prepared_at=now() where id=a.id;
  end if;
  return jsonb_build_object('attachment',descriptor,'state',case when a.state in ('deleting','deleted') then 'retired' else a.state end);
end;
$$;

create function cinemarchive_private.get_ticket_command_receipt(p_operation_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare r cinemarchive_private.library_command_receipts;
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
  select * into r from cinemarchive_private.library_command_receipts where user_id=auth.uid() and operation_id=p_operation_id;
  if not found then return null; end if;
  if r.operations->0->>'kind' not in ('ticket.attach','ticket.detach') or not (r.operations->0 ? 'kind') then
    raise exception 'Operation identifier belongs to another command' using errcode='22023';
  end if;
  return r.result;
end;
$$;

create function cinemarchive_private.mutate_ticket_attachment(
  p_operation_id uuid,p_outing_id uuid,p_attachment_id uuid,p_expected_attachment_id uuid,p_detach boolean
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  outing public.cinema_outings;
  attachment public.ticket_attachments;
  object_metadata jsonb;
  v_result jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_operation_id is null or p_outing_id is null or p_detach is null or (not p_detach and p_attachment_id is null) or (p_detach and p_attachment_id is not null) then
    raise exception 'Invalid ticket command' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind',case when p_detach then 'ticket.detach' else 'ticket.attach' end,
    'outingId',p_outing_id,'attachmentId',p_attachment_id,'expectedAttachmentId',p_expected_attachment_id));
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations <> operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result <> '{}'::jsonb then return receipt.result; end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if not found then raise exception 'Outing ownership required' using errcode='42501'; end if;
  if outing.ticket_attachment_id is distinct from p_expected_attachment_id then
    raise exception 'Ticket changed on another device; review before replacing it' using errcode='40001'; end if;
  if not p_detach then
    select * into attachment from public.ticket_attachments where id=p_attachment_id for update;
    if not found or attachment.user_id <> me or attachment.outing_id <> p_outing_id then
      raise exception 'Ticket attachment ownership required' using errcode='42501'; end if;
    if attachment.state not in ('prepared','attached') then raise exception 'Ticket attachment is retired' using errcode='40001'; end if;
    select metadata into object_metadata from storage.objects
      where bucket_id='ticket-attachments' and name=attachment.object_key;
    if not found then raise exception 'Ticket upload is incomplete' using errcode='22023'; end if;
    if object_metadata->>'size' is distinct from attachment.byte_length::text
      or object_metadata->>'mimetype' is distinct from attachment.mime_type then
      raise exception 'Uploaded ticket does not match its metadata' using errcode='22023'; end if;
    update public.ticket_attachments set state='attached' where id=attachment.id;
  end if;
  if outing.ticket_attachment_id is not null and outing.ticket_attachment_id is distinct from p_attachment_id then
    update public.ticket_attachments set state='retired',retired_at=now() where id=outing.ticket_attachment_id;
  end if;
  update public.cinema_outings set ticket_attachment_managed=true,ticket_attachment_id=case when p_detach then null else p_attachment_id end
    where id=p_outing_id returning * into outing;
  v_result := jsonb_build_object('operationId',p_operation_id,'outingId',p_outing_id,
    'attachment',case when p_detach then null else cinemarchive_private.ticket_descriptor(attachment) end,
    'outingUpdatedAt',outing.updated_at,'request',operations->0,
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',p_outing_id),'row',to_jsonb(outing))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return v_result;
end;
$$;

create function public.prepare_ticket_attachment(p_attachment_id uuid,p_outing_id uuid,p_metadata jsonb)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.prepare_ticket_attachment(p_attachment_id,p_outing_id,p_metadata);
$$;
create function public.get_ticket_command_receipt(p_operation_id uuid)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.get_ticket_command_receipt(p_operation_id);
$$;
create function public.finalize_ticket_attachment(p_operation_id uuid,p_outing_id uuid,p_attachment_id uuid,p_expected_attachment_id uuid)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.mutate_ticket_attachment(p_operation_id,p_outing_id,p_attachment_id,p_expected_attachment_id,false);
$$;
create function public.detach_ticket_attachment(p_operation_id uuid,p_outing_id uuid,p_expected_attachment_id uuid)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.mutate_ticket_attachment(p_operation_id,p_outing_id,null,p_expected_attachment_id,true);
$$;
revoke all on function cinemarchive_private.prepare_ticket_attachment(uuid,uuid,jsonb),
  cinemarchive_private.get_ticket_command_receipt(uuid),cinemarchive_private.mutate_ticket_attachment(uuid,uuid,uuid,uuid,boolean),
  public.prepare_ticket_attachment(uuid,uuid,jsonb),public.get_ticket_command_receipt(uuid),
  public.finalize_ticket_attachment(uuid,uuid,uuid,uuid),public.detach_ticket_attachment(uuid,uuid,uuid) from public,anon;
grant execute on function cinemarchive_private.prepare_ticket_attachment(uuid,uuid,jsonb),
  cinemarchive_private.get_ticket_command_receipt(uuid),cinemarchive_private.mutate_ticket_attachment(uuid,uuid,uuid,uuid,boolean),
  public.prepare_ticket_attachment(uuid,uuid,jsonb),public.get_ticket_command_receipt(uuid),
  public.finalize_ticket_attachment(uuid,uuid,uuid,uuid),public.detach_ticket_attachment(uuid,uuid,uuid) to authenticated;

create function cinemarchive_private.can_upload_ticket_object(p_name text)
returns boolean language plpgsql volatile security definer set search_path = '' as $$
declare permitted boolean;
begin
  if auth.uid() is null then return false; end if;
  -- Hold a metadata lock through the Storage insertion transaction so cleanup
  -- cannot finish just before a delayed upload inserts its object metadata.
  select state='prepared' into permitted from public.ticket_attachments
    where object_key=p_name and user_id=auth.uid() for share;
  return coalesce(permitted,false);
end;
$$;
revoke all on function cinemarchive_private.can_upload_ticket_object(text) from public,anon;
grant execute on function cinemarchive_private.can_upload_ticket_object(text) to authenticated;

insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
  values('ticket-attachments','ticket-attachments',false,20971520,array['image/jpeg','image/png','image/webp'])
  on conflict(id) do update set public=false,file_size_limit=excluded.file_size_limit,allowed_mime_types=excluded.allowed_mime_types;
create policy "ticket bytes: prepared owner upload" on storage.objects for insert to authenticated with check (
  bucket_id='ticket-attachments' and cinemarchive_private.can_upload_ticket_object(name));
create policy "ticket bytes: owner read" on storage.objects for select to authenticated using (
  bucket_id='ticket-attachments' and exists(select 1 from public.ticket_attachments a
    where a.user_id=(select auth.uid()) and a.object_key=name and a.state in ('prepared','attached','retired')));
-- No UPDATE/DELETE policy: immutable uploads never overwrite existing bytes.
-- Retirement cleanup must claim metadata first and remove bytes through Storage API.

-- Restrictive fences prevent an unrelated pre-existing permissive bucket policy
-- from granting access to this private namespace.
create policy "ticket bytes: anonymous fence" on storage.objects as restrictive for all to anon
  using (bucket_id <> 'ticket-attachments') with check (bucket_id <> 'ticket-attachments');
create policy "ticket bytes: upload fence" on storage.objects as restrictive for insert to authenticated with check (
  bucket_id <> 'ticket-attachments' or cinemarchive_private.can_upload_ticket_object(name));
create policy "ticket bytes: read fence" on storage.objects as restrictive for select to authenticated using (
  bucket_id <> 'ticket-attachments' or exists(select 1 from public.ticket_attachments a
    where a.user_id=(select auth.uid()) and a.object_key=name and a.state in ('prepared','attached','retired')));
create policy "ticket bytes: overwrite fence" on storage.objects as restrictive for update to authenticated
  using (bucket_id <> 'ticket-attachments') with check (bucket_id <> 'ticket-attachments');
create policy "ticket bytes: deletion fence" on storage.objects as restrictive for delete to authenticated
  using (bucket_id <> 'ticket-attachments');

create function cinemarchive_private.get_outing_ticket_attachments()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode='42501'; end if;
  return (select coalesce(jsonb_agg(jsonb_build_object('outingId',o.id,
      'managed',true,'attachment',case when a.id is null then null else cinemarchive_private.ticket_descriptor(a) end) order by o.id),'[]'::jsonb)
    from public.cinema_outings o left join public.ticket_attachments a on a.id=o.ticket_attachment_id and a.user_id=auth.uid() and a.state='attached'
    where o.user_id=auth.uid() and o.ticket_attachment_managed);
end;
$$;
create function public.get_outing_ticket_attachments()
returns jsonb language sql stable security invoker set search_path = '' as $$
  select cinemarchive_private.get_outing_ticket_attachments();
$$;
revoke all on function public.get_outing_ticket_attachments(),cinemarchive_private.get_outing_ticket_attachments() from public,anon;
grant execute on function public.get_outing_ticket_attachments(),cinemarchive_private.get_outing_ticket_attachments() to authenticated;

-- Service-only cleanup claims cannot race a finalize: both lock the metadata.
-- Metadata tombstones remain after deleting bytes so an attachment UUID can
-- never be recycled for different content. The original command receipt remains.
grant usage on schema cinemarchive_private to service_role;
create function cinemarchive_private.claim_ticket_attachment_cleanup(p_limit integer)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a public.ticket_attachments; results jsonb := '[]'::jsonb;
begin
  for a in select t.* from public.ticket_attachments t where
    ((t.state='retired' and t.retired_at < now()-interval '7 days')
      or (t.state='prepared' and t.last_prepared_at < now()-interval '30 days')
      or (t.state='deleting' and t.cleanup_claimed_at < now()-interval '1 hour'))
    and not exists(select 1 from public.cinema_outings o where o.ticket_attachment_id=t.id)
    order by t.created_at limit greatest(1,least(coalesce(p_limit,100),100)) for update skip locked
  loop
    update public.ticket_attachments set state='deleting',cleanup_claimed_at=now() where id=a.id;
    results := results || jsonb_build_array(jsonb_build_object('attachmentId',a.id,'objectKey',a.object_key));
  end loop;
  return results;
end;
$$;
create function cinemarchive_private.finish_ticket_attachment_cleanup(p_attachment_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare a public.ticket_attachments;
begin
  select * into a from public.ticket_attachments where id=p_attachment_id for update;
  if not found or a.state='deleted' then return; end if;
  if a.state <> 'deleting' or exists(select 1 from public.cinema_outings where ticket_attachment_id=a.id) then
    raise exception 'Ticket cleanup was not claimed' using errcode='40001'; end if;
  if exists(select 1 from storage.objects where bucket_id='ticket-attachments' and name=a.object_key) then
    raise exception 'Remove ticket bytes through Storage API first' using errcode='22023'; end if;
  update public.ticket_attachments set state='deleted' where id=a.id;
end;
$$;
create function public.claim_ticket_attachment_cleanup(p_limit integer default 100)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.claim_ticket_attachment_cleanup(p_limit);
$$;
create function public.finish_ticket_attachment_cleanup(p_attachment_id uuid)
returns void language sql security invoker set search_path = '' as $$
  select cinemarchive_private.finish_ticket_attachment_cleanup(p_attachment_id);
$$;
revoke all on function public.claim_ticket_attachment_cleanup(integer),public.finish_ticket_attachment_cleanup(uuid),
  cinemarchive_private.claim_ticket_attachment_cleanup(integer),cinemarchive_private.finish_ticket_attachment_cleanup(uuid) from public,anon,authenticated;
grant execute on function public.claim_ticket_attachment_cleanup(integer),public.finish_ticket_attachment_cleanup(uuid),
  cinemarchive_private.claim_ticket_attachment_cleanup(integer),cinemarchive_private.finish_ticket_attachment_cleanup(uuid) to service_role;

-- Person credits sync (20261008193517)
-- Preserve all four credit sources, including Specials, for native person filters.
-- Capability marker permits an epoch backfill of unchanged pre-existing rows.
drop trigger if exists season_cast_tombstone on public.season_cast;
create trigger season_cast_tombstone before delete on public.season_cast
  for each row execute function public.record_tombstone('season_cast');
drop trigger if exists episode_crew_tombstone on public.episode_crew;
create trigger episode_crew_tombstone before delete on public.episode_crew
  for each row execute function public.record_tombstone('episode_crew');

create or replace function cinemarchive_private.sync_library_changes(p_since timestamptz, p_limit integer default 500)
returns table (
  entity_type text,
  entity_id uuid,
  parent_id uuid,
  updated_at timestamptz,
  payload jsonb
)
language sql security definer stable set search_path = '' as $$
  with changes as (
    select 'title'::text as entity_type, t.id as entity_id, null::uuid as parent_id, t.updated_at as updated_at,
      jsonb_build_object(
        'id', t.id, 'tmdbId', t.tmdb_id, 'type', t.type, 'title', t.title, 'year', t.year,
        'director', t.director, 'genres', t.genres, 'posterUrl', t.poster_url,
        'backdropUrl', t.backdrop_url, 'synopsis', t.synopsis, 'runtime', t.runtime,
        'network', t.network, 'status', t.status, 'rating', t.rating, 'notes', t.notes,
        'addedAt', t.added_at, 'updatedAt', t.updated_at, 'releaseDate', t.release_date,
        'imdbRating', t.imdb_rating, 'originalLanguage', t.original_language,
        'tags', t.tags, 'studios', t.studios,
        'collectionId', t.collection_id, 'collectionName', t.collection_name,
        'personCreditsVersion', 1
      ) as payload
    from public.titles t where t.user_id = auth.uid() and t.updated_at > p_since

    union all

    select 'season'::text, s.id, s.title_id, s.updated_at,
      jsonb_build_object(
        'id', s.id, 'titleId', s.title_id, 'seasonNumber', s.season_number,
        'episodeCount', s.episode_count, 'episodesWatched', s.episodes_watched, 'airYear', s.air_year
      )
    from public.seasons s where s.user_id = auth.uid() and s.updated_at > p_since

    union all

    select 'episode'::text, e.id, e.title_id, e.updated_at,
      jsonb_build_object(
        'id', e.id, 'titleId', e.title_id, 'seasonNumber', e.season_number,
        'episodeNumber', e.episode_number, 'episodeName', e.episode_name,
        'airDate', e.air_date, 'runtime', e.runtime,
        'synopsis', e.synopsis, 'stillUrl', e.still_url
      )
    from public.episodes e where e.user_id = auth.uid() and e.updated_at > p_since

    union all

    -- Only the columns the Android mirror holds (core/database/Entities.kt);
    -- profile_url/episode_count stay out rather than inflate a payload nothing reads.
    select 'title_cast'::text, tc.id, tc.title_id, tc.updated_at,
      jsonb_build_object(
        'id', tc.id, 'titleId', tc.title_id, 'tmdbPersonId', tc.tmdb_person_id,
        'name', tc.name, 'characterName', tc.character_name, 'castOrder', tc.cast_order
      )
    from public.title_cast tc where tc.user_id = auth.uid() and tc.updated_at > p_since

    union all

    select 'title_crew'::text, cw.id, cw.title_id, cw.updated_at,
      jsonb_build_object(
        'id', cw.id, 'titleId', cw.title_id, 'tmdbPersonId', cw.tmdb_person_id,
        'name', cw.name, 'job', cw.job, 'department', cw.department
      )
    from public.title_crew cw where cw.user_id = auth.uid() and cw.updated_at > p_since

    union all

    select 'season_cast'::text, sc.id, sc.season_id, sc.updated_at,
      jsonb_build_object(
        'id', sc.id, 'titleId', sc.title_id, 'seasonId', sc.season_id,
        'tmdbPersonId', sc.tmdb_person_id, 'name', sc.name,
        'characterName', sc.character_name, 'castOrder', sc.cast_order
      )
    from public.season_cast sc
    join public.seasons s on s.id = sc.season_id and s.title_id = sc.title_id and s.user_id = sc.user_id
    join public.titles t on t.id = sc.title_id and t.user_id = sc.user_id
    where sc.user_id = auth.uid() and sc.updated_at > p_since

    union all

    select 'episode_crew'::text, ec.id, ec.episode_id, ec.updated_at,
      jsonb_build_object(
        'id', ec.id, 'titleId', ec.title_id, 'episodeId', ec.episode_id,
        'tmdbPersonId', ec.tmdb_person_id, 'name', ec.name, 'job', ec.job
      )
    from public.episode_crew ec
    join public.episodes e on e.id = ec.episode_id and e.title_id = ec.title_id and e.user_id = ec.user_id
    join public.titles t on t.id = ec.title_id and t.user_id = ec.user_id
    where ec.user_id = auth.uid() and ec.updated_at > p_since

    union all

    select 'viewing'::text, v.id, v.title_id, v.updated_at,
      jsonb_build_object(
        'id', v.id, 'titleId', v.title_id, 'date', v.viewed_at, 'rating', v.rating,
        'notes', v.notes, 'venue', v.venue, 'companions', v.companions, 'outingId', v.outing_id
      )
    from public.viewings v where v.user_id = auth.uid() and v.updated_at > p_since

    union all

    select 'episode_watch_event'::text, we.id, we.episode_id, we.updated_at,
      jsonb_build_object('id', we.id, 'episodeId', we.episode_id, 'watchedAt', we.watched_at, 'notes', we.notes)
    from public.episode_watch_events we where we.user_id = auth.uid() and we.updated_at > p_since

    union all

    select 'episode_rating'::text, er.id, er.episode_id, er.updated_at,
      jsonb_build_object('id', er.id, 'episodeId', er.episode_id, 'rating', er.rating, 'ratedAt', er.rated_at)
    from public.episode_ratings er where er.user_id = auth.uid() and er.updated_at > p_since

    union all

    select 'episode_review'::text, rv.id, rv.episode_id, rv.updated_at,
      jsonb_build_object('id', rv.id, 'episodeId', rv.episode_id, 'reviewText', rv.review_text, 'reviewedAt', rv.reviewed_at)
    from public.episode_reviews rv where rv.user_id = auth.uid() and rv.updated_at > p_since

    union all

    select 'cinema_outing'::text, co.id, co.title_id, co.updated_at,
      jsonb_build_object(
        'id', co.id, 'titleId', co.title_id, 'showtime', co.showtime,
        'previewsMinutes', co.previews_minutes, 'runtimeMinutes', co.runtime_minutes,
        'endsAt', co.ends_at, 'venue', co.venue, 'companions', co.companions,
        'format', co.format, 'ticketPrice', co.ticket_price, 'seat', co.seat,
        'auditorium', co.auditorium, 'seatRow', co.seat_row, 'seats', co.seats,
        'bookingRef', co.booking_ref, 'ticketImagePath', co.ticket_image_path,
        'ticketBarcodePayload', co.ticket_barcode_payload, 'ticketBarcodeFormat', co.ticket_barcode_format,
        'notes', co.notes, 'status', co.status,
        'previousStatus', co.previous_status, 'completedViewingId', co.completed_viewing_id,
        'followUpDismissedAt', co.follow_up_dismissed_at, 'createdAt', co.created_at,
        'updatedAt', co.updated_at
      )
    from public.cinema_outings co where co.user_id = auth.uid() and co.updated_at > p_since

    union all

    select 'list'::text, l.id, null::uuid, l.updated_at,
      jsonb_build_object(
        'id', l.id, 'name', l.name, 'description', l.description,
        'createdAt', l.created_at, 'updatedAt', l.updated_at
      )
    from public.lists l where l.user_id = auth.uid() and l.updated_at > p_since

    union all

    select 'list_item'::text, li.id, li.list_id, li.updated_at,
      jsonb_build_object(
        'id', li.id, 'listId', li.list_id, 'titleId', li.title_id,
        'position', li.position, 'addedAt', li.added_at, 'updatedAt', li.updated_at
      )
    from public.list_items li where li.user_id = auth.uid() and li.updated_at > p_since

    union all

    select 'tombstone'::text, st.entity_id, null::uuid, st.deleted_at,
      jsonb_build_object('entityType', st.entity_type)
    from public.sync_tombstones st where st.user_id = auth.uid() and st.deleted_at > p_since
  ),
  ordered as (
    select c.*, row_number() over (order by c.updated_at, c.entity_id) as rn
    from changes c
  )
  -- The limit is a floor, not a ceiling: take every row up to and including the
  -- last one sharing the limit-th row's `updated_at`, so a same-timestamp group is
  -- never split across pages. Both `updated_at` defaults are the *transaction*
  -- timestamp, so a title's whole cast lands on one microsecond, while the client's
  -- cursor is a single watermark advanced with a strict `>` — a split group would
  -- lose its tail permanently and silently
  -- (supabase/migrations/20260726000000_sync_cast_crew_and_scores.sql).
  select o.entity_type, o.entity_id, o.parent_id, o.updated_at, o.payload
  from ordered o
  where o.updated_at <= coalesce(
    (select o2.updated_at from ordered o2 where o2.rn = least(coalesce(p_limit, 500), 500)),
    'infinity'::timestamptz
  )
  order by o.updated_at, o.entity_id;
$$;

revoke all on function cinemarchive_private.sync_library_changes(timestamptz,integer) from public, anon;
grant execute on function cinemarchive_private.sync_library_changes(timestamptz,integer) to authenticated;

create or replace function public.sync_library_changes(p_since timestamptz, p_limit integer default 500)
returns table (
  entity_type text,
  entity_id uuid,
  parent_id uuid,
  updated_at timestamptz,
  payload jsonb
)
language sql security invoker stable set search_path = '' as $$
  select * from cinemarchive_private.sync_library_changes(p_since, p_limit);
$$;
revoke all on function public.sync_library_changes(timestamptz,integer) from public, anon;
grant execute on function public.sync_library_changes(timestamptz,integer) to authenticated;

-- Saved outing review resolution (20261008194039)
-- Explicit, version-checked selection from retained legacy intent. Never creates a plan.
create function cinemarchive_private.resolve_outing_fields(
  p_outing_id uuid, p_expected_updated_at timestamptz, p_patch jsonb, p_operation_id uuid
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  outing public.cinema_outings;
  changed public.cinema_outings;
  item record;
  value jsonb;
  v_result jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_outing_id is null or p_operation_id is null or p_expected_updated_at is null
    or not isfinite(p_expected_updated_at) or p_patch is null or jsonb_typeof(p_patch)<>'object'
    or octet_length(p_patch::text)>1048576 then
    raise exception 'Invalid outing review command' using errcode='22023'; end if;
  for item in select * from jsonb_each(p_patch) loop
    if item.key in ('venue','format','seat','auditorium','seat_row','booking_ref','notes') then
      if jsonb_typeof(item.value) not in ('string','null') then
        raise exception 'Expected text or null for %',item.key using errcode='22023'; end if;
    elsif item.key='showtime' then
      if jsonb_typeof(item.value)<>'string' or not isfinite((item.value#>>'{}')::timestamptz) then
        raise exception 'Expected a finite showtime' using errcode='22023'; end if;
    elsif item.key in ('previews_minutes','runtime_minutes') then
      if jsonb_typeof(item.value)<>'number' or (item.value#>>'{}')::numeric<>trunc((item.value#>>'{}')::numeric) then
        raise exception 'Expected whole minutes' using errcode='22023'; end if;
    elsif item.key='ticket_price' then
      if jsonb_typeof(item.value) not in ('number','null') then
        raise exception 'Expected a numeric price or null' using errcode='22023'; end if;
    elsif item.key='seats' then
      if jsonb_typeof(item.value)<>'array' then raise exception 'Expected seat array' using errcode='22023'; end if;
      if exists(select 1 from jsonb_array_elements(item.value) x where jsonb_typeof(x)<>'string') then
        raise exception 'Expected seat strings' using errcode='22023'; end if;
    elsif item.key='companions' then
      if jsonb_typeof(item.value)<>'array' then raise exception 'Expected companion array' using errcode='22023'; end if;
      for value in select * from jsonb_array_elements(item.value) loop
        if jsonb_typeof(value)<>'object' or not value ? 'name' or jsonb_typeof(value->'name')<>'string'
          or length(btrim(value->>'name'))=0 then
          raise exception 'Expected named companions' using errcode='22023'; end if;
        if exists(select 1 from jsonb_object_keys(value) k where k not in ('name','friendUserId')) then
          raise exception 'Unsupported companion fields' using errcode='22023'; end if;
        if value ? 'friendUserId' and (jsonb_typeof(value->'friendUserId')<>'string'
          or value->>'friendUserId' !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') then
          raise exception 'Invalid companion identity' using errcode='22023'; end if;
      end loop;
    else raise exception 'Unsupported outing review field: %',item.key using errcode='22023';
    end if;
  end loop;
  operations := jsonb_build_array(jsonb_build_object('kind','outing.resolve','outingId',p_outing_id,
    'expectedUpdatedAt',p_expected_updated_at,'patch',p_patch));
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations<>operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result<>'{}'::jsonb then return receipt.result; end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if not found or outing.updated_at<>p_expected_updated_at then
    v_result := jsonb_build_object('status',case when outing.id is null then 'missing' else 'conflict' end,
      'operationId',p_operation_id,'request',operations->0,'outing',case when outing.id is null then null else to_jsonb(outing) end);
    -- Only applied results are receipts. A refreshed comparison starts a new operation.
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return v_result;
  end if;
  changed := jsonb_populate_record(outing,p_patch);
  if p_patch ?| array['showtime','previews_minutes','runtime_minutes'] then
    changed.ends_at := changed.showtime + make_interval(mins=>changed.previews_minutes) + make_interval(mins=>changed.runtime_minutes);
  end if;
  if to_jsonb(changed)<>to_jsonb(outing) then
    update public.cinema_outings set
      showtime=changed.showtime,previews_minutes=changed.previews_minutes,runtime_minutes=changed.runtime_minutes,ends_at=changed.ends_at,
      venue=changed.venue,format=changed.format,ticket_price=changed.ticket_price,seat=changed.seat,
      auditorium=changed.auditorium,seat_row=changed.seat_row,seats=changed.seats,
      booking_ref=changed.booking_ref,notes=changed.notes,companions=changed.companions
      where id=p_outing_id and user_id=me returning * into outing;
  end if;
  v_result := jsonb_build_object('status','applied','operationId',p_operation_id,'request',operations->0,'outing',to_jsonb(outing),
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',p_outing_id),'row',to_jsonb(outing))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return v_result;
end;
$$;
create function public.resolve_outing_fields(
  p_outing_id uuid,p_expected_updated_at timestamptz,p_patch jsonb,p_operation_id uuid
) returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.resolve_outing_fields(p_outing_id,p_expected_updated_at,p_patch,p_operation_id);
$$;
revoke all on function public.resolve_outing_fields(uuid,timestamptz,jsonb,uuid),
  cinemarchive_private.resolve_outing_fields(uuid,timestamptz,jsonb,uuid) from public,anon;
grant execute on function public.resolve_outing_fields(uuid,timestamptz,jsonb,uuid),
  cinemarchive_private.resolve_outing_fields(uuid,timestamptz,jsonb,uuid) to authenticated;

-- Canonical outing completion (20261008194606)
-- Keep identity after event deletion; never deduplicate historical independent viewings.
create table cinemarchive_private.outing_completions (
  user_id uuid not null references auth.users(id) on delete cascade,
  outing_id uuid not null,
  title_id uuid not null,
  canonical_viewing_id uuid,
  completed_title_version timestamptz,
  previous_status public.watch_status,
  created_at timestamptz not null default now(),
  primary key(user_id,outing_id)
);
revoke all on cinemarchive_private.outing_completions from public,anon,authenticated;

create function cinemarchive_private.outing_completion_current(p_receipt jsonb)
returns jsonb language plpgsql security definer stable set search_path = '' as $$
declare
  me uuid := auth.uid();
  outing public.cinema_outings;
  viewing public.viewings;
  title public.titles;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  select * into outing from public.cinema_outings where id=(p_receipt->>'outingId')::uuid and user_id=me;
  if outing.id is not null then
    select * into title from public.titles where id=outing.title_id and user_id=me;
    select * into viewing from public.viewings where id=(p_receipt->>'canonicalViewingId')::uuid
      and user_id=me and outing_id=outing.id and title_id=outing.title_id;
  end if;
  return p_receipt || jsonb_build_object(
    'outing',case when outing.id is null then null else to_jsonb(outing) end,
    'viewing',case when viewing.id is null then null else to_jsonb(viewing) end,
    'title',case when title.id is null then null else jsonb_build_object('id',title.id,'status',title.status,'updated_at',title.updated_at) end);
end;
$$;
revoke all on function cinemarchive_private.outing_completion_current(jsonb) from public,anon,authenticated;

create function cinemarchive_private.complete_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_provisional_viewing_id uuid,
  p_expected_updated_at timestamptz,p_tz text default 'UTC',p_expected_operation_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  completion cinemarchive_private.outing_completions;
  outing public.cinema_outings;
  title public.titles;
  viewing public.viewings;
  previous public.watch_status;
  v_result jsonb;
  names jsonb;
  outcome text;
  expected_version timestamptz;
  dependency jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_outing_id is null or p_operation_id is null or p_provisional_viewing_id is null
    or ((p_expected_updated_at is null) = (p_expected_operation_id is null))
    or (p_expected_updated_at is not null and not isfinite(p_expected_updated_at))
    or p_expected_operation_id=p_operation_id
    or p_tz is null or not exists(select 1 from pg_catalog.pg_timezone_names where name=p_tz) then
    raise exception 'Invalid outing completion command' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind','outing.complete','outingId',p_outing_id,
    'provisionalViewingId',p_provisional_viewing_id,'expectedUpdatedAt',p_expected_updated_at,
    'expectedOperationId',p_expected_operation_id,'timezone',p_tz));
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations<>operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result<>'{}'::jsonb then return cinemarchive_private.outing_completion_current(receipt.result); end if;
  expected_version := p_expected_updated_at;
  if p_expected_operation_id is not null then
    select result into dependency from cinemarchive_private.library_command_receipts
      where user_id=me and operation_id=p_expected_operation_id;
    select (effect->'row'->>'updated_at')::timestamptz into expected_version
      from jsonb_array_elements(coalesce(dependency->'rows','[]'::jsonb)) effect
      where effect->>'table'='cinema_outings' and effect->'key'->>'id'=p_outing_id::text
        and effect->'row'->>'id'=p_outing_id::text limit 1;
    if expected_version is null then raise exception 'Outing dependency is not acknowledged' using errcode='40001'; end if;
  end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if outing.id is null then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return jsonb_build_object('status','missing','operationId',p_operation_id,'outingId',p_outing_id,
      'request',operations->0,'canonicalViewingId',null,'outing',null,'viewing',null,'title',null);
  end if;
  select * into title from public.titles where id=outing.title_id and user_id=me for update;
  if title.id is null then raise exception 'Outing title ownership required' using errcode='42501'; end if;
  select * into completion from cinemarchive_private.outing_completions where user_id=me and outing_id=p_outing_id;
  if outing.status='completed' then
    if completion.outing_id is null then
      -- Older completion: preserve the explicit link only, including absence after deletion.
      if outing.completed_viewing_id is not null and not exists(select 1 from public.viewings v
        where v.id=outing.completed_viewing_id and v.user_id=me and v.title_id=outing.title_id and v.outing_id=outing.id) then
        raise exception 'Completed outing link requires review' using errcode='40001'; end if;
      insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,previous_status)
        values(me,outing.id,outing.title_id,outing.completed_viewing_id,outing.previous_status) returning * into completion;
    end if;
    outcome := 'already_completed';
  elsif outing.status<>'scheduled' or outing.ends_at>now() or outing.updated_at<>expected_version
    or completion.outing_id is not null or exists(select 1 from public.viewings v where v.user_id=me and v.outing_id=outing.id) then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return cinemarchive_private.outing_completion_current(jsonb_build_object('status','conflict','operationId',p_operation_id,
      'outingId',p_outing_id,'request',operations->0,'canonicalViewingId',completion.canonical_viewing_id));
  else
    previous := title.status;
    insert into public.viewings(id,title_id,user_id,viewed_at,venue,companions,outing_id)
      values(p_provisional_viewing_id,outing.title_id,me,(outing.showtime at time zone p_tz)::date,outing.venue,outing.companions,outing.id)
      returning * into viewing;
    if title.status<>'watched' then
      update public.titles set status='watched' where id=title.id and user_id=me returning * into title;
    end if;
    update public.cinema_outings set status='completed',previous_status=previous,completed_viewing_id=viewing.id
      where id=outing.id and user_id=me returning * into outing;
    insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,completed_title_version,previous_status)
      values(me,outing.id,title.id,viewing.id,title.updated_at,previous) returning * into completion;
    select coalesce(jsonb_agg(case when jsonb_typeof(c)='string' then c#>>'{}' else c->>'name' end order by n),'[]'::jsonb)
      into names from jsonb_array_elements(outing.companions) with ordinality as x(c,n);
    insert into public.notifications(recipient_id,type,title_id,payload)
      values(me,'outing_completed',title.id,jsonb_build_object('venue',outing.venue,'companions',names));
    outcome := 'applied';
  end if;
  v_result := jsonb_build_object('status',outcome,'operationId',p_operation_id,'outingId',outing.id,
    'request',operations->0,'canonicalViewingId',completion.canonical_viewing_id,
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',outing.id),'row',to_jsonb(outing))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return cinemarchive_private.outing_completion_current(v_result);
end;
$$;

create function public.complete_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_provisional_viewing_id uuid,
  p_expected_updated_at timestamptz,p_tz text default 'UTC',p_expected_operation_id uuid default null
) returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.complete_cinema_outing(p_outing_id,p_operation_id,p_provisional_viewing_id,p_expected_updated_at,p_tz,p_expected_operation_id);
$$;
revoke all on function public.complete_cinema_outing(uuid,uuid,uuid,timestamptz,text,uuid),
  cinemarchive_private.complete_cinema_outing(uuid,uuid,uuid,timestamptz,text,uuid) from public,anon;
grant execute on function public.complete_cinema_outing(uuid,uuid,uuid,timestamptz,text,uuid),
  cinemarchive_private.complete_cinema_outing(uuid,uuid,uuid,timestamptz,text,uuid) to authenticated;

create function cinemarchive_private.complete_due_outings(p_tz text default 'UTC')
returns table(outing_id uuid,title_id uuid,viewing_id uuid,new_title_status public.watch_status,previous_status public.watch_status)
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  zone text;
  candidate public.cinema_outings;
  result jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  select name into zone from pg_catalog.pg_timezone_names where name=p_tz;
  zone := coalesce(zone,'UTC'); -- Preserve older callers' documented fallback.
  for candidate in select * from public.cinema_outings co where co.user_id=me and co.status='scheduled'
    and co.ends_at<=now() order by co.id for update skip locked loop
    result := cinemarchive_private.complete_cinema_outing(candidate.id,gen_random_uuid(),gen_random_uuid(),candidate.updated_at,zone);
    if result->>'status'='applied' then
      outing_id := candidate.id; title_id := candidate.title_id;
      viewing_id := (result->>'canonicalViewingId')::uuid;
      new_title_status := (result->'title'->>'status')::public.watch_status;
      previous_status := (result->'outing'->>'previous_status')::public.watch_status;
      return next;
    end if;
  end loop;
end;
$$;
create or replace function public.complete_due_outings(p_tz text default 'UTC')
returns table(outing_id uuid,title_id uuid,viewing_id uuid,new_title_status public.watch_status,previous_status public.watch_status)
language sql security invoker set search_path = '' as $$
  select * from cinemarchive_private.complete_due_outings(p_tz);
$$;
revoke all on function public.complete_due_outings(text),cinemarchive_private.complete_due_outings(text) from public,anon;
grant execute on function public.complete_due_outings(text),cinemarchive_private.complete_due_outings(text) to authenticated;

-- Guarded outing completion reversal (20261008195605)
create function cinemarchive_private.revert_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_expected_updated_at timestamptz,
  p_expected_viewing_id uuid,p_expected_viewing_updated_at timestamptz
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  completion cinemarchive_private.outing_completions;
  outing public.cinema_outings;
  title public.titles;
  viewing public.viewings;
  canonical uuid;
  restored boolean := false;
  v_result jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_outing_id is null or p_operation_id is null or p_expected_updated_at is null
    or not isfinite(p_expected_updated_at)
    or (p_expected_viewing_updated_at is not null and not isfinite(p_expected_viewing_updated_at)) then
    raise exception 'Invalid outing revert command' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind','outing.revert','outingId',p_outing_id,
    'expectedUpdatedAt',p_expected_updated_at,'expectedViewingId',p_expected_viewing_id,
    'expectedViewingUpdatedAt',p_expected_viewing_updated_at));
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations<>operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result<>'{}'::jsonb then return cinemarchive_private.outing_completion_current(receipt.result); end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if outing.id is null then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return jsonb_build_object('status','missing','operationId',p_operation_id,'outingId',p_outing_id,
      'request',operations->0,'canonicalViewingId',null,'outing',null,'viewing',null,'title',null);
  end if;
  select * into title from public.titles where id=outing.title_id and user_id=me for update;
  if title.id is null then raise exception 'Outing title ownership required' using errcode='42501'; end if;
  select * into completion from cinemarchive_private.outing_completions where user_id=me and outing_id=p_outing_id;
  canonical := case when completion.outing_id is null then outing.completed_viewing_id else completion.canonical_viewing_id end;
  -- Read the identified row before deciding whether its link is still valid.
  select * into viewing from public.viewings where id=canonical and user_id=me for update;
  if outing.status<>'completed' or outing.updated_at<>p_expected_updated_at
    or canonical is distinct from p_expected_viewing_id
    or (outing.completed_viewing_id is not null and outing.completed_viewing_id is distinct from canonical)
    or (viewing.id is not null and (viewing.outing_id is distinct from outing.id or viewing.title_id<>title.id
      or viewing.updated_at is distinct from p_expected_viewing_updated_at or viewing.rating is not null))
    or (viewing.id is null and p_expected_viewing_updated_at is not null) then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return cinemarchive_private.outing_completion_current(jsonb_build_object('status','conflict','operationId',p_operation_id,
      'outingId',p_outing_id,'request',operations->0,'canonicalViewingId',canonical));
  end if;
  if viewing.id is not null then
    delete from public.viewings where id=viewing.id and user_id=me and outing_id=outing.id;
  end if;
  -- A later same-status write is still deliberate. Another completed trip or
  -- remaining viewing also prevents an old trip from rolling back the title.
  if title.status='watched' and completion.previous_status is not null
    and title.updated_at=completion.completed_title_version
    and not exists(select 1 from public.viewings v where v.user_id=me and v.title_id=title.id)
    and not exists(select 1 from public.cinema_outings o where o.user_id=me and o.title_id=title.id and o.id<>outing.id and o.status='completed') then
    if title.status is distinct from completion.previous_status then
      update public.titles set status=completion.previous_status where id=title.id and user_id=me returning * into title;
      restored := true;
    end if;
  end if;
  update public.cinema_outings set status='missed',completed_viewing_id=null
    where id=outing.id and user_id=me returning * into outing;
  v_result := jsonb_build_object('status','applied','operationId',p_operation_id,'outingId',outing.id,
    'request',operations->0,'canonicalViewingId',canonical,'titleStatusRestored',restored,
    'rows',jsonb_build_array(
      jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',outing.id),'row',to_jsonb(outing)),
      jsonb_build_object('table','titles','key',jsonb_build_object('id',title.id),'row',to_jsonb(title))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return cinemarchive_private.outing_completion_current(v_result);
end;
$$;
create function public.revert_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_expected_updated_at timestamptz,
  p_expected_viewing_id uuid,p_expected_viewing_updated_at timestamptz
) returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.revert_cinema_outing(p_outing_id,p_operation_id,p_expected_updated_at,p_expected_viewing_id,p_expected_viewing_updated_at);
$$;
revoke all on function public.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz),
  cinemarchive_private.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz) from public,anon;
grant execute on function public.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz),
  cinemarchive_private.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz) to authenticated;

-- Credit refresh commands (20261008201710)
-- Provider-credit refresh preserves canonical row identities and receipt replay.
create or replace function cinemarchive_private.apply_library_command(
  p_operation_id uuid, p_operations jsonb
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_owner uuid := auth.uid();
  v_receipt cinemarchive_private.library_command_receipts%rowtype;
  v_op jsonb;
  v_table text;
  v_action text;
  v_key jsonb;
  v_values jsonb;
  v_row jsonb;
  v_existing jsonb;
  v_allowed text[];
  v_keys text[];
  v_actual_keys text[];
  v_column text;
  v_where text;
  v_columns text;
  v_select text;
  v_assign text;
  v_conflict text;
  v_parent uuid;
  v_credit_put boolean;
  v_rows jsonb[] := ARRAY[]::jsonb[];
  v_result jsonb;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if p_operation_id is null or jsonb_typeof(p_operations) is distinct from 'array'
     or jsonb_array_length(p_operations) not between 1 and 50000
     or octet_length(p_operations::text) > 16777216 then
    raise exception 'Invalid library command' using errcode = '22023';
  end if;

  -- Concurrent retries serialize on this primary key. Failure anywhere below
  -- rolls back the placeholder along with every mutation in the command.
  insert into cinemarchive_private.library_command_receipts(user_id, operation_id, operations)
    values (v_owner, p_operation_id, p_operations) on conflict do nothing;
  select * into strict v_receipt from cinemarchive_private.library_command_receipts
    where user_id = v_owner and operation_id = p_operation_id for update;
  if v_receipt.operations <> p_operations then
    raise exception 'Operation ID reused with different data' using errcode = '22023';
  end if;
  if v_receipt.result <> '{}'::jsonb then return v_receipt.result; end if;

  for v_op in select value from jsonb_array_elements(p_operations) loop
    if jsonb_typeof(v_op) is distinct from 'object' or
       exists(select 1 from jsonb_object_keys(v_op) k where k not in ('table','action','key','values','expectedUpdatedAt')) then
      raise exception 'Invalid command operation' using errcode = '22023';
    end if;
    v_table := v_op->>'table';
    v_action := v_op->>'action';
    v_key := v_op->'key';
    v_values := coalesce(v_op->'values', '{}'::jsonb);
    if v_action is null or v_action not in ('insert','update','delete','put') or
       jsonb_typeof(v_key) is distinct from 'object' or jsonb_typeof(v_values) is distinct from 'object' then
      raise exception 'Invalid command action or fields' using errcode = '22023';
    end if;
    v_keys := array['id'];
    -- This is an explicit API allowlist, not arbitrary SQL/table access. Owner
    -- identity is always injected from auth.uid(), never accepted from JSON.
    case v_table
      when 'titles' then v_allowed := array['tmdb_id','type','title','year','director','genres','poster_url','backdrop_url','synopsis','runtime','network','status','rating','notes','tags','imdb_rating','rt_score','metacritic_score','studios','added_at','release_date','original_language','content_rating','imdb_id','rt_url','awards_count','bechdel_outcome','bechdel_score','custom_watch_url','in_home_collection','physical_media','collection_id','collection_name'];
      when 'seasons' then v_allowed := array['title_id','season_number','episode_count','episodes_watched','air_year'];
      when 'episodes' then v_allowed := array['title_id','season_number','episode_number','episode_name','air_date','runtime','synopsis','still_url'];
      when 'viewings' then v_allowed := array['title_id','viewed_at','rating','notes','venue','companions','outing_id','created_at'];
      when 'episode_watch_events' then v_allowed := array['episode_id','watched_at','notes','color_mode','created_at'];
      when 'episode_ratings' then v_allowed := array['episode_id','rating','rated_at'];
      when 'episode_reviews' then v_allowed := array['episode_id','review_text','reviewed_at','color_mode'];
      when 'cinema_outings' then v_allowed := array['title_id','showtime','previews_minutes','runtime_minutes','ends_at','venue','companions','format','ticket_price','seat','auditorium','seat_row','seats','booking_ref','ticket_image_path','ticket_barcode_payload','ticket_barcode_format','notes','status','previous_status','completed_viewing_id','follow_up_dismissed_at','created_at'];
      when 'external_title_links' then v_keys := array['provider','external_id']; v_allowed := array['title_id'];
      when 'lists' then v_allowed := array['name','description','created_at'];
      when 'list_items' then v_keys := array['list_id','title_id']; v_allowed := array['position','added_at'];
      when 'user_title_pins' then v_keys := array['title_id','easter_egg_key']; v_allowed := array['pinned_variant'];
      when 'user_prefs' then v_keys := array[]::text[]; v_allowed := array['ledger_layout'];
      when 'title_cast' then v_keys := array['title_id','tmdb_person_id']; v_allowed := array['name','character_name','episode_count','profile_url','cast_order'];
      when 'title_crew' then v_keys := array['title_id','tmdb_person_id','job']; v_allowed := array['name','department','profile_url'];
      when 'season_cast' then v_keys := array['season_id','tmdb_person_id']; v_allowed := array['title_id','name','character_name','episode_count','profile_url','cast_order'];
      when 'episode_crew' then v_keys := array['episode_id','tmdb_person_id','job']; v_allowed := array['title_id','name'];
      else raise exception 'Unsupported library entity' using errcode = '22023';
    end case;
    v_credit_put := v_action = 'put' and v_table in ('title_cast','title_crew','season_cast','episode_crew');
    if v_action = 'put' and not v_credit_put and v_table not in ('user_title_pins','user_prefs') then
      raise exception 'Put is restricted to preferences and provider credits' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_table in ('title_cast','title_crew') and v_key ? 'title_id' and not v_key ? 'tmdb_person_id' then v_keys := array['title_id']; end if;
    if v_action = 'delete' and v_table = 'season_cast' and v_key ? 'season_id' and not v_key ? 'tmdb_person_id' then v_keys := array['season_id']; end if;
    if v_action = 'delete' and v_table = 'episode_crew' and v_key ? 'episode_id' and not v_key ? 'tmdb_person_id' then v_keys := array['episode_id']; end if;
    select coalesce(array_agg(k order by k), array[]::text[]) into v_actual_keys from jsonb_object_keys(v_key) k;
    if not (v_actual_keys @> v_keys and v_keys @> v_actual_keys) or
       exists(select 1 from jsonb_each(v_key) e where e.value = 'null'::jsonb or jsonb_typeof(e.value) not in ('number','string')) or
       exists(select 1 from jsonb_object_keys(v_values) k where not k = any(v_allowed)) then
      raise exception 'Unsupported library fields or identity' using errcode = '22023';
    end if;
    if v_action in ('update','put') and exists(select 1 from jsonb_object_keys(v_values) k
      where k in ('title_id','episode_id','season_id','season_number','episode_number','created_at','added_at')
        and not (v_credit_put and k = 'title_id')) then
      raise exception 'Parent and creation fields are immutable' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_values <> '{}'::jsonb then raise exception 'Delete has no values' using errcode = '22023'; end if;
    v_where := 'r.user_id = $2';
    foreach v_column in array v_keys loop
      v_where := v_where || format(' and r.%I = (jsonb_populate_record(null::public.%I,$1)).%I', v_column, v_table, v_column);
    end loop;
    v_existing := null;
    execute format('select to_jsonb(r) from public.%I r where %s limit 1 for update', v_table, v_where)
      into v_existing using v_key, v_owner;
    if v_op ? 'expectedUpdatedAt' and (v_existing is null or (v_existing->>'updated_at')::timestamptz is distinct from (v_op->>'expectedUpdatedAt')::timestamptz) then
      raise exception 'Library record changed on another device' using errcode = '40001';
    end if;
    if v_action = 'delete' then
      execute format('delete from public.%I r where %s', v_table, v_where) using v_key, v_owner;
      v_rows := array_append(v_rows, jsonb_build_object('table',v_table,'key',v_key,'deleted',true));
      continue;
    end if;
    if v_action = 'update' and v_existing is null then raise exception 'Library record no longer exists' using errcode = 'P0002'; end if;
    if v_action = 'insert' and v_existing is not null then
      foreach v_column in array array['title_id','episode_id','season_id','season_number','episode_number','tmdb_id','type'] loop
        if v_values ? v_column and v_values->v_column is distinct from v_existing->v_column then
          raise exception 'Library identity reused for another record' using errcode='23505';
        end if;
      end loop;
    end if;
    -- A supplied denormalized title is checked, never used to reparent an existing credit.
    if v_credit_put and v_existing is not null and v_values ? 'title_id'
       and v_values->'title_id' is distinct from v_existing->'title_id' then
      raise exception 'Credit parent is immutable' using errcode='22023';
    end if;
    v_row := coalesce(v_existing, '{}'::jsonb) || v_values || v_key || jsonb_build_object('user_id',v_owner);

    -- Parent ownership is checked even where historical RLS only checked the
    -- child's user_id. A caller cannot attach their row to another user's graph.
    if v_row ? 'title_id' then
      v_parent := (v_row->>'title_id')::uuid;
      if not exists(select 1 from public.titles where id=v_parent and user_id=v_owner) then raise exception 'Title ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'episode_id' then
      v_parent := (v_row->>'episode_id')::uuid;
      if not exists(select 1 from public.episodes where id=v_parent and user_id=v_owner and
        (not v_row ? 'title_id' or title_id=(v_row->>'title_id')::uuid)) then raise exception 'Episode ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'season_id' then
      v_parent := (v_row->>'season_id')::uuid;
      if not exists(select 1 from public.seasons where id=v_parent and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Season ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'list_id' and not exists(select 1 from public.lists where id=(v_row->>'list_id')::uuid and user_id=v_owner) then raise exception 'List ownership required' using errcode='42501'; end if;
    if v_table='episodes' and not exists(select 1 from public.seasons where title_id=(v_row->>'title_id')::uuid and season_number=(v_row->>'season_number')::integer and user_id=v_owner) then raise exception 'Episode season required' using errcode='23503'; end if;
    if v_row->>'outing_id' is not null and not exists(select 1 from public.cinema_outings where id=(v_row->>'outing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Outing ownership required' using errcode='42501'; end if;
    if v_row->>'completed_viewing_id' is not null and not exists(select 1 from public.viewings where id=(v_row->>'completed_viewing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Viewing ownership required' using errcode='42501'; end if;

    if v_action = 'update' or (v_action='put' and v_existing is not null) then
      select string_agg(format('%I = p.%I', k, k), ',') into v_assign from jsonb_object_keys(v_values) k
        where not (v_credit_put and k = 'title_id');
      if v_assign is not null then
        execute format('update public.%I r set %s from jsonb_populate_record(null::public.%I,$3) p where %s returning to_jsonb(r)',v_table,v_assign,v_table,v_where)
          into v_row using v_key,v_owner,v_values;
      else v_row := v_existing; end if;
    elsif v_existing is not null then
      -- An insert retry must not undo an edit made after the original insert.
      v_row := v_existing;
    else
      v_row := v_values || v_key || jsonb_build_object('user_id',v_owner);
      select string_agg(format('%I',k),','),string_agg(format('p.%I',k),',') into v_columns,v_select from jsonb_object_keys(v_row) k;
      v_conflict := 'do nothing';
      if v_action='put' then
        select string_agg(format('%I = excluded.%I',k,k),',') into v_assign from jsonb_object_keys(v_values) k
          where not (v_credit_put and k = 'title_id');
        if v_assign is null then raise exception 'Put requires values' using errcode='22023'; end if;
        select string_agg(format('%I',k),',') into v_conflict from unnest(case when v_credit_put then v_keys else array['user_id'] || v_keys end) k;
        v_conflict := '(' || v_conflict || ') do update set ' || v_assign;
        if v_credit_put then
          -- Credit uniqueness excludes user_id. A concurrent or legacy foreign
          -- row must never be adopted, and neither its UUID nor parent may change.
          v_conflict := v_conflict || format(' where %I.user_id = excluded.user_id and %I.title_id = excluded.title_id',v_table,v_table);
        end if;
      end if;
      execute format('insert into public.%I (%s) select %s from jsonb_populate_record(null::public.%I,$1) p on conflict %s returning to_jsonb(%I)',v_table,v_columns,v_select,v_table,v_conflict,v_table)
        into v_row using v_row;
      if v_row is null then
        if v_credit_put then raise exception 'Credit identity conflicts with an existing owner or parent' using errcode='23505'; end if;
        execute format('select to_jsonb(r) from public.%I r where %s',v_table,v_where) into v_row using v_key,v_owner;
        if v_row is null then raise exception 'Library identity conflicts with an existing record' using errcode='23505'; end if;
      end if;
    end if;
    v_rows := array_append(v_rows, jsonb_build_object('table',v_table,'key',v_key,'row',v_row));
  end loop;
  v_result := jsonb_build_object('operationId',p_operation_id,'rows',v_rows);
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=v_owner and operation_id=p_operation_id;
  return v_result;
end;
$$;

-- Causal outing revert (20261008211455)
-- A reversal queued after an edit uses that edit's immutable receipt revision.
-- Defaulted dependency arguments preserve the original five-argument call.
drop function public.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz);
drop function cinemarchive_private.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz);
create function cinemarchive_private.revert_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_expected_updated_at timestamptz,
  p_expected_viewing_id uuid,p_expected_viewing_updated_at timestamptz,
  p_expected_operation_id uuid default null,p_expected_viewing_operation_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  completion cinemarchive_private.outing_completions;
  outing public.cinema_outings;
  title public.titles;
  viewing public.viewings;
  canonical uuid;
  restored boolean := false;
  expected_outing_at timestamptz := p_expected_updated_at;
  expected_viewing_at timestamptz := p_expected_viewing_updated_at;
  v_result jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_outing_id is null or p_operation_id is null or (p_expected_updated_at is null) = (p_expected_operation_id is null)
    or (p_expected_updated_at is not null and not isfinite(p_expected_updated_at))
    or p_expected_operation_id = p_operation_id or p_expected_viewing_operation_id = p_operation_id
    or (p_expected_viewing_operation_id is not null and
      (p_expected_viewing_updated_at is not null or p_expected_viewing_id is null))
    or (p_expected_viewing_updated_at is not null and not isfinite(p_expected_viewing_updated_at)) then
    raise exception 'Invalid outing revert command' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind','outing.revert','outingId',p_outing_id,
    'expectedUpdatedAt',p_expected_updated_at,'expectedViewingId',p_expected_viewing_id,
    'expectedViewingUpdatedAt',p_expected_viewing_updated_at));
  -- Keep old no-dependency request signatures byte-for-byte compatible with receipts.
  if p_expected_operation_id is not null then
    operations := jsonb_set(operations,'{0,expectedOperationId}',to_jsonb(p_expected_operation_id)); end if;
  if p_expected_viewing_operation_id is not null then
    operations := jsonb_set(operations,'{0,expectedViewingOperationId}',to_jsonb(p_expected_viewing_operation_id)); end if;
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations<>operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result<>'{}'::jsonb then return cinemarchive_private.outing_completion_current(receipt.result); end if;
  if p_expected_operation_id is not null then
    select (effect.value->'row'->>'updated_at')::timestamptz into expected_outing_at
      from cinemarchive_private.library_command_receipts predecessor
      cross join lateral jsonb_array_elements(predecessor.result->'rows') with ordinality effect(value,ordinal)
      where predecessor.user_id=me and predecessor.operation_id=p_expected_operation_id
        and effect.value->>'table'='cinema_outings'
        and effect.value->'key' = jsonb_build_object('id',p_outing_id)
      order by effect.ordinal desc limit 1;
    if expected_outing_at is null then raise exception 'Preceding outing change has no matching revision' using errcode='40001'; end if;
  end if;
  if p_expected_viewing_operation_id is not null then
    select (effect.value->'row'->>'updated_at')::timestamptz into expected_viewing_at
      from cinemarchive_private.library_command_receipts predecessor
      cross join lateral jsonb_array_elements(predecessor.result->'rows') with ordinality effect(value,ordinal)
      where predecessor.user_id=me and predecessor.operation_id=p_expected_viewing_operation_id
        and effect.value->>'table'='viewings'
        and effect.value->'key' = jsonb_build_object('id',p_expected_viewing_id)
      order by effect.ordinal desc limit 1;
    if expected_viewing_at is null then raise exception 'Preceding viewing change has no matching revision' using errcode='40001'; end if;
  end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if outing.id is null then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return jsonb_build_object('status','missing','operationId',p_operation_id,'outingId',p_outing_id,
      'request',operations->0,'canonicalViewingId',null,'outing',null,'viewing',null,'title',null);
  end if;
  select * into title from public.titles where id=outing.title_id and user_id=me for update;
  if title.id is null then raise exception 'Outing title ownership required' using errcode='42501'; end if;
  select * into completion from cinemarchive_private.outing_completions where user_id=me and outing_id=p_outing_id;
  canonical := case when completion.outing_id is null then outing.completed_viewing_id else completion.canonical_viewing_id end;
  -- Read the identified row before deciding whether its link is still valid.
  select * into viewing from public.viewings where id=canonical and user_id=me for update;
  if outing.status<>'completed' or outing.updated_at<>expected_outing_at
    or canonical is distinct from p_expected_viewing_id
    or (outing.completed_viewing_id is not null and outing.completed_viewing_id is distinct from canonical)
    or (viewing.id is not null and (viewing.outing_id is distinct from outing.id or viewing.title_id<>title.id
      or viewing.updated_at is distinct from expected_viewing_at or viewing.rating is not null))
    or (viewing.id is null and expected_viewing_at is not null) then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return cinemarchive_private.outing_completion_current(jsonb_build_object('status','conflict','operationId',p_operation_id,
      'outingId',p_outing_id,'request',operations->0,'canonicalViewingId',canonical));
  end if;
  if viewing.id is not null then
    delete from public.viewings where id=viewing.id and user_id=me and outing_id=outing.id;
  end if;
  -- A later same-status write is still deliberate. Another completed trip or
  -- remaining viewing also prevents an old trip from rolling back the title.
  if title.status='watched' and completion.previous_status is not null
    and title.updated_at=completion.completed_title_version
    and not exists(select 1 from public.viewings v where v.user_id=me and v.title_id=title.id)
    and not exists(select 1 from public.cinema_outings o where o.user_id=me and o.title_id=title.id and o.id<>outing.id and o.status='completed') then
    if title.status is distinct from completion.previous_status then
      update public.titles set status=completion.previous_status where id=title.id and user_id=me returning * into title;
      restored := true;
    end if;
  end if;
  update public.cinema_outings set status='missed',completed_viewing_id=null
    where id=outing.id and user_id=me returning * into outing;
  v_result := jsonb_build_object('status','applied','operationId',p_operation_id,'outingId',outing.id,
    'request',operations->0,'canonicalViewingId',canonical,'titleStatusRestored',restored,
    'rows',jsonb_build_array(
      jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',outing.id),'row',to_jsonb(outing)),
      jsonb_build_object('table','titles','key',jsonb_build_object('id',title.id),'row',to_jsonb(title))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return cinemarchive_private.outing_completion_current(v_result);
end;
$$;
create function public.revert_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_expected_updated_at timestamptz,
  p_expected_viewing_id uuid,p_expected_viewing_updated_at timestamptz,
  p_expected_operation_id uuid default null,p_expected_viewing_operation_id uuid default null
) returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.revert_cinema_outing(p_outing_id,p_operation_id,p_expected_updated_at,p_expected_viewing_id,p_expected_viewing_updated_at,p_expected_operation_id,p_expected_viewing_operation_id);
$$;
revoke all on function public.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz,uuid,uuid),
  cinemarchive_private.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz,uuid,uuid) from public,anon;
grant execute on function public.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz,uuid,uuid),
  cinemarchive_private.revert_cinema_outing(uuid,uuid,timestamptz,uuid,timestamptz,uuid,uuid) to authenticated;

-- Immutable viewing baseline for canonical outing completion (20261008213850).
-- Deliberately no backfill: current historical viewing revisions do not prove completion-time state.
alter table cinemarchive_private.outing_completions
  add column canonical_viewing_version timestamptz;

create or replace function cinemarchive_private.complete_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_provisional_viewing_id uuid,
  p_expected_updated_at timestamptz,p_tz text default 'UTC',p_expected_operation_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  completion cinemarchive_private.outing_completions;
  outing public.cinema_outings;
  title public.titles;
  viewing public.viewings;
  previous public.watch_status;
  v_result jsonb;
  names jsonb;
  outcome text;
  expected_version timestamptz;
  dependency jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_outing_id is null or p_operation_id is null or p_provisional_viewing_id is null
    or ((p_expected_updated_at is null) = (p_expected_operation_id is null))
    or (p_expected_updated_at is not null and not isfinite(p_expected_updated_at))
    or p_expected_operation_id=p_operation_id
    or p_tz is null or not exists(select 1 from pg_catalog.pg_timezone_names where name=p_tz) then
    raise exception 'Invalid outing completion command' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind','outing.complete','outingId',p_outing_id,
    'provisionalViewingId',p_provisional_viewing_id,'expectedUpdatedAt',p_expected_updated_at,
    'expectedOperationId',p_expected_operation_id,'timezone',p_tz));
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations<>operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result<>'{}'::jsonb then return cinemarchive_private.outing_completion_current(receipt.result); end if;
  expected_version := p_expected_updated_at;
  if p_expected_operation_id is not null then
    select result into dependency from cinemarchive_private.library_command_receipts
      where user_id=me and operation_id=p_expected_operation_id;
    select (effect->'row'->>'updated_at')::timestamptz into expected_version
      from jsonb_array_elements(coalesce(dependency->'rows','[]'::jsonb)) effect
      where effect->>'table'='cinema_outings' and effect->'key'->>'id'=p_outing_id::text
        and effect->'row'->>'id'=p_outing_id::text limit 1;
    if expected_version is null then raise exception 'Outing dependency is not acknowledged' using errcode='40001'; end if;
  end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if outing.id is null then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return jsonb_build_object('status','missing','operationId',p_operation_id,'outingId',p_outing_id,
      'request',operations->0,'canonicalViewingId',null,'outing',null,'viewing',null,'title',null);
  end if;
  select * into title from public.titles where id=outing.title_id and user_id=me for update;
  if title.id is null then raise exception 'Outing title ownership required' using errcode='42501'; end if;
  select * into completion from cinemarchive_private.outing_completions where user_id=me and outing_id=p_outing_id;
  if outing.status='completed' then
    if completion.outing_id is null then
      -- Older completion: preserve the explicit link only, including absence after deletion.
      if outing.completed_viewing_id is not null and not exists(select 1 from public.viewings v
        where v.id=outing.completed_viewing_id and v.user_id=me and v.title_id=outing.title_id and v.outing_id=outing.id) then
        raise exception 'Completed outing link requires review' using errcode='40001'; end if;
      insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,previous_status)
        values(me,outing.id,outing.title_id,outing.completed_viewing_id,outing.previous_status) returning * into completion;
    end if;
    outcome := 'already_completed';
  elsif outing.status<>'scheduled' or outing.ends_at>now() or outing.updated_at<>expected_version
    or completion.outing_id is not null or exists(select 1 from public.viewings v where v.user_id=me and v.outing_id=outing.id) then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return cinemarchive_private.outing_completion_current(jsonb_build_object('status','conflict','operationId',p_operation_id,
      'outingId',p_outing_id,'request',operations->0,'canonicalViewingId',completion.canonical_viewing_id));
  else
    previous := title.status;
    insert into public.viewings(id,title_id,user_id,viewed_at,venue,companions,outing_id)
      values(p_provisional_viewing_id,outing.title_id,me,(outing.showtime at time zone p_tz)::date,outing.venue,outing.companions,outing.id)
      returning * into viewing;
    if title.status<>'watched' then
      update public.titles set status='watched' where id=title.id and user_id=me returning * into title;
    end if;
    update public.cinema_outings set status='completed',previous_status=previous,completed_viewing_id=viewing.id
      where id=outing.id and user_id=me returning * into outing;
    insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,canonical_viewing_version,completed_title_version,previous_status)
      values(me,outing.id,title.id,viewing.id,viewing.updated_at,title.updated_at,previous) returning * into completion;
    select coalesce(jsonb_agg(case when jsonb_typeof(c)='string' then c#>>'{}' else c->>'name' end order by n),'[]'::jsonb)
      into names from jsonb_array_elements(outing.companions) with ordinality as x(c,n);
    insert into public.notifications(recipient_id,type,title_id,payload)
      values(me,'outing_completed',title.id,jsonb_build_object('venue',outing.venue,'companions',names,
        'outingId',outing.id,'canonicalViewingId',viewing.id));
    outcome := 'applied';
  end if;
  v_result := jsonb_build_object('status',outcome,'operationId',p_operation_id,'outingId',outing.id,
    'request',operations->0,'canonicalViewingId',completion.canonical_viewing_id,
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',outing.id),'row',to_jsonb(outing))));
  -- This baseline is the revision created by completion, never a later read of the event.
  -- Historical completions remain unproven; accepted older receipts return above unchanged.
  if completion.canonical_viewing_id is not null and completion.canonical_viewing_version is not null then
    v_result := jsonb_set(v_result,'{rows}',(v_result->'rows') || jsonb_build_array(jsonb_build_object(
      'table','viewings','key',jsonb_build_object('id',completion.canonical_viewing_id),
      'row',jsonb_build_object('id',completion.canonical_viewing_id,'user_id',me,
        'title_id',completion.title_id,'outing_id',completion.outing_id,
        'updated_at',completion.canonical_viewing_version))));
  end if;
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return cinemarchive_private.outing_completion_current(v_result);
end;
$$;

-- Ensure episode catalog parents (20261008214554)
-- Preserve canonical parent identity when filling provider episode metadata.
create or replace function cinemarchive_private.apply_library_command(
  p_operation_id uuid, p_operations jsonb
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_owner uuid := auth.uid();
  v_receipt cinemarchive_private.library_command_receipts%rowtype;
  v_op jsonb;
  v_table text;
  v_action text;
  v_key jsonb;
  v_values jsonb;
  v_row jsonb;
  v_existing jsonb;
  v_allowed text[];
  v_keys text[];
  v_actual_keys text[];
  v_column text;
  v_where text;
  v_columns text;
  v_select text;
  v_assign text;
  v_conflict text;
  v_parent uuid;
  v_credit_put boolean;
  v_rows jsonb[] := ARRAY[]::jsonb[];
  v_result jsonb;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if p_operation_id is null or jsonb_typeof(p_operations) is distinct from 'array'
     or jsonb_array_length(p_operations) not between 1 and 50000
     or octet_length(p_operations::text) > 16777216 then
    raise exception 'Invalid library command' using errcode = '22023';
  end if;

  -- Concurrent retries serialize on this primary key. Failure anywhere below
  -- rolls back the placeholder along with every mutation in the command.
  insert into cinemarchive_private.library_command_receipts(user_id, operation_id, operations)
    values (v_owner, p_operation_id, p_operations) on conflict do nothing;
  select * into strict v_receipt from cinemarchive_private.library_command_receipts
    where user_id = v_owner and operation_id = p_operation_id for update;
  if v_receipt.operations <> p_operations then
    raise exception 'Operation ID reused with different data' using errcode = '22023';
  end if;
  if v_receipt.result <> '{}'::jsonb then return v_receipt.result; end if;

  for v_op in select value from jsonb_array_elements(p_operations) loop
    if jsonb_typeof(v_op) is distinct from 'object' or
       exists(select 1 from jsonb_object_keys(v_op) k where k not in ('table','action','key','values','expectedUpdatedAt')) then
      raise exception 'Invalid command operation' using errcode = '22023';
    end if;
    v_table := v_op->>'table';
    v_action := v_op->>'action';
    v_key := v_op->'key';
    v_values := coalesce(v_op->'values', '{}'::jsonb);
    if v_action is null or v_action not in ('insert','update','delete','put','ensure') or
       jsonb_typeof(v_key) is distinct from 'object' or jsonb_typeof(v_values) is distinct from 'object' then
      raise exception 'Invalid command action or fields' using errcode = '22023';
    end if;
    v_keys := array['id'];
    -- This is an explicit API allowlist, not arbitrary SQL/table access. Owner
    -- identity is always injected from auth.uid(), never accepted from JSON.
    case v_table
      when 'titles' then v_allowed := array['tmdb_id','type','title','year','director','genres','poster_url','backdrop_url','synopsis','runtime','network','status','rating','notes','tags','imdb_rating','rt_score','metacritic_score','studios','added_at','release_date','original_language','content_rating','imdb_id','rt_url','awards_count','bechdel_outcome','bechdel_score','custom_watch_url','in_home_collection','physical_media','collection_id','collection_name'];
      when 'seasons' then v_allowed := array['title_id','season_number','episode_count','episodes_watched','air_year'];
      when 'episodes' then v_allowed := array['title_id','season_number','episode_number','episode_name','air_date','runtime','synopsis','still_url'];
      when 'viewings' then v_allowed := array['title_id','viewed_at','rating','notes','venue','companions','outing_id','created_at'];
      when 'episode_watch_events' then v_allowed := array['episode_id','watched_at','notes','color_mode','created_at'];
      when 'episode_ratings' then v_allowed := array['episode_id','rating','rated_at'];
      when 'episode_reviews' then v_allowed := array['episode_id','review_text','reviewed_at','color_mode'];
      when 'cinema_outings' then v_allowed := array['title_id','showtime','previews_minutes','runtime_minutes','ends_at','venue','companions','format','ticket_price','seat','auditorium','seat_row','seats','booking_ref','ticket_image_path','ticket_barcode_payload','ticket_barcode_format','notes','status','previous_status','completed_viewing_id','follow_up_dismissed_at','created_at'];
      when 'external_title_links' then v_keys := array['provider','external_id']; v_allowed := array['title_id'];
      when 'lists' then v_allowed := array['name','description','created_at'];
      when 'list_items' then v_keys := array['list_id','title_id']; v_allowed := array['position','added_at'];
      when 'user_title_pins' then v_keys := array['title_id','easter_egg_key']; v_allowed := array['pinned_variant'];
      when 'user_prefs' then v_keys := array[]::text[]; v_allowed := array['ledger_layout'];
      when 'title_cast' then v_keys := array['title_id','tmdb_person_id']; v_allowed := array['name','character_name','episode_count','profile_url','cast_order'];
      when 'title_crew' then v_keys := array['title_id','tmdb_person_id','job']; v_allowed := array['name','department','profile_url'];
      when 'season_cast' then v_keys := array['season_id','tmdb_person_id']; v_allowed := array['title_id','name','character_name','episode_count','profile_url','cast_order'];
      when 'episode_crew' then v_keys := array['episode_id','tmdb_person_id','job']; v_allowed := array['title_id','name'];
      else raise exception 'Unsupported library entity' using errcode = '22023';
    end case;
    -- Catalog fill adopts existing natural identities without updating their
    -- metadata, user progress, UUIDs or history. It cannot ensure arbitrary rows.
    if v_action = 'ensure' then
      if v_table = 'seasons' then
        v_keys := array['title_id','season_number'];
        v_allowed := array['episode_count','air_year'];
      elsif v_table = 'episodes' then
        v_keys := array['title_id','season_number','episode_number'];
        v_allowed := array['episode_name','air_date','runtime','synopsis','still_url'];
      else raise exception 'Ensure is restricted to catalog parents' using errcode='22023'; end if;
      if v_op ? 'expectedUpdatedAt' then raise exception 'Ensure does not replace existing rows' using errcode='22023'; end if;
    end if;
    v_credit_put := v_action = 'put' and v_table in ('title_cast','title_crew','season_cast','episode_crew');
    if v_action = 'put' and not v_credit_put and v_table not in ('user_title_pins','user_prefs') then
      raise exception 'Put is restricted to preferences and provider credits' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_table in ('title_cast','title_crew') and v_key ? 'title_id' and not v_key ? 'tmdb_person_id' then v_keys := array['title_id']; end if;
    if v_action = 'delete' and v_table = 'season_cast' and v_key ? 'season_id' and not v_key ? 'tmdb_person_id' then v_keys := array['season_id']; end if;
    if v_action = 'delete' and v_table = 'episode_crew' and v_key ? 'episode_id' and not v_key ? 'tmdb_person_id' then v_keys := array['episode_id']; end if;
    select coalesce(array_agg(k order by k), array[]::text[]) into v_actual_keys from jsonb_object_keys(v_key) k;
    if not (v_actual_keys @> v_keys and v_keys @> v_actual_keys) or
       exists(select 1 from jsonb_each(v_key) e where e.value = 'null'::jsonb or jsonb_typeof(e.value) not in ('number','string')) or
       exists(select 1 from jsonb_object_keys(v_values) k where not k = any(v_allowed)) then
      raise exception 'Unsupported library fields or identity' using errcode = '22023';
    end if;
    if v_action in ('update','put') and exists(select 1 from jsonb_object_keys(v_values) k
      where k in ('title_id','episode_id','season_id','season_number','episode_number','created_at','added_at')
        and not (v_credit_put and k = 'title_id')) then
      raise exception 'Parent and creation fields are immutable' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_values <> '{}'::jsonb then raise exception 'Delete has no values' using errcode = '22023'; end if;
    v_where := 'r.user_id = $2';
    foreach v_column in array v_keys loop
      v_where := v_where || format(' and r.%I = (jsonb_populate_record(null::public.%I,$1)).%I', v_column, v_table, v_column);
    end loop;
    v_existing := null;
    execute format('select to_jsonb(r) from public.%I r where %s limit 1 for update', v_table, v_where)
      into v_existing using v_key, v_owner;
    if v_op ? 'expectedUpdatedAt' and (v_existing is null or (v_existing->>'updated_at')::timestamptz is distinct from (v_op->>'expectedUpdatedAt')::timestamptz) then
      raise exception 'Library record changed on another device' using errcode = '40001';
    end if;
    if v_action = 'delete' then
      execute format('delete from public.%I r where %s', v_table, v_where) using v_key, v_owner;
      v_rows := array_append(v_rows, jsonb_build_object('table',v_table,'key',v_key,'deleted',true));
      continue;
    end if;
    if v_action = 'update' and v_existing is null then raise exception 'Library record no longer exists' using errcode = 'P0002'; end if;
    if v_action = 'insert' and v_existing is not null then
      foreach v_column in array array['title_id','episode_id','season_id','season_number','episode_number','tmdb_id','type'] loop
        if v_values ? v_column and v_values->v_column is distinct from v_existing->v_column then
          raise exception 'Library identity reused for another record' using errcode='23505';
        end if;
      end loop;
    end if;
    -- A supplied denormalized title is checked, never used to reparent an existing credit.
    if v_credit_put and v_existing is not null and v_values ? 'title_id'
       and v_values->'title_id' is distinct from v_existing->'title_id' then
      raise exception 'Credit parent is immutable' using errcode='22023';
    end if;
    v_row := coalesce(v_existing, '{}'::jsonb) || v_values || v_key || jsonb_build_object('user_id',v_owner);

    if v_action = 'ensure' then
      -- Episodes have no season UUID FK. Hold the owned natural parent through
      -- insertion so a concurrent parent deletion cannot race this check.
      perform id from public.titles where id=(v_key->>'title_id')::uuid and user_id=v_owner for key share;
      if not found then raise exception 'Title ownership required' using errcode='42501'; end if;
      if v_table = 'episodes' then
        perform id from public.seasons where title_id=(v_key->>'title_id')::uuid
          and season_number=(v_key->>'season_number')::integer and user_id=v_owner for key share;
        if not found then raise exception 'Episode season required' using errcode='23503'; end if;
      end if;
    end if;

    -- Parent ownership is checked even where historical RLS only checked the
    -- child's user_id. A caller cannot attach their row to another user's graph.
    if v_row ? 'title_id' then
      v_parent := (v_row->>'title_id')::uuid;
      if not exists(select 1 from public.titles where id=v_parent and user_id=v_owner) then raise exception 'Title ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'episode_id' then
      v_parent := (v_row->>'episode_id')::uuid;
      if not exists(select 1 from public.episodes where id=v_parent and user_id=v_owner and
        (not v_row ? 'title_id' or title_id=(v_row->>'title_id')::uuid)) then raise exception 'Episode ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'season_id' then
      v_parent := (v_row->>'season_id')::uuid;
      if not exists(select 1 from public.seasons where id=v_parent and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Season ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'list_id' and not exists(select 1 from public.lists where id=(v_row->>'list_id')::uuid and user_id=v_owner) then raise exception 'List ownership required' using errcode='42501'; end if;
    if v_table='episodes' and not exists(select 1 from public.seasons where title_id=(v_row->>'title_id')::uuid and season_number=(v_row->>'season_number')::integer and user_id=v_owner) then raise exception 'Episode season required' using errcode='23503'; end if;
    if v_row->>'outing_id' is not null and not exists(select 1 from public.cinema_outings where id=(v_row->>'outing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Outing ownership required' using errcode='42501'; end if;
    if v_row->>'completed_viewing_id' is not null and not exists(select 1 from public.viewings where id=(v_row->>'completed_viewing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Viewing ownership required' using errcode='42501'; end if;

    if v_action = 'update' or (v_action='put' and v_existing is not null) then
      select string_agg(format('%I = p.%I', k, k), ',') into v_assign from jsonb_object_keys(v_values) k
        where not (v_credit_put and k = 'title_id');
      if v_assign is not null then
        execute format('update public.%I r set %s from jsonb_populate_record(null::public.%I,$3) p where %s returning to_jsonb(r)',v_table,v_assign,v_table,v_where)
          into v_row using v_key,v_owner,v_values;
      else v_row := v_existing; end if;
    elsif v_existing is not null then
      -- Insert retries and catalog ensure must not undo existing metadata or progress.
      v_row := v_existing;
    else
      v_row := v_values || v_key || jsonb_build_object('user_id',v_owner);
      select string_agg(format('%I',k),','),string_agg(format('p.%I',k),',') into v_columns,v_select from jsonb_object_keys(v_row) k;
      v_conflict := 'do nothing';
      if v_action='put' then
        select string_agg(format('%I = excluded.%I',k,k),',') into v_assign from jsonb_object_keys(v_values) k
          where not (v_credit_put and k = 'title_id');
        if v_assign is null then raise exception 'Put requires values' using errcode='22023'; end if;
        select string_agg(format('%I',k),',') into v_conflict from unnest(case when v_credit_put then v_keys else array['user_id'] || v_keys end) k;
        v_conflict := '(' || v_conflict || ') do update set ' || v_assign;
        if v_credit_put then
          -- Credit uniqueness excludes user_id. A concurrent or legacy foreign
          -- row must never be adopted, and neither its UUID nor parent may change.
          v_conflict := v_conflict || format(' where %I.user_id = excluded.user_id and %I.title_id = excluded.title_id',v_table,v_table);
        end if;
      end if;
      execute format('insert into public.%I (%s) select %s from jsonb_populate_record(null::public.%I,$1) p on conflict %s returning to_jsonb(%I)',v_table,v_columns,v_select,v_table,v_conflict,v_table)
        into v_row using v_row;
      if v_row is null then
        if v_credit_put then raise exception 'Credit identity conflicts with an existing owner or parent' using errcode='23505'; end if;
        execute format('select to_jsonb(r) from public.%I r where %s',v_table,v_where) into v_row using v_key,v_owner;
        if v_row is null then raise exception 'Library identity conflicts with an existing record' using errcode='23505'; end if;
      end if;
    end if;
    v_rows := array_append(v_rows, jsonb_build_object('table',v_table,'key',v_key,'row',v_row));
  end loop;
  v_result := jsonb_build_object('operationId',p_operation_id,'rows',v_rows);
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=v_owner and operation_id=p_operation_id;
  return v_result;
end;
$$;

-- Retain completion-time outing revision for dependent commands (20261008220210).
-- No backfill: a current historical outing revision cannot prove the original completion effect.
alter table cinemarchive_private.outing_completions
  add column completion_outing_version timestamptz;

create or replace function cinemarchive_private.complete_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_provisional_viewing_id uuid,
  p_expected_updated_at timestamptz,p_tz text default 'UTC',p_expected_operation_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  completion cinemarchive_private.outing_completions;
  outing public.cinema_outings;
  title public.titles;
  viewing public.viewings;
  previous public.watch_status;
  v_result jsonb;
  names jsonb;
  outcome text;
  expected_version timestamptz;
  dependency jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_outing_id is null or p_operation_id is null or p_provisional_viewing_id is null
    or ((p_expected_updated_at is null) = (p_expected_operation_id is null))
    or (p_expected_updated_at is not null and not isfinite(p_expected_updated_at))
    or p_expected_operation_id=p_operation_id
    or p_tz is null or not exists(select 1 from pg_catalog.pg_timezone_names where name=p_tz) then
    raise exception 'Invalid outing completion command' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind','outing.complete','outingId',p_outing_id,
    'provisionalViewingId',p_provisional_viewing_id,'expectedUpdatedAt',p_expected_updated_at,
    'expectedOperationId',p_expected_operation_id,'timezone',p_tz));
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations<>operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result<>'{}'::jsonb then return cinemarchive_private.outing_completion_current(receipt.result); end if;
  expected_version := p_expected_updated_at;
  if p_expected_operation_id is not null then
    select result into dependency from cinemarchive_private.library_command_receipts
      where user_id=me and operation_id=p_expected_operation_id;
    select (effect->'row'->>'updated_at')::timestamptz into expected_version
      from jsonb_array_elements(coalesce(dependency->'rows','[]'::jsonb)) effect
      where effect->>'table'='cinema_outings' and effect->'key'->>'id'=p_outing_id::text
        and effect->'row'->>'id'=p_outing_id::text limit 1;
    if expected_version is null then raise exception 'Outing dependency is not acknowledged' using errcode='40001'; end if;
  end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if outing.id is null then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return jsonb_build_object('status','missing','operationId',p_operation_id,'outingId',p_outing_id,
      'request',operations->0,'canonicalViewingId',null,'outing',null,'viewing',null,'title',null);
  end if;
  select * into title from public.titles where id=outing.title_id and user_id=me for update;
  if title.id is null then raise exception 'Outing title ownership required' using errcode='42501'; end if;
  select * into completion from cinemarchive_private.outing_completions where user_id=me and outing_id=p_outing_id;
  if outing.status='completed' then
    if completion.outing_id is null then
      -- Older completion: preserve the explicit link only, including absence after deletion.
      if outing.completed_viewing_id is not null and not exists(select 1 from public.viewings v
        where v.id=outing.completed_viewing_id and v.user_id=me and v.title_id=outing.title_id and v.outing_id=outing.id) then
        raise exception 'Completed outing link requires review' using errcode='40001'; end if;
      insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,previous_status)
        values(me,outing.id,outing.title_id,outing.completed_viewing_id,outing.previous_status) returning * into completion;
    end if;
    outcome := 'already_completed';
  elsif outing.status<>'scheduled' or outing.ends_at>now() or outing.updated_at<>expected_version
    or completion.outing_id is not null or exists(select 1 from public.viewings v where v.user_id=me and v.outing_id=outing.id) then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return cinemarchive_private.outing_completion_current(jsonb_build_object('status','conflict','operationId',p_operation_id,
      'outingId',p_outing_id,'request',operations->0,'canonicalViewingId',completion.canonical_viewing_id));
  else
    previous := title.status;
    insert into public.viewings(id,title_id,user_id,viewed_at,venue,companions,outing_id)
      values(p_provisional_viewing_id,outing.title_id,me,(outing.showtime at time zone p_tz)::date,outing.venue,outing.companions,outing.id)
      returning * into viewing;
    if title.status<>'watched' then
      update public.titles set status='watched' where id=title.id and user_id=me returning * into title;
    end if;
    update public.cinema_outings set status='completed',previous_status=previous,completed_viewing_id=viewing.id
      where id=outing.id and user_id=me returning * into outing;
    insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,canonical_viewing_version,completion_outing_version,completed_title_version,previous_status)
      values(me,outing.id,title.id,viewing.id,viewing.updated_at,outing.updated_at,title.updated_at,previous) returning * into completion;
    select coalesce(jsonb_agg(case when jsonb_typeof(c)='string' then c#>>'{}' else c->>'name' end order by n),'[]'::jsonb)
      into names from jsonb_array_elements(outing.companions) with ordinality as x(c,n);
    insert into public.notifications(recipient_id,type,title_id,payload)
      values(me,'outing_completed',title.id,jsonb_build_object('venue',outing.venue,'companions',names,
        'outingId',outing.id,'canonicalViewingId',viewing.id));
    outcome := 'applied';
  end if;
  v_result := jsonb_build_object('status',outcome,'operationId',p_operation_id,'outingId',outing.id,
    'request',operations->0,'canonicalViewingId',completion.canonical_viewing_id,
    'completionOutingVersion',completion.completion_outing_version,
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',outing.id),'row',jsonb_build_object(
      'id',outing.id,'user_id',me,'title_id',outing.title_id,
      'updated_at',completion.completion_outing_version))));
  -- This baseline is the revision created by completion, never a later read of the event.
  -- Historical completions remain unproven; accepted older receipts return above unchanged.
  if completion.canonical_viewing_id is not null and completion.canonical_viewing_version is not null then
    v_result := jsonb_set(v_result,'{rows}',(v_result->'rows') || jsonb_build_array(jsonb_build_object(
      'table','viewings','key',jsonb_build_object('id',completion.canonical_viewing_id),
      'row',jsonb_build_object('id',completion.canonical_viewing_id,'user_id',me,
        'title_id',completion.title_id,'outing_id',completion.outing_id,
        'updated_at',completion.canonical_viewing_version))));
  end if;
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return cinemarchive_private.outing_completion_current(v_result);
end;
$$;

-- Guarded ticket operations and trusted causal revision evidence (20261008222209).
create function cinemarchive_private.library_causal_revision(
  p_operation_id uuid, p_table text, p_key jsonb, p_accepted_replay boolean default false
) returns text language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  receipt cinemarchive_private.library_command_receipts;
  revision text;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id;
  if p_table='cinema_outings' and not p_accepted_replay then
    if receipt.operations->0->>'kind' in ('ticket.attach','ticket.detach')
      and receipt.result->>'outingRevisionGuarded' is distinct from 'true' then
      raise exception 'Ticket association receipt is not an outing revision guard; review required' using errcode='40001';
    end if;
    if receipt.operations->0->>'kind'='outing.complete'
      and receipt.result->>'completionOutingVersion' is null then
      raise exception 'Historical completion has no proven outing revision; review required' using errcode='40001';
    end if;
  end if;
  select effect.value->'row'->>'updated_at' into revision
    from jsonb_array_elements(coalesce(receipt.result->'rows','[]'::jsonb)) with ordinality effect(value,ordinal)
    where effect.value->>'table'=p_table and effect.value->'key'=p_key
    order by effect.ordinal desc limit 1;
  if revision is null then raise exception 'The preceding change has no matching revision' using errcode='40001'; end if;
  return revision;
end;
$$;
-- Only the guarded entrypoints may opt into already-accepted replay. Never expose this helper.
revoke all on function cinemarchive_private.library_causal_revision(uuid,text,jsonb,boolean) from public,anon,authenticated;



create or replace function cinemarchive_private.apply_causal_library_command(
  p_operation_id uuid, p_operations jsonb
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid := auth.uid();
  v_op jsonb;
  v_expected text;
  v_accepted_replay boolean;
  v_operations jsonb[] := ARRAY[]::jsonb[];
begin
  if v_owner is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if jsonb_typeof(p_operations) is distinct from 'array'
    or jsonb_array_length(p_operations) not between 1 and 50000
    or octet_length(p_operations::text) > 16777216 then
    raise exception 'Invalid library command' using errcode='22023';
  end if;
  select exists(select 1 from cinemarchive_private.library_command_receipts
    where user_id=v_owner and operation_id=p_operation_id and result<>'{}'::jsonb) into v_accepted_replay;
  for v_op in select value from jsonb_array_elements(p_operations) loop
    if v_op ? 'expectedOperationId' then
      if v_op ? 'expectedUpdatedAt' or v_op->>'action' not in ('update','delete')
        or jsonb_typeof(v_op->'expectedOperationId') is distinct from 'string'
        or (v_op->>'expectedOperationId')::uuid = p_operation_id then
        raise exception 'Invalid causal revision precondition' using errcode='22023';
      end if;
      -- An accepted retry still passes the original resolved payload to the immutable receipt check.
      -- A changed payload fails there; no new write can bypass evidence validation this way.
      v_expected := cinemarchive_private.library_causal_revision(
        (v_op->>'expectedOperationId')::uuid,v_op->>'table',v_op->'key',v_accepted_replay);
      v_op := jsonb_set(v_op - 'expectedOperationId', '{expectedUpdatedAt}', to_jsonb(v_expected));
    end if;
    v_operations := array_append(v_operations,v_op);
  end loop;
  return cinemarchive_private.apply_library_command(p_operation_id,to_jsonb(v_operations));
end;
$$;

drop function public.finalize_ticket_attachment(uuid,uuid,uuid,uuid);
drop function public.detach_ticket_attachment(uuid,uuid,uuid);
drop function cinemarchive_private.mutate_ticket_attachment(uuid,uuid,uuid,uuid,boolean);


create or replace function cinemarchive_private.mutate_ticket_attachment(
  p_operation_id uuid,p_outing_id uuid,p_attachment_id uuid,p_expected_attachment_id uuid,p_detach boolean,
  p_expected_updated_at timestamptz default null,p_expected_operation_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  outing public.cinema_outings;
  attachment public.ticket_attachments;
  object_metadata jsonb;
  v_result jsonb;
  guarded boolean := p_expected_updated_at is not null or p_expected_operation_id is not null;
  expected_version timestamptz := p_expected_updated_at;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_operation_id is null or p_outing_id is null or p_detach is null or (not p_detach and p_attachment_id is null) or (p_detach and p_attachment_id is not null) then
    raise exception 'Invalid ticket command' using errcode='22023'; end if;
  if (p_expected_updated_at is not null and p_expected_operation_id is not null)
    or (p_expected_updated_at is not null and not isfinite(p_expected_updated_at))
    or p_expected_operation_id=p_operation_id then
    raise exception 'Invalid ticket outing revision guard' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind',case when p_detach then 'ticket.detach' else 'ticket.attach' end,
    'outingId',p_outing_id,'attachmentId',p_attachment_id,'expectedAttachmentId',p_expected_attachment_id));
  if guarded then
    operations := jsonb_set(operations,'{0,expectedUpdatedAt}',coalesce(to_jsonb(p_expected_updated_at),'null'::jsonb));
    operations := jsonb_set(operations,'{0,expectedOperationId}',coalesce(to_jsonb(p_expected_operation_id),'null'::jsonb));
  end if;
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations <> operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result <> '{}'::jsonb then return receipt.result; end if;
  if p_expected_operation_id is not null then
    expected_version := cinemarchive_private.library_causal_revision(p_expected_operation_id,'cinema_outings',jsonb_build_object('id',p_outing_id))::timestamptz;
  end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if not found then raise exception 'Outing ownership required' using errcode='42501'; end if;
  if guarded and outing.updated_at is distinct from expected_version then
    raise exception 'Outing changed on another device; review before saving the ticket' using errcode='40001'; end if;
  if outing.ticket_attachment_id is distinct from p_expected_attachment_id then
    raise exception 'Ticket changed on another device; review before replacing it' using errcode='40001'; end if;
  if not p_detach then
    select * into attachment from public.ticket_attachments where id=p_attachment_id for update;
    if not found or attachment.user_id <> me or attachment.outing_id <> p_outing_id then
      raise exception 'Ticket attachment ownership required' using errcode='42501'; end if;
    if attachment.state not in ('prepared','attached') then raise exception 'Ticket attachment is retired' using errcode='40001'; end if;
    select metadata into object_metadata from storage.objects
      where bucket_id='ticket-attachments' and name=attachment.object_key;
    if not found then raise exception 'Ticket upload is incomplete' using errcode='22023'; end if;
    if object_metadata->>'size' is distinct from attachment.byte_length::text
      or object_metadata->>'mimetype' is distinct from attachment.mime_type then
      raise exception 'Uploaded ticket does not match its metadata' using errcode='22023'; end if;
    update public.ticket_attachments set state='attached' where id=attachment.id;
  end if;
  if outing.ticket_attachment_id is not null and outing.ticket_attachment_id is distinct from p_attachment_id then
    update public.ticket_attachments set state='retired',retired_at=now() where id=outing.ticket_attachment_id;
  end if;
  update public.cinema_outings set ticket_attachment_managed=true,ticket_attachment_id=case when p_detach then null else p_attachment_id end
    where id=p_outing_id returning * into outing;
  v_result := jsonb_build_object('operationId',p_operation_id,'outingId',p_outing_id,
    'attachment',case when p_detach then null else cinemarchive_private.ticket_descriptor(attachment) end,
    'outingUpdatedAt',outing.updated_at,'request',operations->0,'outingRevisionGuarded',guarded,
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',p_outing_id),'row',to_jsonb(outing))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return v_result;
end;
$$;


create function public.finalize_ticket_attachment(
  p_operation_id uuid,p_outing_id uuid,p_attachment_id uuid,p_expected_attachment_id uuid,
  p_expected_updated_at timestamptz default null,p_expected_operation_id uuid default null
) returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.mutate_ticket_attachment(p_operation_id,p_outing_id,p_attachment_id,p_expected_attachment_id,false,p_expected_updated_at,p_expected_operation_id);
$$;
create function public.detach_ticket_attachment(
  p_operation_id uuid,p_outing_id uuid,p_expected_attachment_id uuid,
  p_expected_updated_at timestamptz default null,p_expected_operation_id uuid default null
) returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.mutate_ticket_attachment(p_operation_id,p_outing_id,null,p_expected_attachment_id,true,p_expected_updated_at,p_expected_operation_id);
$$;
revoke all on function public.finalize_ticket_attachment(uuid,uuid,uuid,uuid,timestamptz,uuid),
  public.detach_ticket_attachment(uuid,uuid,uuid,timestamptz,uuid),
  cinemarchive_private.mutate_ticket_attachment(uuid,uuid,uuid,uuid,boolean,timestamptz,uuid) from public,anon;
grant execute on function public.finalize_ticket_attachment(uuid,uuid,uuid,uuid,timestamptz,uuid),
  public.detach_ticket_attachment(uuid,uuid,uuid,timestamptz,uuid),
  cinemarchive_private.mutate_ticket_attachment(uuid,uuid,uuid,uuid,boolean,timestamptz,uuid) to authenticated;


create or replace function cinemarchive_private.complete_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_provisional_viewing_id uuid,
  p_expected_updated_at timestamptz,p_tz text default 'UTC',p_expected_operation_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  completion cinemarchive_private.outing_completions;
  outing public.cinema_outings;
  title public.titles;
  viewing public.viewings;
  previous public.watch_status;
  v_result jsonb;
  names jsonb;
  outcome text;
  expected_version timestamptz;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_outing_id is null or p_operation_id is null or p_provisional_viewing_id is null
    or ((p_expected_updated_at is null) = (p_expected_operation_id is null))
    or (p_expected_updated_at is not null and not isfinite(p_expected_updated_at))
    or p_expected_operation_id=p_operation_id
    or p_tz is null or not exists(select 1 from pg_catalog.pg_timezone_names where name=p_tz) then
    raise exception 'Invalid outing completion command' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind','outing.complete','outingId',p_outing_id,
    'provisionalViewingId',p_provisional_viewing_id,'expectedUpdatedAt',p_expected_updated_at,
    'expectedOperationId',p_expected_operation_id,'timezone',p_tz));
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations<>operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result<>'{}'::jsonb then return cinemarchive_private.outing_completion_current(receipt.result); end if;
  expected_version := p_expected_updated_at;
  if p_expected_operation_id is not null then
    expected_version := cinemarchive_private.library_causal_revision(p_expected_operation_id,'cinema_outings',jsonb_build_object('id',p_outing_id))::timestamptz;
  end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if outing.id is null then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return jsonb_build_object('status','missing','operationId',p_operation_id,'outingId',p_outing_id,
      'request',operations->0,'canonicalViewingId',null,'outing',null,'viewing',null,'title',null);
  end if;
  select * into title from public.titles where id=outing.title_id and user_id=me for update;
  if title.id is null then raise exception 'Outing title ownership required' using errcode='42501'; end if;
  select * into completion from cinemarchive_private.outing_completions where user_id=me and outing_id=p_outing_id;
  if outing.status='completed' then
    if completion.outing_id is null then
      -- Older completion: preserve the explicit link only, including absence after deletion.
      if outing.completed_viewing_id is not null and not exists(select 1 from public.viewings v
        where v.id=outing.completed_viewing_id and v.user_id=me and v.title_id=outing.title_id and v.outing_id=outing.id) then
        raise exception 'Completed outing link requires review' using errcode='40001'; end if;
      insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,previous_status)
        values(me,outing.id,outing.title_id,outing.completed_viewing_id,outing.previous_status) returning * into completion;
    end if;
    outcome := 'already_completed';
  elsif outing.status<>'scheduled' or outing.ends_at>now() or outing.updated_at<>expected_version
    or completion.outing_id is not null or exists(select 1 from public.viewings v where v.user_id=me and v.outing_id=outing.id) then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return cinemarchive_private.outing_completion_current(jsonb_build_object('status','conflict','operationId',p_operation_id,
      'outingId',p_outing_id,'request',operations->0,'canonicalViewingId',completion.canonical_viewing_id));
  else
    previous := title.status;
    insert into public.viewings(id,title_id,user_id,viewed_at,venue,companions,outing_id)
      values(p_provisional_viewing_id,outing.title_id,me,(outing.showtime at time zone p_tz)::date,outing.venue,outing.companions,outing.id)
      returning * into viewing;
    if title.status<>'watched' then
      update public.titles set status='watched' where id=title.id and user_id=me returning * into title;
    end if;
    update public.cinema_outings set status='completed',previous_status=previous,completed_viewing_id=viewing.id
      where id=outing.id and user_id=me returning * into outing;
    insert into cinemarchive_private.outing_completions(user_id,outing_id,title_id,canonical_viewing_id,canonical_viewing_version,completion_outing_version,completed_title_version,previous_status)
      values(me,outing.id,title.id,viewing.id,viewing.updated_at,outing.updated_at,title.updated_at,previous) returning * into completion;
    select coalesce(jsonb_agg(case when jsonb_typeof(c)='string' then c#>>'{}' else c->>'name' end order by n),'[]'::jsonb)
      into names from jsonb_array_elements(outing.companions) with ordinality as x(c,n);
    insert into public.notifications(recipient_id,type,title_id,payload)
      values(me,'outing_completed',title.id,jsonb_build_object('venue',outing.venue,'companions',names,
        'outingId',outing.id,'canonicalViewingId',viewing.id));
    outcome := 'applied';
  end if;
  v_result := jsonb_build_object('status',outcome,'operationId',p_operation_id,'outingId',outing.id,
    'request',operations->0,'canonicalViewingId',completion.canonical_viewing_id,
    'completionOutingVersion',completion.completion_outing_version,
    'rows',jsonb_build_array(jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',outing.id),'row',jsonb_build_object(
      'id',outing.id,'user_id',me,'title_id',outing.title_id,
      'updated_at',completion.completion_outing_version))));
  -- This baseline is the revision created by completion, never a later read of the event.
  -- Historical completions remain unproven; accepted older receipts return above unchanged.
  if completion.canonical_viewing_id is not null and completion.canonical_viewing_version is not null then
    v_result := jsonb_set(v_result,'{rows}',(v_result->'rows') || jsonb_build_array(jsonb_build_object(
      'table','viewings','key',jsonb_build_object('id',completion.canonical_viewing_id),
      'row',jsonb_build_object('id',completion.canonical_viewing_id,'user_id',me,
        'title_id',completion.title_id,'outing_id',completion.outing_id,
        'updated_at',completion.canonical_viewing_version))));
  end if;
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return cinemarchive_private.outing_completion_current(v_result);
end;
$$;

create or replace function cinemarchive_private.revert_cinema_outing(
  p_outing_id uuid,p_operation_id uuid,p_expected_updated_at timestamptz,
  p_expected_viewing_id uuid,p_expected_viewing_updated_at timestamptz,
  p_expected_operation_id uuid default null,p_expected_viewing_operation_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  operations jsonb;
  receipt cinemarchive_private.library_command_receipts;
  completion cinemarchive_private.outing_completions;
  outing public.cinema_outings;
  title public.titles;
  viewing public.viewings;
  canonical uuid;
  restored boolean := false;
  expected_outing_at timestamptz := p_expected_updated_at;
  expected_viewing_at timestamptz := p_expected_viewing_updated_at;
  v_result jsonb;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  if p_outing_id is null or p_operation_id is null or (p_expected_updated_at is null) = (p_expected_operation_id is null)
    or (p_expected_updated_at is not null and not isfinite(p_expected_updated_at))
    or p_expected_operation_id = p_operation_id or p_expected_viewing_operation_id = p_operation_id
    or (p_expected_viewing_operation_id is not null and
      (p_expected_viewing_updated_at is not null or p_expected_viewing_id is null))
    or (p_expected_viewing_updated_at is not null and not isfinite(p_expected_viewing_updated_at)) then
    raise exception 'Invalid outing revert command' using errcode='22023'; end if;
  operations := jsonb_build_array(jsonb_build_object('kind','outing.revert','outingId',p_outing_id,
    'expectedUpdatedAt',p_expected_updated_at,'expectedViewingId',p_expected_viewing_id,
    'expectedViewingUpdatedAt',p_expected_viewing_updated_at));
  -- Keep old no-dependency request signatures byte-for-byte compatible with receipts.
  if p_expected_operation_id is not null then
    operations := jsonb_set(operations,'{0,expectedOperationId}',to_jsonb(p_expected_operation_id)); end if;
  if p_expected_viewing_operation_id is not null then
    operations := jsonb_set(operations,'{0,expectedViewingOperationId}',to_jsonb(p_expected_viewing_operation_id)); end if;
  insert into cinemarchive_private.library_command_receipts(user_id,operation_id,operations)
    values(me,p_operation_id,operations) on conflict do nothing;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id for update;
  if receipt.operations<>operations then raise exception 'Operation ID reused with different data' using errcode='22023'; end if;
  if receipt.result<>'{}'::jsonb then return cinemarchive_private.outing_completion_current(receipt.result); end if;
  if p_expected_operation_id is not null then
    expected_outing_at := cinemarchive_private.library_causal_revision(p_expected_operation_id,'cinema_outings',jsonb_build_object('id',p_outing_id))::timestamptz;
  end if;
  if p_expected_viewing_operation_id is not null then
    expected_viewing_at := cinemarchive_private.library_causal_revision(p_expected_viewing_operation_id,'viewings',jsonb_build_object('id',p_expected_viewing_id))::timestamptz;
  end if;
  select * into outing from public.cinema_outings where id=p_outing_id and user_id=me for update;
  if outing.id is null then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return jsonb_build_object('status','missing','operationId',p_operation_id,'outingId',p_outing_id,
      'request',operations->0,'canonicalViewingId',null,'outing',null,'viewing',null,'title',null);
  end if;
  select * into title from public.titles where id=outing.title_id and user_id=me for update;
  if title.id is null then raise exception 'Outing title ownership required' using errcode='42501'; end if;
  select * into completion from cinemarchive_private.outing_completions where user_id=me and outing_id=p_outing_id;
  canonical := case when completion.outing_id is null then outing.completed_viewing_id else completion.canonical_viewing_id end;
  -- Read the identified row before deciding whether its link is still valid.
  select * into viewing from public.viewings where id=canonical and user_id=me for update;
  if outing.status<>'completed' or outing.updated_at<>expected_outing_at
    or canonical is distinct from p_expected_viewing_id
    or (outing.completed_viewing_id is not null and outing.completed_viewing_id is distinct from canonical)
    or (viewing.id is not null and (viewing.outing_id is distinct from outing.id or viewing.title_id<>title.id
      or viewing.updated_at is distinct from expected_viewing_at or viewing.rating is not null))
    or (viewing.id is null and expected_viewing_at is not null) then
    delete from cinemarchive_private.library_command_receipts where user_id=me and operation_id=p_operation_id;
    return cinemarchive_private.outing_completion_current(jsonb_build_object('status','conflict','operationId',p_operation_id,
      'outingId',p_outing_id,'request',operations->0,'canonicalViewingId',canonical));
  end if;
  if viewing.id is not null then
    delete from public.viewings where id=viewing.id and user_id=me and outing_id=outing.id;
  end if;
  -- A later same-status write is still deliberate. Another completed trip or
  -- remaining viewing also prevents an old trip from rolling back the title.
  if title.status='watched' and completion.previous_status is not null
    and title.updated_at=completion.completed_title_version
    and not exists(select 1 from public.viewings v where v.user_id=me and v.title_id=title.id)
    and not exists(select 1 from public.cinema_outings o where o.user_id=me and o.title_id=title.id and o.id<>outing.id and o.status='completed') then
    if title.status is distinct from completion.previous_status then
      update public.titles set status=completion.previous_status where id=title.id and user_id=me returning * into title;
      restored := true;
    end if;
  end if;
  update public.cinema_outings set status='missed',completed_viewing_id=null
    where id=outing.id and user_id=me returning * into outing;
  v_result := jsonb_build_object('status','applied','operationId',p_operation_id,'outingId',outing.id,
    'request',operations->0,'canonicalViewingId',canonical,'titleStatusRestored',restored,
    'rows',jsonb_build_array(
      jsonb_build_object('table','cinema_outings','key',jsonb_build_object('id',outing.id),'row',to_jsonb(outing)),
      jsonb_build_object('table','titles','key',jsonb_build_object('id',title.id),'row',to_jsonb(title))));
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=me and operation_id=p_operation_id;
  return cinemarchive_private.outing_completion_current(v_result);
end;
$$;

-- A preserved current title is not a causal effect of outing reversal (20261008223218).
create or replace function cinemarchive_private.library_causal_revision(
  p_operation_id uuid, p_table text, p_key jsonb, p_accepted_replay boolean default false
) returns text language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  receipt cinemarchive_private.library_command_receipts;
  revision text;
begin
  if me is null then raise exception 'Authentication required' using errcode='42501'; end if;
  select * into receipt from cinemarchive_private.library_command_receipts
    where user_id=me and operation_id=p_operation_id;
  if p_table='cinema_outings' and not p_accepted_replay then
    if receipt.operations->0->>'kind' in ('ticket.attach','ticket.detach')
      and receipt.result->>'outingRevisionGuarded' is distinct from 'true' then
      raise exception 'Ticket association receipt is not an outing revision guard; review required' using errcode='40001';
    end if;
    if receipt.operations->0->>'kind'='outing.complete'
      and receipt.result->>'completionOutingVersion' is null then
      raise exception 'Historical completion has no proven outing revision; review required' using errcode='40001';
    end if;
  end if;
  if p_table='titles' and not p_accepted_replay
    and receipt.operations->0->>'kind'='outing.revert'
    and receipt.result->>'titleStatusRestored' is distinct from 'true' then
    raise exception 'Reversal preserved the title; refresh or review its revision before editing' using errcode='40001';
  end if;
  select effect.value->'row'->>'updated_at' into revision
    from jsonb_array_elements(coalesce(receipt.result->'rows','[]'::jsonb)) with ordinality effect(value,ordinal)
    where effect.value->>'table'=p_table and effect.value->'key'=p_key
    order by effect.ordinal desc limit 1;
  if revision is null then raise exception 'The preceding change has no matching revision' using errcode='40001'; end if;
  return revision;
end;
$$;

-- Rich title metadata sync (20261008230747).
-- Existing web title metadata exposed to the owner-scoped native sync feed.
-- No title timestamps or stored values change. Clients must backfill from epoch
-- and require titleMetadataVersion before acknowledging this capability.
-- Explicit null/false/empty-array values represent clears; absence on older
-- backends must not clear local metadata or finish the capability backfill.
create or replace function cinemarchive_private.sync_library_changes(p_since timestamptz, p_limit integer default 500)
returns table (
  entity_type text,
  entity_id uuid,
  parent_id uuid,
  updated_at timestamptz,
  payload jsonb
)
language sql security definer stable set search_path = '' as $$
  with changes as (
    select 'title'::text as entity_type, t.id as entity_id, null::uuid as parent_id, t.updated_at as updated_at,
      jsonb_build_object(
        'id', t.id, 'tmdbId', t.tmdb_id, 'type', t.type, 'title', t.title, 'year', t.year,
        'director', t.director, 'genres', t.genres, 'posterUrl', t.poster_url,
        'backdropUrl', t.backdrop_url, 'synopsis', t.synopsis, 'runtime', t.runtime,
        'network', t.network, 'status', t.status, 'rating', t.rating, 'notes', t.notes,
        'addedAt', t.added_at, 'updatedAt', t.updated_at, 'releaseDate', t.release_date,
        'imdbRating', t.imdb_rating, 'originalLanguage', t.original_language,
        'tags', t.tags, 'studios', t.studios,
        'collectionId', t.collection_id, 'collectionName', t.collection_name,
        'personCreditsVersion', 1, 'titleMetadataVersion', 1,
        'contentRating', t.content_rating, 'imdbId', t.imdb_id, 'rtUrl', t.rt_url,
        'rtScore', t.rt_score, 'metacriticScore', t.metacritic_score,
        'customWatchUrl', t.custom_watch_url, 'inHomeCollection', t.in_home_collection,
        'physicalMedia', t.physical_media, 'awardsCount', t.awards_count,
        'bechdelOutcome', t.bechdel_outcome, 'bechdelScore', t.bechdel_score
      ) as payload
    from public.titles t where t.user_id = auth.uid() and t.updated_at > p_since

    union all

    select 'season'::text, s.id, s.title_id, s.updated_at,
      jsonb_build_object(
        'id', s.id, 'titleId', s.title_id, 'seasonNumber', s.season_number,
        'episodeCount', s.episode_count, 'episodesWatched', s.episodes_watched, 'airYear', s.air_year
      )
    from public.seasons s where s.user_id = auth.uid() and s.updated_at > p_since

    union all

    select 'episode'::text, e.id, e.title_id, e.updated_at,
      jsonb_build_object(
        'id', e.id, 'titleId', e.title_id, 'seasonNumber', e.season_number,
        'episodeNumber', e.episode_number, 'episodeName', e.episode_name,
        'airDate', e.air_date, 'runtime', e.runtime,
        'synopsis', e.synopsis, 'stillUrl', e.still_url
      )
    from public.episodes e where e.user_id = auth.uid() and e.updated_at > p_since

    union all

    -- Only the columns the Android mirror holds (core/database/Entities.kt);
    -- profile_url/episode_count stay out rather than inflate a payload nothing reads.
    select 'title_cast'::text, tc.id, tc.title_id, tc.updated_at,
      jsonb_build_object(
        'id', tc.id, 'titleId', tc.title_id, 'tmdbPersonId', tc.tmdb_person_id,
        'name', tc.name, 'characterName', tc.character_name, 'castOrder', tc.cast_order
      )
    from public.title_cast tc where tc.user_id = auth.uid() and tc.updated_at > p_since

    union all

    select 'title_crew'::text, cw.id, cw.title_id, cw.updated_at,
      jsonb_build_object(
        'id', cw.id, 'titleId', cw.title_id, 'tmdbPersonId', cw.tmdb_person_id,
        'name', cw.name, 'job', cw.job, 'department', cw.department
      )
    from public.title_crew cw where cw.user_id = auth.uid() and cw.updated_at > p_since

    union all

    select 'season_cast'::text, sc.id, sc.season_id, sc.updated_at,
      jsonb_build_object(
        'id', sc.id, 'titleId', sc.title_id, 'seasonId', sc.season_id,
        'tmdbPersonId', sc.tmdb_person_id, 'name', sc.name,
        'characterName', sc.character_name, 'castOrder', sc.cast_order
      )
    from public.season_cast sc
    join public.seasons s on s.id = sc.season_id and s.title_id = sc.title_id and s.user_id = sc.user_id
    join public.titles t on t.id = sc.title_id and t.user_id = sc.user_id
    where sc.user_id = auth.uid() and sc.updated_at > p_since

    union all

    select 'episode_crew'::text, ec.id, ec.episode_id, ec.updated_at,
      jsonb_build_object(
        'id', ec.id, 'titleId', ec.title_id, 'episodeId', ec.episode_id,
        'tmdbPersonId', ec.tmdb_person_id, 'name', ec.name, 'job', ec.job
      )
    from public.episode_crew ec
    join public.episodes e on e.id = ec.episode_id and e.title_id = ec.title_id and e.user_id = ec.user_id
    join public.titles t on t.id = ec.title_id and t.user_id = ec.user_id
    where ec.user_id = auth.uid() and ec.updated_at > p_since

    union all

    select 'viewing'::text, v.id, v.title_id, v.updated_at,
      jsonb_build_object(
        'id', v.id, 'titleId', v.title_id, 'date', v.viewed_at, 'rating', v.rating,
        'notes', v.notes, 'venue', v.venue, 'companions', v.companions, 'outingId', v.outing_id
      )
    from public.viewings v where v.user_id = auth.uid() and v.updated_at > p_since

    union all

    select 'episode_watch_event'::text, we.id, we.episode_id, we.updated_at,
      jsonb_build_object('id', we.id, 'episodeId', we.episode_id, 'watchedAt', we.watched_at, 'notes', we.notes)
    from public.episode_watch_events we where we.user_id = auth.uid() and we.updated_at > p_since

    union all

    select 'episode_rating'::text, er.id, er.episode_id, er.updated_at,
      jsonb_build_object('id', er.id, 'episodeId', er.episode_id, 'rating', er.rating, 'ratedAt', er.rated_at)
    from public.episode_ratings er where er.user_id = auth.uid() and er.updated_at > p_since

    union all

    select 'episode_review'::text, rv.id, rv.episode_id, rv.updated_at,
      jsonb_build_object('id', rv.id, 'episodeId', rv.episode_id, 'reviewText', rv.review_text, 'reviewedAt', rv.reviewed_at)
    from public.episode_reviews rv where rv.user_id = auth.uid() and rv.updated_at > p_since

    union all

    select 'cinema_outing'::text, co.id, co.title_id, co.updated_at,
      jsonb_build_object(
        'id', co.id, 'titleId', co.title_id, 'showtime', co.showtime,
        'previewsMinutes', co.previews_minutes, 'runtimeMinutes', co.runtime_minutes,
        'endsAt', co.ends_at, 'venue', co.venue, 'companions', co.companions,
        'format', co.format, 'ticketPrice', co.ticket_price, 'seat', co.seat,
        'auditorium', co.auditorium, 'seatRow', co.seat_row, 'seats', co.seats,
        'bookingRef', co.booking_ref, 'ticketImagePath', co.ticket_image_path,
        'ticketBarcodePayload', co.ticket_barcode_payload, 'ticketBarcodeFormat', co.ticket_barcode_format,
        'notes', co.notes, 'status', co.status,
        'previousStatus', co.previous_status, 'completedViewingId', co.completed_viewing_id,
        'followUpDismissedAt', co.follow_up_dismissed_at, 'createdAt', co.created_at,
        'updatedAt', co.updated_at
      )
    from public.cinema_outings co where co.user_id = auth.uid() and co.updated_at > p_since

    union all

    select 'list'::text, l.id, null::uuid, l.updated_at,
      jsonb_build_object(
        'id', l.id, 'name', l.name, 'description', l.description,
        'createdAt', l.created_at, 'updatedAt', l.updated_at
      )
    from public.lists l where l.user_id = auth.uid() and l.updated_at > p_since

    union all

    select 'list_item'::text, li.id, li.list_id, li.updated_at,
      jsonb_build_object(
        'id', li.id, 'listId', li.list_id, 'titleId', li.title_id,
        'position', li.position, 'addedAt', li.added_at, 'updatedAt', li.updated_at
      )
    from public.list_items li where li.user_id = auth.uid() and li.updated_at > p_since

    union all

    select 'tombstone'::text, st.entity_id, null::uuid, st.deleted_at,
      jsonb_build_object('entityType', st.entity_type)
    from public.sync_tombstones st where st.user_id = auth.uid() and st.deleted_at > p_since
  ),
  ordered as (
    select c.*, row_number() over (order by c.updated_at, c.entity_id) as rn
    from changes c
  )
  -- The limit is a floor, not a ceiling: take every row up to and including the
  -- last one sharing the limit-th row's `updated_at`, so a same-timestamp group is
  -- never split across pages. Both `updated_at` defaults are the *transaction*
  -- timestamp, so a title's whole cast lands on one microsecond, while the client's
  -- cursor is a single watermark advanced with a strict `>` — a split group would
  -- lose its tail permanently and silently
  -- (supabase/migrations/20260726000000_sync_cast_crew_and_scores.sql).
  select o.entity_type, o.entity_id, o.parent_id, o.updated_at, o.payload
  from ordered o
  where o.updated_at <= coalesce(
    (select o2.updated_at from ordered o2 where o2.rn = least(coalesce(p_limit, 500), 500)),
    'infinity'::timestamptz
  )
  order by o.updated_at, o.entity_id;
$$;

revoke all on function cinemarchive_private.sync_library_changes(timestamptz,integer) from public, anon;
grant execute on function cinemarchive_private.sync_library_changes(timestamptz,integer) to authenticated;

-- Private moviegoing preferences (20261008233351).
-- Owner-private moviegoing preferences, shared by both clients.
create table public.venue_notes (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  venue text not null check (venue = btrim(venue) and length(venue) between 1 and 512),
  notes text not null check (length(notes) <= 20000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(user_id, venue)
);

-- The interest identity is the owned title identity, including for tombstones.
create table public.theater_interest (
  id uuid primary key,
  title_id uuid not null unique references public.titles(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (id = title_id)
);
create index theater_interest_user_id_idx on public.theater_interest(user_id);

alter table public.venue_notes enable row level security;
alter table public.theater_interest enable row level security;
create policy "venue notes: owner read" on public.venue_notes for select to authenticated using (user_id = auth.uid());
create policy "theater interest: owner read" on public.theater_interest for select to authenticated using (user_id = auth.uid());
-- Mutations use the existing immutable receipt endpoint. No legacy direct writer exists.
revoke all on public.venue_notes, public.theater_interest from public, anon, authenticated;
grant select on public.venue_notes, public.theater_interest to authenticated;
grant all on public.venue_notes, public.theater_interest to service_role;
create trigger venue_notes_updated_at before update on public.venue_notes
  for each row execute function public.update_updated_at();
create trigger theater_interest_updated_at before update on public.theater_interest
  for each row execute function public.update_updated_at();
create trigger venue_notes_tombstone before delete on public.venue_notes
  for each row execute function public.record_tombstone('venue_note');
create trigger theater_interest_tombstone before delete on public.theater_interest
  for each row execute function public.record_tombstone('theater_interest');

create or replace function cinemarchive_private.apply_library_command(
  p_operation_id uuid, p_operations jsonb
) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_owner uuid := auth.uid();
  v_receipt cinemarchive_private.library_command_receipts%rowtype;
  v_op jsonb;
  v_table text;
  v_action text;
  v_key jsonb;
  v_values jsonb;
  v_row jsonb;
  v_existing jsonb;
  v_allowed text[];
  v_keys text[];
  v_actual_keys text[];
  v_column text;
  v_where text;
  v_columns text;
  v_select text;
  v_assign text;
  v_conflict text;
  v_parent uuid;
  v_credit_put boolean;
  v_rows jsonb[] := ARRAY[]::jsonb[];
  v_result jsonb;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if p_operation_id is null or jsonb_typeof(p_operations) is distinct from 'array'
     or jsonb_array_length(p_operations) not between 1 and 50000
     or octet_length(p_operations::text) > 16777216 then
    raise exception 'Invalid library command' using errcode = '22023';
  end if;

  -- Concurrent retries serialize on this primary key. Failure anywhere below
  -- rolls back the placeholder along with every mutation in the command.
  insert into cinemarchive_private.library_command_receipts(user_id, operation_id, operations)
    values (v_owner, p_operation_id, p_operations) on conflict do nothing;
  select * into strict v_receipt from cinemarchive_private.library_command_receipts
    where user_id = v_owner and operation_id = p_operation_id for update;
  if v_receipt.operations <> p_operations then
    raise exception 'Operation ID reused with different data' using errcode = '22023';
  end if;
  if v_receipt.result <> '{}'::jsonb then return v_receipt.result; end if;

  for v_op in select value from jsonb_array_elements(p_operations) loop
    if jsonb_typeof(v_op) is distinct from 'object' or
       exists(select 1 from jsonb_object_keys(v_op) k where k not in ('table','action','key','values','expectedUpdatedAt')) then
      raise exception 'Invalid command operation' using errcode = '22023';
    end if;
    v_table := v_op->>'table';
    v_action := v_op->>'action';
    v_key := v_op->'key';
    v_values := coalesce(v_op->'values', '{}'::jsonb);
    if v_action is null or v_action not in ('insert','update','delete','put','ensure') or
       jsonb_typeof(v_key) is distinct from 'object' or jsonb_typeof(v_values) is distinct from 'object' then
      raise exception 'Invalid command action or fields' using errcode = '22023';
    end if;
    v_keys := array['id'];
    -- This is an explicit API allowlist, not arbitrary SQL/table access. Owner
    -- identity is always injected from auth.uid(), never accepted from JSON.
    case v_table
      when 'titles' then v_allowed := array['tmdb_id','type','title','year','director','genres','poster_url','backdrop_url','synopsis','runtime','network','status','rating','notes','tags','imdb_rating','rt_score','metacritic_score','studios','added_at','release_date','original_language','content_rating','imdb_id','rt_url','awards_count','bechdel_outcome','bechdel_score','custom_watch_url','in_home_collection','physical_media','collection_id','collection_name'];
      when 'seasons' then v_allowed := array['title_id','season_number','episode_count','episodes_watched','air_year'];
      when 'episodes' then v_allowed := array['title_id','season_number','episode_number','episode_name','air_date','runtime','synopsis','still_url'];
      when 'viewings' then v_allowed := array['title_id','viewed_at','rating','notes','venue','companions','outing_id','created_at'];
      when 'episode_watch_events' then v_allowed := array['episode_id','watched_at','notes','color_mode','created_at'];
      when 'episode_ratings' then v_allowed := array['episode_id','rating','rated_at'];
      when 'episode_reviews' then v_allowed := array['episode_id','review_text','reviewed_at','color_mode'];
      when 'cinema_outings' then v_allowed := array['title_id','showtime','previews_minutes','runtime_minutes','ends_at','venue','companions','format','ticket_price','seat','auditorium','seat_row','seats','booking_ref','ticket_image_path','ticket_barcode_payload','ticket_barcode_format','notes','status','previous_status','completed_viewing_id','follow_up_dismissed_at','created_at'];
      when 'external_title_links' then v_keys := array['provider','external_id']; v_allowed := array['title_id'];
      when 'venue_notes' then v_keys := array['venue']; v_allowed := array['notes'];
      when 'theater_interest' then v_allowed := array['title_id','created_at'];
      when 'lists' then v_allowed := array['name','description','created_at'];
      when 'list_items' then v_keys := array['list_id','title_id']; v_allowed := array['position','added_at'];
      when 'user_title_pins' then v_keys := array['title_id','easter_egg_key']; v_allowed := array['pinned_variant'];
      when 'user_prefs' then v_keys := array[]::text[]; v_allowed := array['ledger_layout'];
      when 'title_cast' then v_keys := array['title_id','tmdb_person_id']; v_allowed := array['name','character_name','episode_count','profile_url','cast_order'];
      when 'title_crew' then v_keys := array['title_id','tmdb_person_id','job']; v_allowed := array['name','department','profile_url'];
      when 'season_cast' then v_keys := array['season_id','tmdb_person_id']; v_allowed := array['title_id','name','character_name','episode_count','profile_url','cast_order'];
      when 'episode_crew' then v_keys := array['episode_id','tmdb_person_id','job']; v_allowed := array['title_id','name'];
      else raise exception 'Unsupported library entity' using errcode = '22023';
    end case;
    -- Catalog fill adopts existing natural identities without updating their
    -- metadata, user progress, UUIDs or history. It cannot ensure arbitrary rows.
    if v_action = 'ensure' then
      if v_table = 'seasons' then
        v_keys := array['title_id','season_number'];
        v_allowed := array['episode_count','air_year'];
      elsif v_table = 'episodes' then
        v_keys := array['title_id','season_number','episode_number'];
        v_allowed := array['episode_name','air_date','runtime','synopsis','still_url'];
      else raise exception 'Ensure is restricted to catalog parents' using errcode='22023'; end if;
      if v_op ? 'expectedUpdatedAt' then raise exception 'Ensure does not replace existing rows' using errcode='22023'; end if;
    end if;
    if v_table = 'venue_notes' then
      if v_action not in ('insert','update','delete')
        or (v_action in ('update','delete') and not v_op ? 'expectedUpdatedAt')
        or (v_action in ('insert','update') and jsonb_typeof(v_values->'notes') is distinct from 'string') then
        raise exception 'Venue notes require text and an observed revision for edits or removal' using errcode='22023';
      end if;
    elsif v_table = 'theater_interest' then
      if v_action not in ('insert','delete')
        or (v_action = 'insert' and v_values->>'title_id' is distinct from v_key->>'id') then
        raise exception 'Theater interest requires the exact owned title identity' using errcode='22023';
      end if;
    end if;
    v_credit_put := v_action = 'put' and v_table in ('title_cast','title_crew','season_cast','episode_crew');
    if v_action = 'put' and not v_credit_put and v_table not in ('user_title_pins','user_prefs') then
      raise exception 'Put is restricted to preferences and provider credits' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_table in ('title_cast','title_crew') and v_key ? 'title_id' and not v_key ? 'tmdb_person_id' then v_keys := array['title_id']; end if;
    if v_action = 'delete' and v_table = 'season_cast' and v_key ? 'season_id' and not v_key ? 'tmdb_person_id' then v_keys := array['season_id']; end if;
    if v_action = 'delete' and v_table = 'episode_crew' and v_key ? 'episode_id' and not v_key ? 'tmdb_person_id' then v_keys := array['episode_id']; end if;
    select coalesce(array_agg(k order by k), array[]::text[]) into v_actual_keys from jsonb_object_keys(v_key) k;
    if not (v_actual_keys @> v_keys and v_keys @> v_actual_keys) or
       exists(select 1 from jsonb_each(v_key) e where e.value = 'null'::jsonb or jsonb_typeof(e.value) not in ('number','string')) or
       exists(select 1 from jsonb_object_keys(v_values) k where not k = any(v_allowed)) then
      raise exception 'Unsupported library fields or identity' using errcode = '22023';
    end if;
    if v_action in ('update','put') and exists(select 1 from jsonb_object_keys(v_values) k
      where k in ('title_id','episode_id','season_id','season_number','episode_number','created_at','added_at')
        and not (v_credit_put and k = 'title_id')) then
      raise exception 'Parent and creation fields are immutable' using errcode = '22023';
    end if;
    if v_action = 'delete' and v_values <> '{}'::jsonb then raise exception 'Delete has no values' using errcode = '22023'; end if;
    v_where := 'r.user_id = $2';
    foreach v_column in array v_keys loop
      v_where := v_where || format(' and r.%I = (jsonb_populate_record(null::public.%I,$1)).%I', v_column, v_table, v_column);
    end loop;
    v_existing := null;
    execute format('select to_jsonb(r) from public.%I r where %s limit 1 for update', v_table, v_where)
      into v_existing using v_key, v_owner;
    if v_op ? 'expectedUpdatedAt' and (v_existing is null or (v_existing->>'updated_at')::timestamptz is distinct from (v_op->>'expectedUpdatedAt')::timestamptz) then
      raise exception 'Library record changed on another device' using errcode = '40001';
    end if;
    if v_action = 'delete' then
      execute format('delete from public.%I r where %s', v_table, v_where) using v_key, v_owner;
      v_rows := array_append(v_rows, jsonb_build_object('table',v_table,'key',v_key,'deleted',true));
      continue;
    end if;
    if v_action = 'update' and v_existing is null then raise exception 'Library record no longer exists' using errcode = 'P0002'; end if;
    if v_action = 'insert' and v_existing is not null then
      foreach v_column in array array['title_id','episode_id','season_id','season_number','episode_number','tmdb_id','type'] loop
        if v_values ? v_column and v_values->v_column is distinct from v_existing->v_column then
          raise exception 'Library identity reused for another record' using errcode='23505';
        end if;
      end loop;
    end if;
    -- A supplied denormalized title is checked, never used to reparent an existing credit.
    if v_credit_put and v_existing is not null and v_values ? 'title_id'
       and v_values->'title_id' is distinct from v_existing->'title_id' then
      raise exception 'Credit parent is immutable' using errcode='22023';
    end if;
    v_row := coalesce(v_existing, '{}'::jsonb) || v_values || v_key || jsonb_build_object('user_id',v_owner);

    if v_action = 'ensure' then
      -- Episodes have no season UUID FK. Hold the owned natural parent through
      -- insertion so a concurrent parent deletion cannot race this check.
      perform id from public.titles where id=(v_key->>'title_id')::uuid and user_id=v_owner for key share;
      if not found then raise exception 'Title ownership required' using errcode='42501'; end if;
      if v_table = 'episodes' then
        perform id from public.seasons where title_id=(v_key->>'title_id')::uuid
          and season_number=(v_key->>'season_number')::integer and user_id=v_owner for key share;
        if not found then raise exception 'Episode season required' using errcode='23503'; end if;
      end if;
    end if;

    -- Parent ownership is checked even where historical RLS only checked the
    -- child's user_id. A caller cannot attach their row to another user's graph.
    if v_row ? 'title_id' then
      v_parent := (v_row->>'title_id')::uuid;
      if not exists(select 1 from public.titles where id=v_parent and user_id=v_owner) then raise exception 'Title ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'episode_id' then
      v_parent := (v_row->>'episode_id')::uuid;
      if not exists(select 1 from public.episodes where id=v_parent and user_id=v_owner and
        (not v_row ? 'title_id' or title_id=(v_row->>'title_id')::uuid)) then raise exception 'Episode ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'season_id' then
      v_parent := (v_row->>'season_id')::uuid;
      if not exists(select 1 from public.seasons where id=v_parent and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Season ownership required' using errcode='42501'; end if;
    end if;
    if v_row ? 'list_id' and not exists(select 1 from public.lists where id=(v_row->>'list_id')::uuid and user_id=v_owner) then raise exception 'List ownership required' using errcode='42501'; end if;
    if v_table='episodes' and not exists(select 1 from public.seasons where title_id=(v_row->>'title_id')::uuid and season_number=(v_row->>'season_number')::integer and user_id=v_owner) then raise exception 'Episode season required' using errcode='23503'; end if;
    if v_row->>'outing_id' is not null and not exists(select 1 from public.cinema_outings where id=(v_row->>'outing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Outing ownership required' using errcode='42501'; end if;
    if v_row->>'completed_viewing_id' is not null and not exists(select 1 from public.viewings where id=(v_row->>'completed_viewing_id')::uuid and user_id=v_owner and title_id=(v_row->>'title_id')::uuid) then raise exception 'Viewing ownership required' using errcode='42501'; end if;

    if v_action = 'update' or (v_action='put' and v_existing is not null) then
      select string_agg(format('%I = p.%I', k, k), ',') into v_assign from jsonb_object_keys(v_values) k
        where not (v_credit_put and k = 'title_id');
      if v_assign is not null then
        execute format('update public.%I r set %s from jsonb_populate_record(null::public.%I,$3) p where %s returning to_jsonb(r)',v_table,v_assign,v_table,v_where)
          into v_row using v_key,v_owner,v_values;
      else v_row := v_existing; end if;
    elsif v_existing is not null then
      -- Insert retries and catalog ensure must not undo existing metadata or progress.
      v_row := v_existing;
    else
      v_row := v_values || v_key || jsonb_build_object('user_id',v_owner);
      select string_agg(format('%I',k),','),string_agg(format('p.%I',k),',') into v_columns,v_select from jsonb_object_keys(v_row) k;
      v_conflict := 'do nothing';
      if v_action='put' then
        select string_agg(format('%I = excluded.%I',k,k),',') into v_assign from jsonb_object_keys(v_values) k
          where not (v_credit_put and k = 'title_id');
        if v_assign is null then raise exception 'Put requires values' using errcode='22023'; end if;
        select string_agg(format('%I',k),',') into v_conflict from unnest(case when v_credit_put then v_keys else array['user_id'] || v_keys end) k;
        v_conflict := '(' || v_conflict || ') do update set ' || v_assign;
        if v_credit_put then
          -- Credit uniqueness excludes user_id. A concurrent or legacy foreign
          -- row must never be adopted, and neither its UUID nor parent may change.
          v_conflict := v_conflict || format(' where %I.user_id = excluded.user_id and %I.title_id = excluded.title_id',v_table,v_table);
        end if;
      end if;
      execute format('insert into public.%I (%s) select %s from jsonb_populate_record(null::public.%I,$1) p on conflict %s returning to_jsonb(%I)',v_table,v_columns,v_select,v_table,v_conflict,v_table)
        into v_row using v_row;
      if v_row is null then
        if v_credit_put then raise exception 'Credit identity conflicts with an existing owner or parent' using errcode='23505'; end if;
        execute format('select to_jsonb(r) from public.%I r where %s',v_table,v_where) into v_row using v_key,v_owner;
        if v_row is null then raise exception 'Library identity conflicts with an existing record' using errcode='23505'; end if;
      end if;
    end if;
    -- A racing first save must not silently adopt another device's different note.
    -- This also checks the post-conflict row, not only the earlier FOR UPDATE read.
    if v_table = 'venue_notes' and v_action = 'insert'
      and v_row->'notes' is distinct from v_values->'notes' then
      raise exception 'Venue notes already exist; compare before replacing them' using errcode='23505';
    end if;
    v_rows := array_append(v_rows, jsonb_build_object('table',v_table,'key',v_key,'row',v_row));
  end loop;
  v_result := jsonb_build_object('operationId',p_operation_id,'rows',v_rows);
  update cinemarchive_private.library_command_receipts set result=v_result where user_id=v_owner and operation_id=p_operation_id;
  return v_result;
end;
$$;

create or replace function cinemarchive_private.sync_library_changes(p_since timestamptz, p_limit integer default 500)
returns table (
  entity_type text,
  entity_id uuid,
  parent_id uuid,
  updated_at timestamptz,
  payload jsonb
)
language sql security definer stable set search_path = '' as $$
  with changes as (
    select 'title'::text as entity_type, t.id as entity_id, null::uuid as parent_id, t.updated_at as updated_at,
      jsonb_build_object(
        'id', t.id, 'tmdbId', t.tmdb_id, 'type', t.type, 'title', t.title, 'year', t.year,
        'director', t.director, 'genres', t.genres, 'posterUrl', t.poster_url,
        'backdropUrl', t.backdrop_url, 'synopsis', t.synopsis, 'runtime', t.runtime,
        'network', t.network, 'status', t.status, 'rating', t.rating, 'notes', t.notes,
        'addedAt', t.added_at, 'updatedAt', t.updated_at, 'releaseDate', t.release_date,
        'imdbRating', t.imdb_rating, 'originalLanguage', t.original_language,
        'tags', t.tags, 'studios', t.studios,
        'collectionId', t.collection_id, 'collectionName', t.collection_name,
        'personCreditsVersion', 1, 'titleMetadataVersion', 1, 'moviegoingPreferencesVersion', 1,
        'contentRating', t.content_rating, 'imdbId', t.imdb_id, 'rtUrl', t.rt_url,
        'rtScore', t.rt_score, 'metacriticScore', t.metacritic_score,
        'customWatchUrl', t.custom_watch_url, 'inHomeCollection', t.in_home_collection,
        'physicalMedia', t.physical_media, 'awardsCount', t.awards_count,
        'bechdelOutcome', t.bechdel_outcome, 'bechdelScore', t.bechdel_score
      ) as payload
    from public.titles t where t.user_id = auth.uid() and t.updated_at > p_since

    union all

    select 'season'::text, s.id, s.title_id, s.updated_at,
      jsonb_build_object(
        'id', s.id, 'titleId', s.title_id, 'seasonNumber', s.season_number,
        'episodeCount', s.episode_count, 'episodesWatched', s.episodes_watched, 'airYear', s.air_year
      )
    from public.seasons s where s.user_id = auth.uid() and s.updated_at > p_since

    union all

    select 'episode'::text, e.id, e.title_id, e.updated_at,
      jsonb_build_object(
        'id', e.id, 'titleId', e.title_id, 'seasonNumber', e.season_number,
        'episodeNumber', e.episode_number, 'episodeName', e.episode_name,
        'airDate', e.air_date, 'runtime', e.runtime,
        'synopsis', e.synopsis, 'stillUrl', e.still_url
      )
    from public.episodes e where e.user_id = auth.uid() and e.updated_at > p_since

    union all

    -- Only the columns the Android mirror holds (core/database/Entities.kt);
    -- profile_url/episode_count stay out rather than inflate a payload nothing reads.
    select 'title_cast'::text, tc.id, tc.title_id, tc.updated_at,
      jsonb_build_object(
        'id', tc.id, 'titleId', tc.title_id, 'tmdbPersonId', tc.tmdb_person_id,
        'name', tc.name, 'characterName', tc.character_name, 'castOrder', tc.cast_order
      )
    from public.title_cast tc where tc.user_id = auth.uid() and tc.updated_at > p_since

    union all

    select 'title_crew'::text, cw.id, cw.title_id, cw.updated_at,
      jsonb_build_object(
        'id', cw.id, 'titleId', cw.title_id, 'tmdbPersonId', cw.tmdb_person_id,
        'name', cw.name, 'job', cw.job, 'department', cw.department
      )
    from public.title_crew cw where cw.user_id = auth.uid() and cw.updated_at > p_since

    union all

    select 'season_cast'::text, sc.id, sc.season_id, sc.updated_at,
      jsonb_build_object(
        'id', sc.id, 'titleId', sc.title_id, 'seasonId', sc.season_id,
        'tmdbPersonId', sc.tmdb_person_id, 'name', sc.name,
        'characterName', sc.character_name, 'castOrder', sc.cast_order
      )
    from public.season_cast sc
    join public.seasons s on s.id = sc.season_id and s.title_id = sc.title_id and s.user_id = sc.user_id
    join public.titles t on t.id = sc.title_id and t.user_id = sc.user_id
    where sc.user_id = auth.uid() and sc.updated_at > p_since

    union all

    select 'episode_crew'::text, ec.id, ec.episode_id, ec.updated_at,
      jsonb_build_object(
        'id', ec.id, 'titleId', ec.title_id, 'episodeId', ec.episode_id,
        'tmdbPersonId', ec.tmdb_person_id, 'name', ec.name, 'job', ec.job
      )
    from public.episode_crew ec
    join public.episodes e on e.id = ec.episode_id and e.title_id = ec.title_id and e.user_id = ec.user_id
    join public.titles t on t.id = ec.title_id and t.user_id = ec.user_id
    where ec.user_id = auth.uid() and ec.updated_at > p_since

    union all

    select 'viewing'::text, v.id, v.title_id, v.updated_at,
      jsonb_build_object(
        'id', v.id, 'titleId', v.title_id, 'date', v.viewed_at, 'rating', v.rating,
        'notes', v.notes, 'venue', v.venue, 'companions', v.companions, 'outingId', v.outing_id
      )
    from public.viewings v where v.user_id = auth.uid() and v.updated_at > p_since

    union all

    select 'episode_watch_event'::text, we.id, we.episode_id, we.updated_at,
      jsonb_build_object('id', we.id, 'episodeId', we.episode_id, 'watchedAt', we.watched_at, 'notes', we.notes)
    from public.episode_watch_events we where we.user_id = auth.uid() and we.updated_at > p_since

    union all

    select 'episode_rating'::text, er.id, er.episode_id, er.updated_at,
      jsonb_build_object('id', er.id, 'episodeId', er.episode_id, 'rating', er.rating, 'ratedAt', er.rated_at)
    from public.episode_ratings er where er.user_id = auth.uid() and er.updated_at > p_since

    union all

    select 'episode_review'::text, rv.id, rv.episode_id, rv.updated_at,
      jsonb_build_object('id', rv.id, 'episodeId', rv.episode_id, 'reviewText', rv.review_text, 'reviewedAt', rv.reviewed_at)
    from public.episode_reviews rv where rv.user_id = auth.uid() and rv.updated_at > p_since

    union all

    select 'cinema_outing'::text, co.id, co.title_id, co.updated_at,
      jsonb_build_object(
        'id', co.id, 'titleId', co.title_id, 'showtime', co.showtime,
        'previewsMinutes', co.previews_minutes, 'runtimeMinutes', co.runtime_minutes,
        'endsAt', co.ends_at, 'venue', co.venue, 'companions', co.companions,
        'format', co.format, 'ticketPrice', co.ticket_price, 'seat', co.seat,
        'auditorium', co.auditorium, 'seatRow', co.seat_row, 'seats', co.seats,
        'bookingRef', co.booking_ref, 'ticketImagePath', co.ticket_image_path,
        'ticketBarcodePayload', co.ticket_barcode_payload, 'ticketBarcodeFormat', co.ticket_barcode_format,
        'notes', co.notes, 'status', co.status,
        'previousStatus', co.previous_status, 'completedViewingId', co.completed_viewing_id,
        'followUpDismissedAt', co.follow_up_dismissed_at, 'createdAt', co.created_at,
        'updatedAt', co.updated_at
      )
    from public.cinema_outings co where co.user_id = auth.uid() and co.updated_at > p_since

    union all

    select 'list'::text, l.id, null::uuid, l.updated_at,
      jsonb_build_object(
        'id', l.id, 'name', l.name, 'description', l.description,
        'createdAt', l.created_at, 'updatedAt', l.updated_at
      )
    from public.lists l where l.user_id = auth.uid() and l.updated_at > p_since

    union all

    select 'list_item'::text, li.id, li.list_id, li.updated_at,
      jsonb_build_object(
        'id', li.id, 'listId', li.list_id, 'titleId', li.title_id,
        'position', li.position, 'addedAt', li.added_at, 'updatedAt', li.updated_at
      )
    from public.list_items li where li.user_id = auth.uid() and li.updated_at > p_since

    union all

    select 'venue_note'::text, vn.id, null::uuid, vn.updated_at,
      jsonb_build_object('id', vn.id, 'venue', vn.venue, 'notes', vn.notes,
        'createdAt', vn.created_at, 'updatedAt', vn.updated_at, 'moviegoingPreferencesVersion', 1)
    from public.venue_notes vn where vn.user_id = auth.uid() and vn.updated_at > p_since

    union all

    select 'theater_interest'::text, ti.id, ti.title_id, ti.updated_at,
      jsonb_build_object('id', ti.id, 'titleId', ti.title_id, 'createdAt', ti.created_at,
        'updatedAt', ti.updated_at, 'moviegoingPreferencesVersion', 1)
    from public.theater_interest ti where ti.user_id = auth.uid() and ti.updated_at > p_since

    union all

    select 'tombstone'::text, st.entity_id, null::uuid, st.deleted_at,
      jsonb_build_object('entityType', st.entity_type)
    from public.sync_tombstones st where st.user_id = auth.uid() and st.deleted_at > p_since
  ),
  ordered as (
    select c.*, row_number() over (order by c.updated_at, c.entity_id) as rn
    from changes c
  )
  -- The limit is a floor, not a ceiling: take every row up to and including the
  -- last one sharing the limit-th row's `updated_at`, so a same-timestamp group is
  -- never split across pages. Both `updated_at` defaults are the *transaction*
  -- timestamp, so a title's whole cast lands on one microsecond, while the client's
  -- cursor is a single watermark advanced with a strict `>` — a split group would
  -- lose its tail permanently and silently
  -- (supabase/migrations/20260726000000_sync_cast_crew_and_scores.sql).
  select o.entity_type, o.entity_id, o.parent_id, o.updated_at, o.payload
  from ordered o
  where o.updated_at <= coalesce(
    (select o2.updated_at from ordered o2 where o2.rn = least(coalesce(p_limit, 500), 500)),
    'infinity'::timestamptz
  )
  order by o.updated_at, o.entity_id;
$$;

revoke all on function cinemarchive_private.sync_library_changes(timestamptz,integer) from public, anon;
grant execute on function cinemarchive_private.sync_library_changes(timestamptz,integer) to authenticated;
