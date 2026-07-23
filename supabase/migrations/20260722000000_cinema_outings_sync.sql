-- Wires cinema_outings into the Android sync layer's read half: a tombstone
-- trigger for hard deletes (deleteOutingFromDb) and a sync_library_changes
-- union arm, both deliberately deferred when 20260713000000_android_sync_layer.sql
-- landed (its header scoped the RPC to Library/Title detail/Episode tracking
-- only) because there was no live Android session to pull through yet.
-- Passkey auth and the real outbox writer have since landed (see
-- docs/android-parity-matrix.md's Authentication row and
-- CinemArchiveApplication.kt), so this closes the gap LibrarySyncRepository's
-- kdoc flagged as a server-side gap.
--
-- Also fixes the `viewing` arm, which never carried `companions`/`outing_id`
-- even after those columns were added in 20260711000000_cinema_outings.sql —
-- a viewing auto-logged by an outing completion (or manually logged with
-- companions) synced down to Android without either field.

create trigger cinema_outings_tombstone before delete on cinema_outings for each row execute function record_tombstone('cinema_outing');

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
        'bookingRef', co.booking_ref, 'notes', co.notes, 'status', co.status,
        'previousStatus', co.previous_status, 'completedViewingId', co.completed_viewing_id,
        'followUpDismissedAt', co.follow_up_dismissed_at, 'createdAt', co.created_at,
        'updatedAt', co.updated_at
      )
    from cinema_outings co where co.user_id = auth.uid() and co.updated_at > p_since

    union all

    select 'tombstone'::text, st.entity_id, null::uuid, st.deleted_at,
      jsonb_build_object('entityType', st.entity_type)
    from sync_tombstones st where st.user_id = auth.uid() and st.deleted_at > p_since
  ) changes
  order by updated_at, entity_id
  limit least(coalesce(p_limit, 500), 500);
$$;
