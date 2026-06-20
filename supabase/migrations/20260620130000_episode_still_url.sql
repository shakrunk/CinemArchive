-- Add still_url column to episodes for TMDB episode thumbnail images
alter table episodes add column if not exists still_url text;
