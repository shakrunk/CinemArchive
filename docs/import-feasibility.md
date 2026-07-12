# Importing Watch History & Ratings from Other Platforms — Feasibility Assessment

_Written 2026-07-12 for KP-045. Companion prototype: the Letterboxd CSV importer
in `src/lib/letterboxd-import.ts` (Profile → Data & Portability)._

## Summary

Importing from third-party platforms is **very feasible**. CinemArchive already
has every hard piece of the pipeline: TMDB search + full-metadata hydration
through the `media-proxy` Edge Function (`searchMedia` / `fetchMediaDetails` in
`src/lib/media.ts`), a client `Title` model with per-viewing dates and a 0–5
rating scale, duplicate-skipping insert logic (Profile's JSON import), and
Supabase persistence (`insertTitleToDb`). A third-party import is therefore just:

```
parse platform export → normalize rows → resolve each row to a TMDB id →
hydrate metadata → skip duplicates → insert
```

Only the first and third stages vary per platform. The dominant cost driver is
**how the platform identifies titles**: platforms that export stable IDs
(IMDb tt-ids, TMDB ids) resolve losslessly; platforms that export bare title
strings need fuzzy matching and accept some error rate.

## Per-platform assessment

| Platform | Export format | Title identity | Ratings | TV/episodes | Difficulty |
| -------- | ------------- | -------------- | ------- | ----------- | ---------- |
| **Letterboxd** | CSV zip (Settings → Data): `watched.csv`, `ratings.csv`, `diary.csv`, `watchlist.csv` | Name + Year (no IDs in CSV) | 0.5–5 stars — maps 1:1 to our 0–5 | Movies only | **Low** (prototyped) |
| **IMDb** | CSV per list (ratings export, watchlist export) | **`tt` const IDs** | 1–10 (halve to 0–5) | Titles yes, episodes as separate tt rows | **Low–Medium** |
| **Trakt** | REST API (OAuth device flow); JSON backup for VIP | **TMDB + IMDb ids inline** | 1–10 | Full episode-level history with timestamps | **Medium** |
| **Simkl** | JSON export / API | simkl id + imdb/tmdb ids (usually) | 1–10 | Yes | **Medium** |
| **Netflix** | `NetflixViewingHistory.csv` (Title, Date) | Bare display string, `"Show: Season N: Episode"` — no year, no IDs | None | Flattened into title strings | **High** (for quality) |

### Letterboxd — Low (prototype shipped)

- CSV columns are stable: `Date,Name,Year,Letterboxd URI[,Rating][,Watched Date]`.
- Match by TMDB search on `Name` filtered to movies, scored by year proximity
  (exact year ≻ ±1 year ≻ first result). Letterboxd's canon is TMDB-derived, so
  name+year matching is reliable in practice; mismatches are surfaced to the
  user as an "unmatched / skipped" list rather than silently guessed.
- Ratings are already half-star 0.5–5 → direct copy.
- `diary.csv` has true watch dates (`Watched Date`); `watched.csv` only has the
  log date, which we use as the viewing date approximation.
- Limitations that keep it "prototype": one file at a time (not the whole zip),
  movies only, and TMDB resolution is ~2–4 proxy calls per new title, so large
  histories take minutes (progress + cancel provided; the `api_cache` layer
  absorbs repeats).

### IMDb — Low–Medium

- The ratings CSV carries `Const` (tt-id), `Your Rating`, `Date Rated`, plus
  title/year for display. tt-ids resolve **exactly** via TMDB `/find/{imdb_id}`
  — no fuzzy matching at all.
- Needs one new `media-proxy` action (`find` by external id) and a function
  deploy — that's the only reason it isn't Low.
- Works for TV titles too (a tt-id can be a series); episode-row tt-ids could
  be mapped onto our episode model later.

### Trakt — Medium

- Best data fidelity of all sources: complete watch history with timestamps,
  per-episode plays, and ratings — and every object carries TMDB ids, so
  resolution is trivial.
- Cost is the auth plumbing: register a Trakt API app, implement the OAuth
  device-code flow (fits our static SPA — no secret needed with PKCE), paginate
  `/sync/history`. Roughly a weekend of work, most of it UI/state.
- This is the one source that could populate our episode-level watch events
  properly, so it's the highest-value follow-up if TV import matters.

### Simkl — Medium

- Similar shape to Trakt (API + ids), smaller user base. Do it only on demand.

### Netflix — High (for quality results)

- The CSV has just a display title and date. TV rows flatten to
  `"Show: Season 1: Episode Name"` strings that need heuristic splitting; no
  year makes movie disambiguation genuinely error-prone (remakes, same-name
  titles). No ratings at all.
- Feasible as a best-effort importer with a mandatory review step ("confirm
  each match"), but expect a meaningful manual-fix tail. Recommend deferring
  unless there's real demand.

## Shared architecture for follow-ups

The prototype deliberately splits pure logic from I/O so later importers slot in:

- `parseLetterboxdCsv` → replace per platform (pure, unit-tested).
- `pickBestMatch` (name/year → TMDB candidate scoring) → reusable for any
  name-based source; ID-based sources (IMDb/Trakt) skip it entirely.
- `importRows` (resolve → hydrate → build `Title` → progress/cancel) → already
  platform-agnostic: it consumes normalized `{ name, year, rating?, watchedDate?,
  status }` rows.

## Effort estimates

| Work item | Estimate |
| --------- | -------- |
| Letterboxd CSV (movies, one file) | **Done** (prototype) |
| Letterboxd zip (all files at once, watchlist + diary merge) | ~½ day |
| IMDb ratings/watchlist CSV (+ `find` proxy action) | ~1 day |
| Trakt OAuth + full history/ratings sync | ~2–3 days |
| Simkl | ~1–2 days |
| Netflix best-effort with review UI | ~3+ days |
