import 'fake-indexeddb/auto'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AddTitleWorkflow } from './AddTitleWorkflow'
import { useAppStore } from 'src/store/useAppStore'
import { fetchMediaDetails, searchMedia, type SearchResult } from 'src/lib/media'

vi.mock('src/lib/media', async (importOriginal) => ({
  ...await importOriginal<typeof import('src/lib/media')>(),
  searchMedia: vi.fn(),
  fetchMediaDetails: vi.fn(),
}))

const movie: SearchResult = { tmdbId: 42, type: 'movie', title: 'A new discovery', year: 2026, genres: ['Drama'] }

beforeEach(async () => {
  vi.clearAllMocks()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })))
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} })
  useAppStore.getState().setUser(null)
  await vi.waitFor(() => expect(useAppStore.getState().offlineStatus.hydrated).toBe(true))
  await useAppStore.getState().setTitles([])
  useAppStore.setState({ isAddTitleOpen: true, preselectedResult: null })
  vi.mocked(searchMedia).mockResolvedValue([movie])
  vi.mocked(fetchMediaDetails).mockResolvedValue({ result: movie, tmdbSeasons: [] })
})

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('starts with an empty search after saving a title and opening again', async () => {
  render(<AddTitleWorkflow />)
  fireEvent.change(screen.getByRole('textbox', { name: 'Search' }), { target: { value: 'discovery' } })
  fireEvent.click(await screen.findByRole('button', { name: /A new discovery/ }))
  fireEvent.click(await screen.findByRole('button', { name: 'Add to Library' }))
  await waitFor(() => expect(useAppStore.getState().titles).toHaveLength(1))
  await waitFor(() => expect(useAppStore.getState().isAddTitleOpen).toBe(false))
  act(() => useAppStore.getState().openAddTitle())
  expect(screen.getByRole('textbox', { name: 'Search' })).toHaveValue('')
  expect(screen.queryByText(movie.title)).not.toBeInTheDocument()
})

it('resets when closed through navigation and ignores old detail responses', async () => {
  let resolveDetails!: (value: Awaited<ReturnType<typeof fetchMediaDetails>>) => void
  vi.mocked(fetchMediaDetails).mockReturnValue(new Promise((resolve) => { resolveDetails = resolve }))
  useAppStore.setState({ preselectedResult: movie })
  render(<AddTitleWorkflow />)
  act(() => useAppStore.getState().closeAddTitle())
  act(() => useAppStore.getState().openAddTitle())
  await act(async () => resolveDetails({ result: movie, tmdbSeasons: [] }))
  expect(screen.getByRole('textbox', { name: 'Search' })).toHaveValue('')
  expect(screen.queryByText(movie.title)).not.toBeInTheDocument()
})

it('clears previous search results after dismissing and reopening the dialog', async () => {
  render(<AddTitleWorkflow />)
  fireEvent.change(screen.getByRole('textbox', { name: 'Search' }), { target: { value: 'discovery' } })
  await screen.findByRole('button', { name: /A new discovery/ })
  fireEvent.click(screen.getByRole('button', { name: 'Close' }))
  act(() => useAppStore.getState().openAddTitle())
  expect(screen.getByRole('textbox', { name: 'Search' })).toHaveValue('')
  expect(screen.queryByRole('button', { name: /A new discovery/ })).not.toBeInTheDocument()
})
