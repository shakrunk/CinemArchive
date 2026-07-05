-- Watched-before-joining support: a NULL date on a viewing or episode watch
-- event marks it as watched before the user joined the platform — the watch
-- is real but its timestamp is indeterminate.

alter table viewings alter column viewed_at drop not null;

alter table episode_watch_events alter column watched_at drop not null;
