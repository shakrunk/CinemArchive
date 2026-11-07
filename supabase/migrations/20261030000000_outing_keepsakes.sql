-- Keepsake photos for cinema outings (#208): a ticket stub, a poster board, a popcorn selfie.
-- Distinct from the single scannable ticket attachment (private_ticket_attachments): there is
-- no barcode, an outing can have several, and each is plain owner-private data.
--
-- Bytes live in the private `outing-keepsakes` bucket under `<owner>/<keepsake id>`. A photo is
-- uploaded first, then its row is inserted; the insert trigger checks that the stored object
-- exists and matches the row's size and type, so a row can never point at nothing or at
-- someone else's bytes. Rows are immutable (no UPDATE) and owner-only (no friend, shared-token
-- or anonymous read exists).
--
-- Clients cannot delete or overwrite bytes. Removing a keepsake deletes its row, which makes
-- the object unreadable at once; a service-only cleanup (scripts/cleanup-keepsake-photos.mjs)
-- later removes objects that have no row for a day, which also covers an upload whose row
-- insert failed and the cascade when an outing or account is deleted.
create table public.outing_keepsakes (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null references auth.users(id) on delete cascade,
  outing_id   uuid not null references public.cinema_outings(id) on delete cascade,
  object_key  text not null unique,
  mime_type   text not null check (mime_type in ('image/jpeg','image/png','image/webp')),
  byte_length integer not null check (byte_length between 1 and 10485760),
  created_at  timestamptz not null default now(),
  check (object_key = user_id::text || '/' || id::text)
);
create index outing_keepsakes_outing_idx on public.outing_keepsakes(outing_id);
create index outing_keepsakes_owner_idx on public.outing_keepsakes(user_id, created_at desc);

alter table public.outing_keepsakes enable row level security;
revoke all on public.outing_keepsakes from public, anon, authenticated;
grant select, insert, delete on public.outing_keepsakes to authenticated;
grant all on public.outing_keepsakes to service_role;

create policy "outing keepsakes: owner read" on public.outing_keepsakes
  for select to authenticated using (user_id = (select auth.uid()));
create policy "outing keepsakes: owner insert" on public.outing_keepsakes
  for insert to authenticated with check (
    user_id = (select auth.uid())
    and exists (select 1 from public.cinema_outings o where o.id = outing_id and o.user_id = (select auth.uid())));
create policy "outing keepsakes: owner delete" on public.outing_keepsakes
  for delete to authenticated using (user_id = (select auth.uid()));

create schema if not exists cinemarchive_private;

-- Runs as the owner of the function so it can read Storage metadata that the caller's own
-- Storage policies would not yet show (the read policy requires the row being inserted).
create function cinemarchive_private.guard_keepsake_insert()
returns trigger language plpgsql security definer set search_path = '' as $$
declare stored jsonb;
begin
  -- Refuse before looking at Storage, so a row for someone else's key cannot probe which objects exist.
  if auth.uid() is not null and new.user_id is distinct from auth.uid() then
    raise exception 'Not allowed' using errcode = '42501';
  end if;
  select metadata into stored from storage.objects
    where bucket_id = 'outing-keepsakes' and name = new.object_key;
  if stored is null then
    raise exception 'Upload the photo before adding it to the outing' using errcode = '22023';
  end if;
  if (stored->>'size')::bigint is distinct from new.byte_length::bigint
     or stored->>'mimetype' is distinct from new.mime_type then
    raise exception 'The uploaded photo does not match its description' using errcode = '22023';
  end if;
  if (select count(*) from public.outing_keepsakes where outing_id = new.outing_id) >= 8 then
    raise exception 'An outing can keep at most 8 photos' using errcode = '54000';
  end if;
  return new;
end;
$$;
revoke all on function cinemarchive_private.guard_keepsake_insert() from public, anon, authenticated;
create trigger outing_keepsakes_guard_insert
  before insert on public.outing_keepsakes
  for each row execute function cinemarchive_private.guard_keepsake_insert();

-- An owner may put an object only under their own folder, with a UUID name, and only while they
-- hold fewer than 500 objects in the bucket, so the folder is not an unbounded file drop.
create function cinemarchive_private.can_upload_keepsake_object(p_name text)
returns boolean language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null then return false; end if;
  if p_name !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
    return false;
  end if;
  if split_part(p_name, '/', 1) <> auth.uid()::text then return false; end if;
  return (select count(*) from storage.objects
    where bucket_id = 'outing-keepsakes' and split_part(name, '/', 1) = auth.uid()::text) < 500;
end;
$$;
revoke all on function cinemarchive_private.can_upload_keepsake_object(text) from public, anon;
grant execute on function cinemarchive_private.can_upload_keepsake_object(text) to authenticated;

insert into storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
  values ('outing-keepsakes', 'outing-keepsakes', false, 10485760, array['image/jpeg','image/png','image/webp'])
  on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

create policy "keepsake bytes: owner upload" on storage.objects for insert to authenticated with check (
  bucket_id = 'outing-keepsakes' and cinemarchive_private.can_upload_keepsake_object(name));
create policy "keepsake bytes: owner read" on storage.objects for select to authenticated using (
  bucket_id = 'outing-keepsakes' and exists (select 1 from public.outing_keepsakes k
    where k.user_id = (select auth.uid()) and k.object_key = name));
-- No UPDATE or DELETE policy: bytes are immutable, and only the service-only cleanup removes them.

-- Restrictive fences keep an unrelated permissive policy on this bucket from granting access.
create policy "keepsake bytes: anonymous fence" on storage.objects as restrictive for all to anon
  using (bucket_id <> 'outing-keepsakes') with check (bucket_id <> 'outing-keepsakes');
create policy "keepsake bytes: upload fence" on storage.objects as restrictive for insert to authenticated with check (
  bucket_id <> 'outing-keepsakes' or cinemarchive_private.can_upload_keepsake_object(name));
create policy "keepsake bytes: read fence" on storage.objects as restrictive for select to authenticated using (
  bucket_id <> 'outing-keepsakes' or exists (select 1 from public.outing_keepsakes k
    where k.user_id = (select auth.uid()) and k.object_key = name));
create policy "keepsake bytes: overwrite fence" on storage.objects as restrictive for update to authenticated
  using (bucket_id <> 'outing-keepsakes') with check (bucket_id <> 'outing-keepsakes');
create policy "keepsake bytes: deletion fence" on storage.objects as restrictive for delete to authenticated
  using (bucket_id <> 'outing-keepsakes');

-- Service-only: object names in the bucket with no keepsake row for over a day, at most 100.
-- The cleanup script removes each through the Storage API (never by deleting the metadata row).
create function public.claim_keepsake_cleanup(p_limit integer default 100)
returns table (object_key text)
language sql stable security definer set search_path = '' as $$
  select o.name from storage.objects o
  where o.bucket_id = 'outing-keepsakes'
    and o.created_at < now() - interval '1 day'
    and not exists (select 1 from public.outing_keepsakes k where k.object_key = o.name)
  order by o.created_at
  limit least(greatest(coalesce(p_limit, 100), 1), 100)
$$;
revoke all on function public.claim_keepsake_cleanup(integer) from public, anon, authenticated;
grant execute on function public.claim_keepsake_cleanup(integer) to service_role;
