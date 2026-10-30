-- Sending a ticket to a friend (#220): one person buys the group's tickets, or a ticket has to
-- reach someone meeting the group there. The owner sends the ticket photo and its decoded barcode to
-- accepted friends; each recipient gets it in the notification inbox, and the owner can see who was
-- sent it, who has opened it, and take it back.
--
-- Tickets stay owner-private by default. A send creates a per-recipient grant on one attached ticket
-- attachment (private_ticket_attachments). A grant lets that one friend read that one attachment's
-- bytes and descriptor, and only while all of these hold: the grant is not revoked, the attachment is
-- still the outing's attached ticket (replacing or removing the ticket ends every grant on the old
-- one), and the two are still accepted friends. Nothing else about the outing is shared: not its
-- seats, booking reference, price or notes.
--
-- Clients have no table access. Sending, revoking, listing and opening all go through the RPCs below.
create table public.ticket_grants (
  id            uuid primary key default gen_random_uuid(),
  attachment_id uuid not null references public.ticket_attachments(id) on delete cascade,
  outing_id     uuid not null references public.cinema_outings(id) on delete cascade,
  owner_id      uuid not null references auth.users(id) on delete cascade,
  recipient_id  uuid not null references auth.users(id) on delete cascade,
  created_at    timestamptz not null default now(),
  opened_at     timestamptz,
  revoked_at    timestamptz,
  unique (attachment_id, recipient_id),
  check (owner_id <> recipient_id)
);
create index ticket_grants_recipient_idx on public.ticket_grants(recipient_id) where revoked_at is null;
create index ticket_grants_outing_idx on public.ticket_grants(outing_id);

alter table public.ticket_grants enable row level security;
revoke all on public.ticket_grants from public, anon, authenticated;
grant all on public.ticket_grants to service_role;

create schema if not exists cinemarchive_private;

-- A type for the notifications a send and an open produce. The list is a superset of every type
-- defined so far, so applying migrations out of order never drops one.
alter table public.notifications drop constraint notifications_type_check;
alter table public.notifications add constraint notifications_type_check check (type in (
  'friend_request_received', 'friend_request_accepted',
  'share_link_used', 'recommendation_received',
  'comment_received', 'reaction_received', 'invite_redeemed',
  'outing_completed', 'outing_plans_shared',
  'outing_invite', 'outing_invite_response',
  'outing_poll', 'outing_poll_closed',
  'ticket_received', 'ticket_opened'
));

-- Is this a grant the recipient can still use? Not revoked, the attachment is still the attached
-- ticket, and the owner and recipient are still accepted friends.
create function cinemarchive_private.grant_is_live(g public.ticket_grants)
returns boolean language sql stable security definer set search_path = '' as $$
  select g.revoked_at is null
     and exists (select 1 from public.ticket_attachments a where a.id = g.attachment_id and a.state = 'attached')
     and public.is_friend(g.owner_id, g.recipient_id);
$$;
revoke all on function cinemarchive_private.grant_is_live(public.ticket_grants) from public, anon, authenticated;

-- The bytes of a ticket someone sent the caller.
create function cinemarchive_private.can_read_granted_ticket_object(p_name text)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.ticket_grants g join public.ticket_attachments a on a.id = g.attachment_id
    where a.object_key = p_name and g.recipient_id = auth.uid() and cinemarchive_private.grant_is_live(g));
$$;
revoke all on function cinemarchive_private.can_read_granted_ticket_object(text) from public, anon;
grant execute on function cinemarchive_private.can_read_granted_ticket_object(text) to authenticated;

-- Reading ticket bytes: the owner as before, or a recipient with a live grant. Uploads, overwrites
-- and deletions are untouched.
drop policy "ticket bytes: owner read" on storage.objects;
create policy "ticket bytes: owner or recipient read" on storage.objects for select to authenticated using (
  bucket_id = 'ticket-attachments' and (
    exists (select 1 from public.ticket_attachments a
      where a.user_id = (select auth.uid()) and a.object_key = name and a.state in ('prepared','attached','retired'))
    or cinemarchive_private.can_read_granted_ticket_object(name)));
drop policy "ticket bytes: read fence" on storage.objects;
create policy "ticket bytes: read fence" on storage.objects as restrictive for select to authenticated using (
  bucket_id <> 'ticket-attachments' or exists (select 1 from public.ticket_attachments a
      where a.user_id = (select auth.uid()) and a.object_key = name and a.state in ('prepared','attached','retired'))
    or cinemarchive_private.can_read_granted_ticket_object(name));

