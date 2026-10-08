import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import type { CinemaOuting, Title } from 'src/store/mockData'
import { UpNext } from './UpNext'

const initial = useAppStore.getState()
const title: Title = {
  id: 'memory-title', tmdbId: 1, type: 'movie', title: 'Anniversary film', year: 2025,
  genres: [], tags: [], status: 'watched', addedAt: '2025-01-01',
  viewings: [{ id: 'memory-viewing', titleId: 'memory-title', rating: 4.5, notes: 'A wonderful crowd' }],
}
const outing: CinemaOuting = {
  id: 'memory-outing', titleId: title.id, status: 'completed', completedViewingId: 'memory-viewing',
  showtime: new Date(2025, 9, 8, 20).toISOString(), endsAt: new Date(2025, 9, 8, 22).toISOString(),
  previewsMinutes: 0, runtimeMinutes: 120, seats: [], venue: 'Private cinema',
  companions: [{ name: 'Sam' }], createdAt: '2025-10-01',
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date(2026, 9, 8, 12))
  useAppStore.setState({ titles: [title], outings: [outing], isSharedView: false, viewerContext: { kind: 'owner' }, selectedTitleId: null })
})

afterEach(() => {
  cleanup()
  useAppStore.setState(initial, true)
  vi.useRealTimers()
})

it('renders an anniversary as content and opens its title with the linked viewing details', () => {
  render(<UpNext onBrowseLibrary={vi.fn()} />)
  expect(screen.getByRole('heading', { name: 'On this day' })).toBeInTheDocument()
  expect(screen.getByText('1 year ago today')).toBeInTheDocument()
  expect(screen.getByText('Private cinema')).toBeInTheDocument()
  expect(screen.getByText('With Sam')).toBeInTheDocument()
  expect(screen.getByLabelText('Rated 4.5 out of 5 stars')).toBeInTheDocument()
  expect(screen.getByText('“A wonderful crowd”')).toBeInTheDocument()
  expect(screen.queryByText('Nothing in progress.')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Open Anniversary film' }))
  expect(useAppStore.getState().selectedTitleId).toBe(title.id)
})

it.each(['shared-link', 'friend'] as const)('never displays owner-only memories in %s views even with stale outings', (kind) => {
  useAppStore.setState({ viewerContext: kind === 'friend'
    ? { kind, userId: 'friend', displayName: 'Friend' }
    : { kind, token: 'shared-token' },
  })
  render(<UpNext onBrowseLibrary={vi.fn()} />)
  expect(screen.queryByRole('heading', { name: 'On this day' })).not.toBeInTheDocument()
  expect(screen.queryByText('Private cinema')).not.toBeInTheDocument()
  expect(screen.queryByText('With Sam')).not.toBeInTheDocument()
  expect(screen.queryByText('“A wonderful crowd”')).not.toBeInTheDocument()
})

it('removes yesterday’s memories when the existing clock tick crosses local midnight', () => {
  vi.setSystemTime(new Date(2026, 9, 8, 23, 59, 30))
  render(<UpNext onBrowseLibrary={vi.fn()} />)
  expect(screen.getByText('1 year ago today')).toBeInTheDocument()
  act(() => { vi.advanceTimersByTime(60_000) })
  expect(screen.queryByText('1 year ago today')).not.toBeInTheDocument()
})
