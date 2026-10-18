import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it } from 'vitest'
import { useAppStore } from 'src/store/useAppStore'
import { mockTitles, type Title, type WatchStatus } from 'src/store/mockData'
import { Lists } from './Lists'

const initialState = useAppStore.getState()

function titleWith(id: string, name: string, status: WatchStatus): Title {
  return { ...mockTitles[0], id, title: name, status }
}

beforeEach(() => {
  useAppStore.setState(initialState, true)
  useAppStore.setState({
    lists: [],
    listMemberships: {},
    titles: [
      titleWith('a', 'Queued Film', 'watchlist'),
      titleWith('b', 'Midway Show', 'watching'),
      titleWith('c', 'Finished Film', 'watched'),
    ],
  })
})
afterEach(() => { cleanup(); useAppStore.setState(initialState, true) })

it('shows the automatic lists even when no custom list exists', () => {
  render(<Lists />)
  expect(screen.getByRole('button', { name: /Watchlist/ })).toHaveTextContent('1 title')
  expect(screen.getByRole('button', { name: /Currently Watching/ })).toHaveTextContent('1 title')
  expect(screen.getByRole('button', { name: 'Create your first list' })).toBeInTheDocument()
})

it('derives smart list members from status, without delete or remove controls', () => {
  render(<Lists />)
  fireEvent.click(screen.getByRole('button', { name: /Watchlist/ }))
  expect(useAppStore.getState().selectedListId).toBe('smart:watchlist')
  expect(screen.getByRole('button', { name: /Queued Film/ })).toBeInTheDocument()
  expect(screen.queryByText('Midway Show')).not.toBeInTheDocument()
  expect(screen.queryByText('Finished Film')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /Delete list/ })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /^Remove / })).not.toBeInTheDocument()
})

it('follows status changes live and opens directly from a smart list id', () => {
  useAppStore.getState().openListDetail('smart:watching')
  render(<Lists />)
  expect(screen.getByRole('heading', { name: 'Currently Watching' })).toBeInTheDocument()
  expect(screen.getByText('Midway Show')).toBeInTheDocument()

  act(() => {
    useAppStore.setState((s) => ({ titles: s.titles.map((t) => (t.id === 'b' ? { ...t, status: 'watched' } : t)) }))
  })
  expect(screen.queryByText('Midway Show')).not.toBeInTheDocument()
  expect(screen.getByText(/Nothing in progress/)).toBeInTheDocument()
})
