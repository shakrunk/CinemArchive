import { describe, expect, it } from 'vitest'
import { mockTitles } from 'src/store/mockData'
import { ledgerRows, posterRows, sectionName, type LibrarySection } from './libraryRows'

const titles = mockTitles.slice(0, 11)
const sections: LibrarySection[] = [
  { key: 'franchise', name: 'A Franchise Collection', titles: titles.slice(0, 7) },
  { key: 'standalone', name: null, titles: titles.slice(7) },
]

describe('virtual library rows', () => {
  it('keeps every title exactly once and in order across densities and section boundaries', () => {
    for (const columns of [1, 2, 3, 5, 8]) {
      const rows = posterRows(titles, columns, sections)
      expect(rows.flatMap(row => row.kind === 'posters' ? row.titles : [])).toEqual(titles)
      expect(rows.filter(row => row.kind === 'heading')).toHaveLength(2)
      expect(new Set(rows.map(row => row.key)).size).toBe(rows.length)
      expect(rows.filter(row => row.kind === 'posters' && row.last)).toHaveLength(2)
    }
  })
  it('omits headings in flat mode and preserves a partial final row', () => {
    const rows = posterRows(titles, 5)
    expect(rows.map(row => row.kind)).toEqual(['posters', 'posters', 'posters'])
    expect(rows.at(-1)).toMatchObject({ titles: titles.slice(10), last: true })
    expect(posterRows([], 3)).toEqual([])
  })
  it('retains ledger order, stable title keys, and per-franchise numbering', () => {
    const rows = ledgerRows(titles, sections)
    const titleRows = rows.filter(row => row.kind === 'title')
    expect(titleRows.map(row => row.title)).toEqual(titles)
    expect(titleRows.map(row => row.number)).toEqual([1, 2, 3, 4, 5, 6, 7, 1, 2, 3, 4])
    expect(titleRows.map(row => row.key)).toEqual(ledgerRows(titles).filter(row => row.kind === 'title').map(row => row.key))
    expect(rows.filter(row => row.kind === 'columns')).toHaveLength(2)
    expect(sectionName(sections[0])).toBe('A Franchise')
    expect(sectionName(sections[1])).toBe('Standalone')
  })
})
