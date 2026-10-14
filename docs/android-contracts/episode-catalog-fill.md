# Filling missing episode parents

Android's credit refresh also fills episode parents needed for season and episode
credits. Its bounded selection matches `apps/web/src/lib/refreshMetadata.ts`:

- Fill a stored season only when it has no local episode rows.
- Add a missing Specials season (season zero) only after the provider returns
  actual episodes. Preserve their numbering, including gaps.
- Do not create an empty Specials shell after an empty or failed response.
- Do not append missing episodes to a partially populated season or add newly
  discovered regular seasons in this slice.

The provider snapshot is queued durably. New parents become editable only after
the server supplies canonical identities; Android does not create provisional
episode IDs that would later require moving watch histories.

## Server identity and retries

The shared `apply_library_command` API uses `ensure` with these identities:

| Table | Natural key | Initial provider values |
| --- | --- | --- |
| `seasons` | `title_id`, `season_number` | `episode_count`, `air_year` |
| `episodes` | `title_id`, `season_number`, `episode_number` | `episode_name`, `air_date`, `runtime`, `synopsis`, `still_url` |

An absent row receives a server UUID. An existing row is returned unchanged,
including metadata and progress. Two devices creating the same natural identity
must receive the same canonical parent. A title ownership/existence barrier leads
the command. No watch, rating, review, or `episodes_watched` value is supplied.
The API locks owned parents through insertion. A legacy row with the same natural
key under another owner is a conflict, never a row to adopt. The restriction is
implemented by migration `20261008214554_ensure_episode_catalog_parents.sql`.

The operation UUID and JSON remain immutable through retries. A cached receipt
describes past acceptance, so the transport separately fetches current owned rows
for the confirmed parent UUIDs. Deleted parents are omitted from local application;
an old receipt cannot recreate them. Each returned row must match the confirmed
table, UUID, owner, title, season number and episode number.
A new explicit ensure operation after deletion may create a new canonical UUID;
replaying the old operation may not.

## Local acknowledgment

The outbox applies the current parent rows and queues their credit writes in one
Room transaction before removing the parent-fill command. Existing local parent
metadata and history remain unchanged. Credit writes use the canonical parent IDs
and respect already queued local credit edits. A local acknowledgment/storage
failure rolls back the entire transaction and retains the original operation for
retry. A conflicting local natural identity with another UUID fails closed and
retains saved history rather than deleting or silently reparenting it.

Sync saves an epoch rewind before pushing the parent-fill command. That rewind
survives interruption after acknowledgment and recovers older rows skipped during
pending protection. Account runtime fencing applies to fetching, acknowledgment,
and the subsequent ordinary sync.

## Coarse progress is preserved, not invented history

The stored `SeasonEntity.episodesWatched` is never rewritten by this fill. Neither
client can infer which episode was watched from that aggregate, so no synthetic
watch events are created.

Both clients display actual episode history once episode rows exist. Web
`episodesWatchedInSeason` (`apps/web/src/store/episodeUtils.ts`) falls back to the
stored count only while the episode array is empty. Thus a stored coarse count of
two remains two in storage, but after filling three unwatched episode rows the
displayed detailed count is zero until real history is present. Android
`CalculationParityTest` explicitly verifies this switch and retained stored count;
the web helper's existing tests verify the same precedence rule.

This is a distinction between preserved aggregate data and displayed detailed
history, not a conversion of aggregate progress into fabricated viewing events.
