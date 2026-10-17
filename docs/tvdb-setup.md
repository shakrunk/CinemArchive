# TVDB metadata setup

The shared `media-proxy` Edge Function prefers TheTVDB v4 for matched TV show
names, English synopses, first-air dates, average runtimes, and compatible episode
names, synopses, air dates, and runtimes. This applies to both web and Android on
their next metadata fetch/refresh. Existing saved metadata is not rewritten automatically.

## Credentials

1. Create an account at [TheTVDB](https://thetvdb.com/).
2. In Dashboard → Developers, create a v4 API key. Select a licensing model
   appropriate for this shared application; a subscriber PIN alone is not an API key.
   See [TheTVDB's key application instructions](https://support.thetvdb.com/kb/faq.php?id=81).
3. In the Supabase project's Edge Functions → Secrets, add `TVDB_API_KEY`.
   If the selected key uses end-user subscriptions, also configure `TVDB_PIN` for
   authorized use under that plan. Check the licensing terms before using one
   subscriber's PIN for a multi-user service.
4. Deploy `media-proxy` (the existing main-branch workflow deploys it). Credentials
   remain server-side; never put them in `VITE_*`, Android configuration, or Git.

For local development, set these same variables in ignored
`supabase/functions/.env`. No database migration is required. TVDB responses use
the existing service-role-only `api_cache`, with a 24-hour TTL. Authentication tokens
stay in function memory, are refreshed after 12 hours, and are retried once on 401.
Each upstream request has a five-second timeout. Failed responses are not cached, and a
failed login pauses further login attempts for 60 seconds so bad credentials or an outage
do not slow every TV request.

## Compatibility and fallback

TMDB remains the catalog/search and identity provider. The adapter uses the exact
`external_ids.tvdb_id` cross-reference; it never guesses by show name. Movies are
unaffected. Artwork, people/credits, genres, season inventories, recommendations,
trailers, and streaming availability continue to come from TMDB. These provider
IDs and artwork paths are not interchangeable with TVDB's.

Episode metadata uses TVDB's official aired order in English. It is applied only
when the complete episode-number set agrees with TMDB, the season numbers match,
and known air dates do not conflict. Disagreements retain the TMDB season so a
provider switch cannot silently move watched episodes. Empty TVDB fields retain
TMDB values. Missing keys, missing mappings, and upstream errors also fall back.
Successful TVDB overlays include `metadata_source: "tvdb"` and `tvdb_id` in the
gateway response; these describe an overlay, not exclusive ownership of every field.

## Verification

Run `rtk proxy node --experimental-strip-types --test scripts/tvdb.test.mjs`.
After configuring credentials and deploying, request `action=details&type=tv&id=1396`
through the authenticated media gateway and check `metadata_source` and `tvdb_id`.
Request a season and confirm episode numbers/IDs remain stable. Refresh an existing
show in each client. Live TVDB access cannot be verified until credentials exist.

API contract: [official TVDB v4 specification](https://github.com/thetvdb/v4-api/blob/main/docs/swagger.yml).
Secret storage: [Supabase Edge Function secrets](https://supabase.com/docs/guides/functions/secrets).
