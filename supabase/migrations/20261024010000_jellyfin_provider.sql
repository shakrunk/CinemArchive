-- Jellyfin as a sync provider (feat: confirm-before-adding Jellyfin watch history).
-- Reuses integration_connections / external_title_links; the access token stays on the
-- user's device, so nothing new is stored in integration_secrets.

alter type integration_provider add value if not exists 'jellyfin';
