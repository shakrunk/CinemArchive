import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { TitleDetailDrawer } from './TitleDetailDrawer'
import { fetchSeasonDetails, fetchTitleImages, fetchTitleVideos, fetchWatchProviders } from 'src/lib/media'
import { listFriendships } from 'src/lib/auth'
import { useAppStore } from 'src/store/useAppStore'
import { SPIDER_NOIR_TMDB_ID, THE_MATRIX_TMDB_ID } from 'src/lib/easterEggThemes'
import type { Season, Title } from 'src/store/mockData'

vi.mock('src/lib/media', async (original) => ({ ...await original<typeof import('src/lib/media')>(),
  fetchTitleVideos: vi.fn(), fetchWatchProviders: vi.fn(), fetchTitleImages: vi.fn(), fetchSeasonDetails: vi.fn(),
}))
vi.mock('src/lib/auth', async (original) => ({ ...await original<typeof import('src/lib/auth')>(), listFriendships: vi.fn() }))
vi.mock('src/components/TitleCommentsPanel', () => ({ TitleCommentsPanel: () => null }))
vi.mock('src/components/MatrixDigitalRain', () => ({ MatrixDigitalRain: () => <div>digital rain</div> }))
vi.mock('src/components/SpiderWebOverlay', () => ({ default: () => null }))

const initial = useAppStore.getState()
const actions = {
  addViewing: vi.fn(), removeTitle: vi.fn(), updateTitle: vi.fn(), updateTitleMetadata: vi.fn(),
  closeDetailDrawer: vi.fn(), unlockTheme: vi.fn(), setPinnedMode: vi.fn(),
}
const movie = (over: Partial<Title> = {}): Title => ({
  id: 'heat', tmdbId: 949, type: 'movie', title: 'Heat', year: 1995, genres: ['Crime'], tags: [],
  status: 'watchlist', addedAt: '2026-01-01', viewings: [], synopsis: 'A heist goes wrong.', ...over,
})
const season = (over: Partial<Season> = {}): Season => ({ id: 's1', seasonNumber: 1, episodeCount: 2, episodesWatched: 0, ...over })
const show = (over: Partial<Title> = {}): Title => ({ ...movie(), id: 'show', tmdbId: 1399, type: 'tv', title: 'A Show', seasons: [season({ episodes: [] })], ...over })
const today = () => new Date().toISOString().slice(0, 10)
const open = (titles: Title[], selectedTitleId = titles[0].id) =>
  useAppStore.setState({ titles, selectedTitleId, isDetailDrawerOpen: true })

beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })))
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} })
  vi.mocked(fetchTitleVideos).mockResolvedValue([])
  vi.mocked(fetchWatchProviders).mockResolvedValue(null)
  vi.mocked(fetchTitleImages).mockResolvedValue({ logoUrl: null, backdropUrl: null })
  vi.mocked(fetchSeasonDetails).mockResolvedValue({ episodes: [], cast: [] })
  vi.mocked(listFriendships).mockResolvedValue([])
  for (const action of Object.values(actions)) action.mockResolvedValue(undefined)
  useAppStore.setState({
    ...actions, user: null, isSharedView: false, viewerContext: { kind: 'owner' },
    outings: [], lists: [], listMemberships: {}, pinnedModes: {},
  })
})
afterEach(() => {
  cleanup()
  useAppStore.setState(initial, true)
  document.body.className = ''
  vi.unstubAllGlobals()
})

it('fetches media for the open title and shows its fetched logo', async () => {
  vi.mocked(fetchTitleImages).mockResolvedValue({ logoUrl: 'https://img/logo.png', backdropUrl: 'https://img/backdrop.jpg' })
  open([movie()])
  render(<TitleDetailDrawer />)
  await waitFor(() => expect(screen.getAllByRole('img', { name: 'Heat' }).some((img) => img.getAttribute('src') === 'https://img/logo.png')).toBe(true))
  expect(fetchTitleVideos).toHaveBeenCalledWith(949, 'movie')
  expect(fetchWatchProviders).toHaveBeenCalledWith(949, 'movie')
  expect(screen.getAllByText('A heist goes wrong.').length).toBeGreaterThan(0)
})

it('fetches nothing while the drawer is closed', async () => {
  useAppStore.setState({ titles: [movie()], selectedTitleId: 'heat', isDetailDrawerOpen: false })
  render(<TitleDetailDrawer />)
  await act(async () => {})
  expect(fetchTitleVideos).not.toHaveBeenCalled()
  expect(fetchWatchProviders).not.toHaveBeenCalled()
  expect(fetchTitleImages).not.toHaveBeenCalled()
})

it('logs a viewing, then closes and resets the form after the saved confirmation', async () => {
  open([movie()])
  render(<TitleDetailDrawer />)
  fireEvent.click(screen.getByRole('button', { name: 'Log a viewing' }))
  fireEvent.change(screen.getByLabelText('Notes'), { target: { value: 'Still great' } })
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Save Viewing' })))
  expect(actions.addViewing).toHaveBeenCalledWith(
    'heat',
    expect.objectContaining({ titleId: 'heat', date: today(), notes: 'Still great' }),
    { status: 'watched' },
  )
  expect(screen.getByRole('button', { name: 'Saved' })).toBeTruthy()
  await waitFor(() => expect(screen.queryByLabelText('Notes')).toBeNull(), { timeout: 3000 })
  fireEvent.click(screen.getByRole('button', { name: 'Log a viewing' }))
  expect((screen.getByLabelText('Notes') as HTMLTextAreaElement).value).toBe('')
})

