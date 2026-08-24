-- Captured-ticket columns for cinema outings (issue #219).
--
-- `cinema_outings.booking_ref` is a manually-typed confirmation code, not the vendor's
-- scannable barcode payload — see the Android TicketScreen's kdoc, which has flagged this
-- gap since the ticket-in-hand screen shipped. This adds a place to store the *real*
-- ticket, captured by photographing it and decoding the barcode on-device:
--
--   ticket_image_path      -- Supabase Storage object path for the captured photo
--   ticket_barcode_payload -- the decoded barcode's raw value
--   ticket_barcode_format  -- its symbology (QR_CODE, CODE_128, ...) — stored alongside the
--                             payload, not inferred later, because re-encoding a 1D payload
--                             as a QR code produces a code no turnstile scanner will read.
--
-- Free text at rest, same convention as `format` (no db-level check — the fixed list lives
-- in the client, per docs/superpowers/plans/2026-08-19-android-ticket-capture.md §4).
--
-- This only attaches a captured ticket to an outing the user already scheduled by hand. It
-- does not populate outing fields (venue/showtime/seats) from the photo — that's the shared
-- `ticket_import_candidates` review pipeline ADR 0003 describes for #219+#223 together,
-- which doesn't exist yet. See the plan doc's §1 for why this is scoped narrower than the ADR.

alter table cinema_outings
  add column ticket_image_path      text,
  add column ticket_barcode_payload text,
  add column ticket_barcode_format  text;

-- Republish the Android sync payload with the three new fields. Unchanged from
-- 20260803000000_outing_seat_details.sql apart from the cinema_outing arm.
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
        'airDate', e.air_date, 'runtime', e.runtime
      )
    from episodes e where e.user_id = auth.uid() and e.updated_at > p_since

    union all

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
  -- never split across pages (see 20260726000000_sync_cast_crew_and_scores.sql).
  select o.entity_type, o.entity_id, o.parent_id, o.updated_at, o.payload
  from ordered o
  where o.updated_at <= coalesce(
    (select o2.updated_at from ordered o2 where o2.rn = least(coalesce(p_limit, 500), 500)),
    'infinity'::timestamptz
  )
  order by o.updated_at, o.entity_id;
$$;
