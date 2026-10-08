import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '@supabase/supabase-js'
import { useAppStore } from './useAppStore'
import { logEpisodeToDb } from '../lib/db'
import type { Title } from './mockData'

vi.mock('../lib/db', async (original) => ({
  ...await original<typeof import('../lib/db')>(),
  logEpisodeToDb: vi.fn(),
}))

const title: Title = {
  id: 'title', tmdbId: 1, title: 'Series', type: 'tv', year: 2026, genres: [], tags: [],
  status: 'watching', addedAt: '2026-01-01', viewings: [],
  seasons: [{ id: 'season', seasonNumber: 1, episodeCount: 1, episodesWatched: 0,
    episodes: [{ id: 'episode', episodeNumber: 1, watchEvents: [], ratings: [], reviews: [] }] }],
}

beforeEach(() => {
  vi.mocked(logEpisodeToDb).mockReset().mockResolvedValue(undefined)
  vi.spyOn(console, 'error').mockImplementation(() => {})
  useAppStore.setState({ user: { id: 'owner' } as User, titles: [structuredClone(title)], notifications: [] })
})
afterEach(() => vi.restoreAllMocks())

describe('episode log local and remote identity', () => {
  it('keeps all optimistic IDs and timestamps unchanged through retry', async () => {
    vi.mocked(logEpisodeToDb).mockRejectedValueOnce(new Error('offline'))
    useAppStore.getState().logEpisode('title', 1, 1, { watchedAt: '2026-10-08', rating: 4, reviewText: ' Memorable ' })
    await Promise.resolve()
    const opts = vi.mocked(logEpisodeToDb).mock.calls[0][2]
    const episode = useAppStore.getState().titles[0].seasons![0].episodes![0]
    expect(episode.watchEvents[0].id).toBe(opts.watchEventId)
    expect(episode.ratings[0]).toMatchObject({ id: opts.ratingId, ratedAt: opts.recordedAt })
    expect(episode.reviews[0]).toMatchObject({ id: opts.reviewId, reviewedAt: opts.recordedAt, reviewText: 'Memorable' })
    await useAppStore.getState().notifications[0].retry!()
    expect(vi.mocked(logEpisodeToDb).mock.calls[1]).toEqual(vi.mocked(logEpisodeToDb).mock.calls[0])
  })

  it('assigns quick-watch timestamp and watch ID before delivery', () => {
    const result = useAppStore.getState().logNextEpisodeWatch('title')
    const opts = vi.mocked(logEpisodeToDb).mock.calls[0][2]
    expect(opts.recordedAt).toMatch(/^\d{4}-\d{2}-\d{2}T/)
    expect(result?.watchEventId).toBe(opts.watchEventId)
  })
})
