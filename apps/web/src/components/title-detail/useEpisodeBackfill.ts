import { captureLibrarySession } from 'src/lib/localSave'
import { useEffect, useRef } from 'react'
import { useAppStore } from 'src/store/useAppStore'
import type { Title, Episode, EpisodeCrew } from 'src/store/mockData'
import { fetchSeasonDetails, TMDB_STILL_BASE } from 'src/lib/media'

/** When a TV show is opened and episode metadata is missing, fetch season details
 *  from TMDB and hydrate them in-place, then persist to DB. Handles three cases:
 *  (a) season rows exist but no episode rows were ever inserted,
 *  (b) episode rows exist but none have names yet (pre-feature data), and
 *  (c) episodes exist but the season cast was never fetched.
 *
 *  Call it from the drawer itself, never from a child that remounts per title: the
 *  once-per-title guard is a ref that has to live as long as the drawer does. */
export function useEpisodeBackfill(title: Title | null, isSharedView: boolean) {
  const updateTitleMetadata = useAppStore((s) => s.updateTitleMetadata)

  // Track which title IDs have already been backfilled this session to avoid repeat calls.
  const backfilledRef = useRef<Set<string>>(new Set())

  useEffect(() => {
    if (!title || title.type !== 'tv' || !title.seasons || title.tmdbId <= 0 || isSharedView) return

    const seasonsNeedingBackfill = title.seasons.filter((s) =>
      // Case (a): season exists but no episodes were persisted to DB yet
      (s.episodeCount > 0 && (!s.episodes || s.episodes.length === 0)) ||
      // Case (b): episode rows exist but none have a name (pre-feature data)
      (s.episodes && s.episodes.length > 0 && s.episodes.every((ep) => !ep.episodeName)) ||
      // Case (c): episodes exist but season cast was never fetched
      (s.episodes && s.episodes.length > 0 && (!s.cast || s.cast.length === 0))
    )
    if (seasonsNeedingBackfill.length === 0) return

    const cacheKey = `${title.id}:${title.tmdbId}`
    if (backfilledRef.current.has(cacheKey)) return
    backfilledRef.current.add(cacheKey)

    const snapshotTitle = title
    const checkSession = captureLibrarySession()

    const EP_CREW_JOBS_BF = new Set(['Director', 'Writer', 'Teleplay', 'Story'])

    async function backfill() {
      const settled = await Promise.allSettled(
        seasonsNeedingBackfill.map(async (season) => {
          const { episodes: tmdbEps, cast: seasonCast } = await fetchSeasonDetails(snapshotTitle.tmdbId, season.seasonNumber)
          return { season, tmdbEps, seasonCast }
        })
      )

      let updatedSeasons = [...snapshotTitle.seasons!]

      for (const result of settled) {
        if (result.status !== 'fulfilled' || result.value.tmdbEps.length === 0) continue
        const { season, tmdbEps, seasonCast } = result.value

        const existingEpisodes = season.episodes || []
        let updatedEpisodes: Episode[]

        if (existingEpisodes.length === 0) {
          updatedEpisodes = tmdbEps.map((tmdbEp) => {
            const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
              .filter((c) => EP_CREW_JOBS_BF.has(c.job))
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
          const tmdbEpMap = new Map(tmdbEps.map((e) => [e.episode_number, e]))
          updatedEpisodes = existingEpisodes.map((ep) => {
            const tmdbEp = tmdbEpMap.get(ep.episodeNumber)
            if (!tmdbEp) return ep
            const epCrew: EpisodeCrew[] = (tmdbEp.crew ?? [])
              .filter((c) => EP_CREW_JOBS_BF.has(c.job))
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

      }

      checkSession()
      if (JSON.stringify(snapshotTitle.seasons) !== JSON.stringify(updatedSeasons)) {
        await updateTitleMetadata(snapshotTitle.id, { seasons: updatedSeasons })
      }
    }

    backfill().catch((e) => console.error('Episode metadata backfill failed:', e))
  }, [title?.id, title?.tmdbId]) // eslint-disable-line react-hooks/exhaustive-deps
}
