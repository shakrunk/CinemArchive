import { describe, expect, it } from 'vitest'
import { mapSimklItems } from './simkl'

describe('mapSimklItems', () => {
  it('converts rating scale and keeps the last watch date for watched movies', () => {
    const [m] = mapSimklItems([
      { externalId: '1', type: 'movie', title: 'Heat', year: 1995, ids: { imdb: 'tt0113277' }, status: 'watched', rating: 9, lastWatchedAt: '2024-05-01T20:00:00Z' },
    ])
    expect(m.rating).toBe(4.5)
    expect(m.watchedDates).toEqual(['2024-05-01'])
    expect(m.provider).toBe('simkl')
  })
  it('gives no viewings for TV or unwatched items', () => {
    const items = mapSimklItems([
      { externalId: '2', type: 'tv', title: 'Severance', ids: {}, status: 'watched', lastWatchedAt: '2024-05-01T00:00:00Z' },
      { externalId: '3', type: 'movie', title: 'Dune', ids: {}, status: 'watchlist', lastWatchedAt: '2024-05-01T00:00:00Z' },
    ])
    expect(items.every((i) => i.watchedDates.length === 0)).toBe(true)
  })
})