-- Send the outing's current ticket to up to 8 accepted friends. Idempotent: a friend who already
-- holds a live grant is left alone and not notified again; a friend whose grant was taken back is
-- sent it again and notified.
create function cinemarchive_private.send_ticket(p_outing_id uuid, p_recipient_ids uuid[])
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  ids uuid[];
  recipient uuid;
  outing public.cinema_outings;
  attachment public.ticket_attachments;
  t public.titles;
  existing public.ticket_grants;
  newly uuid[] := '{}';
  granted public.ticket_grants;
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if p_outing_id is null or p_recipient_ids is null or cardinality(p_recipient_ids) = 0
     or array_position(p_recipient_ids, null) is not null then
    raise exception 'At least one valid friend is required' using errcode = '22023';
  end if;
  select array_agg(distinct id order by id) into ids from unnest(p_recipient_ids) id;
  if cardinality(ids) > 8 then raise exception 'A ticket can be sent to at most 8 friends' using errcode = '22023'; end if;
  select * into outing from public.cinema_outings where id = p_outing_id and user_id = me;
  if outing.id is null then raise exception 'Outing not found' using errcode = '42501'; end if;
  select * into attachment from public.ticket_attachments
    where id = outing.ticket_attachment_id and user_id = me and state = 'attached';
  if attachment.id is null then raise exception 'This outing has no ticket to send' using errcode = '22023'; end if;
  foreach recipient in array ids loop
    if recipient = me or not public.is_friend(me, recipient) then
      raise exception 'Can only send a ticket to accepted friends' using errcode = '42501';
    end if;
  end loop;
  select * into t from public.titles where id = outing.title_id;

  foreach recipient in array ids loop
    select * into existing from public.ticket_grants where attachment_id = attachment.id and recipient_id = recipient for update;
    if existing.id is null then
      insert into public.ticket_grants(attachment_id, outing_id, owner_id, recipient_id)
        values (attachment.id, outing.id, me, recipient);
      newly := newly || recipient;
    elsif existing.revoked_at is not null then
      update public.ticket_grants set revoked_at = null, opened_at = null, created_at = now() where id = existing.id;
      newly := newly || recipient;
    end if;
  end loop;

  if (select count(*) from public.ticket_grants where attachment_id = attachment.id and revoked_at is null) > 8 then
    raise exception 'A ticket can be sent to at most 8 friends' using errcode = '22023';
  end if;

  foreach recipient in array newly loop
    select * into granted from public.ticket_grants where attachment_id = attachment.id and recipient_id = recipient;
    insert into public.notifications(recipient_id, type, actor_id, title_id, payload)
      values (recipient, 'ticket_received', me, outing.title_id, jsonb_build_object(
        'grant_id', granted.id, 'title', t.title, 'showtime', outing.showtime, 'venue', outing.venue));
  end loop;
  return cinemarchive_private.get_ticket_grants(p_outing_id);
end;
$$;

