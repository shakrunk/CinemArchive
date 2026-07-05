-- Franchise groupings: store the TMDB collection a movie belongs to
-- (e.g. "The Lord of the Rings Collection") so the Library can group by franchise.
alter table titles add column if not exists collection_id integer;
alter table titles add column if not exists collection_name text;
