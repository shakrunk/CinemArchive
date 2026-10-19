// Background refresh of upcoming episode air dates for tracked series.
//
// Episode rows are otherwise written only when a series is added or its
// metadata is refreshed by hand, so a returning show whose next season was
// announced later never reaches the Calendar or Up Next. On app open this
// checks a few non-dropped series a day each: one TMDB details call (shared
// with the media-proxy's 24h server cache) decides whether any season can
// have changed, and only those seasons are fetched. Changes are written as
// narrow journal commands — new rows, or an air date/name on an existing
// episode — never a whole-season rewrite, and never touching watch history.

import { fetchSeasonDetails, fetchTvSchedule, type RawTmdbEpisode, type SeasonFetchResult, type TvSchedule } from 'src/lib/media'
import { catalogEpisodeId, catalogSeasonId } from 'src/lib/catalogRowIds'
import { episodeFromTmdb } from 'src/lib/refreshMetadata'
import { localTodayYmd } from 'src/store/episodeUtils'
import type { TrackingMutation } from 'src/lib/offline/commands'
import type { Episode, Season, Title } from 'src/store/mockData'

/** How long a series stays checked. Matches the media-proxy cache TTL. */
export const SCHEDULE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000
/** Series checked per run, least recently checked first. */
export const SCHEDULE_CHECK_BATCH = 20

const FINISHED_STATUSES = new Set(['Ended', 'Canceled'])

/** Series eligible for a schedule check, least recently checked first. */
export function seriesDueForScheduleCheck(
  titles: readonly Title[],
  checkedAt: Readonly<Record<string, number>>,
  now: number,
  limit = SCHEDULE_CHECK_BATCH,
): Title[] {
  return titles
    .filter((t) => t.type === 'tv' && t.status !== 'dropped' && t.tmdbId != null)
    .filter((t) => !(now - (checkedAt[t.id] ?? 0) < SCHEDULE_CHECK_INTERVAL_MS))
    .sort((a, b) => (checkedAt[a.id] ?? 0) - (checkedAt[b.id] ?? 0))
    .slice(0, limit)
}

/**
 * Season numbers worth a season-details fetch:
 * - a season TMDB lists that isn't stored yet (newly announced);
 * - a stored season TMDB now counts more episodes for;
 * - for a series still running, a stored season with an undated or upcoming
 *   episode (dates get set and moved), and the season of the next episode.
 * A stored season without episode rows (an old coarse count) is left to a
 * manual refresh, which knows how to carry its watched count over.
 */
export function seasonsToCheck(title: Title, schedule: TvSchedule, today: string): number[] {
  const stored = new Map((title.seasons ?? []).map((s) => [s.seasonNumber, s]))
  const running = !FINISHED_STATUSES.has(schedule.status ?? '')
  const due = new Set<number>()
  for (const tmdb of schedule.seasons) {
    const season = stored.get(tmdb.season_number)
    if (!season) {
      if (tmdb.season_number >= 1 && tmdb.episode_count > 0) due.add(tmdb.season_number)
      continue
    }
    const episodes = season.episodes ?? []
    if (episodes.length === 0) continue
    if (tmdb.episode_count > episodes.length) due.add(tmdb.season_number)
    else if (running && episodes.some((ep) => !ep.airDate || ep.airDate >= today)) due.add(tmdb.season_number)
  }
  const next = schedule.nextEpisodeSeason
  if (running && next != null && (stored.get(next)?.episodes?.length || (!stored.has(next) && next >= 1))) due.add(next)
  return [...due].sort((a, b) => a - b)
}

/** Catalog IDs for every TMDB season/episode fetched, keyed `s` / `s:e`. */
export async function scheduleRowIds(titleId: string, fetched: ReadonlyMap<number, SeasonFetchResult>): Promise<Map<string, string>> {
  const ids = new Map<string, string>()
  await Promise.all([...fetched].map(async ([seasonNumber, { episodes }]) => {
    ids.set(`${seasonNumber}`, await catalogSeasonId(titleId, seasonNumber))
    await Promise.all(episodes.map(async (ep) => {
      ids.set(`${seasonNumber}:${ep.episode_number}`, await catalogEpisodeId(titleId, seasonNumber, ep.episode_number))
    }))
  }))
  return ids
}

function newEpisodes(seasonNumber: number, existing: Episode[], tmdbEps: RawTmdbEpisode[], ids: ReadonlyMap<string, string>): Episode[] {
  const known = new Set(existing.map((ep) => ep.episodeNumber))
  return tmdbEps
    .filter((ep) => !known.has(ep.episode_number))
    .sort((a, b) => a.episode_number - b.episode_number)
    .map((ep) => episodeFromTmdb(ep, ids.get(`${seasonNumber}:${ep.episode_number}`)!))
}

/**
 * Journal commands that bring `title`'s episode schedule in line with the
 * fetched TMDB seasons. Pure and idempotent: re-running it on the result of
 * its own commands yields none.
 */
