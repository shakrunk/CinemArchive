# Extended Metadata: Cast, Crew & Studios — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add normalized cast, crew, and studio metadata to all titles, displayed in a new Cast & Crew section in the detail drawer; TV shows additionally get season-level cast chips and per-episode director/writer credits inside each episode panel.

**Architecture:** Four new Postgres tables (`title_cast`, `title_crew`, `season_cast`, `episode_crew`) store people with TMDB person IDs enabling future "browse by actor/writer" queries; `studios TEXT[]` on `titles` stores production company names. The TMDB season endpoint gains `append_to_response=credits`. Change flows through: DB schema → TypeScript model → Edge Function → `media.ts` → `db.ts` → three UI components.

**Tech Stack:** Vite + React + TypeScript + Tailwind CSS + Supabase (Postgres + RLS) + Supabase Edge Function (Deno) + TMDB API (proxied)

## Global Constraints

- All DB changes go in a new timestamped migration file **and** `schema.sql` must stay in sync (CLAUDE.md requirement; `schema.sql` is a reference snapshot, not executed incrementally)
- RLS on every new table: two policies each — `owner full access` (all ops, `auth.uid() = user_id`) and `shared key read` (SELECT, `is_valid_shared_token(current_setting('app.shared_token', true), user_id)`) — exact pattern from the existing `episodes` table policies in `supabase/migrations/20260620084847_initial_schema.sql`
- TMDB requests never come from the browser — always via `media-proxy` Edge Function
- Profile image URLs: TMDB `/w185` size prefix `https://image.tmdb.org/t/p/w185`
- Cast limit: top 10 by TMDB `order` field
- Title-level crew jobs: `Director`, `Screenplay`, `Writer`, `Producer`, `Director of Photography`, `Original Music Composer`; TV creators from `data.created_by` get `job: 'Creator'`
- Episode crew jobs extracted: `Director`, `Writer`, `Teleplay`, `Story`
- Season endpoint cache key must be bumped to `tmdb:season:v2:{tmdbId}:{seasonNumber}` to avoid stale cached responses (old cache lacks the `credits` field added by this feature)
- `npm run build` and `npm run lint` must pass after every task commit
- No new npm dependencies

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `supabase/migrations/20260621000001_cast_crew_metadata.sql` | Create | New tables + studios column + RLS |
| `schema.sql` | Modify | Sync snapshot with migration |
| `src/store/mockData.ts` | Modify | `CastMember`, `CrewMember`, `EpisodeCrew` interfaces; extend `Title`, `Season`, `Episode` |
| `supabase/functions/media-proxy/index.ts` | Modify | `append_to_response=credits` on season URL; bump cache key |
| `src/lib/media.ts` | Modify | `RawTmdbEpisode.crew[]`; `SeasonFetchResult`; updated `fetchSeasonDetails`; `fetchMediaDetails` extracts cast/crew/studios |
| `src/lib/db.ts` | Modify | Updated fetch queries (4 new joins); `mapDbTitleToLocal` maps new fields; `insertTitleToDb` writes cast/crew; `updateTitleInDb` refreshes cast/crew; 4 new upsert helpers |
| `src/components/AddTitleWorkflow.tsx` | Modify | `buildSeasons` extracts episode crew + season cast; `selectResult` destructures `SeasonFetchResult`; `handleSave` passes cast/crew/studios |
| `src/components/RefreshMetadataModal.tsx` | Modify | `toSearchResult` includes cast/crew/studios; `applyFrom` builds full patch + fires crew DB writes |
| `src/components/TitleDetailDrawer.tsx` | Modify | Backfill effect extended for season cast + episode crew; new `CastCrewSection`; `EpisodePanel` crew line; `TVSeriesSection` season cast |

---

### Task 1: DB Migration — Four New Tables + Studios Column

**Files:**
- Create: `supabase/migrations/20260621000001_cast_crew_metadata.sql`
- Modify: `schema.sql`

**Interfaces:**
- Produces: `title_cast`, `title_crew`, `season_cast`, `episode_crew` tables; `titles.studios TEXT[]` column — consumed by Tasks 4–8

- [ ] **Step 1: Create migration file**

Create `supabase/migrations/20260621000001_cast_crew_metadata.sql` with the following content:

```sql
-- Extended metadata: normalized cast/crew tables + studios column
-- Enables future "browse by actor / writer" queries across the library.

alter table titles add column studios text[] default '{}';

-- ── title_cast ───────────────────────────────────────────────────────────────
create table title_cast (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  title_id        uuid not null references titles(id) on delete cascade,
  tmdb_person_id  integer not null,
  name            text not null,
  character_name  text,
  profile_url     text,
  cast_order      integer not null default 0,
  created_at      timestamptz default now(),
  constraint unique_title_cast unique (title_id, tmdb_person_id)
);

-- ── title_crew ───────────────────────────────────────────────────────────────
create table title_crew (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  title_id        uuid not null references titles(id) on delete cascade,
  tmdb_person_id  integer not null,
  name            text not null,
  job             text not null,
  department      text,
  profile_url     text,
  created_at      timestamptz default now(),
  constraint unique_title_crew unique (title_id, tmdb_person_id, job)
);

-- ── season_cast ──────────────────────────────────────────────────────────────
create table season_cast (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  title_id        uuid not null references titles(id) on delete cascade,
  season_id       uuid not null references seasons(id) on delete cascade,
  tmdb_person_id  integer not null,
  name            text not null,
  character_name  text,
  profile_url     text,
  cast_order      integer not null default 0,
  created_at      timestamptz default now(),
  constraint unique_season_cast unique (season_id, tmdb_person_id)
);

-- ── episode_crew ─────────────────────────────────────────────────────────────
create table episode_crew (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null references auth.users(id) on delete cascade,
  title_id        uuid not null references titles(id) on delete cascade,
  episode_id      uuid not null references episodes(id) on delete cascade,
  tmdb_person_id  integer not null,
  name            text not null,
  job             text not null,
  created_at      timestamptz default now(),
  constraint unique_episode_crew unique (episode_id, tmdb_person_id, job)
);

-- ── Indexes ──────────────────────────────────────────────────────────────────
create index title_cast_title_id_idx   on title_cast(title_id);
create index title_cast_person_id_idx  on title_cast(tmdb_person_id);
create index title_crew_title_id_idx   on title_crew(title_id);
create index title_crew_person_id_idx  on title_crew(tmdb_person_id);
create index season_cast_season_id_idx on season_cast(season_id);
create index season_cast_person_id_idx on season_cast(tmdb_person_id);
create index ep_crew_episode_id_idx    on episode_crew(episode_id);
create index ep_crew_person_id_idx     on episode_crew(tmdb_person_id);

-- ── RLS ──────────────────────────────────────────────────────────────────────
alter table title_cast   enable row level security;
alter table title_crew   enable row level security;
alter table season_cast  enable row level security;
alter table episode_crew enable row level security;

create policy "title_cast: owner full access"
  on title_cast for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "title_cast: shared key read"
  on title_cast for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

create policy "title_crew: owner full access"
  on title_crew for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "title_crew: shared key read"
  on title_crew for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

create policy "season_cast: owner full access"
  on season_cast for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "season_cast: shared key read"
  on season_cast for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));

create policy "episode_crew: owner full access"
  on episode_crew for all
  using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "episode_crew: shared key read"
  on episode_crew for select
  using (is_valid_shared_token(current_setting('app.shared_token', true), user_id));
```

- [ ] **Step 2: Sync schema.sql**

In `schema.sql`:

1. Add `studios text[] not null default '{}',` to the column list inside `create table titles` (after `metacritic_score`).

2. Append the four new `create table` blocks (copy verbatim from Step 1, changing `add column` to inline column — they go after the existing table definitions and before the indexes section).

3. Add the new indexes to the indexes section.

4. Add the RLS blocks to the RLS section.

- [ ] **Step 3: Verify build**

