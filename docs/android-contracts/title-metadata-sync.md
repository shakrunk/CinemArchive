# Rich title metadata sync

The owner-scoped `sync_library_changes` feed adds `titleMetadataVersion: 1` to
title payloads in migration `20261008230747_rich_title_metadata_sync.sql`. The
existing `personCreditsVersion: 1` marker remains independent.

| Payload field | Shared title column |
| --- | --- |
| `contentRating` | `content_rating` |
| `imdbId` | `imdb_id` |
| `rtUrl` | `rt_url` |
| `rtScore` | `rt_score` |
| `metacriticScore` | `metacritic_score` |
| `customWatchUrl` | `custom_watch_url` |
| `inHomeCollection` | `in_home_collection` |
| `physicalMedia` | `physical_media` |
| `awardsCount` | `awards_count` |
| `bechdelOutcome` | `bechdel_outcome` |
| `bechdelScore` | `bechdel_score` |

The migration changes the read payload only. It does not rewrite stored values,
title timestamps, UUIDs, histories or receipts. Consumers upgrading their local
storage must replay from epoch and must observe the capability marker before
acknowledging that backfill. An empty response or an older backend without the
marker cannot prove that all existing rows have been upgraded.

All listed keys are present in an upgraded title payload. Explicit null values
clear nullable fields; `false`, zero scores/counts and empty arrays are real
values, not missing metadata. Older payloads with missing keys must preserve
local values. `physicalMedia` retains the stored JSON array, including copy IDs,
format, edition and notes. Copy-import remapping is a separate operation; sync
must not regenerate those IDs.

Read visibility remains scoped to `auth.uid()`. Anonymous callers cannot execute
the public wrapper or private function. This feed is not the anonymous shared
library API. Displaying or opening watch links still requires the client's URL
validation and current-account checks.

Local PostgreSQL tests in `apps/web/scripts/rich-title-metadata-sync.test.mjs`
cover unchanged-row backfill, all added values, owner isolation, anonymous
denial, explicit clears and exact title tombstones. These tests do not establish
Android Room storage, UI behavior, real server deployment or device acceptance;
those remain separate integration work.
