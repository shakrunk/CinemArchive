-- Owner access is already granted by each table's owner policy.
-- PostgreSQL may evaluate the permissive shared-read policy first, causing
-- thousands of nested title/friend/scope lookups during a library fetch.
-- CASE guarantees those lookups are skipped for owner rows. Non-owner and
-- anonymous reads retain the original scope checks; write policies are unchanged.

alter policy "titles: shared/friend read" on public.titles
  using (case when user_id = (select auth.uid()) then false else
    public.can_view_title(user_id, genres, status)
  end);

alter policy "seasons: shared/friend read" on public.seasons
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from public.titles t
      where t.id = seasons.title_id
        and public.can_view_title(t.user_id, t.genres, t.status)
    )
  end);

alter policy "viewings: shared/friend read" on public.viewings
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from public.titles t
      where t.id = viewings.title_id
        and public.can_view_title(t.user_id, t.genres, t.status)
    )
  end);

alter policy "episodes: shared/friend read" on public.episodes
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from public.titles t
      where t.id = episodes.title_id
        and public.can_view_title(t.user_id, t.genres, t.status)
    )
  end);

alter policy "episode_watch_events: shared/friend read" on public.episode_watch_events
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from public.episodes e
      join public.titles t on t.id = e.title_id
      where e.id = episode_watch_events.episode_id
        and public.can_view_title(t.user_id, t.genres, t.status)
    )
  end);

alter policy "episode_ratings: shared/friend read" on public.episode_ratings
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from public.episodes e
      join public.titles t on t.id = e.title_id
      where e.id = episode_ratings.episode_id
        and public.can_view_title(t.user_id, t.genres, t.status)
    )
  end);

alter policy "episode_reviews: shared/friend read" on public.episode_reviews
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from public.episodes e
      join public.titles t on t.id = e.title_id
      where e.id = episode_reviews.episode_id
        and public.can_view_title(t.user_id, t.genres, t.status)
    )
  end);

alter policy "title_cast: shared/friend read" on public.title_cast
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from public.titles t
      where t.id = title_cast.title_id
        and public.can_view_title(t.user_id, t.genres, t.status)
    )
  end);

alter policy "title_crew: shared/friend read" on public.title_crew
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from public.titles t
      where t.id = title_crew.title_id
        and public.can_view_title(t.user_id, t.genres, t.status)
    )
  end);

alter policy "season_cast: shared/friend read" on public.season_cast
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from public.titles t
      where t.id = season_cast.title_id
        and public.can_view_title(t.user_id, t.genres, t.status)
    )
  end);

alter policy "episode_crew: shared/friend read" on public.episode_crew
  using (case when user_id = (select auth.uid()) then false else
    exists (
      select 1 from public.titles t
      where t.id = episode_crew.title_id
        and public.can_view_title(t.user_id, t.genres, t.status)
    )
  end);
