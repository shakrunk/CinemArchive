import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import type { Title } from 'src/store/mockData'
import { ActivityHeatmap } from './ActivityHeatmap'
import { TheRun } from './TheRun'
import { DecadeFilmstrip } from './DecadeFilmstrip'
import { SecondOpinions } from './SecondOpinions'
import { ScreeningNights } from './ScreeningNights'
import { ShiftingStandards } from './ShiftingStandards'
import { PremieresRevivals } from './PremieresRevivals'
import { TheMarathon } from './TheMarathon'

const initial = useAppStore.getState()
const film: Title = {
  id: 'film', tmdbId: 1, type: 'movie', title: 'Fixture film', year: 2000,
  genres: ['Drama'], tags: [], status: 'watched', addedAt: '2026-01-01', rating: 4, imdbRating: 7,
  viewings: [
    { id: 'first', titleId: 'film', date: '2026-10-01', rating: 4 },
    { id: 'second', titleId: 'film', date: '2026-10-03', rating: 4 },
  ],
}
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-08T18:00:00Z'))
  useAppStore.setState({ titles: [film], librarySession: 1 })
})
afterEach(() => { cleanup(); useAppStore.setState(initial, true); vi.useRealTimers() })

it.each([
  [ActivityHeatmap, 'Time in the dark', '2026-10-01', ['2026-10-01', '1']],
  [TheRun, 'The run', '2026-10', ['2026-10', '2']],
  [DecadeFilmstrip, 'By the era', '2000s', ['2000s', '1']],
  [SecondOpinions, 'Second opinions', 'Fixture film', ['Fixture film', '8.0', '7.0', '1.0']],
  [ScreeningNights, 'Screening nights', 'Thu', ['Thu', '1']],
  [ShiftingStandards, 'Shifting standards', 'Q4', null],
  [PremieresRevivals, 'Premieres & revivals', '2026-10', ['2026-10', '1', '1']],
  [TheMarathon, 'The marathon', '2026-10-03', ['2026-10-03', 'Yes']],
] as const)('chart %#: exposes complete values in a semantic table', (Component, title, label, expected) => {
  render(<Component width="sm" />)
  fireEvent.click(screen.getByRole('button', { name: `View data for ${title}` }))
  const dialog = screen.getByRole('dialog', { name: `${title} data` })
  const table = within(dialog).getByRole('table')
  const row = within(table).getAllByRole('row').find((candidate) => candidate.textContent?.includes(label))!
  expect(row).toBeDefined()
  const values = [...row.querySelectorAll('th,td')].map((cell) => cell.textContent)
  if (expected) expect(values).toEqual(expected)
  else expect(values.slice(1)).toEqual(['4.0', '1'])
  expect(within(table).getAllByRole('columnheader').length).toBe(values.length)
  expect(within(row).getByRole('rowheader')).toHaveTextContent(label)
})

it('includes zero-count months and all dates hidden by chart label thinning', () => {
  render(<TheRun width="sm" settings={{ timeRange: 'all' }} />)
  fireEvent.click(screen.getByRole('button', { name: 'View data for The run' }))
  expect(screen.getByRole('table')).toHaveTextContent('2026-09')
  const september = screen.getByRole('rowheader', { name: '2026-09' }).closest('tr')!
  expect(within(september).getByRole('cell')).toHaveTextContent('0')
})

it('uses the active movie or TV scope and closes private data on account transition', () => {
  useAppStore.setState({ titles: [film, { ...film, id: 'tv', type: 'tv', title: 'Private series', year: 1990 }] })
  const view = render(<DecadeFilmstrip settings={{ scope: 'movies', title: 'My eras' }} />)
  fireEvent.click(screen.getByRole('button', { name: 'View data for My eras' }))
  expect(screen.getByRole('table')).toHaveTextContent('2000s')
  expect(screen.getByRole('table')).not.toHaveTextContent('1990s')
  act(() => useAppStore.setState({ librarySession: 2, titles: [] }))
  view.rerender(<DecadeFilmstrip settings={{ scope: 'movies', title: 'My eras' }} />)
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

it('keeps every decade filter accessible when visible axis labels are thinned', () => {
  useAppStore.setState({ titles: Array.from({ length: 12 }, (_, index) => ({ ...film, id: String(index), year: 1900 + index * 10 })) })
  render(<DecadeFilmstrip width="sm" />)
  expect(screen.getByRole('button', { name: '1910s: 1 titles' })).toBeInTheDocument()
})
