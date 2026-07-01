-- Grants accepted friends read-only access to a user's library, mirroring the
-- existing "shared key read" policies but gated on is_friend(auth.uid(), user_id)
-- instead of a bearer token. Owner-full-access policies are untouched — this
-- only adds an additional SELECT path per table.

create policy "titles: friend read"
  on titles for select
  using (is_friend(auth.uid(), user_id));

create policy "seasons: friend read"
  on seasons for select
  using (is_friend(auth.uid(), user_id));

create policy "viewings: friend read"
  on viewings for select
  using (is_friend(auth.uid(), user_id));

create policy "episodes: friend read"
  on episodes for select
  using (is_friend(auth.uid(), user_id));

create policy "episode_watch_events: friend read"
  on episode_watch_events for select
  using (is_friend(auth.uid(), user_id));

create policy "episode_ratings: friend read"
  on episode_ratings for select
  using (is_friend(auth.uid(), user_id));

create policy "episode_reviews: friend read"
  on episode_reviews for select
  using (is_friend(auth.uid(), user_id));

create policy "title_cast: friend read"
  on title_cast for select
  using (is_friend(auth.uid(), user_id));

create policy "title_crew: friend read"
  on title_crew for select
  using (is_friend(auth.uid(), user_id));

create policy "season_cast: friend read"
  on season_cast for select
  using (is_friend(auth.uid(), user_id));

create policy "episode_crew: friend read"
  on episode_crew for select
  using (is_friend(auth.uid(), user_id));
