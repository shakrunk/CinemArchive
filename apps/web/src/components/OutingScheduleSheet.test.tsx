import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import type { CinemaOuting, Title } from 'src/store/mockData'
import { useMoviegoingPrefs } from 'src/lib/moviegoingPrefsSync'
import type { OutingTemplate } from 'src/store/outingTemplates'
import { OutingScheduleSheet } from './OutingScheduleSheet'

vi.mock('src/lib/auth', async (importOriginal) => ({
  ...(await importOriginal<typeof import('src/lib/auth')>()),
  listFriendships: vi.fn().mockResolvedValue([]),
}))

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
const matinee: OutingTemplate = {
  id: 't1', name: 'Tuesday matinee', venue: 'AMC Georgetown', companions: ['Sam'], format: 'IMAX', weekday: 2, time: '14:00',
}

const initialState = useAppStore.getState()
afterEach(() => {
  cleanup()
  useAppStore.setState(initialState, true)
  useMoviegoingPrefs.setState({ prefs: null })
})

function open(templates: OutingTemplate[] = [], editing = false) {
  useAppStore.setState({ titles: [title], outings: editing ? [outing] : [] })
  useMoviegoingPrefs.setState({
    prefs: templates.length ? { budget: null, plan: null, templates, updatedAt: '2026-10-29T10:00:00Z', raw: {} } : null,
  })
  render(<OutingScheduleSheet />)
  act(() => { useAppStore.getState().openOutingSchedule('film', editing ? 'o1' : undefined) })
}

it('fills the form from a template, landing on its weekday and time', async () => {
  open([matinee])
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Use template Tuesday matinee' }))
  expect(screen.getByLabelText('Theater')).toHaveValue('AMC Georgetown')
  expect(screen.getByLabelText('Showtime')).toHaveValue('14:00')
  const date = (screen.getByLabelText('Date') as HTMLInputElement).value
  expect(new Date(`${date}T00:00:00`).getDay()).toBe(2)
  expect(new Date(`${date}T14:00:00`).getTime()).toBeGreaterThan(Date.now())
  expect(screen.getByRole('button', { name: 'Remove Sam' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'IMAX' })).toHaveClass('text-amber')
})

it('saves the form as a template with its venue, company, format, weekday and time', async () => {
  open()
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('Theater'), 'Alamo')
  await user.type(screen.getByLabelText('Add a companion'), 'Alex{Enter}')
  await user.click(screen.getByRole('button', { name: 'Dolby' }))
  await user.click(screen.getByRole('button', { name: 'Save as template' }))
  const save = screen.getByRole('button', { name: 'Save template' })
  expect(save).toBeDisabled()
  await user.type(screen.getByLabelText('Template name'), 'Date night')
  await user.click(save)
  const [saved] = useMoviegoingPrefs.getState().prefs?.templates ?? []
  const date = (screen.getByLabelText('Date') as HTMLInputElement).value
  expect(saved).toMatchObject({
    name: 'Date night', venue: 'Alamo', companions: ['Alex'], format: 'Dolby',
    weekday: new Date(`${date}T00:00:00`).getDay() || 7, time: '19:00',
  })
  expect(await screen.findByRole('button', { name: 'Use template Date night' })).toBeInTheDocument()
})

it('deletes a template', async () => {
  open([matinee])
  const user = userEvent.setup()
  await user.click(await screen.findByRole('button', { name: 'Delete template Tuesday matinee' }))
  expect(useMoviegoingPrefs.getState().prefs?.templates).toEqual([])
  expect(screen.queryByRole('button', { name: 'Use template Tuesday matinee' })).not.toBeInTheDocument()
})

it('offers no templates when editing an existing outing', async () => {
  open([matinee], true)
  expect(await screen.findByLabelText('Theater')).toHaveValue('Palace')
  expect(screen.queryByRole('button', { name: 'Use template Tuesday matinee' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Save as template' })).not.toBeInTheDocument()
})
