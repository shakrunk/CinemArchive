-- Sync page cutoff (20261025220000).
-- sync_library_changes built a payload for every row after p_since, ranked them all, and only
-- then kept one page — each page cost the whole remaining library, so a from-epoch resync of a
-- large archive (~135 pages) took minutes and was easily interrupted, which restarts it from
-- epoch. Find the page's cutoff timestamp from per-arm `updated_at` alone first (index range
-- scans, at most p_limit rows per arm), then build payloads only up to that cutoff.
-- Rows returned are unchanged, including the same-timestamp widening: the cutoff is still the
-- p_limit-th smallest `updated_at` among every row after p_since, and the p_limit smallest values
-- overall are always within each arm's own p_limit smallest.

create index if not exists titles_user_updated_idx on public.titles (user_id, updated_at);
create index if not exists seasons_user_updated_idx on public.seasons (user_id, updated_at);
create index if not exists episodes_user_updated_idx on public.episodes (user_id, updated_at);
create index if not exists title_cast_user_updated_idx on public.title_cast (user_id, updated_at);
create index if not exists title_crew_user_updated_idx on public.title_crew (user_id, updated_at);
create index if not exists season_cast_user_updated_idx on public.season_cast (user_id, updated_at);
create index if not exists episode_crew_user_updated_idx on public.episode_crew (user_id, updated_at);
create index if not exists viewings_user_updated_idx on public.viewings (user_id, updated_at);
create index if not exists episode_watch_events_user_updated_idx on public.episode_watch_events (user_id, updated_at);
create index if not exists episode_ratings_user_updated_idx on public.episode_ratings (user_id, updated_at);
create index if not exists episode_reviews_user_updated_idx on public.episode_reviews (user_id, updated_at);
create index if not exists cinema_outings_user_updated_idx on public.cinema_outings (user_id, updated_at);
create index if not exists lists_user_updated_idx on public.lists (user_id, updated_at);
create index if not exists list_items_user_updated_idx on public.list_items (user_id, updated_at);
create index if not exists venue_notes_user_updated_idx on public.venue_notes (user_id, updated_at);
create index if not exists theater_interest_user_updated_idx on public.theater_interest (user_id, updated_at);

