-- Allow the owner to override "where to watch" with a custom URL,
-- surfaced preferentially over TMDB watch-provider data in shared/friend views.
alter table titles add column if not exists custom_watch_url text;
