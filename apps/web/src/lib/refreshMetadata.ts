// Shared TMDB re-fetch logic used by both the single-title Refresh Metadata
// modal and the library-wide bulk refresh in Profile settings.

import { fetchMediaDetails, fetchSeasonDetails, TMDB_STILL_BASE, type SearchResult } from 'src/lib/media'
import { insertSeasonsInDb, upsertEpisodeMetadataInDb, bulkUpsertSeasonCastInDb, bulkUpsertEpisodeCrewInDb } from 'src/lib/db'
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

/**
 * Re-fetch `base` from TMDB/OMDb and build the patch to apply to `title`.
 * For TV titles, also refreshes episode/season-cast/crew data and fires off
 * the corresponding DB writes (gated on `userId`). Does not itself call
 * `updateTitle` — callers apply the returned patch.
 */
export async function fetchRefreshedTitlePatch(
  title: Title,
  base: SearchResult,
  userId: string | undefined
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

  // For TV shows, also refresh episode metadata for all seasons
  if (result.type === 'tv' && title.seasons && title.seasons.length > 0) {
    // Backfill Specials (TMDB season 0) for series added before they were
    // tracked: start from an empty shell and let the episode fetch below fill
    // it in, exactly like a stored season that has no episode rows yet.
    const tmdbHasSpecials = tmdbSeasons.some((s) => s.season_number === SPECIALS_SEASON_NUMBER && s.episode_count > 0)
    const specialsShell: Season | null = tmdbHasSpecials && !title.seasons.some(isSpecialsSeason)
      ? { id: crypto.randomUUID(), seasonNumber: SPECIALS_SEASON_NUMBER, episodeCount: 0, episodesWatched: 0, episodes: [] }
      : null
    const seasonsToRefresh = specialsShell ? [...title.seasons, specialsShell] : title.seasons

    const settled = await Promise.allSettled(
      seasonsToRefresh.map((s) =>
        fetchSeasonDetails(result.tmdbId, s.seasonNumber).then(({ episodes, cast }) => ({
          season: s,
          tmdbEps: episodes,
          seasonCast: cast,
        }))
      )
    )

    const allEpisodeUpdates: Parameters<typeof upsertEpisodeMetadataInDb>[2] = []
    const allEpisodeCrew: Array<{ episodeId: string; crew: EpisodeCrew[] }> = []
    const allSeasonCast: Array<{ seasonId: string; cast: NonNullable<Title['cast']> }> = []

    const refreshedSeasons = seasonsToRefresh.map((s) => {
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
        const tmdbEpMap = new Map(tmdbEps.map((e) => [e.episode_number, e]))
        updatedEpisodes = existingEpisodes.map((ep) => {
          const tmdbEp = tmdbEpMap.get(ep.episodeNumber)
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

    // A Specials shell TMDB returned no episodes for is dropped, not stored empty.
    const addedSeasons = refreshedSeasons.filter((s) => s.id === specialsShell?.id && (s.episodes?.length ?? 0) > 0)
    const updatedSeasons = refreshedSeasons.filter((s) => s.id !== specialsShell?.id || addedSeasons.length > 0)

    patch.seasons = updatedSeasons

    if (userId) {
      // Awaited, not fire-and-forget: the row must exist (with this client id)
      // before season-cast rows reference it, and before the caller's
      // updateTitle upserts seasons by (title_id, season_number) without an id.
      if (addedSeasons.length > 0) {
        try {
          await insertSeasonsInDb(userId, title.id, addedSeasons)
        } catch (e) {
          console.error('Season insert during refresh failed:', e)
        }
      }
      if (allEpisodeUpdates.length > 0) {
        upsertEpisodeMetadataInDb(userId, title.id, allEpisodeUpdates).catch((e) =>
          console.error('Episode metadata refresh DB write failed:', e)
        )
      }
      if (allSeasonCast.length > 0) {
        bulkUpsertSeasonCastInDb(userId, title.id, allSeasonCast).catch((e) =>
          console.error('Season cast refresh DB write failed:', e)
        )
      }
      if (allEpisodeCrew.length > 0) {
        bulkUpsertEpisodeCrewInDb(userId, title.id, allEpisodeCrew).catch((e) =>
          console.error('Episode crew refresh DB write failed:', e)
        )
      }
    }
  }

  return patch
}