create or replace function cinemarchive_private.sync_library_changes(p_since timestamptz, p_limit integer default 500)
returns table (
  entity_type text,
  entity_id uuid,
  parent_id uuid,
  updated_at timestamptz,
  payload jsonb
)
language sql security definer stable set search_path = '' as $$
  with page as (
    select least(coalesce(p_limit, 500), 500) as size
  ),
  -- Each arm's filters (and, for season_cast/episode_crew, its joins) must match `changes`
  -- below exactly: a candidate the payload arm then drops would cut the page short, and the
  -- client reads a short page as "nothing left".
  candidates as (
    (select t.updated_at as ts from public.titles t
      where t.user_id = auth.uid() and t.updated_at > p_since
      order by t.updated_at limit (select greatest(size, 0) from page))
    union all
    (select s.updated_at from public.seasons s
      where s.user_id = auth.uid() and s.updated_at > p_since
      order by s.updated_at limit (select greatest(size, 0) from page))
    union all
    (select e.updated_at from public.episodes e
      where e.user_id = auth.uid() and e.updated_at > p_since
      order by e.updated_at limit (select greatest(size, 0) from page))
    union all
    (select tc.updated_at from public.title_cast tc
      where tc.user_id = auth.uid() and tc.updated_at > p_since
      order by tc.updated_at limit (select greatest(size, 0) from page))
    union all
    (select cw.updated_at from public.title_crew cw
      where cw.user_id = auth.uid() and cw.updated_at > p_since
      order by cw.updated_at limit (select greatest(size, 0) from page))
    union all
    (select sc.updated_at from public.season_cast sc
      join public.seasons s on s.id = sc.season_id and s.title_id = sc.title_id and s.user_id = sc.user_id
      join public.titles t on t.id = sc.title_id and t.user_id = sc.user_id
      where sc.user_id = auth.uid() and sc.updated_at > p_since
      order by sc.updated_at limit (select greatest(size, 0) from page))
    union all
    (select ec.updated_at from public.episode_crew ec
      join public.episodes e on e.id = ec.episode_id and e.title_id = ec.title_id and e.user_id = ec.user_id
      join public.titles t on t.id = ec.title_id and t.user_id = ec.user_id
      where ec.user_id = auth.uid() and ec.updated_at > p_since
      order by ec.updated_at limit (select greatest(size, 0) from page))
    union all
    (select v.updated_at from public.viewings v
      where v.user_id = auth.uid() and v.updated_at > p_since
      order by v.updated_at limit (select greatest(size, 0) from page))
    union all
    (select we.updated_at from public.episode_watch_events we
      where we.user_id = auth.uid() and we.updated_at > p_since
      order by we.updated_at limit (select greatest(size, 0) from page))
    union all
    (select er.updated_at from public.episode_ratings er
      where er.user_id = auth.uid() and er.updated_at > p_since
      order by er.updated_at limit (select greatest(size, 0) from page))
    union all
    (select rv.updated_at from public.episode_reviews rv
      where rv.user_id = auth.uid() and rv.updated_at > p_since
      order by rv.updated_at limit (select greatest(size, 0) from page))
    union all
    (select co.updated_at from public.cinema_outings co
      where co.user_id = auth.uid() and co.updated_at > p_since
      order by co.updated_at limit (select greatest(size, 0) from page))
    union all
    (select l.updated_at from public.lists l
      where l.user_id = auth.uid() and l.updated_at > p_since
      order by l.updated_at limit (select greatest(size, 0) from page))
    union all
    (select li.updated_at from public.list_items li
      where li.user_id = auth.uid() and li.updated_at > p_since
      order by li.updated_at limit (select greatest(size, 0) from page))
    union all
    (select vn.updated_at from public.venue_notes vn
      where vn.user_id = auth.uid() and vn.updated_at > p_since
      order by vn.updated_at limit (select greatest(size, 0) from page))
    union all
    (select ti.updated_at from public.theater_interest ti
      where ti.user_id = auth.uid() and ti.updated_at > p_since
      order by ti.updated_at limit (select greatest(size, 0) from page))
    union all
    (select st.deleted_at from public.sync_tombstones st
      where st.user_id = auth.uid() and st.deleted_at > p_since
      order by st.deleted_at limit (select greatest(size, 0) from page))
  ),
  -- The limit is a floor, not a ceiling: take every row up to and including the
  -- last one sharing the limit-th row's `updated_at`, so a same-timestamp group is
  -- never split across pages. Both `updated_at` defaults are the *transaction*
  -- timestamp, so a title's whole cast lands on one microsecond, while the client's
  -- cursor is a single watermark advanced with a strict `>` — a split group would
  -- lose its tail permanently and silently
  -- (supabase/migrations/20260726000000_sync_cast_crew_and_scores.sql).
  cutoff as (
    select coalesce(
      (select r.ts from (select c.ts, row_number() over (order by c.ts) as rn from candidates c) r
        where r.rn = (select size from page)),
      'infinity'::timestamptz
    ) as ts
  ),
  changes as (
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
        'personCreditsVersion', 1, 'titleMetadataVersion', 1, 'moviegoingPreferencesVersion', 1, 'backupGraphVersion', 1,
        'contentRating', t.content_rating, 'imdbId', t.imdb_id, 'rtUrl', t.rt_url,
        'rtScore', t.rt_score, 'metacriticScore', t.metacritic_score,
        'customWatchUrl', t.custom_watch_url, 'inHomeCollection', t.in_home_collection,
        'physicalMedia', t.physical_media, 'awardsCount', t.awards_count,
        'bechdelOutcome', t.bechdel_outcome, 'bechdelScore', t.bechdel_score
      ) as payload
    from public.titles t
    where t.user_id = auth.uid() and t.updated_at > p_since and t.updated_at <= (select ts from cutoff)

    union all

    select 'season'::text, s.id, s.title_id, s.updated_at,
      jsonb_build_object(
        'id', s.id, 'titleId', s.title_id, 'seasonNumber', s.season_number,
        'episodeCount', s.episode_count, 'episodesWatched', s.episodes_watched, 'airYear', s.air_year
      )
    from public.seasons s
    where s.user_id = auth.uid() and s.updated_at > p_since and s.updated_at <= (select ts from cutoff)

    union all

    select 'episode'::text, e.id, e.title_id, e.updated_at,
      jsonb_build_object(
        'id', e.id, 'titleId', e.title_id, 'seasonNumber', e.season_number,
        'episodeNumber', e.episode_number, 'episodeName', e.episode_name,
        'airDate', e.air_date, 'runtime', e.runtime,
        'synopsis', e.synopsis, 'stillUrl', e.still_url
      )
    from public.episodes e
    where e.user_id = auth.uid() and e.updated_at > p_since and e.updated_at <= (select ts from cutoff)

    union all

    -- Preserve the full credited-person fields used by portable library archives.
    select 'title_cast'::text, tc.id, tc.title_id, tc.updated_at,
      jsonb_build_object(
        'id', tc.id, 'titleId', tc.title_id, 'tmdbPersonId', tc.tmdb_person_id,
        'name', tc.name, 'characterName', tc.character_name, 'castOrder', tc.cast_order,
        'profileUrl', tc.profile_url, 'episodeCount', tc.episode_count
      )
    from public.title_cast tc
    where tc.user_id = auth.uid() and tc.updated_at > p_since and tc.updated_at <= (select ts from cutoff)

    union all

    select 'title_crew'::text, cw.id, cw.title_id, cw.updated_at,
      jsonb_build_object(
        'id', cw.id, 'titleId', cw.title_id, 'tmdbPersonId', cw.tmdb_person_id,
        'name', cw.name, 'job', cw.job, 'department', cw.department, 'profileUrl', cw.profile_url
      )
    from public.title_crew cw
    where cw.user_id = auth.uid() and cw.updated_at > p_since and cw.updated_at <= (select ts from cutoff)

    union all

    select 'season_cast'::text, sc.id, sc.season_id, sc.updated_at,
      jsonb_build_object(
        'id', sc.id, 'titleId', sc.title_id, 'seasonId', sc.season_id,
        'tmdbPersonId', sc.tmdb_person_id, 'name', sc.name,
        'characterName', sc.character_name, 'castOrder', sc.cast_order,
        'profileUrl', sc.profile_url, 'episodeCount', sc.episode_count
      )
    from public.season_cast sc
    join public.seasons s on s.id = sc.season_id and s.title_id = sc.title_id and s.user_id = sc.user_id
    join public.titles t on t.id = sc.title_id and t.user_id = sc.user_id
    where sc.user_id = auth.uid() and sc.updated_at > p_since and sc.updated_at <= (select ts from cutoff)

    union all

    select 'episode_crew'::text, ec.id, ec.episode_id, ec.updated_at,
      jsonb_build_object(
        'id', ec.id, 'titleId', ec.title_id, 'episodeId', ec.episode_id,
        'tmdbPersonId', ec.tmdb_person_id, 'name', ec.name, 'job', ec.job
      )
    from public.episode_crew ec
    join public.episodes e on e.id = ec.episode_id and e.title_id = ec.title_id and e.user_id = ec.user_id
    join public.titles t on t.id = ec.title_id and t.user_id = ec.user_id
    where ec.user_id = auth.uid() and ec.updated_at > p_since and ec.updated_at <= (select ts from cutoff)

    union all

    select 'viewing'::text, v.id, v.title_id, v.updated_at,
      jsonb_build_object(
        'id', v.id, 'titleId', v.title_id, 'date', v.viewed_at, 'rating', v.rating,
        'notes', v.notes, 'venue', v.venue, 'companions', v.companions, 'outingId', v.outing_id
      )
    from public.viewings v
    where v.user_id = auth.uid() and v.updated_at > p_since and v.updated_at <= (select ts from cutoff)

    union all

    select 'episode_watch_event'::text, we.id, we.episode_id, we.updated_at,
      jsonb_build_object('id', we.id, 'episodeId', we.episode_id, 'watchedAt', we.watched_at, 'notes', we.notes, 'colorMode', we.color_mode)
    from public.episode_watch_events we
    where we.user_id = auth.uid() and we.updated_at > p_since and we.updated_at <= (select ts from cutoff)

    union all

    select 'episode_rating'::text, er.id, er.episode_id, er.updated_at,
      jsonb_build_object('id', er.id, 'episodeId', er.episode_id, 'rating', er.rating, 'ratedAt', er.rated_at)
    from public.episode_ratings er
    where er.user_id = auth.uid() and er.updated_at > p_since and er.updated_at <= (select ts from cutoff)

    union all

    select 'episode_review'::text, rv.id, rv.episode_id, rv.updated_at,
      jsonb_build_object('id', rv.id, 'episodeId', rv.episode_id, 'reviewText', rv.review_text, 'reviewedAt', rv.reviewed_at, 'colorMode', rv.color_mode)
    from public.episode_reviews rv
    where rv.user_id = auth.uid() and rv.updated_at > p_since and rv.updated_at <= (select ts from cutoff)

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
    from public.cinema_outings co
    where co.user_id = auth.uid() and co.updated_at > p_since and co.updated_at <= (select ts from cutoff)

    union all

    select 'list'::text, l.id, null::uuid, l.updated_at,
      jsonb_build_object(
        'id', l.id, 'name', l.name, 'description', l.description,
        'createdAt', l.created_at, 'updatedAt', l.updated_at
      )
    from public.lists l
    where l.user_id = auth.uid() and l.updated_at > p_since and l.updated_at <= (select ts from cutoff)

    union all

    select 'list_item'::text, li.id, li.list_id, li.updated_at,
      jsonb_build_object(
        'id', li.id, 'listId', li.list_id, 'titleId', li.title_id,
        'position', li.position, 'addedAt', li.added_at, 'updatedAt', li.updated_at
      )
    from public.list_items li
    where li.user_id = auth.uid() and li.updated_at > p_since and li.updated_at <= (select ts from cutoff)

    union all

    select 'venue_note'::text, vn.id, null::uuid, vn.updated_at,
      jsonb_build_object('id', vn.id, 'venue', vn.venue, 'notes', vn.notes,
        'createdAt', vn.created_at, 'updatedAt', vn.updated_at, 'moviegoingPreferencesVersion', 1)
    from public.venue_notes vn
    where vn.user_id = auth.uid() and vn.updated_at > p_since and vn.updated_at <= (select ts from cutoff)

    union all

    select 'theater_interest'::text, ti.id, ti.title_id, ti.updated_at,
      jsonb_build_object('id', ti.id, 'titleId', ti.title_id, 'createdAt', ti.created_at,
        'updatedAt', ti.updated_at, 'moviegoingPreferencesVersion', 1)
    from public.theater_interest ti
    where ti.user_id = auth.uid() and ti.updated_at > p_since and ti.updated_at <= (select ts from cutoff)

    union all

    select 'tombstone'::text, st.entity_id, null::uuid, st.deleted_at,
      jsonb_build_object('entityType', st.entity_type)
    from public.sync_tombstones st
    where st.user_id = auth.uid() and st.deleted_at > p_since and st.deleted_at <= (select ts from cutoff)
  )
  select c.entity_type, c.entity_id, c.parent_id, c.updated_at, c.payload
  from changes c
  order by c.updated_at, c.entity_id;
$$;

revoke all on function cinemarchive_private.sync_library_changes(timestamptz,integer) from public, anon;
grant execute on function cinemarchive_private.sync_library_changes(timestamptz,integer) to authenticated;
