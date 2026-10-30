-- Group post-show reactions (#207): the people on a shared outing (shared_outings, #206) can
-- share how it went, a star rating and a short note, and see each other's.
--
-- Sharing is a deliberate act and separate from the private viewing log: a reaction is written
-- here only by an explicit "share with the group", and never copied from or into the owner's or a
-- member's own library. Only the outing's participants (the owner and members who accepted) can
-- read or write reactions, and only after the show has ended. Clients have no table access at all;
-- everything goes through the RPCs below.
create table public.shared_outing_reactions (
  shared_outing_id uuid not null references public.shared_outings(id) on delete cascade,
  user_id          uuid not null references auth.users(id) on delete cascade,
  rating           numeric(2,1) check (rating is null or (rating between 0.5 and 5 and rating * 2 = floor(rating * 2))),
  note             text check (note is null or char_length(note) between 1 and 500),
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  primary key (shared_outing_id, user_id),
  check (rating is not null or note is not null)
);

alter table public.shared_outing_reactions enable row level security;
revoke all on public.shared_outing_reactions from public, anon, authenticated;
grant all on public.shared_outing_reactions to service_role;

-- A member who declines, leaves or is removed takes their reaction with them.
create function cinemarchive_private.drop_reaction_of_departed_member()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'DELETE' or new.status = 'declined' then
    delete from public.shared_outing_reactions
      where shared_outing_id = old.shared_outing_id and user_id = old.user_id;
  end if;
  return coalesce(new, old);
end;
$$;
revoke all on function cinemarchive_private.drop_reaction_of_departed_member() from public, anon, authenticated;
create trigger shared_outing_members_drop_reaction
  after update of status or delete on public.shared_outing_members
  for each row execute function cinemarchive_private.drop_reaction_of_departed_member();

-- The owner, or a member who accepted.
create function cinemarchive_private.is_outing_participant(p_shared_outing_id uuid, p_user_id uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.shared_outings where id = p_shared_outing_id and owner_id = p_user_id)
      or exists (select 1 from public.shared_outing_members
           where shared_outing_id = p_shared_outing_id and user_id = p_user_id and status = 'accepted');
$$;
revoke all on function cinemarchive_private.is_outing_participant(uuid, uuid) from public, anon, authenticated;

-- Reactions open once the show has ended (or the owner's outing completed) and stay closed for a
-- cancelled one.
create function cinemarchive_private.reactions_open(s public.shared_outings)
returns boolean language sql stable set search_path = '' as $$
  select s.status <> 'cancelled' and (s.status = 'completed' or s.ends_at <= now());
$$;
revoke all on function cinemarchive_private.reactions_open(public.shared_outings) from public, anon, authenticated;

-- Share or update the caller's own reaction. Idempotent for the same values.
create function cinemarchive_private.share_outing_reaction(p_shared_outing_id uuid, p_rating numeric, p_note text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  shared public.shared_outings;
  note text := nullif(btrim(coalesce(p_note, '')), '');
  saved public.shared_outing_reactions;
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  select * into shared from public.shared_outings where id = p_shared_outing_id;
  if shared.id is null or not cinemarchive_private.is_outing_participant(shared.id, me) then
    raise exception 'Outing not found' using errcode = '42501';
  end if;
  if not cinemarchive_private.reactions_open(shared) then
    raise exception 'Reactions open once the show has ended' using errcode = '22023';
  end if;
  if p_rating is null and note is null then
    raise exception 'Add a rating or a note' using errcode = '22023';
  end if;
  if p_rating is not null and (p_rating < 0.5 or p_rating > 5 or p_rating * 2 <> floor(p_rating * 2)) then
    raise exception 'Rate from 0.5 to 5 in half stars' using errcode = '22023';
  end if;
  if note is not null and char_length(note) > 500 then
    raise exception 'Keep the note to 500 characters' using errcode = '22023';
  end if;
  insert into public.shared_outing_reactions(shared_outing_id, user_id, rating, note)
    values (shared.id, me, p_rating, note)
    on conflict (shared_outing_id, user_id) do update
      set rating = excluded.rating, note = excluded.note, updated_at = now()
    returning * into saved;
  return jsonb_build_object('rating', saved.rating, 'note', saved.note, 'updated_at', saved.updated_at);
end;
$$;

create function cinemarchive_private.withdraw_outing_reaction(p_shared_outing_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  delete from public.shared_outing_reactions where shared_outing_id = p_shared_outing_id and user_id = auth.uid();
end;
$$;

-- Everyone's reaction on the outing, for a participant. `is_me` marks the caller's own.
create function cinemarchive_private.get_outing_reactions(p_shared_outing_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if not cinemarchive_private.is_outing_participant(p_shared_outing_id, me) then
    raise exception 'Outing not found' using errcode = '42501';
  end if;
  return (select coalesce(jsonb_agg(jsonb_build_object(
      'user_id', r.user_id, 'is_me', r.user_id = me,
      'name', coalesce(nullif(p.display_name, ''), p.username, 'A friend'),
      'rating', r.rating, 'note', r.note, 'updated_at', r.updated_at) order by r.updated_at), '[]'::jsonb)
    from public.shared_outing_reactions r
    left join public.profiles p on p.user_id = r.user_id
    where r.shared_outing_id = p_shared_outing_id
      and cinemarchive_private.is_outing_participant(p_shared_outing_id, r.user_id));
end;
$$;

-- Reactions come after the show, so a member's "going" card has to outlast the screening.
create or replace function cinemarchive_private.get_shared_outings()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  return (select coalesce(jsonb_agg(jsonb_build_object(
      'id', s.id, 'status', s.status, 'my_status', m.status, 'owner_id', s.owner_id,
      'owner_name', coalesce(nullif(p.display_name, ''), p.username, 'A friend'),
      'showtime', s.showtime, 'ends_at', s.ends_at, 'snapshot', s.snapshot) order by s.showtime), '[]'::jsonb)
    from public.shared_outing_members m
    join public.shared_outings s on s.id = m.shared_outing_id
    left join public.profiles p on p.user_id = s.owner_id
    where m.user_id = auth.uid() and m.status in ('invited','accepted')
      and s.ends_at > now() - interval '7 days');
end;
$$;

revoke all on function cinemarchive_private.share_outing_reaction(uuid, numeric, text) from public, anon;
revoke all on function cinemarchive_private.withdraw_outing_reaction(uuid) from public, anon;
revoke all on function cinemarchive_private.get_outing_reactions(uuid) from public, anon;
grant execute on function cinemarchive_private.share_outing_reaction(uuid, numeric, text) to authenticated;
grant execute on function cinemarchive_private.withdraw_outing_reaction(uuid) to authenticated;
grant execute on function cinemarchive_private.get_outing_reactions(uuid) to authenticated;

-- Public wrappers: PostgREST exposes only the public schema.
create function public.share_outing_reaction(p_shared_outing_id uuid, p_rating numeric, p_note text)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.share_outing_reaction(p_shared_outing_id, p_rating, p_note);
$$;
create function public.withdraw_outing_reaction(p_shared_outing_id uuid)
returns void language sql security invoker set search_path = '' as $$
  select cinemarchive_private.withdraw_outing_reaction(p_shared_outing_id);
$$;
create function public.get_outing_reactions(p_shared_outing_id uuid)
returns jsonb language sql stable security invoker set search_path = '' as $$
  select cinemarchive_private.get_outing_reactions(p_shared_outing_id);
$$;
revoke all on function public.share_outing_reaction(uuid, numeric, text) from public, anon;
revoke all on function public.withdraw_outing_reaction(uuid) from public, anon;
revoke all on function public.get_outing_reactions(uuid) from public, anon;
grant execute on function public.share_outing_reaction(uuid, numeric, text) to authenticated;
grant execute on function public.withdraw_outing_reaction(uuid) to authenticated;
grant execute on function public.get_outing_reactions(uuid) to authenticated;