export function scheduleMutations(title: Title, fetched: ReadonlyMap<number, SeasonFetchResult>, ids: ReadonlyMap<string, string>): TrackingMutation[] {
  const mutations: TrackingMutation[] = []
  for (const [seasonNumber, { episodes: tmdbEps, cast }] of [...fetched].sort(([a], [b]) => a - b)) {
    if (tmdbEps.length === 0) continue
    const stored = title.seasons?.find((s) => s.seasonNumber === seasonNumber)

    if (!stored) {
      const episodes = newEpisodes(seasonNumber, [], tmdbEps, ids)
      const season: Season = { id: ids.get(`${seasonNumber}`)!, seasonNumber, episodeCount: episodes.length, episodesWatched: 0, episodes }
      if (cast.length > 0) season.cast = cast
      mutations.push({ kind: 'season.put', titleId: title.id, season })
      continue
    }

    const existing = stored.episodes ?? []
    if (existing.length === 0) continue
    const byNumber = new Map(tmdbEps.map((ep) => [ep.episode_number, ep]))
    for (const ep of existing) {
      const tmdbEp = byNumber.get(ep.episodeNumber)
      if (!tmdbEp) continue
      const patch: { airDate?: string; episodeName?: string } = {}
      if (tmdbEp.air_date && tmdbEp.air_date !== ep.airDate) patch.airDate = tmdbEp.air_date
      if (tmdbEp.name && tmdbEp.name !== ep.episodeName) patch.episodeName = tmdbEp.name
      if (Object.keys(patch).length > 0) mutations.push({ kind: 'episode.metadata', titleId: title.id, episodeId: ep.id, patch })
    }
    const added = newEpisodes(seasonNumber, existing, tmdbEps, ids)
    if (added.length > 0) {
      // Only the new rows: a put carrying the stored episodes would rewrite
      // every one of them (and their crew) on each run.
      const season: Season = { id: stored.id, seasonNumber, episodeCount: existing.length + added.length, episodesWatched: stored.episodesWatched, episodes: added }
      if (stored.airYear !== undefined) season.airYear = stored.airYear
      mutations.push({ kind: 'season.put', titleId: title.id, season })
    }
  }
  return mutations
}

export interface ScheduleRefreshDeps {
  now?: () => Date
  /** The owner's library as it is now (re-read between network calls). */
  getTitles: () => readonly Title[]
  /** False once the account or library view changed; the run stops. */
  isCurrent: () => boolean
  readCheckedAt: () => Record<string, number>
  writeCheckedAt: (checkedAt: Record<string, number>) => void
  /** Writes `build(latest title)` through the durable store. */
  submit: (titleId: string, build: (title: Title) => TrackingMutation[]) => Promise<void>
  fetchSchedule?: (tmdbId: number) => Promise<TvSchedule | null>
  fetchSeason?: (tmdbId: number, seasonNumber: number) => Promise<SeasonFetchResult>
}

/** One background pass. Returns how many series got schedule changes. */
export async function runEpisodeScheduleRefresh(deps: ScheduleRefreshDeps): Promise<number> {
  const { getTitles, isCurrent, submit, fetchSchedule = fetchTvSchedule, fetchSeason = fetchSeasonDetails } = deps
  const now = (deps.now ?? (() => new Date()))()
  const today = localTodayYmd(now)
  const titles = getTitles()
  // Forget titles no longer in the library so the stamp map can't grow forever.
  const live = new Set(titles.map((t) => t.id))
  const checkedAt = Object.fromEntries(Object.entries(deps.readCheckedAt()).filter(([id]) => live.has(id)))
  const due = seriesDueForScheduleCheck(titles, checkedAt, now.getTime())
  if (due.length === 0) return 0
  // Stamp before fetching, so another tab opened meanwhile skips these.
  for (const title of due) checkedAt[title.id] = now.getTime()
  deps.writeCheckedAt(checkedAt)

  let updated = 0
  for (const title of due) {
    if (!isCurrent()) break
    try {
      const schedule = await fetchSchedule(title.tmdbId!)
      if (!schedule) continue
      const numbers = seasonsToCheck(title, schedule, today)
      if (numbers.length === 0) continue
      const fetched = new Map(await Promise.all(numbers.map(async (n) => [n, await fetchSeason(title.tmdbId!, n)] as const)))
      const ids = await scheduleRowIds(title.id, fetched)
      if (!isCurrent()) break
      let changed = false
      await submit(title.id, (latest) => {
        if (latest.status === 'dropped' || latest.tmdbId !== title.tmdbId) return []
        const mutations = scheduleMutations(latest, fetched, ids)
        changed = mutations.length > 0
        return mutations
      })
      if (changed) updated++
    } catch (error) {
      console.warn(`Episode schedule check failed for "${title.title}":`, error)
    }
  }
  return updated
}
