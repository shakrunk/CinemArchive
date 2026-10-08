# Web tooling and typing debt

Tracks the remaining `@typescript-eslint/no-explicit-any` warnings in `apps/web` (part of
[#278](https://github.com/shakrunk/CinemArchive/issues/278)). The enforced ceiling is the
`--max-warnings` value of `lint:ci` in `apps/web/package.json`; lower it whenever a cleanup lands.

## Status

| Date | Warnings | Change |
|------|----------|--------|
| 2026-10-07 | 78 | Baseline when the budget was introduced |
| 2026-10-08 | 48 | `src/lib/media.ts` (29) and `src/lib/ics.test.ts` (1) cleared |

## Remaining warnings (48)

| File | Count |
|------|-------|
| `src/lib/db.ts` | 36 |
| `src/lib/export-import.ts` | 7 |
| `src/lib/sync/plex.ts` | 3 |
| `src/lib/sync/apply.ts` | 1 |
| `src/lib/sync/simkl.ts` | 1 |

Regenerate with `npx eslint . -f json` from `apps/web/`.

## Approach used for `media.ts`

`media-proxy` forwards TMDB/OMDb JSON verbatim, so `supabase.functions.invoke` returns `data`
typed `any`. Rather than casting the whole payload, `media.ts` declares small private interfaces
for the raw shapes it reads (`TmdbItem`, `TmdbCastEntry`, `TmdbVideo`, `TmdbProvider`,
`TmdbPerson`, `TmdbCompany`, `TmdbImage`, `OmdbRating`, `TmdbCertificationSource`), with every
field optional unless the code already assumed it. Callbacks are annotated with these types;
`data` itself stays untyped at the network boundary.

Behavior changes: none intended. One deliberate simplification: `fetchSeasonDetails` now reuses
`mapTmdbCast` instead of an inline copy of the same mapping (identical output for the
non-aggregate shape).

## Suggested next steps

- `db.ts` (36): the same technique — raw row interfaces for the Supabase tables at the
  row-to-client mapping boundary. Consider generating them from `schema.sql`.
- `export-import.ts`, `sync/*`: type the external file/API payloads (Plex, Simkl, import files),
  validating at the boundary where the input is untrusted.

## Evidence (2026-10-08)

Typecheck (`tsc -b`) clean; lint 0 errors / 48 warnings; Vitest 240/240 passed; production build passed.
