import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import type { Title } from 'src/store/mockData'
import { RatingDistribution } from './RatingDistribution'

const titles: Title[] = [
  { id: 'a', title: 'Low film', rating: 1, type: 'movie' },
  { id: 'b', title: 'High film', rating: 3, type: 'movie' },
  { id: 'c', title: 'Low series', rating: 3, type: 'tv' },
  { id: 'd', title: 'High series', rating: 5, type: 'tv' },
].map((t) => ({ tmdbId: 1, year: 2026, genres: [], tags: [], status: 'watched', addedAt: '', viewings: [], ...t } as Title))

const initialState = useAppStore.getState()
afterEach(() => { cleanup(); useAppStore.setState(initialState, true) })

it('switches baselines, searches without recalibrating, and opens the selected title', () => {
  useAppStore.setState({ titles })
  render(<RatingDistribution />)
  fireEvent.click(screen.getByRole('button', { name: 'Normalized' }))
  const row = () => screen.getByRole('button', { name: 'High film' }).closest('tr')!
  expect(within(row()).getByText('0.00')).toBeInTheDocument()
  fireEvent.change(screen.getByRole('combobox', { name: 'Compare against' }), { target: { value: 'media' } })
  expect(within(row()).getByText('+1.00')).toBeInTheDocument()
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'High film' } })
  expect(within(row()).getByText('+1.00')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Low film' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'High film' }))
  expect(useAppStore.getState().selectedTitleId).toBe('b')
  expect(useAppStore.getState().isDetailDrawerOpen).toBe(true)
})

it('recomputes for the current library and handles an empty scope', () => {
  useAppStore.setState({ titles })
  render(<RatingDistribution settings={{ scope: 'tv' }} />)
  fireEvent.click(screen.getByRole('button', { name: 'Normalized' }))
  expect(screen.queryByRole('button', { name: 'High film' })).not.toBeInTheDocument()
  act(() => useAppStore.setState({ titles: [] }))
  expect(screen.getByText('No rated titles in this scope yet.')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'High series' })).not.toBeInTheDocument()
})
