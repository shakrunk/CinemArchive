import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import type { CinemaOuting, Title } from 'src/store/mockData'
import * as keepsakes from 'src/lib/keepsakes'
import { KeepsakeButton } from './OutingKeepsakes'

vi.mock('src/lib/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/auth')>()),
  isSupabaseConfigured: true,
}))
vi.mock('src/lib/keepsakes', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/keepsakes')>()),
  listKeepsakes: vi.fn(),
  addKeepsake: vi.fn(),
  removeKeepsake: vi.fn(),
  keepsakeUrl: vi.fn(),
  prepareKeepsake: vi.fn(),
}))

const owner = '10000000-0000-4000-8000-000000000001'
const outingId = '30000000-0000-4000-8000-000000000001'
const title: Title = {
  id: 'film', tmdbId: 1, type: 'movie', title: 'Cinema film', year: 2026, runtime: 100,
  genres: [], tags: [], status: 'watchlist', addedAt: '2026-01-01', viewings: [],
}
const outing: CinemaOuting = {
  id: outingId, titleId: 'film', venue: 'Palace', format: 'IMAX',
  showtime: '2099-07-01T19:00:00Z', endsAt: '2099-07-01T21:00:00Z',
  previewsMinutes: 20, runtimeMinutes: 100, companions: [], seats: [],
  status: 'scheduled', createdAt: '2026-07-01',
}
const keepsake = (id: string): keepsakes.OutingKeepsake => ({ id, outingId, mimeType: 'image/jpeg', byteLength: 4, createdAt: '2026-10-30T10:00:00Z' })
const first = '40000000-0000-4000-8000-000000000001'
const second = '40000000-0000-4000-8000-000000000002'

const initialState = useAppStore.getState()
beforeEach(() => {
  useAppStore.setState({ titles: [title], outings: [outing], user: { id: owner, email: 'a@b.test' } as never })
  vi.mocked(keepsakes.keepsakeUrl).mockResolvedValue('https://signed.example/photo')
})
afterEach(() => {
  cleanup()
  useAppStore.setState(initialState, true)
  vi.clearAllMocks()
})

async function openAlbum() {
  render(<KeepsakeButton outingId={outingId} />)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Photos' }))
  return user
}

it('lists the trip\'s photos in a private album', async () => {
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue([keepsake(first), keepsake(second)])
  await openAlbum()
  expect(await screen.findAllByRole('img', { name: 'Keepsake photo' })).toHaveLength(2)
  expect(keepsakes.listKeepsakes).toHaveBeenCalledWith(outingId)
})

it('prepares and adds a chosen photo', async () => {
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue([])
  const prepared = new Blob([new Uint8Array(4)], { type: 'image/jpeg' })
  vi.mocked(keepsakes.prepareKeepsake).mockResolvedValue(prepared)
  vi.mocked(keepsakes.addKeepsake).mockResolvedValue(keepsake(first))
  const user = await openAlbum()
  expect(await screen.findByText('No photos yet.')).toBeInTheDocument()
  await user.upload(screen.getByLabelText('Add photo'), new File(['x'], 'stub.png', { type: 'image/png' }))
  expect(await screen.findByRole('img', { name: 'Keepsake photo' })).toBeInTheDocument()
  expect(keepsakes.addKeepsake).toHaveBeenCalledWith(owner, outingId, prepared)
})

it('confirms before removing a photo', async () => {
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue([keepsake(first)])
  vi.mocked(keepsakes.removeKeepsake).mockResolvedValue()
  const user = await openAlbum()
  await user.click(await screen.findByRole('button', { name: 'Remove photo' }))
  expect(keepsakes.removeKeepsake).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: 'Confirm removal' }))
  expect(keepsakes.removeKeepsake).toHaveBeenCalledWith(owner, first)
  expect(await screen.findByText('No photos yet.')).toBeInTheDocument()
})

it('stops adding at eight photos', async () => {
  const eight = Array.from({ length: 8 }, (_, i) => keepsake(`40000000-0000-4000-8000-00000000000${i + 1}`))
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue(eight)
  await openAlbum()
  expect(await screen.findByText(/keeps up to 8 photos/)).toBeInTheDocument()
  expect(screen.getByLabelText('Add photo')).toBeDisabled()
})

it('shows a retry when the album cannot be loaded', async () => {
  vi.mocked(keepsakes.listKeepsakes).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce([keepsake(first)])
  const user = await openAlbum()
  expect(await screen.findByRole('alert')).toHaveTextContent('offline')
  await user.click(screen.getByRole('button', { name: 'Try again' }))
  expect(await screen.findByRole('img', { name: 'Keepsake photo' })).toBeInTheDocument()
})

it('offers no photos entry without an account or for an unknown outing', () => {
  useAppStore.setState({ user: null })
  const { container, rerender } = render(<KeepsakeButton outingId={outingId} />)
  expect(container).toBeEmptyDOMElement()
  act(() => useAppStore.setState({ user: { id: owner, email: 'a@b.test' } as never }))
  rerender(<KeepsakeButton outingId="missing" />)
  expect(container).toBeEmptyDOMElement()
})
