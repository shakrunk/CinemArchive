import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { User } from '@supabase/supabase-js'
import { RefreshMetadataModal } from './RefreshMetadataModal'
import { useAppStore } from 'src/store/useAppStore'
import { fetchRefreshedTitlePatch } from 'src/lib/refreshMetadata'
import { updateTitleInDb } from 'src/lib/db'
import type { Title } from 'src/store/mockData'

vi.mock('src/lib/refreshMetadata', async (original) => ({
  ...await original<typeof import('src/lib/refreshMetadata')>(),
  fetchRefreshedTitlePatch: vi.fn(),
}))
vi.mock('src/lib/db', async (original) => ({
  ...await original<typeof import('src/lib/db')>(),
  updateTitleInDb: vi.fn(),
}))

const title: Title = {
  id: 'metadata-title', tmdbId: 42, type: 'movie', title: 'A film', year: 2026,
  genres: [], tags: [], status: 'watched', rating: 4, notes: 'Keep this',
  addedAt: '2026-01-01', viewings: [], synopsis: 'Old synopsis',
}
const patch = { synopsis: 'Fresh synopsis', cast: [{ tmdbPersonId: 1, name: 'Updated cast', order: 0 }] }

beforeEach(() => {
  vi.mocked(fetchRefreshedTitlePatch).mockResolvedValue(patch)
  vi.mocked(updateTitleInDb).mockResolvedValue(undefined)
  useAppStore.setState({
    user: { id: 'metadata-owner' } as User, titles: [title], selectedTitleId: title.id,
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
  expect(updateTitleInDb).toHaveBeenCalledWith('metadata-owner', title.id, patch)
  expect(useAppStore.getState().titles[0]).toMatchObject({ rating: 4, notes: 'Keep this' })
  expect(useAppStore.getState().isRefreshMetadataOpen).toBe(false)
  expect(screen.queryByText('Could not fetch fresh metadata. Please try again.')).not.toBeInTheDocument()
  expect(useAppStore.getState().notifications.filter((n) => n.dedupeKey === 'browser-cache-unavailable')).toHaveLength(1)
})

it('keeps a real fetch failure visible and leaves the original metadata intact', async () => {
  vi.mocked(fetchRefreshedTitlePatch).mockRejectedValue(new Error('Service unavailable'))
  render(<RefreshMetadataModal />)
  fireEvent.click(screen.getByRole('button', { name: 'Re-fetch current match' }))
  expect(await screen.findByText('Could not fetch fresh metadata. Please try again.')).toBeInTheDocument()
  expect(useAppStore.getState().titles[0].synopsis).toBe('Old synopsis')
  expect(updateTitleInDb).not.toHaveBeenCalled()
})

it('reports a remote save failure separately from fetching metadata', async () => {
  vi.mocked(updateTitleInDb).mockRejectedValue(new Error('Offline'))
  render(<RefreshMetadataModal />)
  fireEvent.click(screen.getByRole('button', { name: 'Re-fetch current match' }))
  await waitFor(() => expect(useAppStore.getState().isRefreshMetadataOpen).toBe(false))
  expect(useAppStore.getState().notifications.some((n) => n.message.includes("Couldn't save changes"))).toBe(true)
})