it('keeps an unsaved log form when the drawer switches titles', async () => {
  open([movie(), movie({ id: 'thief', tmdbId: 11902, title: 'Thief', synopsis: 'A safecracker takes one last job.' })])
  render(<TitleDetailDrawer />)
  fireEvent.click(screen.getByRole('button', { name: 'Log a viewing' }))
  fireEvent.change(screen.getByLabelText('Notes'), { target: { value: 'half-written' } })
  await act(async () => useAppStore.setState({ selectedTitleId: 'thief' }))
  expect(screen.getAllByText('A safecracker takes one last job.').length).toBeGreaterThan(0)
  expect((screen.getByLabelText('Notes') as HTMLTextAreaElement).value).toBe('half-written')
})

it('removes the title only after the delete is confirmed', async () => {
  open([movie()])
  render(<TitleDetailDrawer />)
  fireEvent.click(screen.getByRole('button', { name: 'Remove from library' }))
  expect(actions.removeTitle).not.toHaveBeenCalled()
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Delete forever: title from library' })))
  expect(actions.removeTitle).toHaveBeenCalledWith('heat')
  expect(actions.closeDetailDrawer).toHaveBeenCalled()
})

it('backfills missing episode metadata once for the life of the drawer', async () => {
  vi.mocked(fetchSeasonDetails).mockResolvedValue({
    episodes: [{ episode_number: 1, name: 'Pilot' }, { episode_number: 2, name: 'Second' }],
    cast: [],
  } as Awaited<ReturnType<typeof fetchSeasonDetails>>)
  open([show(), movie()], 'show')
  render(<TitleDetailDrawer />)
  await waitFor(() => expect(actions.updateTitleMetadata).toHaveBeenCalledTimes(1))
  expect(fetchSeasonDetails).toHaveBeenCalledWith(1399, 1)
  const [titleId, patch] = actions.updateTitleMetadata.mock.calls[0]
  expect(titleId).toBe('show')
  expect(patch.seasons[0].episodes.map((ep: { episodeName?: string }) => ep.episodeName)).toEqual(['Pilot', 'Second'])

  // The store action is a stub, so the show still looks un-backfilled when it comes back.
  await act(async () => useAppStore.setState({ selectedTitleId: 'heat' }))
  await act(async () => useAppStore.setState({ selectedTitleId: 'show' }))
  expect(fetchSeasonDetails).toHaveBeenCalledTimes(1)
})

it('never backfills episodes in a shared view', async () => {
  useAppStore.setState({ isSharedView: true })
  open([show()])
  render(<TitleDetailDrawer />)
  await act(async () => {})
  expect(fetchSeasonDetails).not.toHaveBeenCalled()
})

it('applies a pinned Spider-Noir mode on open and drops it for Normal', async () => {
  const noir = show({
    id: 'noir', tmdbId: SPIDER_NOIR_TMDB_ID, title: 'Spider-Noir',
    seasons: [season({ cast: [{ tmdbPersonId: 1, name: 'Nic Cage', order: 0 }], episodes: [{
      id: 'e1', episodeNumber: 1, episodeName: 'Pilot',
      watchEvents: [{ id: 'w1', watchedAt: '2026-05-01', colorMode: 'bw' }], ratings: [], reviews: [],
    }] })],
  })
  useAppStore.setState({ pinnedModes: { 'noir:spider_noir_color': 'bw' } })
  open([noir])
  render(<TitleDetailDrawer />)
  await waitFor(() => expect(document.body.classList.contains('spider-noir-bw')).toBe(true))
  const bw = screen.getAllByRole('button', { name: /B&W/ }).find((b) => b.hasAttribute('aria-pressed'))
  expect(bw?.getAttribute('aria-pressed')).toBe('true')
  await act(async () => fireEvent.click(screen.getByRole('button', { name: /Normal/ })))
  expect(document.body.classList.contains('spider-noir-bw')).toBe(false)
  expect(actions.unlockTheme).toHaveBeenCalledWith('noir')
})

it('asks for a pill before logging The Matrix and unlocks its theme on red', async () => {
  open([movie({ id: 'matrix', tmdbId: THE_MATRIX_TMDB_ID, title: 'The Matrix' })])
  render(<TitleDetailDrawer />)
  fireEvent.click(screen.getByRole('button', { name: 'Log a viewing' }))
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Save Viewing' })))
  expect(actions.addViewing).not.toHaveBeenCalled()
  await act(async () => fireEvent.click(screen.getByRole('button', { name: /Red Pill/ })))
  expect(actions.addViewing).toHaveBeenCalledWith('matrix', expect.objectContaining({ titleId: 'matrix' }), { status: 'watched' })
  expect(await screen.findByText('digital rain')).toBeTruthy()
  expect(actions.unlockTheme).toHaveBeenCalledWith('matrix')
})
