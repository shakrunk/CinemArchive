-- Reject reused viewing identities with different supplied history atomically.
-- Other entities retain their established insert-adoption behavior.

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
    -- A viewing insert is an immutable history intent, not a partial update.
    -- Compare only supplied fields after PostgreSQL's normal column coercions:
    -- omitted values retain defaults/current data; explicit null remains intent.
    -- Re-read under a row lock to cover the ON CONFLICT path as well as the
    -- initial lookup. A collision rejects every companion operation and receipt.
    -- Already accepted operation IDs returned their original receipt above.
    if v_table = 'viewings' and v_action = 'insert' then
      select to_jsonb(r) into v_row from public.viewings r
        where r.id = (v_key->>'id')::uuid and r.user_id = v_owner for update;
      if v_row is null or exists (
        select 1 from jsonb_object_keys(v_values) supplied(column_name)
        cross join lateral jsonb_populate_record(null::public.viewings,v_values) expected
        where v_row->supplied.column_name is distinct from to_jsonb(expected)->supplied.column_name
      ) then
        raise exception 'Viewing identity already contains different history; compare before replacing it' using errcode='23505';
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
