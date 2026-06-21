# Extended Metadata: Cast, Crew & Studios

**Date:** 2026-06-21  
**Status:** Approved

## Overview

Add normalized cast, crew, and studio metadata to movies and TV shows. Display in a dedicated "Cast & Crew" section inside the detail drawer. TV shows get three levels of granularity: series-level cast/crew, season-level cast aggregates, and per-episode director/writer credits.

Chosen storage approach: **normalized relational tables** (Approach B). This makes cast/crew searchable across the library, enabling a future "browse by actor / writer" feature without a painful schema migration.

---

## 1. Schema Changes

### New tables

```sql
-- Series/movie-level cast (top 10 by TMDB order)
CREATE TABLE title_cast (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  title_id UUID NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
  tmdb_person_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  character_name TEXT,
  profile_url TEXT,
  cast_order INTEGER NOT NULL DEFAULT 0,
  UNIQUE (title_id, tmdb_person_id)
);

-- Series/movie-level crew: Director, Screenplay, Writer, Producer,
-- Director of Photography, Original Music Composer, Creator (TV)
CREATE TABLE title_crew (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  title_id UUID NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
  tmdb_person_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  job TEXT NOT NULL,
  department TEXT,
  profile_url TEXT,
  UNIQUE (title_id, tmdb_person_id, job)
);

-- Season-level cast (regulars/guests billed in that season)
CREATE TABLE season_cast (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  title_id UUID NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
  season_id UUID NOT NULL REFERENCES seasons(id) ON DELETE CASCADE,
  tmdb_person_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  character_name TEXT,
  profile_url TEXT,
  cast_order INTEGER NOT NULL DEFAULT 0,
  UNIQUE (season_id, tmdb_person_id)
);

-- Per-episode crew: Director and Writer(s)
CREATE TABLE episode_crew (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  title_id UUID NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
  episode_id UUID NOT NULL REFERENCES episodes(id) ON DELETE CASCADE,
  tmdb_person_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  job TEXT NOT NULL,
  UNIQUE (episode_id, tmdb_person_id, job)
);
```

RLS on all four tables: `user_id = auth.uid()` for mutations; shared-token holders get SELECT via the existing `set_shared_token` RPC.

### Column addition

```sql
ALTER TABLE titles ADD COLUMN studios TEXT[] DEFAULT '{}';
```

Studios are production company names (e.g. `['Warner Bros.', 'Legendary']`). They are people-independent, so a simple array column is appropriate rather than a fifth normalized table.

---

## 2. TypeScript Model Changes (`src/store/mockData.ts`)

### New interfaces

```typescript
export interface CastMember {
  tmdbPersonId: number
  name: string
  character?: string
  profileUrl?: string
  order: number
}

export interface CrewMember {
  tmdbPersonId: number
  name: string
  job: string        // 'Director' | 'Screenplay' | 'Writer' | 'Producer' |
                     // 'Director of Photography' | 'Original Music Composer' | 'Creator'
  department?: string
  profileUrl?: string
}

export interface EpisodeCrew {
  tmdbPersonId: number
  name: string
  job: string        // 'Director' | 'Writer' | 'Teleplay' | 'Story'
}
```

### `Title` additions

```typescript
cast?: CastMember[]
crew?: CrewMember[]
studios?: string[]
```

### `Season` additions

```typescript
cast?: CastMember[]
```

### `Episode` additions

```typescript
director?: string      // derived display field — first Director from episode_crew
writers?: string[]     // derived display field — Writer/Teleplay/Story from episode_crew
crew?: EpisodeCrew[]   // full normalized crew (for DB writes and future browsing)
```

---

## 3. Edge Function Changes (`supabase/functions/media-proxy/index.ts`)

**One line change** — add `&append_to_response=credits` to the season URL so TMDB returns season-level cast alongside the episode list:

```typescript
// Before
const url = `${TMDB_BASE}/tv/${tmdbId}/season/${seasonNumber}?api_key=${TMDB_API_KEY}&language=en-US`
// After
const url = `${TMDB_BASE}/tv/${tmdbId}/season/${seasonNumber}?api_key=${TMDB_API_KEY}&language=en-US&append_to_response=credits`
```

The standard TMDB season response already includes `episodes[n].crew[]` (per-episode crew) without any extra params. The `credits` append adds `data.credits.cast[]` — the season-billed cast list.

---

## 4. `media.ts` Changes

### Updated `RawTmdbEpisode`

Add `crew` array to the existing interface:

```typescript
export interface RawTmdbEpisode {
  episode_number: number
  name: string
  overview?: string
  air_date?: string
  runtime?: number
  still_path?: string
  crew?: Array<{ id: number; name: string; job: string; department?: string; profile_path?: string }>
}
```

### New `SeasonFetchResult` return type

```typescript
export interface SeasonFetchResult {
  episodes: RawTmdbEpisode[]
  cast: CastMember[]
}
```

`fetchSeasonDetails` now returns `Promise<SeasonFetchResult>` instead of `Promise<RawTmdbEpisode[]>`. All three callers are updated accordingly.

### `SearchResult` additions

```typescript
cast?: CastMember[]
crew?: CrewMember[]
studios?: string[]
```

### `fetchMediaDetails` extraction logic

Constants controlling what gets stored:
- **Cast:** top 10 by TMDB `order`
- **Crew jobs fetched:** `Director`, `Screenplay`, `Writer`, `Producer`, `Director of Photography`, `Original Music Composer`
- **TV creators:** extracted from `data.created_by` with `job: 'Creator'`
- **Studios:** all entries from `data.production_companies[].name`
- **Dedup:** track `tmdb_person_id + job` to avoid duplicate crew rows