```
npm run build
```
Expected: succeeds (TypeScript doesn't reference the schema directly).

- [ ] **Step 4: Commit**

```
git add supabase/migrations/20260621000001_cast_crew_metadata.sql schema.sql
git commit -m "feat(db): add cast/crew/studios tables with RLS"
```

---

### Task 2: TypeScript Model (`src/store/mockData.ts`)

**Files:**
- Modify: `src/store/mockData.ts`

**Interfaces:**
- Produces (exported from `mockData.ts`):
  - `CastMember { tmdbPersonId: number; name: string; character?: string; profileUrl?: string; order: number }`
  - `CrewMember { tmdbPersonId: number; name: string; job: string; department?: string; profileUrl?: string }`
  - `EpisodeCrew { tmdbPersonId: number; name: string; job: string }`
  - `Episode.director?: string`, `Episode.writers?: string[]`, `Episode.crew?: EpisodeCrew[]`
  - `Season.cast?: CastMember[]`
  - `Title.cast?: CastMember[]`, `Title.crew?: CrewMember[]`, `Title.studios?: string[]`

- [ ] **Step 1: Add three new interfaces after the `WatchStatus` type line**

In `src/store/mockData.ts`, add immediately after `export type WatchStatus = ...`:

```typescript
export interface CastMember {
  character?: string
  name: string
  order: number
  profileUrl?: string
  tmdbPersonId: number
}

export interface CrewMember {
  department?: string
  job: string
  name: string
  profileUrl?: string
  tmdbPersonId: number
}

export interface EpisodeCrew {
  job: string
  name: string
  tmdbPersonId: number
}
```

- [ ] **Step 2: Extend `Episode` interface**

Add three fields after `stillUrl?: string` in the `Episode` interface:

```typescript
  director?: string
  writers?: string[]
  crew?: EpisodeCrew[]
```

- [ ] **Step 3: Extend `Season` interface**

Add one field after `episodes?: Episode[]` in the `Season` interface:

```typescript
  cast?: CastMember[]
```

- [ ] **Step 4: Extend `Title` interface**

Add three fields after `metacriticScore?: number` in the `Title` interface:

```typescript
  cast?: CastMember[]
  crew?: CrewMember[]
  studios?: string[]
```

- [ ] **Step 5: Verify build**

```
npm run build
```
Expected: succeeds — all new fields are optional, so all existing mock data and downstream code remains valid.

- [ ] **Step 6: Commit**

```
git add src/store/mockData.ts
git commit -m "feat(model): add CastMember/CrewMember/EpisodeCrew interfaces; extend Title/Season/Episode"
```

---

### Task 3: Edge Function — Season Endpoint Update

**Files:**
- Modify: `supabase/functions/media-proxy/index.ts`

**Interfaces:**
- Produces: TMDB season response now includes `credits.cast[]` (season cast) alongside the existing `episodes[].crew[]` (per-episode crew already present in the TMDB response)

- [ ] **Step 1: Update `getTMDBSeasonDetails` in the Edge Function**

In `supabase/functions/media-proxy/index.ts`, replace the `getTMDBSeasonDetails` function:

```typescript
async function getTMDBSeasonDetails(tmdbId: number, seasonNumber: number) {
  // Cache key bumped to v2 — v1 entries lack the credits field added by append_to_response
  const cacheKey = `tmdb:season:v2:${tmdbId}:${seasonNumber}`
  const cached = await getCached(cacheKey)
  if (cached) return cached

  const url = `${TMDB_BASE}/tv/${tmdbId}/season/${seasonNumber}?api_key=${TMDB_API_KEY}&language=en-US&append_to_response=credits`
  const res = await fetch(url)
  const data = await res.json()

  await setCached(cacheKey, data)
  return data
}
```

- [ ] **Step 2: Verify build**

```
npm run build
```
Expected: succeeds (no TypeScript changes, just a Deno function string).

- [ ] **Step 3: Commit**

```
git add supabase/functions/media-proxy/index.ts
git commit -m "feat(edge): append season credits to TMDB season endpoint; bump cache key to v2"
```

---

### Task 4: `media.ts` — Fetch Layer Updates + Caller Fixes

**Files:**
- Modify: `src/lib/media.ts`
- Modify: `src/components/AddTitleWorkflow.tsx` (minimal — destructure fix only)
- Modify: `src/components/RefreshMetadataModal.tsx` (minimal — destructure fix only)
- Modify: `src/components/TitleDetailDrawer.tsx` (minimal — destructure fix only)

**Interfaces:**
- Consumes: `CastMember`, `CrewMember` from `src/store/mockData.ts` (Task 2)
- Produces:
  - `SearchResult` extended with `cast?: CastMember[]`, `crew?: CrewMember[]`, `studios?: string[]`
  - `RawTmdbEpisode` extended with `crew?: Array<{ id: number; name: string; job: string; department?: string; profile_path?: string }>`
  - New export: `SeasonFetchResult { cast: CastMember[]; episodes: RawTmdbEpisode[] }`
  - `fetchSeasonDetails(tmdbId: number, seasonNumber: number): Promise<SeasonFetchResult>` — return type changed; all callers fixed minimally here

- [ ] **Step 1: Update `media.ts` import**

Replace the existing import line in `src/lib/media.ts`:

```typescript
import type { MediaType } from '../store/mockData'
```

with:

```typescript
import type { CastMember, CrewMember, MediaType } from '../store/mockData'
```

- [ ] **Step 2: Extend `RawTmdbEpisode` interface**

Replace the existing `RawTmdbEpisode` interface in `src/lib/media.ts`:

```typescript
export interface RawTmdbEpisode {
  episode_number: number
  name: string
  overview?: string
  air_date?: string
  runtime?: number
  still_path?: string
  crew?: Array<{
    id: number
    name: string
    job: string
    department?: string
    profile_path?: string
  }>
}
```

- [ ] **Step 3: Add `SeasonFetchResult` interface and extend `SearchResult`**

Add `SeasonFetchResult` after the `MediaDetails` interface:

```typescript
export interface SeasonFetchResult {
  cast: CastMember[]
  episodes: RawTmdbEpisode[]
}
```

In the `SearchResult` interface, add after `metacriticScore?: number`:

```typescript
  cast?: CastMember[]
  crew?: CrewMember[]
  studios?: string[]
```

- [ ] **Step 4: Update `fetchMediaDetails` to extract cast, crew, and studios**

In `src/lib/media.ts`, replace everything from `const director = ...` through the end of the `result` object construction (before `return { result, tmdbSeasons: data.seasons ?? [] }`):

```typescript
  const TITLE_CREW_JOBS = new Set([
    'Director', 'Screenplay', 'Writer', 'Producer',
    'Director of Photography', 'Original Music Composer',
  ])
  const TMDB_IMG_W185 = `${TMDB_IMG}/w185`

  const cast: CastMember[] = (data.credits?.cast ?? [])
    .slice(0, 10)
    .map((c: any) => ({
      tmdbPersonId: c.id,
      name: c.name,
      character: c.character || undefined,
      profileUrl: c.profile_path ? `${TMDB_IMG_W185}${c.profile_path}` : undefined,
      order: c.order ?? 0,
    }))

  const seenCrewKey = new Set<string>()
  const crew: CrewMember[] = []
  for (const c of (data.credits?.crew ?? [])) {
    if (!TITLE_CREW_JOBS.has(c.job)) continue
    const key = `${c.id}:${c.job}`
    if (seenCrewKey.has(key)) continue
    seenCrewKey.add(key)
    crew.push({
      tmdbPersonId: c.id,
      name: c.name,
      job: c.job,
      department: c.department || undefined,
      profileUrl: c.profile_path ? `${TMDB_IMG_W185}${c.profile_path}` : undefined,
    })
  }

  if (base.type === 'tv') {
    for (const creator of (data.created_by ?? [])) {
      crew.push({
        tmdbPersonId: creator.id,
        name: creator.name,
        job: 'Creator',
        profileUrl: creator.profile_path ? `${TMDB_IMG_W185}${creator.profile_path}` : undefined,
      })
    }
  }

  const studios: string[] = (data.production_companies ?? []).map((c: any) => c.name as string)

  const director = crew.find((c) => c.job === 'Director')?.name
  const date = data.release_date || data.first_air_date

  const result: SearchResult = {
    tmdbId: data.id,
    type: base.type,
    title: data.title || data.name,
    year: date ? new Date(date).getFullYear() : base.year,
    posterUrl: data.poster_path ? `${TMDB_IMG}/w500${data.poster_path}` : base.posterUrl,
    backdropUrl: data.backdrop_path ? `${TMDB_IMG}/w780${data.backdrop_path}` : base.backdropUrl,
    director,
    genres: data.genres?.map((g: any) => g.name) ?? [],
    synopsis: data.overview,
    runtime: data.runtime,
    network: data.networks?.[0]?.name,
    seasonCount: data.number_of_seasons,
    imdbRating,
    rtScore,
    metacriticScore,
    cast,
    crew,
    studios,
  }
```

- [ ] **Step 5: Replace `fetchSeasonDetails` with new return type**

Replace the entire `fetchSeasonDetails` function in `src/lib/media.ts`:

```typescript
export async function fetchSeasonDetails(tmdbId: number, seasonNumber: number): Promise<SeasonFetchResult> {
  if (!(isSupabaseConfigured && supabase)) return { episodes: [], cast: [] }

  try {
    const { data, error } = await supabase.functions.invoke(
      `media-proxy?action=season&id=${tmdbId}&season=${seasonNumber}`
    )
    if (error) throw error

    const episodes = (data?.episodes ?? []) as RawTmdbEpisode[]

    const cast: CastMember[] = (data?.credits?.cast ?? [])
      .slice(0, 10)
      .map((c: any) => ({
        tmdbPersonId: c.id,
        name: c.name,
        character: c.character || undefined,
        profileUrl: c.profile_path ? `https://image.tmdb.org/t/p/w185${c.profile_path}` : undefined,
        order: c.order ?? 0,
      }))

    return { episodes, cast }
  } catch (e) {
    console.error(`Error fetching season ${seasonNumber} details for tmdbId ${tmdbId}:`, e)
    return { episodes: [], cast: [] }
  }
}
```

- [ ] **Step 6: Fix `fetchSeasonDetails` callers (minimal destructure — no logic change yet)**

**In `src/components/AddTitleWorkflow.tsx`**, find the `fetchSeasonDetails` call inside `selectResult` (around line 251):

```typescript
// Before:
.map((s) => fetchSeasonDetails(detailed.tmdbId, s.season_number).then((eps) => ({ seasonNumber: s.season_number, eps })))
// ...
episodesBySeason.set(r.value.seasonNumber, r.value.eps)

