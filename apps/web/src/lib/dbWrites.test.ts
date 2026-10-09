import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { insertPrePlatformWatchEventsToDb, logEpisodeToDb, updateTitleInDb, type EpisodeLogWrite } from './db'

const server = vi.hoisted(() => ({
  tables: new Map<string, Map<string, Record<string, unknown>>>(),
  loseResponseFor: '',
  calls: 0,
  patch: {} as Record<string, unknown>,
}))

vi.mock('./auth', () => ({
  supabase: {
    from: (table: string) => ({
      upsert: async (input: Record<string, unknown> | Record<string, unknown>[], options: { ignoreDuplicates?: boolean }) => {
        server.calls++
        const rows = server.tables.get(table) ?? new Map<string, Record<string, unknown>>()
        server.tables.set(table, rows)
        for (const row of Array.isArray(input) ? input : [input]) {
          const id = String(row.id)
          if (!rows.has(id) || !options.ignoreDuplicates) rows.set(id, { ...row })
        }
        if (server.loseResponseFor === table) {
          server.loseResponseFor = ''
          return { error: { code: 'NETWORK', message: 'Response lost after commit' } }
        }
        return { error: null }
      },
      update: (patch: Record<string, unknown>) => {
        server.patch = patch
        return { eq: () => ({ eq: async () => ({ error: null }) }) }
      },
    }),
  },
}))

const log: EpisodeLogWrite = {
  watchedAt: '2026-10-07', watchEventId: 'watch-1', watchNotes: 'First watch',
  rating: 4, ratingId: 'rating-1', reviewText: ' Memorable ', reviewId: 'review-1',
  recordedAt: '2026-10-08T12:00:00.000Z',
}

beforeEach(() => {
  server.tables.clear()
  server.loseResponseFor = ''
  server.calls = 0
  server.patch = {}
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => vi.restoreAllMocks())

describe('episode write retry identity', () => {
  it.each(['episode_watch_events', 'episode_ratings', 'episode_reviews'])(
    'resumes after a lost %s acknowledgment without duplicating earlier records', async (table) => {
      server.loseResponseFor = table
      await expect(logEpisodeToDb('owner', 'episode', log)).rejects.toMatchObject({ code: 'NETWORK' })
      await logEpisodeToDb('owner', 'episode', JSON.parse(JSON.stringify(log)))
      for (const rows of server.tables.values()) expect(rows.size).toBe(1)
      expect(server.tables.size).toBe(3)
      expect(server.tables.get('episode_ratings')?.get('rating-1')).toMatchObject({ rated_at: log.recordedAt, rating: 4 })
      expect(server.tables.get('episode_reviews')?.get('review-1')).toMatchObject({ reviewed_at: log.recordedAt, review_text: 'Memorable' })
    },
  )

  it('does not overwrite an existing record on retry', async () => {
    await logEpisodeToDb('owner', 'episode', log)
    server.tables.get('episode_ratings')!.get('rating-1')!.rating = 5
    await logEpisodeToDb('owner', 'episode', log)
    expect(server.tables.get('episode_ratings')!.get('rating-1')!.rating).toBe(5)
  })

  it('rejects missing IDs before writing any part of a compound log', async () => {
    await expect(logEpisodeToDb('owner', 'episode', { ...log, ratingId: undefined })).rejects.toThrow('stable record IDs')
    expect(server.calls).toBe(0)
  })

  it('preserves an indeterminate pre-platform watch date even if a date was supplied', async () => {
    await logEpisodeToDb('owner', 'episode', { ...log, prePlatform: true })
    expect(server.tables.get('episode_watch_events')?.get('watch-1')?.watched_at).toBeNull()
  })

  it('retries bulk pre-platform watches by their original IDs', async () => {
    const events = [{ id: 'a', episodeId: 'episode-a' }, { id: 'b', episodeId: 'episode-b' }]
    server.loseResponseFor = 'episode_watch_events'
    await expect(insertPrePlatformWatchEventsToDb('owner', events)).rejects.toMatchObject({ code: 'NETWORK' })
    await insertPrePlatformWatchEventsToDb('owner', events)
    expect(server.tables.get('episode_watch_events')?.size).toBe(2)
  })
})

describe('explicit title clears', () => {
  it('clears rating and notes without changing absent fields', async () => {
    await updateTitleInDb('owner', 'title', { rating: undefined, notes: undefined })
    expect(server.patch).toEqual({ rating: null, notes: null })
    await updateTitleInDb('owner', 'title', { status: 'watching' })
    expect(server.patch).toEqual({ status: 'watching' })
  })
})
