import 'fake-indexeddb/auto'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { RefreshMetadataModal } from './RefreshMetadataModal'
import { useAppStore } from 'src/store/useAppStore'
import { fetchRefreshedTitlePatch } from 'src/lib/refreshMetadata'
import { IndexedDbOfflineStore } from 'src/lib/offline/storage'
import type { Title } from 'src/store/mockData'

vi.mock('src/lib/refreshMetadata', async (original) => ({
  ...await original<typeof import('src/lib/refreshMetadata')>(),
  fetchRefreshedTitlePatch: vi.fn(),
}))

const title: Title = {
  id: 'metadata-title', tmdbId: 42, type: 'movie', title: 'A film', year: 2026,
  genres: [], tags: [], status: 'watched', rating: 4, notes: 'Keep this',
  addedAt: '2026-01-01', viewings: [], synopsis: 'Old synopsis',
}
const patch = { synopsis: 'Fresh synopsis', cast: [{ tmdbPersonId: 1, name: 'Updated cast', order: 0 }] }

beforeEach(async () => {
  vi.mocked(fetchRefreshedTitlePatch).mockResolvedValue(patch)
  useAppStore.getState().setUser(null)
  await vi.waitFor(() => expect(useAppStore.getState().offlineStatus.hydrated).toBe(true))
  await useAppStore.getState().setTitles([])
  await useAppStore.getState().addTitle(title)
  useAppStore.setState({
    selectedTitleId: title.id,
    isRefreshMetadataOpen: true, notifications: [],
  })
  vi.spyOn(console, 'error').mockImplementation(() => {})
  vi.spyOn(console, 'warn').mockImplementation(() => {})
})
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.clearAllMocks(); useAppStore.setState({ user: null }) })

it('closes after applying fresh metadata even when the browser cache is full', async () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('The quota has been exceeded.', 'QuotaExceededError')
  })
  render(<RefreshMetadataModal />)
  fireEvent.click(screen.getByRole('button', { name: 'Re-fetch current match' }))
  await waitFor(() => expect(useAppStore.getState().titles[0].synopsis).toBe('Fresh synopsis'))
  expect(useAppStore.getState().titles[0]).toMatchObject({ rating: 4, notes: 'Keep this' })
  expect(useAppStore.getState().isRefreshMetadataOpen).toBe(false)
  expect(screen.queryByText('Could not fetch fresh metadata. Please try again.')).not.toBeInTheDocument()
  expect(useAppStore.getState().notifications.filter((n) => n.dedupeKey === 'browser-cache-unavailable')).toHaveLength(1)
})

it('keeps a real fetch failure visible and leaves the original metadata intact', async () => {
  vi.mocked(fetchRefreshedTitlePatch).mockRejectedValue(new Error('Service unavailable'))
  render(<RefreshMetadataModal />)
  fireEvent.click(screen.getByRole('button', { name: 'Re-fetch current match' }))
  expect(await screen.findByText('Service unavailable')).toBeInTheDocument()
  expect(useAppStore.getState().titles[0].synopsis).toBe('Old synopsis')
})

it('keeps the form and original metadata when durable storage fails', async () => {
  vi.spyOn(IndexedDbOfflineStore.prototype, 'applyLocal').mockRejectedValueOnce(new Error('Storage quota exceeded'))
  render(<RefreshMetadataModal />)
  fireEvent.click(screen.getByRole('button', { name: 'Re-fetch current match' }))
  expect(await screen.findByText('Storage quota exceeded')).toBeInTheDocument()
  expect(useAppStore.getState().isRefreshMetadataOpen).toBe(true)
  expect(useAppStore.getState().titles[0].synopsis).toBe('Old synopsis')
})
