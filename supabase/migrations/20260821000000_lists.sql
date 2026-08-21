-- User-created custom lists of movies/TV titles (e.g. "Best of 2026", "Halloween
-- marathon"). Private-only for v1 — owner-only RLS, no sharing (that's a follow-up).
-- A title can belong to many lists and a list can hold many titles (many-to-many via
-- list_items), fully additive to titles.status (the watchlist/watched/watching/dropped
-- enum) — a title's list memberships are completely independent of its watch status.
--
-- Naming: deliberately "list"/"list_items", not "collection" (already used for TMDB
-- franchise grouping via titles.collection_id/collection_name, and for
-- titles.in_home_collection/physical_media) and not "watchlist" (already a
-- titles.status enum value).
--
-- list_items uses a surrogate `id uuid` primary key, not a composite key like
-- user_title_pins — user_title_pins is never synced (no tombstone trigger, no
-- sync_library_changes arm), but list_items must be: record_tombstone() needs a
-- single old.id column, and Android's outbox contract (docs/android-sync-contract.md
-- §4.2) is id-keyed upsert throughout. Duplicate membership is instead prevented by
-- an explicit unique constraint. list_items also carries a redundant user_id (like
-- seasons/episodes do despite their own FK chain) because record_tombstone() reads
-- old.user_id directly off the row being deleted.
--
-- list_items.position is nullable and unused in v1 (UI orders by added_at) —
-- reordering is a follow-up, but the column ships now so a future reorder feature
-- never needs a schema migration or (per SYNC_SCHEMA_VERSION's contract) a forced
-- Android resync-from-epoch to backfill it onto already-synced rows.

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

create trigger lists_updated_at before update on lists
  for each row execute function update_updated_at();

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

create trigger list_items_updated_at before update on list_items
  for each row execute function update_updated_at();

create trigger lists_tombstone      before delete on lists      for each row execute function record_tombstone('list');
create trigger list_items_tombstone before delete on list_items for each row execute function record_tombstone('list_item');

-- Republish the Android sync payload with the two new arms. Unchanged from
-- 20260819000000_cinema_outing_ticket_capture.sql apart from the list/list_item arms
-- (added just before the tombstone arm).
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
