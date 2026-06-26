-- Add release_date column to titles for tracking upcoming/unreleased titles
alter table titles add column if not exists release_date date;
