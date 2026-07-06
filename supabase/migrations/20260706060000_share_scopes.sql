-- ============================================================
-- SHARE SCOPES — per-friend / per-link narrowing of library visibility
-- ============================================================
--
-- Absence of a row for a given link/friend means UNRESTRICTED (today's
-- existing behavior) — this is opt-in narrowing only, never opt-in
-- widening. That invariant is load-bearing and is exercised explicitly by
-- scripts/verify-share-scope-logic.mjs; do not change the "no row -> allow
-- everything" polarity without updating that script and re-verifying
-- against a real Postgres (this migration replaces live RLS policies and
-- was NOT validated against one in the environment it was authored in —
-- see the test checklist in that script's header comment before merging
-- this to main).
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
-- restriction on that dimension." Kept separate from can_view_title so the
-- genre/status matching logic isn't duplicated between the shared-link and
-- friend branches below.
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
-- is_friend(...) USING clauses across every shareable content table.
-- SECURITY DEFINER so it can read shared_access_keys/share_scopes (owner-only
-- RLS) and friendships regardless of the caller's own visibility into those
-- tables — same reasoning as is_valid_shared_token/is_friend. Deliberately
-- takes the title's columns as parameters rather than a title_id + an
-- internal re-select from `titles`: this function is invoked from the
-- `titles` table's own SELECT policy, and having it read `titles` again
-- internally would create a self-referential RLS evaluation on the very
-- table whose policy is calling it. Taking columns directly avoids that
-- entirely, for every caller (titles gets its own row's columns for free;
-- child tables join to titles once to fetch genres/status, see below).
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
-- Replace the per-table "shared key read" / "friend read" policy pairs
-- with one "shared/friend read" policy calling can_view_title. Owner
-- full-access policies are untouched. user_prefs is NOT touched here — it's
-- a whole-account board layout, not title-scoped (see its own section).
-- -----------------------------------------------------------

drop policy "titles: shared key read" on titles;
drop policy "titles: friend read" on titles;
create policy "titles: shared/friend read"
  on titles for select
  using (can_view_title(user_id, genres, status));

drop policy "seasons: shared key read" on seasons;
drop policy "seasons: friend read" on seasons;
create policy "seasons: shared/friend read"
  on seasons for select
  using (
    exists (
      select 1 from titles t
      where t.id = seasons.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

drop policy "viewings: shared key read" on viewings;
drop policy "viewings: friend read" on viewings;
create policy "viewings: shared/friend read"
  on viewings for select
  using (
    exists (
      select 1 from titles t
      where t.id = viewings.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

drop policy "episodes: shared key read" on episodes;
drop policy "episodes: friend read" on episodes;
create policy "episodes: shared/friend read"
  on episodes for select
  using (
    exists (
      select 1 from titles t
      where t.id = episodes.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

drop policy "episode_watch_events: shared key read" on episode_watch_events;
drop policy "episode_watch_events: friend read" on episode_watch_events;
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

drop policy "episode_ratings: shared key read" on episode_ratings;
drop policy "episode_ratings: friend read" on episode_ratings;
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

drop policy "episode_reviews: shared key read" on episode_reviews;
drop policy "episode_reviews: friend read" on episode_reviews;
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

drop policy "title_cast: shared key read" on title_cast;
drop policy "title_cast: friend read" on title_cast;
create policy "title_cast: shared/friend read"
  on title_cast for select
  using (
    exists (
      select 1 from titles t
      where t.id = title_cast.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

drop policy "title_crew: shared key read" on title_crew;
drop policy "title_crew: friend read" on title_crew;
create policy "title_crew: shared/friend read"
  on title_crew for select
  using (
    exists (
      select 1 from titles t
      where t.id = title_crew.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

drop policy "season_cast: shared key read" on season_cast;
drop policy "season_cast: friend read" on season_cast;
create policy "season_cast: shared/friend read"
  on season_cast for select
  using (
    exists (
      select 1 from titles t
      where t.id = season_cast.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );

drop policy "episode_crew: shared key read" on episode_crew;
drop policy "episode_crew: friend read" on episode_crew;
create policy "episode_crew: shared/friend read"
  on episode_crew for select
  using (
    exists (
      select 1 from titles t
      where t.id = episode_crew.title_id
        and can_view_title(t.user_id, t.genres, t.status)
    )
  );
