import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { Discover } from './Discover'
import { fetchPersonCredits, fetchRecommendations, fetchTrending, type SearchResult } from 'src/lib/media'
import { useAppStore } from 'src/store/useAppStore'
import type { CastMember, Title } from 'src/store/mockData'

vi.mock('src/lib/media', async (original) => ({ ...await original<typeof import('src/lib/media')>(),
  fetchTrending: vi.fn(), fetchRecommendations: vi.fn(), fetchPersonCredits: vi.fn(),
}))
vi.mock('src/components/DiscoverDetailModal', () => ({ DiscoverDetailModal: () => null }))
const initial = useAppStore.getState()
const result = (title: string, tmdbId: number, type: 'movie' | 'tv' = 'movie'): SearchResult => ({ title, tmdbId, type, year: 2026, genres: [] })
const actor = (name: string, tmdbPersonId: number): CastMember => ({ name, tmdbPersonId, order: 0 })
const title = (id: string, tmdbId: number, cast: CastMember[]): Title => ({
  id, title: id, tmdbId, type: 'movie', year: 1990, genres: [], tags: [], status: 'watched', addedAt: '', viewings: [], cast,
})
const library = [title('Alien', 348, [actor('Sigourney Weaver', 10205)]), title('Heat', 949, [actor('Al Pacino', 1158)])]
const cards = (name: string) => screen.queryAllByRole('button', { name: `View details for ${name}` })

beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true, addEventListener() {}, removeEventListener() {} })))
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} })
  useAppStore.setState({ titles: library, isSharedView: false })
  vi.mocked(fetchTrending).mockResolvedValue([])
  vi.mocked(fetchRecommendations).mockImplementation(async (tmdbId) =>
    tmdbId === 348
      ? [result('Aliens', 679), result('Heat', 949), result('Alien: Earth', 157239, 'tv')]
      : [result('Collateral', 1538)])
  vi.mocked(fetchPersonCredits).mockImplementation(async (personId) =>
    personId === 1158 ? [result('The Godfather', 238), result('Heat', 949)] : [result('Gorillas in the Mist', 1939)])
})
afterEach(() => { cleanup(); useAppStore.setState(initial, true); vi.unstubAllGlobals(); vi.restoreAllMocks() })

it('seeds both rails from the library and hides titles already owned', async () => {
  render(<Discover />)
  await waitFor(() => expect(cards('Aliens').length).toBeGreaterThan(0))
  expect(fetchRecommendations).toHaveBeenCalledWith(348, 'movie')
  // More Starring defaults to the alphabetically first library cast member.
  expect(fetchPersonCredits).toHaveBeenCalledWith(1158)
  await waitFor(() => expect(cards('The Godfather').length).toBeGreaterThan(0))
  expect(cards('Heat')).toHaveLength(0)
})

it('reseeds a rail from the dropdown choice', async () => {
  render(<Discover />)
  await waitFor(() => expect(cards('Aliens').length).toBeGreaterThan(0))
  fireEvent.click(screen.getByRole('button', { name: 'Choose a title to base recommendations on' }))
  await act(async () => fireEvent.click(screen.getByRole('option', { name: 'Heat' })))
  await waitFor(() => expect(cards('Collateral').length).toBeGreaterThan(0))
  expect(fetchRecommendations).toHaveBeenLastCalledWith(949, 'movie')
  expect(cards('Aliens')).toHaveLength(0)

  fireEvent.click(screen.getByRole('button', { name: 'Choose an actor to see more of their titles' }))
  await act(async () => fireEvent.click(screen.getByRole('option', { name: 'Sigourney Weaver' })))
  await waitFor(() => expect(cards('Gorillas in the Mist').length).toBeGreaterThan(0))
  expect(cards('The Godfather')).toHaveLength(0)
})

it('filters rail results by the active format without refetching', async () => {
  render(<Discover />)
  await waitFor(() => expect(cards('Aliens').length).toBeGreaterThan(0))
  expect(cards('Alien: Earth').length).toBeGreaterThan(0)
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'TV Shows' })))
  await waitFor(() => expect(cards('Aliens')).toHaveLength(0))
  expect(cards('Alien: Earth').length).toBeGreaterThan(0)
  expect(fetchRecommendations).toHaveBeenCalledTimes(1)
})

it('explains an unseedable basis instead of fetching for it', async () => {
  useAppStore.setState({ titles: [title('Home Movie', 0, [])] })
  render(<Discover />)
  expect(await screen.findByText('No recommendations found for this title — try picking another.')).toBeTruthy()
  expect(fetchRecommendations).not.toHaveBeenCalled()
  expect(screen.queryByText('More Starring')).toBeNull()
})
