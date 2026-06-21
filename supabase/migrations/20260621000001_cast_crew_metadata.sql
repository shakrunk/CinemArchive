-- Extended metadata: normalized cast/crew tables + studios column
-- Enables future "browse by actor / writer" queries across the library.

alter table titles add column studios text[] default '{}';

-- ── title_cast ───────────────────────────────────────────────────────────────
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

-- ── title_crew ───────────────────────────────────────────────────────────────
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

-- ── season_cast ──────────────────────────────────────────────────────────────
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

-- ── episode_crew ─────────────────────────────────────────────────────────────
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

-- ── Indexes ──────────────────────────────────────────────────────────────────
create index title_cast_title_id_idx   on title_cast(title_id);
create index title_cast_person_id_idx  on title_cast(tmdb_person_id);
create index title_crew_title_id_idx   on title_crew(title_id);
create index title_crew_person_id_idx  on title_crew(tmdb_person_id);
create index season_cast_season_id_idx on season_cast(season_id);
create index season_cast_person_id_idx on season_cast(tmdb_person_id);
create index ep_crew_episode_id_idx    on episode_crew(episode_id);
create index ep_crew_person_id_idx     on episode_crew(tmdb_person_id);

-- ── RLS ──────────────────────────────────────────────────────────────────────
alter table title_cast   enable row level security;
alter table title_crew   enable row level security;
alter table season_cast  enable row level security;
alter table episode_crew enable row level security;

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
