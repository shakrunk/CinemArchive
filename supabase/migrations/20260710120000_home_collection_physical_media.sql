-- Home Collection + physical media cataloging (KP-002 / KP-003).
-- in_home_collection: the title is owned locally (physical shelf or media
-- server) and surfaces as a "Home Collection" source in Where to Watch.
-- physical_media: cataloged physical copies, a jsonb array of
--   { id, format, edition?, notes? } in the client's PhysicalMediaItem shape.

alter table titles
  add column if not exists in_home_collection boolean not null default false,
  add column if not exists physical_media jsonb not null default '[]'::jsonb;