---

## 5. `db.ts` Changes

### Updated fetch queries

Both `fetchUserLibrary` and `fetchSharedLibrary` add the four new table joins:

```typescript
supabase.from('titles').select(`
  *,
  title_cast (*),
  title_crew (*),
  seasons (
    *,
    season_cast (*)
  ),
  viewings (*),
  episodes (
    *,
    episode_crew (*),
    episode_watch_events (*),
    episode_ratings (*),
    episode_reviews (*)
  )
`)
```

### `mapDbTitleToLocal` additions

- Map `row.title_cast` → `Title.cast[]` (sorted by `cast_order`)
- Map `row.title_crew` → `Title.crew[]`
- Map `row.studios` → `Title.studios`
- Map `season.season_cast` → `Season.cast[]` (sorted by `cast_order`)
- Map `ep.episode_crew` → `Episode.crew[]`, derive `Episode.director` and `Episode.writers`

### `insertTitleToDb` additions

After the existing title/seasons/episodes insert, add fire-and-forget writes for:
- `title_cast` rows from `title.cast`
- `title_crew` rows from `title.crew`
- `studios` is included directly in the initial `titles` insert (not a separate update)
- `season_cast` rows from each `season.cast`
- `episode_crew` rows from each `episode.crew`

### `updateTitleInDb` additions

- Add `studios` to `META_COLUMNS` (mapped to `studios` column)
- When `patch.cast` is present: **delete all existing `title_cast` rows** for the title, then insert the new set. Same for `patch.crew` → `title_crew`. Delete-then-insert is used (not upsert) so stale cast members removed from the TMDB credits don't persist indefinitely.

### New helper functions

```typescript
export async function upsertTitleCastInDb(userId: string, titleId: string, cast: CastMember[]): Promise<void>
export async function upsertTitleCrewInDb(userId: string, titleId: string, crew: CrewMember[]): Promise<void>
export async function upsertSeasonCastInDb(userId: string, titleId: string, seasonId: string, cast: CastMember[]): Promise<void>
export async function upsertEpisodeCrewInDb(userId: string, titleId: string, episodeId: string, crew: EpisodeCrew[]): Promise<void>
```

All use `upsert` with their respective unique constraints so re-fetch doesn't create duplicates.

---

## 6. `AddTitleWorkflow.tsx` Changes

### `selectResult` function

Update the `fetchSeasonDetails` call to destructure the new return type:

```typescript
// Before
.map((s) => fetchSeasonDetails(...).then((eps) => ({ seasonNumber, eps })))
// After
.map((s) => fetchSeasonDetails(...).then(({ episodes, cast }) => ({ seasonNumber, episodes, cast })))
```

Build a `seasonCastBySeason: Map<number, CastMember[]>` alongside `episodesBySeason`.

Pass `seasonCastBySeason` into `buildSeasons`.

### `buildSeasons` function

- Accept `seasonCastBySeason?: Map<number, CastMember[]>` parameter
- Set `season.cast` from map
- Set `episode.director`, `episode.writers`, `episode.crew` from `tmdbEp.crew`

### `handleSave` function

Pass `cast`, `crew`, `studios` from `selected` (the detailed `SearchResult`) into the `newTitle` object.

---

## 7. `RefreshMetadataModal.tsx` Changes

### `toSearchResult`

Add `cast`, `crew`, `studios` from the source `Title`.

### `applyFrom`

- Add `cast`, `crew`, `studios` to the `patch` built from `result`
- Update the `fetchSeasonDetails` call to use new return type
- After episode upsert, also call `upsertSeasonCastInDb` and `upsertEpisodeCrewInDb`

---

## 8. `TitleDetailDrawer.tsx` Changes

### Backfill effect

Update the `fetchSeasonDetails` call to use the new `SeasonFetchResult` type. After persisting episode metadata, also call `upsertSeasonCastInDb` and `upsertEpisodeCrewInDb`.

### New `CastCrewSection` component

Rendered between Synopsis and Critical Reception for both movies and TV:

- **Cast row:** horizontal scrollable avatar chips (56×56 circle, name, character)
- **Crew rows:** labeled table — `Creator`, `Dir.`, `Written by`, `Prod.`, `D.O.P.`, `Composer`
- **Studios line:** same label-row style as crew

Only rendered when at least one of cast/crew/studios is non-empty.

### `EpisodePanel` update

Add a compact crew line above the still/synopsis when `episode.director` or `episode.writers` is set:

```
Dir. Ben Stiller  ·  Written by Dan Erickson
```

### `TVSeriesSection` update

When a season tab is selected, show `season.cast` as a horizontal avatar scroll between the episode list header and the episode rows (only when the season has cast data).

---

## 9. Key Constraints

- **Existing `title.director` field is preserved.** The `Director` crew member is also written to `title_crew`, enabling future person-centric queries. The hero area continues to show `dir. X` as before; the Cast & Crew section is additive.
- **Season `0` (specials)** is excluded from both the UI and TMDB season fetches, consistent with existing behavior.
- **Profile images** use TMDB's `w185` size for cast/crew avatars (compact, mobile-friendly).
- **TMDB cache** already covers the details and season endpoints; the `append_to_response=credits` addition will be a cache-miss on existing entries but hits on re-fetch (24-hour TTL). Invalidate cache for affected entries by bumping the cache key or waiting for TTL.
- **Backfill for existing TV titles** — cast/crew are populated the next time the title's detail drawer is opened (the existing episode backfill effect is extended to also fetch season cast + episode crew). **For movies**, there is no automatic backfill trigger — cast/crew are populated on the next "Refresh metadata" invocation. No bulk migration script is needed; the data accumulates organically.
