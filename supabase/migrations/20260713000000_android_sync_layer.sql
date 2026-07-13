-- Android sync layer: bootstrap + incremental sync support for the native
-- Android client. See docs/android-sync-contract.md for the full design and
-- docs/android-contracts/ for the per-domain contracts this implements.
--
-- Validated end-to-end against the cinemarchive-android-test Supabase
-- project (2026-07-12/13) before being promoted here, including a fix for
-- an account-deletion foreign-key cascade bug found during that validation
-- (see docs/android-sync-contract.md §3.3) — sync_tombstones.user_id is
-- deliberately NOT a foreign key to auth.users for that reason.

-- ============================================================
-- updated_at on every synchronizable table currently missing it
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

-- ============================================================
-- Tombstones
-- ============================================================

create table sync_tombstones (
  id            uuid primary key default gen_random_uuid(),
  -- deliberately NOT a foreign key to auth.users — see the header note above
  -- and docs/android-sync-contract.md §3.3.
  user_id       uuid not null,
  entity_type   text not null,   -- matches sync_library_changes.entity_type values
  entity_id     uuid not null,
  deleted_at    timestamptz not null default now()
);

create index sync_tombstones_user_deleted_idx on sync_tombstones(user_id, deleted_at);

alter table sync_tombstones enable row level security;

create policy "sync_tombstones: owner read"
  on sync_tombstones for select
  using (auth.uid() = user_id);
-- no client insert/update/delete policy — every row is written by
-- record_tombstone() (SECURITY DEFINER), the same single-choke-point
-- pattern friendships/recommendations/notifications already use.

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

-- ============================================================
-- sync_library_changes RPC
-- ============================================================
-- Scoped to the three domains contracted in docs/android-contracts/
-- (Library, Title detail, Episode tracking). Cast/crew and other domains
-- are deferred — see docs/android-implementation-status.md.

create or replace function sync_library_changes(p_since timestamptz, p_limit integer default 500)
returns table (
  entity_type text,
  entity_id uuid,
  parent_id uuid,
  updated_at timestamptz,
  payload jsonb
)
language sql security definer stable as $$
  select * from (
    select 'title'::text as entity_type, t.id as entity_id, null::uuid as parent_id, t.updated_at as updated_at,
      jsonb_build_object(
        'id', t.id, 'tmdbId', t.tmdb_id, 'type', t.type, 'title', t.title, 'year', t.year,
        'director', t.director, 'genres', t.genres, 'posterUrl', t.poster_url,
        'backdropUrl', t.backdrop_url, 'synopsis', t.synopsis, 'runtime', t.runtime,
        'network', t.network, 'status', t.status, 'rating', t.rating, 'notes', t.notes,
        'addedAt', t.added_at, 'updatedAt', t.updated_at
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
        'airDate', e.air_date, 'runtime', e.runtime
      )
    from episodes e where e.user_id = auth.uid() and e.updated_at > p_since

    union all

    select 'viewing'::text, v.id, v.title_id, v.updated_at,
      jsonb_build_object(
        'id', v.id, 'titleId', v.title_id, 'date', v.viewed_at, 'rating', v.rating,
        'notes', v.notes, 'venue', v.venue
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

    select 'tombstone'::text, st.entity_id, null::uuid, st.deleted_at,
      jsonb_build_object('entityType', st.entity_type)
    from sync_tombstones st where st.user_id = auth.uid() and st.deleted_at > p_since
  ) changes
  order by updated_at, entity_id
  limit least(coalesce(p_limit, 500), 500);
$$;
