import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '@supabase/supabase-js'
import { useAppStore } from './useAppStore'
import type { Title } from './mockData'
import { IndexedDbOfflineStore } from '../lib/offline/storage'
import { captureLibrarySession } from '../lib/localSave'

vi.mock('./offlineLibrary', async (original) => {
  const actual = await original<typeof import('./offlineLibrary')>()
  const { IDBFactory } = await import('fake-indexeddb')
  const { IndexedDbOfflineStore } = await import('../lib/offline/storage')
  const factory = new IDBFactory()
  return { ...actual, OfflineLibraryRuntime: class extends actual.OfflineLibraryRuntime {
    constructor(options: ConstructorParameters<typeof actual.OfflineLibraryRuntime>[0]) {
      super({ ...options, browserEvents: false, isAuthenticated: async () => false,
        ownerStorage: new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'episode-owner' }),
        anonymousStorage: new IndexedDbOfflineStore({ indexedDB: factory, databaseName: 'episode-anon' }),
        lock: async (_name, work) => work(),
      })
    }
  } }
})

const title: Title = {
  id: 'title', tmdbId: 1, title: 'Series', type: 'tv', year: 2026, genres: [], tags: [],
  status: 'watching', addedAt: '2026-01-01', viewings: [],
  seasons: [{ id: 'season', seasonNumber: 1, episodeCount: 1, episodesWatched: 0,
    episodes: [{ id: 'episode', episodeNumber: 1, watchEvents: [], ratings: [], reviews: [] }] }],
}

beforeEach(async () => {
  useAppStore.getState().setUser({ id: crypto.randomUUID() } as User)
  await useAppStore.getState().loadUserLibrary()
  await useAppStore.getState().addTitle(structuredClone(title))
})
afterEach(() => vi.restoreAllMocks())

describe('durable episode actions', () => {
  it('persists identities and timestamps before resolving, surviving owner reload', async () => {
    await useAppStore.getState().logEpisode('title', 1, 1, { watchedAt: '2026-10-08', rating: 4, reviewText: ' Memorable ' })
    const saved = useAppStore.getState().offlineStatus.commands.at(-1)!
    expect(saved.mutation.kind).toBe('episode.log')
    if (saved.mutation.kind !== 'episode.log') throw new Error('Wrong command')
    const episode = useAppStore.getState().titles[0].seasons![0].episodes![0]
    expect(episode.watchEvents[0]).toEqual(saved.mutation.watchEvent)
    expect(episode.ratings[0]).toEqual(saved.mutation.rating)
    expect(episode.reviews[0]).toEqual(saved.mutation.review)
    expect(saved.mutation.review?.reviewText).toBe('Memorable')
    const user = useAppStore.getState().user
    useAppStore.getState().setUser(null)
    useAppStore.getState().setUser(user)
    await useAppStore.getState().loadUserLibrary()
    expect(useAppStore.getState().offlineStatus.commands.at(-1)).toEqual(saved)
    expect(useAppStore.getState().titles[0].seasons![0].episodes![0]).toEqual(episode)
  })

  it('returns a quick-watch identity only after the journal contains it', async () => {
    const result = await useAppStore.getState().logNextEpisodeWatch('title')
    const command = useAppStore.getState().offlineStatus.commands.at(-1)!
    expect(command.mutation).toMatchObject({ kind: 'episode.log', watchEvent: { id: result?.watchEventId } })
    expect(command.createdAt).toMatch(/^\d{4}-\d{2}-\d{2}T/)
  })

  it('rejects storage failure without showing an unsaved episode or returning success', async () => {
    vi.spyOn(IndexedDbOfflineStore.prototype, 'append').mockRejectedValueOnce(new Error('Storage quota exceeded'))
    await expect(useAppStore.getState().logNextEpisodeWatch('title')).rejects.toThrow('Storage quota exceeded')
    expect(useAppStore.getState().titles[0].seasons![0].episodes![0].watchEvents).toEqual([])
    expect(useAppStore.getState().offlineStorageError).toBe('Storage quota exceeded')
    expect(useAppStore.getState().offlineStatus.commands).toHaveLength(1)
  })

  it('serializes rapid layout edits using the latest durable projection', async () => {
    const initialCount = useAppStore.getState().ledgerPrefs.widgets.length
    const first = useAppStore.getState().addLedgerWidget('ratings')
    const second = useAppStore.getState().addLedgerWidget('ratings')
    const ids = await Promise.all([first, second])
    expect(useAppStore.getState().ledgerPrefs.widgets).toHaveLength(initialCount + 2)
    expect(useAppStore.getState().ledgerPrefs.widgets.slice(-2).map((widget) => widget.id)).toEqual(ids)
  })

  it('captures explicit clears and causal receipt references before later changes', async () => {
    await useAppStore.getState().updateTitle('title', { rating: 4, notes: 'Earlier' })
    const first = useAppStore.getState().offlineStatus.commands.at(-1)!
    await useAppStore.getState().updateTitle('title', { rating: undefined, notes: undefined })
    const last = useAppStore.getState().offlineStatus.commands.at(-1)!
    expect(last.mutation).toMatchObject({ kind: 'title.patch', patch: { rating: null, notes: null } })
    expect(last.preconditions).toContainEqual({ table: 'titles', id: 'title', afterCommandId: first.id })
    expect(useAppStore.getState().titles[0].rating).toBeUndefined()
  })

  it('rejects an asynchronous preparation after an owner generation changes', () => {
    const check = captureLibrarySession()
    const user = useAppStore.getState().user
    useAppStore.getState().setUser(null)
    useAppStore.getState().setUser(user)
    expect(check).toThrow('changed')
  })

  it('splits bulk imports by title while keeping each title and provenance atomic', async () => {
    await useAppStore.getState().applySyncOutcome({ inserts: [{ ...title, id: 'second', tmdbId: 2, seasons: [] }, { ...title, id: 'third', tmdbId: 3, seasons: [] }], updates: [],
      links: [{ titleId: 'second', provider: 'letterboxd', externalId: 'second' }, { titleId: 'third', provider: 'letterboxd', externalId: 'third' }], unchanged: 0, unmatched: [] })
    const commands = useAppStore.getState().offlineStatus.commands.slice(-2)
    expect(commands).toHaveLength(2)
    for (const command of commands) {
      expect(command.mutation.kind).toBe('batch')
      if (command.mutation.kind !== 'batch') throw new Error('Expected compound import')
      expect(command.mutation.mutations.map((m) => m.kind)).toEqual(['title.create', 'external.link'])
    }
  })
})
