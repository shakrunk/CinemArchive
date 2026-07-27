-- One-shot data repair: `titles.added_at` holding a release date (issue #177 §1).
--
-- The v1 importer (apps/web/scripts/migrate-from-v1.mjs) anchored added_at to the
-- entry's first watch date and, for an entry that had none, fell back to the v1
-- `ReleaseDate`. That fallback is fixed at the source in the same change as this
-- migration, but the rows it already produced are still in the table — added_at
-- values spanning back to 1994, which is a release-date range, not a library-add
-- range.
--
-- Effect being corrected: the Ledger's Shifting Standards plots a rated title in
-- the quarter of its first dated viewing and falls back to added_at when it has
-- none (docs/android-contracts/ledger.md §2, and deriveTrajectory in the web app's
-- ledgerDerive.ts). Most rated titles hit that fallback, so the chart stretched
-- back three decades.
--
-- Predicate rationale, one clause at a time:
--
--   * `added_at < 2026-06-16` — CinemArchive did not exist before the v1 import, so
--     an earlier added_at cannot be a genuine "added to the library" timestamp.
--   * no viewings — an entry that *had* watch dates got dates[0] instead, a real
--     date worth preserving even though it also predates the import.
--   * added_at's year = the title's release year — the exact signature the release-
--     date fallback leaves behind. Redundant with the first clause against real
--     data, kept so the statement can't be misread as a blanket date cutoff.
--
-- Rows are rewritten to the import date itself: the honest answer to "when did
-- this enter the library" for a v1-imported title is "when the import ran".
-- Idempotent — a repaired row no longer matches the predicate. The `titles`
-- update trigger bumps updated_at, so both clients pick the corrected value up on
-- their next sync rather than needing a cache reset.

update titles t
set added_at = timestamptz '2026-06-16 00:00:00+00'
where t.added_at < timestamptz '2026-06-16 00:00:00+00'
  and t.year is not null
  -- Pinned to UTC: the importer wrote a bare date through `new Date(...).toISOString()`,
  -- so it is stored as UTC midnight and would fall into the previous year under any
  -- session timezone west of Greenwich.
  and extract(year from (t.added_at at time zone 'utc')) = t.year
  and not exists (select 1 from viewings v where v.title_id = t.id);