// After:
.map((s) => fetchSeasonDetails(detailed.tmdbId, s.season_number).then(({ episodes }) => ({ seasonNumber: s.season_number, episodes })))
// ...
episodesBySeason.set(r.value.seasonNumber, r.value.episodes)
```

**In `src/components/RefreshMetadataModal.tsx`**, find the `fetchSeasonDetails` call inside `applyFrom` (around line 112):

```typescript
// Before:
title.seasons.map((s) =>
  fetchSeasonDetails(result.tmdbId, s.seasonNumber).then((eps) => ({ season: s, tmdbEps: eps }))
)
// ...
const { tmdbEps } = match.value

// After:
title.seasons.map((s) =>
  fetchSeasonDetails(result.tmdbId, s.seasonNumber).then(({ episodes }) => ({ season: s, tmdbEps: episodes }))
)
// ...
const { tmdbEps } = match.value   // variable name unchanged — still works
```

**In `src/components/TitleDetailDrawer.tsx`**, find the `fetchSeasonDetails` call inside the backfill async function (around line 625):

```typescript
// Before:
const tmdbEps = await fetchSeasonDetails(snapshotTitle.tmdbId, season.seasonNumber)

// After:
const { episodes: tmdbEps } = await fetchSeasonDetails(snapshotTitle.tmdbId, season.seasonNumber)
```

- [ ] **Step 7: Verify build and lint**

```
npm run build && npm run lint
```
Expected: both succeed. The callers now destructure correctly but don't yet use the new `cast` and `crew` fields — that comes in Tasks 6–8.

- [ ] **Step 8: Commit**

```
git add src/lib/media.ts src/components/AddTitleWorkflow.tsx src/components/RefreshMetadataModal.tsx src/components/TitleDetailDrawer.tsx
git commit -m "feat(media): extract cast/crew/studios from TMDB; SeasonFetchResult type; fix callers"
```

---

### Task 5: `db.ts` — Queries, Mapping, and Write Helpers

**Files:**
- Modify: `src/lib/db.ts`

**Interfaces:**
- Consumes: `CastMember`, `CrewMember`, `EpisodeCrew` from `src/store/mockData.ts` (Task 2); new DB tables from Task 1
- Produces:
  - `fetchUserLibrary` and `fetchSharedLibrary` now return `Title` objects with `cast`, `crew`, `studios` populated; `Season` with `cast`; `Episode` with `director`, `writers`, `crew`
  - `insertTitleToDb` writes cast/crew/studios rows
  - `updateTitleInDb` handles `studios` in `META_COLUMNS`; delete-reinsert cast/crew when present in patch
  - New exports: `upsertTitleCastInDb`, `upsertTitleCrewInDb`, `upsertSeasonCastInDb`, `upsertEpisodeCrewInDb`

- [ ] **Step 1: Add new imports to `db.ts`**

At the top of `src/lib/db.ts`, replace:

```typescript
import type { Title, WatchStatus, MediaType } from '../store/mockData'
```

with:

```typescript
import type { CastMember, CrewMember, EpisodeCrew, Title, WatchStatus, MediaType } from '../store/mockData'
```

- [ ] **Step 2: Update `mapDbTitleToLocal` to map new fields**

Replace the `mapDbTitleToLocal` function with:

```typescript
function mapDbTitleToLocal(row: any): Title {
  const episodesBySeason: Record<number, any[]> = {}
  for (const ep of (row.episodes || [])) {
    if (!episodesBySeason[ep.season_number]) episodesBySeason[ep.season_number] = []
    episodesBySeason[ep.season_number].push(ep)
  }

  function mapCastRow(c: any): CastMember {
    return {
      tmdbPersonId: c.tmdb_person_id,
      name: c.name,
      character: c.character_name || undefined,
      profileUrl: c.profile_url || undefined,
      order: c.cast_order,
    }
  }

  return {
    id: row.id,
    tmdbId: row.tmdb_id,
    type: row.type as MediaType,
    title: row.title,
    year: row.year,
    director: row.director || undefined,
    genres: row.genres || [],
    posterUrl: row.poster_url || undefined,
    backdropUrl: row.backdrop_url || undefined,
    synopsis: row.synopsis || undefined,
    runtime: row.runtime || undefined,
    network: row.network || undefined,
    status: row.status as WatchStatus,
    rating: row.rating ? parseFloat(row.rating) : undefined,
    notes: row.notes || undefined,
    tags: row.tags || [],
    addedAt: row.added_at
      ? new Date(row.added_at).toISOString().slice(0, 10)
      : new Date().toISOString().slice(0, 10),
    imdbRating: row.imdb_rating ? parseFloat(row.imdb_rating) : undefined,
    rtScore: row.rt_score || undefined,
    metacriticScore: row.metacritic_score || undefined,
    studios: row.studios || [],
    cast: (row.title_cast || [])
      .sort((a: any, b: any) => a.cast_order - b.cast_order)
      .map(mapCastRow),
    crew: (row.title_crew || []).map((c: any): CrewMember => ({
      tmdbPersonId: c.tmdb_person_id,
      name: c.name,
      job: c.job,
      department: c.department || undefined,
      profileUrl: c.profile_url || undefined,
    })),
    seasons: (row.seasons || [])
      .map((s: any) => {
        const episodes = (episodesBySeason[s.season_number] || [])
          .sort((a: any, b: any) => a.episode_number - b.episode_number)
          .map((ep: any) => {
            const epCrewRaw: any[] = ep.episode_crew || []
            const epCrew: EpisodeCrew[] = epCrewRaw.map((c) => ({
              tmdbPersonId: c.tmdb_person_id,
              name: c.name,
              job: c.job,
            }))
            return {
              id: ep.id,
              episodeNumber: ep.episode_number,
              episodeName: ep.episode_name || undefined,
              airDate: ep.air_date || undefined,
              runtime: ep.runtime || undefined,
              synopsis: ep.synopsis || undefined,
              stillUrl: ep.still_url || undefined,
              director: epCrew.find((c) => c.job === 'Director')?.name,
              writers: epCrew
                .filter((c) => ['Writer', 'Teleplay', 'Story'].includes(c.job))
                .map((c) => c.name),
              crew: epCrew.length > 0 ? epCrew : undefined,
              watchEvents: (ep.episode_watch_events || [])
                .sort(
                  (a: any, b: any) =>
                    new Date(a.watched_at).getTime() - new Date(b.watched_at).getTime()
                )
                .map((we: any) => ({
                  id: we.id,
                  watchedAt: we.watched_at,
                  notes: we.notes || undefined,
                })),
              ratings: (ep.episode_ratings || [])
                .sort(
                  (a: any, b: any) =>
                    new Date(a.rated_at).getTime() - new Date(b.rated_at).getTime()
                )
                .map((er: any) => ({
                  id: er.id,
                  rating: parseFloat(er.rating),
                  ratedAt: er.rated_at,
                })),
              reviews: (ep.episode_reviews || [])
                .sort(
                  (a: any, b: any) =>
                    new Date(a.reviewed_at).getTime() - new Date(b.reviewed_at).getTime()
                )
                .map((rv: any) => ({
                  id: rv.id,
                  reviewText: rv.review_text,
                  reviewedAt: rv.reviewed_at,
                })),
            }
          })
        return {
          id: s.id,
          seasonNumber: s.season_number,
          episodeCount: s.episode_count,
          episodesWatched: s.episodes_watched,
          airYear: s.air_year || undefined,
          cast: (s.season_cast || [])
            .sort((a: any, b: any) => a.cast_order - b.cast_order)
            .map(mapCastRow),
          episodes,
        }
      })
      .sort((a: any, b: any) => a.seasonNumber - b.seasonNumber),
    viewings: (row.viewings || [])
      .map((v: any) => ({
        id: v.id,
        titleId: v.title_id,
        date: v.viewed_at,
        rating: v.rating ? parseFloat(v.rating) : undefined,
        notes: v.notes || undefined,
      }))
      .sort((a: any, b: any) => new Date(b.date).getTime() - new Date(a.date).getTime()),
  }
}
```

- [ ] **Step 3: Update `fetchUserLibrary` and `fetchSharedLibrary` select queries**

Both functions have an identical `.select(...)` call. Replace it in **both** with:

```typescript
.select(`
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

- [ ] **Step 4: Update `insertTitleToDb` to include studios and write cast/crew rows**

In `insertTitleToDb`, add `studios: title.studios ?? []` to the initial `titles` insert object (alongside the other columns like `imdb_rating`, `rt_score`):

```typescript
    studios: title.studios ?? [],
```

Then, after the existing viewings insert block (at the end of `insertTitleToDb`), add:

```typescript
  // 4. Insert title-level cast and crew (fire-and-forget)
  if (title.cast && title.cast.length > 0) {
    supabase.from('title_cast').insert(
      title.cast.map((c) => ({
        user_id: userId,
        title_id: title.id,
        tmdb_person_id: c.tmdbPersonId,
        name: c.name,
        character_name: c.character ?? null,
        profile_url: c.profileUrl ?? null,
        cast_order: c.order,
      }))
    ).catch((e) => console.error('Failed to insert title cast:', e))
  }

  if (title.crew && title.crew.length > 0) {
    supabase.from('title_crew').insert(
      title.crew.map((c) => ({
        user_id: userId,
        title_id: title.id,
        tmdb_person_id: c.tmdbPersonId,
        name: c.name,
        job: c.job,
        department: c.department ?? null,
        profile_url: c.profileUrl ?? null,
      }))
    ).catch((e) => console.error('Failed to insert title crew:', e))
  }

  // 5. Insert season cast and episode crew
  if (title.type === 'tv' && title.seasons) {
    for (const season of title.seasons) {
      if (season.cast && season.cast.length > 0) {
        supabase.from('season_cast').insert(
          season.cast.map((c) => ({
            user_id: userId,
            title_id: title.id,
            season_id: season.id,
            tmdb_person_id: c.tmdbPersonId,
            name: c.name,
            character_name: c.character ?? null,
            profile_url: c.profileUrl ?? null,
            cast_order: c.order,
          }))
        ).catch((e) => console.error('Failed to insert season cast:', e))
      }

      for (const ep of (season.episodes ?? [])) {
        if (ep.crew && ep.crew.length > 0) {
          supabase.from('episode_crew').insert(
            ep.crew.map((c) => ({
              user_id: userId,
              title_id: title.id,
              episode_id: ep.id,
              tmdb_person_id: c.tmdbPersonId,
              name: c.name,
              job: c.job,
            }))
          ).catch((e) => console.error('Failed to insert episode crew:', e))
        }
      }
    }
  }
```

- [ ] **Step 5: Update `META_COLUMNS` and `updateTitleInDb` for cast/crew/studios**

In `META_COLUMNS`, add:

```typescript
  ['studios', 'studios'],
```

At the end of `updateTitleInDb`, after the existing `patch.viewings` block, add:

```typescript
  // Cast refresh: delete existing rows, re-insert new set (stale entries are removed)
  if ('cast' in patch && patch.cast !== undefined && supabase) {
    await supabase.from('title_cast').delete().eq('title_id', titleId).eq('user_id', userId)
    if (patch.cast.length > 0) {
      const { error } = await supabase.from('title_cast').insert(
        patch.cast.map((c) => ({
          user_id: userId,
          title_id: titleId,
          tmdb_person_id: c.tmdbPersonId,
          name: c.name,
          character_name: c.character ?? null,
          profile_url: c.profileUrl ?? null,
          cast_order: c.order,
        }))
      )
      if (error) console.error('Error re-inserting title cast:', error)
    }
  }

  // Crew refresh: same delete-reinsert pattern
  if ('crew' in patch && patch.crew !== undefined && supabase) {
    await supabase.from('title_crew').delete().eq('title_id', titleId).eq('user_id', userId)
    if (patch.crew.length > 0) {
      const { error } = await supabase.from('title_crew').insert(
        patch.crew.map((c) => ({
          user_id: userId,
          title_id: titleId,
          tmdb_person_id: c.tmdbPersonId,
          name: c.name,
          job: c.job,
          department: c.department ?? null,
          profile_url: c.profileUrl ?? null,
        }))
      )
      if (error) console.error('Error re-inserting title crew:', error)
    }
  }
```

- [ ] **Step 6: Add four new upsert helper functions**

Append to the end of `src/lib/db.ts`:

```typescript
export async function upsertTitleCastInDb(userId: string, titleId: string, cast: CastMember[]): Promise<void> {
  if (!supabase || cast.length === 0) return
  const { error } = await supabase.from('title_cast').upsert(
    cast.map((c) => ({
      user_id: userId,
      title_id: titleId,
      tmdb_person_id: c.tmdbPersonId,
      name: c.name,
      character_name: c.character ?? null,
      profile_url: c.profileUrl ?? null,
      cast_order: c.order,
    })),
    { onConflict: 'title_id,tmdb_person_id' }
  )
  if (error) console.error('Error upserting title cast:', error)
}

export async function upsertTitleCrewInDb(userId: string, titleId: string, crew: CrewMember[]): Promise<void> {
  if (!supabase || crew.length === 0) return
  const { error } = await supabase.from('title_crew').upsert(
    crew.map((c) => ({
      user_id: userId,
      title_id: titleId,
      tmdb_person_id: c.tmdbPersonId,
      name: c.name,
      job: c.job,
      department: c.department ?? null,
      profile_url: c.profileUrl ?? null,
    })),
    { onConflict: 'title_id,tmdb_person_id,job' }
  )
  if (error) console.error('Error upserting title crew:', error)
}

export async function upsertSeasonCastInDb(
  userId: string,
  titleId: string,
  seasonId: string,
  cast: CastMember[]
): Promise<void> {
  if (!supabase || cast.length === 0) return
  const { error } = await supabase.from('season_cast').upsert(
    cast.map((c) => ({
      user_id: userId,
      title_id: titleId,
      season_id: seasonId,
      tmdb_person_id: c.tmdbPersonId,
      name: c.name,
      character_name: c.character ?? null,
      profile_url: c.profileUrl ?? null,
      cast_order: c.order,
    })),
    { onConflict: 'season_id,tmdb_person_id' }
  )
  if (error) console.error('Error upserting season cast:', error)
}

export async function upsertEpisodeCrewInDb(
  userId: string,
  titleId: string,
  episodeId: string,
  crew: EpisodeCrew[]
): Promise<void> {
  if (!supabase || crew.length === 0) return
  const { error } = await supabase.from('episode_crew').upsert(
    crew.map((c) => ({
      user_id: userId,
      title_id: titleId,
      episode_id: episodeId,
      tmdb_person_id: c.tmdbPersonId,
      name: c.name,
      job: c.job,
    })),
    { onConflict: 'episode_id,tmdb_person_id,job' }
  )
  if (error) console.error('Error upserting episode crew:', error)
}
```

- [ ] **Step 7: Verify build and lint**

```
npm run build && npm run lint
```
Expected: both succeed.

- [ ] **Step 8: Commit**

```
git add src/lib/db.ts
git commit -m "feat(db): join cast/crew tables in fetch queries; map new fields; add upsert helpers"
```

---

### Task 6: `AddTitleWorkflow.tsx` — Full Cast/Crew Integration

**Files:**
- Modify: `src/components/AddTitleWorkflow.tsx`

**Interfaces:**
- Consumes: `CastMember`, `EpisodeCrew` from `src/store/mockData.ts` (Task 2); `SeasonFetchResult` from `src/lib/media.ts` (Task 4); `Season.cast`, `Episode.crew/director/writers` (Task 2)
- Produces: `newTitle` passed to `addTitle` includes `cast`, `crew`, `studios`; each `Season` in `newTitle.seasons` includes `cast`; each `Episode` includes `director`, `writers`, `crew`

- [ ] **Step 1: Update imports in `AddTitleWorkflow.tsx`**

Add `CastMember`, `EpisodeCrew` to the mockData import:

```typescript
import type { Title, WatchStatus, Season, CastMember, EpisodeCrew } from 'src/store/mockData'
```

Update the media import to include `SeasonFetchResult`:

```typescript
import { searchMedia, fetchMediaDetails, fetchSeasonDetails, type SearchResult, type RawTmdbSeason, type RawTmdbEpisode, type SeasonFetchResult } from 'src/lib/media'
```

(The `SeasonFetchResult` import may produce an unused-variable lint warning if not referenced — it's used implicitly via destructuring, so it may not be needed. Only add it if TypeScript requires the explicit type reference.)

- [ ] **Step 2: Update `buildSeasons` to populate episode crew and season cast**

Replace the `buildSeasons` function signature and body:

```typescript
function buildSeasons(
  result: SearchResult,
  tmdbSeasons: RawTmdbSeason[],
  episodesBySeason?: Map<number, RawTmdbEpisode[]>,
  seasonCastBySeason?: Map<number, CastMember[]>
): Season[] {
  if (result.type !== 'tv' || !result.seasonCount) return []
  return Array.from({ length: result.seasonCount }, (_, i) => {
    const seasonNum = i + 1
    const tmdbSeason = tmdbSeasons.find((s) => s.season_number === seasonNum)
    const epCount = tmdbSeason?.episode_count || 10
    const tmdbEpisodes = episodesBySeason?.get(seasonNum) ?? []
    const EP_CREW_JOBS = new Set(['Director', 'Writer', 'Teleplay', 'Story'])
    return {
      id: crypto.randomUUID(),
      seasonNumber: seasonNum,
      episodeCount: epCount,
      episodesWatched: 0,
      cast: seasonCastBySeason?.get(seasonNum),
      episodes: Array.from({ length: epCount }, (_, j) => {
        const epNum = j + 1
        const tmdbEp = tmdbEpisodes.find((e) => e.episode_number === epNum)
        const epCrew: EpisodeCrew[] = (tmdbEp?.crew ?? [])
          .filter((c) => EP_CREW_JOBS.has(c.job))
          .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
        return {
          id: crypto.randomUUID(),
          episodeNumber: epNum,
          episodeName: tmdbEp?.name || undefined,
          airDate: tmdbEp?.air_date || undefined,
          runtime: tmdbEp?.runtime || undefined,
          synopsis: tmdbEp?.overview || undefined,
          stillUrl: tmdbEp?.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : undefined,
          director: epCrew.find((c) => c.job === 'Director')?.name,
          writers: epCrew.filter((c) => ['Writer', 'Teleplay', 'Story'].includes(c.job)).map((c) => c.name),
          crew: epCrew.length > 0 ? epCrew : undefined,
          watchEvents: [],
          ratings: [],
          reviews: [],
        }
      }),
    }
  })
}
```

- [ ] **Step 3: Update `selectResult` to collect season cast**

Replace the `episodesBySeason` block inside `selectResult`:

```typescript
      let episodesBySeason: Map<number, RawTmdbEpisode[]> | undefined
      const seasonCastBySeason = new Map<number, CastMember[]>()
      if (detailed.type === 'tv' && detailed.tmdbId && tmdbSeasons.length > 0) {
        const settled = await Promise.allSettled(
          tmdbSeasons
            .filter((s) => s.season_number > 0)
            .map((s) =>
              fetchSeasonDetails(detailed.tmdbId, s.season_number).then(({ episodes, cast }) => ({
                seasonNumber: s.season_number,
                episodes,
                cast,
              }))
            )
        )
        episodesBySeason = new Map()
        for (const r of settled) {
          if (r.status === 'fulfilled') {
            episodesBySeason.set(r.value.seasonNumber, r.value.episodes)
            if (r.value.cast.length > 0) {
              seasonCastBySeason.set(r.value.seasonNumber, r.value.cast)
            }
          }
        }
      }
      setSelected(detailed)
      setLog({ ...DEFAULT_LOG, seasons: buildSeasons(detailed, tmdbSeasons, episodesBySeason, seasonCastBySeason) })
```

- [ ] **Step 4: Update `handleSave` to include cast, crew, and studios**

In `handleSave`, extend the `newTitle` object to include:

```typescript
      cast: selected.cast,
      crew: selected.crew,
      studios: selected.studios,
```

Add these three lines to `newTitle` alongside the other fields from `selected` (after `metacriticScore`).

- [ ] **Step 5: Verify build and lint**

```
npm run build && npm run lint
```
Expected: both succeed.

- [ ] **Step 6: Commit**

```
git add src/components/AddTitleWorkflow.tsx
git commit -m "feat(add-title): populate episode crew and season cast; include cast/crew/studios in new title"
```

---

### Task 7: `RefreshMetadataModal.tsx` — Full Cast/Crew Integration

**Files:**
- Modify: `src/components/RefreshMetadataModal.tsx`

**Interfaces:**
- Consumes: `SeasonFetchResult` from `src/lib/media.ts` (Task 4); `upsertSeasonCastInDb`, `upsertEpisodeCrewInDb` from `src/lib/db.ts` (Task 5); `CastMember`, `EpisodeCrew` from `src/store/mockData.ts` (Task 2)
- Produces: `patch` built in `applyFrom` includes `cast`, `crew`, `studios`; season cast and episode crew are persisted via the new upsert helpers

- [ ] **Step 1: Update imports**

In `src/components/RefreshMetadataModal.tsx`, update the db import:

```typescript
import { upsertEpisodeMetadataInDb, upsertSeasonCastInDb, upsertEpisodeCrewInDb } from 'src/lib/db'
```

Add `EpisodeCrew` to the mockData import:

```typescript
import type { Title, Episode, EpisodeCrew } from 'src/store/mockData'
```

- [ ] **Step 2: Update `toSearchResult` to include cast, crew, studios**

Replace the `toSearchResult` function:

```typescript
function toSearchResult(t: Title): SearchResult {
  return {
    tmdbId: t.tmdbId,
    type: t.type,
    title: t.title,
    year: t.year,
    posterUrl: t.posterUrl,
    backdropUrl: t.backdropUrl,
    director: t.director,
    genres: t.genres,
    synopsis: t.synopsis,
    runtime: t.runtime,
    network: t.network,
    imdbRating: t.imdbRating,
    rtScore: t.rtScore,
    metacriticScore: t.metacriticScore,
    cast: t.cast,
    crew: t.crew,
    studios: t.studios,
  }
}
```

- [ ] **Step 3: Update `applyFrom` — patch + season cast + episode crew**

In `applyFrom`, add `cast`, `crew`, `studios` to the `patch`:

```typescript
      const patch: Partial<Title> = {
        tmdbId: result.tmdbId,
        title: result.title,
        year: result.year,
        director: result.director,
        genres: result.genres,
        posterUrl: result.posterUrl,
        backdropUrl: result.backdropUrl,
        synopsis: result.synopsis,
        runtime: result.runtime,
        network: result.network,
        imdbRating: result.imdbRating,
        rtScore: result.rtScore,
        metacriticScore: result.metacriticScore,
        cast: result.cast,
        crew: result.crew,
        studios: result.studios,
      }
```

Update the `fetchSeasonDetails` call inside `applyFrom` to use full destructuring and collect season cast + episode crew. Replace the `settled` / `updatedSeasons` block:

```typescript
        const settled = await Promise.allSettled(
          title.seasons.map((s) =>
            fetchSeasonDetails(result.tmdbId, s.seasonNumber).then(({ episodes, cast }) => ({
              season: s,
              tmdbEps: episodes,
              seasonCast: cast,
            }))
          )
        )

        const allEpisodeUpdates: Parameters<typeof upsertEpisodeMetadataInDb>[2] = []
        const allEpisodeCrew: Array<{ episodeId: string; crew: EpisodeCrew[] }> = []
        const allSeasonCast: Array<{ seasonId: string; cast: typeof result.cast }> = []
        const EP_CREW_JOBS = new Set(['Director', 'Writer', 'Teleplay', 'Story'])

        const updatedSeasons = title.seasons.map((s) => {
          const match = settled.find(
            (r) => r.status === 'fulfilled' && r.value.season.seasonNumber === s.seasonNumber
          )
          if (!match || match.status !== 'fulfilled' || match.value.tmdbEps.length === 0) return s

          const { tmdbEps, seasonCast } = match.value
          const existingEpisodes = s.episodes || []
          let updatedEpisodes: Episode[]

          if (existingEpisodes.length === 0) {
            updatedEpisodes = tmdbEps.map((tmdbEp) => {
              const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
                .filter((c) => EP_CREW_JOBS.has(c.job))
                .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
              return {
                id: crypto.randomUUID(),
                episodeNumber: tmdbEp.episode_number,
                episodeName: tmdbEp.name || undefined,
                airDate: tmdbEp.air_date || undefined,
                runtime: tmdbEp.runtime || undefined,
                synopsis: tmdbEp.overview || undefined,
                stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : undefined,
                director: epCrew.find((c) => c.job === 'Director')?.name,
                writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
                crew: epCrew.length > 0 ? epCrew : undefined,
                watchEvents: [],
                ratings: [],
                reviews: [],
              }
            })
          } else {
            updatedEpisodes = existingEpisodes.map((ep) => {
              const tmdbEp = tmdbEps.find((e) => e.episode_number === ep.episodeNumber)
              if (!tmdbEp) return ep
              const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
                .filter((c) => EP_CREW_JOBS.has(c.job))
                .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
              return {
                ...ep,
                episodeName: tmdbEp.name || ep.episodeName,
                airDate: tmdbEp.air_date || ep.airDate,
                runtime: tmdbEp.runtime || ep.runtime,
                synopsis: tmdbEp.overview || ep.synopsis,
                stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : ep.stillUrl,
                director: epCrew.find((c) => c.job === 'Director')?.name ?? ep.director,
                writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
                crew: epCrew.length > 0 ? epCrew : ep.crew,
              }
            })
          }

          for (const ep of updatedEpisodes) {
            allEpisodeUpdates.push({
              id: ep.id,
              seasonNumber: s.seasonNumber,
              episodeNumber: ep.episodeNumber,
              episodeName: ep.episodeName,
              airDate: ep.airDate,
              runtime: ep.runtime,
              synopsis: ep.synopsis,
              stillUrl: ep.stillUrl,
            })
            if (ep.crew && ep.crew.length > 0) {
              allEpisodeCrew.push({ episodeId: ep.id, crew: ep.crew })
            }
          }

          if (seasonCast && seasonCast.length > 0) {
            allSeasonCast.push({ seasonId: s.id, cast: seasonCast })
          }

          return {
            ...s,
            episodes: updatedEpisodes,
            episodeCount: updatedEpisodes.length,
            cast: seasonCast && seasonCast.length > 0 ? seasonCast : s.cast,
          }
        })

        patch.seasons = updatedSeasons

        if (user) {
          if (allEpisodeUpdates.length > 0) {
            upsertEpisodeMetadataInDb(user.id, title.id, allEpisodeUpdates).catch((e) =>
              console.error('Episode metadata refresh DB write failed:', e)
            )
          }
          for (const { seasonId, cast } of allSeasonCast) {
            if (cast) {
              upsertSeasonCastInDb(user.id, title.id, seasonId, cast).catch((e) =>
                console.error('Season cast refresh DB write failed:', e)
              )
            }
          }
          for (const { episodeId, crew } of allEpisodeCrew) {
            upsertEpisodeCrewInDb(user.id, title.id, episodeId, crew).catch((e) =>
              console.error('Episode crew refresh DB write failed:', e)
            )
          }
        }
```

- [ ] **Step 4: Verify build and lint**

```
npm run build && npm run lint
```
Expected: both succeed.

- [ ] **Step 5: Commit**

```
git add src/components/RefreshMetadataModal.tsx
git commit -m "feat(refresh): include cast/crew/studios in metadata refresh; write season cast and episode crew"
```

---

### Task 8: `TitleDetailDrawer.tsx` — Backfill Extension + Cast/Crew UI

**Files:**
- Modify: `src/components/TitleDetailDrawer.tsx`

**Interfaces:**
- Consumes: `CastMember`, `CrewMember`, `EpisodeCrew` from `src/store/mockData.ts` (Task 2); `upsertSeasonCastInDb`, `upsertEpisodeCrewInDb` from `src/lib/db.ts` (Task 5); `SeasonFetchResult` from `src/lib/media.ts` (Task 4)
- Produces: Updated detail drawer with a new "Cast & Crew" section between Synopsis and Critical Reception; episode panels showing director/writers; TV season tabs showing season cast

- [ ] **Step 1: Update imports in `TitleDetailDrawer.tsx`**

Add to the existing db import:

```typescript
import { upsertEpisodeMetadataInDb, upsertSeasonCastInDb, upsertEpisodeCrewInDb } from 'src/lib/db'
```

Add `EpisodeCrew` to the mockData import:

```typescript
import type { Viewing, WatchStatus, Season, Episode, EpisodeCrew } from 'src/store/mockData'
```

- [ ] **Step 2: Extend the backfill effect to capture season cast and episode crew**

In `TitleDetailDrawer.tsx`, update the `seasonsNeedingBackfill` filter to also include seasons without cast data:

```typescript
    const seasonsNeedingBackfill = title.seasons.filter((s) =>
      (s.episodeCount > 0 && (!s.episodes || s.episodes.length === 0)) ||
      (s.episodes && s.episodes.length > 0 && s.episodes.every((ep) => !ep.episodeName)) ||
      (!s.cast || s.cast.length === 0)
    )
```

Then update the `backfill` async function to destructure `SeasonFetchResult` and persist season cast + episode crew. Replace the `Promise.allSettled` call and the results loop:

```typescript
      const settled = await Promise.allSettled(
        seasonsNeedingBackfill.map(async (season) => {
          const { episodes: tmdbEps, cast: seasonCast } = await fetchSeasonDetails(snapshotTitle.tmdbId, season.seasonNumber)
          return { season, tmdbEps, seasonCast }
        })
      )

      let updatedSeasons = [...snapshotTitle.seasons!]
      const allUpdatedEpisodes: Parameters<typeof upsertEpisodeMetadataInDb>[2] = []
      const allEpisodeCrew: Array<{ episodeId: string; crew: EpisodeCrew[] }> = []
      const allSeasonCast: Array<{ seasonId: string; cast: typeof snapshotTitle.cast }> = []
      const EP_CREW_JOBS = new Set(['Director', 'Writer', 'Teleplay', 'Story'])

      for (const result of settled) {
        if (result.status !== 'fulfilled' || result.value.tmdbEps.length === 0) continue
        const { season, tmdbEps, seasonCast } = result.value

        const existingEpisodes = season.episodes || []
        let updatedEpisodes: Episode[]

        if (existingEpisodes.length === 0) {
          updatedEpisodes = tmdbEps.map((tmdbEp) => {
            const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
              .filter((c) => EP_CREW_JOBS.has(c.job))
              .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
            return {
              id: crypto.randomUUID(),
              episodeNumber: tmdbEp.episode_number,
              episodeName: tmdbEp.name || undefined,
              airDate: tmdbEp.air_date || undefined,
              runtime: tmdbEp.runtime || undefined,
              synopsis: tmdbEp.overview || undefined,
              stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : undefined,
              director: epCrew.find((c) => c.job === 'Director')?.name,
              writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
              crew: epCrew.length > 0 ? epCrew : undefined,
              watchEvents: [],
              ratings: [],
              reviews: [],
            }
          })
          updatedSeasons = updatedSeasons.map((s) =>
            s.seasonNumber === season.seasonNumber
              ? { ...s, episodes: updatedEpisodes, episodeCount: updatedEpisodes.length, cast: seasonCast.length > 0 ? seasonCast : s.cast }
              : s
          )
        } else {
          updatedEpisodes = existingEpisodes.map((ep) => {
            const tmdbEp = tmdbEps.find((e) => e.episode_number === ep.episodeNumber)
            if (!tmdbEp) return ep
            const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
              .filter((c) => EP_CREW_JOBS.has(c.job))
              .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
            return {
              ...ep,
              episodeName: tmdbEp.name || ep.episodeName,
              airDate: tmdbEp.air_date || ep.airDate,
              runtime: tmdbEp.runtime || ep.runtime,
              synopsis: tmdbEp.overview || ep.synopsis,
              stillUrl: tmdbEp.still_path ? `${TMDB_STILL_BASE}${tmdbEp.still_path}` : ep.stillUrl,
              director: epCrew.find((c) => c.job === 'Director')?.name ?? ep.director,
              writers: epCrew.filter((c) => c.job !== 'Director').map((c) => c.name),
              crew: epCrew.length > 0 ? epCrew : ep.crew,
            }
          })
          updatedSeasons = updatedSeasons.map((s) =>
            s.seasonNumber === season.seasonNumber
              ? { ...s, episodes: updatedEpisodes, cast: seasonCast.length > 0 ? seasonCast : s.cast }
              : s
          )
        }

        for (const ep of updatedEpisodes) {
          allUpdatedEpisodes.push({
            id: ep.id,
            seasonNumber: season.seasonNumber,
            episodeNumber: ep.episodeNumber,
            episodeName: ep.episodeName,
            airDate: ep.airDate,
            runtime: ep.runtime,
            synopsis: ep.synopsis,
            stillUrl: ep.stillUrl,
          })
          if (ep.crew && ep.crew.length > 0) {
            allEpisodeCrew.push({ episodeId: ep.id, crew: ep.crew })
          }
        }

        if (seasonCast.length > 0) {
          allSeasonCast.push({ seasonId: season.id, cast: seasonCast })
        }
      }

      if (allUpdatedEpisodes.length > 0 || allSeasonCast.length > 0) {
        updateTitle(snapshotTitle.id, { seasons: updatedSeasons })
        if (snapshotUser) {
          if (allUpdatedEpisodes.length > 0) {
            upsertEpisodeMetadataInDb(snapshotUser.id, snapshotTitle.id, allUpdatedEpisodes).catch((e) =>
              console.error('Episode metadata backfill DB write failed:', e)
            )
          }
          for (const { seasonId, cast } of allSeasonCast) {
            upsertSeasonCastInDb(snapshotUser.id, snapshotTitle.id, seasonId, cast).catch((e) =>
              console.error('Season cast backfill DB write failed:', e)
            )
          }
          for (const { episodeId, crew } of allEpisodeCrew) {
            upsertEpisodeCrewInDb(snapshotUser.id, snapshotTitle.id, episodeId, crew).catch((e) =>
              console.error('Episode crew backfill DB write failed:', e)
            )
          }
        }
      }
```

- [ ] **Step 3: Add `CastCrewSection` component**

Add the following component in `TitleDetailDrawer.tsx` after the `ReviewBadges` component and before `EpLogState`:

```typescript
// ─── Cast & Crew section ──────────────────────────────────────────────────────

import type { CastMember, CrewMember } from 'src/store/mockData'

interface CastCrewSectionProps {
  cast?: CastMember[]
  crew?: CrewMember[]
  studios?: string[]
}

const CREW_DISPLAY: Array<{ jobs: string[]; label: string }> = [
  { jobs: ['Creator'],                     label: 'Created by' },
  { jobs: ['Director'],                    label: 'Dir.' },
  { jobs: ['Screenplay', 'Writer', 'Teleplay', 'Story'], label: 'Written by' },
  { jobs: ['Producer'],                    label: 'Prod.' },
  { jobs: ['Director of Photography'],     label: 'D.O.P.' },
  { jobs: ['Original Music Composer'],     label: 'Composer' },
]

function CastCrewSection({ cast, crew, studios }: CastCrewSectionProps) {
  const hasCast = cast && cast.length > 0
  const hasCrew = crew && crew.length > 0
  const hasStudios = studios && studios.length > 0
  if (!hasCast && !hasCrew && !hasStudios) return null

  return (
    <div className="space-y-4">
      {/* Cast — horizontal avatar scroll */}
      {hasCast && (
        <div>
          <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">Cast</h4>
          <div className="flex gap-3 overflow-x-auto scrollbar-none pb-1 -mx-1 px-1">
            {cast.map((member) => (
              <div key={member.tmdbPersonId} className="shrink-0 w-14 text-center">
                <div
                  className="w-14 h-14 rounded-full overflow-hidden mb-1 mx-auto flex items-center justify-center"
                  style={{ background: 'rgba(0,0,0,0.3)', border: '1px solid var(--line)' }}
                >
                  {member.profileUrl ? (
                    <img
                      src={member.profileUrl}
                      alt={member.name}
                      className="w-full h-full object-cover"
                    />
                  ) : (
                    <span className="font-mono text-lg" style={{ color: 'var(--paper-faint)' }}>
                      {member.name.charAt(0).toUpperCase()}
                    </span>
                  )}
                </div>
                <div
                  className="font-sans truncate"
                  style={{ fontSize: '10px', color: 'var(--paper)', lineHeight: 1.3 }}
                  title={member.name}
                >
                  {member.name}
                </div>
                {member.character && (
                  <div
                    className="font-mono truncate"
                    style={{ fontSize: '9px', color: 'var(--paper-faint)', lineHeight: 1.3 }}
                    title={member.character}
                  >
                    {member.character}
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Crew rows */}
      {(hasCrew || hasStudios) && (
        <div className="space-y-1.5">
          {hasCrew && CREW_DISPLAY.map(({ jobs, label }) => {
            const members = crew!.filter((c) => jobs.includes(c.job))
            if (members.length === 0) return null
            return (
              <div key={label} className="flex gap-3 text-xs">
                <span
                  className="font-mono shrink-0 text-right"
                  style={{ width: '80px', color: 'var(--paper-faint)', fontSize: '10px' }}
                >
                  {label}
                </span>
                <span className="font-sans" style={{ color: 'var(--paper)' }}>
                  {members.map((m) => m.name).join(' · ')}
                </span>
              </div>
            )
          })}
          {hasStudios && (
            <div className="flex gap-3 text-xs">
              <span
                className="font-mono shrink-0 text-right"
                style={{ width: '80px', color: 'var(--paper-faint)', fontSize: '10px' }}
              >
                Studio
              </span>
              <span className="font-sans" style={{ color: 'var(--paper)' }}>
                {studios!.join(', ')}
              </span>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
```

Note: The `import type { CastMember, CrewMember }` inside the component block will cause a linting error if the file already has a top-level import from `mockData`. Move the types to the top-level import instead. The final top-level mockData import should be:

```typescript
import type { Viewing, WatchStatus, Season, Episode, EpisodeCrew, CastMember, CrewMember } from 'src/store/mockData'
```

- [ ] **Step 4: Render `CastCrewSection` in the drawer body**

In the scrollable body section of the main drawer (`TitleDetailDrawer`), add `CastCrewSection` between the Genres/Tags block and the Critical Reception block:

```tsx
          {/* Cast & Crew */}
          {(title.cast?.length || title.crew?.length || title.studios?.length) ? (
            <div>
              <h4 className="font-sans text-xs uppercase tracking-widest text-muted-foreground mb-3">
                Cast &amp; Crew
              </h4>
              <CastCrewSection cast={title.cast} crew={title.crew} studios={title.studios} />
            </div>
          ) : null}
```

- [ ] **Step 5: Add episode crew line to `EpisodePanel`**

In the `EpisodePanel` component, add a crew line at the top of the panel body (before the existing still/synopsis block):

```tsx
      {/* Episode crew — director / writers */}
      {(episode.director || (episode.writers && episode.writers.length > 0)) && (
        <div className="flex flex-wrap gap-x-3 gap-y-0.5" style={{ fontSize: '11px' }}>
          {episode.director && (
            <span>
              <span className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '9px' }}>Dir. </span>
              <span className="font-sans" style={{ color: 'var(--paper-dim)' }}>{episode.director}</span>
            </span>
          )}
          {episode.writers && episode.writers.length > 0 && (
            <span>
              <span className="font-mono" style={{ color: 'var(--paper-faint)', fontSize: '9px' }}>Written by </span>
              <span className="font-sans" style={{ color: 'var(--paper-dim)' }}>{episode.writers.join(', ')}</span>
            </span>
          )}
        </div>
      )}
```

- [ ] **Step 6: Add season cast to `TVSeriesSection`**

In `TVSeriesSection`, after the season tabs `div` and before the episode list section, add:

```tsx
        {/* Season cast — shown when the selected season has cast data */}
        {season?.cast && season.cast.length > 0 && (
          <div className="mb-4">
            <div
              className="font-mono mb-2"
              style={{ fontSize: '9px', letterSpacing: '0.14em', color: 'var(--paper-faint)', textTransform: 'uppercase' }}
            >
              Season Cast
            </div>
            <div className="flex gap-3 overflow-x-auto scrollbar-none pb-1 -mx-1 px-1">
              {season.cast.map((member) => (
                <div key={member.tmdbPersonId} className="shrink-0 w-12 text-center">
                  <div
                    className="w-12 h-12 rounded-full overflow-hidden mb-1 mx-auto flex items-center justify-center"
                    style={{ background: 'rgba(0,0,0,0.3)', border: '1px solid var(--line)' }}
                  >
                    {member.profileUrl ? (
                      <img src={member.profileUrl} alt={member.name} className="w-full h-full object-cover" />
                    ) : (
                      <span className="font-mono text-base" style={{ color: 'var(--paper-faint)' }}>
                        {member.name.charAt(0).toUpperCase()}
                      </span>
                    )}
                  </div>
                  <div
                    className="font-sans truncate"
                    style={{ fontSize: '9px', color: 'var(--paper)', lineHeight: 1.3 }}
                    title={member.name}
                  >
                    {member.name}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
```

- [ ] **Step 7: Verify build and lint**

```
npm run build && npm run lint
```
Expected: both succeed with no type errors.

- [ ] **Step 8: Manual verification**

Run `npm run dev`. Open the app and verify:

1. **New title (movie):** Search for a movie with TMDB configured → add it → open detail drawer → confirm "Cast & Crew" section appears with avatar chips and labeled crew rows; confirm studios shown.
2. **New title (TV):** Search for a TV series → add it → open detail drawer → expand the Season section → confirm "Season Cast" avatar row appears → expand an episode → confirm "Dir." and "Written by" lines appear above synopsis.
3. **Existing movie:** Open an existing movie → confirm Cast & Crew section is absent (no data yet; use "Refresh poster & metadata" to populate).
4. **Existing TV show (Severance/Black Mirror):** Open the detail drawer → wait for backfill → confirm season cast populates on the selected season tab; expand an episode → confirm director line appears.
5. **Shared view:** In a shared-link view, cast/crew and season cast should be visible (read-only access via shared_key RLS policy).

- [ ] **Step 9: Commit**

```
git add src/components/TitleDetailDrawer.tsx
git commit -m "feat(drawer): add CastCrewSection, episode crew line, season cast; extend TV backfill"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|-----------------|------|
| 4 new normalized tables + studios column | Task 1 |
| RLS on all new tables (owner + shared key read) | Task 1 |
| `CastMember`, `CrewMember`, `EpisodeCrew` interfaces | Task 2 |
| `Title.cast/crew/studios`, `Season.cast`, `Episode.director/writers/crew` | Task 2 |
| Edge Function `append_to_response=credits`; cache key bump | Task 3 |
| `RawTmdbEpisode.crew[]`; `SeasonFetchResult`; `fetchSeasonDetails` return type | Task 4 |
| `fetchMediaDetails` extracts top-10 cast, filtered crew, studios, TV creators | Task 4 |
| DB queries join 4 new tables; `mapDbTitleToLocal` maps all new fields | Task 5 |
| `insertTitleToDb` writes cast/crew/studios/season cast/episode crew | Task 5 |
| `updateTitleInDb` handles studios in META_COLUMNS; delete-reinsert cast/crew | Task 5 |
| 4 new upsert helpers exported from `db.ts` | Task 5 |
| `AddTitleWorkflow` builds seasons with cast and episode crew | Task 6 |
| `handleSave` passes cast/crew/studios in newTitle | Task 6 |
| `RefreshMetadataModal` refreshes cast/crew/studios + season cast + episode crew | Task 7 |
| Backfill effect extended for season cast + episode crew | Task 8 |
| `CastCrewSection` (cast avatar scroll + crew rows + studios) | Task 8 |
| Episode panel: director + writers line | Task 8 |
| `TVSeriesSection`: season cast avatar row | Task 8 |
| Series-level cast backfill deferred to "Refresh metadata" (by design) | ✓ noted in Task 8 |
