import type { CinemaOuting, Title } from '../../store/mockData'
import { scopeKey, type OfflineScope } from './commands'
import { emptySnapshot } from './snapshot'

export const owner: OfflineScope = { projectId: 'https://project.supabase.co', userId: 'owner-a' }
export const stamp = '2026-10-08T12:00:00.000Z'
export const title: Title = {
  id: 'title-a', tmdbId: 123, type: 'tv', title: 'An Example', year: 2026,
  genres: [], status: 'watchlist', tags: [], addedAt: stamp, viewings: [],
  seasons: [{ id: 'season-a', seasonNumber: 1, episodeCount: 1, episodesWatched: 0,
    episodes: [{ id: 'episode-a', episodeNumber: 1, watchEvents: [], ratings: [], reviews: [] }],
  }],
}
export const outing: CinemaOuting = {
  id: 'outing-a', titleId: title.id, showtime: stamp, endsAt: '2026-10-08T14:00:00.000Z',
  previewsMinutes: 15, runtimeMinutes: 105, companions: [], seats: [], status: 'scheduled', createdAt: stamp,
}
export function snapshot() { return { ...emptySnapshot(), titles: [title] } }
export function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

/** Corrupt persisted bytes without bypassing the real IndexedDB transaction. */
export function writeRawOwner(factory: IDBFactory, databaseName: string, raw: unknown): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = factory.open(databaseName, 1)
    request.onerror = () => reject(request.error)
    request.onsuccess = () => {
      const connection = request.result
      const tx = connection.transaction('owners', 'readwrite')
      tx.objectStore('owners').put(raw, scopeKey(owner))
      tx.oncomplete = () => { connection.close(); resolve() }
      tx.onabort = () => { connection.close(); reject(tx.error) }
    }
  })
}
