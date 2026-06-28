-- Add original language, content certification, and IMDb id to titles.
-- Powers the Details sidebar (Language / Released / Rated) and the exact IMDb link.
alter table titles add column if not exists original_language text;  -- ISO 639-1, e.g. "en"
alter table titles add column if not exists content_rating text;     -- e.g. "PG-13", "TV-MA"
alter table titles add column if not exists imdb_id text;            -- e.g. "tt1375666"
