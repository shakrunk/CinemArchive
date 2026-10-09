import { beforeEach, describe, expect, it } from 'vitest'
import fixture from '../../../../docs/android-contracts/fixtures/library-person-filters.json'
import { useAppStore, type LibraryFilters } from './useAppStore'
import type { Title } from './mockData'

const titles: Title[] = fixture.titles.map((row) => ({ ...row, tags: [], viewings: [], type: row.type as Title['type'], status: row.status as Title['status'] }))

beforeEach(() => {
  useAppStore.setState({ titles })
  useAppStore.getState().resetFilters()
})

describe('cross-client person filter identity', () => {
  it.each(fixture.cases)('$name', ({ filters, expected }) => {
    const patches = filters as Partial<LibraryFilters>
    for (const key of Object.keys(patches) as (keyof LibraryFilters)[]) useAppStore.getState().setFilter(key, patches[key]!)
    expect(useAppStore.getState().filteredTitles.map((title) => title.id)).toEqual(expected)
  })

  it('credit navigation preserves other filters and reset clears the person', () => {
    useAppStore.getState().setFilter('genres', ['Drama'])
    useAppStore.getState().browseByPerson({ id: 42, name: 'Same Name' })
    expect(useAppStore.getState().filters.genres).toEqual(['Drama'])
    expect(useAppStore.getState().filteredTitles.map((title) => title.id)).toEqual(['cast', 'season'])
    useAppStore.getState().resetFilters()
    expect(useAppStore.getState().filters.person).toBeNull()
    expect(useAppStore.getState().filteredTitles).toHaveLength(7)
  })
})