-- The owner takes a ticket back from one friend. Idempotent.
create function cinemarchive_private.revoke_ticket_grant(p_grant_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  if not exists (select 1 from public.ticket_grants where id = p_grant_id and owner_id = auth.uid()) then
    raise exception 'Ticket not found' using errcode = '42501';
  end if;
  update public.ticket_grants set revoked_at = now() where id = p_grant_id and revoked_at is null;
end;
$$;

-- The owner's view of who was sent this outing's current ticket, and who has opened it.
create function cinemarchive_private.get_ticket_grants(p_outing_id uuid)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  return (select coalesce(jsonb_agg(jsonb_build_object(
      'grant_id', g.id, 'user_id', g.recipient_id,
      'name', coalesce(nullif(p.display_name, ''), p.username, 'A friend'),
      'sent_at', g.created_at, 'opened_at', g.opened_at) order by g.created_at), '[]'::jsonb)
    from public.ticket_grants g
    join public.cinema_outings o on o.id = g.outing_id and o.ticket_attachment_id = g.attachment_id
    left join public.profiles p on p.user_id = g.recipient_id
    where g.owner_id = auth.uid() and g.outing_id = p_outing_id and cinemarchive_private.grant_is_live(g));
end;
$$;

-- Tickets friends have sent the caller for shows that ended less than a day ago or are upcoming.
-- Only enough to list them: the barcode and the file come from opening one.
create function cinemarchive_private.get_received_tickets()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  return (select coalesce(jsonb_agg(jsonb_build_object(
      'grant_id', g.id, 'sender_id', g.owner_id,
      'sender_name', coalesce(nullif(p.display_name, ''), p.username, 'A friend'),
      'title', t.title, 'year', t.year, 'poster_url', t.poster_url,
      'showtime', o.showtime, 'ends_at', o.ends_at, 'venue', o.venue,
      'sent_at', g.created_at, 'opened_at', g.opened_at) order by o.showtime), '[]'::jsonb)
    from public.ticket_grants g
    join public.cinema_outings o on o.id = g.outing_id and o.ticket_attachment_id = g.attachment_id
    join public.titles t on t.id = o.title_id
    left join public.profiles p on p.user_id = g.owner_id
    where g.recipient_id = auth.uid() and cinemarchive_private.grant_is_live(g)
      and o.ends_at > now() - interval '1 day');
end;
$$;

-- Open a ticket someone sent the caller: the first open is recorded and the sender is told. Returns
-- the attachment descriptor (the file's key, type, size, digest and decoded barcode) with the show.
create function cinemarchive_private.open_received_ticket(p_grant_id uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  g public.ticket_grants;
  a public.ticket_attachments;
  o public.cinema_outings;
  t public.titles;
  first_open boolean;
begin
  if me is null then raise exception 'Not authenticated' using errcode = '42501'; end if;
  select * into g from public.ticket_grants where id = p_grant_id and recipient_id = me for update;
  if g.id is null or not cinemarchive_private.grant_is_live(g) then
    raise exception 'Ticket not found' using errcode = '42501';
  end if;
  select * into a from public.ticket_attachments where id = g.attachment_id;
  select * into o from public.cinema_outings where id = g.outing_id;
  select * into t from public.titles where id = o.title_id;
  first_open := g.opened_at is null;
  if first_open then
    update public.ticket_grants set opened_at = now() where id = g.id;
    insert into public.notifications(recipient_id, type, actor_id, title_id, payload)
      values (g.owner_id, 'ticket_opened', me, o.title_id, jsonb_build_object('grant_id', g.id, 'title', t.title));
  end if;
  return cinemarchive_private.ticket_descriptor(a) || jsonb_build_object(
    'title', t.title, 'showtime', o.showtime, 'venue', o.venue);
end;
$$;

revoke all on function cinemarchive_private.send_ticket(uuid, uuid[]) from public, anon;
revoke all on function cinemarchive_private.revoke_ticket_grant(uuid) from public, anon;
revoke all on function cinemarchive_private.get_ticket_grants(uuid) from public, anon;
revoke all on function cinemarchive_private.get_received_tickets() from public, anon;
revoke all on function cinemarchive_private.open_received_ticket(uuid) from public, anon;
grant execute on function cinemarchive_private.send_ticket(uuid, uuid[]) to authenticated;
grant execute on function cinemarchive_private.revoke_ticket_grant(uuid) to authenticated;
grant execute on function cinemarchive_private.get_ticket_grants(uuid) to authenticated;
grant execute on function cinemarchive_private.get_received_tickets() to authenticated;
grant execute on function cinemarchive_private.open_received_ticket(uuid) to authenticated;

-- Public wrappers: PostgREST exposes only the public schema.
create function public.send_ticket(p_outing_id uuid, p_recipient_ids uuid[])
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.send_ticket(p_outing_id, p_recipient_ids);
$$;
create function public.revoke_ticket_grant(p_grant_id uuid)
returns void language sql security invoker set search_path = '' as $$
  select cinemarchive_private.revoke_ticket_grant(p_grant_id);
$$;
create function public.get_ticket_grants(p_outing_id uuid)
returns jsonb language sql stable security invoker set search_path = '' as $$
  select cinemarchive_private.get_ticket_grants(p_outing_id);
$$;
create function public.get_received_tickets()
returns jsonb language sql stable security invoker set search_path = '' as $$
  select cinemarchive_private.get_received_tickets();
$$;
create function public.open_received_ticket(p_grant_id uuid)
returns jsonb language sql security invoker set search_path = '' as $$
  select cinemarchive_private.open_received_ticket(p_grant_id);
$$;
revoke all on function public.send_ticket(uuid, uuid[]) from public, anon;
revoke all on function public.revoke_ticket_grant(uuid) from public, anon;
revoke all on function public.get_ticket_grants(uuid) from public, anon;
revoke all on function public.get_received_tickets() from public, anon;
revoke all on function public.open_received_ticket(uuid) from public, anon;
grant execute on function public.send_ticket(uuid, uuid[]) to authenticated;
grant execute on function public.revoke_ticket_grant(uuid) to authenticated;
grant execute on function public.get_ticket_grants(uuid) to authenticated;
grant execute on function public.get_received_tickets() to authenticated;
grant execute on function public.open_received_ticket(uuid) to authenticated;
