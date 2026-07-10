-- Rotten Tomatoes page URL, resolved via Wikidata (P1258) from the title's imdb_id.
alter table titles add column rt_url text;
