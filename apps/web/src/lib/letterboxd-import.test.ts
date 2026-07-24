import { describe, expect, it } from 'vitest'
import { parseLetterboxdCsv, pickBestMatch, groupRows } from './letterboxd-import'
import type { SearchResult } from './media'

function movie(overrides: Partial<SearchResult>): SearchResult {
  return { tmdbId: 1, type: 'movie', title: 'Placeholder', year: 2000, genres: [], ...overrides }
}

describe('parseLetterboxdCsv', () => {
  it('rejects a CSV without a Name column', () => {
    expect(() => parseLetterboxdCsv('Foo,Bar\n1,2')).toThrow('no "Name" column')
  })

  it('parses watched.csv rows (Date,Name,Year,Letterboxd URI)', () => {
    const rows = parseLetterboxdCsv(
      'Date,Name,Year,Letterboxd URI\n' +
      '2026-01-05,The Matrix,1999,https://boxd.it/x\n' +
      '2026-02-10,Heat,1995,https://boxd.it/y\n'
    )
    expect(rows).toEqual([
      { name: 'The Matrix', year: 1999, rating: undefined, watchedDate: '2026-01-05' },
      { name: 'Heat', year: 1995, rating: undefined, watchedDate: '2026-02-10' },
    ])
  })

  it('parses ratings and prefers Watched Date over the log Date (diary.csv)', () => {
    const rows = parseLetterboxdCsv(
      'Date,Name,Year,Letterboxd URI,Rating,Rewatch,Tags,Watched Date\n' +
      '2026-03-01,Arrival,2016,https://boxd.it/z,4.5,,,2026-02-27\n'
    )
    expect(rows).toEqual([{ name: 'Arrival', year: 2016, rating: 4.5, watchedDate: '2026-02-27' }])
  })

  it('handles quoted names with commas and escaped quotes', () => {
    const rows = parseLetterboxdCsv(
      'Date,Name,Year,Letterboxd URI\n' +
      '2026-01-01,"I, Tonya",2017,https://boxd.it/a\n' +
      '2026-01-02,"The ""Best"" Movie",2020,https://boxd.it/b\n'
    )
    expect(rows.map((r) => r.name)).toEqual(['I, Tonya', 'The "Best" Movie'])
  })

  it('skips blank rows and tolerates missing optional columns', () => {
    const rows = parseLetterboxdCsv('Name\nDune\n\n')
    expect(rows).toEqual([{ name: 'Dune', year: undefined, rating: undefined, watchedDate: undefined }])
  })
})

describe('pickBestMatch', () => {
  const candidates = [
    movie({ tmdbId: 10, title: 'Solaris', year: 2002 }),
    movie({ tmdbId: 11, title: 'Solaris', year: 1972 }),
    movie({ tmdbId: 12, title: 'Solaris: Behind the Scenes', year: 2002 }),
  ]

  it('prefers the exact title with the exact year', () => {
    expect(pickBestMatch(candidates, 'Solaris', 1972)?.tmdbId).toBe(11)
    expect(pickBestMatch(candidates, 'Solaris', 2002)?.tmdbId).toBe(10)
  })

  it('tolerates a ±1 year drift', () => {
    expect(pickBestMatch(candidates, 'Solaris', 1973)?.tmdbId).toBe(11)
  })

  it('matches titles case/diacritic/punctuation-insensitively', () => {
    const list = [movie({ tmdbId: 20, title: 'Amélie', year: 2001 })]
    expect(pickBestMatch(list, 'amelie', 2001)?.tmdbId).toBe(20)
  })

  it('returns undefined rather than guessing on a full year+title mismatch', () => {
    expect(pickBestMatch(candidates, 'Solar Crisis', 1990)).toBeUndefined()
  })

  it('ignores tv candidates and falls back to TMDB order without a year', () => {
    const list = [
      { ...movie({ tmdbId: 30, title: 'Fargo', year: 2014 }), type: 'tv' as const },
      movie({ tmdbId: 31, title: 'Fargo', year: 1996 }),
    ]
    expect(pickBestMatch(list, 'Fargo')?.tmdbId).toBe(31)
  })

  it('returns undefined when there are no movie candidates', () => {
    expect(pickBestMatch([], 'Anything', 2020)).toBeUndefined()
  })
})

describe('groupRows', () => {
  it('collapses diary rewatches into one film, keeping the most recent watch\'s rating', () => {
    const films = groupRows([
      { name: 'Blade Runner', year: 1982, watchedDate: '2026-05-01', rating: 4 },
      { name: 'Blade Runner', year: 1982, watchedDate: '2026-01-15', rating: 4.5 },
      { name: 'Alien', year: 1979, watchedDate: '2026-03-03' },
    ])
    expect(films).toHaveLength(2)
    const br = films.find((f) => f.name === 'Blade Runner')!
    expect(br.watchedDates).toEqual(['2026-01-15', '2026-05-01'])
    expect(br.rating).toBe(4)
  })

  it('lets an undated rating row (ratings.csv) override dated diary ratings', () => {
    const films = groupRows([
      { name: 'Blade Runner', year: 1982, watchedDate: '2026-05-01', rating: 4 },
      { name: 'Blade Runner', year: 1982, rating: 5 },
    ])
    expect(films[0].rating).toBe(5)
  })

  it('keeps films with the same name but different years separate', () => {
    const films = groupRows([
      { name: 'Solaris', year: 1972 },
      { name: 'Solaris', year: 2002 },
    ])
    expect(films).toHaveLength(2)
  })
})
