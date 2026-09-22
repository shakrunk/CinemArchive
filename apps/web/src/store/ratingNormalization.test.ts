import { describe, expect, it } from 'vitest'
import type { Title } from './mockData'
import { deriveRatingNormalization } from './ratingNormalization'

function title(id: string, rating?: number, type: Title['type'] = 'movie'): Title {
  return { id, title: id, rating, type, tmdbId: 1, year: 2026, genres: [], tags: [], status: 'watched', addedAt: '', viewings: [] }
}

describe('personal rating normalization', () => {
  it('standardizes the population and gives ties an empirical midrank', () => {
    const { groups, rows } = deriveRatingNormalization([title('a', 1), title('b', 3), title('c', 3), title('d', 5)])
    expect(groups[0].mean).toBe(3)
    expect(groups[0].deviation).toBeCloseTo(Math.sqrt(2))
    expect(rows.map((r) => r.percentile)).toEqual([87.5, 50, 50, 12.5])
    expect(rows[0].zScore).toBeCloseTo(Math.sqrt(2))
    expect(rows.reduce((sum, r) => sum + r.zScore!, 0)).toBeCloseTo(0)
    expect(rows.reduce((sum, r) => sum + r.zScore! ** 2, 0) / rows.length).toBeCloseTo(1)
  })

  it('includes zero and fractional ratings, and excludes missing or invalid ratings', () => {
    const { rows } = deriveRatingNormalization([
      title('zero', 0), title('fraction', 0.5), title('rollup', 3.333), title('unrated'),
      title('nan', NaN), title('inf', Infinity), title('negative', -1), title('too high', 6),
    ])
    expect(rows.map((r) => r.title.id)).toEqual(['rollup', 'fraction', 'zero'])
  })

  it('handles empty, singleton and constant baselines without inventing z-scores', () => {
    expect(deriveRatingNormalization([]).rows).toEqual([])
    expect(deriveRatingNormalization([]).groups[0].mean).toBeNull()
    for (const input of [[title('a', 4)], [title('a', 4), title('b', 4)]]) {
      for (const row of deriveRatingNormalization(input).rows) {
        expect(row.zScore).toBeNull()
        expect(row.percentile).toBe(50)
      }
    }
  })

  it('separates movie and series habits, while scope only limits displayed rows', () => {
    const titles = [title('film-low', 1), title('film-high', 3), title('tv-low', 3, 'tv'), title('tv-high', 5, 'tv')]
    const all = deriveRatingNormalization(titles)
    const scoped = deriveRatingNormalization(titles, 'all', 'movies')
    expect(scoped.groups[0].count).toBe(4)
    expect(scoped.rows).toEqual(all.rows.filter((r) => r.title.type === 'movie'))
    const byType = deriveRatingNormalization(titles, 'media')
    expect(byType.rows.find((r) => r.title.id === 'film-high')?.zScore).toBe(1)
    expect(byType.rows.find((r) => r.title.id === 'tv-low')?.zScore).toBe(-1)
  })

  it('uses one current rating per title regardless of rewatches and updates with the library', () => {
    const titles = [title('a', 1), title('b', 5)]
    titles[0].viewings = [{ id: 'v1', titleId: 'a', rating: 5 }, { id: 'v2', titleId: 'a', rating: 4 }]
    const before = JSON.stringify(titles)
    expect(deriveRatingNormalization(titles).groups[0].mean).toBe(3)
    expect(JSON.stringify(titles)).toBe(before)
    expect(deriveRatingNormalization([titles[1]]).rows[0].zScore).toBeNull()
    expect(deriveRatingNormalization([titles[0], { ...titles[1], rating: 3 }]).groups[0].mean).toBe(2)
  })
})
