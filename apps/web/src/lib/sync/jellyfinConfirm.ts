// Writes a user-confirmed Jellyfin play into the library. Nothing here runs
// until the user answers "Add" on a prompt. Movies reuse the import merge
// (dedupes by date, never overwrites). Episodes are logged on a series that is
// already in the library; anything else hands off to the normal Add flow so the
// season/episode catalog is built the same way as every other add.

import { useAppStore } from 'src/store/useAppStore'
import type { Title } from 'src/store/mockData'
import { resolveSyncItems, resolveTmdb, type SyncItem } from './core'
import { applySyncOutcome } from './apply'
import type { JellyfinPlay } from './jellyfin'

export type ConfirmResult = 'added' | 'duplicate' | 'opened-add-flow' | 'unmatched'

export type EpisodePlan = 'log' | 'duplicate' | 'missing'

/** Whether an episode watch on `date` can be logged against `title`. */
export function planEpisodeLog(title: Title, seasonNumber: number, episodeNumber: number, date: string): EpisodePlan {
  const episode = title.seasons?.find((s) => s.seasonNumber === seasonNumber)?.episodes?.find((e) => e.episodeNumber === episodeNumber)
  if (!episode) return 'missing'
  return episode.watchEvents.some((w) => w.watchedAt === date) ? 'duplicate' : 'log'
}

const itemId = (play: JellyfinPlay) => play.key.split('@')[0]

export async function confirmJellyfinPlay(play: JellyfinPlay, userId: string): Promise<ConfirmResult> {
  const store = useAppStore.getState()
  if (play.kind === 'movie') {
    const item: SyncItem = {
      provider: 'jellyfin',
      externalId: itemId(play),
      type: 'movie',
      title: play.title,
      year: play.year,
      ids: play.ids,
      status: 'watched',
      watchedDates: [play.date],
    }
    const outcome = await resolveSyncItems([item], { library: store.titles })
    if (outcome.unmatched.length > 0) return 'unmatched'
    const { added, updated } = await applySyncOutcome({
      userId,
      outcome,
      titles: store.titles,
      setTitles: store.setTitles,
      updateTitle: store.updateTitle,
    })
    return added + updated > 0 ? 'added' : 'duplicate'
  }

  const match = await resolveTmdb({
    provider: 'jellyfin',
    externalId: itemId(play),
    type: 'tv',
    title: play.title,
    ids: play.ids,
    status: 'watching',
    watchedDates: [],
  })
  if (!match) return 'unmatched'
  const series = store.titles.find((t) => t.type === 'tv' && t.tmdbId === match.tmdbId)
  const plan = series ? planEpisodeLog(series, play.seasonNumber ?? -1, play.episodeNumber ?? -1, play.date) : 'missing'
  if (series && plan === 'duplicate') return 'duplicate'
  if (series && plan === 'log') {
    await store.logEpisode(series.id, play.seasonNumber as number, play.episodeNumber as number, { watchedAt: play.date })
    return 'added'
  }
  store.openAddTitlePreselected(match)
  return 'opened-add-flow'
}
