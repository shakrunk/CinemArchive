import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import type { CinemaOuting, Title } from 'src/store/mockData'
import * as keepsakes from 'src/lib/keepsakes'
import { KeepsakeButton } from './OutingKeepsakes'

// Replace and interrupted-upload recovery in the album (#394).

vi.mock('src/lib/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/auth')>()),
  isSupabaseConfigured: true,
}))
vi.mock('src/lib/keepsakes', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/keepsakes')>()),
  listKeepsakes: vi.fn(),
  addKeepsake: vi.fn(),
  replaceKeepsake: vi.fn(),
  finishKeepsake: vi.fn(),
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
const pendingId = '40000000-0000-4000-8000-0000000000aa'
const pending: keepsakes.PendingKeepsake = { id: pendingId, outingId, mimeType: 'image/jpeg', byteLength: 4 }
const prepared = new Blob([new Uint8Array(4)], { type: 'image/jpeg' })
const pick = () => new File(['x'], 'stub.png', { type: 'image/png' })

const initialState = useAppStore.getState()
beforeEach(() => {
  localStorage.clear()
  useAppStore.setState({ titles: [title], outings: [outing], user: { id: owner, email: 'a@b.test' } as never })
  vi.mocked(keepsakes.keepsakeUrl).mockResolvedValue('https://signed.example/photo')
  vi.mocked(keepsakes.prepareKeepsake).mockResolvedValue(prepared)
})
afterEach(() => {
  cleanup()
  useAppStore.setState(initialState, true)
  vi.clearAllMocks()
  localStorage.clear()
})

async function openAlbum() {
  render(<KeepsakeButton outingId={outingId} />)
  const user = userEvent.setup()
  await user.click(screen.getByRole('button', { name: 'Photos' }))
  return user
}

it('replaces a photo and swaps it in the album', async () => {
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue([keepsake(first), keepsake(second)])
  vi.mocked(keepsakes.replaceKeepsake).mockResolvedValue({ saved: keepsake(pendingId), oldRemoved: true })
  const user = await openAlbum()
  await screen.findAllByRole('img', { name: 'Keepsake photo' })
  await user.upload(screen.getAllByLabelText('Replace photo')[0], pick())
  expect(keepsakes.replaceKeepsake).toHaveBeenCalledWith(owner, keepsake(first), prepared, 2)
  await vi.waitFor(() => expect(screen.getAllByRole('img', { name: 'Keepsake photo' })).toHaveLength(2))
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('says the original is unchanged when a replacement fails', async () => {
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue([keepsake(first)])
  vi.mocked(keepsakes.replaceKeepsake).mockRejectedValue(new Error('storage down'))
  const user = await openAlbum()
  await screen.findByRole('img', { name: 'Keepsake photo' })
  await user.upload(screen.getByLabelText('Replace photo'), pick())
  expect(await screen.findByRole('alert')).toHaveTextContent('storage down Your original photo is unchanged.')
  expect(screen.getAllByRole('img', { name: 'Keepsake photo' })).toHaveLength(1)
})

it('says the original was removed, and drops it from the album, when it could not be put back', async () => {
  localStorage.setItem(`cinemarchive:keepsake-pending:v1:${owner}`, JSON.stringify([pending]))
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue([keepsake(first)])
  const failure = new keepsakes.KeepsakeSaveError('offline', pending)
  failure.originalRemoved = true
  vi.mocked(keepsakes.replaceKeepsake).mockRejectedValue(failure)
  const user = await openAlbum()
  await screen.findByRole('img', { name: 'Keepsake photo' })
  await user.upload(screen.getByLabelText('Replace photo'), pick())
  expect(await screen.findByRole('alert')).toHaveTextContent('Your original photo was removed to make room')
  expect(screen.queryByRole('img', { name: 'Keepsake photo' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Finish saving' })).toBeInTheDocument()
})

it('keeps both photos and says so when the old one could not be removed', async () => {
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue([keepsake(first)])
  vi.mocked(keepsakes.replaceKeepsake).mockResolvedValue({ saved: keepsake(pendingId), oldRemoved: false })
  const user = await openAlbum()
  await screen.findByRole('img', { name: 'Keepsake photo' })
  await user.upload(screen.getByLabelText('Replace photo'), pick())
  expect(await screen.findByRole('alert')).toHaveTextContent("couldn't be removed")
  expect(screen.getAllByRole('img', { name: 'Keepsake photo' })).toHaveLength(2)
})

it('offers to finish saving an uploaded photo that never got its row, and saves it', async () => {
  localStorage.setItem(`cinemarchive:keepsake-pending:v1:${owner}`, JSON.stringify([pending]))
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue([])
  vi.mocked(keepsakes.finishKeepsake).mockResolvedValue(keepsake(pendingId))
  const user = await openAlbum()
  expect(await screen.findByText("A photo uploaded but wasn't saved to this trip.")).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Finish saving' }))
  expect(keepsakes.finishKeepsake).toHaveBeenCalledWith(owner, pending)
  expect(await screen.findByRole('img', { name: 'Keepsake photo' })).toBeInTheDocument()
})

it('lets an unsaved photo be discarded', async () => {
  localStorage.setItem(`cinemarchive:keepsake-pending:v1:${owner}`, JSON.stringify([pending]))
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue([])
  const user = await openAlbum()
  await user.click(await screen.findByRole('button', { name: 'Discard' }))
  expect(screen.queryByText("A photo uploaded but wasn't saved to this trip.")).not.toBeInTheDocument()
  expect(keepsakes.pendingKeepsakes(owner, outingId)).toEqual([])
})

it('forgets a pending photo that turns out to have a row already', async () => {
  localStorage.setItem(`cinemarchive:keepsake-pending:v1:${owner}`, JSON.stringify([pending]))
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue([keepsake(pendingId)])
  await openAlbum()
  await screen.findByRole('img', { name: 'Keepsake photo' })
  expect(screen.queryByRole('button', { name: 'Finish saving' })).not.toBeInTheDocument()
  expect(keepsakes.pendingKeepsakes(owner, outingId)).toEqual([])
})

it('surfaces a failed finish and keeps the photo pending for another try', async () => {
  localStorage.setItem(`cinemarchive:keepsake-pending:v1:${owner}`, JSON.stringify([pending]))
  vi.mocked(keepsakes.listKeepsakes).mockResolvedValue([])
  vi.mocked(keepsakes.finishKeepsake).mockRejectedValue(new Error('offline'))
  const user = await openAlbum()
  await user.click(await screen.findByRole('button', { name: 'Finish saving' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('offline')
  expect(screen.getByRole('button', { name: 'Finish saving' })).toBeInTheDocument()
})
