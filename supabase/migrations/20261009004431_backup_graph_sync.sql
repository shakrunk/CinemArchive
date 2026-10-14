-- Backup graph sync (20261009004431).
-- Add archive fields without changing rows or their observed revisions.
-- Existing clients ignore the additions; new mirrors must replay from epoch.

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
        'personCreditsVersion', 1, 'titleMetadataVersion', 1, 'moviegoingPreferencesVersion', 1, 'backupGraphVersion', 1,
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

    -- Preserve the full credited-person fields used by portable library archives.
    select 'title_cast'::text, tc.id, tc.title_id, tc.updated_at,
      jsonb_build_object(
        'id', tc.id, 'titleId', tc.title_id, 'tmdbPersonId', tc.tmdb_person_id,
        'name', tc.name, 'characterName', tc.character_name, 'castOrder', tc.cast_order,
        'profileUrl', tc.profile_url, 'episodeCount', tc.episode_count
      )
    from public.title_cast tc where tc.user_id = auth.uid() and tc.updated_at > p_since

    union all

    select 'title_crew'::text, cw.id, cw.title_id, cw.updated_at,
      jsonb_build_object(
        'id', cw.id, 'titleId', cw.title_id, 'tmdbPersonId', cw.tmdb_person_id,
        'name', cw.name, 'job', cw.job, 'department', cw.department, 'profileUrl', cw.profile_url
      )
    from public.title_crew cw where cw.user_id = auth.uid() and cw.updated_at > p_since

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
      jsonb_build_object('id', we.id, 'episodeId', we.episode_id, 'watchedAt', we.watched_at, 'notes', we.notes, 'colorMode', we.color_mode)
    from public.episode_watch_events we where we.user_id = auth.uid() and we.updated_at > p_since

    union all

    select 'episode_rating'::text, er.id, er.episode_id, er.updated_at,
      jsonb_build_object('id', er.id, 'episodeId', er.episode_id, 'rating', er.rating, 'ratedAt', er.rated_at)
    from public.episode_ratings er where er.user_id = auth.uid() and er.updated_at > p_since

    union all

    select 'episode_review'::text, rv.id, rv.episode_id, rv.updated_at,
      jsonb_build_object('id', rv.id, 'episodeId', rv.episode_id, 'reviewText', rv.review_text, 'reviewedAt', rv.reviewed_at, 'colorMode', rv.color_mode)
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
