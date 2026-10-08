import { beforeEach, describe, expect, it } from 'vitest'
import fixture from '../../../../docs/android-contracts/fixtures/library-filters.json'
import { useAppStore, type LibraryFilters } from './useAppStore'
import type { Title } from './mockData'

const titles: Title[] = fixture.titles.map((row) => ({
  ...row,
  tmdbId: row.id.charCodeAt(0),
  type: row.type as Title['type'],
  status: row.status as Title['status'],
  cast: row.castNames.map((name, index) => ({ tmdbPersonId: index + 1, name, character: '', order: index })),
  viewings: [{ id: `viewing-${row.id}`, titleId: row.id, date: row.lastInteractionAt }],
}))

beforeEach(() => {
  useAppStore.setState({ titles })
  useAppStore.getState().resetFilters()
})

describe('cross-client Library fixtures', () => {
  it.each(fixture.cases)('$name', ({ filters, expected }) => {
    const patches = filters as Partial<LibraryFilters>
    for (const key of Object.keys(patches) as (keyof LibraryFilters)[]) {
      useAppStore.getState().setFilter(key, patches[key]!)
    }
    expect(useAppStore.getState().filteredTitles.map((title) => title.id)).toEqual(expected)
  })

  it('reset clears search, compound facets, grouping, and custom sort', () => {
    const store = useAppStore.getState()
    store.setFilter('search', 'no match')
    store.setFilter('genres', ['Drama'])
    store.setFilter('groupByFranchise', true)
    store.setFilter('sortField', 'director')
    store.setFilter('sortDir', 'asc')
    store.resetFilters()
    expect(useAppStore.getState().filters).toMatchObject({ search: '', genres: [], groupByFranchise: false, sortField: 'lastInteraction', sortDir: 'desc' })
    expect(useAppStore.getState().filteredTitles.map((title) => title.id)).toEqual(['d', 'b', 'a', 'c', 'e'])
  })
})
