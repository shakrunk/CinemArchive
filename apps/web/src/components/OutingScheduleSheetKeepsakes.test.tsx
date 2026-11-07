import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import type { CinemaOuting, Title } from 'src/store/mockData'
import { OutingScheduleSheet } from './OutingScheduleSheet'

// Keepsake photos can be attached from the schedule flow (#394): while editing an outing and on
// the "Tickets saved" step (not covered here: it needs a submit that depends on the clock), beside the ticket button.

vi.mock('src/lib/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/auth')>()),
  isSupabaseConfigured: true,
  listFriendships: vi.fn().mockResolvedValue([]),
}))

const owner = '10000000-0000-4000-8000-000000000001'
const title: Title = {
  id: 'film', tmdbId: 1, type: 'movie', title: 'Cinema film', year: 2026, runtime: 100,
  genres: [], tags: [], status: 'watchlist', addedAt: '2026-01-01', viewings: [],
}
const outing: CinemaOuting = {
  id: 'o1', titleId: 'film', venue: 'Palace', format: 'IMAX',
  showtime: '2099-07-01T19:00:00Z', endsAt: '2099-07-01T21:00:00Z',
  previewsMinutes: 20, runtimeMinutes: 100, companions: [], seats: [],
  status: 'scheduled', createdAt: '2026-07-01',
}

const initialState = useAppStore.getState()
afterEach(() => { cleanup(); useAppStore.setState(initialState, true) })

it('offers the Photos entry beside the ticket button when editing an outing', async () => {
  useAppStore.setState({ titles: [title], outings: [outing], user: { id: owner, email: 'a@b.test' } as never })
  render(<OutingScheduleSheet />)
  act(() => { useAppStore.getState().openOutingSchedule('film', 'o1') })
  expect(await screen.findByRole('button', { name: 'Ticket & seats' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Photos' })).toBeInTheDocument()
})

it('offers no Photos entry when scheduling a new outing, which has nothing to attach to yet', async () => {
  useAppStore.setState({ titles: [title], outings: [], user: { id: owner, email: 'a@b.test' } as never })
  render(<OutingScheduleSheet />)
  act(() => { useAppStore.getState().openOutingSchedule('film') })
  expect(await screen.findByLabelText('Theater')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Photos' })).not.toBeInTheDocument()
})

it('offers no Photos entry without an account', async () => {
  useAppStore.setState({ titles: [title], outings: [outing], user: null })
  render(<OutingScheduleSheet />)
  act(() => { useAppStore.getState().openOutingSchedule('film', 'o1') })
  expect(await screen.findByRole('button', { name: 'Ticket & seats' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Photos' })).not.toBeInTheDocument()
})
