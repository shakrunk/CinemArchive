import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { Discover } from './Discover'
import { fetchDiscover, fetchTrending, searchMedia, type SearchResult } from 'src/lib/media'
import { useAppStore } from 'src/store/useAppStore'
import { deferred } from 'src/lib/offline/fixtures.test-support'

vi.mock('src/lib/media', async (original) => ({ ...await original<typeof import('src/lib/media')>(),
  fetchDiscover: vi.fn(), fetchTrending: vi.fn(), searchMedia: vi.fn(),
}))
vi.mock('src/components/DiscoverDetailModal', () => ({ DiscoverDetailModal: () => null }))
const initial = useAppStore.getState()
const result = (title: string, tmdbId = 1, type: 'movie' | 'tv' = 'movie'): SearchResult => ({ title, tmdbId, type, year: 2026, genres: [] })
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true, addEventListener() {}, removeEventListener() {} })))
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} })
  useAppStore.setState({ titles: [], isSharedView: false })
  vi.mocked(fetchTrending).mockResolvedValue([result('Trending')])
  vi.mocked(fetchDiscover).mockResolvedValue([result('Movie')])
  vi.mocked(searchMedia).mockResolvedValue([result('Search match')])
})
afterEach(() => { cleanup(); useAppStore.setState(initial, true); vi.useRealTimers(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
async function genre(name = 'Drama') {
  if (!screen.queryByRole('radio', { name })) fireEvent.click(screen.getByRole('button', { name: 'Filters' }))
  await act(async () => fireEvent.click(screen.getByRole('radio', { name })))
}
async function browse() {
  render(<Discover />)
  await screen.findByRole('button', { name: 'View more' })
  await genre()
}

it('keeps Both on later genre pages, deduplicates media identities and advances duplicate-only pages', async () => {
  await browse()
  const next = deferred<SearchResult[]>()
  vi.mocked(fetchDiscover).mockReturnValueOnce(next.promise)
  const more = screen.getByRole('button', { name: 'View more' })
  act(() => { fireEvent.click(more); fireEvent.click(more) })
  expect(fetchDiscover).toHaveBeenCalledTimes(2)
  expect(fetchDiscover).toHaveBeenLastCalledWith('all', 18, 2)
  await act(async () => next.resolve([result('Movie'), result('Series', 1, 'tv'), result('Later series', 2, 'tv'), result('Later series', 2, 'tv')]))
  // The carousel intentionally renders two copies for its scrolling loop.
  for (const title of ['Movie', 'Series', 'Later series']) expect(screen.getAllByRole('button', { name: `View details for ${title}` })).toHaveLength(2)
  vi.mocked(fetchDiscover).mockResolvedValueOnce([result('Later series', 2, 'tv')])
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'View more' })))
  expect(fetchDiscover).toHaveBeenLastCalledWith('all', 18, 3)
  vi.mocked(fetchDiscover).mockResolvedValueOnce([])
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'View more' })))
  expect(fetchDiscover).toHaveBeenLastCalledWith('all', 18, 4)
  expect(screen.queryByRole('button', { name: 'View more' })).not.toBeInTheDocument()
})

it('retains cards and retries the same page after a failed mixed request', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  await browse()
  vi.mocked(fetchDiscover).mockRejectedValueOnce(new Error('TV unavailable'))
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'View more' })))
  expect(screen.getAllByRole('button', { name: 'View details for Movie' })).toHaveLength(2)
  vi.mocked(fetchDiscover).mockResolvedValueOnce([result('Retry series', 2, 'tv')])
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'View more' })))
  expect(vi.mocked(fetchDiscover).mock.calls.slice(-2)).toEqual([['all', 18, 2], ['all', 18, 2]])
  expect(screen.getAllByRole('button', { name: 'View details for Retry series' })).toHaveLength(2)
})

it('ignores an old genre page and does not release the newer in-flight page', async () => {
  await browse()
  const old = deferred<SearchResult[]>(), current = deferred<SearchResult[]>()
  vi.mocked(fetchDiscover).mockReturnValueOnce(old.promise).mockResolvedValueOnce([result('Comedy')]).mockReturnValueOnce(current.promise)
  fireEvent.click(screen.getByRole('button', { name: 'View more' }))
  await genre('Comedy')
  fireEvent.click(screen.getByRole('button', { name: 'View more' }))
  await act(async () => old.resolve([result('Stale drama', 3)]))
  expect(screen.queryByRole('button', { name: 'View details for Stale drama' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Loading…' })).toBeDisabled()
  await act(async () => current.resolve([result('Comedy series', 4, 'tv')]))
  expect(fetchDiscover).toHaveBeenLastCalledWith('all', 35, 2)
  expect(screen.getAllByRole('button', { name: 'View details for Comedy series' })).toHaveLength(2)
})

it('fences an old genre page after a type change and keeps typed trending pagination', async () => {
  await browse()
  const old = deferred<SearchResult[]>()
  vi.mocked(fetchDiscover).mockReturnValueOnce(old.promise)
  fireEvent.click(screen.getByRole('button', { name: 'View more' }))
  vi.mocked(fetchTrending).mockResolvedValueOnce([result('TV trending', 3, 'tv')])
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'TV Shows' })))
  await act(async () => old.resolve([result('Stale genre', 4)]))
  expect(screen.queryByRole('button', { name: 'View details for Stale genre' })).not.toBeInTheDocument()
  vi.mocked(fetchTrending).mockResolvedValueOnce([result('Next TV', 5, 'tv')])
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'View more' })))
  expect(fetchTrending).toHaveBeenLastCalledWith('tv', 2)
  expect(screen.getAllByRole('button', { name: 'View details for Next TV' })).toHaveLength(2)
})

