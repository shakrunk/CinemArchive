import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DiscoverDetailModal } from './DiscoverDetailModal'
import { fetchMediaDetails, fetchTitleImages, fetchTitleVideos, fetchWatchProviders, type MediaDetails, type SearchResult, type TitleVideo } from 'src/lib/media'

vi.mock('src/lib/media', () => ({
  fetchMediaDetails: vi.fn(),
  fetchTitleImages: vi.fn(),
  fetchTitleVideos: vi.fn(),
  fetchWatchProviders: vi.fn(),
}))

const movie: SearchResult = { tmdbId: 42, type: 'movie', title: 'A new discovery', year: 2026, genres: ['Drama'] }
const hydrated: SearchResult = {
  ...movie,
  synopsis: 'A story worth exploring.',
  cast: Array.from({ length: 12 }, (_, i) => ({ tmdbPersonId: i + 1, name: `Actor ${i + 1}`, character: `Role ${i + 1}`, order: i })),
  crew: [{ tmdbPersonId: 99, name: 'The Director', job: 'Director' }],
  studios: ['A Studio'],
  originalLanguage: 'en',
  releaseDate: '2026-09-01',
}

function props(result: SearchResult | null = movie) {
  return { result, isOwned: false, isSharedView: false, onClose: vi.fn(), onAdd: vi.fn(), onBrowsePerson: vi.fn() }
}

beforeEach(() => {
  vi.resetAllMocks()
  // jsdom has no layout; leave smooth scrolling disabled while exercising the real UI.
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })))
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} })
  vi.mocked(fetchMediaDetails).mockResolvedValue({ result: hydrated, tmdbSeasons: [] })
  vi.mocked(fetchTitleImages).mockResolvedValue({ logoUrl: null, backdropUrl: null })
  vi.mocked(fetchTitleVideos).mockResolvedValue([{ key: 'preview-video', name: 'Official Trailer', type: 'Trailer', official: true }])
  vi.mocked(fetchWatchProviders).mockResolvedValue({
    link: 'https://www.themoviedb.org/movie/42/watch',
    flatrate: [{ providerId: 1, name: 'Example Stream', logoUrl: '/stream.png' }],
    free: [], ads: [], rent: [], buy: [],
  })
})

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('Discover title preview', () => {
  it('loads trailers and watch options independently of slower title details', async () => {
    vi.mocked(fetchMediaDetails).mockReturnValue(new Promise(() => {}))
    render(<DiscoverDetailModal {...props()} />)
    expect(await screen.findByRole('button', { name: 'Watch Official Trailer' })).toBeVisible()
    expect(await screen.findByAltText('Example Stream')).toBeVisible()
    expect(screen.getByText('Loading cast and crew…')).toBeVisible()
    expect(screen.queryByText('set link for friends')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Watch Official Trailer' }))
    expect(screen.getByRole('button', { name: 'Close trailer' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Close trailer' }))
    expect(screen.queryByRole('button', { name: 'Close trailer' })).not.toBeInTheDocument()
  })

  it('shares expandable cast and crew and opens a selected filmography', async () => {
    const actions = props()
    render(<DiscoverDetailModal {...actions} />)
    expect(await screen.findByRole('button', { name: 'View details for The Director' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /View All/ }))
    fireEvent.click(screen.getByRole('button', { name: 'View details for Actor 12' }))
    expect(actions.onClose).toHaveBeenCalledOnce()
    expect(actions.onBrowsePerson).toHaveBeenCalledWith(expect.objectContaining({ tmdbPersonId: 12 }))
  })

  it('closes only the trailer on Escape, keeping the title preview open', async () => {
    const actions = props()
    render(<DiscoverDetailModal {...actions} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Watch Official Trailer' }))
    fireEvent.keyDown(screen.getByRole('button', { name: 'Close trailer' }), { key: 'Escape' })
    expect(screen.queryByRole('button', { name: 'Close trailer' })).not.toBeInTheDocument()
    expect(actions.onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog', { name: movie.title })).toBeVisible()
  })

  it('adds the hydrated title and preserves the shared and already-owned states', async () => {
    const actions = props()
    const { rerender } = render(<DiscoverDetailModal {...actions} />)
    await screen.findByRole('button', { name: 'View details for The Director' })
    fireEvent.click(screen.getByRole('button', { name: 'Add to Library' }))
    expect(actions.onAdd).toHaveBeenCalledWith(hydrated)
    rerender(<DiscoverDetailModal {...actions} isOwned />)
    expect(screen.getByText('Already in your library')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Add to Library' })).not.toBeInTheDocument()
    rerender(<DiscoverDetailModal {...actions} isSharedView />)
    expect(screen.queryByRole('button', { name: 'Add to Library' })).not.toBeInTheDocument()
  })

  it('does not let late movie results overwrite a series with the same numeric ID', async () => {
    let finishMovie!: (value: MediaDetails) => void
    let finishMovieVideos!: (value: TitleVideo[]) => void
    vi.mocked(fetchTitleVideos).mockImplementation((_id, type) => type === 'movie'
      ? new Promise((resolve) => { finishMovieVideos = resolve })
      : Promise.resolve([{ key: 'series-video', name: 'Series Trailer', type: 'Trailer', official: true }]))
    vi.mocked(fetchMediaDetails).mockImplementation((result) => result.type === 'movie'
      ? new Promise((resolve) => { finishMovie = resolve })
      : Promise.resolve({ result: { ...result, synopsis: 'The series synopsis.' }, tmdbSeasons: [] }))
    const actions = props()
    const { rerender } = render(<DiscoverDetailModal {...actions} />)
    rerender(<DiscoverDetailModal {...actions} result={{ ...movie, type: 'tv', title: 'A series discovery' }} />)
    await screen.findByRole('dialog', { name: 'A series discovery' })
    await act(async () => {
      finishMovie({ result: hydrated, tmdbSeasons: [] })
      finishMovieVideos([{ key: 'old-movie', name: 'Old Movie Trailer', type: 'Trailer', official: true }])
    })
    expect(screen.getByRole('dialog', { name: 'A series discovery' })).toBeVisible()
    expect(screen.queryByText('A story worth exploring.')).not.toBeInTheDocument()
    expect(fetchTitleVideos).toHaveBeenCalledWith(42, 'tv')
    expect(fetchWatchProviders).toHaveBeenCalledWith(42, 'tv')
    expect(screen.getByRole('button', { name: 'Watch Series Trailer' })).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Watch Old Movie Trailer' })).not.toBeInTheDocument()
  })

  it('keeps the preview usable when optional metadata fails', async () => {
    vi.mocked(fetchMediaDetails).mockRejectedValue(new Error('offline'))
    vi.mocked(fetchTitleVideos).mockRejectedValue(new Error('offline'))
    vi.mocked(fetchWatchProviders).mockRejectedValue(new Error('offline'))
    vi.mocked(fetchTitleImages).mockRejectedValue(new Error('offline'))
    render(<DiscoverDetailModal {...props()} />)
    expect(await screen.findByText('No trailers available for this title.')).toBeVisible()
    expect(await screen.findByText('No watch options are available right now.')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Add to Library' })).toBeEnabled()
  })
})
