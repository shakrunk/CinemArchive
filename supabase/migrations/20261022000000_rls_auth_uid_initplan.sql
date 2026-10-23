-- Evaluate auth.uid() once per statement instead of once per row in RLS
-- policies (Supabase advisor lint 0003 auth_rls_initplan).
--
-- A bare auth.uid() in a policy expression re-reads and re-parses the
-- request.jwt.claims setting for every row checked. Wrapping it in a scalar
-- subquery lets Postgres hoist it into an InitPlan evaluated once. The owner
-- library fetch embeds ~60k child rows, so this per-row cost added measurably
-- to a request that was already timing out. Semantics are unchanged:
-- auth.uid() is stable within a statement.
--
-- ALTER POLICY keeps each policy's command, roles and permissive mode.

-- Library graph
alter policy "titles: owner full access" on public.titles
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "title_cast: owner full access" on public.title_cast
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "title_crew: owner full access" on public.title_crew
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "seasons: owner full access" on public.seasons
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "season_cast: owner full access" on public.season_cast
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "episodes: owner full access" on public.episodes
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "episode_crew: owner full access" on public.episode_crew
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "episode_watch_events: owner full access" on public.episode_watch_events
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "episode_ratings: owner full access" on public.episode_ratings
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "episode_reviews: owner full access" on public.episode_reviews
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "viewings: owner full access" on public.viewings
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

-- Other owner-scoped tables
alter policy "cinema_outings: owner full access" on public.cinema_outings
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "lists: owner full access" on public.lists
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "list_items: owner full access" on public.list_items
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "user_title_pins: owner full access" on public.user_title_pins
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "user_prefs: owner full access" on public.user_prefs
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "profiles: owner full access" on public.profiles
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "shared_keys: owner full access" on public.shared_access_keys
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "share_scopes: owner full access" on public.share_scopes
  using ((select auth.uid()) = owner_user_id) with check ((select auth.uid()) = owner_user_id);
alter policy "integration_connections: owner full access" on public.integration_connections
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "external_title_links: owner full access" on public.external_title_links
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
alter policy "sync_tombstones: owner read" on public.sync_tombstones
  using ((select auth.uid()) = user_id);
alter policy "venue notes: owner read" on public.venue_notes
  using (user_id = (select auth.uid()));
alter policy "theater interest: owner read" on public.theater_interest
  using (user_id = (select auth.uid()));

-- Social
alter policy "user_prefs: friend read" on public.user_prefs
  using (is_friend((select auth.uid()), user_id));
alter policy "friendships: parties can read" on public.friendships
  using ((select auth.uid()) = user_id_a or (select auth.uid()) = user_id_b);
alter policy "recommendations: recipient can read" on public.recommendations
  using ((select auth.uid()) = recipient_user_id);
alter policy "recommendations: sender can read" on public.recommendations
  using ((select auth.uid()) = sender_user_id);
alter policy "notifications: recipient can read" on public.notifications
  using ((select auth.uid()) = recipient_id);
alter policy "notifications: recipient can mark read/delete" on public.notifications
  using ((select auth.uid()) = recipient_id) with check ((select auth.uid()) = recipient_id);
alter policy "notifications: recipient can delete" on public.notifications
  using ((select auth.uid()) = recipient_id);
alter policy "invite_codes: owner can view own" on public.invite_codes
  using ((select auth.uid()) = created_by);
alter policy "invite_codes: owner can delete own unredeemed" on public.invite_codes
  using ((select auth.uid()) = created_by and redeemed_by is null);
alter policy "invite_codes: capped insert" on public.invite_codes
  with check (
    (select auth.uid()) = created_by
    and (
      (select is_owner from public.profiles where user_id = (select auth.uid())) = true
      or (select count(*) from public.invite_codes where created_by = (select auth.uid())) < 2
    )
  );