it('does not append a late browse page into search and preserves the add action', async () => {
  await browse()
  const old = deferred<SearchResult[]>(), add = vi.fn()
  useAppStore.setState({ openAddTitlePreselected: add })
  vi.mocked(fetchDiscover).mockReturnValueOnce(old.promise)
  fireEvent.click(screen.getByRole('button', { name: 'View more' }))
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'search' } })
  await screen.findAllByRole('button', { name: 'View details for Search match' })
  await act(async () => old.resolve([result('Stale genre', 4)]))
  expect(screen.queryByRole('button', { name: 'View more' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'View details for Stale genre' })).not.toBeInTheDocument()
  fireEvent.click(screen.getAllByRole('button', { name: 'Add Search match to library' })[0])
  expect(add).toHaveBeenCalledWith(result('Search match'))
})

it.each(['clear search', 'erase search', 'clear genre'])('waits for fresh page one after %s before allowing another page', async (action) => {
  await browse()
  vi.mocked(fetchDiscover).mockResolvedValueOnce([result('Page two', 2)])
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'View more' })))
  if (action !== 'clear genre') {
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'search' } })
    await screen.findAllByRole('button', { name: 'View details for Search match' })
  }
  const first = deferred<SearchResult[]>()
  // Clearing the query retains its selected genre; clearing the genre restores trending.
  if (action === 'clear genre') vi.mocked(fetchTrending).mockReturnValueOnce(first.promise)
  else vi.mocked(fetchDiscover).mockReturnValueOnce(first.promise)
  if (action === 'clear genre') fireEvent.click(screen.getByRole('radio', { name: 'All' }))
  else if (action === 'clear search') fireEvent.click(screen.getByRole('button', { name: 'Clear search' }))
  else fireEvent.change(screen.getByRole('textbox'), { target: { value: '' } })
  expect(screen.queryByRole('button', { name: 'View more' })).not.toBeInTheDocument()
  await act(async () => first.resolve([result('Fresh page one', 3)]))
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'View more' })))
  if (action === 'clear genre') expect(fetchTrending).toHaveBeenLastCalledWith('all', 2)
  else expect(fetchDiscover).toHaveBeenLastCalledWith('all', 18, 2)
})

it('does not let a stale title search unlock pagination while replacement browse page one is pending', async () => {
  await browse()
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'View more' })))
  const search = deferred<SearchResult[]>(), first = deferred<SearchResult[]>()
  vi.mocked(searchMedia).mockReturnValueOnce(search.promise)
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'slow search' } })
  await waitFor(() => expect(searchMedia).toHaveBeenCalledWith('slow search'))
  vi.mocked(fetchDiscover).mockReturnValueOnce(first.promise)
  fireEvent.click(screen.getByRole('button', { name: 'Clear search' }))
  await act(async () => search.resolve([result('Stale search match', 8)]))
  expect(screen.queryByRole('button', { name: 'View more' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'View details for Stale search match' })).not.toBeInTheDocument()
  await act(async () => first.resolve([result('Fresh first page', 9)]))
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'View more' })))
  expect(fetchDiscover).toHaveBeenLastCalledWith('all', 18, 2)
})

it.each(['clear', 'type', 'same type', 'mode'])('cancels a queued title search when changing %s', async (action) => {
  await browse()
  vi.useFakeTimers()
  fireEvent.change(screen.getByRole('textbox'), { target: { value: 'not yet requested' } })
  if (action === 'clear') fireEvent.click(screen.getByRole('button', { name: 'Clear search' }))
  else if (action === 'type') fireEvent.click(screen.getByRole('button', { name: 'TV Shows' }))
  else if (action === 'same type') fireEvent.click(screen.getByRole('button', { name: 'Both' }))
  else fireEvent.click(screen.getByRole('button', { name: 'People' }))
  await act(async () => vi.advanceTimersByTimeAsync(500))
  expect(searchMedia).not.toHaveBeenCalled()
})
