-- Spider Noir viewing mode: record whether each watch event / review was
-- experienced in black-and-white or full color. Nullable — only populated
-- for Spider-Man: Noir episodes.
alter table episode_watch_events
  add column color_mode text check (color_mode in ('bw', 'color'));

alter table episode_reviews
  add column color_mode text check (color_mode in ('bw', 'color'));
