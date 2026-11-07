# Jellyfin watch sync on Android

Android counterpart of the web Jellyfin sync (`apps/web/src/lib/sync/jellyfin*.ts`,
`JellyfinWatchPrompt.tsx`, `SyncConnections.tsx`). Tracked by issue #373. No backend change: the
`jellyfin` value of `integration_provider` (migration `20261024010000_jellyfin_provider.sql`)
backs the owner-only `integration_connections` row, and `external_title_links` provenance for
imported titles.

## Sign-in and credentials

- Per-user `POST {server}/Users/AuthenticateByName` with `Authorization: MediaBrowser Client=...,
  Device="Android", DeviceId=<per-install id>`. The token is scoped to that one Jellyfin user, so
  only that user's plays are ever visible. No server API key is used.
- The server address gets `https://` when no scheme is given and loses trailing slashes; a base
  path such as `/jellyfin` is kept.
- The password is sent only to the user's own server and is never stored. The access token, the
  cursor and the pending queue are stored on the device only, in `EncryptedSharedPreferences`
  (`cinemarchive_jellyfin_<owner key>`, one file per CinemArchive account). Nothing about the
  session reaches Supabase; `integration_connections` records only the server URL, the Jellyfin
  user name, and a timestamp.
- Disconnect posts `/Sessions/Logout` (best effort), deletes the stored state and removes the
  connection row. A 401 while polling flags the session as expired; the card then shows "Sign in
  again", and signing in again keeps the cursor and queue so missed plays still prompt.

## Polling, cursor and queue

Same semantics as `jellyfinPending.ts`:

- Connecting starts the cursor at "now": history from before connecting is never prompted for.
- A check lists the user's 100 most recently played movies and episodes
  (`Filters=IsPlayed`, `SortBy=DatePlayed`) and keeps plays strictly newer than the cursor, oldest
  first. Episodes without season and episode numbers are skipped. Episode plays carry the
  provider ids of their series (second lookup by series id).
- A play's key is `<itemId>@<LastPlayedDate>`: a re-watch (new date) prompts again; the same play
  never queues twice. Queuing advances the cursor to the newest play seen, so an answered play
  never returns.
- A play leaves the queue only when answered (Add or Dismiss). An unreachable server is retried
  at the next check without any error shown.

Checks run from a periodic WorkManager job (`jellyfin_watch_check`, network-constrained), when an
account runtime starts, and from "Check for new watches". WorkManager's periodic floor is 15
minutes, where the web app polls every 5 while open. The job is scheduled only while a Jellyfin
session exists. Only the job and the app-start check notify, and only for newly queued plays.

## Confirm before add

Nothing is written without an explicit yes. A new play posts a notification with **Add** and
**Dismiss** actions (channel `jellyfin_watches`); the same queue is listed under Settings -> Import
& sync, which is the fallback when notifications are denied. The actions run in a short-lived
worker, and only for the account the notification was posted for; sign-out clears the account's
notifications.

| Play | Add does |
| --- | --- |
| Movie | Resolves the TMDB id, then the standard provider import (`SyncRepository.import`): new titles are admitted as a complete graph with `external_title_links` provenance, existing titles get a non-overwriting merge adding the watch date. Same day already logged: "Already in your history". |
| Episode, series and episode in the library | Logs a watch event (`logEpisodeWatched`) dated the play day. Same day already on that episode: duplicate, nothing written. |
| Episode, series not in the library (or episode not in its catalog) | Writes nothing; opens the normal Add flow preselected on the series. From a notification this is a follow-up "tap to add" notification. |
| No TMDB match | "Couldn't match that to a title"; the play leaves the queue. |

A lookup or storage failure keeps the play queued for a retry.

## Optional history import

"Import existing history" signs in and runs the standard import over the user's Jellyfin movies and
series (`mapJellyfinItems`: played movies with their last-played date; series fully or partly
played). It never overwrites existing ratings or viewings.

## Tests

`JellyfinTest` (data module) mirrors the web `jellyfin.test.ts`: play mapping and cursor
filtering, history mapping, URL normalization, queue dedupe and resolve, episode log planning. It
adds state JSON round trips, sign-in and 401 handling, and the `JellyfinWatch` check, expiry and
disconnect behavior, using canned HTTP responses.

## Not covered

- No recorded fixture or on-device pass against a real Jellyfin server has been run.
- Background polling is every 15 minutes at best, and Doze may defer it further.
