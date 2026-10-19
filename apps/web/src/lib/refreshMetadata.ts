// Shared TMDB re-fetch logic used by both the single-title Refresh Metadata
// modal and the library-wide bulk refresh in Profile settings.

import { fetchMediaDetails, fetchSeasonDetails, TMDB_STILL_BASE, type RawTmdbEpisode, type SearchResult } from 'src/lib/media'
import { catalogEpisodeId, catalogSeasonId } from 'src/lib/catalogRowIds'
import { SPECIALS_SEASON_NUMBER, isSpecialsSeason } from 'src/store/episodeUtils'
import type { Title, Season, Episode, EpisodeCrew } from 'src/store/mockData'

/** Project an existing library Title back into a SearchResult so it can be
 *  re-hydrated through the same detail-fetch path as a fresh search pick. */
export function titleToSearchResult(t: Title): SearchResult {
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
    collectionId: t.collectionId,
    collectionName: t.collectionName,
  }
}

const EP_CREW_JOBS = new Set(['Director', 'Writer', 'Teleplay', 'Story'])

function episodeCrew(tmdbEp: RawTmdbEpisode): EpisodeCrew[] {
  return (tmdbEp.crew ?? [])
    .filter((c) => EP_CREW_JOBS.has(c.job))
    .map((c) => ({ tmdbPersonId: c.id, name: c.name, job: c.job }))
}

/** A new library episode row built from TMDB, with no watch history. */
export function episodeFromTmdb(tmdbEp: RawTmdbEpisode, id: string): Episode {
  const epCrew = episodeCrew(tmdbEp)
  return {
    id,
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
}

/** TMDB episodes `existing` doesn't hold yet, as new rows with catalog IDs. */
export async function newEpisodesFromTmdb(titleId: string, seasonNumber: number, existing: Episode[], tmdbEps: RawTmdbEpisode[]): Promise<Episode[]> {
  const known = new Set(existing.map((ep) => ep.episodeNumber))
  const missing = tmdbEps.filter((ep) => !known.has(ep.episode_number))
  return Promise.all(missing.map(async (ep) => episodeFromTmdb(ep, await catalogEpisodeId(titleId, seasonNumber, ep.episode_number))))
}

/**
 * Re-fetch `base` from TMDB/OMDb and build the patch to apply to `title`.
 * For TV titles, also refreshes episode/season-cast/crew metadata. Callers
 * submit the returned patch through the durable store; this fetch never writes.
 */
export async function fetchRefreshedTitlePatch(
  title: Title,
  base: SearchResult,
  _userId?: string
): Promise<Partial<Title>> {
  const { result, tmdbSeasons } = await fetchMediaDetails(base)
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
    releaseDate: result.releaseDate,
    originalLanguage: result.originalLanguage,
    contentRating: result.contentRating,
    imdbId: result.imdbId,
    rtUrl: result.rtUrl,
    imdbRating: result.imdbRating,
    rtScore: result.rtScore,
    metacriticScore: result.metacriticScore,
    awardsCount: result.awardsCount,
    bechdelOutcome: result.bechdelOutcome,
    bechdelScore: result.bechdelScore,
    cast: result.cast,
    crew: result.crew,
    studios: result.studios,
    collectionId: result.collectionId,
    collectionName: result.collectionName,
  }

  // For TV shows, also refresh episode metadata for all seasons. A series saved
  // with no seasons at all (its details fetch failed when it was added) is
  // rebuilt from TMDB's season list as empty shells the episode fetch fills in.
  const storedSeasons: Season[] = title.seasons && title.seasons.length > 0
    ? title.seasons
    : tmdbSeasons
        .filter((s) => s.season_number >= 1)
        .sort((a, b) => a.season_number - b.season_number)
        .map((s) => ({ id: crypto.randomUUID(), seasonNumber: s.season_number, episodeCount: s.episode_count, episodesWatched: 0, episodes: [] }))
  if (result.type === 'tv' && storedSeasons.length > 0) {
    // Backfill Specials (TMDB season 0) for series added before they were
    // tracked: start from an empty shell and let the episode fetch below fill
    // it in, exactly like a stored season that has no episode rows yet.
    const tmdbHasSpecials = tmdbSeasons.some((s) => s.season_number === SPECIALS_SEASON_NUMBER && s.episode_count > 0)
    const specialsShell: Season | null = tmdbHasSpecials && !storedSeasons.some(isSpecialsSeason)
      ? { id: crypto.randomUUID(), seasonNumber: SPECIALS_SEASON_NUMBER, episodeCount: 0, episodesWatched: 0, episodes: [] }
      : null
    // Seasons TMDB announced after the series was saved, likewise as shells.
    const storedNumbers = new Set(storedSeasons.map((s) => s.seasonNumber))
    const newSeasonShells: Season[] = await Promise.all(tmdbSeasons
      .filter((s) => s.season_number >= 1 && s.episode_count > 0 && !storedNumbers.has(s.season_number))
      .sort((a, b) => a.season_number - b.season_number)
      .map(async (s) => ({ id: await catalogSeasonId(title.id, s.season_number), seasonNumber: s.season_number, episodeCount: 0, episodesWatched: 0, episodes: [] })))
    const shells = [...newSeasonShells, ...(specialsShell ? [specialsShell] : [])]
    const shellIds = new Set(shells.map((s) => s.id))
    const seasonsToRefresh = [...storedSeasons, ...shells]

    const settled = await Promise.allSettled(
      seasonsToRefresh.map((s) =>
        fetchSeasonDetails(result.tmdbId, s.seasonNumber).then(({ episodes, cast }) => ({
          season: s,
          tmdbEps: episodes,
          seasonCast: cast,
        }))
      )
    )

    const refreshedSeasons = await Promise.all(seasonsToRefresh.map(async (s) => {
      const match = settled.find(
        (r) => r.status === 'fulfilled' && r.value.season.seasonNumber === s.seasonNumber
      )
      if (!match || match.status !== 'fulfilled' || match.value.tmdbEps.length === 0) return s

      const { tmdbEps, seasonCast } = match.value
      const existingEpisodes = s.episodes || []
      const tmdbEpMap = new Map(tmdbEps.map((e) => [e.episode_number, e]))
      // Existing rows keep their ids and history; episodes TMDB added since
      // (a season still airing) are appended.
      const updatedEpisodes: Episode[] = [
        ...existingEpisodes.map((ep) => {
          const tmdbEp = tmdbEpMap.get(ep.episodeNumber)
          if (!tmdbEp) return ep
          const epCrew = episodeCrew(tmdbEp)
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
        }),
        ...await newEpisodesFromTmdb(title.id, s.seasonNumber, existingEpisodes, tmdbEps),
      ].sort((a, b) => a.episodeNumber - b.episodeNumber)

      return {
        ...s,
        episodes: updatedEpisodes,
        episodeCount: updatedEpisodes.length,
        cast: seasonCast && seasonCast.length > 0 ? seasonCast : s.cast,
      }
    }))

    // A shell TMDB returned no episodes for is dropped, not stored empty.
    const updatedSeasons = refreshedSeasons.filter((s) => !shellIds.has(s.id) || (s.episodes?.length ?? 0) > 0)

    patch.seasons = updatedSeasons


  }

  return patch
}
